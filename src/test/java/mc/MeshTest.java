package mc;


public class MeshTest {
    static int failures = 0;
    static void check(String n, boolean ok, String d) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + n + (d.isEmpty() ? "" : "  -> " + d));
        if (!ok) failures++;
    }

    /**
     * Nezavisly, naivni prepocet stejneho vysledku - proti nemu se mesher overuje.
     *
     * Pravidlo viditelnosti neni "soused je vzduch", od zavedeni vody je slozitejsi:
     * neprusvitny blok se kresli i k VODE (jinak by zmizelo dno jezera), ale voda
     * se kresli jen ke vzduchu - k sousedni vode by delala mrizku uvnitr jezera
     * a k pevnemu bloku by blikala s jeho stenou.
     */
    static boolean visible(World w, int x, int y, int z, boolean water) {
        byte neighbour = w.getBlock(x, y, z);
        return water ? neighbour == World.AIR : !World.isOpaque(neighbour);
    }

    static int naiveFaceCount(World w, Chunk c, int bx, int by, int bz) {
        int faces = 0;
        for (int y = 0; y < Chunk.SIZE; y++)
            for (int z = 0; z < Chunk.SIZE; z++)
                for (int x = 0; x < Chunk.SIZE; x++) {
                    byte id = c.get(x, y, z);
                    if (id == World.AIR) continue;
                    boolean water = id == World.WATER;
                    int wx = bx + x, wy = by + y, wz = bz + z;
                    if (visible(w, wx, wy + 1, wz, water)) faces++;
                    if (visible(w, wx, wy - 1, wz, water)) faces++;
                    if (visible(w, wx + 1, wy, wz, water)) faces++;
                    if (visible(w, wx - 1, wy, wz, water)) faces++;
                    if (visible(w, wx, wy, wz + 1, water)) faces++;
                    if (visible(w, wx, wy, wz - 1, water)) faces++;
                }
        return faces;
    }

    public static void main(String[] args) {
        World w = new World();
        w.updateBlocking(8f, 8f);

        ChunkColumn col = w.column(0, 0);
        check("sloupec (0,0) je nacteny", col != null, "");

        ChunkMesh mesh = new ChunkMesh();
        int totalFaces = 0, builtSections = 0, emptySections = 0;

        for (int s = 0; s < ChunkColumn.SECTIONS; s++) {
            Chunk section = col.section(s);
            if (section == null) { emptySections++; continue; }
            mesh.build(w, section, 0, s << Chunk.BITS, 0);
            int expected = naiveFaceCount(w, section, 0, s << Chunk.BITS, 0);
            if (mesh.faceCount() != expected) {
                check("sekce " + s + ": pocet sten", false, mesh.faceCount() + " vs naivne " + expected);
            }
            totalFaces += mesh.faceCount();
            builtSections++;
        }
        check("mesher souhlasi s naivnim vypoctem ve vsech sekcich", failures == 0,
                builtSections + " sekci, " + totalFaces + " sten");
        check("nejake sekce jsou prazdne (nad terenem)", emptySections > 0, emptySections + " prazdnych");

        // ---------- sekce obklopena kamenem nema zadne steny ----------
        // Drive se na to brala sekce hluboko pod povrchem, jenze od zavedeni
        // jeskyn uz zadna podzemni sekce plna neni. Plna sekce se proto postavi
        // rucne: vysoko nad terenem, kde je jinak vzduch, se zaplni oblast
        // 18x18x18 kamene. Vnitrnich 16^3 je pak sekce, ve ktere ma kazdy blok
        // vsech sest sousedu pevnych - mesher na ni nesmi vyrobit ani jednu stenu.
        final int SOLID_SECTION = 6;                       // y 96-111, nad terenem
        final int solidBaseY = SOLID_SECTION << Chunk.BITS;

        for (int x = -1; x <= Chunk.SIZE; x++)
            for (int z = -1; z <= Chunk.SIZE; z++)
                for (int y = solidBaseY - 1; y <= solidBaseY + Chunk.SIZE; y++)
                    w.placeBlock(x, y, z, World.STONE);

        Chunk solid = col.section(SOLID_SECTION);
        check("plna testovaci sekce vznikla", solid != null, "");

        if (solid != null) {
            mesh.build(w, solid, 0, solidBaseY, 0);
            check("sekce obklopena kamenem nema zadne steny", mesh.faceCount() == 0,
                    "" + mesh.faceCount());
        }

        // ---------- rychlost stavby ----------
        for (int i = 0; i < 200; i++) { // rozehrat JIT
            Chunk s5 = col.section(4);
            if (s5 != null) mesh.build(w, s5, 0, 64, 0);
        }

        long t0 = System.nanoTime();
        int built = 0;
        for (ChunkColumn c : w.loadedColumns()) {
            for (int s = 0; s < ChunkColumn.SECTIONS; s++) {
                Chunk sec = c.section(s);
                if (sec == null || sec.isEmpty()) continue;
                mesh.build(w, sec, c.cx << Chunk.BITS, s << Chunk.BITS, c.cz << Chunk.BITS);
                built++;
            }
        }
        long t1 = System.nanoTime();

        double ms = (t1 - t0) / 1e6;
        System.out.printf("%nStavba meshu: %d sekci za %.0f ms  (%.2f ms na sekci)%n", built, ms, ms / built);
        System.out.printf("Prestavba po rozbiti bloku = 1-4 sekce = ~%.2f ms  (frame ma 16.7 ms)%n", 4 * ms / built);
        System.out.printf("Naplneni dohledu pri startu = %d sekci = ~%.0f ms%n", built, ms);

        w.shutdown();
        dirtyIsComplete();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ==================================================================
    // kazda sekce, ktere se zmenou zmenil mesh, je oznacena k prestavbe
    // ==================================================================

    /** Meshe vsech sekci ve 3x3 sloupcich kolem (cx, cz), jako pole floatu. */
    static java.util.Map<String, float[]> meshes(World w, int cxC, int czC) {
        java.util.Map<String, float[]> out = new java.util.HashMap<>();
        ChunkMesh mesh = new ChunkMesh();
        for (int cx = cxC - 1; cx <= cxC + 1; cx++)
            for (int cz = czC - 1; cz <= czC + 1; cz++) {
                ChunkColumn col = w.column(cx, cz);
                if (col == null) continue;
                for (int cy = 0; cy < ChunkColumn.SECTIONS; cy++) {
                    Chunk sec = col.section(cy);
                    float[] data = new float[0];
                    if (sec != null) {
                        mesh.build(w, sec, cx << Chunk.BITS, cy << Chunk.BITS, cz << Chunk.BITS);
                        float[] a = mesh.opaqueData(), b = mesh.transparentData();
                        data = java.util.Arrays.copyOf(a, a.length + b.length);
                        System.arraycopy(b, 0, data, a.length, b.length);
                    }
                    out.put(cx + "," + cy + "," + cz, data);
                }
            }
        return out;
    }

    /** Pocet sekci, kterym zmena zmenila mesh, a kolik z nich NENI oznacenych. */
    static int unmarkedAfter(World w, int x, int z, Runnable edit) {
        java.util.Map<String, float[]> before = meshes(w, x >> Chunk.BITS, z >> Chunk.BITS);
        w.dirtySections().clear();
        edit.run();
        w.updateBlocking(x, z);

        java.util.Set<String> dirty = new java.util.HashSet<>();
        for (World.SectionPos p : w.dirtySections()) dirty.add(p.cx() + "," + p.cy() + "," + p.cz());

        int unmarked = 0;
        for (java.util.Map.Entry<String, float[]> e : meshes(w, x >> Chunk.BITS, z >> Chunk.BITS).entrySet()) {
            if (!java.util.Arrays.equals(before.get(e.getKey()), e.getValue()) && !dirty.contains(e.getKey())) {
                unmarked++;
            }
        }
        return unmarked;
    }

    static int top(World w, int x, int z) {
        for (int y = World.WORLD_HEIGHT - 1; y >= 0; y--) if (w.isSolid(x, y, z)) return y;
        return -1;
    }

    /**
     * BUG: znaceni sekci vychazelo z "face culling kouka jen na 6 sousedu",
     * jenze plynule osvetleni a AO berou i bunky do strany a do rohu steny.
     * Zmena na hrane nebo rohu sekce zmenila mesh i DIAGONALNI sekce a ta
     * zustala se starym stinem. A svetlo, ktere po otevreni stropu spadlo
     * do sachty, sekce pod ni neoznacilo vubec. Test prestavi meshe vsech
     * sekci kolem zmeny pred ni a po ni a trva na tom, ze kazda zmenena je
     * v dirtySections().
     */
    static void dirtyIsComplete() {
        System.out.println("\n-- kazda sekce se zmenenym meshem je oznacena --");

        World w = new World();
        w.loadRadius = 3;
        w.unloadRadius = 5;
        w.updateBlocking(16f, 16f);

        int unmarked = 0;
        // Roh ctyr chunku, hrana chunku, stred chunku - a zaporne souradnice.
        int[][] spots = {{16, 16}, {15, 15}, {0, 0}, {-1, -1}, {31, 16}, {8, 8}};
        for (int[] spot : spots) {
            int x = spot[0], z = spot[1];
            int y = top(w, x, z) + 1;
            unmarked += unmarkedAfter(w, x, z, () -> w.placeBlock(x, y, z, World.PLANKS));
            unmarked += unmarkedAfter(w, x, z, () -> w.breakBlock(x, y, z));
            unmarked += unmarkedAfter(w, x, z, () -> w.placeBlock(x, y, z, World.TORCH));
            unmarked += unmarkedAfter(w, x, z, () -> w.breakBlock(x, y, z));
        }
        check("polozeni, rozbiti a pochoden na hranach a rozich sekci oznaci vsechny zmenene meshe",
                unmarked == 0, unmarked + " sekci se zastaralym meshem");

        // Sachta 1x1: zastropit, pak strop rozbit - slunce spadne az dolu.
        int sx = 40, sz = 40;
        w.updateBlocking(sx, sz);
        int sy = top(w, sx, sz);
        for (int y = sy; y > sy - 40 && y > 4; y--) w.breakBlock(sx, y, sz);
        w.placeBlock(sx, sy + 1, sz, World.STONE);
        w.updateBlocking(sx, sz);
        check("otevreni stropu sachty oznaci i sekce hluboko pod nim",
                unmarkedAfter(w, sx, sz, () -> w.breakBlock(sx, sy + 1, sz)) == 0, "");

        w.shutdown();
    }
}
