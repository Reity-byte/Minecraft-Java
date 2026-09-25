package mc;

/**
 * Pec: bloky s cely (4 natoceni), polozeni celem k hraci, textury sten,
 * recept, predmet, zeleny ingot.
 */
public class FurnaceTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws java.io.IOException {
        blocks();
        smelting();
        storage();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    static FurnaceState furnace(ItemStack input, ItemStack fuel) {
        FurnaceState f = new FurnaceState();
        f.slots.set(FurnaceState.INPUT, input);
        f.slots.set(FurnaceState.FUEL, fuel);
        return f;
    }

    static void run(FurnaceState f, float seconds) {
        for (float t = 0; t < seconds - 1e-4f; t += 1f / 60f) f.update(1f / 60f);
    }

    static void smelting() {
        check("recepty: ruda na ingot, kmeny na uhli, kamen se netavi",
                Smelting.resultOf(ItemStack.of(World.IRON_ORE, 3)).equals(ItemStack.of(ItemRegistry.IRON_INGOT, 1))
                        && Smelting.resultOf(ItemStack.of(World.SPRUCE_LOG, 1)).id() == ItemRegistry.COAL
                        && Smelting.resultOf(ItemStack.of(World.STONE, 1)).isEmpty(), "");
        check("palivo: uhli 80 s, prkna 15 s, klacek 5 s, kamen nic",
                Fuel.seconds(ItemRegistry.COAL) == 80f && Fuel.seconds(World.PLANKS) == 15f
                        && Fuel.seconds(ItemRegistry.STICK) == 5f && !Fuel.isFuel(World.STONE), "");

        FurnaceState f = furnace(ItemStack.of(World.IRON_ORE, 3), ItemStack.of(ItemRegistry.COAL, 1));
        run(f, 9.9f);
        check("za 9,9 s jeste nic, ohen hori a uhli se spotrebovalo",
                f.slots.get(FurnaceState.OUTPUT).isEmpty() && f.isBurning() && f.slots.get(FurnaceState.FUEL).isEmpty()
                        && f.progress() > 0.95f, f.progress() + "");
        run(f, 0.2f);
        check("za 10 s prvni ingot, surovina ubyla",
                f.slots.get(FurnaceState.OUTPUT).equals(ItemStack.of(ItemRegistry.IRON_INGOT, 1))
                        && f.slots.get(FurnaceState.INPUT).count() == 2, f.slots.get(FurnaceState.OUTPUT).toString());
        run(f, 20f);
        check("za 30 s vsechny tri, ohen hori dal (uhli vydrzi 80 s)",
                f.slots.get(FurnaceState.OUTPUT).count() == 3 && f.slots.get(FurnaceState.INPUT).isEmpty()
                        && f.isBurning() && f.progress() == 0f, "");
        run(f, 60f);
        check("uhli dohori i bez suroviny", !f.isBurning(), "");

        FurnaceState idle = furnace(ItemStack.EMPTY, ItemStack.of(ItemRegistry.COAL, 5));
        run(idle, 5f);
        check("bez suroviny se palivo nezapali", !idle.isBurning() && idle.slots.get(FurnaceState.FUEL).count() == 5, "");
        FurnaceState stone = furnace(ItemStack.of(World.STONE, 5), ItemStack.of(ItemRegistry.COAL, 5));
        run(stone, 5f);
        check("s netavitelnou surovinou taky ne", !stone.isBurning(), "");

        FurnaceState sticks = furnace(ItemStack.of(World.IRON_ORE, 5), ItemStack.of(ItemRegistry.STICK, 3));
        run(sticks, 15f);
        check("tri klacky (3 x 5 s) utavi jeden ingot a pul dalsiho, pak ohen zhasne",
                sticks.slots.get(FurnaceState.OUTPUT).count() == 1 && !sticks.isBurning()
                        && sticks.slots.get(FurnaceState.FUEL).isEmpty(), sticks.slots.get(FurnaceState.OUTPUT).toString());
        float before = sticks.progress();
        run(sticks, 1f);
        check("bez ohne postup klesa", before > 0f && sticks.progress() < before, before + " -> " + sticks.progress());

        FurnaceState full = furnace(ItemStack.of(World.IRON_ORE, 5), ItemStack.of(ItemRegistry.COAL, 1));
        full.slots.set(FurnaceState.OUTPUT, ItemStack.of(ItemRegistry.IRON_INGOT, 64));
        run(full, 5f);
        check("plny vystup: pec se nezapali", !full.isBurning() && full.slots.get(FurnaceState.FUEL).count() == 1, "");
        full.slots.set(FurnaceState.OUTPUT, ItemStack.of(ItemRegistry.COAL, 1));
        run(full, 5f);
        check("jina vec ve vystupu: taky ne", !full.isBurning(), "");

        FurnaceState burst = furnace(ItemStack.of(World.IRON_ORE, 10), ItemStack.of(ItemRegistry.STICK, 1));
        burst.update(30f);   // zaseknuty frame
        check("dlouhy dt: klacek (5 s) neutavi 3 kusy na dluh",
                burst.slots.get(FurnaceState.OUTPUT).isEmpty() && !burst.isBurning(), burst.slots.get(FurnaceState.OUTPUT).toString());
    }

    static void storage() throws java.io.IOException {
        Furnaces all = new Furnaces();
        FurnaceState a = all.create(8, 101, -3);
        a.slots.set(FurnaceState.INPUT, ItemStack.of(World.IRON_ORE, 4));
        a.slots.set(FurnaceState.FUEL, ItemStack.of(ItemRegistry.COAL, 2));
        run(a, 3f);
        all.create(-1000, 5, 70000);
        check("create je idempotentni", all.create(8, 101, -3) == a && all.size() == 2, "");

        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("mc-furnace");
        java.nio.file.Path file = dir.resolve("world.dat");
        WorldStorage.save(file, new WorldStorage.Save(8, 70, 8, 0, 0, false, 0,
                new java.util.HashMap<>(), new ItemStack[0], 100f, all.snapshot()));
        WorldStorage.Save loaded = WorldStorage.load(file);
        Furnaces back = new Furnaces();
        back.restore(loaded.furnaces());
        FurnaceState b = back.at(8, 101, -3);
        check("pece prezily ulozeni (i zaporne a vzdalene souradnice)",
                back.size() == 2 && back.at(-1000, 5, 70000) != null && b != null
                        && b.slots.get(FurnaceState.INPUT).equals(a.slots.get(FurnaceState.INPUT))
                        && b.slots.get(FurnaceState.FUEL).equals(a.slots.get(FurnaceState.FUEL))
                        && b.isBurning() && Math.abs(b.progress() - a.progress()) < 1e-6f, "");
        java.nio.file.Files.deleteIfExists(file);
        java.nio.file.Files.deleteIfExists(dir);

        java.util.List<ItemStack> dropped = back.remove(8, 101, -3);
        check("rozbita pec vrati obsah a zmizi", dropped.size() == 2 && back.at(8, 101, -3) == null && back.size() == 1, "");
        check("pec bez obsahu nevrati nic", back.remove(-1000, 5, 70000).isEmpty(), "");

        Furnaces odd = new Furnaces();
        odd.restore(java.util.List.of(new Furnaces.Saved(1, 2, 3, new ItemStack[]{ItemStack.EMPTY},
                Float.NaN, -5f, 999f)));
        FurnaceState o = odd.at(1, 2, 3);
        check("nesmysly ze souboru se srovnaji (NaN, zaporne, prilis velke tavení)",
                o != null && !o.isBurning() && o.progress() == 1f && o.slots.get(FurnaceState.FUEL).isEmpty(), "");

        // Recepty z labu: jen tam, kde vestaveny neni; vysledek se vejde do slotu.
        SmeltBook book = SmeltBook.empty().with(new Smelting.Recipe(World.SAND, World.STONE, 1));
        check("tavení z labu: vlastni surovina projde, vestavena ne",
                SmeltBook.validate(new Smelting.Recipe(World.IRON_ORE, World.STONE, 1)) != null
                        && book.find(World.SAND) != null, "");
        SmeltBook.activate(book);
        check("pec pouzije recept z labu", Smelting.resultOf(ItemStack.of(World.SAND, 1)).id() == World.STONE, "");
        check("smelting.json tam a zpet", SmeltBook.fromJson(book.toJson()).find(World.SAND) != null
                && SmeltBook.fromJson(book.toJson()).size() == 1, "");
        SmeltBook.activate(SmeltBook.empty());
        check("bez knihy se pisek netavi", Smelting.resultOf(ItemStack.of(World.SAND, 1)).isEmpty(), "");
    }

    static void blocks() {
        byte[] all = {World.FURNACE, World.FURNACE_WEST, World.FURNACE_NORTH, World.FURNACE_EAST};

        boolean allFurnace = true, oneFront = true, sameName = true, stone = true;
        for (byte f : all) {
            allFurnace &= World.isFurnace(f) && World.canonical(f) == World.FURNACE && World.isOpaque(f)
                    && World.blocksMovement(f) && World.hardness(f) == 3.5f;
            int fronts = 0;
            for (int face = BlockAtlas.FACE_EAST; face <= BlockAtlas.FACE_NORTH; face++) {
                if (BlockAtlas.tile(f, face) == BlockAtlas.TILE_FURNACE_FRONT) fronts++;
            }
            oneFront &= fronts == 1 && BlockAtlas.tile(f, World.furnaceFront(f)) == BlockAtlas.TILE_FURNACE_FRONT
                    && BlockAtlas.tile(f, BlockAtlas.FACE_TOP) == BlockAtlas.TILE_FURNACE_TOP
                    && BlockAtlas.tile(f, BlockAtlas.FACE_SIDE) == BlockAtlas.TILE_FURNACE_SIDE;
            sameName &= TextureLab.blockName(f).equals("Furnace");
            stone &= Sound.Material.of(f) == Sound.Material.STONE;
        }
        check("ctyri pece: pevne, neprusvitne, 3,5 s, kanonicky FURNACE", allFurnace, "");
        check("celo je prave na jedne strane, vrsek a boky vlastni dlazdice", oneFront, "");
        check("vsechny se jmenuji Furnace a zni jako kamen", sameName && stone, "");
        check("jen FURNACE je predmet; natoceni jsou varianty",
                !World.isVariant(World.FURNACE) && World.isVariant(World.FURNACE_EAST)
                        && CreativeInventory.blocks(BlockRegistry.empty()).contains(World.FURNACE)
                        && !CreativeInventory.blocks(BlockRegistry.empty()).contains(World.FURNACE_WEST), "");
        check("ostatni bloky smerove boky nerozlisuji",
                BlockAtlas.tile(World.LOG, BlockAtlas.FACE_EAST) == BlockAtlas.tile(World.LOG, BlockAtlas.FACE_SIDE)
                        && BlockAtlas.tile(World.GRASS, BlockAtlas.FACE_NORTH) == BlockAtlas.TILE_GRASS_SIDE, "");

        // Pohled -Z (sever): celo k hraci = na jih (+Z). Pohled +X: celo na -X.
        check("polozeni celem k hraci",
                World.orientPlaced(World.FURNACE, 0, -1) == World.FURNACE
                        && World.orientPlaced(World.FURNACE, 0, 1) == World.FURNACE_NORTH
                        && World.orientPlaced(World.FURNACE, 1, 0.3f) == World.FURNACE_WEST
                        && World.orientPlaced(World.FURNACE, -1, 0.3f) == World.FURNACE_EAST, "");
        check("celo mizi na hrace: pohled +X -> celo na zapad (-X)",
                World.furnaceFront(World.orientPlaced(World.FURNACE, 1, 0)) == BlockAtlas.FACE_WEST, "");
        check("ostatni bloky se neotaci", World.orientPlaced(World.STONE, 1, 0) == World.STONE, "");
        check("z natocene pece pada pec", Mining.dropOf(World.FURNACE_NORTH) == World.FURNACE
                && Mining.dropOf(World.FURNACE) == World.FURNACE, "");

        Container ring = new Container(9);
        for (int i = 0; i < 9; i++) if (i != 4) ring.set(i, ItemStack.of(World.STONE, 1));
        ItemStack furnace = Recipes.match(ring, 3, 3);
        check("osm kamenu do kruhu = pec", furnace.id() == World.FURNACE && furnace.count() == 1, furnace.toString());
        ring.set(4, ItemStack.of(World.STONE, 1));
        check("s kamenem uprostred ne", Recipes.match(ring, 3, 3).isEmpty(), "");

        int[] atlas = Textures.blockAtlasPixels();
        check("dlazdice pece jsou namalovane (ne sachovnice)",
                !Textures.tileEmpty(atlas, BlockAtlas.TILE_FURNACE_FRONT)
                        && atlas[AtlasEditor.pixelIndex(BlockAtlas.TILE_FURNACE_FRONT, 7, 5)] == 0xFF141414
                        && atlas[AtlasEditor.pixelIndex(BlockAtlas.TILE_FURNACE_SIDE, 7, 5)] != 0xFF141414, "");

        check("zelezny ingot je vestaveny predmet s vlastni dlazdici",
                Items.exists(ItemRegistry.IRON_INGOT) && Items.name(ItemRegistry.IRON_INGOT).equals("Iron ingot")
                        && !Textures.tileEmpty(ItemTextures.procedural(), ItemRegistry.TILE_IRON_INGOT), "");
    }
}
