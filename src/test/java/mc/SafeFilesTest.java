package mc;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Bezpečný zápis souborů: SafeFiles a všechno, co přes něj jde.
 *
 * ---------------------------------------------------------------------------
 * PROČ TENHLE TEST JE. Audit našel tři místa, kde se vzor "atomický zápis
 * + .bak" nedodržoval, a jedno, kde ho sám SafeFiles porušoval:
 *
 *   - world.dat se psal přímo přes starý soubor (TRUNCATE_EXISTING) - plný
 *     disk nebo zabití procesu při ukládání nechalo svět, který nejde načíst;
 *   - atlas.png a skin.png šly přes ImageIO.write(File), který cíl nejdřív
 *     smaže - a nečitelný soubor se přepsal bez zálohy;
 *   - druhá záloha přepsala první (REPLACE_EXISTING), takže druhé poškození
 *     smazalo, co zachránilo to první.
 *
 * ⚠️ JAK SE OVĚŘUJE ATOMICITA. (1) Nepovedený zápis se vyrobí tak, že se
 * místo <soubor>.tmp položí neprázdná složka - zápis musí selhat a starý
 * soubor musí zůstat bajt po bajtu. (2) U world.dat navíc hardlink: pevný
 * odkaz ukazuje na tentýž obsah (inode) jako soubor; zápis přímo do cíle
 * (dřívější TRUNCATE_EXISTING) by se v něm projevil, atomický zápis vyrobí
 * nový soubor vedle, takže odkaz si drží starý obsah. Když souborový systém
 * hardlinky neumí, tahle jedna kontrola se přeskočí.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL. Zapisuje jen do dočasného adresáře.
 */
public class SafeFilesTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("mc-safefiles");

        backups(dir.resolve("backups"));
        backupsFull(dir.resolve("full"));
        worldFile(dir.resolve("world"));
        legacyWorldFile(dir.resolve("mcw1"));
        images(dir.resolve("images"));

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    /** Predikát "soubor jde celý načíst": u testů je to "obsahuje OK". */
    static boolean readsOk(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).startsWith("OK");
        } catch (IOException e) {
            return false;
        }
    }

    static boolean write(Path file, String content) {
        return SafeFiles.writeAtomically(file, content, SafeFilesTest::readsOk, "Test");
    }

    static String text(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    // ==================================================================
    // zálohy se nepřepisují
    // ==================================================================

    static void backups(Path dir) throws IOException {
        System.out.println("\n-- zaloha poskozeneho souboru nikdy neprepise starsi zalohu --");

        Path file = dir.resolve("data.json");

        check("prvni zapis projde", write(file, "OK 1"), "");
        check("po uspesnem zapisu nezustal .tmp",
                !Files.exists(file.resolveSibling("data.json.tmp")), "");
        check("platny soubor se nezalohuje", !Files.exists(SafeFiles.backupOf(file)), "");

        // Prvni poskozeni: jde do .bak.
        Files.writeString(file, "poskozeno A");
        check("zapis pres poskozeny soubor projde", write(file, "OK 2"), "");
        check("poskozeny soubor je v .bak",
                Files.isRegularFile(SafeFiles.backupOf(file)) && text(SafeFiles.backupOf(file)).equals("poskozeno A"), "");

        // Druhe, JINE poskozeni: .bak zustane, nove jde do .bak.1.
        Files.writeString(file, "poskozeno B");
        check("druhy zapis pres jine poskozeni projde", write(file, "OK 3"), "");
        check("prvni zaloha zustala beze zmeny (driv ji druha prepsala)",
                text(SafeFiles.backupOf(file)).equals("poskozeno A"), text(SafeFiles.backupOf(file)));
        check("druhe poskozeni je v .bak.1",
                Files.isRegularFile(SafeFiles.backupOf(file, 1)) && text(SafeFiles.backupOf(file, 1)).equals("poskozeno B"), "");

        // Stejny obsah podruhe: nova zaloha nevznikne.
        Files.writeString(file, "poskozeno A");
        check("zapis pres uz zazalohovany obsah projde", write(file, "OK 4"), "");
        check("stejny obsah se nezalohuje podruhe", !Files.exists(SafeFiles.backupOf(file, 2)), "");
        check("a soubor ma novy obsah", text(file).equals("OK 4"), text(file));
    }

    /**
     * Když jsou obsazené všechny zálohy, soubor se radši NEPŘEPÍŠE - stejné
     * pravidlo jako "když se zálohovat nepovede". Tahle větev dřív neměla
     * test vůbec (a v audit ji to vedlo jako mezeru).
     */
    static void backupsFull(Path dir) throws IOException {
        System.out.println("\n-- kdyz zalohovat nejde, soubor se neprepise --");

        Path file = dir.resolve("data.json");
        Files.createDirectories(dir);

        for (int n = 0; n < SafeFiles.MAX_BACKUPS; n++) {
            Files.writeString(SafeFiles.backupOf(file, n), "stara zaloha " + n);
        }

        Files.writeString(file, "poskozeno a nezazalohovano");

        check("zapis se odmitne", !write(file, "OK novy"), "");
        check("soubor zustal, jak byl", text(file).equals("poskozeno a nezazalohovano"), text(file));
        check("zadna zaloha se neprepsala", text(SafeFiles.backupOf(file, 0)).equals("stara zaloha 0")
                && text(SafeFiles.backupOf(file, SafeFiles.MAX_BACKUPS - 1))
                        .equals("stara zaloha " + (SafeFiles.MAX_BACKUPS - 1)), "");
        check("nezustal po nem .tmp", !Files.exists(file.resolveSibling("data.json.tmp")), "");

        // Stejné selhání i u zálohy, která není soubor (neprázdná složka).
        Path other = dir.resolve("other.json");
        Files.writeString(other, "poskozeno");
        for (int n = 0; n < SafeFiles.MAX_BACKUPS; n++) {
            Path slot = SafeFiles.backupOf(other, n);
            Files.createDirectories(slot);
            Files.writeString(slot.resolve("x"), "x");
        }

        check("i se zalohami, ktere nejsou soubory, se zapis odmitne", !write(other, "OK"), "");
        check("a soubor zustal", text(other).equals("poskozeno"), "");
    }

    // ==================================================================
    // world.dat
    // ==================================================================

    static WorldStorage.Save sampleSave(float x) {
        Map<Long, Map<Integer, Byte>> changes = new HashMap<>();
        Map<Integer, Byte> column = new HashMap<>();
        column.put(World.columnIndex(1, 70, 2), World.PLANKS);
        changes.put(World.key(0, 0), column);

        ItemStack[] inventory = new ItemStack[Inventory.SIZE];
        inventory[0] = ItemStack.of(World.STONE, 12);

        return new WorldStorage.Save(x, 70f, 8f, 0.5f, -0.25f, false, 2,
                changes, inventory, 123f);
    }

    static void worldFile(Path dir) throws IOException {
        System.out.println("\n-- world.dat se zapisuje atomicky --");

        Path file = dir.resolve("world.dat");

        check("prvni ulozeni projde", WorldStorage.save(file, sampleSave(1f)), "");
        byte[] first = Files.readAllBytes(file);

        Path link = dir.resolve("puvodni-odkaz.dat");
        boolean linked = hardLink(file, link);

        check("druhe ulozeni projde", WorldStorage.save(file, sampleSave(2f)), "");
        check("nezustal .tmp", !Files.exists(dir.resolve("world.dat.tmp")), "");

        WorldStorage.Save loaded = WorldStorage.load(file);
        check("novy soubor jde nacist s novymi hodnotami", loaded != null && loaded.x() == 2f, "");

        if (linked) {
            check("stary obsah zustal cely v puvodnim souboru (zapis nesel pres nej, ale vedle)",
                    Arrays.equals(Files.readAllBytes(link), first), "");
        } else {
            System.out.println("  (hardlinky tu nejdou - kontrola atomicity preskocena)");
        }

        // Nepovedeny zapis nechá stary svet cely (driv by po nem zbyl useknuty soubor).
        byte[] before = Files.readAllBytes(file);
        Path blocked = dir.resolve("world.dat.tmp");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("x"), "x");
        check("ulozeni se zablokovanym .tmp selze", !WorldStorage.save(file, sampleSave(9f)), "");
        check("a stary world.dat zustal bajt po bajtu", Arrays.equals(Files.readAllBytes(file), before), "");
        Files.delete(blocked.resolve("x"));
        Files.delete(blocked);

        // Soubor, ktery nejde nacist, se pred prepsanim zazalohuje.
        byte[] junk = {0x4D, 0x43, 0x57, 0x33, 0, 0, 0, 5, 1};
        Files.write(file, junk);
        check("ulozeni pres nenacitelny world.dat projde", WorldStorage.save(file, sampleSave(3f)), "");
        check("nenacitelny world.dat je bajt po bajtu v .bak",
                sameBytes(SafeFiles.backupOf(file), junk), "");

        // Platny soubor se nezalohuje - ani jeho pripadne varovani (jina verze
        // generatoru) neni duvod k zaloze, svet se nacte cely.
        Files.delete(SafeFiles.backupOf(file));
        check("dalsi ulozeni pres platny soubor projde", WorldStorage.save(file, sampleSave(4f)), "");
        check("platny world.dat se nezalohuje", !Files.exists(SafeFiles.backupOf(file)), "");
    }

    /**
     * Soubor ve formátu MCW1 (bez inventáře a bez času) - na jeho čitelnost se
     * odvolávají komentáře i ARCHITECTURE, ale test ho dřív nezapisoval nikde.
     */
    static void legacyWorldFile(Path dir) throws IOException {
        System.out.println("\n-- world.dat ve formatu MCW1 --");

        Files.createDirectories(dir);
        Path file = dir.resolve("world.dat");

        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(0x4D435731);                       // MCW1
            out.writeInt(WorldStorage.GENERATOR_VERSION);
            out.writeFloat(5); out.writeFloat(66); out.writeFloat(-7);
            out.writeFloat(10f); out.writeFloat(-5f);
            out.writeBoolean(true);
            out.writeInt(4);
            out.writeInt(1);                                 // jeden sloupec
            out.writeLong(World.key(-1, 2));
            out.writeInt(1);
            out.writeInt(World.columnIndex(3, 60, 4));
            out.writeByte(World.STONE_BRICKS);
            // MCW1 konci tady - zadny inventar, zadny cas.
        }

        WorldStorage.Save loaded = WorldStorage.load(file);
        check("MCW1 se nacte", loaded != null, "");
        if (loaded == null) return;

        check("poloha, pohled, let i slot sedi",
                loaded.x() == 5 && loaded.y() == 66 && loaded.z() == -7
                        && loaded.yaw() == 10f && loaded.pitch() == -5f
                        && loaded.flying() && loaded.selectedSlot() == 4, "");
        check("zmena bloku dorazila",
                loaded.changes().get(World.key(-1, 2)) != null
                        && loaded.changes().get(World.key(-1, 2)).get(World.columnIndex(3, 60, 4)) == World.STONE_BRICKS, "");
        check("inventar je prazdny (MCW1 ho nemel)", loaded.inventory().length == 0, "" + loaded.inventory().length);
        check("cas je START_TIME (MCW1 ho nemel)", loaded.dayTime() == DayCycle.START_TIME, "" + loaded.dayTime());
    }

    // ==================================================================
    // atlas.png a skin.png
    // ==================================================================

    static void images(Path dir) throws IOException {
        System.out.println("\n-- atlas.png a skin.png se zapisuji atomicky a se zalohou --");

        int size = BlockAtlas.ATLAS_PIXELS;
        int[] pixels = new int[size * size];
        Arrays.fill(pixels, 0xFF336699);

        Path atlas = dir.resolve("atlas.png");
        check("prvni ulozeni atlasu projde", AtlasImage.save(pixels, atlas), "");

        pixels[0] = 0xFFFF0000;
        check("druhe ulozeni atlasu projde", AtlasImage.save(pixels, atlas), "");
        check("nezustal .tmp", !Files.exists(dir.resolve("atlas.png.tmp")), "");
        int[] back = AtlasImage.load(atlas);
        check("atlas jde nacist a nese zmenu", back != null && back[0] == 0xFFFF0000, "");

        // Nepovedeny zapis (tady zablokovany .tmp) nechá stary atlas cely -
        // ImageIO.write(File) cil nejdriv smazal, takze po chybe nezbylo nic.
        byte[] before = Files.readAllBytes(atlas);
        Path blocked = dir.resolve("atlas.png.tmp");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("x"), "x");
        pixels[1] = 0xFF00FF00;
        check("zapis se zablokovanym .tmp selze", !AtlasImage.save(pixels, atlas), "");
        check("a stary atlas zustal bajt po bajtu", Arrays.equals(Files.readAllBytes(atlas), before), "");
        Files.delete(blocked.resolve("x"));
        Files.delete(blocked);

        // Poskozeny atlas (nahodne bajty) se pred prepsanim zazalohuje.
        byte[] junk = "tohle neni PNG".getBytes(StandardCharsets.UTF_8);
        Files.write(atlas, junk);
        check("ulozeni pres poskozeny atlas projde", AtlasImage.save(pixels, atlas), "");
        check("poskozeny atlas je bajt po bajtu v .bak",
                sameBytes(SafeFiles.backupOf(atlas), junk), "");

        // PNG jineho rozmeru (napr. 256x256 pripraveny rucne) - taky se zazalohuje.
        Path other = dir.resolve("big").resolve("atlas.png");
        int[] big = new int[256 * 256];
        AtlasImage.save(big, other, 256, true);
        byte[] bigBytes = Files.readAllBytes(other);
        check("ulozeni pres atlas jineho rozmeru projde", AtlasImage.save(pixels, other), "");
        check("atlas jineho rozmeru je v .bak",
                sameBytes(SafeFiles.backupOf(other), bigBytes), "");

        // Skin: tataz cesta, jen 64x64 a bez preklapeni.
        Path skin = dir.resolve("skin.png");
        int[] skinPixels = new int[64 * 64];
        Arrays.fill(skinPixels, 0xFF123456);
        check("ulozeni kuze projde", AtlasImage.save(skinPixels, skin, 64, false), "");
        Files.write(skin, junk);
        check("ulozeni pres poskozenou kuzi projde", AtlasImage.save(skinPixels, skin, 64, false), "");
        check("poskozena kuze je v .bak", sameBytes(SafeFiles.backupOf(skin), junk), "");
        int[] skinBack = AtlasImage.load(skin, 64, false);
        check("kuze jde nacist", skinBack != null && skinBack[5] == 0xFF123456, "");
    }

    /** Soubor existuje a má přesně tyhle bajty (bez výjimky, když neexistuje). */
    static boolean sameBytes(Path file, byte[] expected) throws IOException {
        return Files.isRegularFile(file) && Arrays.equals(Files.readAllBytes(file), expected);
    }

    static boolean hardLink(Path target, Path link) {
        try {
            Files.createLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}
