package mc;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Overuje biomovou mapu - zaklad, na kterem stoji terén, stromy i ruda v horach.
 *
 * Tri veci, ktere se tady hlidaji a nikde jinde nejdou:
 *
 *  1) DETERMINISMUS. Biom je cista funkce souradnic a seedu, jako vyska terenu,
 *     stromy a rudne zily. Kdyby nebyl, rozpadl by se svet po kazdem nacteni
 *     jinak a ulozene stavby by stály v jinem biomu, nez ve kterem vznikly.
 *
 *  2) PLYNULY PRECHOD VYSKY. Hory maji amplitudu 42, poust 9 - kdyby si kazdy
 *     sloupec vzal parametry SVEHO biomu, byla by na hranici svisla zed. Test
 *     meri nejvetsi skok vysky NA hranici dvou biomu a porovnava ho se skokem
 *     UVNITR biomu; prechod nesmi byt horsi nez obycejny kopec.
 *
 *  3) SOUCET VAH JE PRESNE JEDNA. Na tom cely plynuly prechod stoji: vysledna
 *     vyska je vazeny prumer, takze musi lezet mezi parametry biomu. Kdyby se
 *     vahy nesectly do jednicky, terén by se celkove zvedl nebo klesl a ani by
 *     to nemuselo byt na prvni pohled videt.
 *
 * Nesaha na GL.
 */
public class BiomeTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        thresholds();
        smoothstep();
        weights();
        classification();
        determinism();
        distribution();
        independence();
        transitions();
        heightRange();
        trees();
        cost();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ==================================================================
    // 1) prahy a data biomu
    // ==================================================================

    static void thresholds() {
        System.out.println("\n-- prahy a data --");

        // ⚠️ Prekryv pasem by dal NEGATIVNI vahu: temperate = 1 - warm - cold,
        // takze kdyby se pasmo tepla a chladu potkalo, vyslo by mirne pasmo
        // pod nulu a "vazeny prumer" by prestal byt prumerem.
        check("pasmo tepla a chladu se neprekryva",
                Biome.T_WARM - Biome.BAND >= Biome.T_COLD + Biome.BAND,
                (Biome.T_WARM - Biome.BAND) + " >= " + (Biome.T_COLD + Biome.BAND));
        check("pasmo hor a kopcu se neprekryva",
                Biome.R_MOUNTAINS - Biome.BAND >= Biome.R_HILLS + Biome.BAND,
                (Biome.R_MOUNTAINS - Biome.BAND) + " >= " + (Biome.R_HILLS + Biome.BAND));

        check("prahy lezi v rozsahu sumu",
                Math.abs(Biome.T_COLD) < 1 && Math.abs(Biome.T_WARM) < 1
                        && Math.abs(Biome.R_HILLS) < 1 && Math.abs(Biome.R_MOUNTAINS) < 1, "");

        // Kazdy biom musi mit smysluplna data. Nula amplitudy = uplne placka,
        // zaporna zakladni vyska = svet pod dnem.
        boolean sane = true;
        String bad = "";
        for (Biome b : Biome.values()) {
            if (b.amplitude() <= 0 || b.baseHeight() <= 0
                    || b.baseHeight() + b.amplitude() >= World.WORLD_HEIGHT
                    || b.treeDensity() < 0 || b.treeDensity() > Biome.DENSITY_SCALE
                    || b.surface() == World.AIR || b.subsurface() == World.AIR) {
                sane = false;
                bad = b.toString();
            }
        }
        check("kazdy biom ma smysluplnou vysku, amplitudu, hustotu a povrch", sane, bad);

        // Bez stromu = hustota nula, a naopak. Kdyby melo NONE nenulovou
        // hustotu, razitkovala by se v pousti "nic" a hasTree by lhal.
        boolean consistent = true;
        for (Biome b : Biome.values()) {
            boolean none = b.treeType() == Biome.TreeType.NONE;
            if (none != (b.treeDensity() == 0)) consistent = false;
        }
        check("druh NONE a nulova hustota jdou vzdycky spolu", consistent, "");

        // Tvar koruny: obe pole stejne dlouhe, jinak by placeTree cetl mimo.
        boolean shapes = true;
        for (Biome.TreeType t : Biome.TreeType.values()) {
            if (t.layerRadius.length != t.layerTrim.length) shapes = false;
            if (t != Biome.TreeType.NONE && (t.layerRadius.length < 2 || t.trunkVariants < 1)) shapes = false;
        }
        check("kazdy druh stromu ma polomery a orezani stejne dlouhe", shapes, "");

        // Prales ma nejsirsi korunu - z ni se pocita TREE_REACH v generatoru.
        check("nejsirsi koruna je pralesni (polomer 3)",
                Biome.TreeType.JUNGLE.maxRadius() == 3
                        && Biome.TreeType.OAK.maxRadius() == 2, "");

        // Plane jsou schvalne presne ten teren, ktery hra mela pred biomy.
        check("plane maji porad zakladni vysku 64 a amplitudu 20",
                Biome.PLAINS.baseHeight() == 64 && Biome.PLAINS.amplitude() == 20, "");

        // Hory musi byt vyssi A clenitejsi nez kopce, kopce nez plane -
        // jinak by se ty tri stupne nedaly od sebe poznat.
        check("hory > kopce > plane, ve vysce i v amplitude",
                Biome.MOUNTAINS.baseHeight() > Biome.HILLS.baseHeight()
                        && Biome.HILLS.baseHeight() > Biome.PLAINS.baseHeight()
                        && Biome.MOUNTAINS.amplitude() > Biome.HILLS.amplitude()
                        && Biome.HILLS.amplitude() > Biome.PLAINS.amplitude(), "");

        check("poust a tundra jsou plossi nez plane",
                Biome.DESERT.amplitude() < Biome.PLAINS.amplitude()
                        && Biome.TUNDRA.amplitude() < Biome.PLAINS.amplitude(), "");

        check("hranice lesa je jen v horach",
                Biome.MOUNTAINS.treeLine() < World.WORLD_HEIGHT
                        && Biome.PLAINS.treeLine() >= World.WORLD_HEIGHT
                        && Biome.TAIGA.treeLine() >= World.WORLD_HEIGHT, "");
    }

    // ==================================================================
    // 2) smoothstep
    // ==================================================================

    static void smoothstep() {
        System.out.println("\n-- smoothstep --");

        check("pod dolnim okrajem je nula", Biome.smoothstep(0, 1, -5) == 0.0, "");
        check("nad hornim okrajem je jednicka", Biome.smoothstep(0, 1, 5) == 1.0, "");
        check("na okrajich presne 0 a 1",
                Biome.smoothstep(0, 1, 0) == 0.0 && Biome.smoothstep(0, 1, 1) == 1.0, "");
        check("v pulce je pulka", Math.abs(Biome.smoothstep(0, 1, 0.5) - 0.5) < 1e-12, "");

        boolean monotone = true;
        double prev = -1;
        for (int i = 0; i <= 1000; i++) {
            double v = Biome.smoothstep(-0.3, 0.7, -0.5 + i * 0.002);
            if (v < prev - 1e-15) monotone = false;
            prev = v;
        }
        check("je neklesajici na celem rozsahu", monotone, "");

        // ⚠️ Nulova derivace na obou koncich je to, proc tu neni linearni rampa:
        // zlom v derivaci by byl v terenu videt jako hrana na zacatku i konci
        // prechodoveho pasu.
        double eps = 1e-4;
        double slopeStart = (Biome.smoothstep(0, 1, eps) - Biome.smoothstep(0, 1, 0)) / eps;
        double slopeEnd = (Biome.smoothstep(0, 1, 1) - Biome.smoothstep(0, 1, 1 - eps)) / eps;
        check("derivace na obou koncich je (skoro) nulova",
                slopeStart < 0.01 && slopeEnd < 0.01,
                String.format("%.5f / %.5f", slopeStart, slopeEnd));
    }

    // ==================================================================
    // 3) vahy
    // ==================================================================

    static void weights() {
        System.out.println("\n-- vahy biomu --");

        java.util.Random random = new java.util.Random(4242);

        double worstSum = 0, worstNegative = 0;
        double worstHeightDiff = 0;
        boolean inRange = true;

        for (int i = 0; i < 200_000; i++) {
            double t = random.nextDouble() * 2.4 - 1.2;
            double h = random.nextDouble() * 2.4 - 1.2;
            double r = random.nextDouble() * 2.4 - 1.2;
            double fbm = random.nextDouble() * 2 - 1;

            double[] w = Biome.weights(t, h, r);

            double sum = 0;
            for (double value : w) {
                sum += value;
                worstNegative = Math.min(worstNegative, value);
            }
            worstSum = Math.max(worstSum, Math.abs(sum - 1.0));

            // ⚠️ Naivni suma pres weights() je NEZAVISLA CESTA k tomu, co
            // pocita vytknuty zapis v surfaceHeight(). Kdyby se ty dve verze
            // rozesly (nekdo prida biom jen do jedne z nich), je to tady videt.
            double base = 0, amplitude = 0;
            for (Biome b : Biome.values()) {
                base += w[b.ordinal()] * b.baseHeight();
                amplitude += w[b.ordinal()] * b.amplitude();
            }
            double naive = base + fbm * amplitude;
            double fast = Biome.surfaceHeight(t, h, r, fbm);
            worstHeightDiff = Math.max(worstHeightDiff, Math.abs(naive - fast));

            // Vazeny prumer musi lezet mezi nejmensim a nejvetsim parametrem.
            if (base < 60 || base > 90) inRange = false;
        }

        System.out.printf("Na 200 000 nahodnych klimatech: |suma-1| <= %.2e, nejmensi vaha %.2e,"
                + " |naivni - vytknuty| <= %.2e%n", worstSum, worstNegative, worstHeightDiff);

        check("souctem vah je presne jedna (partition of unity)", worstSum < 1e-12,
                String.format("%.3e", worstSum));
        check("zadna vaha neni negativni", worstNegative >= 0, String.format("%.3e", worstNegative));
        check("vytknuty surfaceHeight() = naivni suma pres weights()", worstHeightDiff < 1e-9,
                String.format("%.3e", worstHeightDiff));
        check("prumerna zakladni vyska zustava mezi parametry biomu", inRange, "");

        // V jadru biomu musi mit ten biom vahu presne 1 - jinak by cisty biom
        // nikdy nemel svoji vlastni vysku a vsechno by bylo rozmazane.
        Map<Biome, double[]> cores = new EnumMap<>(Biome.class);
        cores.put(Biome.MOUNTAINS, new double[]{0, 0, 0.95});
        cores.put(Biome.HILLS, new double[]{0, 0, 0.45});
        cores.put(Biome.PLAINS, new double[]{0, -0.5, -0.5});
        cores.put(Biome.BIRCH_FOREST, new double[]{0, 0.5, -0.5});
        cores.put(Biome.DESERT, new double[]{0.9, -0.5, -0.5});
        cores.put(Biome.JUNGLE, new double[]{0.9, 0.5, -0.5});
        cores.put(Biome.TUNDRA, new double[]{-0.9, -0.5, -0.5});
        cores.put(Biome.TAIGA, new double[]{-0.9, 0.5, -0.5});

        boolean pure = true;
        String impure = "";
        for (Map.Entry<Biome, double[]> entry : cores.entrySet()) {
            double[] c = entry.getValue();
            double[] w = Biome.weights(c[0], c[1], c[2]);
            if (Math.abs(w[entry.getKey().ordinal()] - 1.0) > 1e-12) {
                pure = false;
                impure = entry.getKey() + "=" + w[entry.getKey().ordinal()];
            }
            if (Biome.classify(c[0], c[1], c[2]) != entry.getKey()) {
                pure = false;
                impure = entry.getKey() + " se klasifikuje jako " + Biome.classify(c[0], c[1], c[2]);
            }
        }
        check("v jadru biomu ma ten biom vahu 1 a classify() ho vybere", pure, impure);
    }

    // ==================================================================
    // 4) klasifikace
    // ==================================================================

    static void classification() {
        System.out.println("\n-- classify --");

        // Reliéf rozhoduje PRVNI: hora je hora, i kdyz je v tropech.
        check("hory prebijou klima",
                Biome.classify(0.9, 0.9, 0.95) == Biome.MOUNTAINS
                        && Biome.classify(-0.9, -0.9, 0.95) == Biome.MOUNTAINS, "");
        check("kopce taky, ale jen pod horami",
                Biome.classify(0.9, 0.9, 0.45) == Biome.HILLS, "");

        check("teplo a sucho je poust", Biome.classify(0.9, -0.9, 0) == Biome.DESERT, "");
        check("teplo a vlhko je prales", Biome.classify(0.9, 0.9, 0) == Biome.JUNGLE, "");
        check("chladno a sucho je tundra", Biome.classify(-0.9, -0.9, 0) == Biome.TUNDRA, "");
        check("chladno a vlhko je tajga", Biome.classify(-0.9, 0.9, 0) == Biome.TAIGA, "");
        check("mirno a sucho jsou plane", Biome.classify(0, -0.9, 0) == Biome.PLAINS, "");
        check("mirno a vlhko je brezovy les", Biome.classify(0, 0.9, 0) == Biome.BIRCH_FOREST, "");

        // classify() je ostre prahovani, vahy jsou hladke - uvnitr biomu (dal
        // nez BAND od prahu) se ale musi shodnout, jinak by povrchovy blok
        // patril jinemu biomu, nez ze ktereho je spocitana vyska.
        java.util.Random random = new java.util.Random(99);
        int compared = 0, mismatched = 0;

        for (int i = 0; i < 100_000; i++) {
            double t = random.nextDouble() * 2 - 1;
            double h = random.nextDouble() * 2 - 1;
            double r = random.nextDouble() * 2 - 1;

            double[] w = Biome.weights(t, h, r);
            int best = 0;
            for (int j = 1; j < w.length; j++) if (w[j] > w[best]) best = j;

            // Jen jasne vnitrky: nejvetsi vaha aspon 0,9.
            if (w[best] < 0.9) continue;
            compared++;
            if (Biome.values()[best] != Biome.classify(t, h, r)) mismatched++;
        }

        System.out.printf("Jasnych vnitrku %d, z nich se classify() a nejvetsi vaha rozesly %dx%n",
                compared, mismatched);
        check("uvnitr biomu classify() = biom s nejvetsi vahou", mismatched == 0, "" + mismatched);
        check("test neni degenerovany (vnitrku je dost)", compared > 10_000, "" + compared);
    }

    // ==================================================================
    // 5) determinismus
    // ==================================================================

    static void determinism() {
        System.out.println("\n-- determinismus --");

        TerrainGenerator gen = new TerrainGenerator(World.DEFAULT_SEED);

        // Opakovane volani na tomtez miste.
        boolean stable = true;
        for (int i = 0; i < 2000; i++) {
            int x = i * 271 - 200_000, z = i * 137 - 90_000;
            Biome first = gen.biomeAt(x, z);
            for (int repeat = 0; repeat < 3; repeat++) {
                if (gen.biomeAt(x, z) != first) stable = false;
            }
        }
        check("opakovany dotaz na stejne misto da tentyz biom", stable, "");

        // Novy generator tehoz seedu - to je to, co se stane pri nacteni sveta.
        TerrainGenerator reloaded = new TerrainGenerator(World.DEFAULT_SEED);
        boolean sameAfterReload = true;
        for (int x = -3000; x <= 3000; x += 31)
            for (int z = -3000; z <= 3000; z += 31)
                if (gen.biomeAt(x, z) != reloaded.biomeAt(x, z)) sameAfterReload = false;
        check("novy generator tehoz seedu da stejne rozlozeni biomu", sameAfterReload, "");

        // ⚠️ A z ciziho vlakna taky - generátor je nemenny a worker na nem
        // biomy pocita bez zamku.
        final boolean[] fromWorker = {true};
        Thread worker = new Thread(() -> {
            for (int x = -2000; x <= 2000; x += 29)
                for (int z = -2000; z <= 2000; z += 29)
                    if (gen.biomeAt(x, z) != reloaded.biomeAt(x, z)) fromWorker[0] = false;
        });
        worker.start();
        try { worker.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        check("z ciziho vlakna vyjdou tytez biomy", fromWorker[0], "");

        // Jiny seed = jine rozlozeni. Bez toho by biomy nezavisely na seedu
        // a kazdy svet by mel poust na temze miste.
        TerrainGenerator other = new TerrainGenerator(World.DEFAULT_SEED + 1);
        int differences = 0, samples = 0;
        for (int x = -3000; x <= 3000; x += 31)
            for (int z = -3000; z <= 3000; z += 31) {
                samples++;
                if (gen.biomeAt(x, z) != other.biomeAt(x, z)) differences++;
            }
        System.out.printf("Jiny seed zmenil biom na %.1f %% vzorku%n", 100.0 * differences / samples);
        check("jiny seed da jine rozlozeni biomu", differences > samples / 2,
                differences + " z " + samples);

        // Svet a jeho generator musi videt tentyz biom - jinak by povrch
        // ve svete nesouhlasil s tim, co si o miste mysli spawn nebo stromy.
        World world = new World(777L);
        boolean worldAgrees = true;
        for (int x = -500; x <= 500; x += 13)
            for (int z = -500; z <= 500; z += 13)
                if (world.generator().biomeAt(x, z) != new TerrainGenerator(777L).biomeAt(x, z))
                    worldAgrees = false;
        world.shutdown();
        check("World(seed).generator() da tytez biomy jako TerrainGenerator(seed)", worldAgrees, "");
    }

    // ==================================================================
    // 6) rozlozeni biomu ve svete
    // ==================================================================

    static void distribution() {
        System.out.println("\n-- rozlozeni --");

        long[] seeds = {World.DEFAULT_SEED, 1L, 777L, -12345L};

        double smallestShare = 100, largestShare = 0;
        int missing = 0;

        for (long seed : seeds) {
            TerrainGenerator gen = new TerrainGenerator(seed);
            Map<Biome, Integer> counts = new EnumMap<>(Biome.class);
            for (Biome b : Biome.values()) counts.put(b, 0);

            int samples = 0;
            for (int x = -4000; x <= 4000; x += 29)
                for (int z = -4000; z <= 4000; z += 29) {
                    counts.merge(gen.biomeAt(x, z), 1, Integer::sum);
                    samples++;
                }

            StringBuilder line = new StringBuilder(String.format("  seed %-8d ", seed));
            for (Biome b : Biome.values()) {
                double share = 100.0 * counts.get(b) / samples;
                line.append(String.format("%s %.1f%%  ", shortName(b), share));
                if (counts.get(b) == 0) missing++;
                smallestShare = Math.min(smallestShare, share);
                largestShare = Math.max(largestShare, share);
            }
            System.out.println(line);
        }

        System.out.printf("Nejmensi podil %.1f %%, nejvetsi %.1f %%%n", smallestShare, largestShare);

        check("kazdy z osmi biomu se ve svete opravdu vyskytuje", missing == 0, missing + " chybi");
        check("zadny biom neni tak vzacny, ze by se nedal najit", smallestShare > 3.0,
                String.format("%.1f %%", smallestShare));
        check("zadny biom nezabira vic nez ctvrtinu sveta", largestShare < 25.0,
                String.format("%.1f %%", largestShare));

        // Prumerna delka jednoho biomu podel primky. Kdyby to byly desitky
        // bloku, byla by z biomu mozaika a hrac by je nepoznal; kdyby tisice,
        // nedosel by za celou hru do druheho.
        TerrainGenerator gen = new TerrainGenerator(World.DEFAULT_SEED);
        int runs = 0;
        long length = 0, currentRun = 0;
        Biome previous = null;

        for (int x = -30_000; x <= 30_000; x++) {
            Biome b = gen.biomeAt(x, 137);
            if (b != previous) {
                if (previous != null) { runs++; length += currentRun; }
                previous = b;
                currentRun = 0;
            }
            currentRun++;
        }

        double averageRun = (double) length / runs;
        System.out.printf("Prumerna delka biomu podel primky: %.0f bloku (%d useku na 60 000)%n",
                averageRun, runs);
        check("biom je stovky bloku velky, ne mozaika ani kontinent",
                averageRun > 40 && averageRun < 2000, String.format("%.0f bloku", averageRun));
    }

    static String shortName(Biome b) {
        return switch (b) {
            case PLAINS -> "PLA";
            case DESERT -> "DES";
            case JUNGLE -> "JUN";
            case BIRCH_FOREST -> "BIR";
            case TAIGA -> "TAI";
            case TUNDRA -> "TUN";
            case HILLS -> "HIL";
            case MOUNTAINS -> "MTN";
        };
    }

    // ==================================================================
    // 7) tri vrstvy sumu jsou nezavisle
    // ==================================================================

    static void independence() {
        System.out.println("\n-- nezavislost vrstev --");

        TerrainGenerator gen = new TerrainGenerator(World.DEFAULT_SEED);

        int n = 20_000;
        double[] t = new double[n], h = new double[n], r = new double[n];

        for (int i = 0; i < n; i++) {
            int x = i * 37 - 300_000;
            int z = i * 53 + 111_111;
            t[i] = gen.temperatureAt(x, z);
            h[i] = gen.humidityAt(x, z);
            r[i] = gen.reliefAt(x, z);
        }

        double th = correlation(t, h), tr = correlation(t, r), hr = correlation(h, r);
        System.out.printf("Korelace teplota-vlhkost %.4f, teplota-relief %.4f, vlhkost-relief %.4f%n",
                th, tr, hr);

        // ⚠️ Vsechny tri vrstvy jedou na TEZE permutacni tabulce, jen s jinym
        // posunem. Kdyby byl posun maly nebo kulaty, byly by vrstvy korelovane
        // a z matice biomu by zbyl pruhovany svet.
        check("teplota a vlhkost nejsou korelovane", Math.abs(th) < 0.2,
                String.format("%.4f", th));
        check("teplota a relief nejsou korelovane", Math.abs(tr) < 0.2,
                String.format("%.4f", tr));
        check("vlhkost a relief nejsou korelovane", Math.abs(hr) < 0.2,
                String.format("%.4f", hr));

        // A hlavne nesmi byt totozne - to by korelace 1 chytila, ale tohle je
        // primejsi: dve vrstvy, ktere by vratily stejne cislo na stejnem miste.
        int identical = 0;
        for (int i = 0; i < n; i++) {
            if (t[i] == h[i] || t[i] == r[i] || h[i] == r[i]) identical++;
        }
        check("vrstvy nejsou tataz funkce", identical < n / 100, identical + " z " + n);

        // Vsechny tri musi pokryt rozsah, ve kterem lezi prahy - jinak by
        // biom za prahem neexistoval.
        double[][] all = {t, h, r};
        boolean spread = true;
        for (double[] layer : all) {
            double min = 9, max = -9;
            for (double v : layer) { min = Math.min(min, v); max = Math.max(max, v); }
            if (min > -0.7 || max < 0.7) spread = false;
        }
        check("kazda vrstva pokryje aspon <-0,7; 0,7>", spread, "");
    }

    static double correlation(double[] a, double[] b) {
        double meanA = 0, meanB = 0;
        for (int i = 0; i < a.length; i++) { meanA += a[i]; meanB += b[i]; }
        meanA /= a.length;
        meanB /= b.length;

        double ab = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            ab += (a[i] - meanA) * (b[i] - meanB);
            aa += (a[i] - meanA) * (a[i] - meanA);
            bb += (b[i] - meanB) * (b[i] - meanB);
        }
        return ab / Math.sqrt(aa * bb);
    }

    // ==================================================================
    // 8) plynulost prechodu vysky
    // ==================================================================

    static void transitions() {
        System.out.println("\n-- prechody mezi biomy --");

        TerrainGenerator gen = new TerrainGenerator(World.DEFAULT_SEED);

        int worstOnBorder = 0, worstInside = 0;
        long borderSteps = 0, insideSteps = 0;
        long borderSum = 0;
        String where = "";
        Set<String> borderPairs = new HashSet<>();

        // Vodorovne rezy: v kazdem kroku o jeden blok se porovna vyska
        // a zjisti, jestli se pritom zmenil biom.
        for (int z = -4000; z <= 4000; z += 53) {
            for (int x = -4000; x < 4000; x++) {
                int h0 = gen.terrainHeight(x, z);
                int h1 = gen.terrainHeight(x + 1, z);
                int step = Math.abs(h1 - h0);

                Biome b0 = gen.biomeAt(x, z);
                Biome b1 = gen.biomeAt(x + 1, z);

                if (b0 != b1) {
                    borderSteps++;
                    borderSum += step;
                    borderPairs.add(b0 + ">" + b1);
                    if (step > worstOnBorder) {
                        worstOnBorder = step;
                        where = b0 + " -> " + b1 + " na " + x + "," + z;
                    }
                } else {
                    insideSteps++;
                    worstInside = Math.max(worstInside, step);
                }
            }
        }

        System.out.printf("Hranic prekroceno %d (%d druhu prechodu), vnitrnich kroku %d%n",
                borderSteps, borderPairs.size(), insideSteps);
        System.out.printf("Nejvetsi skok vysky NA hranici: %d bloku (%s), prumer %.2f%n",
                worstOnBorder, where, (double) borderSum / borderSteps);
        System.out.printf("Nejvetsi skok vysky UVNITR biomu: %d bloku%n", worstInside);

        check("test neni degenerovany (hranice se opravdu prekrocily)",
                borderSteps > 1000 && borderPairs.size() >= 8,
                borderSteps + " prechodu, " + borderPairs.size() + " druhu");

        // ⚠️ TOHLE JE TA KONTROLA, KVULI KTERE VAHY EXISTUJI. Bez prolnuti
        // by skok na hranici hor a pousti byl desitky bloku - svisla zed.
        check("skok na hranici biomu neni vetsi nez uvnitr biomu",
                worstOnBorder <= worstInside,
                worstOnBorder + " vs " + worstInside);
        check("skok na hranici biomu je pod rozumnou mezi (4 bloky)",
                worstOnBorder <= 4, "" + worstOnBorder);

        // Rez skrz konkretni hranici hor: vyska musi stoupat postupne,
        // ne jednim skokem. Hleda se misto, kde plane prechazi do hor.
        int found = 0, abrupt = 0;
        for (int z = -6000; z <= 6000 && found < 40; z += 17) {
            for (int x = -6000; x < 6000 && found < 40; x += 3) {
                if (gen.biomeAt(x, z) == Biome.MOUNTAINS
                        && gen.biomeAt(x - 60, z) != Biome.MOUNTAINS
                        && gen.biomeAt(x - 60, z) != Biome.HILLS) {
                    found++;
                    // Na 60 blocich pred horou nesmi byt jediny krok vetsi
                    // nez 4 bloky - hora se musi "svarit" s okolim.
                    for (int d = -60; d < 0; d++) {
                        if (Math.abs(gen.terrainHeight(x + d + 1, z) - gen.terrainHeight(x + d, z)) > 4) {
                            abrupt++;
                        }
                    }
                }
            }
        }
        System.out.printf("Nalezeno %d nabehu plane->hory, prudkych kroku v nich: %d%n", found, abrupt);
        check("nabeh do hor je postupny, ne zed", found > 0 && abrupt == 0,
                found + " nabehu, " + abrupt + " skoku");
    }

    // ==================================================================
    // 9) vysky se vejdou do sveta
    // ==================================================================

    static void heightRange() {
        System.out.println("\n-- rozsah vysek --");

        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        long sum = 0;
        int samples = 0, clamped = 0;
        int cap = World.WORLD_HEIGHT - 14;   // = strop generatoru (nejvyssi strom je 13)

        Map<Biome, int[]> perBiome = new EnumMap<>(Biome.class);
        for (Biome b : Biome.values()) perBiome.put(b, new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE, 0, 0});

        for (long seed : new long[]{World.DEFAULT_SEED, 1L, 777L}) {
            TerrainGenerator gen = new TerrainGenerator(seed);
            for (int x = -5000; x <= 5000; x += 41)
                for (int z = -5000; z <= 5000; z += 41) {
                    int h = gen.terrainHeight(x, z);
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                    sum += h;
                    samples++;
                    if (h >= cap) clamped++;

                    int[] stat = perBiome.get(gen.biomeAt(x, z));
                    stat[0] = Math.min(stat[0], h);
                    stat[1] = Math.max(stat[1], h);
                    stat[2] += h;
                    stat[3]++;
                }
        }

        System.out.printf("Vysky celkem: %d az %d, prumer %.1f (%d vzorku)%n",
                min, max, (double) sum / samples, samples);
        for (Biome b : Biome.values()) {
            int[] s = perBiome.get(b);
            System.out.printf("   %-13s %3d az %3d, prumer %.1f%n", b, s[0], s[1],
                    (double) s[2] / s[3]);
        }
        System.out.printf("Strop %d trefen na %.4f %% vzorku (je to pojistka, ne bezna cesta)%n",
                cap, 100.0 * clamped / samples);

        int tallestTree = 0;
        for (Biome.TreeType t : Biome.TreeType.values())
            tallestTree = Math.max(tallestTree, t.totalHeight());

        check("teren se vejde do sveta i s mistem na nejvyssi korunu stromu",
                max + tallestTree < World.WORLD_HEIGHT,
                "max " + max + " + strom " + tallestTree);
        check("teren nikde nespadne pod dno", min >= 4, "min " + min);
        check("strop je jen pojistka, ne bezna cesta", 100.0 * clamped / samples < 0.5,
                String.format("%.4f %%", 100.0 * clamped / samples));

        // Hory musi byt opravdu VYS nez plane - a mereno, ne slibeno.
        double mountainAverage = (double) perBiome.get(Biome.MOUNTAINS)[2] / perBiome.get(Biome.MOUNTAINS)[3];
        double plainsAverage = (double) perBiome.get(Biome.PLAINS)[2] / perBiome.get(Biome.PLAINS)[3];
        double hillsAverage = (double) perBiome.get(Biome.HILLS)[2] / perBiome.get(Biome.HILLS)[3];
        double desertAverage = (double) perBiome.get(Biome.DESERT)[2] / perBiome.get(Biome.DESERT)[3];

        check("hory jsou v prumeru vyssi nez kopce a ty nez plane",
                mountainAverage > hillsAverage && hillsAverage > plainsAverage,
                String.format("%.1f > %.1f > %.1f", mountainAverage, hillsAverage, plainsAverage));
        check("hory prevysuji plane aspon o 15 bloku", mountainAverage - plainsAverage > 15,
                String.format("%.1f", mountainAverage - plainsAverage));

        // Poust je plocha: rozptyl vysek v ni musi byt mensi nez v kopcich.
        int desertSpan = perBiome.get(Biome.DESERT)[1] - perBiome.get(Biome.DESERT)[0];
        int hillsSpan = perBiome.get(Biome.HILLS)[1] - perBiome.get(Biome.HILLS)[0];
        check("poust je plossi nez kopce", desertSpan < hillsSpan,
                desertSpan + " vs " + hillsSpan);
        check("poust lezi nad hladinou, takze v ni nejsou jezera",
                perBiome.get(Biome.DESERT)[0] >= World.SEA_LEVEL - 4,
                "nejniz " + perBiome.get(Biome.DESERT)[0] + ", hladina " + World.SEA_LEVEL);

        // Sníh v horach: nekde musi byt nad snehovou linii, jinak by byl
        // blok snehu v horach napsany, ale nikdy videt.
        TerrainGenerator gen = new TerrainGenerator(World.DEFAULT_SEED);
        int snowy = 0, mountainSamples = 0;
        for (int x = -5000; x <= 5000; x += 37)
            for (int z = -5000; z <= 5000; z += 37)
                if (gen.biomeAt(x, z) == Biome.MOUNTAINS) {
                    mountainSamples++;
                    if (gen.terrainHeight(x, z) > Biome.MOUNTAINS.treeLine()) snowy++;
                }
        System.out.printf("Zasnezenych vrcholu: %.1f %% hor (snezna cara = hranice lesa %d)%n",
                100.0 * snowy / mountainSamples, Biome.MOUNTAINS.treeLine());
        check("cast hor prevysuje snehovou linii, ale ne vsechny",
                snowy > 0 && snowy < mountainSamples,
                snowy + " z " + mountainSamples);

        System.out.printf("Desert prumer %.1f (placata poust nad hladinou)%n", desertAverage);
    }

    // ==================================================================
    // 10) stromy: druh podle biomu
    // ==================================================================

    static void trees() {
        System.out.println("\n-- druh stromu podle biomu --");

        TerrainGenerator gen = new TerrainGenerator(World.DEFAULT_SEED);

        // Kazdy strom, ktery ve svete stoji, musi byt druhu sveho biomu.
        // Statisticky pres celou oblast, ne jeden strom.
        Map<Biome, Integer> found = new EnumMap<>(Biome.class);
        for (Biome b : Biome.values()) found.put(b, 0);

        int wrongType = 0, total = 0;
        for (int x = -2000; x <= 2000; x += 1)
            for (int z = -2000; z <= 2000; z += 11) {
                Biome.TreeType type = gen.treeTypeAt(x, z);
                if (type == null) continue;
                total++;
                Biome biome = gen.biomeAt(x, z);
                found.merge(biome, 1, Integer::sum);
                if (type != biome.treeType()) wrongType++;
            }

        System.out.printf("Nalezeno %d kmenu, z toho spatneho druhu %d%n", total, wrongType);
        check("kazdy strom je druhu sveho biomu", wrongType == 0, "" + wrongType);
        check("kmenu je dost, aby to bylo statisticky", total > 2000, "" + total);
        check("v pousti neroste ani jeden strom", found.get(Biome.DESERT) == 0,
                "" + found.get(Biome.DESERT));
        check("v pralese, brezovem lese i tajze stromy rostou",
                found.get(Biome.JUNGLE) > 0 && found.get(Biome.BIRCH_FOREST) > 0
                        && found.get(Biome.TAIGA) > 0, found.toString());

        // Hranice lesa v horach: nad ni nesmi byt strom, pod ni smi.
        int aboveTreeLine = 0, belowTreeLine = 0;
        for (int x = -6000; x <= 6000; x += 7)
            for (int z = -6000; z <= 6000; z += 71) {
                if (gen.biomeAt(x, z) != Biome.MOUNTAINS) continue;
                boolean tree = gen.hasTree(x, z);
                if (gen.terrainHeight(x, z) > Biome.MOUNTAINS.treeLine()) {
                    if (tree) aboveTreeLine++;
                } else if (tree) {
                    belowTreeLine++;
                }
            }
        System.out.printf("V horach: %d kmenu pod hranici lesa, %d nad ni%n",
                belowTreeLine, aboveTreeLine);
        check("nad hranici lesa v horach neroste nic", aboveTreeLine == 0, "" + aboveTreeLine);
        check("pod hranici lesa v horach neco roste", belowTreeLine > 0, "" + belowTreeLine);
    }

    // ==================================================================
    // 11) cena biomove mapy
    // ==================================================================

    static void cost() {
        System.out.println("\n-- cena --");

        // Zahrat JIT, jinak se meri rozjezd.
        TerrainGenerator warm = new TerrainGenerator(4242L);
        long sink = 0;
        for (int x = 0; x < 300; x++)
            for (int z = 0; z < 300; z++) sink += warm.terrainHeight(x, z);

        TerrainGenerator gen = new TerrainGenerator(World.DEFAULT_SEED);

        int side = 400;
        long t0 = System.nanoTime();
        for (int x = 0; x < side; x++)
            for (int z = 0; z < side; z++) sink += gen.terrainHeight(x + 100_000, z + 100_000);
        long t1 = System.nanoTime();

        long t2 = System.nanoTime();
        for (int x = 0; x < side; x++)
            for (int z = 0; z < side; z++) sink += gen.biomeAt(x + 200_000, z + 200_000).ordinal();
        long t3 = System.nanoTime();

        double perHeight = (t1 - t0) / (double) (side * side);
        double perBiome = (t3 - t2) / (double) (side * side);

        System.out.printf("terrainHeight() %.0f ns na sloupecek, biomeAt() %.0f ns"
                + "  -> na sloupec chunku (256) %.2f ms terenu%n",
                perHeight, perBiome, perHeight * 256 / 1e6);
        System.out.println("(sink " + (sink & 1) + " - jen aby to JIT nevyhodil)");

        // Neni to tvrda mez vykonu, jen pojistka proti radovemu zhorseni:
        // kdyby vyska sloupecku stala mikrosekundy, generovani sloupce by
        // vyskocilo z 1,5 ms na desitky.
        check("vyska sloupecku stoji jednotky az desitky nanosekund", perHeight < 1000,
                String.format("%.0f ns", perHeight));
        check("biomeAt() neni drazsi nez cela vyska terenu", perBiome < perHeight * 1.5,
                String.format("%.0f vs %.0f ns", perBiome, perHeight));
    }
}
