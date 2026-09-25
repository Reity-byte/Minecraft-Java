package mc;


/** Ověření chunk systému bez GL - hlavně chování v záporných souřadnicích. */
public class ChunkTest {

    static int failures = 0;

    // stejne konstanty jako ve World.generateTerrain
    static final double FREQ = 0.007;
    static final int OCTAVES = 4;
    static final int GROUND = 64;
    static final int SAND_LEVEL = GROUND - 8;
    static final int SOIL_DEPTH = 4;

    /** Generator vychoziho seedu - stejny svet, na kterem test jede. */
    static final TerrainGenerator GEN = new TerrainGenerator(World.DEFAULT_SEED);

    /** Nezavisla replika TerrainGenerator.fbm() - kdyz se rozejde, test to chytne. */
    static double fbm(int wx, int wz) {
        double sum = 0, amplitude = 1, frequency = FREQ, total = 0;
        for (int i = 0; i < OCTAVES; i++) {
            sum += SimplexNoise.noise(wx * frequency, wz * frequency) * amplitude;
            total += amplitude;
            frequency *= 2.0;
            amplitude *= 0.5;
        }
        return sum / total;
    }

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    /**
     * Nezavisla replika vysky terenu.
     *
     * Od zavedeni biomu uz neni zakladni vyska ani amplituda konstanta -
     * pocitaji se jako VAZENY PRUMER parametru vsech osmi biomu. Replika
     * proto jde pres Biome.weights() a scita to pole rucne; generator uvnitr
     * pouziva vytknuty zapis v Biome.surfaceHeight(), takze jsou to dve
     * ruzne cesty k temuz cislu. Samotne vrstvy sumu (teplota, vlhkost,
     * relief) si test bere z generatoru - duplikovat sest posunu by uz byly
     * jen opsane konstanty, ne nezavisla kontrola.
     */
    static int expectedHeight(int wx, int wz) {
        double t = GEN.temperatureAt(wx, wz);
        double h = GEN.humidityAt(wx, wz);
        double r = GEN.reliefAt(wx, wz);

        double[] w = Biome.weights(t, h, r);
        double base = 0, amplitude = 0;
        for (Biome b : Biome.values()) {
            base += w[b.ordinal()] * b.baseHeight();
            amplitude += w[b.ordinal()] * b.amplitude();
        }

        return (int) (base + fbm(wx, wz) * amplitude);
    }

    /** Povrch uz nezavisi jen na vysce, ale i na biomu (snih v tundre, pisek v pousti). */
    static byte expectedSurface(int wx, int wz, int height) {
        if (height < SAND_LEVEL) return World.SAND;

        Biome biome = GEN.biomeAt(wx, wz);
        if (biome == Biome.MOUNTAINS && height > Biome.MOUNTAINS.treeLine()) return World.SNOW;
        return biome.surface();
    }

    static byte expectedSubsurface(int wx, int wz, int height) {
        if (height < SAND_LEVEL) return World.SAND;
        return GEN.biomeAt(wx, wz).subsurface();
    }

    /** Co smi byt pod pudni vrstvou: kamen, ruda, nebo vykopana jeskyne. */
    static boolean isUnderground(byte block) {
        return block == World.STONE || block == World.COAL_ORE
                || block == World.IRON_ORE || block == World.AIR;
    }

    public static void main(String[] args) {
        World w = new World();

        // ---------- 1) index a rozsah plochého pole ----------
        check("index(0,0,0) = 0", Chunk.index(0, 0, 0) == 0, "");
        check("index(15,15,15) = 4095", Chunk.index(15, 15, 15) == 4095, "" + Chunk.index(15, 15, 15));
        boolean unique = true;
        boolean[] seen = new boolean[Chunk.VOLUME];
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int i = Chunk.index(x, y, z);
                    if (i < 0 || i >= Chunk.VOLUME || seen[i]) unique = false;
                    else seen[i] = true;
                }
        check("index je bijekce na <0,4095>", unique, "");
        check("x sousedi v pameti (index+1)", Chunk.index(5, 3, 7) + 1 == Chunk.index(6, 3, 7), "");

        // ---------- 2) nacteni sloupcu kolem hrace ----------
        w.updateBlocking(8f, 8f);
        int expectedCols = (2 * w.loadRadius + 1) * (2 * w.loadRadius + 1);
        check("po update je nacteno (2r+1)^2 sloupcu",
                w.loadedColumnCount() == expectedCols,
                w.loadedColumnCount() + " / cekano " + expectedCols);

        // ---------- 3) teren navazuje pres hranice chunku ----------
        // vzorkujeme pres hranici chunku 0/1 a pres hranici -1/0 (zaporna strana)
        int[] probes = {-33, -17, -16, -15, -1, 0, 1, 15, 16, 17, 31, 32};
        boolean terrainOk = true;
        boolean heightVaries = false;
        int firstHeight = expectedHeight(probes[0], 8);
        StringBuilder bad = new StringBuilder();

        for (int wx : probes) {
            int h = expectedHeight(wx, 8);
            if (h != firstHeight) heightVaries = true;

            byte top   = w.getBlock(wx, h - 1, 8);              // povrch
            byte soil  = w.getBlock(wx, h - 2, 8);              // hlina/pisek pod nim
            byte deep  = w.getBlock(wx, h - SOIL_DEPTH - 1, 8); // uz podlozi
            byte above = w.getBlock(wx, h,     8);              // vzduch nebo voda nad

            // Pod pudni vrstvou uz nesmi byt puda. Kamen to ale byt nemusi:
            // od zavedeni jeskyn a rud tam muze byt i ruda nebo vykopany vzduch.
            // Test hlida poradi vrstev, ne to, ze je podlozi vsude celistve.
            if (top != expectedSurface(wx, 8, h) || soil != expectedSubsurface(wx, 8, h)
                    || !isUnderground(deep) || World.isOpaque(above)) {
                terrainOk = false;
                bad.append(" x=").append(wx).append("(h=").append(h)
                   .append(" top=").append(top).append(" soil=").append(soil)
                   .append(" deep=").append(deep).append(" above=").append(above).append(")");
            }
        }
        check("teren sedi na ocekavanou vysku i vrstvy i v zapornych x", terrainOk, bad.toString());
        check("vysky se lisi (test neni degenerovany)", heightVaries, "");

        // ---------- rozsah vysek FBM pres velkou plochu ----------
        // Od zavedeni biomu se amplituda lisi biom od biomu (poust 9, hory 42),
        // takze uz to neni "20 kolem 64" - o to vic musi platit, ze se vysledek
        // porad vejde do sveta (0..128).
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        long sum = 0;
        int samples = 0;
        for (int sx = -3000; sx <= 3000; sx += 37)
            for (int sz = -3000; sz <= 3000; sz += 41) {
                int h = expectedHeight(sx, sz);
                minH = Math.min(minH, h);
                maxH = Math.max(maxH, h);
                sum += h;
                samples++;
            }
        System.out.println("  ...vysky na " + samples + " vzorcich: min=" + minH
                + " max=" + maxH + " prumer=" + (sum / samples));
        check("teren nikde nepodleze dno sveta", minH > 0, "min=" + minH);
        check("teren nikde nepresahne strop sveta", maxH < World.WORLD_HEIGHT, "max=" + maxH);
        check("teren ma slusnou clenitost", maxH - minH > 15, "rozsah=" + (maxH - minH));

        // ---------- 4) prevod souradnic v zapornych cislech ----------
        // blok na x=-1 patri do chunku -1, lokalne 15. Kdyby se pouzilo deleni
        // misto >>, spadl by do chunku 0 a svet by mel u nuly 16 bloku siroky sev.
        check("x=-1 -> chunk -1", (-1 >> Chunk.BITS) == -1, "" + (-1 >> Chunk.BITS));
        check("x=-1 -> lokalne 15", (-1 & Chunk.MASK) == 15, "" + (-1 & Chunk.MASK));
        check("x=-16 -> chunk -1, lokalne 0",
                (-16 >> Chunk.BITS) == -1 && (-16 & Chunk.MASK) == 0, "");
        check("x=-17 -> chunk -2, lokalne 15",
                (-17 >> Chunk.BITS) == -2 && (-17 & Chunk.MASK) == 15, "");

        // ---------- 5) hranice sveta ----------
        check("pod svetem je pevno (usetri spodni steny)", w.isSolid(0, -1, 0), "");
        check("nad svetem je vzduch", !w.isSolid(0, World.WORLD_HEIGHT, 0), "");
        check("nenacteny sloupec je vzduch", !w.isSolid(9999, 64, 9999), "");
        check("do nenacteneho sloupce nejde pokladat",
                !w.placeBlock(9999, 64, 9999, World.STONE), "");

        // ---------- 6) tezeni a pokladani v zapornych souradnicich ----------
        int nx = -20, nz = -20;
        int nh = expectedHeight(nx, nz);
        check("zaporny sloupec ma teren", w.isSolid(nx, nh - 1, nz), "h=" + nh);
        check("pokladani nad teren projde", w.placeBlock(nx, nh, nz, World.STONE), "");
        check("polozeny blok je pevny", w.isSolid(nx, nh, nz), "");
        check("pokladani do obsazene bunky selze", !w.placeBlock(nx, nh, nz, World.GRASS), "");
        w.breakBlock(nx, nh, nz);
        check("po vytezeni je zase vzduch", !w.isSolid(nx, nh, nz), "");
        w.breakBlock(nx, nh - 1, nz);
        check("da se vytezit i puvodni teren", !w.isSolid(nx, nh - 1, nz), "");

        // ---------- 7) liny alokator sekci + solidCount ----------
        // Driv tu stalo "na sloupec pres verejne API nejde" a kontroly, ktere
        // prosly vzdycky. Jde: World.column() i ChunkColumn.section() jsou
        // verejne - a na solidCount stoji, ktere sekce WorldRenderer preskoci.
        ChunkColumn col = w.column(0, 0);
        int top = ChunkColumn.SECTIONS - 1;
        check("nejvyssi sekce nad terenem neni alokovana", col != null && col.section(top) == null, "");
        check("rozbiti vzduchu nic nezmeni (vraci false)", !w.breakBlock(8, top * Chunk.SIZE + 8, 8), "");
        check("zapis vzduchu do prazdne sekce ji nealokuje", col.section(top) == null, "");
        check("pocet sekci na sloupec = 8", ChunkColumn.SECTIONS == 8, "" + ChunkColumn.SECTIONS);

        Chunk chunk = new Chunk();
        check("nova sekce je prazdna", chunk.isEmpty(), "");
        chunk.set(1, 2, 3, World.STONE);
        check("jeden blok ji udela neprazdnou", !chunk.isEmpty(), "");
        chunk.set(1, 2, 3, World.STONE);
        chunk.set(1, 2, 3, World.GRASS);
        check("prepis stejnym i jinym blokem pocitadlo neposune", !chunk.isEmpty(), "");
        chunk.set(1, 2, 3, World.AIR);
        check("vzduch pres posledni blok ji udela zase prazdnou (pocitadlo nekleslo pod nulu ani nezustalo)",
                chunk.isEmpty(), "");
        chunk.set(1, 2, 3, World.AIR);
        chunk.set(4, 4, 4, World.WATER);
        check("vzduch pres vzduch nic nezmeni a voda se pocita", !chunk.isEmpty(), "");

        // ---------- 8) uvolnovani + hystereze ----------
        check("unloadRadius > loadRadius (jinak thrashing na hranici)",
                w.unloadRadius > w.loadRadius, w.loadRadius + " vs " + w.unloadRadius);

        w.updateBlocking(8f, 8f);
        int before = w.loadedColumnCount();
        // odejit hodne daleko -> vsechny stare sloupce musi zmizet
        w.updateBlocking(2000f, 2000f);
        check("po presunu daleko zustane jen novy okruh",
                w.loadedColumnCount() == expectedCols,
                before + " -> " + w.loadedColumnCount());
        check("stary sloupec uz neni nacteny", !w.isSolid(8, expectedHeight(8, 8) - 1, 8), "");
        check("novy sloupec je nacteny", w.isSolid(2000, expectedHeight(2000, 2000) - 1, 2000), "");

        // ---------- 9) raycast funguje i v zapornych souradnicich ----------
        World w2 = new World();
        w2.updateBlocking(-100f, -100f);
        int rh = expectedHeight(-100, -100);
        Raycaster.RaycastHit hit = Raycaster.cast(w2, -99.5f, rh + 5.5f, -99.5f, 0, -1, 0, 20f);
        check("raycast dolu v zapornych souradnicich trefi teren",
                hit != null && hit.ny() == 1, String.valueOf(hit));
        if (hit != null) {
            check("raycast: place bunka je vzduch",
                    !w2.isSolid(hit.placeX(), hit.placeY(), hit.placeZ()), "");
        }

        // Zastavit workery - jinak by kazdy svet (~17 MB) zil v jedne JVM az do konce AllTests (WLD-13).
        w.shutdown();
        w2.shutdown();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
