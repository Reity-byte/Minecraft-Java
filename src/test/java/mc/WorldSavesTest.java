package mc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * Overuje slozky svetu, jejich metadata a prenos stareho saves/world.dat.
 *
 * Tezistem je migrace: stary svet je to jedine, co nejde dopocitat, takze se
 * testuje na FABRIKOVANYCH datech (skutecny world.dat se zmenami bloku
 * i inventarem) a hlida se bajt po bajtu. K tomu pribyvaji pady uprostred
 * prenosu - kazdy krok se simuluje zvlast a po kazdem musi stary soubor
 * zustat nedotceny.
 *
 * Vsechno bezi v docasnem adresari, takze to nesaha na saves/ v repozitari.
 */
public class WorldSavesTest {

    static int failures = 0;

    /** Cas, na ktery se nastavuje stary soubor - vychazi z nej created i lastPlayed. */
    static final long LEGACY_TIME = 1_700_000_000_000L;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        names();
        folders();
        unique();
        createAndList();
        metadata();
        damagedMetadata();
        deleting();
        migrationBasics();
        migrationRepeat();
        migrationCrashes();
        migrationNeighbours();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ==================================================================
    // 1) jmena svetu
    // ==================================================================

    static void names() {
        System.out.println("\n-- cleanName --");

        check("mezery na krajich se orezou",
                WorldSaves.cleanName("  Moje mapa  ").equals("Moje mapa"),
                WorldSaves.cleanName("  Moje mapa  "));
        check("prazdne jmeno je " + WorldSaves.DEFAULT_NAME,
                WorldSaves.cleanName("").equals(WorldSaves.DEFAULT_NAME), "");
        check("jen mezery je taky vychozi jmeno",
                WorldSaves.cleanName("   ").equals(WorldSaves.DEFAULT_NAME), "");
        check("null je vychozi jmeno",
                WorldSaves.cleanName(null).equals(WorldSaves.DEFAULT_NAME), "");

        // Font je ASCII 32-126; cokoliv jineho by se kreslilo jako '?'.
        check("diakritika vypadne (font ji nema)",
                WorldSaves.cleanName("Kaňon").equals("Kaon"), WorldSaves.cleanName("Kaňon"));
        check("ridici znaky vypadnou",
                WorldSaves.cleanName("a\tb\nc").equals("abc"), WorldSaves.cleanName("a\tb\nc"));
        check("po vyhozeni znaku se jeste orizne",
                WorldSaves.cleanName("Svet é").equals("Svet"), WorldSaves.cleanName("Svet é"));
        check("jmeno jen z diakritiky je vychozi jmeno",
                WorldSaves.cleanName("éé").equals(WorldSaves.DEFAULT_NAME), "");

        check("interpunkce zustava",
                WorldSaves.cleanName("hello world!@#$%^&*()_+ 123").equals("hello world!@#$%^&*()_+ 123"), "");

        String longName = "x".repeat(40);
        check("dlouhe jmeno se zkrati na " + WorldSaves.MAX_NAME_LENGTH,
                WorldSaves.cleanName(longName).length() == WorldSaves.MAX_NAME_LENGTH,
                "" + WorldSaves.cleanName(longName).length());

        String cut = WorldSaves.cleanName("a".repeat(31) + "  bbb");
        check("po zkraceni nezbyde mezera na konci",
                cut.equals("a".repeat(31)), cut);
    }

    // ==================================================================
    // 2) nazvy slozek
    // ==================================================================

    static void folders() {
        System.out.println("\n-- folderFor --");

        check("obycejne jmeno projde beze zmeny",
                WorldSaves.folderFor("Moje mapa").equals("Moje mapa"), "");
        check("zakazane znaky Windows se nahradi podtrzitkem",
                WorldSaves.folderFor("a<b>c:d\"e/f\\g|h?i*j").equals("a_b_c_d_e_f_g_h_i_j"),
                WorldSaves.folderFor("a<b>c:d\"e/f\\g|h?i*j"));
        check("ridici znak se nahradi podtrzitkem",
                WorldSaves.folderFor("tab\there").equals("tab_here"), WorldSaves.folderFor("tab\there"));

        // Jmena zarizeni ve Windows - soubor takoveho jmena nejde zalozit.
        check("CON dostane podtrzitko", WorldSaves.folderFor("CON").equals("CON_"), "");
        check("con dostane podtrzitko i malymi", WorldSaves.folderFor("con").equals("con_"), "");
        check("com1 dostane podtrzitko", WorldSaves.folderFor("com1").equals("com1_"), "");
        check("LPT9 dostane podtrzitko", WorldSaves.folderFor("LPT9").equals("LPT9_"), "");
        check("aux.txt dostane podtrzitko za kmen",
                WorldSaves.folderFor("aux.txt").equals("aux_.txt"), WorldSaves.folderFor("aux.txt"));
        check("NUL.world.dat dostane podtrzitko za kmen",
                WorldSaves.folderFor("NUL.world.dat").equals("NUL_.world.dat"),
                WorldSaves.folderFor("NUL.world.dat"));
        check("console neni zakazane jmeno", WorldSaves.folderFor("console").equals("console"), "");
        check("com10 neni zakazane jmeno", WorldSaves.folderFor("com10").equals("com10"), "");

        check("tecka na konci se utne", WorldSaves.folderFor("World.").equals("World"), "");
        check("tecky a mezery na konci se utnou",
                WorldSaves.folderFor("World . . ").equals("World"), WorldSaves.folderFor("World . . "));
        check("samotna tecka je " + WorldSaves.FALLBACK_FOLDER,
                WorldSaves.folderFor(".").equals(WorldSaves.FALLBACK_FOLDER), "");
        check("dve tecky jsou " + WorldSaves.FALLBACK_FOLDER,
                WorldSaves.folderFor("..").equals(WorldSaves.FALLBACK_FOLDER), "");
        check("prazdne jmeno je " + WorldSaves.FALLBACK_FOLDER,
                WorldSaves.folderFor("").equals(WorldSaves.FALLBACK_FOLDER), "");
        check("null je " + WorldSaves.FALLBACK_FOLDER,
                WorldSaves.folderFor(null).equals(WorldSaves.FALLBACK_FOLDER), "");
        check("jen mezery jsou " + WorldSaves.FALLBACK_FOLDER,
                WorldSaves.folderFor("    ").equals(WorldSaves.FALLBACK_FOLDER), "");

        // Tecka na zacatku by byla skryta slozka a seznam svetu ji preskakuje.
        check("tecka na zacatku se nahradi podtrzitkem",
                WorldSaves.folderFor(".hidden").equals("_hidden"), WorldSaves.folderFor(".hidden"));
        check("vic tecek na zacatku taky",
                WorldSaves.folderFor("..a").equals("__a"), WorldSaves.folderFor("..a"));

        check("dlouhe jmeno se zkrati",
                WorldSaves.folderFor("x".repeat(100)).length() == WorldSaves.MAX_NAME_LENGTH,
                "" + WorldSaves.folderFor("x".repeat(100)).length());

        String[] nasty = {"", ".", "..", "...", "   ", "CON", "com1.dat", "a<b>c", "World...",
                ".a.", "x".repeat(100), "\t", "nul", "  .hidden  ",
                Character.toString(0) + "x"};   // NUL v nazvu souboru neunese nikdo
        boolean safe = true;
        String bad = "";

        for (String name : nasty) {
            String folder = WorldSaves.folderFor(name);
            boolean ok = !folder.isEmpty()
                    && !folder.startsWith(".")
                    && !folder.endsWith(".") && !folder.endsWith(" ")
                    && folder.chars().noneMatch(c -> c < 32 || c == 127 || "<>:\"/\\|?*".indexOf(c) >= 0);
            if (!ok) { safe = false; bad = name + " -> " + folder; }
        }

        check("zadne jmeno nedokaze vyrobit nepouzitelnou slozku", safe, bad);
    }

    // ==================================================================
    // 3) unikatni slozka
    // ==================================================================

    static void unique() throws IOException {
        System.out.println("\n-- uniqueFolder --");

        Path root = tempRoot("unique");
        check("kdyz korenu neni, nic neni obsazene",
                WorldSaves.uniqueFolder(root, "World").equals("World"), "");

        Files.createDirectories(root.resolve("World"));
        Files.createDirectories(root.resolve("old world"));
        Files.write(root.resolve("world.dat"), new byte[]{1, 2, 3});

        check("volny nazev se nemeni",
                WorldSaves.uniqueFolder(root, "New World").equals("New World"), "");
        check("obsazeny nazev dostane (2)",
                WorldSaves.uniqueFolder(root, "World").equals("World (2)"),
                WorldSaves.uniqueFolder(root, "World"));
        check("porovnava se bez ohledu na velikost pismen",
                WorldSaves.uniqueFolder(root, "world").equals("world (2)"),
                WorldSaves.uniqueFolder(root, "world"));
        check("obsazeny i s jinou velikosti pismen",
                WorldSaves.uniqueFolder(root, "Old World").equals("Old World (2)"),
                WorldSaves.uniqueFolder(root, "Old World"));
        check("soubor obsazuje nazev stejne jako slozka",
                WorldSaves.uniqueFolder(root, "world.dat").equals("world.dat (2)"),
                WorldSaves.uniqueFolder(root, "world.dat"));

        Files.createDirectories(root.resolve("World (2)"));
        check("kdyz je i (2), prijde (3)",
                WorldSaves.uniqueFolder(root, "World").equals("World (3)"),
                WorldSaves.uniqueFolder(root, "World"));
    }

    // ==================================================================
    // 4) zalozeni, seznam, razeni
    // ==================================================================

    static void createAndList() throws IOException {
        System.out.println("\n-- create / list / touch --");

        Path root = tempRoot("create");

        WorldSaves.WorldInfo alpha = WorldSaves.create(root, "Alpha", 42L, "42", 1000L);
        WorldSaves.WorldInfo beta = WorldSaves.create(root, "Beta", -7L, "hello", 2000L);
        WorldSaves.WorldInfo alpha2 = WorldSaves.create(root, "Alpha", 5L, "", 3000L);

        check("zalozeni vrati zaznam", alpha != null && beta != null && alpha2 != null, "");
        check("slozka i metadata existuji",
                Files.isDirectory(alpha.dir()) && Files.isRegularFile(alpha.metaFile()), "");
        check("druhy svet stejneho jmena dostane jinou slozku",
                alpha2.folder().equals("Alpha (2)") && alpha2.name().equals("Alpha"),
                alpha2.folder());
        check("cesty v zaznamu miri do slozky svetu",
                alpha.worldFile().equals(alpha.dir().resolve(WorldSaves.WORLD_FILE))
                        && alpha.iconFile().equals(alpha.dir().resolve(WorldSaves.ICON_FILE))
                        && alpha.metaFile().equals(alpha.dir().resolve(WorldSaves.META_FILE)), "");
        check("cerstvy svet ma created == lastPlayed",
                alpha.created() == 1000L && alpha.lastPlayed() == 1000L, "");

        List<WorldSaves.WorldInfo> list = WorldSaves.list(root);
        check("v seznamu jsou vsechny tri svety", list.size() == 3, "" + list.size());
        check("radi se od naposledy hraneho",
                folders(list).equals("Alpha (2),Beta,Alpha"), folders(list));

        WorldSaves.WorldInfo loaded = find(list, "Beta");
        check("seed prezije zapis a nacteni", loaded.seed() == -7L, "" + loaded.seed());
        check("text seedu prezije zapis a nacteni", loaded.seedText().equals("hello"), loaded.seedText());
        check("jmeno prezije zapis a nacteni", loaded.name().equals("Beta"), loaded.name());

        WorldSaves.WorldInfo played = WorldSaves.touch(alpha, 9000L);
        check("touch posune jen lastPlayed",
                played.lastPlayed() == 9000L && played.created() == 1000L, "");
        check("po touch je svet v seznamu prvni",
                folders(WorldSaves.list(root)).equals("Alpha,Alpha (2),Beta"),
                folders(WorldSaves.list(root)));

        WorldSaves.touch(beta, 9000L);
        check("pri shode casu radi jmeno",
                folders(WorldSaves.list(root)).equals("Alpha,Beta,Alpha (2)"),
                folders(WorldSaves.list(root)));

        WorldSaves.WorldInfo device = WorldSaves.create(root, "  CON  ", 1L, "", 4000L);
        check("jmeno zarizeni dostane jinou slozku, jmeno zustane",
                device.folder().equals("CON_") && device.name().equals("CON"),
                device.folder() + " / " + device.name());

        WorldSaves.WorldInfo unnamed = WorldSaves.create(root, "   ", 1L, null, 5000L);
        check("svet bez jmena dostane " + WorldSaves.DEFAULT_NAME,
                unnamed.name().equals(WorldSaves.DEFAULT_NAME) && unnamed.seedText().isEmpty(),
                unnamed.name());

        // Co svetem neni, do seznamu nepatri.
        Files.createDirectories(root.resolve(".hidden"));
        Files.write(root.resolve(".hidden").resolve(WorldSaves.WORLD_FILE), new byte[]{1});
        Files.createDirectories(root.resolve("prazdna"));
        Files.write(root.resolve("poznamky.txt"), "ahoj".getBytes(StandardCharsets.UTF_8));

        check("slozka s teckou, prazdna slozka ani soubor nejsou svet",
                WorldSaves.list(root).size() == 5, "" + WorldSaves.list(root).size());

        check("seznam neexistujiciho korene je prazdny",
                WorldSaves.list(root.resolve("nic")).isEmpty(), "");
    }

    // ==================================================================
    // 5) metadata tam a zpet
    // ==================================================================

    static void metadata() throws IOException {
        System.out.println("\n-- world.json --");

        Path root = tempRoot("meta");
        long[] seeds = {0L, -1L, 12345L, Long.MIN_VALUE, Long.MAX_VALUE, 0x7FFFFFFFFFFFFABCL};
        boolean allOk = true;
        String bad = "";

        for (int i = 0; i < seeds.length; i++) {
            WorldSaves.WorldInfo w = WorldSaves.create(root, "Seed " + i, seeds[i], "t" + i, 1000L + i);
            WorldSaves.WorldInfo back = find(WorldSaves.list(root), w.folder());

            if (back.seed() != seeds[i]) { allOk = false; bad = seeds[i] + " -> " + back.seed(); }
        }

        check("seed prezije tam a zpet i na krajich rozsahu long", allOk, bad);

        WorldSaves.WorldInfo big = find(WorldSaves.list(root), "Seed 5");
        String json = text(big.metaFile());
        check("seed je v souboru jako retezec (double by ho zaokrouhlil)",
                json.contains("\"seed\": \"" + 0x7FFFFFFFFFFFFABCL + "\""), json);
        check("soubor zacina cislem formatu jako blocks.json",
                json.startsWith("{\n  \"format\": " + WorldSaves.FORMAT + ",\n"),
                json.substring(0, Math.min(30, json.length())));
        check("soubor konci novym radkem a jen s \\n", json.endsWith("}\n") && !json.contains("\r"), "");

        WorldSaves.WorldInfo quoted = WorldSaves.create(root, "He said \"hi\" \\ bye", 1L, "a\"b\\c", 2000L);
        WorldSaves.WorldInfo quotedBack = find(WorldSaves.list(root), quoted.folder());
        check("uvozovky a zpetne lomitko prezijou jmeno i seedText",
                quotedBack.name().equals("He said \"hi\" \\ bye") && quotedBack.seedText().equals("a\"b\\c"),
                quotedBack.name() + " / " + quotedBack.seedText());
        check("uvozovky ve jmenu neprojdou do nazvu slozky",
                !quoted.folder().contains("\""), quoted.folder());
    }

    // ==================================================================
    // 6) poskozena metadata svet neschovaji
    // ==================================================================

    static void damagedMetadata() throws IOException {
        System.out.println("\n-- poskozena metadata --");

        Path root = tempRoot("damaged");
        WorldSaves.WorldInfo w = WorldSaves.create(root, "Pokus", 777L, "sedm", 5000L);
        Files.write(w.worldFile(), new byte[]{1, 2, 3});
        Files.setLastModifiedTime(w.worldFile(), java.nio.file.attribute.FileTime.fromMillis(LEGACY_TIME));

        byte[] goodMeta = Files.readAllBytes(w.metaFile());

        // a) rozbity JSON bez zalohy -> odvozene udaje, ale svet zustava
        Files.write(w.metaFile(), "{{{ tohle neni JSON".getBytes(StandardCharsets.UTF_8));
        List<WorldSaves.WorldInfo> list = WorldSaves.list(root);
        check("poskozeny world.json svet neschova", list.size() == 1, "" + list.size());
        check("odvozene jmeno je podle slozky", list.get(0).name().equals("Pokus"), list.get(0).name());
        check("odvozeny seed je vychozi", list.get(0).seed() == World.DEFAULT_SEED, "" + list.get(0).seed());
        check("odvozene casy jsou z world.dat",
                list.get(0).created() == LEGACY_TIME && list.get(0).lastPlayed() == LEGACY_TIME,
                list.get(0).created() + " / " + list.get(0).lastPlayed());

        // b) se zalohou .bak se vezmou udaje z ni
        Files.write(SafeFiles.backupOf(w.metaFile()), goodMeta);
        WorldSaves.WorldInfo fromBackup = WorldSaves.list(root).get(0);
        check("ze zalohy .bak se vezme seed i jmeno",
                fromBackup.seed() == 777L && fromBackup.seedText().equals("sedm"),
                fromBackup.seed() + " / " + fromBackup.seedText());

        // c) touch poskozeny soubor nejdriv zazalohuje
        Files.delete(SafeFiles.backupOf(w.metaFile()));
        String damaged = text(w.metaFile());
        WorldSaves.touch(WorldSaves.list(root).get(0), 8000L);
        check("touch zazalohuje puvodni poskozeny soubor",
                text(SafeFiles.backupOf(w.metaFile())).equals(damaged), "");
        check("po touch je world.json zase platny",
                WorldSaves.list(root).get(0).lastPlayed() == 8000L,
                "" + WorldSaves.list(root).get(0).lastPlayed());

        // d) chybejici world.json
        Path onlyDat = root.resolve("Bez metadat");
        Files.createDirectories(onlyDat);
        Files.write(onlyDat.resolve(WorldSaves.WORLD_FILE), new byte[]{9});
        Files.setLastModifiedTime(onlyDat.resolve(WorldSaves.WORLD_FILE),
                java.nio.file.attribute.FileTime.fromMillis(LEGACY_TIME));

        WorldSaves.WorldInfo derived = find(WorldSaves.list(root), "Bez metadat");
        check("slozka jen s world.dat je taky svet",
                derived.name().equals("Bez metadat") && derived.seed() == World.DEFAULT_SEED, "");

        // e) novejsi format se precte s varovanim, nezname udaje se ignoruji
        Path newer = root.resolve("Z budoucnosti");
        Files.createDirectories(newer);
        Files.writeString(newer.resolve(WorldSaves.META_FILE),
                "{\n  \"format\": 99,\n  \"name\": \"Budoucnost\",\n  \"seed\": \"-4172395517239813\",\n"
                        + "  \"seedText\": \"x\",\n  \"created\": 10,\n  \"lastPlayed\": 20,\n"
                        + "  \"gameMode\": {\"a\": [1, 2, 3]}\n}\n", StandardCharsets.UTF_8);

        WorldSaves.WorldInfo future = find(WorldSaves.list(root), "Z budoucnosti");
        check("novejsi format se nacte a nezname udaje se ignoruji",
                future.name().equals("Budoucnost") && future.seed() == -4172395517239813L
                        && future.lastPlayed() == 20L, future.name() + " / " + future.seed());

        // f) seed napsany rucne jako cislo
        Path handmade = root.resolve("Rucne");
        Files.createDirectories(handmade);
        Files.writeString(handmade.resolve(WorldSaves.META_FILE),
                "{\"format\": 1, \"name\": \"Rucne\", \"seed\": 12345, \"created\": 1, \"lastPlayed\": 2}",
                StandardCharsets.UTF_8);
        check("seed napsany jako cele cislo se prijme",
                find(WorldSaves.list(root), "Rucne").seed() == 12345L, "");

        // g) seed jako cislo nad 2^53 uz je nepresny, takze se neprijme
        Path lossy = root.resolve("Nepresny");
        Files.createDirectories(lossy);
        Files.writeString(lossy.resolve(WorldSaves.META_FILE),
                "{\"format\": 1, \"name\": \"Nepresny\", \"seed\": 9223372036854775807,"
                        + " \"created\": 1, \"lastPlayed\": 2}", StandardCharsets.UTF_8);
        WorldSaves.WorldInfo lossyInfo = find(WorldSaves.list(root), "Nepresny");
        check("seed jako prilis velke cislo se neprijme, svet ale nezmizi",
                lossyInfo.seed() == World.DEFAULT_SEED, "" + lossyInfo.seed());
    }

    // ==================================================================
    // 7) mazani
    // ==================================================================

    static void deleting() throws IOException {
        System.out.println("\n-- delete --");

        Path root = tempRoot("delete");
        WorldSaves.WorldInfo first = WorldSaves.create(root, "Prvni", 1L, "", 1000L);
        WorldSaves.WorldInfo second = WorldSaves.create(root, "Druhy", 2L, "", 2000L);
        Files.createDirectories(first.dir().resolve("podslozka"));
        Files.write(first.dir().resolve("podslozka").resolve("neco.bin"), new byte[]{1, 2});
        Files.write(root.resolve("poznamky.txt"), new byte[]{3});

        check("smazani projde", WorldSaves.delete(root, first), "");
        check("slozka i s obsahem je pryc", !Files.exists(first.dir()), "");
        check("ostatni svety zustaly", Files.isDirectory(second.dir()), "");
        check("soubory v koreni zustaly", Files.isRegularFile(root.resolve("poznamky.txt")), "");
        check("v seznamu zbyl jeden svet", WorldSaves.list(root).size() == 1, "");

        check("smazani uz smazaneho je v poradku", WorldSaves.delete(root, first), "");

        // Pojistka: mazat jde jen slozka primo v koreni.
        Path outside = root.getParent().resolve("cizi");
        Files.createDirectories(outside);
        Files.write(outside.resolve("dulezite.txt"), new byte[]{7});

        WorldSaves.WorldInfo alien = new WorldSaves.WorldInfo("cizi", "Cizi", 1L, "", 0L, 0L, outside);
        check("slozka mimo koren se odmitne", !WorldSaves.delete(root, alien), "");
        check("a zustane netknuta", Files.isRegularFile(outside.resolve("dulezite.txt")), "");

        WorldSaves.WorldInfo rootItself = new WorldSaves.WorldInfo("", "Koren", 1L, "", 0L, 0L, root);
        check("koren sam se odmitne", !WorldSaves.delete(root, rootItself), "");
        check("a zustane", Files.isDirectory(root), "");

        WorldSaves.WorldInfo deeper = new WorldSaves.WorldInfo("hloubeji", "Hloubeji", 1L, "", 0L, 0L,
                second.dir().resolve("podslozka"));
        Files.createDirectories(second.dir().resolve("podslozka"));
        check("slozka o patro niz se odmitne", !WorldSaves.delete(root, deeper), "");
        check("a zustane", Files.isDirectory(second.dir().resolve("podslozka")), "");

        WorldSaves.WorldInfo dotted = new WorldSaves.WorldInfo("ven", "Ven", 1L, "", 0L, 0L,
                root.resolve("..").resolve("cizi"));
        check("cesta s .. ven z korene se odmitne", !WorldSaves.delete(root, dotted), "");
        check("a cizi slozka zustane", Files.isRegularFile(outside.resolve("dulezite.txt")), "");
    }

    // ==================================================================
    // 8) migrace stareho saves/world.dat
    // ==================================================================

    static void migrationBasics() throws IOException {
        System.out.println("\n-- migrace --");

        Path root = tempRoot("migrate");
        check("bez stareho souboru se nic nedeje",
                WorldSaves.migrateLegacy(root) == WorldSaves.Migration.NONE, "");
        check("a koren se ani nezalozi", !Files.exists(root), "");

        byte[] legacy = fabricateLegacy(root.resolve(WorldSaves.WORLD_FILE));

        check("prenos probehl",
                WorldSaves.migrateLegacy(root) == WorldSaves.Migration.MIGRATED, "");

        List<WorldSaves.WorldInfo> list = WorldSaves.list(root);
        check("v seznamu je prave jeden svet", list.size() == 1, "" + list.size());

        WorldSaves.WorldInfo old = list.get(0);
        check("jmenuje se " + WorldSaves.LEGACY_NAME,
                old.name().equals(WorldSaves.LEGACY_NAME), old.name());
        check("slozka je " + WorldSaves.LEGACY_NAME, old.folder().equals(WorldSaves.LEGACY_NAME), old.folder());
        check("dostal vychozi seed (teren zustane stejny)",
                old.seed() == World.DEFAULT_SEED && old.seedText().isEmpty(), "" + old.seed());
        check("casy jsou z casu stareho souboru",
                old.created() == LEGACY_TIME && old.lastPlayed() == LEGACY_TIME,
                old.created() + " / " + old.lastPlayed());

        check("prenesena data jsou bajt po bajtu stejna",
                java.util.Arrays.equals(legacy, Files.readAllBytes(old.worldFile())), "");
        check("stary soubor je pryc z korene",
                !Files.exists(root.resolve(WorldSaves.WORLD_FILE)), "");

        Path kept = root.resolve(WorldSaves.WORLD_FILE + WorldSaves.MIGRATED_SUFFIX);
        check("zustal jako world.dat.migrated", Files.isRegularFile(kept), "");
        check("a je taky bajt po bajtu stejny",
                java.util.Arrays.equals(legacy, Files.readAllBytes(kept)), "");
        check("docasna slozka .migrating je uklizena",
                !Files.exists(root.resolve(WorldSaves.MIGRATING_DIR)), "");

        WorldStorage.Save save = WorldStorage.load(old.worldFile());
        check("preneseny svet jde nacist", save != null, "");
        check("nese tytez zmeny i inventar",
                save != null && changedBlocks(save) == 3
                        && save.inventory()[0].block() == World.STONE
                        && save.inventory()[0].count() == 64
                        && save.inventory()[7].block() == World.LOG,
                save == null ? "null" : "" + changedBlocks(save));
        check("nese i pozici hrace",
                save != null && save.x() == 1.5f && save.z() == -2.5f && save.selectedSlot() == 2, "");

        Object meta = Json.parse(text(old.metaFile()));
        Map<?, ?> fields = (Map<?, ?>) meta;
        check("metadata nesou, odkud se svet vzal",
                WorldSaves.WORLD_FILE.equals(fields.get("migratedFrom")), "" + fields.get("migratedFrom"));
        check("metadata nesou velikost a kontrolni soucet zdroje",
                ((Double) fields.get("migratedBytes")).longValue() == legacy.length
                        && ((Double) fields.get("migratedCrc32")).longValue() == crc32(legacy),
                fields.get("migratedBytes") + " / " + fields.get("migratedCrc32"));
    }

    static void migrationRepeat() throws IOException {
        System.out.println("\n-- migrace podruhe --");

        Path root = tempRoot("again");
        fabricateLegacy(root.resolve(WorldSaves.WORLD_FILE));
        WorldSaves.migrateLegacy(root);

        Map<String, String> before = snapshot(root);
        check("druhe spusteni uz nic nedela",
                WorldSaves.migrateLegacy(root) == WorldSaves.Migration.NONE, "");
        check("zadny soubor se nezmenil", snapshot(root).equals(before), "");
        check("a svet je porad jeden", WorldSaves.list(root).size() == 1, "");

        // Kdyz stary soubor zustal (prejmenovani selhalo) a slozka uz existuje,
        // prenos se nesmi zopakovat - jen se dokonci prejmenovani.
        Files.copy(root.resolve(WorldSaves.WORLD_FILE + WorldSaves.MIGRATED_SUFFIX),
                root.resolve(WorldSaves.WORLD_FILE));

        check("stejny stary soubor podruhe se pozna",
                WorldSaves.migrateLegacy(root) == WorldSaves.Migration.FINISHED_EARLIER, "");
        check("zadny duplikat nevznikl", WorldSaves.list(root).size() == 1,
                "" + WorldSaves.list(root).size());
        check("a soubor se schoval jako -2",
                !Files.exists(root.resolve(WorldSaves.WORLD_FILE))
                        && Files.isRegularFile(root.resolve(WorldSaves.WORLD_FILE
                                + WorldSaves.MIGRATED_SUFFIX + "-2")), "");

        // Ve svete se mezitim hralo, takze world.dat uz shodny neni - poznat
        // ho musi velikost a kontrolni soucet v metadatech (a ty musi prezit touch).
        WorldSaves.WorldInfo old = WorldSaves.list(root).get(0);
        WorldSaves.touch(old, 12345678L);
        check("touch zachova migracni udaje",
                text(old.metaFile()).contains("\"migratedFrom\""), text(old.metaFile()));

        Files.write(old.worldFile(), new byte[]{4, 2});
        Files.copy(root.resolve(WorldSaves.WORLD_FILE + WorldSaves.MIGRATED_SUFFIX),
                root.resolve(WorldSaves.WORLD_FILE));

        check("pozna se i kdyz se ve svete mezitim hralo",
                WorldSaves.migrateLegacy(root) == WorldSaves.Migration.FINISHED_EARLIER, "");
        check("porad zadny duplikat", WorldSaves.list(root).size() == 1, "");
        check("a soubor se schoval jako -3",
                Files.isRegularFile(root.resolve(WorldSaves.WORLD_FILE
                        + WorldSaves.MIGRATED_SUFFIX + "-3")), "");

        // BUG: s dobrou zalohou world.json.bak a poskozenym world.json zapsal
        // touch() metadata BEZ migrace (cetl ji z poskozeneho souboru) a pak
        // SafeFiles prepsal dobrou zalohu tim poskozenym. Stary soubor by se
        // pak prenesl podruhe jako "Old World (2)".
        WorldSaves.WorldInfo again = WorldSaves.list(root).get(0);
        byte[] goodMeta = Files.readAllBytes(again.metaFile());
        Files.write(SafeFiles.backupOf(again.metaFile()), goodMeta);
        Files.write(again.metaFile(), "{ poskozeno".getBytes(StandardCharsets.UTF_8));

        WorldSaves.touch(WorldSaves.list(root).get(0), 23456789L);

        check("touch pres poskozeny world.json vezme migraci ze zalohy",
                text(again.metaFile()).contains("\"migratedFrom\""), text(again.metaFile()));
        check("dobra zaloha .bak zustala beze zmeny",
                java.util.Arrays.equals(Files.readAllBytes(SafeFiles.backupOf(again.metaFile())), goodMeta), "");
        check("poskozeny soubor je v dalsi zaloze .bak.1",
                text(SafeFiles.backupOf(again.metaFile(), 1)).equals("{ poskozeno"), "");

        Files.copy(root.resolve(WorldSaves.WORLD_FILE + WorldSaves.MIGRATED_SUFFIX),
                root.resolve(WorldSaves.WORLD_FILE));

        check("stary soubor se ani po poskozeni metadat neprenese podruhe",
                WorldSaves.migrateLegacy(root) == WorldSaves.Migration.FINISHED_EARLIER, "");
        check("a svet je porad jeden", WorldSaves.list(root).size() == 1,
                "" + WorldSaves.list(root).size());
    }

    static void migrationCrashes() throws IOException {
        System.out.println("\n-- migrace po padu --");

        // a) zbytek po padu v .migrating
        Path root = tempRoot("leftover");
        byte[] legacy = fabricateLegacy(root.resolve(WorldSaves.WORLD_FILE));

        Path leftover = root.resolve(WorldSaves.MIGRATING_DIR);
        Files.createDirectories(leftover.resolve("smeti"));
        Files.write(leftover.resolve(WorldSaves.WORLD_FILE),
                java.util.Arrays.copyOf(legacy, legacy.length / 2));
        Files.write(leftover.resolve("smeti").resolve("x.bin"), new byte[]{9});

        check("zbytek po padu prenos nerozhodi",
                WorldSaves.migrateLegacy(root) == WorldSaves.Migration.MIGRATED, "");
        check("zbytek se uklidil", !Files.exists(leftover), "");
        check("vznikl prave jeden svet", WorldSaves.list(root).size() == 1, "");
        check("a nese cela data, ne useknuty zbytek",
                java.util.Arrays.equals(legacy, Files.readAllBytes(WorldSaves.list(root).get(0).worldFile())), "");

        // b) pad mezi prejmenovanim slozky a souboru
        Path crashed = tempRoot("halfway");
        byte[] second = fabricateLegacy(crashed.resolve(WorldSaves.WORLD_FILE));

        check("pad po prejmenovani slozky vrati NONE",
                WorldSaves.migrateLegacy(crashed, WorldSaves.Step.FOLDER_RENAMED)
                        == WorldSaves.Migration.NONE, "");
        check("stary soubor po padu zustal",
                java.util.Arrays.equals(second, Files.readAllBytes(crashed.resolve(WorldSaves.WORLD_FILE))), "");
        check("ale slozka uz existuje", WorldSaves.list(crashed).size() == 1, "");

        check("priste se jen dokonci prejmenovani",
                WorldSaves.migrateLegacy(crashed) == WorldSaves.Migration.FINISHED_EARLIER, "");
        check("zadny duplikat", WorldSaves.list(crashed).size() == 1,
                "" + WorldSaves.list(crashed).size());
        check("stary soubor je schovany",
                !Files.exists(crashed.resolve(WorldSaves.WORLD_FILE))
                        && java.util.Arrays.equals(second, Files.readAllBytes(
                                crashed.resolve(WorldSaves.WORLD_FILE + WorldSaves.MIGRATED_SUFFIX))), "");

        // c) chyba disku v kterekoliv fazi: stary soubor zustava, priste to projde
        for (WorldSaves.Step step : new WorldSaves.Step[]{
                WorldSaves.Step.COPIED, WorldSaves.Step.VERIFIED, WorldSaves.Step.META_WRITTEN}) {

            Path failing = tempRoot("fail-" + step);
            byte[] data = fabricateLegacy(failing.resolve(WorldSaves.WORLD_FILE));

            boolean none = WorldSaves.migrateLegacy(failing, step) == WorldSaves.Migration.NONE;
            boolean intact = java.util.Arrays.equals(data,
                    Files.readAllBytes(failing.resolve(WorldSaves.WORLD_FILE)));
            boolean noWorld = WorldSaves.list(failing).isEmpty();

            check("chyba po kroku " + step + ": NONE, soubor zustava, zadny pulsvet",
                    none && intact && noWorld, none + " " + intact + " " + noWorld);

            boolean retry = WorldSaves.migrateLegacy(failing) == WorldSaves.Migration.MIGRATED;
            boolean ok = retry && WorldSaves.list(failing).size() == 1
                    && java.util.Arrays.equals(data,
                            Files.readAllBytes(WorldSaves.list(failing).get(0).worldFile()));

            check("a priste prenos projde cely", ok, "" + retry);
        }
    }

    static void migrationNeighbours() throws IOException {
        System.out.println("\n-- migrace vedle jinych svetu --");

        // Cizi slozka stejneho jmena se nesmi prepsat.
        Path root = tempRoot("neighbour");
        WorldSaves.WorldInfo mine = WorldSaves.create(root, WorldSaves.LEGACY_NAME, 99L, "muj", 1000L);
        byte[] mineMeta = Files.readAllBytes(mine.metaFile());
        byte[] legacy = fabricateLegacy(root.resolve(WorldSaves.WORLD_FILE));

        check("prenos vedle cizi slozky projde",
                WorldSaves.migrateLegacy(root) == WorldSaves.Migration.MIGRATED, "");
        check("jsou z toho dva svety", WorldSaves.list(root).size() == 2,
                "" + WorldSaves.list(root).size());
        check("cizi svet zustal netknuty",
                java.util.Arrays.equals(mineMeta, Files.readAllBytes(mine.metaFile()))
                        && find(WorldSaves.list(root), WorldSaves.LEGACY_NAME).seed() == 99L, "");

        WorldSaves.WorldInfo migrated = find(WorldSaves.list(root), WorldSaves.LEGACY_NAME + " (2)");
        check("preneseny svet je vedle, v (2)",
                migrated.seed() == World.DEFAULT_SEED
                        && java.util.Arrays.equals(legacy, Files.readAllBytes(migrated.worldFile())), "");

        // Stejna slozka jinou velikosti pismen (Windows je nerozlisi).
        Path other = tempRoot("case");
        Files.createDirectories(other.resolve("old world"));
        Files.write(other.resolve("old world").resolve(WorldSaves.WORLD_FILE), new byte[]{5});
        fabricateLegacy(other.resolve(WorldSaves.WORLD_FILE));

        WorldSaves.migrateLegacy(other);
        check("slozka lisici se jen velikosti pismen se taky obejde",
                Files.isDirectory(other.resolve(WorldSaves.LEGACY_NAME + " (2)")),
                String.join(",", listFolders(other)));

        // Poskozeny stary soubor se prenese taky - bajty se neztraci.
        Path broken = tempRoot("broken");
        byte[] junk = new byte[257];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) (i * 37);
        Files.createDirectories(broken);
        Files.write(broken.resolve(WorldSaves.WORLD_FILE), junk);

        check("poskozeny stary soubor se prenese taky",
                WorldSaves.migrateLegacy(broken) == WorldSaves.Migration.MIGRATED, "");
        check("a to bajt po bajtu",
                java.util.Arrays.equals(junk, Files.readAllBytes(WorldSaves.list(broken).get(0).worldFile())), "");
        check("nacist se neda, to uz ale resi WorldStorage",
                WorldStorage.load(WorldSaves.list(broken).get(0).worldFile()) == null, "");

        // Cizi world.dat.migrated se nikdy nepresype.
        Path taken = tempRoot("taken");
        Files.createDirectories(taken);
        byte[] foreign = "cizi soubor".getBytes(StandardCharsets.UTF_8);
        Files.write(taken.resolve(WorldSaves.WORLD_FILE + WorldSaves.MIGRATED_SUFFIX), foreign);
        byte[] data = fabricateLegacy(taken.resolve(WorldSaves.WORLD_FILE));

        WorldSaves.migrateLegacy(taken);
        check("obsazeny world.dat.migrated se neprepise",
                java.util.Arrays.equals(foreign,
                        Files.readAllBytes(taken.resolve(WorldSaves.WORLD_FILE + WorldSaves.MIGRATED_SUFFIX))), "");
        check("stary soubor skoncil jako -2",
                java.util.Arrays.equals(data, Files.readAllBytes(
                        taken.resolve(WorldSaves.WORLD_FILE + WorldSaves.MIGRATED_SUFFIX + "-2"))), "");
    }

    // ==================================================================
    // pomocne
    // ==================================================================

    /** Docasny koren svetu; slozka saves/ se schvalne nezaklada. */
    static Path tempRoot(String name) throws IOException {
        return Files.createTempDirectory("mc-worlds-" + name).resolve("saves");
    }

    /**
     * Vyrobi skutecny stary saves/world.dat: svet se zmenami bloku a s inventarem,
     * ulozeny pres WorldStorage, s pevnym casem souboru.
     */
    static byte[] fabricateLegacy(Path file) throws IOException {
        World w = new World();
        w.loadRadius = 1;
        w.unloadRadius = 3;
        w.updateBlocking(8f, 8f);

        int surface = -1;
        for (int y = World.WORLD_HEIGHT - 1; y >= 0 && surface < 0; y--) if (w.isSolid(8, y, 8)) surface = y;

        w.breakBlock(8, surface, 8);
        w.placeBlock(8, surface + 1, 8, World.PLANKS);
        w.placeBlock(9, surface + 1, 8, World.STONE);

        ItemStack[] inventory = new ItemStack[Inventory.SIZE];
        inventory[0] = ItemStack.of(World.STONE, 64);
        inventory[7] = ItemStack.of(World.LOG, 12);

        Files.createDirectories(file.getParent());
        WorldStorage.save(file, new WorldStorage.Save(1.5f, 70.25f, -2.5f, 33f, -11f, false, 2,
                w.changes(), inventory, DayCycle.START_TIME));
        w.shutdown();

        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(LEGACY_TIME));
        return Files.readAllBytes(file);
    }

    static int changedBlocks(WorldStorage.Save save) {
        int total = 0;
        for (Map<Integer, Byte> column : save.changes().values()) total += column.size();
        return total;
    }

    static String folders(List<WorldSaves.WorldInfo> worlds) {
        List<String> names = new ArrayList<>();
        for (WorldSaves.WorldInfo w : worlds) names.add(w.folder());
        return String.join(",", names);
    }

    static List<String> listFolders(Path root) throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            entries.forEach(p -> names.add(p.getFileName().toString()));
        }
        names.sort(String::compareTo);
        return names;
    }

    static WorldSaves.WorldInfo find(List<WorldSaves.WorldInfo> worlds, String folder) {
        for (WorldSaves.WorldInfo w : worlds) if (w.folder().equals(folder)) return w;
        throw new IllegalStateException("svet " + folder + " v seznamu neni");
    }

    static String text(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /** Vsechny soubory pod korenem jako cesta -> velikost a kontrolni soucet. */
    static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();

        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                if (!Files.isRegularFile(path)) continue;
                byte[] data = Files.readAllBytes(path);
                files.put(root.relativize(path).toString(), data.length + ":" + crc32(data));
            }
        }

        return files;
    }
}
