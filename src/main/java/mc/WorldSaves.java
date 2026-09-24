package mc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Světy na disku: složka na svět, metadata a přenos jediného starého světa.
 *
 * ---------------------------------------------------------------------------
 * USPOŘÁDÁNÍ. Dřív byl jeden svět v saves/world.dat. Teď má každý svět vlastní
 * složku:
 *
 *   saves/&lt;složka&gt;/world.dat    uložený svět (formát WorldStorage, NEZMĚNĚNÝ)
 *   saves/&lt;složka&gt;/world.json   metadata: jméno, seed, časy
 *   saves/&lt;složka&gt;/icon.png     náhled (volitelný, zapisuje ho Thumbnails)
 *
 * Jméno složky je odvozené od jména světa, ale NENÍ to ono - jméno se dá napsat
 * jakkoliv a souborové systémy to neunesou (viz folderFor). Skutečné jméno je
 * ve world.json a dvě složky klidně smí mít světy stejného jména.
 *
 * ⚠️ SEED SE V JSON UKLÁDÁ JAKO ŘETĚZEC. Json čte čísla jako double a ten má
 * 53bitovou mantisu - seed nad 2^53 by se načetl jako jiné číslo, tedy jako
 * úplně jiný svět. V uvozovkách projde přesně.
 *
 * ⚠️ POŠKOZENÁ METADATA NESMÍ SVĚT SCHOVAT. world.dat je to jediné, co nejde
 * dopočítat; jméno i časy jsou vedlejší. Když world.json nejde přečíst, zkusí
 * se jeho záloha .bak a pak se údaje odvodí ze složky a z času souboru - svět
 * je pořád v seznamu a pořád se dá hrát.
 *
 * ⚠️ MIGRACE STARÉHO SVĚTA NIKDY NEMAŽE ZDROJ. Postup je kopie -> ověření
 * bajt po bajtu -> metadata -> přejmenování složky -> teprve nakonec
 * přejmenování starého souboru na world.dat.migrated. Pád kdekoliv uprostřed
 * nechá starý soubor tam, kde byl, a příští spuštění migraci dokončí nebo
 * zopakuje. Smazat starý soubor musí uživatel sám, až si svět ověří.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, takže jde celé otestovat headless (viz WorldSavesTest).
 */
public final class WorldSaves {

    /** Kořen všech světů, relativně k pracovnímu adresáři - jako textures/. */
    public static final Path ROOT = Path.of("saves");

    public static final String WORLD_FILE = "world.dat";
    public static final String META_FILE  = "world.json";
    public static final String ICON_FILE  = "icon.png";

    /** Jméno, které dostane svět přenesený ze starého saves/world.dat. */
    public static final String LEGACY_NAME = "Old World";

    /** Jméno, které dostane svět bez jména. */
    public static final String DEFAULT_NAME = "New World";

    /** Delší jméno by se nevešlo do seznamu světů ani na tlačítko. */
    public static final int MAX_NAME_LENGTH = 32;

    /**
     * Verze formátu world.json.
     *
     * Historie: 1 = jméno, seed a časy; 2 = k tomu herní mód (gameMode).
     *
     * ⚠️ Zvýšeno kvůli módu schválně, i když by chybějící klíč starší čtečka
     * jen ignorovala. Právě proto: starší build by creative svět tiše hrál
     * jako survival. S vyšším formátem aspoň napíše "format 2 je novejsi",
     * a svět se pořád načte - seed je v souboru od formátu 1.
     */
    public static final int FORMAT = 2;

    /** Dočasná složka migrace. Tečka na začátku = seznam světů ji přeskočí. */
    static final String MIGRATING_DIR = ".migrating";

    /** Přípona, kterou dostane starý soubor po přenesení. */
    static final String MIGRATED_SUFFIX = ".migrated";

    /** Složka, když z jména nezbude nic použitelného. */
    static final String FALLBACK_FOLDER = "World";

    /** Znaky, které Windows (a leckde i ostatní) v názvu souboru neunesou. */
    private static final String FORBIDDEN = "<>:\"/\\|?*";

    /**
     * Jména zařízení ve Windows. Soubor takového jména nejde založit ani
     * s příponou (con.txt je pořád CON), a to bez ohledu na velikost písmen.
     */
    private static final Set<String> RESERVED_NAMES = reservedNames();

    /** Od naposledy hraného; při shodě podle jména, pak podle složky. */
    private static final Comparator<WorldInfo> ORDER =
            Comparator.comparingLong((WorldInfo w) -> w.lastPlayed()).reversed()
                    .thenComparing(WorldInfo::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(WorldInfo::folder, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(WorldInfo::folder);

    private WorldSaves() {}

    // ------------------------------------------------------------------
    // jeden svět
    // ------------------------------------------------------------------

    /**
     * Jeden svět na disku. folder je název složky (ne jméno světa), seedText
     * je to, co uživatel při zakládání napsal do políčka Seed (prázdné =
     * seed byl náhodný), časy jsou v milisekundách jako System.currentTimeMillis().
     */
    public record WorldInfo(String folder, String name, long seed, String seedText, GameMode mode,
                            long created, long lastPlayed, Path dir) {

        /**
         * Bez uvedeného módu je svět survival - stejně jako světy založené
         * dřív, než creative vůbec existoval. Je to táž úmluva jako
         * u chybějícího klíče ve world.json, jen v kódu.
         */
        public WorldInfo(String folder, String name, long seed, String seedText,
                         long created, long lastPlayed, Path dir)
        {
            this(folder, name, seed, seedText, GameMode.SURVIVAL, created, lastPlayed, dir);
        }

        public Path worldFile() { return dir.resolve(WORLD_FILE); }
        public Path iconFile()  { return dir.resolve(ICON_FILE); }
        public Path metaFile()  { return dir.resolve(META_FILE); }
    }

    /** Co se stalo se starým saves/world.dat. */
    public enum Migration {
        /** Starý soubor tam není (nebo se přenos nepovedl a zůstal, kde byl). */
        NONE,
        /** Přenesen do vlastní složky. */
        MIGRATED,
        /** Složka už z minula existovala, jen se dokončilo přejmenování souboru. */
        FINISHED_EARLIER
    }

    /** Kroky migrace - JEN PRO TESTY, aby šlo simulovat pád uprostřed. */
    enum Step { COPIED, VERIFIED, META_WRITTEN, FOLDER_RENAMED }

    /** Odkud se svět přenesl. Do world.json jde jen u přenesených světů. */
    private record Migrated(String from, long bytes, long crc32) {}

    /**
     * Načtená metadata. name, created a lastPlayed smí být null - to znamená
     * "ve world.json to nebylo, doplní se odjinud". seed null být nesmí:
     * bez něj by svět vypadal jinak, takže soubor, ve kterém chybí, je
     * nepoužitelný a sáhne se po záloze.
     */
    private record Meta(String name, long seed, String seedText, GameMode mode,
                        Long created, Long lastPlayed, Migrated migrated) {}

    // ------------------------------------------------------------------
    // seznam světů
    // ------------------------------------------------------------------

    /**
     * Všechny světy v kořeni, od naposledy hraného.
     *
     * Svět = podsložka, ve které je world.json nebo world.dat. Složky začínající
     * tečkou se přeskakují (.migrating a cokoliv skrytého) a soubory přímo
     * v kořeni taky - starý saves/world.dat tedy v seznamu není, dokud ho
     * migrateLegacy() nepřenese.
     */
    public static List<WorldInfo> list(Path root)
    {
        List<WorldInfo> worlds = new ArrayList<>();

        if(!Files.isDirectory(root))
        {
            return worlds;
        }

        try(DirectoryStream<Path> entries = Files.newDirectoryStream(root))
        {
            for(Path entry : entries)
            {
                Path fileName = entry.getFileName();

                if(fileName == null || fileName.toString().startsWith(".") || !Files.isDirectory(entry))
                {
                    continue;
                }

                if(Files.isRegularFile(entry.resolve(META_FILE))
                        || Files.isRegularFile(entry.resolve(WORLD_FILE)))
                {
                    worlds.add(readInfo(entry));
                }
            }
        }
        catch(IOException e)
        {
            System.err.println("Svety v " + root + " nejdou vypsat: " + e);
        }

        worlds.sort(ORDER);
        return worlds;
    }

    /**
     * Údaje o jednom světě. Když world.json chybí nebo nejde přečíst, zkusí se
     * jeho záloha a nakonec se odvodí ze složky - svět se kvůli metadatům
     * nesmí ztratit.
     */
    private static WorldInfo readInfo(Path dir)
    {
        String folder = dir.getFileName().toString();
        Path metaFile = dir.resolve(META_FILE);
        Path worldFile = dir.resolve(WORLD_FILE);

        Meta meta = null;

        if(Files.isRegularFile(metaFile))
        {
            List<String> problems = new ArrayList<>();
            meta = readMeta(metaFile, problems);
            report(metaFile, problems);
        }

        if(meta == null)
        {
            Path backup = SafeFiles.backupOf(metaFile);

            if(Files.isRegularFile(backup))
            {
                List<String> problems = new ArrayList<>();
                meta = readMeta(backup, problems);
                report(backup, problems);

                if(meta != null)
                {
                    System.err.println("Svet " + folder + ": udaje se berou ze zalohy " + backup);
                }
            }
        }

        // Čas souboru je jediné, co o neznámém světě víme - a je to lepší
        // odhad než "právě teď", protože řazení podle něj aspoň zhruba sedí.
        long fallbackTime = lastModified(Files.isRegularFile(worldFile) ? worldFile : dir);

        if(meta == null)
        {
            System.err.println("Svet " + folder + ": metadata chybi nebo nejdou precist"
                    + " - jmeno podle slozky a seed " + World.DEFAULT_SEED);
            meta = new Meta(null, World.DEFAULT_SEED, "", GameMode.SURVIVAL, null, null, null);
        }

        return new WorldInfo(folder,
                meta.name() == null ? cleanName(folder) : meta.name(),
                meta.seed(),
                meta.seedText(),
                meta.mode(),
                meta.created() == null ? fallbackTime : meta.created(),
                meta.lastPlayed() == null ? fallbackTime : meta.lastPlayed(),
                dir);
    }

    // ------------------------------------------------------------------
    // zakládání, hraní, mazání
    // ------------------------------------------------------------------

    /**
     * Založí složku nového světa a zapíše metadata. Vrací null, když se to
     * nepovedlo (důvod jde na stderr) - hra kvůli tomu nemá padat.
     */
    public static WorldInfo create(Path root, String displayName, long seed, String seedText, long now)
    {
        return create(root, displayName, seed, seedText, GameMode.SURVIVAL, now);
    }

    /**
     * Totéž s herním módem. Mód je vlastnost světa a zapisuje se sem jednou
     * provždy - za běhu se nemění, viz GameMode.
     */
    public static WorldInfo create(Path root, String displayName, long seed, String seedText,
                                   GameMode mode, long now)
    {
        String name = cleanName(displayName);

        try
        {
            Files.createDirectories(root);

            String folder = uniqueFolder(root, folderFor(name));
            Path dir = root.resolve(folder);

            // Bez REPLACE: kdyby složka mezitím vznikla, je to cizí svět.
            Files.createDirectory(dir);

            WorldInfo info = new WorldInfo(folder, name, seed,
                    seedText == null ? "" : seedText,
                    mode == null ? GameMode.SURVIVAL : mode, now, now, dir);

            if(!writeMeta(info, null))
            {
                // Prázdná složka bez metadat by se v seznamu tvářila jako svět.
                deleteQuietly(dir);
                return null;
            }

            return info;
        }
        catch(IOException e)
        {
            System.err.println("Novy svet v " + root + " nejde zalozit: " + e);
            return null;
        }
    }

    /**
     * Zapíše nový čas posledního hraní a vrátí aktualizovaný záznam.
     *
     * Migrační údaje se přitom zachovají - podle nich se pozná, že starý
     * soubor už je přenesený, i když se ve světě mezitím hrálo.
     */
    public static WorldInfo touch(WorldInfo w, long now)
    {
        WorldInfo updated = new WorldInfo(w.folder(), w.name(), w.seed(), w.seedText(), w.mode(),
                w.created(), now, w.dir());

        writeMeta(updated, migrationOf(w.metaFile()));

        // I když zápis selže, hra může běžet dál - jen se příště seřadí jinak.
        return updated;
    }

    /**
     * Smaže celou složku světa.
     *
     * ⚠️ POJISTKA: maže se jen složka ležící PŘÍMO v root. Záznam odjinud
     * (ručně poskládaný, s ".." v cestě, nebo rovnou root sám) se odmítne -
     * rekurzivní mazání je ta nejhorší věc, kterou může překlep v cestě udělat.
     */
    public static boolean delete(Path root, WorldInfo w)
    {
        Path dir = w.dir().toAbsolutePath().normalize();
        Path base = root.toAbsolutePath().normalize();

        if(dir.equals(base) || !base.equals(dir.getParent()))
        {
            System.err.println("Smazani odmitnuto: " + w.dir() + " neni slozka primo v " + root);
            return false;
        }

        try
        {
            deleteRecursively(dir);
            return true;
        }
        catch(IOException e)
        {
            System.err.println("Svet " + w.folder() + " nejde smazat: " + e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // jména a složky
    // ------------------------------------------------------------------

    /**
     * Jméno světa, jak se smí zobrazit: bez mezer na krajích, jen ASCII 32-126
     * (font nic jiného neumí) a nejvýš MAX_NAME_LENGTH znaků. Z prázdného
     * jména se stane DEFAULT_NAME.
     */
    public static String cleanName(String displayName)
    {
        String source = displayName == null ? "" : displayName;
        StringBuilder out = new StringBuilder(source.length());

        for(int i = 0; i < source.length(); i++)
        {
            char c = source.charAt(i);

            if(c >= 32 && c <= 126)
            {
                out.append(c);
            }
        }

        String name = out.toString().trim();

        if(name.length() > MAX_NAME_LENGTH)
        {
            name = name.substring(0, MAX_NAME_LENGTH).trim();
        }

        return name.isEmpty() ? DEFAULT_NAME : name;
    }

    /**
     * Název složky pro jméno světa, který unese Windows, Linux i macOS.
     *
     * ⚠️ Tři různé pasti najednou:
     *   - znaky &lt;&gt;:"/\|?* a řídicí znaky se ve Windows do jména nesmí;
     *   - jména zařízení (CON, NUL, COM1...) nejdou založit ani s příponou;
     *   - tečky a mezery na konci Windows tiše zahodí, takže "World." by se
     *     uložilo jako "World" a příště by se hledalo marně.
     * K tomu tečka na začátku: to je na Unixu skrytá složka a seznam světů
     * ji přeskakuje, takže by svět zmizel.
     */
    public static String folderFor(String displayName)
    {
        String source = displayName == null ? "" : displayName;
        StringBuilder out = new StringBuilder(source.length());

        for(int i = 0; i < source.length() && out.length() < MAX_NAME_LENGTH; i++)
        {
            char c = source.charAt(i);
            out.append(c < 32 || c == 127 || FORBIDDEN.indexOf(c) >= 0 ? '_' : c);
        }

        while(out.length() > 0 && (endsWith(out, '.') || endsWith(out, ' ')))
        {
            out.setLength(out.length() - 1);
        }

        int start = 0;

        while(start < out.length() && out.charAt(start) == ' ')
        {
            start++;
        }

        // Tečky na začátku ne, ale zahodit je taky nejde ("...a" a "a" by byly
        // dvě jména jedné složky), takže se z nich stane podtržítko.
        for(int i = start; i < out.length() && out.charAt(i) == '.'; i++)
        {
            out.setCharAt(i, '_');
        }

        String folder = out.substring(start);

        if(folder.isEmpty())
        {
            return FALLBACK_FOLDER;
        }

        // Podtržítko za kmen, ne na konec: "con.txt_" má pořád kmen "con".
        int dot = folder.indexOf('.');

        if(dot < 0)
        {
            return RESERVED_NAMES.contains(stem(folder)) ? folder + "_" : folder;
        }

        return RESERVED_NAMES.contains(stem(folder.substring(0, dot)))
                ? folder.substring(0, dot) + "_" + folder.substring(dot)
                : folder;
    }

    /**
     * base, nebo "base (2)", "base (3)"... když už je obsazené.
     *
     * ⚠️ Porovnává se BEZ OHLEDU NA VELIKOST PÍSMEN. Windows "World" a "world"
     * nerozlišuje, takže by se druhý svět nasypal do složky prvního.
     */
    public static String uniqueFolder(Path root, String base)
    {
        Set<String> taken = new HashSet<>();

        if(Files.isDirectory(root))
        {
            try(DirectoryStream<Path> entries = Files.newDirectoryStream(root))
            {
                for(Path entry : entries)
                {
                    Path name = entry.getFileName();

                    if(name != null)
                    {
                        taken.add(name.toString().toLowerCase(Locale.ROOT));
                    }
                }
            }
            catch(IOException e)
            {
                // Radši přidat číslo zbytečně než přepsat cizí svět.
                System.err.println("Slozky v " + root + " nejdou vypsat: " + e);
            }
        }

        if(!taken.contains(base.toLowerCase(Locale.ROOT)))
        {
            return base;
        }

        for(int n = 2; ; n++)
        {
            String candidate = base + " (" + n + ")";

            if(!taken.contains(candidate.toLowerCase(Locale.ROOT)))
            {
                return candidate;
            }
        }
    }

    // ------------------------------------------------------------------
    // přenos starého saves/world.dat
    // ------------------------------------------------------------------

    /**
     * Přenese starý saves/world.dat do vlastní složky, pokud tam ještě je.
     *
     * ⚠️ BEZE ZTRÁTY DAT. Nejdřív se udělá kopie v saves/.migrating, ověří se
     * bajt po bajtu, přibydou metadata a teprve pak se dočasná složka
     * přejmenuje na světovou. Starý soubor se NEMAŽE, jen se úplně nakonec
     * přejmenuje na world.dat.migrated - smazat ho může uživatel sám, až si
     * přenesený svět ověří.
     *
     * Pád uprostřed nevadí: zbytek .migrating se příště smaže a přenos se
     * zopakuje, a když už světová složka existuje (pád mezi přejmenováním
     * složky a souboru), jen se dokončí přejmenování souboru (FINISHED_EARLIER).
     *
     * Seed přeneseného světa je World.DEFAULT_SEED - starý svět vznikl s pevným
     * seedem 12345, takže terén pod stavbami zůstane přesně ten samý.
     */
    public static Migration migrateLegacy(Path root)
    {
        return migrateLegacy(root, null);
    }

    /** failAfter je JEN PRO TESTY: po daném kroku se tváří, že selhal disk. */
    static Migration migrateLegacy(Path root, Step failAfter)
    {
        Path legacy = root.resolve(WORLD_FILE);

        if(!Files.isRegularFile(legacy))
        {
            return Migration.NONE;
        }

        Path temp = root.resolve(MIGRATING_DIR);

        try
        {
            // Poškozený soubor se přenáší stejně jako každý jiný - jestli se
            // dá načíst, řeší až WorldStorage.load(). Tady jsou to jen bajty.
            byte[] data = Files.readAllBytes(legacy);
            long crc32 = crc32(data);
            long time = lastModified(legacy);

            Path already = findMigrated(root, data, crc32);

            if(already != null)
            {
                Path renamed = renameLegacy(legacy);
                System.err.println("Stary svet " + legacy + " uz byl prenesen do "
                        + already.getFileName() + "; soubor je ted " + renamed);
                return Migration.FINISHED_EARLIER;
            }

            deleteRecursively(temp);
            Files.createDirectories(temp);

            Path copy = temp.resolve(WORLD_FILE);

            // COPY_ATTRIBUTES: čas souboru je jediná stopa po tom, kdy se svět
            // hrál naposledy, a odvozená metadata ho pak najdou i na kopii.
            Files.copy(legacy, copy, StandardCopyOption.COPY_ATTRIBUTES);
            fail(failAfter, Step.COPIED);

            if(Files.mismatch(legacy, copy) != -1 || !Arrays.equals(data, Files.readAllBytes(copy)))
            {
                throw new IOException("kopie " + copy + " neni shodna se zdrojem");
            }

            fail(failAfter, Step.VERIFIED);

            WorldInfo draft = new WorldInfo(MIGRATING_DIR, LEGACY_NAME, World.DEFAULT_SEED,
                    "", time, time, temp);

            if(!writeMeta(draft, new Migrated(WORLD_FILE, data.length, crc32)))
            {
                throw new IOException("metadata do " + temp.resolve(META_FILE) + " nejdou zapsat");
            }

            fail(failAfter, Step.META_WRITTEN);

            String folder = uniqueFolder(root, folderFor(LEGACY_NAME));

            // Bez REPLACE_EXISTING a bez ATOMIC_MOVE: obojí umí cizí složku
            // nebo soubor tiše přepsat, a tady je celý smysl nepřepsat nic.
            Files.move(temp, root.resolve(folder));
            fail(failAfter, Step.FOLDER_RENAMED);

            Path renamed = renameLegacy(legacy);
            System.err.println("Stary svet " + legacy + " je prenesen do " + root.resolve(folder)
                    + "; puvodni soubor zustal jako " + renamed
                    + " - smazat ho muzes rucne, az si svet overis.");

            return Migration.MIGRATED;
        }
        catch(IOException | RuntimeException e)
        {
            System.err.println("Prenos stareho sveta " + legacy + " se nepovedl: " + e
                    + " - soubor zustava, kde byl");

            try
            {
                deleteRecursively(temp);
            }
            catch(IOException ignored)
            {
                // Zbytek .migrating nevadí, příští spuštění ho smaže.
            }

            return Migration.NONE;
        }
    }

    /**
     * Složka, do které se tenhle starý soubor už jednou přenesl, nebo null.
     *
     * Hledá se dvěma způsoby, protože obojí je pravda: hned po přenosu je
     * world.dat ve složce bajt po bajtu tentýž, ale jakmile se ve světě hrálo,
     * je jiný - a to, že vznikl z tohohle souboru, řeknou velikost a kontrolní
     * součet uložené v metadatech.
     *
     * ⚠️ Složky s tečkou se přeskakují: v .migrating leží po pádu kopie
     * s týmiž metadaty, a kdyby se brala jako hotový přenos, svět by zůstal
     * schovaný ve skryté složce a starý soubor by se přejmenoval.
     */
    private static Path findMigrated(Path root, byte[] data, long crc32) throws IOException
    {
        try(DirectoryStream<Path> entries = Files.newDirectoryStream(root))
        {
            for(Path dir : entries)
            {
                Path fileName = dir.getFileName();

                if(fileName == null || fileName.toString().startsWith(".") || !Files.isDirectory(dir))
                {
                    continue;
                }

                Meta meta = migrationMeta(dir.resolve(META_FILE));

                if(meta == null || meta.migrated() == null || !WORLD_FILE.equals(meta.migrated().from()))
                {
                    continue;
                }

                Path dat = dir.resolve(WORLD_FILE);

                if(Files.isRegularFile(dat) && Files.size(dat) == data.length
                        && Arrays.equals(data, Files.readAllBytes(dat)))
                {
                    return dir;
                }

                if(meta.migrated().bytes() == data.length && meta.migrated().crc32() == crc32)
                {
                    return dir;
                }
            }
        }

        return null;
    }

    /**
     * Přejmenuje starý soubor na world.dat.migrated, a když takový je,
     * na -2, -3...
     *
     * ⚠️ Nikdy nepřepsat: Files.move bez REPLACE_EXISTING selže, kdežto
     * ATOMIC_MOVE by na Windows i na Linuxu existující soubor přepsal.
     */
    private static Path renameLegacy(Path legacy) throws IOException
    {
        String base = legacy.getFileName() + MIGRATED_SUFFIX;
        Path target = legacy.resolveSibling(base);

        for(int n = 2; Files.exists(target, LinkOption.NOFOLLOW_LINKS); n++)
        {
            target = legacy.resolveSibling(base + "-" + n);
        }

        Files.move(legacy, target);
        return target;
    }

    private static void fail(Step failAfter, Step step) throws IOException
    {
        if(failAfter == step)
        {
            throw new IOException("simulovana chyba po kroku " + step);
        }
    }

    // ------------------------------------------------------------------
    // world.json
    // ------------------------------------------------------------------

    /**
     * Zapíše metadata atomicky. Soubor, který nejde celý načíst (poškozený
     * nebo novějšího formátu), se přitom zazálohuje do .bak - v paměti není
     * všechno, co v něm je, a zápis by ten zbytek tiše smazal.
     */
    private static boolean writeMeta(WorldInfo w, Migrated migrated)
    {
        return SafeFiles.writeAtomically(w.metaFile(), toJson(w, migrated),
                WorldSaves::readsCompletely, "Svet");
    }

    /** Přečte se soubor bez jediné výhrady? Nic nevypisuje. */
    private static boolean readsCompletely(Path file)
    {
        List<String> problems = new ArrayList<>();
        return readMeta(file, problems) != null && problems.isEmpty();
    }

    /**
     * Metadata jako JSON. Odsazení dvě mezery, "\n" i na Windows a nový řádek
     * na konci - soubor má vypadat všude stejně, jako textures/blocks.json.
     */
    private static String toJson(WorldInfo w, Migrated migrated)
    {
        StringBuilder out = new StringBuilder();

        out.append("{\n");
        out.append("  \"format\": ").append(FORMAT).append(",\n");
        out.append("  \"name\": ").append(Json.quote(w.name())).append(",\n");

        // ⚠️ Seed v uvozovkách - jako číslo by se nad 2^53 načetl nepřesně.
        out.append("  \"seed\": \"").append(w.seed()).append("\",\n");
        out.append("  \"seedText\": ").append(Json.quote(w.seedText())).append(",\n");

        // Herní mód. Textem, ne číslem: soubor se dá otevřít a přečíst,
        // a přibude-li někdy třetí mód, nepřečíslují se ty dosavadní.
        out.append("  \"gameMode\": ").append(Json.quote(w.mode().id())).append(",\n");

        out.append("  \"created\": ").append(w.created()).append(",\n");
        out.append("  \"lastPlayed\": ").append(w.lastPlayed());

        if(migrated != null)
        {
            out.append(",\n  \"migratedFrom\": ").append(Json.quote(migrated.from()));
            out.append(",\n  \"migratedBytes\": ").append(migrated.bytes());
            out.append(",\n  \"migratedCrc32\": ").append(migrated.crc32());
        }

        return out.append("\n}\n").toString();
    }

    /** Migrační údaje ze souboru, nebo null. Nic nevypisuje. */
    /**
     * Migrační údaje světa. Když world.json nejde přečíst, vezmou se ze
     * zálohy .bak - z té, ze které readInfo() vzala jméno a seed.
     *
     * ⚠️ Bez toho touch() přes poškozený world.json zapsal metadata BEZ
     * migrace, a starý saves/world.dat by se pak při příštím startu přenesl
     * podruhé jako "Old World (2)".
     */
    private static Migrated migrationOf(Path file)
    {
        Meta meta = migrationMeta(file);

        if(meta == null)
        {
            meta = migrationMeta(SafeFiles.backupOf(file));
        }

        return meta == null ? null : meta.migrated();
    }

    private static Meta migrationMeta(Path file)
    {
        return Files.isRegularFile(file) ? readMeta(file, new ArrayList<>()) : null;
    }

    /**
     * Metadata ze souboru, nebo null, když se nedají použít. Výhrady (novější
     * formát, chybějící jméno, upravené jméno) jdou do problems - volající je
     * buď vypíše, nebo si podle nich pozná, že je potřeba záloha.
     */
    private static Meta readMeta(Path file, List<String> problems)
    {
        String json;

        try
        {
            json = Files.readString(file, StandardCharsets.UTF_8);
        }
        catch(IOException e)
        {
            problems.add("nejde precist: " + e);
            return null;
        }

        try
        {
            return parseMeta(json, problems);
        }
        catch(RuntimeException e)
        {
            // Json hlásí chyby jako IllegalArgumentException s řádkem a sloupcem.
            problems.add(e.getMessage());
            return null;
        }
    }

    private static Meta parseMeta(String json, List<String> problems)
    {
        if(!(Json.parse(json) instanceof Map<?, ?> root))
        {
            throw new IllegalArgumentException("koren neni objekt");
        }

        if(!(root.get("format") instanceof Double format))
        {
            problems.add("chybi cislo format");
        }
        else if(format > FORMAT)
        {
            // Stejně jako u GENERATOR_VERSION: varovat, ale číst - neznámá
            // pole se ignorují a to podstatné (seed) je tu od formátu 1.
            problems.add("format " + number(format) + " je novejsi nez " + FORMAT
                    + " - nezname udaje se ignoruji");
        }

        Long seed = longValue(root.get("seed"));

        if(seed == null)
        {
            // Bez seedu by svět vypadal jinak, takže radši sáhnout po záloze.
            throw new IllegalArgumentException("chybi seed (cele cislo v uvozovkach)");
        }

        String name = null;

        if(root.get("name") instanceof String raw)
        {
            name = cleanName(raw);

            if(!name.equals(raw))
            {
                problems.add("jmeno \"" + raw + "\" se nedalo pouzit cele, bude z nej \"" + name + "\"");
            }
        }
        else if(root.containsKey("name"))
        {
            problems.add("name neni text - jmeno bude podle slozky");
        }
        else
        {
            problems.add("chybi name - jmeno bude podle slozky");
        }

        String seedText = "";

        if(root.get("seedText") instanceof String text)
        {
            seedText = text;
        }
        else if(root.containsKey("seedText"))
        {
            problems.add("seedText neni text - bere se prazdny");
        }

        Long created = longValue(root.get("created"));
        Long lastPlayed = longValue(root.get("lastPlayed"));

        if(created == null || lastPlayed == null)
        {
            problems.add("chybi created nebo lastPlayed - vezme se cas souboru");
        }

        return new Meta(name, seed, seedText, gameMode(root, problems),
                created, lastPlayed, migrated(root, problems));
    }

    /**
     * Herní mód z metadat.
     *
     * ⚠️ CHYBĚJÍCÍ KLÍČ NENÍ CHYBA. Světy z formátu 1 (a migrovaný starý svět)
     * ho nemají a survival je přesně to, čím dosud byly - mlčky tedy survival.
     * Překlep nebo cizí hodnota už chyba je: svět by se dal hrát v jiném módu,
     * než jakým vznikl, a o tom musí být vidět zpráva. Svět se i tak načte,
     * ze stejného důvodu jako u GENERATOR_VERSION.
     */
    private static GameMode gameMode(Map<?, ?> root, List<String> problems)
    {
        Object raw = root.get("gameMode");

        if(raw == null)
        {
            return GameMode.SURVIVAL;
        }

        if(raw instanceof String id && GameMode.known(id))
        {
            return GameMode.byId(id);
        }

        problems.add("gameMode \"" + raw + "\" neznam - svet se bude hrat jako "
                + GameMode.SURVIVAL.id());
        return GameMode.SURVIVAL;
    }

    private static Migrated migrated(Map<?, ?> root, List<String> problems)
    {
        if(!(root.get("migratedFrom") instanceof String from))
        {
            return null;
        }

        Long bytes = longValue(root.get("migratedBytes"));
        Long crc32 = longValue(root.get("migratedCrc32"));

        if(bytes == null || crc32 == null)
        {
            problems.add("migratedBytes nebo migratedCrc32 chybi - pozna se jen shodny soubor");
            return new Migrated(from, -1, -1);
        }

        return new Migrated(from, bytes, crc32);
    }

    /**
     * Celé číslo z JSON hodnoty: z řetězce (tak se píše seed, aby neztratil
     * přesnost) nebo z čísla, když je celé a double ho unese přesně.
     */
    private static Long longValue(Object value)
    {
        if(value instanceof String s)
        {
            try
            {
                return Long.parseLong(s.trim());
            }
            catch(NumberFormatException e)
            {
                return null;
            }
        }

        if(value instanceof Double d && d == Math.rint(d) && Math.abs(d) <= 9007199254740992.0)
        {
            return (long) (double) d;
        }

        return null;
    }

    /** Číslo do zprávy: celé bez ".0". */
    private static String number(double value)
    {
        return value == Math.rint(value) && Math.abs(value) < 1e15
                ? Long.toString((long) value)
                : Double.toString(value);
    }

    private static void report(Path file, List<String> problems)
    {
        for(String problem : problems)
        {
            System.err.println("Svet " + file + ": " + problem);
        }
    }

    // ------------------------------------------------------------------
    // práce se soubory
    // ------------------------------------------------------------------

    private static long lastModified(Path file)
    {
        try
        {
            return Files.getLastModifiedTime(file).toMillis();
        }
        catch(IOException e)
        {
            return System.currentTimeMillis();
        }
    }

    private static long crc32(byte[] data)
    {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /** Smaže složku i s obsahem. Co neexistuje, se mlčky přeskočí. */
    private static void deleteRecursively(Path path) throws IOException
    {
        if(Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
        {
            return;
        }

        Files.walkFileTree(path, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException
            {
                if(failure != null)
                {
                    throw failure;
                }

                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            deleteRecursively(path);
        }
        catch(IOException ignored)
        {
            // Zbytek po neúspěšném zakládání nevadí; unikátní jméno ho obejde.
        }
    }

    // ------------------------------------------------------------------
    // drobnosti
    // ------------------------------------------------------------------

    private static boolean endsWith(StringBuilder text, char c)
    {
        return text.length() > 0 && text.charAt(text.length() - 1) == c;
    }

    /** Kmen jména pro porovnání s jmény zařízení: Windows mezery na konci ignoruje. */
    private static String stem(String name)
    {
        return name.stripTrailing().toLowerCase(Locale.ROOT);
    }

    private static Set<String> reservedNames()
    {
        Set<String> names = new HashSet<>(Set.of("con", "prn", "aux", "nul"));

        for(int i = 0; i <= 9; i++)
        {
            names.add("com" + i);
            names.add("lpt" + i);
        }

        // Horní indexy jsou ve Windows platná jména zařízení taky (COM¹).
        for(String superscript : new String[]{"¹", "²", "³"})
        {
            names.add("com" + superscript);
            names.add("lpt" + superscript);
        }

        return names;
    }
}
