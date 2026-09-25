package mc;

/**
 * Geometrie izometrické ikony bloku (BlockIcon.build) bez GL.
 *
 * ---------------------------------------------------------------------------
 * PROČ TENHLE TEST JE. Dokud stavba vrcholů byla metoda instance, nešla bez
 * okna ověřit - GL je už v konstruktoru BlockIcon. Pořadí rohů (s face
 * cullingem by špatně otočený trojúhelník potichu zmizel, jako kdysi celá
 * obloha), UV z dlaždice a odstíny stěn tak hlídal jen pohled do hotbaru.
 * ---------------------------------------------------------------------------
 */
public class BlockIconTest {

    static int failures = 0;

    static final int F = BlockIcon.FLOATS_PER_VERTEX;
    static final float X = 100, Y = 50, SIZE = 32;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    static float[] icon(byte block) {
        float[] out = new float[BlockIcon.floatsFor(block)];
        int written = BlockIcon.build(block, X, Y, SIZE, out, 0);
        check("blok " + block + ": zapise presne floatsFor()", written == out.length, written + " z " + out.length);
        return out;
    }

    /** Dvojnásobná znaménková plocha trojúhelníku (osa y nahoru). */
    static float area2(float[] v, int triangle) {
        int a = triangle * 3 * F, b = a + F, c = b + F;
        return (v[b] - v[a]) * (v[c + 1] - v[a + 1]) - (v[c] - v[a]) * (v[b + 1] - v[a + 1]);
    }

    public static void main(String[] args) {
        BlockRegistry before = BlockRegistry.active();

        try {
            BlockRegistry.activate(BlockRegistry.empty());
            geometry();
            faces();
            translucency();
        } finally {
            BlockRegistry.activate(before);
        }

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ------------------------------------------------------------------

    static void geometry() {
        System.out.println("\n-- tvar: poradi rohu, meze, plocha --");

        for (byte block : new byte[]{World.STONE, World.GRASS, World.TORCH, World.FENCE, World.WATER}) {
            float[] v = icon(block);
            int triangles = v.length / F / 3;

            // Proti smeru hodinovych rucicek = licova strana. Zapnuty face
            // culling by opacne otoceny trojuhelnik zahodil.
            int clockwise = 0;
            for (int t = 0; t < triangles; t++) if (area2(v, t) <= 0) clockwise++;
            check("blok " + block + ": vsechny trojuhelniky proti smeru hodinovych rucicek",
                    clockwise == 0, clockwise + " z " + triangles);

            boolean inside = true;
            for (int i = 0; i < v.length; i += F) {
                inside &= v[i] >= X - 1e-3f && v[i] <= X + SIZE + 1e-3f
                        && v[i + 1] >= Y - 1e-3f && v[i + 1] <= Y + SIZE + 1e-3f;
            }
            check("blok " + block + ": ikona nevylezne ze sveho ctverce", inside, "");
        }

        // Plna krychle je sestiuhelnik vepsany do ctverce: ctverec bez ctyr
        // rohovych trojuhelniku = 3/4 plochy. Mezera nebo prekryv sten by to rozbily.
        float[] cube = icon(World.STONE);
        float sum = 0;
        for (int t = 0; t < cube.length / F / 3; t++) sum += area2(cube, t) / 2f;
        check("plocha krychle = 3/4 ctverce (steny se neprekryvaji ani nechavaji mezery)",
                Math.abs(sum - 0.75f * SIZE * SIZE) < 0.01f, sum + " vs " + 0.75f * SIZE * SIZE);

        // Pochoden je tenka: plocha ikony vyrazne mensi nez u krychle.
        float[] torch = icon(World.TORCH);
        float torchSum = 0;
        for (int t = 0; t < torch.length / F / 3; t++) torchSum += area2(torch, t) / 2f;
        check("pochoden kresli svuj model, ne krychli", torchSum < sum / 4, torchSum + " vs " + sum);

        // Davka: druha ikona navazuje za prvni a prvni neprepise.
        float[] batch = new float[BlockIcon.floatsFor(World.STONE) * 2];
        int end1 = BlockIcon.build(World.STONE, X, Y, SIZE, batch, 0);
        int end2 = BlockIcon.build(World.DIRT, X + 40, Y, SIZE, batch, end1);
        boolean firstIntact = true;
        for (int i = 0; i < end1; i++) firstIntact &= batch[i] == cube[i];
        check("davka: druha ikona navaze za prvni a prvni necha byt",
                end2 == batch.length && firstIntact && batch[end1] > X + 39, "");
    }

    static void faces() {
        System.out.println("\n-- steny: dlazdice, odstin, poloha --");

        float[] v = icon(World.GRASS);
        int perFace = BlockIcon.VERTICES_PER_QUAD;
        int top = BlockAtlas.tile(World.GRASS, BlockAtlas.FACE_TOP);
        int side = BlockAtlas.tile(World.GRASS, BlockAtlas.FACE_SIDE);
        int[] tiles = {top, side, side};
        float[] shades = {BlockIcon.SHADE_TOP, BlockIcon.SHADE_LEFT, BlockIcon.SHADE_RIGHT};
        String[] names = {"horni", "leva (+Z)", "prava (+X)"};

        for (int face = 0; face < 3; face++) {
            boolean uvInTile = true, shade = true;
            for (int k = 0; k < perFace; k++) {
                int i = (face * perFace + k) * F;
                int t = tiles[face];
                uvInTile &= v[i + 2] >= BlockAtlas.u0(t) - 1e-6f && v[i + 2] <= BlockAtlas.u1(t) + 1e-6f
                        && v[i + 3] >= BlockAtlas.v0(t) - 1e-6f && v[i + 3] <= BlockAtlas.v1(t) + 1e-6f;
                shade &= v[i + 4] == shades[face];
            }
            check(names[face] + " stena bere UV ze sve dlazdice", uvInTile, "dlazdice " + tiles[face]);
            check(names[face] + " stena ma svuj odstin", shade, "" + shades[face]);
        }

        check("trava ma nahore jinou dlazdici nez z boku", top != side, top + " / " + side);
        check("svetlo shora: horni > leva > prava",
                BlockIcon.SHADE_TOP > BlockIcon.SHADE_LEFT && BlockIcon.SHADE_LEFT > BlockIcon.SHADE_RIGHT, "");

        // Horni stena lezi v horni polovine ikony, bocni v dolni.
        float centerY = Y + SIZE / 2f;
        boolean topUp = true, sidesDown = true;
        for (int k = 0; k < perFace; k++) topUp &= v[k * F + 1] >= centerY - 1e-3f;
        for (int k = perFace; k < 3 * perFace; k++) sidesDown &= v[k * F + 1] <= centerY + SIZE / 4f + 1e-3f;
        check("horni stena je nahore, bocni dole", topUp && sidesDown, "");

        // Kazda stena pokryje celou dlazdici (rohy UV = rohy dlazdice).
        float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE;
        for (int k = 0; k < perFace; k++) {
            minU = Math.min(minU, v[k * F + 2]);
            maxU = Math.max(maxU, v[k * F + 2]);
        }
        check("krychle: horni stena pokryje celou sirku dlazdice",
                Math.abs(minU - BlockAtlas.u0(top)) < 1e-6f && Math.abs(maxU - BlockAtlas.u1(top)) < 1e-6f, "");
    }

    static void translucency() {
        System.out.println("\n-- pruhlednost: alfa jen tam, kde plati ve svete --");

        // REN-4: svet kresli vsechno krome vody nepruhlednym pruchodem, kde se
        // alfa zahodi. Ikona dela totez - driv mela blok z labu v inventari
        // diru a ve svete cernou skvrnu.
        for (byte block : new byte[]{World.STONE, World.LEAVES, World.TORCH, World.WATER}) {
            float[] v = icon(block);
            boolean all = true;
            float want = World.isTranslucent(block) ? 1f : 0f;
            for (int i = 5; i < v.length; i += F) all &= v[i] == want;
            check("blok " + block + ": priznak alfy = World.isTranslucent (" + want + ")", all, "");
        }
        check("pruhledna je jen voda", World.isTranslucent(World.WATER)
                && !World.isTranslucent(World.LEAVES) && !World.isTranslucent(World.STONE)
                && !World.isTranslucent((byte) 100), "");
    }
}
