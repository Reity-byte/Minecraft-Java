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

    public static void main(String[] args) {
        blocks();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
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
