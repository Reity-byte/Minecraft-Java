package mc;

/**
 * Overuje stromy.
 *
 * Nejdulezitejsi kontrola je ta na hranicich chunku: koruna je siroka 5 bloku,
 * takze strom stojici az dva bloky za hranici do sousedniho sloupce porad
 * zasahuje. Kdyby se prochazel jen vlastni sloupec, byly by na kazde hranici
 * useknute koruny - a je to chyba, ktera se pri behu hry lehko prehledne,
 * protoze vypada jako "tak proste ten strom takhle vyrostl".
 */
public class TreeTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    static final int RADIUS = 3;

    public static void main(String[] args) {
        World w = new World();
        w.loadRadius = RADIUS;
        w.unloadRadius = RADIUS + 2;
        w.updateBlocking(8f, 8f);

        int lo = -RADIUS * Chunk.SIZE, hi = (RADIUS + 1) * Chunk.SIZE - 1;
        int area = (hi - lo + 1) * (hi - lo + 1);

        // ---------- kolik stromu ----------
        int trees = 0, logs = 0, leaves = 0;
        for (int x = lo; x <= hi; x++)
            for (int z = lo; z <= hi; z++) {
                if (World.hasTree(x, z)) trees++;
                for (int y = 0; y < World.WORLD_HEIGHT; y++) {
                    byte b = w.getBlock(x, y, z);
                    if (b == World.LOG) logs++;
                    else if (b == World.LEAVES) leaves++;
                }
            }

        System.out.printf("%nStromy: %d na %d blocich povrchu (1 na %.0f), %d kmenu, %d listi%n",
                trees, area, trees == 0 ? 0 : (double) area / trees, logs, leaves);

        check("stromy vubec rostou", trees > 0, "" + trees);
        check("stromu neni les na kazdem bloku", (double) area / trees > 20,
                String.format("1 na %.0f", (double) area / trees));
        check("stromu neni tak malo, ze se nedaji najit", (double) area / trees < 400,
                String.format("1 na %.0f", (double) area / trees));
        check("kazdy strom ma kmen i listi", logs > 0 && leaves > logs, logs + " / " + leaves);

        // ---------- ⚠️ uplnost pres hranice chunku ----------
        // Pro kazdy kmen v oblasti se overi, ze cela jeho koruna existuje.
        // Vnitrni okraj se vynecha, aby se nekontrolovaly stromy sahajici
        // za nactenou oblast.
        int incomplete = 0, checkedTrees = 0, acrossBoundary = 0;

        for (int x = lo + 4; x <= hi - 4; x++) {
            for (int z = lo + 4; z <= hi - 4; z++) {
                if (!World.hasTree(x, z)) continue;

                checkedTrees++;

                // Zasahuje koruna do jineho chunku nez kmen?
                boolean spans = (x - 2 >> Chunk.BITS) != (x + 2 >> Chunk.BITS)
                        || (z - 2 >> Chunk.BITS) != (z + 2 >> Chunk.BITS);
                if (spans) acrossBoundary++;

                int ground = World.terrainHeight(x, z);

                // kmen
                if (w.getBlock(x, ground, z) != World.LOG) incomplete++;

                // listi v obou sirokych vrstvach - tam koruna prekracuje hranici
                int top = -1;
                for (int y = ground; y < ground + 8; y++)
                    if (w.getBlock(x, y, z) == World.LOG) top = y;

                if (top < 0) { incomplete++; continue; }

                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++) {
                        if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;   // uriznuty roh
                        if (dx == 0 && dz == 0) continue;                        // tam je kmen

                        byte b = w.getBlock(x + dx, top - 2, z + dz);
                        // Listi ustoupi jen tomu, co uz tam bylo (jiny strom nebo teren).
                        if (b == World.AIR) incomplete++;
                    }
            }
        }

        System.out.printf("Zkontrolovano %d stromu, z toho %d presahuje hranici chunku%n",
                checkedTrees, acrossBoundary);

        check("nejaky strom opravdu presahuje hranici chunku (test neni degenerovany)",
                acrossBoundary > 0, "" + acrossBoundary);
        check("zadna koruna neni useknuta na hranici chunku", incomplete == 0,
                incomplete + " chybejicich bloku");

        // ---------- stromy jen na trave ----------
        int inWater = 0, onSand = 0;
        for (int x = lo; x <= hi; x++)
            for (int z = lo; z <= hi; z++) {
                if (!World.hasTree(x, z)) continue;
                int ground = World.terrainHeight(x, z);
                if (ground < World.SEA_LEVEL) inWater++;
                if (w.getBlock(x, ground - 1, z) == World.SAND) onSand++;
            }

        check("zadny strom neroste ve vode", inWater == 0, inWater + " stromu");
        check("zadny strom neroste na pisku", onSand == 0, onSand + " stromu");

        // ---------- kmen stoji na zemi, ne ve vzduchu ----------
        int floating = 0, buried = 0;
        for (int x = lo + 4; x <= hi - 4; x++)
            for (int z = lo + 4; z <= hi - 4; z++) {
                if (!World.hasTree(x, z)) continue;
                int ground = World.terrainHeight(x, z);

                // pod kmenem musi byt pevna zem
                if (!w.isSolid(x, ground - 1, z)) floating++;
                // a nad korunou uz nic
                if (w.getBlock(x, ground + 10, z) != World.AIR) buried++;
            }

        check("zadny strom nevisi ve vzduchu", floating == 0, floating + " stromu");
        check("zadny strom neni zahrabany", buried == 0, buried + " stromu");

        // ---------- determinismus ----------
        World w2 = new World();
        w2.loadRadius = 1;
        w2.unloadRadius = 3;
        w2.updateBlocking(8f, 8f);

        boolean same = true;
        for (int x = 0; x < Chunk.SIZE && same; x++)
            for (int z = 0; z < Chunk.SIZE && same; z++)
                for (int y = 0; y < World.WORLD_HEIGHT; y++)
                    if (w.getBlock(x, y, z) != w2.getBlock(x, y, z)) { same = false; break; }

        check("stromy vyjdou dvakrat stejne", same, "");

        // ---------- crafting: kmen -> prkna -> stul ----------
        // Tohle je duvod, proc stromy vznikly: bez nich nemela prkna v terenu
        // zdroj a kanonicky recept na crafting table byl nedosazitelny.
        Container grid = new Container(4);
        grid.set(0, ItemStack.of(World.LOG, 1));
        ItemStack planks = Recipes.match(grid, 2, 2);
        check("kmen da ctyri prkna",
                planks.block() == World.PLANKS && planks.count() == 4, planks.toString());

        grid.clear();
        for (int i = 0; i < 4; i++) grid.set(i, ItemStack.of(World.PLANKS, 1));
        check("ctyri prkna daji crafting table",
                Recipes.match(grid, 2, 2).block() == World.CRAFTING_TABLE, "");

        // ---------- dlazdice ----------
        check("kmen ma jinou dlazdici z boku nez shora",
                BlockAtlas.tile(World.LOG, BlockAtlas.FACE_SIDE)
                        != BlockAtlas.tile(World.LOG, BlockAtlas.FACE_TOP), "");
        check("listi ma vsude stejnou dlazdici",
                BlockAtlas.tile(World.LEAVES, BlockAtlas.FACE_TOP)
                        == BlockAtlas.tile(World.LEAVES, BlockAtlas.FACE_SIDE), "");
        check("listi je neprusvitne (kresli se v neprusvitnem pruchodu)",
                World.isOpaque(World.LEAVES), "");

        w.shutdown();
        w2.shutdown();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
