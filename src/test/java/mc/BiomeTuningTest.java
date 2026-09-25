package mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Overuje Ore/Biome Tuner: biome_tuning.json a randomizaci velikosti stromu.
 *
 * ---------------------------------------------------------------------------
 * Nejdulezitejsi kontroly jsou tri.
 *
 * 1) VYCHOZI TUNING = DNESNI HRA BIT PO BITU. Chybejici soubor nesmi zmenit
 *    ani jeden blok terenu - jinak by nepovinny soubor prestal byt nepovinny
 *    a vsem ulozenym svetum by se posunul teren pod stavbami.
 *
 * 2) ROZSAH OPRAVDU LOSUJE. Kdyby se randomizace tise nepouzila (treba
 *    proto, ze se nekdo splet ve vetvi "min == max"), vypadalo by to jako
 *    dnes a nikdo by si toho nevsiml - test proto MERI, kolik ruznych
 *    velikosti na dost velkem vzorku padne.
 *
 * 3) TYZ SEED A SOURADNICE = TYZ STROM, POKAZDE. Na tom stoji razitkovani
 *    stromu ze sousednich sloupcu: stejny strom musi vyjit identicky, at
 *    se na nej pta kterykoliv ze ctyr sousedu.
 *
 * ⚠️ Testy si na konci vrati puvodni aktivni tuning. BiomeTuning.active()
 * je globalni stav a AllTests bezi vsechno v jednom JVM.
 * ---------------------------------------------------------------------------
 *
 * Nesaha na GL.
 */
public class BiomeTuningTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        BiomeTuning before = BiomeTuning.active();

        try {
            defaults();
            clamping();
            roundTrip();
            brokenFile();
            oreDensity();
            treeRandomness();
            treeDeterminism();
            terrainUnchanged();
            tunedTerrain();
            limits();
            previewIsReal();
            smallCrowns();
            oreNumbers();
            fileNumbers();
            tunedSurfaceHeight();
            tunedCrownSeams();
            previewTerrainIgnoresTuning();
        } finally {
            BiomeTuning.activate(before);
        }

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ==================================================================
    // 1) vychozi hodnoty = data z Biome
    // ==================================================================

    static void defaults() {
        System.out.println("\n-- vychozi tuning --");

        BiomeTuning d = BiomeTuning.defaults();

        check("vychozi tuning je vychozi", d.isDefault(), "");

        boolean fromEnum = true;
        for (Biome biome : Biome.values()) {
            BiomeTuning.Tune t = d.tune(biome);
            Biome.TreeType type = biome.treeType();

            // Poust je TreeType.NONE s kmenem 0, coz je mimo meze - vychozi
            // hodnoty jsou proto uz oriznute (viz nize). Rozsah se u ni
            // stejne nepouzije, protoze nema stromy.
            boolean hasTrees = type != Biome.TreeType.NONE;

            fromEnum &= t.baseHeight() == biome.baseHeight()
                    && t.amplitude() == biome.amplitude()
                    && t.treeDensity() == biome.treeDensity()
                    && (!hasTrees || (t.trunkMin() == type.trunkMin
                            && t.trunkMax() == type.trunkMax()
                            && t.crownMin() == type.maxRadius()
                            && t.crownMax() == type.maxRadius()));
        }
        check("vychozi cisla se ctou z Biome, neopisuji se", fromEnum, "");

        // ⚠️ Vychozi hodnoty MUSI byt uz oriznute, jinak by ulozeny
        // a znovu nacteny vychozi tuning vysel jinak nez vychozi - nacitani
        // orezava, takze by se "chybejici soubor" a "soubor s vychozimi
        // hodnotami" chovaly ruzne. Prave tohle odhalilo poust.
        boolean allClamped = true;
        for (Biome biome : Biome.values()) {
            allClamped &= d.tune(biome).isClamped();
        }
        check("vychozi hodnoty jsou uz oriznute (jinak se rozejde soubor tam a zpet)",
                allClamped, "");

        // Plane jsou "ten puvodni teren" - drzi to ARCHITECTURE.md i SeedTest.
        check("plane maji porad 64 / 20",
                d.tune(Biome.PLAINS).baseHeight() == 64 && d.tune(Biome.PLAINS).amplitude() == 20, "");

        check("hory maji 3x vic zeleza", d.tune(Biome.MOUNTAINS).ironDensity() == 3.0, "");
        check("nikde jinde se zelezo nemeni",
                d.tune(Biome.PLAINS).ironDensity() == 1.0
                        && d.tune(Biome.TAIGA).ironDensity() == 1.0, "");
        check("uhli se nemeni nikde",
                d.tune(Biome.MOUNTAINS).coalDensity() == 1.0
                        && d.tune(Biome.PLAINS).coalDensity() == 1.0, "");

        // ⚠️ Vychozi rozsahy jsou JEDNOPRVKOVE u koruny - jinak by se teren
        // hnul uz jen tim, ze tuner existuje.
        boolean fixedCrowns = true;
        for (Biome biome : Biome.values()) {
            fixedCrowns &= d.tune(biome).crownVariants() == 1;
        }
        check("vychozi polomer koruny je jedno cislo, ne rozsah", fixedCrowns, "");

        check("dosah koruny je nejvetsi polomer se stromy (prales, 3)",
                d.maxCrownRadius() == Biome.TreeType.JUNGLE.maxRadius(), "" + d.maxCrownRadius());
        check("nejvyssi strom je pralesni kmen 11 + 2",
                d.maxTreeHeight() == Biome.TreeType.JUNGLE.trunkMax() + 2, "" + d.maxTreeHeight());

        check("with() nemeni puvodni tuning",
                BiomeTuning.defaults().with(Biome.PLAINS,
                        new BiomeTuning.Tune(90, 5, 1, 4, 4, 2, 2, 1, 1))
                        .tune(Biome.PLAINS).baseHeight() == 90
                        && BiomeTuning.defaults().tune(Biome.PLAINS).baseHeight() == 64, "");
    }

    // ==================================================================
    // 2) hodnoty mimo meze
    // ==================================================================

    static void clamping() {
        System.out.println("\n-- hodnoty mimo meze --");

        BiomeTuning.Tune wild = new BiomeTuning.Tune(
                9999, -40, 999, -5, 999, -3, 99, -2.0, 1000.0).clamped();

        check("zaporna amplituda se orizne na nulu",
                wild.amplitude() == BiomeTuning.MIN_AMPLITUDE, "" + wild.amplitude());
        check("prilis vysoky zaklad se orizne na strop",
                wild.baseHeight() == BiomeTuning.MAX_BASE, "" + wild.baseHeight());
        check("hustota stromu se orizne na 16",
                wild.treeDensity() == BiomeTuning.MAX_DENSITY, "" + wild.treeDensity());
        check("kmen se orizne do mezi",
                wild.trunkMin() == BiomeTuning.MIN_TRUNK
                        && wild.trunkMax() == BiomeTuning.MAX_TRUNK, "");
        check("polomer koruny se orizne do mezi",
                wild.crownMin() == BiomeTuning.MIN_CROWN
                        && wild.crownMax() == BiomeTuning.MAX_CROWN, "");
        check("zaporny nasobek rudy se orizne na nulu", wild.ironDensity() == 0.0, "");
        check("prilis velky nasobek rudy se orizne", wild.coalDensity() == BiomeTuning.MAX_ORE, "");

        // ⚠️ Prehozeny rozsah se PROHODI, ne srovna na jedno cislo - jinak
        // by preklep v souboru tise vypnul celou variabilitu.
        BiomeTuning.Tune swapped = new BiomeTuning.Tune(64, 20, 5, 9, 4, 3, 1, 1, 1).clamped();
        check("min > max se prohodi, rozsah zustane",
                swapped.trunkMin() == 4 && swapped.trunkMax() == 9, "");
        check("a u koruny taky",
                swapped.crownMin() == 1 && swapped.crownMax() == 3, "");

        check("nulova hustota stromu je platna hodnota (biom bez stromu)",
                new BiomeTuning.Tune(64, 20, 0, 4, 6, 2, 2, 1, 1).clamped().treeDensity() == 0, "");

        check("NaN u nasobku spadne na dolni mez",
                new BiomeTuning.Tune(64, 20, 5, 4, 6, 2, 2, Double.NaN, 1).clamped()
                        .ironDensity() == BiomeTuning.MIN_ORE, "");

        check("uz oriznute hodnoty se nemeni",
                BiomeTuning.defaults().tune(Biome.PLAINS).isClamped(), "");

        BiomeTuning.Tune range = new BiomeTuning.Tune(64, 20, 5, 4, 8, 1, 3, 1, 1);
        check("pocet variant kmene sedi", range.trunkVariants() == 5, "" + range.trunkVariants());
        check("pocet variant koruny sedi", range.crownVariants() == 3, "" + range.crownVariants());
        check("rozsah se pozna jako promenlivy", range.variesTreeSize(), "");
        check("jednoprvkovy rozsah se pozna jako pevny",
                !new BiomeTuning.Tune(64, 20, 5, 4, 4, 2, 2, 1, 1).variesTreeSize(), "");
    }

    // ==================================================================
    // 3) soubor tam a zpatky
    // ==================================================================

    static void roundTrip() throws IOException {
        System.out.println("\n-- biome_tuning.json tam a zpatky --");

        Path dir = Files.createTempDirectory("mc-tuning");
        Path file = dir.resolve("biome_tuning.json");

        try {
            check("chybejici soubor da VYCHOZI hodnoty MLCKY",
                    BiomeTuning.load(file).isDefault(), "");

            BiomeTuning custom = BiomeTuning.defaults()
                    .with(Biome.PLAINS, new BiomeTuning.Tune(70, 26, 8, 4, 9, 2, 4, 1.5, 0.5))
                    .with(Biome.TUNDRA, new BiomeTuning.Tune(65, 11, 0, 6, 6, 2, 2, 1.0, 1.0));

            check("ulozeni zapise soubor", custom.save(file) && Files.isRegularFile(file), "");

            BiomeTuning loaded = BiomeTuning.load(file);

            boolean same = true;
            for (Biome biome : Biome.values()) {
                same &= loaded.tune(biome).equals(custom.tune(biome));
            }
            check("nacteny tuning je ten ulozeny", same, "");
            check("nacteny tuning uz neni vychozi", !loaded.isDefault(), "");

            String first = Files.readString(file);
            custom.save(file);
            check("druhy zapis je bajt po bajtu stejny", first.equals(Files.readString(file)), "");

            check("soubor je citelny - biomy jsou pojmenovane",
                    first.contains("\"plains\"") && first.contains("\"mountains\""), "");
            check("a nasobky rud jsou v nem jako desetinna cisla",
                    first.contains("\"ironDensity\": 3.00"), "");

            BiomeTuning.defaults().save(file);
            check("ulozene vychozi hodnoty se nactou jako vychozi",
                    BiomeTuning.load(file).isDefault(), "");
        } finally {
            delete(dir);
        }
    }

    // ==================================================================
    // 4) poskozeny soubor
    // ==================================================================

    static void brokenFile() throws IOException {
        System.out.println("\n-- poskozeny soubor --");

        Path dir = Files.createTempDirectory("mc-tuning-broken");
        Path file = dir.resolve("biome_tuning.json");

        try {
            String[] junk = {"tohle neni json", "", "[1,2,3]", "{}", "{\"format\": 1}",
                    "{\"biomes\": 7}", "{\"format\": 1, \"biomes\": [1,2]}"};

            boolean allDefault = true;
            for (String text : junk) {
                Files.writeString(file, text);
                allDefault &= BiomeTuning.load(file).isDefault();
            }
            check("kazdy druh poskozeneho souboru da VYCHOZI hodnoty (= teren jako dnes)",
                    allDefault, "");

            Files.writeString(file, "{\"format\": 1, \"biomes\": {"
                    + "\"plains\": {\"baseHeight\": 70},"
                    + "\"neexistuje\": {\"baseHeight\": 90},"
                    + "\"hills\": {\"amplitude\": \"hodne\"},"
                    + "\"taiga\": 5}}");

            BiomeTuning partial = BiomeTuning.load(file);
            check("platna hodnota se nacte", partial.tune(Biome.PLAINS).baseHeight() == 70, "");
            check("chybejici hodnota zustane vychozi",
                    partial.tune(Biome.PLAINS).amplitude() == Biome.PLAINS.amplitude(), "");
            check("hodnota, ktera neni cislo, necha biom na vychozi",
                    partial.tune(Biome.HILLS).amplitude() == Biome.HILLS.amplitude(), "");
            check("biom, ktery neni objekt, zustane vychozi",
                    partial.tune(Biome.TAIGA).equals(BiomeTuning.defaults().tune(Biome.TAIGA)), "");
            check("neznamy biom jen zmizi, ostatni se nactou",
                    partial.tune(Biome.DESERT).equals(BiomeTuning.defaults().tune(Biome.DESERT)), "");

            // Hodnota mimo meze se orizne a nacte - soubor se kvuli ni nezahodi.
            Files.writeString(file, "{\"format\": 1, \"biomes\": {"
                    + "\"plains\": {\"amplitude\": 9999, \"trunkMin\": 9, \"trunkMax\": 4}}}");
            BiomeTuning clamped = BiomeTuning.load(file);
            check("hodnota mimo meze se orizne, soubor se nezahodi",
                    clamped.tune(Biome.PLAINS).amplitude() == BiomeTuning.MAX_AMPLITUDE, "");
            check("a prehozeny rozsah se prohodi",
                    clamped.tune(Biome.PLAINS).trunkMin() == 4
                            && clamped.tune(Biome.PLAINS).trunkMax() == 9, "");

            Files.writeString(file, "{\"format\": 99, \"biomes\": {\"plains\": {\"baseHeight\": 70}}}");
            check("novejsi format se precte, co zna",
                    BiomeTuning.load(file).tune(Biome.PLAINS).baseHeight() == 70, "");

            Files.writeString(file, "{\"format\": 1, \"biomes\": {\"plains\": {\"amplitude\": \"x\"}}}");
            BiomeTuning.defaults().save(file);
            check("poskozeny soubor se pred prepsanim zalohoval do .bak",
                    Files.isRegularFile(dir.resolve("biome_tuning.json.bak")), "");
        } finally {
            delete(dir);
        }
    }

    // ==================================================================
    // 5) nasobky rud
    // ==================================================================

    static void oreDensity() {
        System.out.println("\n-- nasobky rud --");

        // ⚠️ Vychozi hory musi trefit PRESNE dnesni IRON_RARITY_MOUNTAINS,
        // jinak by se v nich zmenilo mnozstvi zeleza jen tim, ze tuner vznikl.
        check("hory: 3x hustota = dnesni vzacnost 20",
                BiomeTuning.rarity(60, 3.0) == TerrainGenerator.IRON_RARITY_MOUNTAINS,
                "" + BiomeTuning.rarity(60, 3.0));
        check("nasobek 1 nechava zakladni vzacnost", BiomeTuning.rarity(60, 1.0) == 60, "");
        check("dvojnasobna hustota = polovicni vzacnost", BiomeTuning.rarity(60, 2.0) == 30, "");
        check("polovicni hustota = dvojnasobna vzacnost", BiomeTuning.rarity(60, 0.5) == 120, "");

        // ⚠️ Nula znamena "ruda tu neni". Delenim by vyslo nekonecno.
        check("nulova hustota = ruda v tom biomu neni (0, ne nekonecno)",
                BiomeTuning.rarity(60, 0.0) == 0, "");
        check("obrovska hustota se nezbláznila na nulovou vzacnost",
                BiomeTuning.rarity(60, 1000.0) >= 1, "" + BiomeTuning.rarity(60, 1000.0));

        // A ted totez pres skutecny generator.
        BiomeTuning noIron = BiomeTuning.defaults().with(Biome.PLAINS,
                new BiomeTuning.Tune(64, 20, 5, 4, 6, 2, 2, 0.0, 1.0));
        TerrainGenerator plain = new TerrainGenerator(World.DEFAULT_SEED, noIron);
        TerrainGenerator normal = new TerrainGenerator(World.DEFAULT_SEED);

        int ironOff = 0, ironOn = 0;

        for (int x = 0; x < 240; x++) {
            for (int y = 3; y <= 42; y += 3) {
                for (int z = 0; z < 60; z++) {
                    if (normal.biomeAt(x, z) != Biome.PLAINS) {
                        continue;
                    }
                    if (plain.oreAt(x, y, z, Biome.PLAINS) == World.IRON_ORE)  ironOff++;
                    if (normal.oreAt(x, y, z, Biome.PLAINS) == World.IRON_ORE) ironOn++;
                }
            }
        }

        check("s nulovou hustotou v planich zadne zelezo nevznikne",
                ironOff == 0 && ironOn > 0, ironOff + " vs " + ironOn);
    }

    // ==================================================================
    // 6) rozsah opravdu losuje
    // ==================================================================

    static void treeRandomness() {
        System.out.println("\n-- rozsah velikosti stromu --");

        // Prales je nejhustsi biom, takze se v nem najde dost stromu
        // na zmereni. Kmen 5-11, koruna 2-5.
        BiomeTuning wide = BiomeTuning.defaults().with(Biome.JUNGLE,
                new BiomeTuning.Tune(64, 18, 16, 5, 11, 2, 5, 1.0, 1.0));

        TerrainGenerator generator = new TerrainGenerator(World.DEFAULT_SEED, wide);
        BiomeTuning.Tune tune = wide.tune(Biome.JUNGLE);

        Set<Integer> trunks = new HashSet<>();
        Set<Integer> crowns = new HashSet<>();

        for (int x = 0; x < 400; x++) {
            for (int z = 0; z < 400; z += 7) {
                trunks.add(generator.trunkHeight(x, z, tune));
                crowns.add(generator.crownDelta(x, z, Biome.TreeType.JUNGLE, tune));
            }
        }

        // ⚠️ TOHLE JE TA KONTROLA, ktera odhali "randomizace se tise
        // nepouzila". Kdyby generator bral porad jedno cislo, byla by
        // v mnozine jedna hodnota a jinak by to vypadalo uplne stejne.
        check("vyska kmene pokryje cely rozsah 5-11", trunks.size() == 7, "" + trunks.size());
        check("a nic mimo nej",
                java.util.Collections.min(trunks) == 5 && java.util.Collections.max(trunks) == 11, "");

        check("polomer koruny pokryje cely rozsah 2-5", crowns.size() == 4, "" + crowns.size());

        // Delta je polomer minus vlastni polomer druhu (prales ma 3).
        int base = Biome.TreeType.JUNGLE.maxRadius();
        check("a odpovida polomerum 2 az 5",
                java.util.Collections.min(crowns) == 2 - base
                        && java.util.Collections.max(crowns) == 5 - base, "");

        // Kmen a koruna se nesmi tahnout za jeden provaz - jinak by les
        // vypadal jako rada zvetsenin jednoho stromu.
        int together = 0, apart = 0;

        for (int x = 0; x < 600; x++) {
            boolean tallTrunk = generator.trunkHeight(x, 0, tune) >= 9;
            boolean wideCrown = generator.crownDelta(x, 0, Biome.TreeType.JUNGLE, tune) >= 1;

            if (tallTrunk == wideCrown) together++; else apart++;
        }

        check("vysoky kmen NENI spraheny se sirokou korunou",
                apart > 600 * 0.3 && together > 600 * 0.3, together + " spolu / " + apart + " zvlast");

        // ⚠️ Jednoprvkovy rozsah musi dat presne to jedno cislo - na tom
        // stoji "vychozi tuning = dnesni teren".
        BiomeTuning.Tune fixed = new BiomeTuning.Tune(64, 18, 16, 7, 7, 3, 3, 1, 1);
        boolean alwaysSame = true;

        for (int x = 0; x < 500; x++) {
            alwaysSame &= generator.trunkHeight(x, 3, fixed) == 7
                    && generator.crownDelta(x, 3, Biome.TreeType.JUNGLE, fixed) == 0;
        }
        check("jednoprvkovy rozsah dava porad tu jednu hodnotu", alwaysSame, "");

        // A tvar: vetsi delta = sirsi koruna, spicka smrku zustane spicka.
        check("delta +1 rozsiri korunu dubu z 2 na 3",
                TreeShape.reach(Biome.TreeType.OAK, 1) == 3, "");
        check("delta -1 ji zuzi na 1", TreeShape.reach(Biome.TreeType.OAK, -1) == 1, "");
        check("polomer nikdy nespadne pod nulu",
                TreeShape.reach(Biome.TreeType.OAK, -9) == 0, "");
    }

    // ==================================================================
    // 7) determinismus
    // ==================================================================

    static void treeDeterminism() {
        System.out.println("\n-- determinismus stromu --");

        BiomeTuning wide = BiomeTuning.defaults().with(Biome.JUNGLE,
                new BiomeTuning.Tune(64, 18, 16, 5, 11, 2, 5, 1.0, 1.0));
        BiomeTuning.Tune tune = wide.tune(Biome.JUNGLE);

        TerrainGenerator a = new TerrainGenerator(World.DEFAULT_SEED, wide);
        TerrainGenerator b = new TerrainGenerator(World.DEFAULT_SEED, wide);

        boolean same = true;
        for (int x = -200; x < 200; x += 3) {
            for (int z = -200; z < 200; z += 37) {
                same &= a.trunkHeight(x, z, tune) == b.trunkHeight(x, z, tune)
                        && a.crownDelta(x, z, Biome.TreeType.JUNGLE, tune)
                                == b.crownDelta(x, z, Biome.TreeType.JUNGLE, tune);
            }
        }
        check("tyz seed a souradnice = tyz strom, i v zapornych souradnicich", same, "");

        // Opakovany dotaz na tomtez generatoru - na tom stoji razitkovani
        // ze sousednich sloupcu.
        boolean stable = true;
        for (int i = 0; i < 100; i++) {
            stable &= a.trunkHeight(37, -91, tune) == a.trunkHeight(37, -91, tune);
        }
        check("opakovany dotaz vraci totez", stable, "");

        // Jiny seed = jine stromy.
        TerrainGenerator other = new TerrainGenerator(World.DEFAULT_SEED + 12345, wide);
        int different = 0;

        for (int x = 0; x < 500; x++) {
            if (a.trunkHeight(x, 11, tune) != other.trunkHeight(x, 11, tune)) {
                different++;
            }
        }
        check("jiny seed da jine stromy", different > 300, different + " z 500");

        // ⚠️ Kmen nikdy nevyjde kratsi nez minimum - hash je nezaporny,
        // takze zbytek po deleni taky. Kdyby byl zaporny, rostly by stromy
        // bez kmene a bylo by to videt az ve svete.
        int shortest = Integer.MAX_VALUE;
        for (int x = -3000; x < 3000; x += 7) {
            shortest = Math.min(shortest, a.trunkHeight(x, x / 3, tune));
        }
        check("kmen nikdy nevyjde kratsi nez minimum rozsahu", shortest == 5, "" + shortest);
    }

    // ==================================================================
    // 8) vychozi tuning nehne terenem ani o blok
    // ==================================================================

    static void terrainUnchanged() {
        System.out.println("\n-- vychozi tuning = dnesni teren --");

        // Vychozi tuning proti tuningu, ktery prosel souborem (toJson -> fromJson).
        // Driv se porovnaval DEFAULTS sam se sebou (aktivni tuning v testu je
        // vychozi), coz nic nehlidalo; skutecnou shodu s terenem pred tunerem
        // drzi kontrolni soucty v SeedTest. Tohle hlida, ze ulozeni a nacteni
        // vychozich cisel terén nezmeni ani o blok.
        BiomeTuning roundTrip = BiomeTuning.fromJson(BiomeTuning.defaults().toJson());
        check("vychozi tuning prezije soubor beze zmeny", roundTrip.sameNumbers(BiomeTuning.defaults())
                && roundTrip != BiomeTuning.defaults(), "");
        TerrainGenerator explicit = new TerrainGenerator(World.DEFAULT_SEED, BiomeTuning.defaults());
        TerrainGenerator implicit = new TerrainGenerator(World.DEFAULT_SEED, roundTrip);

        boolean sameHeights = true;
        for (int x = -400; x < 400; x += 3) {
            for (int z = -400; z < 400; z += 11) {
                sameHeights &= explicit.terrainHeight(x, z) == implicit.terrainHeight(x, z);
            }
        }
        check("vysky terenu jsou stejne", sameHeights, "");

        // A cely sloupec blok po bloku, vcetne stromu a rud.
        boolean sameColumns = true;
        for (int cx = -2; cx <= 2 && sameColumns; cx++) {
            for (int cz = -2; cz <= 2 && sameColumns; cz++) {
                ChunkColumn left = explicit.generateColumn(cx, cz);
                ChunkColumn right = implicit.generateColumn(cx, cz);

                for (int x = 0; x < Chunk.SIZE && sameColumns; x++) {
                    for (int y = 0; y < World.WORLD_HEIGHT && sameColumns; y++) {
                        for (int z = 0; z < Chunk.SIZE; z++) {
                            if (left.get(x, y, z) != right.get(x, y, z)) {
                                sameColumns = false;
                                break;
                            }
                        }
                    }
                }
            }
        }
        check("25 sloupcu vyjde blok po bloku stejne", sameColumns, "");

        // Strop terenu se pocita z nejvyssiho stromu - musi vyjit na dnesnich 114.
        int cap = World.WORLD_HEIGHT - BiomeTuning.defaults().maxTreeHeight() - 1;
        check("strop terenu vyjde na dnesnich 114", cap == 114, "" + cap);
    }

    // ==================================================================
    // 9) natuneny teren se opravdu zmeni
    // ==================================================================

    /**
     * GEN-14: generovani na mezich tuningu nespadne a spawn bez souse se vrati
     * na puvodni bod. Bezpecnost proti IndexOutOfBounds na worker vlakne dnes
     * stoji jen na orezu vysek - tohle ho drzi.
     */
    static void limits() {
        System.out.println("\n-- generovani na mezich tuningu --");

        int[][] extremes = {
                {BiomeTuning.MIN_BASE, BiomeTuning.MIN_AMPLITUDE}, {BiomeTuning.MIN_BASE, BiomeTuning.MAX_AMPLITUDE},
                {BiomeTuning.MAX_BASE, BiomeTuning.MIN_AMPLITUDE}, {BiomeTuning.MAX_BASE, BiomeTuning.MAX_AMPLITUDE}};
        boolean ok = true;
        String detail = "";
        for (int[] e : extremes) {
            BiomeTuning t = BiomeTuning.defaults();
            for (Biome b : Biome.values()) {
                BiomeTuning.Tune d = t.tune(b);
                t = t.with(b, new BiomeTuning.Tune(e[0], e[1], d.treeDensity(), BiomeTuning.MAX_TRUNK,
                        BiomeTuning.MAX_TRUNK, BiomeTuning.MAX_CROWN, BiomeTuning.MAX_CROWN,
                        BiomeTuning.MAX_ORE, BiomeTuning.MAX_ORE));
            }
            TerrainGenerator gen = new TerrainGenerator(World.DEFAULT_SEED, t);
            try {
                for (int cx = -1; cx <= 1; cx++)
                    for (int cz = -1; cz <= 1; cz++) gen.generateColumn(cx * 37, cz * 53);
                for (int x = -200; x < 200; x += 13) {
                    int h = gen.terrainHeight(x, x * 3);
                    if (h < 1 || h >= World.WORLD_HEIGHT) { ok = false; detail = "vyska " + h; }
                }
            } catch (RuntimeException ex) {
                ok = false;
                detail = "base " + e[0] + ", amp " + e[1] + ": " + ex;
            }
        }
        check("vsechny ctyri kombinace mezi (i max kmen, koruna a ruda) vygeneruji bez vyjimky",
                ok, detail);

        // Vsechno na base 8 = pod hladinou, zadna sous: spawn se po prohledani
        // vrati na puvodni bod (lepsi voda nez zadny svet).
        BiomeTuning sunk = BiomeTuning.defaults();
        for (Biome b : Biome.values()) {
            BiomeTuning.Tune d = sunk.tune(b);
            sunk = sunk.with(b, new BiomeTuning.Tune(BiomeTuning.MIN_BASE, 0, d.treeDensity(),
                    d.trunkMin(), d.trunkMax(), d.crownMin(), d.crownMax(), d.ironDensity(), d.coalDensity()));
        }
        int[] spawn = new TerrainGenerator(World.DEFAULT_SEED, sunk).findLandSpawn(8, 8, 32);
        check("bez souse se spawn vrati na puvodni bod", spawn[0] == 8 && spawn[1] == 8,
                spawn[0] + "," + spawn[1]);
    }

    static void tunedTerrain() {
        System.out.println("\n-- natuneny teren --");

        BiomeTuning raised = BiomeTuning.defaults().with(Biome.PLAINS,
                new BiomeTuning.Tune(80, 20, 5, 4, 6, 2, 2, 1.0, 1.0));

        TerrainGenerator normal = new TerrainGenerator(World.DEFAULT_SEED);
        TerrainGenerator higher = new TerrainGenerator(World.DEFAULT_SEED, raised);

        int checked = 0, higherCount = 0;

        for (int x = 0; x < 400 && checked < 200; x++) {
            for (int z = 0; z < 400 && checked < 200; z += 13) {
                if (normal.biomeAt(x, z) != Biome.PLAINS) {
                    continue;
                }

                checked++;
                if (higher.terrainHeight(x, z) > normal.terrainHeight(x, z)) {
                    higherCount++;
                }
            }
        }

        check("zvednuty zaklad plani zvedne teren v planich",
                checked > 50 && higherCount == checked, higherCount + " z " + checked);

        // Biom bez stromu: hustota 0 znamena zadny strom.
        BiomeTuning noTrees = BiomeTuning.defaults().with(Biome.JUNGLE,
                new BiomeTuning.Tune(64, 18, 0, 7, 11, 3, 3, 1.0, 1.0));
        TerrainGenerator bare = new TerrainGenerator(World.DEFAULT_SEED, noTrees);
        TerrainGenerator lush = new TerrainGenerator(World.DEFAULT_SEED);

        int bareTrees = 0, lushTrees = 0;

        for (int x = -600; x < 600; x++) {
            for (int z = -600; z < 600; z += 17) {
                if (lush.biomeAt(x, z) != Biome.JUNGLE) {
                    continue;
                }
                if (bare.treeTypeAt(x, z) != null) bareTrees++;
                if (lush.treeTypeAt(x, z) != null) lushTrees++;
            }
        }

        check("nulova hustota vypne stromy v tom biomu",
                bareTrees == 0 && lushTrees > 0, bareTrees + " vs " + lushTrees);

        // ⚠️ Vetsi koruna musi zvetsit DOSAH razitkovani, jinak by se
        // natunene koruny orezaly presne na svech chunku.
        BiomeTuning bigCrowns = BiomeTuning.defaults().with(Biome.PLAINS,
                new BiomeTuning.Tune(64, 20, 5, 4, 6, 5, 6, 1.0, 1.0));
        check("vetsi koruna zvetsi dosah razitkovani",
                bigCrowns.maxCrownRadius() == 6, "" + bigCrowns.maxCrownRadius());

        // A vyssi kmen musi snizit strop terenu, aby se strom vzdycky vesel.
        BiomeTuning tallTrees = BiomeTuning.defaults().with(Biome.PLAINS,
                new BiomeTuning.Tune(64, 20, 5, 4, 16, 2, 2, 1.0, 1.0));
        check("vyssi kmen snizi strop terenu",
                tallTrees.maxTreeHeight() == 18, "" + tallTrees.maxTreeHeight());

        // Cely sloupec s natunenymi stromy musi jit vygenerovat bez pádu
        // a stromy v nem musi byt - to je nejlevnejsi kontrola, ze se
        // razitkovani s tunenymi rozsahy nerozbilo.
        BiomeTuning varied = BiomeTuning.defaults().with(Biome.PLAINS,
                new BiomeTuning.Tune(64, 20, 16, 3, 10, 1, 5, 1.0, 1.0));
        TerrainGenerator forest = new TerrainGenerator(World.DEFAULT_SEED, varied);

        int logs = 0, leaves = 0;

        for (int cx = 0; cx < 4; cx++) {
            ChunkColumn column = forest.generateColumn(cx, 0);

            for (int x = 0; x < Chunk.SIZE; x++) {
                for (int y = 0; y < World.WORLD_HEIGHT; y++) {
                    for (int z = 0; z < Chunk.SIZE; z++) {
                        byte block = column.get(x, y, z);
                        if (block == World.LOG) logs++;
                        if (block == World.LEAVES) leaves++;
                    }
                }
            }
        }

        check("natunene stromy se do sloupcu opravdu vyrazitkuji",
                logs > 0 && leaves > logs, logs + " kmene, " + leaves + " listi");
    }

    // ==================================================================
    // 10) nahled v labu je skutecny strom
    // ==================================================================

    /**
     * ⚠️ NAHLED MUSI POSTAVIT TYZ STROM, JAKY VYROSTE VE SVETE.
     *
     * TreePreview je z vetsi casti GL, ale razitkovani samo GL nesaha -
     * je to staticka stamp() nad obycejnym World. Test ji zavola a porovna
     * vysledek s tim, co da TreeShape se stejnymi cisly z generatoru.
     * Kdyby si nahled kreslil "podobny" strom, byl by to obrazek a ne nahled
     * a lab by lhal - tataz uvaha jako u nahledu bloku (bajt po bajtu tyz
     * mesh) a u nahledu receptu (tataz Recipes.match).
     */
    static void previewIsReal() {
        System.out.println("\n-- nahled stromu je skutecny strom --");

        BiomeTuning tuning = BiomeTuning.defaults().with(Biome.TAIGA,
                new BiomeTuning.Tune(64, 16, 10, 5, 9, 1, 4, 1.0, 1.0));
        TerrainGenerator generator = new TerrainGenerator(World.DEFAULT_SEED, tuning);

        World world = TreePreview.createWorld();

        try {
            java.util.List<int[]> placed = new java.util.ArrayList<>();
            TreePreview.stamp(world, placed, Biome.TAIGA, tuning, generator,
                    TreePreview.X, TreePreview.Z);

            BiomeTuning.Tune tune = tuning.tune(Biome.TAIGA);
            int trunk = generator.trunkHeight(TreePreview.X, TreePreview.Z, tune);
            int delta = generator.crownDelta(TreePreview.X, TreePreview.Z,
                    Biome.TreeType.SPRUCE, tune);

            check("nahled pouziva rozsah z tuningu, ne z druhu stromu",
                    trunk >= 5 && trunk <= 9, "kmen " + trunk);

            // Kmen musi ve svete nahledu opravdu stat, cely a ze spravneho dreva.
            boolean wholeTrunk = true;
            for (int y = TreePreview.GROUND; y < TreePreview.GROUND + trunk; y++) {
                wholeTrunk &= world.getBlock(TreePreview.X, y, TreePreview.Z) == World.SPRUCE_LOG;
            }
            check("cely kmen stoji v nahledovem svete", wholeTrunk, "vyska " + trunk);

            check("a pod nim je povrch biomu, ne vzduch",
                    world.getBlock(TreePreview.X, TreePreview.GROUND - 1, TreePreview.Z)
                            == Biome.TAIGA.surface(), "");

            // LAB-17: mesuje se od sekce plosinky nahoru. Terenu v ni nesmi
            // byt nic - jinak by se mesoval a prestavoval pri kazdem kliknuti.
            int firstMeshed = ((TreePreview.GROUND - 1) >> 4) << 4;
            int terrainInSection = 0;
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++)
                    for (int y = firstMeshed; y < TreePreview.GROUND - 1; y++)
                        if (world.getBlock(x, y, z) != World.AIR) terrainInSection++;
            check("plosinka je prvni blok mesovane sekce (pod ni nic)",
                    TreePreview.GROUND - 1 == firstMeshed && terrainInSection == 0,
                    "sekce od " + firstMeshed + ", bloku terenu " + terrainInSection);

            check("nad kmenem uz kmen neni",
                    world.getBlock(TreePreview.X, TreePreview.GROUND + trunk, TreePreview.Z)
                            != World.SPRUCE_LOG, "");

            // A listi presne tam, kam ho poslal TreeShape - porovnano proti
            // nezavislemu razitkovani do mnoziny.
            Set<Long> expected = new HashSet<>();
            TreeShape.stamp(Biome.TreeType.SPRUCE, trunk, delta,
                    TreePreview.X, TreePreview.GROUND, TreePreview.Z,
                    new TreeShape.Sink() {
                        @Override public void leaves(int x, int y, int z, byte block) {
                            expected.add(packed(x, y, z));
                        }
                        @Override public void log(int x, int y, int z, byte block) { }
                    });

            int found = 0, missing = 0;
            for (long key : expected) {
                int x = (int) (key >> 40) - 512;
                int y = (int) ((key >> 20) & 0xFFFFF) - 512;
                int z = (int) (key & 0xFFFFF) - 512;

                byte block = world.getBlock(x, y, z);
                if (block == World.SPRUCE_LEAVES || block == World.SPRUCE_LOG) found++; else missing++;
            }

            check("kazdy list koruny je v nahledovem svete tam, kam ho posila TreeShape",
                    missing == 0 && found > 0, found + " nalezeno, " + missing + " chybi");

            // Druhe razitkovani musi po sobe uklidit: po prepnuti na poust
            // (bez stromu) nesmi zustat ani jeden smrkovy blok.
            TreePreview.stamp(world, placed, Biome.DESERT, tuning, generator,
                    TreePreview.X, TreePreview.Z);

            boolean cleared = true;
            for (int y = TreePreview.GROUND; y < TreePreview.GROUND + 20 && cleared; y++) {
                for (int dx = -6; dx <= 6 && cleared; dx++) {
                    for (int dz = -6; dz <= 6; dz++) {
                        byte block = world.getBlock(TreePreview.X + dx, y, TreePreview.Z + dz);
                        if (block == World.SPRUCE_LOG || block == World.SPRUCE_LEAVES) {
                            cleared = false;
                            break;
                        }
                    }
                }
            }
            check("prepnuti biomu uklidi predchozi strom", cleared, "");

            check("poust ukaze plosinku z pisku a zadny strom",
                    world.getBlock(TreePreview.X, TreePreview.GROUND - 1, TreePreview.Z) == World.SAND
                            && world.getBlock(TreePreview.X, TreePreview.GROUND, TreePreview.Z) == World.AIR, "");
        } finally {
            world.shutdown();
        }
    }

    /** Souradnice do jednoho cisla - posun o 512 kvuli zapornym hodnotam. */
    static long packed(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    static void delete(Path dir) throws IOException {
        try (var files = Files.walk(dir)) {
            for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ==================================================================
    // opravy z auditu: koruna, rudy, cisla v souboru, svy, nahled
    // ==================================================================

    /** Kolik listu strom polozi a jestli je list nad vrcholem kmene. */
    static int[] leaves(Biome.TreeType type, int trunk, int delta) {
        int[] result = new int[2];   // [pocet listu, list nad kmenem 0/1]
        TreeShape.stamp(type, trunk, delta, 0, 0, 0, new TreeShape.Sink() {
            @Override public void leaves(int x, int y, int z, byte block) {
                result[0]++;
                if (x == 0 && z == 0 && y == trunk) result[1] = 1;
            }
            @Override public void log(int x, int y, int z, byte block) { }
        });
        return result;
    }

    /**
     * BUG: vrstva, kterou delta stahla na polomer 0, se dal orezavala o rohy
     * - a jeji jediny blok je "roh". Dub a briza s korunou 1 koncily holym
     * spalkem, s korunou 0 nemely ani list; prales ztratil vrsek o krok pod
     * vychozi trojkou.
     */
    static void smallCrowns() {
        System.out.println("\n-- mala koruna nekonci holym spalkem --");

        for (Biome.TreeType type : new Biome.TreeType[]{
                Biome.TreeType.OAK, Biome.TreeType.BIRCH, Biome.TreeType.SPRUCE, Biome.TreeType.JUNGLE}) {
            boolean allHaveTop = true;
            String detail = "";

            for (int crown = BiomeTuning.MIN_CROWN; crown <= BiomeTuning.MAX_CROWN; crown++) {
                int delta = crown - type.maxRadius();
                int[] got = leaves(type, 5, delta);
                if (got[0] == 0 || got[1] == 0) {
                    allHaveTop = false;
                    detail += " koruna " + crown + ": " + got[0] + " listu, vrsek " + (got[1] == 1);
                }
            }

            check(type + ": kazda velikost koruny ma listi a list nad kmenem", allHaveTop, detail.trim());
        }

        // Vychozi koruna (delta 0) se nezmenila - svet s vychozim tuningem je bit po bitu tentyz.
        check("dub s vychozi korunou ma porad 21+21+9+5 listu jako driv",
                leaves(Biome.TreeType.OAK, 5, 0)[0] == 21 + 21 + 9 + 5,
                "" + leaves(Biome.TreeType.OAK, 5, 0)[0]);
    }

    static void oreNumbers() {
        System.out.println("\n-- rudy: preteceni, zaokrouhleni, zapis --");

        // Preteceni: skoro nulova hustota dala nejhustsi rudu (vzacnost 1).
        check("hustota 1e-9 neni nejhustsi ruda", BiomeTuning.rarity(60, 1e-9) > 1_000_000,
                "" + BiomeTuning.rarity(60, 1e-9));
        check("hustota 1e-300 je prakticky nikde", BiomeTuning.rarity(60, 1e-300) == Integer.MAX_VALUE,
                "" + BiomeTuning.rarity(60, 1e-300));
        check("nula je porad 'ruda tu neni'", BiomeTuning.rarity(60, 0.0) == 0, "");
        check("vychozi hory porad trefi IRON_RARITY_MOUNTAINS",
                BiomeTuning.rarity(TerrainGenerator.IRON_RARITY, 3.0) == TerrainGenerator.IRON_RARITY_MOUNTAINS, "");

        // Zaokrouhleni: kazdy krok labu zmeni svet (driv 32 kroku uhli = 18 svetu).
        for (int base : new int[]{TerrainGenerator.IRON_RARITY, TerrainGenerator.COAL_RARITY}) {
            double value = BiomeTuning.MIN_ORE;
            int steps = 0, same = 0;
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            seen.add(BiomeTuning.rarity(base, value));

            while (value < BiomeTuning.MAX_ORE && steps < 100) {
                double next = BiomeTunerLab.oreStep(value, +1, base);
                if (next < BiomeTuning.MAX_ORE && BiomeTuning.rarity(base, next) == BiomeTuning.rarity(base, value)) same++;
                seen.add(BiomeTuning.rarity(base, next));
                value = next;
                steps++;
            }

            check("zaklad " + base + ": kazdy krok + zmeni vzacnost (krome dorazu na mez)", same == 0,
                    same + " kroku naprazdno");
            check("zaklad " + base + ": a dojde se az na mez", value == BiomeTuning.MAX_ORE, "" + value);

            double down = BiomeTunerLab.oreStep(value, -1, base);
            check("zaklad " + base + ": krok - z meze taky zmeni vzacnost",
                    BiomeTuning.rarity(base, down) != BiomeTuning.rarity(base, value), value + " -> " + down);
        }

        check("lab ukazuje skutecny nasobek: 7,0x uhli je 7,5x",
                Math.abs(BiomeTuning.effectiveDensity(TerrainGenerator.COAL_RARITY, 7.0) - 7.5) < 1e-9,
                "" + BiomeTuning.effectiveDensity(TerrainGenerator.COAL_RARITY, 7.0));
        check("a 3,0x zeleza je presne 3,0x",
                BiomeTuning.effectiveDensity(TerrainGenerator.IRON_RARITY, 3.0) == 3.0, "");

        // Zapis: 0,004 se driv ulozilo jako 0.00 (ruda vypnuta).
        check("0,004 se zapise beze ztraty", Double.parseDouble(BiomeTuning.decimalText(0.004)) == 0.004,
                BiomeTuning.decimalText(0.004));
        check("krok labu (3,25) se zapise na dve mista", BiomeTuning.decimalText(3.25).equals("3.25"),
                BiomeTuning.decimalText(3.25));
        check("cele cislo taky na dve mista (jako drive)", BiomeTuning.decimalText(3.0).equals("3.00"), "");
    }

    static void fileNumbers() throws IOException {
        System.out.println("\n-- cisla v biome_tuning.json: preteceni a zlomky --");

        Path dir = Files.createTempDirectory("mc-tuning-numbers");
        Path file = dir.resolve("biome_tuning.json");

        // Driv: (int) Math.round(4294967300.0) pretekl na 4 a orez nic nenahlasil.
        Files.writeString(file, "{\"format\": 1, \"biomes\": {\"plains\": {"
                + "\"trunkMax\": 4294967300, \"trunkMin\": 4294967298, \"baseHeight\": 3000000000,"
                + " \"amplitude\": 20.4, \"ironDensity\": 0.004}}}");

        BiomeTuning loaded = BiomeTuning.load(file);
        BiomeTuning.Tune t = loaded.tune(Biome.PLAINS);
        check("obri kmen se orizne na HORNI mez, ne na preteceny zbytek",
                t.trunkMin() == BiomeTuning.MAX_TRUNK && t.trunkMax() == BiomeTuning.MAX_TRUNK,
                t.trunkMin() + "-" + t.trunkMax());
        check("obri zakladni vyska se orizne na MAX_BASE, ne na MIN_BASE",
                t.baseHeight() == BiomeTuning.MAX_BASE, "" + t.baseHeight());
        check("zlomek se zaokrouhli", t.amplitude() == 20, "" + t.amplitude());

        // Soubor s vyhradou (preteceni, zlomek) se pred prepsanim zazalohuje.
        check("ulozeni projde", loaded.save(file), "");
        check("soubor s vyhradami je v .bak", Files.isRegularFile(SafeFiles.backupOf(file)), "");

        BiomeTuning back = BiomeTuning.load(file);
        check("0,004 prezije ulozeni a nacteni (ruda se nevypne)",
                back.tune(Biome.PLAINS).ironDensity() == 0.004, "" + back.tune(Biome.PLAINS).ironDensity());
    }

    /**
     * Tunena verze surfaceHeight() proti naivni sume vah - s cisly, ktera
     * se pro KAZDY biom lisi. Na vychozich cislech maji prales a brezovy les
     * totez (64/18), takze by jejich zamena ve vzorci prosla.
     */
    static void tunedSurfaceHeight() {
        System.out.println("\n-- tunena vyska proti naivni sume (kazdy biom jina cisla) --");

        BiomeTuning tuning = BiomeTuning.defaults();
        int i = 0;
        for (Biome b : Biome.values()) {
            BiomeTuning.Tune d = tuning.tune(b);
            tuning = tuning.with(b, new BiomeTuning.Tune(20 + 9 * i, 3 + 5 * i, d.treeDensity(),
                    d.trunkMin(), d.trunkMax(), d.crownMin(), d.crownMax(), d.ironDensity(), d.coalDensity()));
            i++;
        }

        java.util.Random random = new java.util.Random(7);
        double worst = 0;
        for (int n = 0; n < 50_000; n++) {
            double t = random.nextDouble() * 2.4 - 1.2;
            double h = random.nextDouble() * 2.4 - 1.2;
            double r = random.nextDouble() * 2.4 - 1.2;
            double fbm = random.nextDouble() * 2 - 1;

            double[] w = Biome.weights(t, h, r);
            double base = 0, amplitude = 0;
            for (Biome b : Biome.values()) {
                base += w[b.ordinal()] * tuning.tune(b).baseHeight();
                amplitude += w[b.ordinal()] * tuning.tune(b).amplitude();
            }
            worst = Math.max(worst, Math.abs(base + fbm * amplitude - Biome.surfaceHeight(tuning, t, h, r, fbm)));
        }

        check("vytknuty tuneny vzorec = naivni suma pro kazdy biom zvlast", worst < 1e-9, "" + worst);
    }

    /**
     * Uplnost korun na svech chunku s natunenou (nejvetsi) korunou. TreeTest
     * to hlida jen na vychozim tuningu; kdyby se dosah razitkovani vratil na
     * hodnotu z druhu stromu, natunene koruny by se na svech usekly.
     */
    static void tunedCrownSeams() {
        System.out.println("\n-- natunena koruna se na svech chunku neusekne --");

        BiomeTuning tuning = BiomeTuning.defaults();
        for (Biome b : Biome.values()) {
            BiomeTuning.Tune d = tuning.tune(b);
            tuning = tuning.with(b, new BiomeTuning.Tune(d.baseHeight(), d.amplitude(), d.treeDensity(),
                    d.trunkMin(), d.trunkMax(), BiomeTuning.MAX_CROWN, BiomeTuning.MAX_CROWN,
                    d.ironDensity(), d.coalDensity()));
        }

        World world = new World(World.DEFAULT_SEED, tuning);
        world.loadRadius = 3;
        world.unloadRadius = 5;
        world.updateBlocking(8f, 8f);
        TerrainGenerator gen = world.generator();

        int spanning = 0, missing = 0;
        for (int x = -16; x < 32; x++) {
            for (int z = -16; z < 32; z++) {
                Biome.TreeType type = gen.treeTypeAt(x, z);
                if (type == null) continue;

                BiomeTuning.Tune tune = tuning.tune(gen.biomeAt(x, z));
                int ground = gen.terrainHeight(x, z);
                int trunk = gen.trunkHeight(x, z, tune);
                int delta = gen.crownDelta(x, z, type, tune);
                int reach = TreeShape.reach(type, delta);

                if ((x - reach >> Chunk.BITS) == (x + reach >> Chunk.BITS)
                        && (z - reach >> Chunk.BITS) == (z + reach >> Chunk.BITS)) continue;
                spanning++;

                int[] miss = {0};
                int tx = x, tz = z;
                TreeShape.stamp(type, trunk, delta, x, ground, z, new TreeShape.Sink() {
                    @Override public void leaves(int lx, int y, int lz, byte block) {
                        if (lx == tx && lz == tz && y < ground + trunk) return;   // tam je kmen
                        if (world.getBlock(lx, y, lz) == World.AIR) miss[0]++;
                    }
                    @Override public void log(int lx, int y, int lz, byte block) { }
                });
                missing += miss[0];
            }
        }

        check("nejaky natuneny strom opravdu presahuje sev (test neni degenerovany)", spanning > 0, "" + spanning);
        check("zadna natunena koruna neni na svu useknuta", missing == 0, missing + " chybejicich listu");
        world.shutdown();
    }

    /** Nahled stromu stoji na terenu s VYCHOZIM tuningem, i kdyz aktivni tuning plane zvedne. */
    static void previewTerrainIgnoresTuning() {
        System.out.println("\n-- nahled stromu: teren pod nim nezavisi na aktivnim tuningu --");

        BiomeTuning high = BiomeTuning.defaults();
        for (Biome b : Biome.values()) {
            BiomeTuning.Tune d = high.tune(b);
            high = high.with(b, new BiomeTuning.Tune(BiomeTuning.MAX_BASE, 60, d.treeDensity(),
                    d.trunkMin(), d.trunkMax(), d.crownMin(), d.crownMax(), d.ironDensity(), d.coalDensity()));
        }

        BiomeTuning before = BiomeTuning.active();
        BiomeTuning.activate(high);
        World world;
        try {
            world = TreePreview.createWorld();
        } finally {
            BiomeTuning.activate(before);
        }

        int top = -1;
        for (int y = World.WORLD_HEIGHT - 1; y >= 0; y--) {
            if (world.getBlock(TreePreview.X, y, TreePreview.Z) != World.AIR) { top = y; break; }
        }

        check("teren u plosinky je pod ni i s plani na 110", top < TreePreview.GROUND - 1, "vrchol " + top);
        world.shutdown();
    }
}
