package mc;

/**
 * Overuje stromy.
 *
 * Nejdulezitejsi kontrola je ta na hranicich chunku: koruna je siroka az
 * 7 bloku (prales), takze strom stojici az tri bloky za hranici do sousedniho
 * sloupce porad zasahuje. Kdyby se prochazel jen vlastni sloupec, byly by na
 * kazde hranici useknute koruny - a je to chyba, ktera se pri behu hry lehko
 * prehledne, protoze vypada jako "tak proste ten strom takhle vyrostl".
 *
 * Od zavedeni biomu tu pribyl druhy oddil: DRUH A HUSTOTA stromu podle biomu.
 * Meri se statisticky pres stovky tisic pozic - hustota je pravdepodobnost na
 * bunku 8x8, takze jeden nalezeny strom by nedokazal vubec nic.
 */
public class TreeTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    static final int RADIUS = 3;

    static boolean isLog(byte b) {
        for (Biome.TreeType t : Biome.TreeType.values())
            if (t != Biome.TreeType.NONE && b == t.log) return true;
        return false;
    }

    static boolean isLeaves(byte b) {
        for (Biome.TreeType t : Biome.TreeType.values())
            if (t != Biome.TreeType.NONE && b == t.leaves) return true;
        return false;
    }

    /**
     * Hustota a druh stromu podle biomu - STATISTICKY, pres statisice pozic.
     *
     * Jeden strom nic nedokazuje: hustota je pravdepodobnost na bunku 8x8,
     * takze se da overit jen poctem na plose. Meri se pres generator, ne pres
     * nacteny svet, protoze okruh kolem pocatku nemusi obsahovat vsechny biomy.
     */
    static void biomeDensity(TerrainGenerator gen) {
        System.out.println();

        java.util.Map<Biome, long[]> stat = new java.util.EnumMap<>(Biome.class);
        for (Biome b : Biome.values()) stat.put(b, new long[2]);   // [povrch, stromy]

        // Vzorkuje se kazdy 5. radek z, takze jeden vzorek zastupuje 5 bloku
        // povrchu A zaroven 5 bloku, ve kterych by strom mohl stat - pomer
        // "bloku na strom" tim vyjde spravne i pri vzorkovani.
        for (int x = -2500; x <= 2500; x++)
            for (int z = -2500; z <= 2500; z += 5) {
                long[] cell = stat.get(gen.biomeAt(x, z));
                cell[0]++;
                if (gen.hasTree(x, z)) cell[1]++;
            }

        double jungle = 0, plains = 0, tundra = 0, birch = 0, taiga = 0, hills = 0;

        for (Biome b : Biome.values()) {
            long[] cell = stat.get(b);
            double perTree = cell[1] == 0 ? -1 : (double) cell[0] / cell[1];
            System.out.printf("  %-13s %-9s %s%n", b,
                    b.treeType() + "/" + b.treeDensity(),
                    perTree < 0 ? "bez stromu"
                            : String.format("1 strom na %.0f bloku povrchu", perTree));

            switch (b) {
                case JUNGLE -> jungle = perTree;
                case PLAINS -> plains = perTree;
                case TUNDRA -> tundra = perTree;
                case BIRCH_FOREST -> birch = perTree;
                case TAIGA -> taiga = perTree;
                case HILLS -> hills = perTree;
                default -> { }
            }
        }

        check("v pousti nevyrostl ani jeden strom", stat.get(Biome.DESERT)[1] == 0,
                "" + stat.get(Biome.DESERT)[1]);
        check("vzorku je dost, aby to bylo statisticky",
                stat.get(Biome.JUNGLE)[1] > 1000 && stat.get(Biome.TUNDRA)[1] > 100,
                stat.get(Biome.JUNGLE)[1] + " / " + stat.get(Biome.TUNDRA)[1]);

        // Poradi hustot je to, co zadani chtelo: prales nejhustsi, lesy
        // stredni, plane rideji, tundra nejridceji ze vsech, kde neco roste.
        check("prales je hustsi nez brezovy les i tajga",
                jungle < birch && jungle < taiga,
                String.format("%.0f vs %.0f / %.0f", jungle, birch, taiga));
        check("brezovy les a tajga jsou hustsi nez plane",
                birch < plains && taiga < plains,
                String.format("%.0f / %.0f vs %.0f", birch, taiga, plains));
        check("plane jsou hustsi nez kopce", plains < hills,
                String.format("%.0f vs %.0f", plains, hills));
        check("tundra je nejridsi ze vsech, kde neco roste",
                tundra > jungle && tundra > birch && tundra > taiga
                        && tundra > plains && tundra > hills,
                String.format("%.0f", tundra));

        // A prales musi byt opravdu hustsi nez byl svet pred biomy (1 na 109),
        // jinak by "hodne stromu" bylo jen na papire.
        check("prales je hustsi nez cely svet pred biomy (1 na 109)", jungle < 90,
                String.format("1 na %.0f", jungle));
    }

    public static void main(String[] args) {
        World w = new World();
        TerrainGenerator gen = w.generator();
        w.loadRadius = RADIUS;
        w.unloadRadius = RADIUS + 2;
        w.updateBlocking(8f, 8f);

        int lo = -RADIUS * Chunk.SIZE, hi = (RADIUS + 1) * Chunk.SIZE - 1;
        int area = (hi - lo + 1) * (hi - lo + 1);

        // ---------- kolik stromu ----------
        int trees = 0, logs = 0, leaves = 0;
        for (int x = lo; x <= hi; x++)
            for (int z = lo; z <= hi; z++) {
                if (gen.hasTree(x, z)) trees++;
                for (int y = 0; y < World.WORLD_HEIGHT; y++) {
                    byte b = w.getBlock(x, y, z);
                    if (isLog(b)) logs++;
                    else if (isLeaves(b)) leaves++;
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

        for (int x = lo + 5; x <= hi - 5; x++) {
            for (int z = lo + 5; z <= hi - 5; z++) {
                Biome.TreeType type = gen.treeTypeAt(x, z);
                if (type == null) continue;

                checkedTrees++;

                // Zasahuje koruna do jineho chunku nez kmen? Polomer se bere
                // z druhu stromu - pralesni koruna je sirsi nez dubova.
                int reach = type.maxRadius();
                boolean spans = (x - reach >> Chunk.BITS) != (x + reach >> Chunk.BITS)
                        || (z - reach >> Chunk.BITS) != (z + reach >> Chunk.BITS);
                if (spans) acrossBoundary++;

                int ground = gen.terrainHeight(x, z);

                // kmen
                if (w.getBlock(x, ground, z) != type.log) incomplete++;

                // Okno hledani vrcholu musi sahat az po NEJVYSSI kmen daneho
                // druhu (prales az 11 bloku). S pevnou osmickou z dob, kdy byl
                // jediny strom dub, by test u vysokeho stromu nasel "vrchol"
                // uprostred kmene a kontroloval korunu tam, kde zadna neni.
                int top = -1;
                for (int y = ground; y <= ground + type.trunkMax(); y++)
                    if (w.getBlock(x, y, z) == type.log) top = y;

                if (top < 0) { incomplete++; continue; }

                // Kazda vrstva koruny podle dat druhu, presne jako placeTree().
                int layers = type.layerRadius.length;
                for (int layer = 0; layer < layers; layer++) {
                    int y = top - (layers - 2) + layer;
                    int radius = type.layerRadius[layer];
                    boolean trim = type.layerTrim[layer];

                    for (int dx = -radius; dx <= radius; dx++)
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (trim && Math.abs(dx) == radius && Math.abs(dz) == radius) continue;
                            if (dx == 0 && dz == 0 && y <= top) continue;   // tam je kmen

                            // Listi ustoupi jen tomu, co uz tam bylo (jiny strom nebo teren).
                            if (w.getBlock(x + dx, y, z + dz) == World.AIR) incomplete++;
                        }
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
                if (!gen.hasTree(x, z)) continue;
                int ground = gen.terrainHeight(x, z);
                if (ground < World.SEA_LEVEL) inWater++;
                if (w.getBlock(x, ground - 1, z) == World.SAND) onSand++;
            }

        check("zadny strom neroste ve vode", inWater == 0, inWater + " stromu");
        check("zadny strom neroste na pisku", onSand == 0, onSand + " stromu");
        check("v pousti neroste nic (ma druh NONE)",
                Biome.DESERT.treeType() == Biome.TreeType.NONE
                        && Biome.DESERT.treeDensity() == 0, "");

        // ---------- kmen stoji na zemi, ne ve vzduchu ----------
        int floating = 0, buried = 0;
        for (int x = lo + 5; x <= hi - 5; x++)
            for (int z = lo + 5; z <= hi - 5; z++) {
                Biome.TreeType type = gen.treeTypeAt(x, z);
                if (type == null) continue;
                int ground = gen.terrainHeight(x, z);

                // pod kmenem musi byt pevna zem
                if (!w.isSolid(x, ground - 1, z)) floating++;
                // a nad korunou uz nic - vyska se bere z druhu, protoze
                // pralesni strom saha o pet bloku vys nez dub.
                if (w.getBlock(x, ground + type.totalHeight() + 1, z) != World.AIR) buried++;
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

        // Kazdy druh stromu musi mit vlastni, rozeznatelne dlazdice - jinak
        // by se biomy sice lisily v datech, ale ve hre by vypadaly stejne.
        boolean distinct = true;
        String clash = "";
        Biome.TreeType[] real = {Biome.TreeType.OAK, Biome.TreeType.BIRCH,
                Biome.TreeType.SPRUCE, Biome.TreeType.JUNGLE};

        for (Biome.TreeType a : real)
            for (Biome.TreeType b : real) {
                if (a == b) continue;
                if (a.leaves == b.leaves) { distinct = false; clash = a + "/" + b + " listi"; }
            }
        check("kazdy druh stromu ma vlastni listi", distinct, clash);

        check("briza i smrk maji vlastni dlazdici kury, jinou nez dub",
                BlockAtlas.tile(World.BIRCH_LOG, BlockAtlas.FACE_SIDE)
                        != BlockAtlas.tile(World.LOG, BlockAtlas.FACE_SIDE)
                        && BlockAtlas.tile(World.SPRUCE_LOG, BlockAtlas.FACE_SIDE)
                        != BlockAtlas.tile(World.LOG, BlockAtlas.FACE_SIDE)
                        && BlockAtlas.tile(World.SPRUCE_LOG, BlockAtlas.FACE_SIDE)
                        != BlockAtlas.tile(World.BIRCH_LOG, BlockAtlas.FACE_SIDE), "");
        check("kazde listi ma vlastni dlazdici",
                BlockAtlas.tile(World.BIRCH_LEAVES, BlockAtlas.FACE_SIDE)
                        != BlockAtlas.tile(World.LEAVES, BlockAtlas.FACE_SIDE)
                        && BlockAtlas.tile(World.SPRUCE_LEAVES, BlockAtlas.FACE_SIDE)
                        != BlockAtlas.tile(World.JUNGLE_LEAVES, BlockAtlas.FACE_SIDE), "");

        // Kmen bez receptu na prkna by znamenal, ze hrac, ktery zacne v tajze,
        // nema na prkna zdroj - viz pravidlo o dosazitelnosti receptu z terenu.
        for (byte log : new byte[]{World.LOG, World.BIRCH_LOG, World.SPRUCE_LOG}) {
            Container one = new Container(4);
            one.set(0, ItemStack.of(log, 1));
            ItemStack out = Recipes.match(one, 2, 2);
            check("kmen " + log + " da ctyri prkna",
                    out.block() == World.PLANKS && out.count() == 4, out.toString());
        }

        biomeDensity(gen);

        w.shutdown();
        w2.shutdown();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
