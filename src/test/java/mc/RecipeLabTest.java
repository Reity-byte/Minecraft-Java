package mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Overuje Recipe Lab: recepty z textures/recipes.json.
 *
 * ---------------------------------------------------------------------------
 * Nejdulezitejsi kontrola je ta posledni: recept zalozeny v labu musi
 * vyhodnotit TATAZ cesta kodem jako vestaveny recept. Lab si nesmi nest
 * vlastni pravidla shody - jinak by byly dve odpovedi na otazku "co je
 * shoda" a nikdo by nepoznal, ktera plati ve hre.
 *
 * ⚠️ Vsechny testy si na konci vrati puvodni aktivni seznam. RecipeBook.active()
 * je globalni stav (jako BlockRegistry.active()) a ostatni testy pocitaji
 * s tim, ze v nem nic neni - AllTests bezi vsechno v jednom JVM.
 * ---------------------------------------------------------------------------
 *
 * Nesaha na GL.
 */
public class RecipeLabTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        RecipeBook before = RecipeBook.active();

        try {
            validation();
            normalization();
            roundTrip();
            brokenFile();
            sameCodePath();
            liveWithoutRestart();
            labDraft();
        } finally {
            RecipeBook.activate(before);
        }

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    /** Recept 2x2 ze ctyr kamenu na jeden blok snehu - nic vestaveneho to neni. */
    static Recipes.Recipe snowFromStone() {
        return new Recipes.Recipe(2, 2, new byte[]{
                World.STONE, World.SNOW,
                World.SNOW, World.STONE
        }, World.SNOW, 3);
    }

    // ==================================================================
    // 1) kontrola hodnot
    // ==================================================================

    static void validation() {
        System.out.println("\n-- kontrola receptu --");

        check("platny recept projde", RecipeBook.validate(snowFromStone()) == null,
                "" + RecipeBook.validate(snowFromStone()));

        check("null se odmitne", RecipeBook.validate(null) != null, "");

        check("prazdna mrizka se odmitne",
                RecipeBook.validate(new Recipes.Recipe(2, 2, new byte[4], World.STONE, 1)) != null, "");

        check("vzor, ktery nesedi s rozmerem, se odmitne",
                RecipeBook.validate(new Recipes.Recipe(2, 2, new byte[]{World.STONE},
                        World.STONE, 1)) != null, "");

        check("mrizka vetsi nez crafting table se odmitne",
                RecipeBook.validate(new Recipes.Recipe(4, 1,
                        new byte[]{World.STONE, World.STONE, World.STONE, World.STONE},
                        World.STONE, 1)) != null, "");

        check("nulovy ani zaporny pocet kusu neprojde",
                RecipeBook.validate(new Recipes.Recipe(1, 1, new byte[]{World.STONE}, World.STONE, 0)) != null
                        && RecipeBook.validate(new Recipes.Recipe(1, 1, new byte[]{World.STONE},
                                World.STONE, -3)) != null, "");

        check("vic kusu, nez se vejde do hromadky, neprojde",
                RecipeBook.validate(new Recipes.Recipe(1, 1, new byte[]{World.STONE},
                        World.STONE, ItemStack.MAX_COUNT + 1)) != null, "");

        check("cela hromadka na vystupu projde",
                RecipeBook.validate(new Recipes.Recipe(1, 1, new byte[]{World.STONE},
                        World.STONE, ItemStack.MAX_COUNT)) == null, "");

        // ⚠️ Blok z labu, ktery neexistuje, recept vyradi - jinak by se
        // v mrizce objevila surovina, kterou nejde nikde vzit.
        check("neznamy blok z labu jako surovina se odmitne",
                RecipeBook.validate(new Recipes.Recipe(1, 1, new byte[]{(byte) 99},
                        World.STONE, 1)) != null, "");
        check("neznamy blok z labu jako vysledek se odmitne",
                RecipeBook.validate(new Recipes.Recipe(1, 1, new byte[]{World.STONE},
                        (byte) 99, 1)) != null, "");

        check("vzduch jako vysledek se odmitne",
                RecipeBook.validate(new Recipes.Recipe(1, 1, new byte[]{World.STONE},
                        World.AIR, 1)) != null, "");
    }

    // ==================================================================
    // 2) orez na nejmensi obdelnik
    // ==================================================================

    /**
     * ⚠️ Lab sklada vzdycky do 3x3, ale recept si nese svou velikost a hleda
     * se kdekoliv v mrizce. Bez orezu by se recept 3x3 s jednou surovinou
     * uprostred do male mrizky 2x2 u inventare NEVESEL VUBEC.
     */
    static void normalization() {
        System.out.println("\n-- orez vzoru --");

        byte[] big = new byte[9];
        big[4] = World.STONE;                       // jen prostredni bunka
        Recipes.Recipe trimmed = RecipeBook.normalize(
                new Recipes.Recipe(3, 3, big, World.SNOW, 2));

        check("recept 3x3 s jednou surovinou se orezal na 1x1",
                trimmed.width() == 1 && trimmed.height() == 1
                        && trimmed.pattern().length == 1 && trimmed.pattern()[0] == World.STONE,
                trimmed.width() + "x" + trimmed.height());
        check("orez nechal vysledek i pocet", trimmed.result() == World.SNOW
                && trimmed.resultCount() == 2, "");

        // Vodorovny pruh dole vlevo -> 2x1, ne 3x3.
        byte[] row = new byte[9];
        row[6] = World.PLANKS;
        row[7] = World.PLANKS;
        Recipes.Recipe strip = RecipeBook.normalize(new Recipes.Recipe(3, 3, row, World.FENCE, 1));
        check("pruh dvou bloku se orezal na 2x1",
                strip.width() == 2 && strip.height() == 1, strip.width() + "x" + strip.height());

        // Uz tesny vzor se nesmi zmenit ani o bajt.
        Recipes.Recipe tight = snowFromStone();
        Recipes.Recipe same = RecipeBook.normalize(tight);
        check("tesny vzor zustane, jaky byl",
                same.width() == 2 && same.height() == 2
                        && java.util.Arrays.equals(same.pattern(), tight.pattern()), "");

        // A orezany recept se skutecne vejde do male mrizky - to je ten duvod.
        RecipeBook book = RecipeBook.empty().with(new Recipes.Recipe(3, 3, big, World.SNOW, 2));
        RecipeBook previous = RecipeBook.active();
        RecipeBook.activate(book);

        Container small = new Container(4);
        small.set(0, ItemStack.of(World.STONE, 1));
        ItemStack out = Recipes.match(small, 2, 2);

        RecipeBook.activate(previous);

        check("orezany recept funguje i v male mrizce 2x2",
                out.block() == World.SNOW && out.count() == 2, out.toString());
    }

    // ==================================================================
    // 3) soubor tam a zpet
    // ==================================================================

    static void roundTrip() throws IOException {
        System.out.println("\n-- recipes.json tam a zpet --");

        Path dir = Files.createTempDirectory("mc-recipes");
        Path file = dir.resolve("textures").resolve("recipes.json");

        try {
            check("chybejici soubor da prazdny seznam MLCKY",
                    RecipeBook.load(file).isEmpty(), "");

            RecipeBook book = RecipeBook.empty()
                    .with(snowFromStone())
                    .with(new Recipes.Recipe(1, 1, new byte[]{World.LOG}, World.TORCH, 8));

            check("dva recepty jsou v seznamu", book.size() == 2, "" + book.size());
            check("ulozeni zalozi adresar a zapise soubor",
                    book.save(file) && Files.isRegularFile(file), "");

            RecipeBook loaded = RecipeBook.load(file);
            check("nactou se oba recepty", loaded.size() == 2, "" + loaded.size());

            Recipes.Recipe first = loaded.recipes().get(0);
            check("prvni recept prisel beze zmeny",
                    first.width() == 2 && first.height() == 2
                            && java.util.Arrays.equals(first.pattern(), snowFromStone().pattern())
                            && first.result() == World.SNOW && first.resultCount() == 3,
                    first.width() + "x" + first.height() + " -> " + first.result());

            Recipes.Recipe second = loaded.recipes().get(1);
            check("druhy taky", second.width() == 1 && second.height() == 1
                    && second.pattern()[0] == World.LOG && second.result() == World.TORCH
                    && second.resultCount() == 8, "");

            // Zapsat znovu to, co se prave nacetlo, musi dat TENTYZ text -
            // jinak by se soubor menil pri kazdem ulozeni bez zmeny obsahu.
            String text = Files.readString(file);
            loaded.save(file);
            check("druhe ulozeni da bajt po bajtu tentyz soubor",
                    Files.readString(file).equals(text), "");

            check("soubor konci novym radkem a ma mezery po dvou",
                    text.endsWith("}\n") && text.contains("\n  \"recipes\""), "");

            // Prazdny seznam se zapise jako prazdne pole a nacte zpatky prazdny.
            check("prazdny seznam se ulozi a nacte prazdny",
                    RecipeBook.empty().save(file) && RecipeBook.load(file).isEmpty(), "");
        } finally {
            clean(dir);
        }
    }

    // ==================================================================
    // 4) poskozeny soubor
    // ==================================================================

    static void brokenFile() throws IOException {
        System.out.println("\n-- poskozeny soubor --");
        System.out.println("  (nize ocekavane hlasky o poskozenych souborech)");

        Path dir = Files.createTempDirectory("mc-recipes-broken");
        Path file = dir.resolve("recipes.json");

        try {
            for (String junk : new String[]{"tohle neni json", "", "[1,2,3]", "{}",
                    "{\"format\": 1}", "{\"recipes\": []}", "{\"format\": \"x\", \"recipes\": []}"}) {
                Files.writeString(file, junk);
                if (!RecipeBook.load(file).isEmpty()) {
                    check("poskozeny soubor da prazdny seznam: " + junk, false, "");
                    return;
                }
            }
            check("kazdy druh poskozeneho souboru da prazdny seznam (= jen vestavene recepty)",
                    true, "");

            // ⚠️ JEDEN SPATNY RECEPT NESHODI OSTATNI - stejne pravidlo jako
            // u blocks.json. Soubor se da psat i rucne a preklep v jednom
            // zaznamu nesmi znamenat, ze zmizi vsechny.
            Files.writeString(file, """
                    {
                      "format": 1,
                      "recipes": [
                        {"width": 1, "height": 1, "pattern": [99], "result": 2, "count": 1},
                        {"width": 1, "height": 1, "pattern": [2], "result": 15, "count": 4},
                        {"width": 5, "height": 5, "pattern": [2], "result": 2, "count": 1}
                      ]
                    }
                    """);

            RecipeBook partial = RecipeBook.load(file);
            check("neplatne recepty se preskoci a platny zustane", partial.size() == 1,
                    "" + partial.size());
            check("zustal ten spravny", partial.size() == 1
                    && partial.recipes().get(0).result() == World.SNOW, "");

            // Soubor, ktery nesel cely nacist, se pred prepsanim zalohuje -
            // jinak by prvni Save z labu ty preskocene recepty tise smazal.
            Path backup = file.resolveSibling("recipes.json.bak");
            Files.deleteIfExists(backup);
            partial.save(file);
            check("poskozeny soubor se pred prepsanim zalohoval do .bak",
                    Files.isRegularFile(backup), "");

            // Novejsi format se nacte s varovanim, ne zahodi.
            Files.writeString(file, """
                    {
                      "format": 99,
                      "recipes": [
                        {"width": 1, "height": 1, "pattern": [2], "result": 15, "count": 1}
                      ]
                    }
                    """);
            check("novejsi format se nacte s varovanim", RecipeBook.load(file).size() == 1, "");
        } finally {
            clean(dir);
        }
    }

    // ==================================================================
    // 5) TATAZ cesta kodem jako vestaveny recept
    // ==================================================================

    /**
     * ⚠️ TOHLE JE TA HLAVNI KONTROLA CELEHO MODU.
     *
     * Recept z labu se nesmi chovat jinak nez recept z kodu: musi se hledat
     * kdekoliv v mrizce, musi vadit surovina navic mimo jeho obdelnik a musi
     * fungovat v male i velke mrizce presne podle sve velikosti. Testuje se
     * to tim, ze se tytez otazky poloz i vestavenemu receptu a vysledky se
     * porovnaji - ne tim, ze se popise, jak se to ma chovat.
     */
    static void sameCodePath() {
        System.out.println("\n-- recept z labu se chova jako vestaveny --");

        RecipeBook previous = RecipeBook.active();

        // Recept z labu: ctverec 2x2 ze snehu -> osm kamennych cihel.
        Recipes.Recipe lab = new Recipes.Recipe(2, 2, new byte[]{
                World.SNOW, World.SNOW,
                World.SNOW, World.SNOW
        }, World.STONE_BRICKS, 8);

        RecipeBook.activate(RecipeBook.empty().with(lab));

        try {
            // Vestaveny protejsek stejneho tvaru: ctverec 2x2 z prken.
            byte builtInIngredient = World.PLANKS;
            byte labIngredient = World.SNOW;

            // (a) obe varianty v male mrizce 2x2
            check("recept z labu funguje v male mrizce",
                    square(labIngredient, 4, 2, 2).block() == World.STONE_BRICKS, "");
            check("a vestaveny tam funguje porad",
                    square(builtInIngredient, 4, 2, 2).block() == World.CRAFTING_TABLE, "");

            // (b) obe varianty se hledaji KDEKOLIV v mrizce 3x3
            boolean labAnywhere = true, builtInAnywhere = true;

            for (int offsetY = 0; offsetY <= 1; offsetY++) {
                for (int offsetX = 0; offsetX <= 1; offsetX++) {
                    labAnywhere &= squareAt(labIngredient, offsetX, offsetY).block() == World.STONE_BRICKS;
                    builtInAnywhere &= squareAt(builtInIngredient, offsetX, offsetY).block()
                            == World.CRAFTING_TABLE;
                }
            }

            check("recept z labu se najde v ktermkoliv rohu mrizky 3x3", labAnywhere, "");
            check("vestaveny taky - tedy tataz pravidla", builtInAnywhere, "");

            // (c) surovina navic mimo obdelnik receptu ho vyradi, u obou stejne
            Container extraLab = grid3(labIngredient, 0, 0);
            extraLab.set(8, ItemStack.of(World.DIRT, 1));
            Container extraBuiltIn = grid3(builtInIngredient, 0, 0);
            extraBuiltIn.set(8, ItemStack.of(World.DIRT, 1));

            check("surovina navic vyradi recept z labu",
                    Recipes.match(extraLab, 3, 3).isEmpty(), "");
            check("a vestaveny stejne tak", Recipes.match(extraBuiltIn, 3, 3).isEmpty(), "");

            // (d) pocet kusu z receptu dorazi na vystup
            check("pocet kusu na vystupu sedi",
                    square(labIngredient, 4, 2, 2).count() == 8, "");

            // (e) VESTAVENE RECEPTY SE NEMENI. Kdyby lab prepisoval seznam
            // misto pridavani, zmizely by.
            Container planks = new Container(4);
            for (int i = 0; i < 4; i++) planks.set(i, ItemStack.of(World.PLANKS, 1));
            Container stone = new Container(4);
            for (int i = 0; i < 4; i++) stone.set(i, ItemStack.of(World.STONE, 1));
            Container logOnly = new Container(4);
            logOnly.set(0, ItemStack.of(World.LOG, 1));

            check("vsechny vestavene recepty fungujou dal",
                    Recipes.match(planks, 2, 2).block() == World.CRAFTING_TABLE
                            && Recipes.match(stone, 2, 2).block() == World.STONE_BRICKS
                            && Recipes.match(logOnly, 2, 2).block() == World.PLANKS, "");

            // (f) ⚠️ Vestaveny recept ma PREDNOST. Lab takovy vzor ani
            // neuklada (problem() ho odmitne), ale kdyby se do souboru dostal
            // rucne, nesmi prebit hru.
            RecipeBook.activate(RecipeBook.empty().with(new Recipes.Recipe(2, 2, new byte[]{
                    World.PLANKS, World.PLANKS, World.PLANKS, World.PLANKS
            }, World.SNOW, 64)));

            check("recept z labu neprebije vestaveny se stejnym vzorem",
                    Recipes.match(planks, 2, 2).block() == World.CRAFTING_TABLE,
                    Recipes.match(planks, 2, 2).toString());

            check("builtInHasPattern takovy vzor pozna",
                    Recipes.builtInHasPattern(new Recipes.Recipe(2, 2, new byte[]{
                            World.PLANKS, World.PLANKS, World.PLANKS, World.PLANKS
                    }, World.SNOW, 1)), "");
            check("a u vzoru, ktery vestaveny neni, ne",
                    !Recipes.builtInHasPattern(lab), "");

            // (g) prazdny seznam = hra presne jako driv
            RecipeBook.activate(RecipeBook.empty());
            check("bez receptu z labu vrati mrizka snehu prazdno",
                    square(labIngredient, 4, 2, 2).isEmpty(), "");
        } finally {
            RecipeBook.activate(previous);
        }
    }

    /** Mrizka columns x rows vyplnena blokem v prvnich `count` slotech. */
    static ItemStack square(byte block, int count, int columns, int rows) {
        Container grid = new Container(columns * rows);
        for (int i = 0; i < count; i++) grid.set(i, ItemStack.of(block, 1));
        return Recipes.match(grid, columns, rows);
    }

    static Container grid3(byte block, int offsetX, int offsetY) {
        Container grid = new Container(9);
        for (int y = 0; y < 2; y++)
            for (int x = 0; x < 2; x++)
                grid.set((y + offsetY) * 3 + x + offsetX, ItemStack.of(block, 1));
        return grid;
    }

    static ItemStack squareAt(byte block, int offsetX, int offsetY) {
        return Recipes.match(grid3(block, offsetX, offsetY), 3, 3);
    }

    // ==================================================================
    // 6) plati hned, bez restartu
    // ==================================================================

    /**
     * ⚠️ Save zapise soubor A ZAROVEN aktivuje novy seznam. Recipes.match()
     * se pta aktivniho seznamu, ne souboru, takze recept funguje okamzite -
     * kdyby se jen zapsal, hrac by musel restartovat hru, aby si ho zkusil.
     */
    static void liveWithoutRestart() throws IOException {
        System.out.println("\n-- recept plati hned --");

        RecipeBook previous = RecipeBook.active();
        Path dir = Files.createTempDirectory("mc-recipes-live");
        Path file = dir.resolve("recipes.json");

        try {
            RecipeBook.activate(RecipeBook.empty());

            Container grid = new Container(4);
            grid.set(0, ItemStack.of(World.SNOW, 1));
            grid.set(1, ItemStack.of(World.SNOW, 1));

            check("pred zalozenim receptu mrizka nic nedava",
                    Recipes.match(grid, 2, 2).isEmpty(), "");

            RecipeBook updated = RecipeBook.active().with(new Recipes.Recipe(2, 1,
                    new byte[]{World.SNOW, World.SNOW}, World.IRON_ORE, 2));

            check("zapis projde", updated.save(file), "");

            // Zapsano, ale jeste neaktivovano - hra o nem porad nevi.
            check("samotny zapis souboru hru nezmeni",
                    Recipes.match(grid, 2, 2).isEmpty(), "");

            RecipeBook.activate(updated);

            ItemStack out = Recipes.match(grid, 2, 2);
            check("po aktivaci recept plati OKAMZITE, bez restartu",
                    out.block() == World.IRON_ORE && out.count() == 2, out.toString());

            // A po "restartu" (nacteni ze souboru) plati porad.
            RecipeBook.activate(RecipeBook.load(file));
            ItemStack afterRestart = Recipes.match(grid, 2, 2);
            check("a po nacteni ze souboru taky",
                    afterRestart.block() == World.IRON_ORE && afterRestart.count() == 2,
                    afterRestart.toString());

            // Seznam je nemenny: with() vrati novy a stary zustane, jaky byl.
            RecipeBook base = RecipeBook.empty();
            RecipeBook grown = base.with(snowFromStone());
            check("with() vrati novy seznam a stary necha byt",
                    base.isEmpty() && grown.size() == 1, "");

            check("containsPattern pozna uz ulozeny vzor",
                    grown.containsPattern(snowFromStone())
                            && !grown.containsPattern(new Recipes.Recipe(1, 1,
                                    new byte[]{World.DIRT}, World.STONE, 1)), "");

            // Recept smi odkazovat na blok z labu - to je rozdil proti
            // generatoru terenu, kde plati "jen vestavene bloky".
            BlockRegistry registry = BlockRegistry.empty()
                    .with(BlockRegistry.empty().define("Marble", 1.5f, true, true, 63, 62, 63));
            BlockRegistry beforeRegistry = BlockRegistry.active();
            BlockRegistry.activate(registry);

            byte labBlock = (byte) BlockRegistry.FIRST_ID;
            String problem = RecipeBook.validate(new Recipes.Recipe(1, 1,
                    new byte[]{World.STONE}, labBlock, 1));

            BlockRegistry.activate(beforeRegistry);
            check("blok z labu smi byt vysledkem receptu", problem == null, "" + problem);
        } finally {
            RecipeBook.activate(previous);
            clean(dir);
        }
    }

    // ==================================================================
    // 7) logika samotneho modu, bez GL
    // ==================================================================

    /**
     * Co lab ulozi a proc to nekdy odmitne.
     *
     * ⚠️ RecipeLab jde postavit i s null kreslitky: draft(), problem()
     * a existingResult() jsou cista logika nad Containerem a Recipes.match().
     * Kreslit se tu nic nebude, takze na GL nesahne.
     */
    static void labDraft() {
        System.out.println(System.lineSeparator() + "-- co lab ulozi --");

        RecipeBook previous = RecipeBook.active();
        RecipeBook.activate(RecipeBook.empty());

        try {
            RecipeLab lab = new RecipeLab(null, null, null);

            check("prazdna mrizka nejde ulozit a rekne proc",
                    lab.draft() == null && lab.problem() != null, "" + lab.problem());

            // Jedna surovina uprostred 3x3 -> ulozi se jako 1x1.
            lab.grid().set(4, ItemStack.of(World.SNOW, 1));
            Recipes.Recipe draft = lab.draft();

            check("lab ulozi orezany vzor, ne cele 3x3",
                    draft != null && draft.width() == 1 && draft.height() == 1,
                    draft == null ? "null" : draft.width() + "x" + draft.height());
            check("a takovy recept jde ulozit", lab.problem() == null, "" + lab.problem());

            // ⚠️ Vzor, ktery uz ma VESTAVENY recept, lab odmitne - match()
            // zkousi vestavene prvni, takze by ulozeny recept nikdy nevyhral
            // a tise by nedelal nic.
            lab.clear();
            for (int i : new int[]{0, 1, 3, 4}) {
                lab.grid().set(i, ItemStack.of(World.PLANKS, 1));
            }

            String problem = lab.problem();
            check("vzor vestaveneho receptu lab odmitne", problem != null, "" + problem);
            check("a rekne, ze uz ho ma vestaveny recept",
                    problem != null && problem.contains("built-in"), "" + problem);

            // Nahled v labu = to, co rekne crafting table.
            check("lab ukaze tentyz vysledek jako Recipes.match()",
                    lab.existingResult().block() == World.CRAFTING_TABLE,
                    lab.existingResult().toString());

            // Uz ulozeny vzor se neulozi podruhe.
            lab.clear();
            lab.grid().set(0, ItemStack.of(World.SNOW, 1));
            lab.grid().set(1, ItemStack.of(World.SNOW, 1));

            check("novy vzor jde ulozit", lab.problem() == null, "" + lab.problem());

            RecipeBook.activate(RecipeBook.active().with(lab.draft()));

            check("tentyz vzor podruhe uz ne", lab.problem() != null, "");
            check("a rekne, ze ho uz ma ulozeny recept",
                    lab.problem().contains("saved"), lab.problem());

            check("Clear vyprazdni mrizku a lab je zase bez receptu",
                    clearedIsEmpty(lab), "");
        } finally {
            RecipeBook.activate(previous);
        }
    }

    static boolean clearedIsEmpty(RecipeLab lab) {
        lab.clear();
        return lab.draft() == null;
    }

    static void clean(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList())
                Files.deleteIfExists(p);
        }
    }
}
