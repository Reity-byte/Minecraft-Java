package mc;

/**
 * Overuje mapovani blok+stena -> dlazdice, UV souradnice a obsah atlasu.
 *
 * Testovatelne je to proto, ze BlockAtlas je cista tabulka bez GL a generovani
 * pixelu je oddelene od jejich nahrani na grafiku (Textures.blockAtlasPixels).
 * Samotne vykresleni uz overi az spusteni hry.
 */
public class AtlasTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    static final int SIZE = BlockAtlas.ATLAS_PIXELS;
    static final int TILE = BlockAtlas.TILE_PIXELS;

    static int[] pixels;

    /** Pixel uvnitr dlazdice; y = 0 je DOLNI radek, stejne jako v generatoru. */
    static int texel(int tile, int x, int y) {
        int px = BlockAtlas.column(tile) * TILE + x;
        int py = BlockAtlas.row(tile) * TILE + y;
        return pixels[py * SIZE + px];
    }

    static int r(int argb) { return (argb >> 16) & 0xFF; }
    static int g(int argb) { return (argb >> 8) & 0xFF; }
    static int b(int argb) { return argb & 0xFF; }
    static int alpha(int argb) { return (argb >>> 24) & 0xFF; }

    static boolean isCrack(int tile) {
        return tile >= BlockAtlas.TILE_CRACK_FIRST
                && tile < BlockAtlas.TILE_CRACK_FIRST + BlockAtlas.CRACK_STAGES;
    }

    public static void main(String[] args) {
        // ---------- mapovani blok -> dlazdice ----------
        byte[] blocks = {World.GRASS, World.STONE, World.DIRT, World.SAND, World.PLANKS};
        int[] faces = {BlockAtlas.FACE_TOP, BlockAtlas.FACE_BOTTOM, BlockAtlas.FACE_SIDE};

        for (byte id : blocks) {
            for (int face : faces) {
                int tile = BlockAtlas.tile(id, face);
                check("blok " + id + " stena " + face + " ma platnou dlazdici",
                        tile >= 0 && tile < BlockAtlas.TILE_COUNT, "tile=" + tile);
            }
        }

        // Trava je jediny blok, ktery ma tri ruzne steny - kvuli tomu cely atlas vznikl.
        int gTop = BlockAtlas.tile(World.GRASS, BlockAtlas.FACE_TOP);
        int gSide = BlockAtlas.tile(World.GRASS, BlockAtlas.FACE_SIDE);
        int gBottom = BlockAtlas.tile(World.GRASS, BlockAtlas.FACE_BOTTOM);

        check("trava: vrsek se lisi od boku", gTop != gSide, gTop + " vs " + gSide);
        check("trava: vrsek se lisi od spodku", gTop != gBottom, gTop + " vs " + gBottom);
        check("trava: spodek je hlina", gBottom == BlockAtlas.TILE_DIRT, "" + gBottom);

        // Bloky bez vlastni textury musi spadnout na krik lavou dlazdici, ne tise na nulu.
        check("neznamy blok -> TILE_UNKNOWN",
                BlockAtlas.tile((byte) 99, BlockAtlas.FACE_SIDE) == BlockAtlas.TILE_UNKNOWN, "");

        // Kamen, pisek a prkna maji na vsech stenach totez.
        for (byte id : new byte[]{World.STONE, World.SAND, World.DIRT, World.PLANKS}) {
            check("blok " + id + " ma vsechny steny stejne",
                    BlockAtlas.tile(id, BlockAtlas.FACE_TOP) == BlockAtlas.tile(id, BlockAtlas.FACE_SIDE)
                            && BlockAtlas.tile(id, BlockAtlas.FACE_SIDE) == BlockAtlas.tile(id, BlockAtlas.FACE_BOTTOM),
                    "");
        }

        // ---------- UV souradnice ----------
        for (int tile = 0; tile < BlockAtlas.TILE_COUNT; tile++) {
            float u0 = BlockAtlas.u0(tile), u1 = BlockAtlas.u1(tile);
            float v0 = BlockAtlas.v0(tile), v1 = BlockAtlas.v1(tile);

            check("dlazdice " + tile + ": UV je uvnitr atlasu",
                    u0 > 0f && u1 < 1f && v0 > 0f && v1 < 1f,
                    String.format("u %.4f-%.4f v %.4f-%.4f", u0, u1, v0, v1));

            check("dlazdice " + tile + ": UV neni prohozene", u0 < u1 && v0 < v1, "");

            // Stred UV musi padnout zpatky do te same bunky mrizky - to je
            // hlavni vec, kterou by rozbil preklep v poctech radku a sloupcu.
            int cellX = (int) ((u0 + u1) / 2f * SIZE) / TILE;
            int cellY = (int) ((v0 + v1) / 2f * SIZE) / TILE;
            check("dlazdice " + tile + ": stred UV miri do sve bunky",
                    cellX == BlockAtlas.column(tile) && cellY == BlockAtlas.row(tile),
                    cellX + "," + cellY + " ocekavano " + BlockAtlas.column(tile) + "," + BlockAtlas.row(tile));

            // Pultexelove zuzeni: rozsah musi zacinat ZA hranou bunky a koncit PRED ni.
            float leftEdge = BlockAtlas.column(tile) * (float) TILE / SIZE;
            float rightEdge = (BlockAtlas.column(tile) + 1) * (float) TILE / SIZE;
            check("dlazdice " + tile + ": UV je zuzene dovnitr (proti prosakovani)",
                    u0 > leftEdge && u1 < rightEdge, "");
        }

        // Zadne dve dlazdice nesmi sdilet bunku - jinak by dva bloky vypadaly stejne.
        boolean overlap = false;
        for (int a = 0; a < BlockAtlas.TILE_COUNT && !overlap; a++)
            for (int c = a + 1; c < BlockAtlas.TILE_COUNT; c++)
                if (BlockAtlas.column(a) == BlockAtlas.column(c)
                        && BlockAtlas.row(a) == BlockAtlas.row(c)) overlap = true;
        check("zadne dve dlazdice nesdili bunku", !overlap, "");

        check("vsechny dlazdice se vejdou do mrizky",
                BlockAtlas.TILE_COUNT <= BlockAtlas.TILES_PER_ROW * BlockAtlas.TILES_PER_ROW,
                BlockAtlas.TILE_COUNT + " z " + (BlockAtlas.TILES_PER_ROW * BlockAtlas.TILES_PER_ROW));

        // ---------- obsah atlasu ----------
        pixels = Textures.blockAtlasPixels();
        check("atlas ma spravnou velikost", pixels.length == SIZE * SIZE, "" + pixels.length);

        // Voda je JEDINA dlazdice s alfou pod 255 - fragment shader si z ni bere
        // pruhlednost. Kdyby ji nedopatrenim dostala i jina, kreslila by se
        // v neprusvitnem pruchodu a alfa by se tise zahodila.
        boolean strayAlpha = false;
        for (int tile = 0; tile < BlockAtlas.TILE_COUNT; tile++) {
            if (tile == BlockAtlas.TILE_WATER || isCrack(tile)) continue;
            for (int y = 0; y < TILE; y++)
                for (int x = 0; x < TILE; x++)
                    if (alpha(texel(tile, x, y)) != 0xFF) strayAlpha = true;
        }
        check("vsechny dlazdice krome vody a prasklin jsou kryci", !strayAlpha, "");

        boolean waterTranslucent = true;
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int a2 = alpha(texel(BlockAtlas.TILE_WATER, x, y));
                if (a2 == 0xFF || a2 < 0x40) waterTranslucent = false;
            }
        check("voda je pruhledna, ale ne neviditelna", waterTranslucent,
                Integer.toHexString(alpha(texel(BlockAtlas.TILE_WATER, 0, 0))));

        // ---------- praskliny ----------
        // ⚠️ Vzor musi RUST: co je prasklé ve stadiu s, musi byt prasklé i v s+1.
        // Kdyby se kazde stadium losovalo zvlast, praskliny by pri kopani
        // poskakovaly misto toho, aby se prohlubovaly.
        int shrinking = 0;
        int previousCracked = -1;
        boolean growing = true;

        for (int stage = 0; stage < BlockAtlas.CRACK_STAGES; stage++) {
            int tile = BlockAtlas.TILE_CRACK_FIRST + stage;
            int cracked = 0;

            for (int y = 0; y < TILE; y++)
                for (int x = 0; x < TILE; x++) {
                    boolean now = alpha(texel(tile, x, y)) > 0;
                    if (now) cracked++;

                    // pixel prasklý driv nesmi byt v dalsim stadiu cely
                    if (stage > 0 && !now
                            && alpha(texel(tile - 1, x, y)) > 0) shrinking++;
                }

            if (cracked <= previousCracked) growing = false;
            previousCracked = cracked;
        }

        check("praskliny se nikdy nevraci (pixel jednou prasklý zustane)",
                shrinking == 0, shrinking + " pixelu zmizelo");
        check("kazde dalsi stadium ma vic prasklin", growing, "");

        int firstStage = 0, lastStage = 0;
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                if (alpha(texel(BlockAtlas.TILE_CRACK_FIRST, x, y)) > 0) firstStage++;
                if (alpha(texel(BlockAtlas.TILE_CRACK_FIRST
                        + BlockAtlas.CRACK_STAGES - 1, x, y)) > 0) lastStage++;
            }

        System.out.printf("Praskliny: prvni stadium %d z 256 pixelu, posledni %d%n",
                firstStage, lastStage);
        check("prvni stadium je jen nataknuti", firstStage > 0 && firstStage < 60, "" + firstStage);
        check("posledni stadium je vyrazne, ale blok jde porad videt",
                lastStage > 100 && lastStage < 220, "" + lastStage);

        // Kazda dlazdice musi mit aspon dva odstiny - jednobarevna by znamenala,
        // ze se generator na ni vubec nedostal.
        for (int tile = 0; tile < BlockAtlas.TILE_COUNT; tile++) {
            int first = texel(tile, 0, 0);
            boolean varied = false;
            for (int y = 0; y < TILE && !varied; y++)
                for (int x = 0; x < TILE; x++)
                    if (texel(tile, x, y) != first) { varied = true; break; }
            check("dlazdice " + tile + " neni jednobarevna", varied, "");
        }

        // Barvy odpovidaji tomu, co dlazdice predstavuje.
        boolean grassGreen = true, dirtBrown = true, stoneGray = true;
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                int gt = texel(BlockAtlas.TILE_GRASS_TOP, x, y);
                if (!(g(gt) > r(gt) && g(gt) > b(gt))) grassGreen = false;

                int d = texel(BlockAtlas.TILE_DIRT, x, y);
                if (!(r(d) > g(d) && g(d) > b(d))) dirtBrown = false;

                int st = texel(BlockAtlas.TILE_STONE, x, y);
                if (!(r(st) == g(st) && g(st) == b(st))) stoneGray = false;
            }
        }
        check("grass_top je zeleny v celé dlazdici", grassGreen, "");
        check("dirt je hnedy v celé dlazdici", dirtBrown, "");
        check("stone je sedy (r == g == b)", stoneGray, "");

        // grass_side: dole hlina, nahore trava. Kdyby se prehodila orientace y,
        // travnaty pruh by skoncil pod zemi a bylo by to hned videt.
        int bottom = texel(BlockAtlas.TILE_GRASS_SIDE, 0, 0);
        check("grass_side ma dole hlinu", r(bottom) > g(bottom) && g(bottom) > b(bottom),
                Integer.toHexString(bottom));

        int top = texel(BlockAtlas.TILE_GRASS_SIDE, 0, TILE - 1);
        check("grass_side ma nahore travu", g(top) > r(top) && g(top) > b(top),
                Integer.toHexString(top));

        // Neznama dlazdice musi byt videt na kilometr.
        boolean magenta = false;
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int p = texel(BlockAtlas.TILE_UNKNOWN, x, y);
                if (r(p) > 200 && g(p) < 50 && b(p) > 200) magenta = true;
            }
        check("TILE_UNKNOWN obsahuje magentu", magenta, "");

        // ---------- generovani je deterministicke ----------
        int[] again = Textures.blockAtlasPixels();
        boolean same = true;
        for (int i = 0; i < pixels.length; i++) if (pixels[i] != again[i]) same = false;
        check("atlas vyjde dvakrat stejne (hash, ne Random)", same, "");

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
