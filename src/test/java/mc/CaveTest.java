package mc;

import java.util.HashMap;
import java.util.Map;

/**
 * Overuje podzemi: jeskyne vyrezane 3D sumem a rudne zily.
 *
 * Krome kontrol test i MERI - podil vykopaneho kamene a cetnost rud se nedaji
 * odhadnout od stolu, tak je test vypisuje. Kdyz cislo vypadne z rozumneho
 * rozsahu, prahy v World se podle nej doladi.
 */
public class CaveTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    /** Rozsah, ve kterem se prochazi svet. Mensi nez loadRadius, at je test rychly. */
    static final int RADIUS = 3;

    /** Nejvyssi blok TERENU - stromy se preskoci. */
    static int terrainTop(World w, int x, int z) {
        for (int y = World.WORLD_HEIGHT - 1; y >= 0; y--) {
            byte b = w.getBlock(x, y, z);
            if (b == World.LOG || b == World.LEAVES || !w.isSolid(x, y, z)) continue;
            return y;
        }
        return -1;
    }

    public static void main(String[] args) {
        World w = new World();
        TerrainGenerator gen = w.generator();
        w.loadRadius = RADIUS;
        w.unloadRadius = RADIUS + 2;

        long t0 = System.nanoTime();
        w.updateBlocking(8f, 8f);
        long t1 = System.nanoTime();

        int columns = w.loadedColumnCount();
        System.out.printf("%nGenerovani za studena: %d sloupcu za %.0f ms  (%.2f ms na sloupec)%n",
                columns, (t1 - t0) / 1e6, (t1 - t0) / 1e6 / columns);

        // Za studena meri i rozjezd JIT. Skutecna cena za behu je az ta druha.
        World warm = new World();
        warm.loadRadius = RADIUS;
        warm.unloadRadius = RADIUS + 2;
        warm.updateBlocking(1000f, 1000f);

        World warm2 = new World();
        warm2.loadRadius = RADIUS;
        warm2.unloadRadius = RADIUS + 2;
        long t2 = System.nanoTime();
        warm2.updateBlocking(-5000f, 3000f);
        long t3 = System.nanoTime();
        System.out.printf("Generovani po zahrati: %.2f ms na sloupec%n",
                (t3 - t2) / 1e6 / warm2.loadedColumnCount());

        int minX = -RADIUS * Chunk.SIZE, maxX = (RADIUS + 1) * Chunk.SIZE - 1;
        int minZ = -RADIUS * Chunk.SIZE, maxZ = (RADIUS + 1) * Chunk.SIZE - 1;

        // ---------- jeskyne ----------
        long stone = 0, carved = 0;
        long belowFloor = 0;
        long caveWithNeighbour = 0;

        // Kolik bloku by v hloubce bylo kamene, kdyby se nekopalo, a kolik z nich
        // ted chybi. Bere se jen pasmo, kde jeskyne vubec mohou byt.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = 0; y < 50; y++) {
                    // "Vykopano" znamena VZDUCH, ne "neni pevne" - voda taky neni
                    // pevna a u dna jezer zasahuje az k y=48.
                    boolean air = w.getBlock(x, y, z) == World.AIR;

                    if (air && y < 3) belowFloor++;

                    if (!air) {
                        stone++;
                    } else {
                        carved++;
                        int n = 0;
                        if (w.getBlock(x + 1, y, z) == World.AIR) n++;
                        if (w.getBlock(x - 1, y, z) == World.AIR) n++;
                        if (w.getBlock(x, y + 1, z) == World.AIR) n++;
                        if (w.getBlock(x, y - 1, z) == World.AIR) n++;
                        if (w.getBlock(x, y, z + 1) == World.AIR) n++;
                        if (w.getBlock(x, y, z - 1) == World.AIR) n++;
                        if (n >= 2) caveWithNeighbour++;
                    }
                }
            }
        }

        double carvedPercent = 100.0 * carved / (stone + carved);
        System.out.printf("Vykopano v hloubce 0-49: %.2f %% (%d z %d)%n",
                carvedPercent, carved, stone + carved);

        check("jeskyne vubec vznikaji", carved > 0, "" + carved);
        check("jeskyne nesezraly celou horninu", carvedPercent < 25.0,
                String.format("%.2f %%", carvedPercent));
        check("jeskyn je znatelne mnozstvi", carvedPercent > 0.5,
                String.format("%.2f %%", carvedPercent));

        // Jeskyne maji byt chodby, ne rozsypane bubliny - vetsina vykopanych
        // bloku proto musi mit aspon dva vykopane sousedy.
        double connected = 100.0 * caveWithNeighbour / Math.max(1, carved);
        System.out.printf("Vykopane bloky s >= 2 vykopanymi sousedy: %.1f %%%n", connected);
        check("jeskyne jsou propojene, ne izolovane bubliny", connected > 80.0,
                String.format("%.1f %%", connected));

        // ---------- sirka chodeb ----------
        // Tohle je to, co je ve hre videt: jestli se hrac v jeskyni jen protahuje,
        // nebo jestli obcas prijde do sine. Meri se delka souvislych vodorovnych
        // useku vzduchu podel osy x; v hloubce pod 50 je vsechen vzduch jeskyne.
        long[] buckets = new long[7];        // index = delka useku, 6+ se scita do [6]
        long runs = 0, runBlocks = 0, maxRun = 0;
        long shallowRuns = 0, shallowLen = 0, deepRuns = 0, deepLen = 0;

        for (int y = 3; y < 50; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                int run = 0;
                for (int x = minX; x <= maxX + 1; x++) {
                    boolean air = x <= maxX && w.getBlock(x, y, z) == World.AIR;
                    if (air) { run++; continue; }
                    if (run > 0) {
                        buckets[Math.min(run, 6)]++;
                        runs++; runBlocks += run;
                        maxRun = Math.max(maxRun, run);
                        if (y >= 35) { shallowRuns++; shallowLen += run; }
                        else if (y <= 20) { deepRuns++; deepLen += run; }
                    }
                    run = 0;
                }
            }
        }

        System.out.printf("Sirka chodeb (vodorovne useky): prumer %.2f bloku, nejsirsi %d%n",
                (double) runBlocks / Math.max(1, runs), maxRun);
        System.out.print("  rozlozeni: ");
        for (int i = 1; i <= 6; i++)
            System.out.printf("%s%d=%.0f%%  ", i == 6 ? ">=" : "", i,
                    100.0 * buckets[i] / Math.max(1, runs));
        System.out.println();
        System.out.printf("  melko (y 35-49) %.2f bloku, hluboko (y 3-20) %.2f bloku%n",
                (double) shallowLen / Math.max(1, shallowRuns),
                (double) deepLen / Math.max(1, deepRuns));

        double averageRun = (double) runBlocks / Math.max(1, runs);
        check("chodby nejsou jen jednoblokove skuliny", averageRun > 2.0,
                String.format("prumer %.2f", averageRun));
        check("nekde vznikne i sirsi sin", maxRun >= 8, "nejsirsi " + maxRun);
        check("hluboko jsou chodby sirsi nez melko",
                (double) deepLen / Math.max(1, deepRuns) > (double) shallowLen / Math.max(1, shallowRuns),
                "");

        // ---------- dno sveta ----------
        check("pod y=3 se nekope (nedá se propadnout ze sveta)", belowFloor == 0,
                belowFloor + " der");

        // ---------- povrch zustal netknuty ----------
        // Jeskyne se tykaji jen kamene, takze nejvyssi blok sloupce musi byt
        // porad povrch a pod nim souvisla puda - zadna dira, zadna visici trava.
        int holes = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Od zavedeni stromu byva nejvyssi pevny blok listi nebo kmen -
                // hleda se proto povrch POD korunou.
                int top = terrainTop(w, x, z);
                if (top < 0) { holes++; continue; }

                byte surface = w.getBlock(x, top, z);
                if (surface != World.GRASS && surface != World.SAND) holes++;

                // Pod povrchem musi byt souvisla pudni vrstva. Pudni vrstva ma
                // 4 bloky VCETNE povrchu (SOIL_DEPTH), takze se kontroluji tri
                // pod nim - paty blok dolu uz je kamen a ten se kopat smi.
                for (int d = 1; d <= 3; d++) {
                    if (!w.isSolid(x, top - d, z)) { holes++; break; }
                }
            }
        }
        check("povrch a puda pod nim jsou souvisle", holes == 0, holes + " problemu");

        // ---------- rudy ----------
        Map<Byte, Integer> counts = new HashMap<>();
        int minCoalY = Integer.MAX_VALUE, maxCoalY = Integer.MIN_VALUE;
        int minIronY = Integer.MAX_VALUE, maxIronY = Integer.MIN_VALUE;
        int oreInSoil = 0;
        long oreWithNeighbour = 0, oreTotal = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = 0; y < World.WORLD_HEIGHT; y++) {
                    byte b = w.getBlock(x, y, z);
                    if (b != World.COAL_ORE && b != World.IRON_ORE) continue;

                    counts.merge(b, 1, Integer::sum);
                    oreTotal++;

                    if (b == World.COAL_ORE) {
                        minCoalY = Math.min(minCoalY, y); maxCoalY = Math.max(maxCoalY, y);
                    } else {
                        minIronY = Math.min(minIronY, y); maxIronY = Math.max(maxIronY, y);
                    }

                    if (w.getBlock(x + 1, y, z) == b || w.getBlock(x - 1, y, z) == b
                            || w.getBlock(x, y + 1, z) == b || w.getBlock(x, y - 1, z) == b
                            || w.getBlock(x, y, z + 1) == b || w.getBlock(x, y, z - 1) == b) {
                        oreWithNeighbour++;
                    }
                }
            }
        }

        int coal = counts.getOrDefault(World.COAL_ORE, 0);
        int iron = counts.getOrDefault(World.IRON_ORE, 0);

        double coalPerThousand = 1000.0 * coal / Math.max(1, stone);
        double ironPerThousand = 1000.0 * iron / Math.max(1, stone);

        System.out.printf("Uhli: %d bloku (%.1f na 1000 kamene), vyska %d-%d%n",
                coal, coalPerThousand, minCoalY, maxCoalY);
        System.out.printf("Zelezo: %d bloku (%.1f na 1000 kamene), vyska %d-%d%n",
                iron, ironPerThousand, minIronY, maxIronY);

        check("uhli se vyskytuje", coal > 0, "" + coal);
        check("zelezo se vyskytuje", iron > 0, "" + iron);
        check("uhli je castejsi nez zelezo", coal > iron, coal + " vs " + iron);

        check("uhli neni tak caste, ze by kamen zmizel", coalPerThousand < 60,
                String.format("%.1f promile", coalPerThousand));
        check("uhli neni tak vzacne, ze by se nedalo najit", coalPerThousand > 2,
                String.format("%.1f promile", coalPerThousand));
        check("zelezo je vzacnejsi nez uhli", ironPerThousand < coalPerThousand, "");
        check("zelezo se da najit", ironPerThousand > 0.5,
                String.format("%.1f promile", ironPerThousand));

        check("zelezo je jen v hloubce (max 42)", maxIronY <= 42, "" + maxIronY);
        check("uhli je jen do vysky 72", maxCoalY <= 72, "" + maxCoalY);
        check("zadna ruda neni pod y=3", Math.min(minCoalY, minIronY) >= 3,
                "" + Math.min(minCoalY, minIronY));

        // Zily maji byt shluky, ne jednotlive rozhozene bloky.
        double clustered = 100.0 * oreWithNeighbour / Math.max(1, oreTotal);
        System.out.printf("Rudne bloky se sousedem stejne rudy: %.1f %%%n", clustered);
        check("rudy tvori zily, ne konfety", clustered > 75.0,
                String.format("%.1f %%", clustered));

        // Rudy nahrazuji jen kamen - v pude ani na povrchu nemaji co delat.
        for (int x = minX; x <= maxX; x += 3) {
            for (int z = minZ; z <= maxZ; z += 3) {
                int top = terrainTop(w, x, z);
                for (int d = 0; d <= 3 && top - d >= 0; d++) {
                    byte b = w.getBlock(x, top - d, z);
                    if (b == World.COAL_ORE || b == World.IRON_ORE) oreInSoil++;
                }
            }
        }
        check("rudy nevystupuji do pudni vrstvy", oreInSoil == 0, oreInSoil + " nalezu");

        // ---------- zaporne souradnice ----------
        // Zilne bunky se pocitaji posunem >>, ne delenim. S delenim by bunka
        // kolem nuly byla dvakrat siroka a zily by u osy vypadaly jinak.
        int oreNeg = 0, orePos = 0;
        for (int y = 10; y < 40; y++) {
            for (int i = 0; i < 64; i++) {
                if (gen.oreAt(-1 - i, y, -1 - i) != World.STONE) oreNeg++;
                if (gen.oreAt(i, y, i) != World.STONE) orePos++;
            }
        }
        check("rudy vznikaji i v zapornych souradnicich", oreNeg > 0, "" + oreNeg);
        check("cetnost rud je v zapornych souradnicich srovnatelna",
                oreNeg > orePos / 4 && oreNeg < orePos * 4, oreNeg + " vs " + orePos);

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

        check("dva svety se stejnym seedem vyjdou stejne", same, "");

        // isCave je cista funkce pozice - musi vratit totez i pri opakovanem volani
        boolean stable = true;
        for (int i = 0; i < 500; i++) {
            int x = i * 7 - 1000, y = 5 + i % 40, z = i * 13 - 500;
            if (gen.isCave(x, y, z) != gen.isCave(x, y, z)) stable = false;
        }
        check("isCave je deterministicka", stable, "");

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
