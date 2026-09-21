package mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Overuje ukladani a nacitani sveta.
 *
 * Uklada se ROZDIL proti generatoru, ne cely svet - generator je cista funkce
 * souradnic, takze teren se dopocita znovu a na disk staci to, co hrac zmenil.
 * Testy proto hlidaji dve veci: ze se rozdil spravne prenese na disk a zpatky,
 * a ze se spravne nasadi na cerstve vygenerovany teren.
 */
public class SaveTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("mc-save-test");
        Path file = dir.resolve("world.dat");

        // ---------- 0) neexistujici a poskozeny soubor ----------
        check("neexistujici soubor: exists() je false", !WorldStorage.exists(file), "");
        check("neexistujici soubor: load() vrati null", WorldStorage.load(file) == null, "");

        Path junk = dir.resolve("junk.dat");
        Files.write(junk, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        check("cizi soubor se odmitne misto padu", WorldStorage.load(junk) == null, "");

        Path truncated = dir.resolve("truncated.dat");
        Files.write(truncated, new byte[]{0x4D, 0x43, 0x57, 0x31, 0, 0, 0, 2, 0, 0});
        check("useknuty soubor se odmitne misto padu", WorldStorage.load(truncated) == null, "");

        // ---------- 1) zmeny prezijou unload sloupce ----------
        // Tohle platilo i pred ukladanim: staci odejit za unloadRadius a vratit
        // se, sloupec se vygeneroval znovu z sumu a postavene veci byly pryc.
        World w = new World();
        w.loadRadius = 2;
        w.unloadRadius = 4;
        w.updateBlocking(8f, 8f);

        int surface = topSolid(w, 8, 8);
        check("nasli jsme povrch", surface > 0, "y=" + surface);

        w.breakBlock(8, surface, 8);
        w.placeBlock(8, surface + 1, 8, World.PLANKS);
        w.placeBlock(9, surface + 1, 8, World.STONE);

        check("rozbity blok je pryc", !w.isSolid(8, surface, 8), "");
        check("polozeny blok je tam", w.getBlock(8, surface + 1, 8) == World.PLANKS, "");
        check("pocet zmen sedi", w.changedBlockCount() == 3, "" + w.changedBlockCount());

        // odejit dost daleko, aby se sloupec zahodil, a vratit se
        w.updateBlocking(5000f, 5000f);
        check("sloupec se opravdu zahodil", w.column(0, 0) == null, "");

        w.updateBlocking(8f, 8f);
        check("po navratu je rozbity blok porad pryc", !w.isSolid(8, surface, 8), "");
        check("po navratu je polozeny blok porad tam",
                w.getBlock(8, surface + 1, 8) == World.PLANKS, "");
        check("po navratu je i druhy polozeny blok tam",
                w.getBlock(9, surface + 1, 8) == World.STONE, "");

        // ---------- 2) ulozit a nacist ----------
        // Inventář se ukládá spolu se světem - bez nej by se po nacteni
        // hrac vratil k tomu, co postavil, ale s prazdnymi kapsami.
        Inventory packed = new Inventory();
        packed.set(0, ItemStack.of(World.STONE, 64));
        packed.set(4, ItemStack.of(World.PLANKS, 7));
        packed.set(30, ItemStack.of(World.IRON_ORE, 1));

        ItemStack[] snapshot = new ItemStack[Inventory.SIZE];
        for (int i = 0; i < Inventory.SIZE; i++) snapshot[i] = packed.get(i);

        WorldStorage.Save save = new WorldStorage.Save(
                12.5f, 70.25f, -34.75f, 123.5f, -12.25f, true, 3, w.changes(), snapshot);

        check("ulozeni projde", WorldStorage.save(file, save), "");
        check("po ulozeni soubor existuje", WorldStorage.exists(file), "");

        long size = Files.size(file);
        System.out.printf("%nVelikost uloziste: %d bajtu pro %d zmen%n", size, w.changedBlockCount());
        check("uloziste je male (uklada se rozdil, ne svet)", size < 4096, size + " bajtu");

        WorldStorage.Save loaded = WorldStorage.load(file);
        check("nacteni projde", loaded != null, "");

        if (loaded == null) {
            System.out.println("\nSELHALO: " + failures);
            return;
        }

        check("pozice hrace sedi",
                loaded.x() == 12.5f && loaded.y() == 70.25f && loaded.z() == -34.75f,
                loaded.x() + " " + loaded.y() + " " + loaded.z());
        check("uhly kamery sedi", loaded.yaw() == 123.5f && loaded.pitch() == -12.25f, "");
        check("let a vybrany slot sedi", loaded.flying() && loaded.selectedSlot() == 3, "");

        int loadedBlocks = 0;
        for (Map<Integer, Byte> column : loaded.changes().values()) loadedBlocks += column.size();
        check("pocet zmen prezil ulozeni", loadedBlocks == 3, "" + loadedBlocks);

        check("inventar prezil ulozeni: pocet slotu",
                loaded.inventory().length == Inventory.SIZE, "" + loaded.inventory().length);
        check("inventar prezil ulozeni: plny stoh kamene",
                loaded.inventory()[0].block() == World.STONE && loaded.inventory()[0].count() == 64,
                loaded.inventory()[0].toString());
        check("inventar prezil ulozeni: rozdelana prkna",
                loaded.inventory()[4].block() == World.PLANKS && loaded.inventory()[4].count() == 7,
                loaded.inventory()[4].toString());
        check("inventar prezil ulozeni: slot v batohu",
                loaded.inventory()[30].block() == World.IRON_ORE, loaded.inventory()[30].toString());
        check("inventar prezil ulozeni: prazdne sloty zustaly prazdne",
                loaded.inventory()[1].isEmpty() && loaded.inventory()[35].isEmpty(), "");

        // ---------- 3) nacteny svet je shodny s puvodnim ----------
        World restored = new World();
        restored.loadRadius = 2;
        restored.unloadRadius = 4;
        restored.restoreChanges(loaded.changes());
        restored.updateBlocking(8f, 8f);

        int diff = 0;
        for (int x = -16; x < 32 && diff == 0; x++)
            for (int z = -16; z < 32 && diff == 0; z++)
                for (int y = 0; y < World.WORLD_HEIGHT; y++)
                    if (w.getBlock(x, y, z) != restored.getBlock(x, y, z)) { diff++; break; }

        check("nacteny svet je blok po bloku shodny s ulozenym", diff == 0, diff + " rozdilu");

        // A hlavne: zmeny opravdu sedi na spravnych mistech, ne jen ze se svety rovnaji.
        check("nacteny svet ma rozbity blok pryc", !restored.isSolid(8, surface, 8), "");
        check("nacteny svet ma polozena prkna",
                restored.getBlock(8, surface + 1, 8) == World.PLANKS, "");

        // ---------- 4) svet bez zmen se ulozi a nacte prazdny ----------
        World clean = new World();
        clean.loadRadius = 1;
        clean.unloadRadius = 3;
        clean.updateBlocking(8f, 8f);
        check("netknuty svet nema zadne zmeny", clean.changedBlockCount() == 0,
                "" + clean.changedBlockCount());

        Path emptyFile = dir.resolve("empty.dat");
        WorldStorage.save(emptyFile, new WorldStorage.Save(
                0, 0, 0, 0, 0, false, 0, clean.changes(), new ItemStack[Inventory.SIZE]));
        WorldStorage.Save emptyLoaded = WorldStorage.load(emptyFile);
        check("prazdny svet se nacte", emptyLoaded != null && emptyLoaded.changes().isEmpty(), "");

        // ---------- 5) zaporne souradnice ----------
        // Klic sloupce i index uvnitr nej musi fungovat i pod nulou.
        World neg = new World();
        neg.loadRadius = 1;
        neg.unloadRadius = 3;
        neg.updateBlocking(-100f, -100f);

        int negSurface = topSolid(neg, -100, -100);
        neg.placeBlock(-100, negSurface + 1, -100, World.SAND);

        Path negFile = dir.resolve("neg.dat");
        WorldStorage.save(negFile, new WorldStorage.Save(
                -100, 70, -100, 0, 0, false, 0, neg.changes(), new ItemStack[Inventory.SIZE]));

        WorldStorage.Save negLoaded = WorldStorage.load(negFile);
        World negRestored = new World();
        negRestored.loadRadius = 1;
        negRestored.unloadRadius = 3;
        negRestored.restoreChanges(negLoaded.changes());
        negRestored.updateBlocking(-100f, -100f);

        check("zmena v zapornych souradnicich prezije ulozeni",
                negRestored.getBlock(-100, negSurface + 1, -100) == World.SAND,
                "" + negRestored.getBlock(-100, negSurface + 1, -100));

        // ---------- 6) index uvnitr sloupce je obousmerny ----------
        boolean indexOk = true;
        for (int y = 0; y < World.WORLD_HEIGHT; y++)
            for (int lz = 0; lz < Chunk.SIZE; lz++)
                for (int lx = 0; lx < Chunk.SIZE; lx++) {
                    int i = World.columnIndex(lx, y, lz);
                    if (World.indexX(i) != lx || World.indexY(i) != y || World.indexZ(i) != lz)
                        indexOk = false;
                }
        check("columnIndex jde rozlozit zpatky pro celou vysku sveta", indexOk, "");

        oldGeneratorVersion(dir);

        w.shutdown();
        restored.shutdown();
        clean.shutdown();
        neg.shutdown();
        negRestored.shutdown();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    static int topSolid(World w, int x, int z) {
        for (int y = World.WORLD_HEIGHT - 1; y >= 0; y--) if (w.isSolid(x, y, z)) return y;
        return -1;
    }

    // ==================================================================
    // svet z nizsi GENERATOR_VERSION
    // ==================================================================

    /**
     * Svet ulozeny STARSIM generatorem se musi nacist, ne zahodit.
     *
     * Presne to, co zadani nazyva "zpetna kompatibilita": po zvyseni
     * GENERATOR_VERSION (biomy = 5) musi stary svet (4) dal jit otevrit,
     * hlavicka se precte, vsechny zmeny bloku i inventar dorazi beze zmeny
     * a na konzoli se o neshode verzi jen napise.
     *
     * ⚠️ CO SE NAOPAK ZMENI, A JE TO ZAMER. Nacteni je "vygeneruj a prepis
     * zmeny", takze teren pod stavbami se dopocita NOVYM generatorem a s biomy
     * vypada jinak. Zustavaji stavby, ne krajina - stejne jako u jeskyni, vody
     * a stromu drive. Test to overuje primo: postaveny blok je po nacteni na
     * svem miste, i kdyz se pod nim teren posunul.
     */
    static void oldGeneratorVersion(Path dir) throws IOException {
        System.out.println();

        Path file = dir.resolve("old-version.dat");

        // Soubor se zapise rucne, bajt po bajtu, s verzi 4 v hlavicce -
        // tedy presne tak, jak ho zapsala hra pred biomy. Volat save() by
        // neslo: ta zapisuje aktualni verzi.
        java.util.Map<Long, java.util.Map<Integer, Byte>> changes = new java.util.HashMap<>();
        java.util.Map<Integer, Byte> column = new java.util.HashMap<>();
        column.put(World.columnIndex(4, 70, 6), World.PLANKS);
        column.put(World.columnIndex(4, 71, 6), World.STONE_BRICKS);
        column.put(World.columnIndex(5, 70, 6), World.AIR);       // vykopana dira
        changes.put(World.key(0, 0), column);

        ItemStack[] inventory = new ItemStack[Inventory.SIZE];
        inventory[0] = ItemStack.of(World.IRON_ORE, 7);

        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(0x4D435732);        // MAGIC "MCW2"
            out.writeInt(4);                 // ⚠️ STARA verze generatoru
            out.writeFloat(8);  out.writeFloat(70); out.writeFloat(8);
            out.writeFloat(1.5f); out.writeFloat(-0.25f);
            out.writeBoolean(true);
            out.writeInt(3);

            out.writeInt(changes.size());
            for (Map.Entry<Long, Map<Integer, Byte>> c : changes.entrySet()) {
                out.writeLong(c.getKey());
                out.writeInt(c.getValue().size());
                for (Map.Entry<Integer, Byte> b : c.getValue().entrySet()) {
                    out.writeInt(b.getKey());
                    out.writeByte(b.getValue());
                }
            }

            out.writeInt(inventory.length);
            for (ItemStack stack : inventory) {
                ItemStack safe = stack == null ? ItemStack.EMPTY : stack;
                out.writeByte(safe.block());
                out.writeInt(safe.count());
            }
        }

        check("verze v hlavicce se opravdu lisi od dnesni",
                WorldStorage.GENERATOR_VERSION > 4, "" + WorldStorage.GENERATOR_VERSION);
        check("biomy zvysily GENERATOR_VERSION na 5", WorldStorage.GENERATOR_VERSION == 5,
                "" + WorldStorage.GENERATOR_VERSION);

        System.out.println("  (nize ocekavana hlaska o neshode verzi generatoru)");
        WorldStorage.Save loaded = WorldStorage.load(file);

        check("svet ze stare verze generatoru se NACTE, nezahodi", loaded != null, "");
        if (loaded == null) return;

        check("poloha a pohled prezily beze zmeny",
                loaded.x() == 8 && loaded.y() == 70 && loaded.z() == 8
                        && loaded.yaw() == 1.5f && loaded.pitch() == -0.25f
                        && loaded.flying() && loaded.selectedSlot() == 3, "");

        check("inventar prezil beze zmeny",
                loaded.inventory()[0].block() == World.IRON_ORE
                        && loaded.inventory()[0].count() == 7, "");

        Map<Integer, Byte> restoredColumn = loaded.changes().get(World.key(0, 0));
        check("vsechny tri zmeny bloku prisly beze zmeny",
                restoredColumn != null && restoredColumn.size() == 3
                        && restoredColumn.get(World.columnIndex(4, 70, 6)) == World.PLANKS
                        && restoredColumn.get(World.columnIndex(4, 71, 6)) == World.STONE_BRICKS
                        && restoredColumn.get(World.columnIndex(5, 70, 6)) == World.AIR,
                restoredColumn == null ? "sloupec chybi" : "" + restoredColumn.size());

        // A ted to hlavni: nasadit stare zmeny na NOVY generator.
        World world = new World();
        world.loadRadius = 1;
        world.unloadRadius = 3;
        world.restoreChanges(loaded.changes());
        world.updateBlocking(8f, 8f);

        check("stavby ze stareho sveta stoji i po zvyseni verze generatoru",
                world.getBlock(4, 70, 6) == World.PLANKS
                        && world.getBlock(4, 71, 6) == World.STONE_BRICKS,
                world.getBlock(4, 70, 6) + " / " + world.getBlock(4, 71, 6));
        check("i vykopana dira zustala vykopana", world.getBlock(5, 70, 6) == World.AIR,
                "" + world.getBlock(5, 70, 6));

        // Novy teren za hranici prozkoumaneho sveta uz biomy ma - to je ta
        // druha pulka zadani. Staci ukazat, ze se ve svete s tymz seedem
        // vyskytuje vic nez jeden biom a aspon jeden z novych povrchu.
        TerrainGenerator gen = world.generator();
        java.util.Set<Biome> seen = new java.util.HashSet<>();
        for (int x = -3000; x <= 3000; x += 97)
            for (int z = -3000; z <= 3000; z += 97) seen.add(gen.biomeAt(x, z));

        check("nove chunky tehoz sveta uz maji biomy", seen.size() == Biome.values().length,
                seen.size() + " z " + Biome.values().length);

        world.shutdown();
    }
}
