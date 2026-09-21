package mc;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * Overuje seed: cteni z textu a generovani terenu podle nej.
 *
 * ⚠️ KONTROLNI SOUCTY TERENU JSOU PRIPINACEK, NE DOGMA - a je dulezite vedet,
 * ktery zrovna drzi.
 *
 * Puvodne sem byly zmerene na kodu PRED zavedenim seedu: refaktor na seedy
 * GENERATOR_VERSION nezvysoval, takze vychozi seed musel davat bit po bitu
 * tytez bloky, jinak by se vsem ulozenym svetum posunul teren pod stavbami.
 *
 * BIOMY tohle poprve zmenily zamerne: posouvaji teren, a proto zvysily
 * GENERATOR_VERSION ze 4 na 5 - presne to je ten mechanismus, kterym se takova
 * zmena ohlasi (WorldStorage o neshode napise a svet nacte i tak). Soucty nize
 * jsou proto PREMERENE na generatoru s biomy a jejich role se tim otocila:
 * uz nehlidaji shodu s minulosti, ale to, ze se teren nehne NEPOZOROVANE.
 * Kdyz se rozejdou a GENERATOR_VERSION se nezvysil, je to chyba.
 *
 * Historicke hodnoty pred biomy, kdyby je nekdo hledal:
 *   oblasti 0xC384CE02, 0x438B9FEE, 0xC13BBED5, vysky 0x3B3F14B2.
 *
 * Co se NEZMENILO: spawn (5,5) a vyska terenu u nej (53). Okoli pocatku je
 * u vychoziho seedu biom PLAINS a ten ma schvalne presne puvodni parametry
 * (zakladni vyska 64, amplituda 20), takze tam vychazi tentyz teren jako driv.
 */
public class SeedTest {

    static int failures = 0;

    /** CRC32 vsech bloku oblasti chunku, premerene na generatoru s biomy (verze 5). */
    static final int[][] REGIONS = {
            {-2, -2, 1, 1},          // pres nulu, tedy i zaporne souradnice
            {40, -63, 42, -61},
            {-250, 120, -248, 122},
    };
    static final long[] REGION_CRC = {0xE0386A4CL, 0xE80F3826L, 0x9D5EFDD8L};

    /** CRC32 vysek terenu na mrizce 6000 x 6000 bloku, taky na verzi 5. */
    static final long HEIGHTS_CRC = 0x08D8083AL;

    /**
     * Co vracel spawn a vyska terenu pred refaktorem - a co vraci porad,
     * protoze okoli pocatku je PLAINS s puvodnimi parametry terenu.
     */
    static final int SPAWN_X = 5, SPAWN_Z = 5, HEIGHT_AT_8_8 = 53;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        parsing();
        defaultTerrain();
        differentSeeds();
        noise();
        threads();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ==================================================================
    // 1) seed z textu
    // ==================================================================

    static void parsing() {
        System.out.println("\n-- Seeds.parse --");

        AtomicInteger calls = new AtomicInteger();

        check("prazdne pole da nahodny seed",
                Seeds.parse("", () -> { calls.incrementAndGet(); return 42L; }) == 42L, "");
        check("jen mezery taky",
                Seeds.parse("   ", () -> { calls.incrementAndGet(); return 43L; }) == 43L, "");
        check("null taky",
                Seeds.parse(null, () -> { calls.incrementAndGet(); return 44L; }) == 44L, "");
        check("dva prazdne vstupy s jinym zdrojem daji jine seedy",
                Seeds.parse("", () -> 1L) != Seeds.parse("", () -> 2L), "");

        check("cislo se vezme, jak je", Seeds.parse("12345", failingRandom()) == 12345L, "");
        check("mezery kolem cisla nevadi", Seeds.parse("  12345  ", failingRandom()) == 12345L, "");
        check("zaporne cislo taky", Seeds.parse("-7", failingRandom()) == -7L, "");
        check("nula je nula, ne nahoda", Seeds.parse("0", failingRandom()) == 0L, "");
        check("kraje rozsahu long projdou",
                Seeds.parse(Long.toString(Long.MIN_VALUE), failingRandom()) == Long.MIN_VALUE
                        && Seeds.parse(Long.toString(Long.MAX_VALUE), failingRandom()) == Long.MAX_VALUE, "");
        check("u cisla se nahodny zdroj vubec nezavola", calls.get() == 3, "" + calls.get());

        check("text se prevede hashem",
                Seeds.parse(" hello ", failingRandom()) == "hello".hashCode(),
                Seeds.parse(" hello ", failingRandom()) + " vs " + "hello".hashCode());
        check("stejny text da porad stejne cislo",
                Seeds.parse("Minecraft", failingRandom()) == Seeds.parse("Minecraft", failingRandom()), "");
        check("jiny text da jine cislo",
                Seeds.parse("hello", failingRandom()) != Seeds.parse("Hello", failingRandom()), "");
        check("cislo mimo rozsah long spadne taky na hash",
                Seeds.parse("99999999999999999999", failingRandom()) == "99999999999999999999".hashCode(), "");

        check("nahodny seed neni porad stejny", Seeds.random() != Seeds.random(), "");
    }

    static java.util.function.LongSupplier failingRandom() {
        return () -> { throw new IllegalStateException("nahodny seed se tady volat nemel"); };
    }

    // ==================================================================
    // 2) vychozi seed = puvodni teren
    // ==================================================================

    static void defaultTerrain() {
        System.out.println("\n-- vychozi seed --");

        check("World() jede na vychozim seedu", new WorldHolder(new World()).close() == World.DEFAULT_SEED, "");
        check("vychozi seed je porad 12345", World.DEFAULT_SEED == 12345L, "" + World.DEFAULT_SEED);

        for (int i = 0; i < REGIONS.length; i++) {
            int[] r = REGIONS[i];
            long crc = regionCrc(World.DEFAULT_SEED, r[0], r[1], r[2], r[3]);

            check("oblast " + r[0] + "," + r[1] + " sedi na premereny soucet (verze generatoru 5)",
                    crc == REGION_CRC[i],
                    String.format("0x%08X vs 0x%08X", crc, REGION_CRC[i]));
        }

        TerrainGenerator gen = new TerrainGenerator(World.DEFAULT_SEED);

        CRC32 heights = new CRC32();
        for (int x = -3000; x <= 3000; x += 37)
            for (int z = -3000; z <= 3000; z += 37)
                heights.update(gen.terrainHeight(x, z));

        check("vysky terenu na velke ploche sedi",
                heights.getValue() == HEIGHTS_CRC,
                String.format("0x%08X vs 0x%08X", heights.getValue(), HEIGHTS_CRC));

        check("vyska u spawnu sedi", gen.terrainHeight(8, 8) == HEIGHT_AT_8_8,
                "" + gen.terrainHeight(8, 8));

        // Proc vyska u spawnu prezila i biomy: okoli pocatku je PLAINS a ten
        // ma schvalne presne puvodni parametry. Kdyby se PLAINS preladily,
        // padne tahle kontrola drive nez cokoliv jineho a bude videt proc.
        check("spawn vychoziho sveta lezi v planich s puvodnimi parametry",
                gen.biomeAt(8, 8) == Biome.PLAINS
                        && Biome.PLAINS.baseHeight() == 64 && Biome.PLAINS.amplitude() == 20,
                "" + gen.biomeAt(8, 8));

        int[] spawn = gen.findLandSpawn(8, 8, 64);
        check("spawn vychoziho sveta sedi", spawn[0] == SPAWN_X && spawn[1] == SPAWN_Z,
                spawn[0] + "," + spawn[1]);

        // Main hleda spawn na generatoru sveta, ktery zaklada - svet s vychozim
        // seedem proto musi dat tentyz spawn jako TerrainGenerator.DEFAULT.
        World world = new World();
        int[] worldSpawn = world.generator().findLandSpawn(8, 8, 64);
        check("svet s vychozim seedem najde tentyz spawn",
                worldSpawn[0] == spawn[0] && worldSpawn[1] == spawn[1], "");
        world.shutdown();
    }

    // ==================================================================
    // 3) jiny seed, jiny svet
    // ==================================================================

    static void differentSeeds() {
        System.out.println("\n-- seed meni svet --");

        long seed = 0x7FFFFFFFFFFFFABCL;

        World a = new World(seed);
        World b = new World(seed);
        a.loadRadius = 1; a.unloadRadius = 3;
        b.loadRadius = 1; b.unloadRadius = 3;
        a.updateBlocking(8f, 8f);
        b.updateBlocking(8f, 8f);

        check("World(seed) si seed pamatuje", a.seed() == seed && a.generator().seed() == seed,
                "" + a.seed());

        int same = 0, total = 0;
        for (int x = 0; x < Chunk.SIZE; x++)
            for (int z = 0; z < Chunk.SIZE; z++)
                for (int y = 0; y < World.WORLD_HEIGHT; y++) {
                    total++;
                    if (a.getBlock(x, y, z) == b.getBlock(x, y, z)) same++;
                }

        check("dva svety se stejnym seedem jsou blok po bloku stejne", same == total,
                (total - same) + " rozdilu");

        World other = new World(seed + 1);
        other.loadRadius = 1;
        other.unloadRadius = 3;
        other.updateBlocking(8f, 8f);

        int diff = 0;
        for (int x = 0; x < Chunk.SIZE; x++)
            for (int z = 0; z < Chunk.SIZE; z++)
                for (int y = 0; y < World.WORLD_HEIGHT; y++)
                    if (a.getBlock(x, y, z) != other.getBlock(x, y, z)) diff++;

        check("jiny seed da jiny teren", diff > total / 100,
                diff + " rozdilu z " + total);

        // A hlavne: vychozi svet se musi lisit od kazdeho jineho seedu,
        // jinak by "nahodny seed" nebyl k nicemu.
        long defaultCrc = regionCrc(World.DEFAULT_SEED, 0, 0, 1, 1);
        check("vychozi seed dava jiny teren nez jine seedy",
                regionCrc(1L, 0, 0, 1, 1) != defaultCrc
                        && regionCrc(-1L, 0, 0, 1, 1) != defaultCrc
                        && regionCrc(Long.MIN_VALUE, 0, 0, 1, 1) != defaultCrc, "");

        // Lisit se nesmi jen stromy a rudy (ty jdou z hashu), ale i samotny
        // tvar krajiny - jinak by seed menil jen ozdoby na tomtez terenu.
        TerrainGenerator one = new TerrainGenerator(World.DEFAULT_SEED);
        TerrainGenerator two = new TerrainGenerator(1L);
        int heightDiffs = 0, samples = 0;

        for (int x = -500; x <= 500; x += 17)
            for (int z = -500; z <= 500; z += 17) {
                samples++;
                if (one.terrainHeight(x, z) != two.terrainHeight(x, z)) heightDiffs++;
            }

        check("jiny seed meni i tvar krajiny, ne jen stromy a rudy",
                heightDiffs > samples / 2, heightDiffs + " z " + samples);

        // Spawn se pocita ze sumu, takze musi na seedu zaviset taky.
        long[] seeds = {World.DEFAULT_SEED, 1L, -7L, 12346L, Long.MAX_VALUE, Long.MIN_VALUE};
        java.util.Set<String> spawns = new java.util.HashSet<>();
        boolean onLand = true;

        for (long s : seeds) {
            TerrainGenerator gen = new TerrainGenerator(s);
            int[] spawn = gen.findLandSpawn(8, 8, 64);
            spawns.add(spawn[0] + "," + spawn[1]);

            if (gen.terrainHeight(spawn[0], spawn[1]) < World.SEA_LEVEL) onLand = false;
        }

        check("spawn je u kazdeho seedu na sousi", onLand, "");
        check("spawn zavisi na seedu", spawns.size() > 1, spawns.toString());

        a.shutdown();
        b.shutdown();
        other.shutdown();
    }

    // ==================================================================
    // 4) sum
    // ==================================================================

    static void noise() {
        System.out.println("\n-- SimplexNoise --");

        SimplexNoise def = new SimplexNoise(World.DEFAULT_SEED);

        boolean sameAsStatic = true;
        for (int i = 0; i < 200; i++) {
            double x = i * 0.37 - 30, y = i * 0.13 + 7, z = i * 0.71 - 12;
            if (def.sample(x, y) != SimplexNoise.noise(x, y)) sameAsStatic = false;
            if (def.sample(x, y, z) != SimplexNoise.noise(x, y, z)) sameAsStatic = false;
        }

        check("staticke noise() je sum vychoziho seedu", sameAsStatic, "");

        SimplexNoise again = new SimplexNoise(World.DEFAULT_SEED);
        check("stejny seed da stejny sum", again.sample(1.5, 2.5) == def.sample(1.5, 2.5), "");
        check("instance si pamatuje seed", again.seed() == World.DEFAULT_SEED, "");

        SimplexNoise other = new SimplexNoise(99L);
        boolean differs = false;
        for (int i = 0; i < 200 && !differs; i++) {
            if (other.sample(i * 0.37, i * 0.11) != def.sample(i * 0.37, i * 0.11)) differs = true;
        }

        check("jiny seed da jiny sum", differs, "");

        // ⚠️ Random bere ze seedu jen dolnich 48 bitu - bez primichani hornich
        // by tyhle dva seedy daly uplne stejny svet.
        SimplexNoise low = new SimplexNoise(12345L);
        SimplexNoise high = new SimplexNoise(12345L + (1L << 48));
        boolean highBitsMatter = false;
        for (int i = 0; i < 200 && !highBitsMatter; i++) {
            if (low.sample(i * 0.37, i * 0.11) != high.sample(i * 0.37, i * 0.11)) highBitsMatter = true;
        }

        check("horni bity seedu nezapadnou pod stul", highBitsMatter, "");

        boolean inRange = true;
        for (int i = 0; i < 500; i++) {
            double v = other.sample(i * 0.03, i * 0.07, i * 0.11);
            if (v < -1.5 || v > 1.5) inRange = false;
        }

        check("sum zustava v rozumnem rozsahu", inRange, "");
    }

    // ==================================================================
    // 5) generator smi na worker vlakno
    // ==================================================================

    static void threads() {
        System.out.println("\n-- generator na vice vlaknech --");

        TerrainGenerator gen = new TerrainGenerator(777L);
        ChunkColumn mine = gen.generateColumn(3, -4);

        final ChunkColumn[] theirs = new ChunkColumn[1];
        Thread worker = new Thread(() -> theirs[0] = gen.generateColumn(3, -4));
        worker.start();

        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean same = true;
        for (int x = 0; x < Chunk.SIZE && same; x++)
            for (int z = 0; z < Chunk.SIZE && same; z++)
                for (int y = 0; y < World.WORLD_HEIGHT; y++)
                    if (mine.get(x, y, z) != theirs[0].get(x, y, z)) { same = false; break; }

        check("sloupec z ciziho vlakna je tentyz (generator je nemenny)", same, "");
    }

    // ==================================================================
    // pomocne
    // ==================================================================

    /** CRC32 vsech bloku v oblasti chunku - stejny vypocet jako pred refaktorem. */
    static long regionCrc(long seed, int minCx, int minCz, int maxCx, int maxCz) {
        World w = new World(seed);
        w.loadRadius = 2;
        w.unloadRadius = 4;

        int cx = (minCx + maxCx) >> 1;
        int cz = (minCz + maxCz) >> 1;
        w.updateBlocking(cx * 16 + 8, cz * 16 + 8);

        CRC32 crc = new CRC32();

        for (int chunkX = minCx; chunkX <= maxCx; chunkX++)
            for (int chunkZ = minCz; chunkZ <= maxCz; chunkZ++)
                for (int y = 0; y < World.WORLD_HEIGHT; y++)
                    for (int lz = 0; lz < Chunk.SIZE; lz++)
                        for (int lx = 0; lx < Chunk.SIZE; lx++)
                            crc.update(w.getBlock((chunkX << 4) + lx, y, (chunkZ << 4) + lz));

        w.shutdown();
        return crc.getValue();
    }

    /** Svet, ktery se hned po dotazu na seed zase zastavi. */
    record WorldHolder(World world) {
        long close() {
            long seed = world.seed();
            world.shutdown();
            return seed;
        }
    }
}
