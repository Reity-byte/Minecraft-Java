package mc;


/** Ověření chunk systému bez GL - hlavně chování v záporných souřadnicích. */
public class ChunkTest {

    static int failures = 0;

    // stejne konstanty jako ve World.generateTerrain
    static final double FREQ = 0.007;
    static final int OCTAVES = 4;
    static final int AMPLITUDE = 20;
    static final int GROUND = 64;
    static final int SAND_LEVEL = GROUND - 8;
    static final int SOIL_DEPTH = 4;

    /** Nezavisla replika World.fbm() - kdyz se rozejde, test to chytne. */
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

    static int expectedHeight(int wx, int wz) {
        return (int) (GROUND + fbm(wx, wz) * AMPLITUDE);
    }

    static byte expectedSurface(int height) {
        return height < SAND_LEVEL ? World.SAND : World.GRASS;
    }

    static byte expectedSubsurface(int height) {
        return height < SAND_LEVEL ? World.SAND : World.DIRT;
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
            if (top != expectedSurface(h) || soil != expectedSubsurface(h)
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
        // Amplituda 20 kolem vysky 64 nesmi nikde vyjet ze sveta (0..128).
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
        ChunkColumn col = null;
        // sahnout na sloupec pres verejne API nejde, tak testujeme nepr. pres chovani:
        // sekce vysoko nad terenem musi byt vzduch a nesmi se alokovat zapisem AIR
        check("sekce nad terenem je vzduch", !w.isSolid(8, 120, 8), "");
        w.breakBlock(8, 120, 8); // zapis AIR do neexistujici sekce nesmi spadnout
        check("break do prazdne sekce nespadne a nic nezmeni", !w.isSolid(8, 120, 8), "");
        check("pocet sekci na sloupec = 8", ChunkColumn.SECTIONS == 8, "" + ChunkColumn.SECTIONS);

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

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
