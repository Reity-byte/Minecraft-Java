package mc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Overuje registr bloku z labu a jeho soubor textures/blocks.json.
 *
 * Nejdulezitejsi je STABILITA ID: blok ulozeny ve svete nese jen cislo, takze
 * kdyby se po nacteni souboru id posunula nebo se id smazaneho bloku pridelilo
 * znovu, kostky ve svete by se tise promenily v jiny blok. Druha vec je, ze
 * hra kvuli souboru nikdy nespadne - poskozeny soubor znamena prazdny registr,
 * spatny jednotlivy blok jen jeho preskoceni.
 */
public class BlockRegistryTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("mc-blocks-test");

        try {
            format();
            roundTrip(dir);
            missingAndCorrupt(dir);
            invalidBlocks();
            idStability(dir);
            versionAndEscapes();
            jsonParser();
            full(dir);
            backup(dir);
            activeRegistry();
        } finally {
            // Aktivni registr je static - test nesmi nechat svoje bloky ostatnim testum v teze JVM.
            BlockRegistry.activate(BlockRegistry.empty());
        }

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ==================================================================
    // pomocne
    // ==================================================================

    /** Co akce napsala na stderr (zachyceno, aby ocekavane chyby nezasypaly vypis). */
    static String lastErr = "";

    static <T> T quiet(Supplier<T> action) {
        PrintStream old = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            return action.get();
        } finally {
            System.setErr(old);
            lastErr = buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /** Zapise text do souboru a nacte ho; vyjimka z load() je chyba testu, ne pad. */
    static BlockRegistry loadText(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return quiet(() -> BlockRegistry.load(file));
        } catch (Throwable t) {
            check("load() nesmi hodit vyjimku", false, t.toString());
            return null;
        }
    }

    static BlockRegistry loadBytes(Path file, byte[] content) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
            return quiet(() -> BlockRegistry.load(file));
        } catch (Throwable t) {
            check("load() nesmi hodit vyjimku", false, t.toString());
            return null;
        }
    }

    /** Jeden blok jako JSON; id a hardness jako text, aby slo psat i nesmysly. */
    static String block(String id, String name, String hardness, int top, int side, int bottom) {
        return "{\"id\": " + id + ", \"name\": \"" + name + "\", \"hardness\": " + hardness
                + ", \"solid\": true, \"opaque\": true, \"tiles\": {\"top\": " + top
                + ", \"side\": " + side + ", \"bottom\": " + bottom + "}}";
    }

    /** Cely soubor; nextId null = bez pole nextId. */
    static String file(String nextId, String... blocks) {
        return "{\"format\": 1, " + (nextId == null ? "" : "\"nextId\": " + nextId + ", ")
                + "\"blocks\": [" + String.join(", ", blocks) + "]}";
    }

    static boolean sameRegistry(BlockRegistry a, BlockRegistry b) {
        return a != null && b != null && a.blocks().equals(b.blocks()) && a.nextId() == b.nextId();
    }

    /** Tri bloky pres define+with, jako je zaklada lab. */
    static BlockRegistry sample() {
        BlockRegistry r = BlockRegistry.empty();
        r = r.with(r.define("Marble", 1.5f, true, true, 63, 62, 63));
        r = r.with(r.define("Glass Pane", 0.1f, true, false, 10, 11, 12));
        r = r.with(r.define("  Soft Moss  ", 0f, false, false, 0, 0, 0));
        return r;
    }

    // ==================================================================
    // presny tvar souboru
    // ==================================================================

    static void format() {
        System.out.println("\n--- tvar souboru ---");

        String expected = String.join("\n",
                "{",
                "  \"format\": 1,",
                "  \"nextId\": 66,",
                "  \"blocks\": [",
                "    {",
                "      \"id\": 64,",
                "      \"name\": \"Marble\",",
                "      \"hardness\": 1.5,",
                "      \"solid\": true,",
                "      \"opaque\": true,",
                "      \"tiles\": {\"top\": 63, \"side\": 62, \"bottom\": 63}",
                "    }",
                "  ]",
                "}") + "\n";

        // Vzor ze zadani: jeden blok, ale nextId 66 (blok 65 byl smazan).
        BlockRegistry r = quiet(() -> BlockRegistry.fromJson(expected));
        check("vzorovy soubor se nacte bez vyhrad", r.size() == 1 && lastErr.isEmpty(), lastErr.trim());
        check("vzorovy soubor: nextId ze souboru", r.nextId() == 66, "" + r.nextId());
        check("toJson vrati znak po znaku vzorovy soubor", r.toJson().equals(expected),
                r.toJson().equals(expected) ? "" : "\n" + r.toJson());

        String empty = BlockRegistry.empty().toJson();
        check("prazdny registr ma prazdne pole blocks",
                empty.equals("{\n  \"format\": 1,\n  \"nextId\": 64,\n  \"blocks\": []\n}\n"), "");

        // Bloky pridane v jinem poradi nez podle id.
        BlockRegistry shuffled = BlockRegistry.empty()
                .with(new BlockDef((byte) 70, "Seventy", 1f, true, true, 1, 1, 1))
                .with(new BlockDef((byte) 64, "SixtyFour", 1f, true, true, 1, 1, 1))
                .with(new BlockDef((byte) 66, "SixtySix", 1f, true, true, 1, 1, 1));
        String json = shuffled.toJson();
        int i64 = json.indexOf("\"id\": 64"), i66 = json.indexOf("\"id\": 66"), i70 = json.indexOf("\"id\": 70");
        check("bloky v souboru serazene podle id", i64 > 0 && i64 < i66 && i66 < i70, i64 + " " + i66 + " " + i70);
        check("soubor obsahuje \"format\": 1 a nextId", json.contains("\"format\": 1,") && json.contains("\"nextId\": 71,"), "");
        check("jen \\n, zadne \\r, a konci novym radkem", !json.contains("\r") && json.endsWith("}\n"), "");

        // hardness pres Float.toString a zpet pres Double.parseDouble musi dat tentyz float
        Random random = new Random(7);
        int bad = 0;
        for (int n = 0; n < 100_000; n++) {
            float f = random.nextFloat() * BlockRegistry.MAX_HARDNESS;
            if ((float) Double.parseDouble(Float.toString(f)) != f) bad++;
        }
        check("hardness: Float.toString -> parseDouble -> float je presne (100k hodnot)", bad == 0, bad + " rozdilu");
    }

    // ==================================================================
    // tam a zpet
    // ==================================================================

    static void roundTrip(Path dir) throws IOException {
        System.out.println("\n--- tam a zpet ---");

        BlockRegistry r = sample();
        check("jmeno se pri define orizne", r.get((byte) 66).name().equals("Soft Moss"), r.get((byte) 66).name());

        BlockRegistry back = BlockRegistry.fromJson(r.toJson());
        check("toJson -> fromJson: vsechny bloky a nextId", sameRegistry(r, back), "");
        check("toJson -> fromJson: jmeno s mezerou", back.get((byte) 65).name().equals("Glass Pane"), "");
        check("toJson -> fromJson: hardness 0.1 presne", back.get((byte) 65).hardness() == 0.1f, "");
        check("toJson -> fromJson: solid/opaque", back.get((byte) 65).solid() && !back.get((byte) 65).opaque()
                && !back.get((byte) 66).solid(), "");
        check("toJson -> fromJson: dlazdice top/side/bottom",
                back.get((byte) 65).topTile() == 10 && back.get((byte) 65).sideTile() == 11
                        && back.get((byte) 65).bottomTile() == 12, "");

        Path f = dir.resolve("roundtrip").resolve("textures").resolve("blocks.json");
        boolean saved = quiet(() -> r.save(f));
        check("save zalozi adresare a projde", saved && Files.isRegularFile(f), lastErr.trim());
        check("na disku je presne toJson v UTF-8",
                Files.readString(f, StandardCharsets.UTF_8).equals(r.toJson()), "");
        check("po ulozeni nezbyl docasny soubor",
                !Files.exists(f.resolveSibling("blocks.json.tmp")), "");
        check("platny soubor se nezalohuje", !Files.exists(f.resolveSibling("blocks.json.bak")), "");

        BlockRegistry loaded = quiet(() -> BlockRegistry.load(f));
        check("save -> load: vsechny bloky a nextId", sameRegistry(r, loaded), "");
        check("save -> load bez hlasek na stderr", lastErr.isEmpty(), lastErr.trim());

        // Druhe ulozeni pres existujici platny soubor.
        BlockRegistry more = loaded.with(loaded.define("Fourth", 3f, true, true, 5, 6, 7));
        check("prepis existujiciho souboru projde", quiet(() -> more.save(f)), lastErr.trim());
        check("prepsany soubor ma novy blok", sameRegistry(more, quiet(() -> BlockRegistry.load(f))), "");
        check("ani prepis platneho souboru nezalohuje", !Files.exists(f.resolveSibling("blocks.json.bak")), "");
    }

    // ==================================================================
    // chybejici a poskozeny soubor
    // ==================================================================

    static void missingAndCorrupt(Path dir) {
        System.out.println("\n--- chybejici a poskozeny soubor ---");

        Path missing = dir.resolve("nothing").resolve("blocks.json");
        BlockRegistry r = quiet(() -> BlockRegistry.load(missing));
        check("neexistujici soubor -> prazdny registr", r == BlockRegistry.empty(), "");
        check("neexistujici soubor je mlcky (bezny stav)", lastErr.isEmpty(), lastErr.trim());

        Path f = dir.resolve("corrupt").resolve("blocks.json");

        byte[] noise = new byte[2000];
        new Random(42).nextBytes(noise);
        r = loadBytes(f, noise);
        check("nahodne bajty -> prazdny registr", r == BlockRegistry.empty(), "");
        check("nahodne bajty -> hlaska na stderr", lastErr.startsWith("Bloky "), lastErr.trim());

        String good = sample().toJson();
        String[][] cases = {
                {"prazdny soubor", ""},
                {"neni to JSON", "hello blocks"},
                {"useknuty JSON", good.substring(0, good.length() / 2)},
                {"koren je pole", "[" + good + "]"},
                {"koren je cislo", "42"},
                {"format je text", "{\"format\": \"1\", \"blocks\": []}"},
                {"chybi format", "{\"nextId\": 64, \"blocks\": []}"},
                {"chybi blocks", "{\"format\": 1, \"nextId\": 64}"},
                {"blocks je objekt", "{\"format\": 1, \"blocks\": {}}"},
                {"blocks je null", "{\"format\": 1, \"blocks\": null}"},
                {"carka navic", "{\"format\": 1, \"blocks\": [],}"},
                {"smeti za koncem", good + "x"},
                {"hluboke vnoreni", "[".repeat(100_000) + "]".repeat(100_000)},
        };

        for (String[] c : cases) {
            r = loadText(f, c[1]);
            check(c[0] + " -> prazdny registr bez vyjimky", r == BlockRegistry.empty(), "");
            // Detail bez cesty k souboru - ta je dlouha a u vsech pripadu stejna.
            check(c[0] + " -> hlaska na stderr", lastErr.startsWith("Bloky "),
                    lastErr.replace(f.toString(), "<soubor>").trim());
        }

        // fromJson hodi srozumitelnou vyjimku s pozici.
        try {
            BlockRegistry.fromJson("{\n  \"format\": 1,\n  \"blocks\": [x]\n}");
            check("fromJson na spatnem JSON hodi IllegalArgumentException", false, "nic nehodil");
        } catch (IllegalArgumentException e) {
            check("fromJson na spatnem JSON hodi IllegalArgumentException s radkem a sloupcem",
                    e.getMessage().contains("radek 3") && e.getMessage().contains("sloupec 14"), e.getMessage());
        }
        try {
            BlockRegistry.fromJson("[]");
            check("fromJson na koreni poli hodi IllegalArgumentException", false, "nic nehodil");
        } catch (IllegalArgumentException e) {
            check("fromJson na koreni poli hodi IllegalArgumentException", true, e.getMessage());
        }
    }

    // ==================================================================
    // neplatne jednotlive bloky
    // ==================================================================

    static void invalidBlocks() {
        System.out.println("\n--- neplatne jednotlive bloky ---");

        String json = file(null,
                block("64", "Marble", "1.5", 1, 2, 3),
                block("10", "Builtin Clash", "1", 1, 1, 1),        // id vestavenych bloku
                block("200", "Too High", "1", 1, 1, 1),            // mimo byte i rozsah labu
                block("64.5", "Half", "1", 1, 1, 1),               // zlomkove id
                block("\"65\"", "Text Id", "1", 1, 1, 1),          // id jako text
                block("64", "Dup Id", "1", 1, 1, 1),               // duplicitni id
                block("67", "Bad Tile", "1", 64, 1, 1),            // dlazdice mimo atlas
                block("68", "Negative", "-1", 1, 1, 1),            // zaporna tvrdost
                block("69", "", "1", 1, 1, 1),                     // prazdne jmeno
                block("70", "MARBLE", "1", 1, 1, 1),               // duplicitni jmeno (velikost pismen)
                block("71", "Frac Tile", "1", 1, 2, 1).replace("\"side\": 2,", "\"side\": 2.5,"), // zlomkova dlazdice
                "{\"id\": 72, \"name\": \"No Opaque\", \"hardness\": 1, \"solid\": true, "
                        + "\"tiles\": {\"top\": 1, \"side\": 1, \"bottom\": 1}}",
                "{\"id\": 73, \"name\": \"Text Solid\", \"hardness\": 1, \"solid\": \"yes\", \"opaque\": true, "
                        + "\"tiles\": {\"top\": 1, \"side\": 1, \"bottom\": 1}}",
                "42",
                block("66", "Granite", "2", 4, 5, 6),
                block("65", "Basalt", "0.5", 7, 8, 9));

        BlockRegistry r = quiet(() -> BlockRegistry.fromJson(json));
        List<String> lines = Arrays.stream(lastErr.split("\\R")).filter(s -> !s.isBlank()).toList();

        check("platne bloky zustaly (Marble, Basalt, Granite)", r.size() == 3
                && r.get((byte) 64).name().equals("Marble")
                && r.get((byte) 65).name().equals("Basalt")
                && r.get((byte) 66).name().equals("Granite"), r.size() + " bloku");
        check("duplicitni id: vyhral prvni zaznam", r.get((byte) 64).hardness() == 1.5f, "");
        boolean noneSkipped = true;
        for (int id = 67; id <= 73; id++) if (r.get((byte) id) != null) noneSkipped = false;
        check("neplatne bloky 67-73 se nenacetly", noneSkipped, "");
        check("kazdy preskoceny zaznam ma hlasku (13)", lines.size() == 13
                && lines.stream().allMatch(s -> s.startsWith("Bloky: ") && s.contains("preskocen")),
                lines.size() == 13 ? "" : lines.size() + "\n" + String.join("\n", lines));
        check("id 200 registr nezaplni", !r.isFull(), "nextId " + r.nextId());
        check("nextId je za nejvyssim id vcetne preskocenych (73 -> 74)", r.nextId() == 74, "" + r.nextId());
        check("novy blok nedostane id zadneho preskoceneho",
                r.define("New", 1f, true, true, 0, 0, 0).id() == 74, "");

        // Jediny blok, a ten spatny: jeho id se i tak nepreda dalsimu.
        BlockRegistry lone = quiet(() -> BlockRegistry.fromJson(file(null, block("100", "Bad", "1", 99, 1, 1))));
        check("jediny spatny blok: registr je prazdny", lone.size() == 0, "");
        check("jediny spatny blok: nextId za jeho id", lone.nextId() == 101, "" + lone.nextId());

        // Neplatne nextId ve souboru se ignoruje (s varovanim) a dopocita se z bloku.
        BlockRegistry textNext = quiet(() -> BlockRegistry.fromJson(file("\"many\"", block("64", "A", "1", 1, 1, 1))));
        check("nextId jako text: dopocita se z bloku", textNext.nextId() == 65 && lastErr.contains("nextId"), lastErr.trim());
        BlockRegistry hugeNext = quiet(() -> BlockRegistry.fromJson(file("500", block("64", "A", "1", 1, 1, 1))));
        check("nextId 500: registr je plny (radsi nez znovu pouzit id)", hugeNext.isFull()
                && hugeNext.nextId() == BlockRegistry.LAST_ID + 1, "" + hugeNext.nextId());
    }

    // ==================================================================
    // stabilita id pres vic sezeni
    // ==================================================================

    static void idStability(Path dir) throws IOException {
        System.out.println("\n--- stabilita id pres vic sezeni ---");

        Path f = dir.resolve("sessions").resolve("blocks.json");

        // sezeni 1: prazdno -> A
        BlockRegistry s1 = quiet(() -> BlockRegistry.load(f));
        check("sezeni 1 zacina prazdne", s1 == BlockRegistry.empty(), "");
        BlockDef a = s1.define("Alpha", 1f, true, true, 1, 2, 3);
        BlockRegistry s1b = s1.with(a);
        check("sezeni 1: A dostane prvni id", a.id() == BlockRegistry.FIRST_ID, "" + a.id());
        check("sezeni 1: ulozeni", quiet(() -> s1b.save(f)), lastErr.trim());

        // sezeni 2: load -> B
        BlockRegistry s2 = quiet(() -> BlockRegistry.load(f));
        check("sezeni 2: A ma stejne id i vsechno ostatni", a.equals(s2.get(a.id())), "" + s2.get(a.id()));
        BlockDef b = s2.define("Beta", 2f, false, false, 4, 5, 6);
        BlockRegistry s2b = s2.with(b);
        check("sezeni 2: B dostane dalsi id", b.id() == a.id() + 1, "" + b.id());
        check("sezeni 2: ulozeni", quiet(() -> s2b.save(f)), lastErr.trim());

        // sezeni 3: load -> C
        BlockRegistry s3 = quiet(() -> BlockRegistry.load(f));
        check("sezeni 3: A i B maji stejna id", a.equals(s3.get(a.id())) && b.equals(s3.get(b.id())), "");
        BlockDef c = s3.define("Gamma", 3f, true, false, 7, 8, 9);
        check("sezeni 3: C dostane dalsi id", c.id() == b.id() + 1, "" + c.id());
        BlockRegistry s3b = s3.with(c);
        check("sezeni 3: ulozeni", quiet(() -> s3b.save(f)), lastErr.trim());

        // Rucne smazany nejvyssi blok: nextId v souboru zustane, id se neuvolni.
        String text = Files.readString(f, StandardCharsets.UTF_8);
        int start = text.lastIndexOf(",\n    {\n      \"id\": " + c.id());
        int end = text.indexOf("\n  ]", start);
        String edited = start < 0 || end < 0 ? text : text.substring(0, start) + text.substring(end);
        check("z JSON slo smazat blok C", !edited.contains("Gamma") && edited.contains("\"nextId\": " + (c.id() + 1)), "");

        BlockRegistry s4 = loadText(f, edited);
        check("po smazani C: A a B zustaly", s4 != null && s4.size() == 2 && a.equals(s4.get(a.id())), "");
        check("po smazani C se nacte bez vyhrad", lastErr.isEmpty(), lastErr.trim());
        BlockDef d = s4.define("Delta", 1f, true, true, 0, 0, 0);
        check("novy blok NEDOSTANE id smazaneho C", d.id() == c.id() + 1, d.id() + " (C mel " + c.id() + ")");

        // Soubor bez nextId: nextId = nejvyssi id + 1.
        String noNext = edited.replace("  \"nextId\": " + (c.id() + 1) + ",\n", "");
        BlockRegistry s5 = loadText(f, noNext);
        check("soubor bez nextId se nacte bez vyhrad", s5 != null && s5.size() == 2 && lastErr.isEmpty(), lastErr.trim());
        check("soubor bez nextId: nextId = nejvyssi id + 1", s5.nextId() == b.id() + 1, "" + s5.nextId());
    }

    // ==================================================================
    // novejsi format a escape sekvence
    // ==================================================================

    static void versionAndEscapes() {
        System.out.println("\n--- novejsi format a escape sekvence ---");

        // \\u0041 je v Jave dvojite lomitko, aby escape dostal az JSON parser, ne javac.
        String json = "{\"format\": 2, \"future\": {\"x\": [1, 2.5e3, null, false]}, \"nextId\": 65,"
                + " \"blocks\": [{\"id\": 64, \"name\": \"\\u0041pple\","
                + " \"note\": \"line1\\nline2 \\\"q\\\" \\\\ \\/ \\t \\b \\f \\r\","
                + " \"hardness\": 2, \"solid\": true, \"opaque\": false,"
                + " \"tiles\": {\"top\": 1, \"side\": 2, \"bottom\": 3, \"front\": 4}, \"glow\": 7}]}";

        BlockRegistry r = quiet(() -> BlockRegistry.fromJson(json));
        check("format 2 se nacte", r.size() == 1, "");
        check("format 2 -> varovani na stderr", lastErr.contains("format 2"), lastErr.trim());
        check("\\u0041 v nazvu je A", r.get((byte) 64).name().equals("Apple"), r.get((byte) 64).name());
        check("cele cislo 2 jako hardness", r.get((byte) 64).hardness() == 2f, "");
        check("nezname pole se ignoruji", r.get((byte) 64).topTile() == 1 && r.get((byte) 64).sideTile() == 2
                && r.get((byte) 64).bottomTile() == 3 && !r.get((byte) 64).opaque(), "");

        Object note = ((Map<?, ?>) ((List<?>) ((Map<?, ?>) Json.parse(json)).get("blocks")).get(0)).get("note");
        check("escape sekvence v jinem poli", "line1\nline2 \"q\" \\ / \t \b \f \r".equals(note), "");
    }

    // ==================================================================
    // JSON parser a zapis retezce
    // ==================================================================

    static void jsonParser() {
        System.out.println("\n--- JSON parser ---");

        check("cisla jako Double", Double.valueOf(-125.0).equals(Json.parse("-12.5e1"))
                && Double.valueOf(0.0).equals(Json.parse(" 0 ")), "");
        check("true/false/null", Boolean.TRUE.equals(Json.parse("true")) && Boolean.FALSE.equals(Json.parse("false"))
                && Json.parse("null") == null, "");
        check("prazdny objekt a pole", Json.parse("{}") instanceof Map<?, ?> m && m.isEmpty()
                && Json.parse("[ ]") instanceof List<?> l && l.isEmpty(), "");
        check("poradi klicu jako v textu", Json.parse("{\"b\":1,\"a\":2,\"c\":3}") instanceof Map<?, ?> m
                && String.join(",", m.keySet().stream().map(Object::toString).toList()).equals("b,a,c"), "");
        check("\\u00e9 a nahradni par", Json.parse("\"\\u00e9\\ud83d\\ude00\"").equals(
                String.valueOf((char) 0xE9) + (char) 0xD83D + (char) 0xDE00), "");
        check("BOM na zacatku se preskoci", Double.valueOf(1).equals(Json.parse((char) 0xFEFF + "1")), "");
        check("1e999 je nekonecno, ne pad", Double.valueOf(Double.POSITIVE_INFINITY).equals(Json.parse("1e999")), "");

        String[] invalid = {"", "01", "1.", "-", "+1", ".5", "1e", "[1,]", "{\"a\":1,}", "{a:1}", "'x'",
                "tru", "nul", "\"\\x\"", "\"\\u12G4\"", "\"\\u12\"", "\"abc", "\"a" + (char) 1 + "\"",
                "[1 2]", "{\"a\" 1}", "1 2", "[".repeat(65) + "]".repeat(65)};
        int wrong = 0;
        StringBuilder detail = new StringBuilder();
        for (String s : invalid) {
            try {
                Json.parse(s);
                wrong++;
                detail.append(" prijal:").append(s);
            } catch (IllegalArgumentException e) {
                // ok
            } catch (Throwable t) {
                wrong++;
                detail.append(" jina vyjimka:").append(t);
            }
        }
        check("spatny JSON vzdy IllegalArgumentException (" + invalid.length + " pripadu)", wrong == 0, detail.toString());
        check("vnoreni 64 je jeste v poradku", Json.parse("[".repeat(64) + "]".repeat(64)) instanceof List<?>, "");

        try {
            Json.parse("{\n  \"a\": 1,\n  \"b\": x\n}");
            check("chyba ma radek a sloupec", false, "nic nehodil");
        } catch (IllegalArgumentException e) {
            check("chyba ma radek a sloupec", e.getMessage().contains("radek 3, sloupec 8"), e.getMessage());
        }

        // quote: vsechny ridici znaky, uvozovky, lomitko a ne-ASCII tam a zpet
        StringBuilder all = new StringBuilder("\"\\/ ");
        for (char ch = 0; ch < 32; ch++) all.append(ch);
        all.append((char) 0x159).append((char) 0x7F);
        String quoted = Json.quote(all.toString());
        boolean rawControl = quoted.chars().anyMatch(ch -> ch < 32);
        check("quote nenecha v textu zadny ridici znak", !rawControl, "");
        check("quote -> parse vrati tentyz retezec", all.toString().equals(Json.parse(quoted)), "");
        check("quote pouziva kratke escape", Json.quote("a\"b\\c\nd\te").equals("\"a\\\"b\\\\c\\nd\\te\""),
                Json.quote("a\"b\\c\nd\te"));
    }

    // ==================================================================
    // plny registr
    // ==================================================================

    static void full(Path dir) {
        System.out.println("\n--- plny registr ---");

        BlockRegistry r = BlockRegistry.empty();
        while (!r.isFull()) r = r.with(r.define("Block " + r.nextId(), 1f, true, true, 0, 0, 0));

        check("plny registr ma 64 bloku az do 127", r.size() == 64
                && r.get((byte) BlockRegistry.LAST_ID) != null, "" + r.size());
        check("plny registr: isFull a nextId 128", r.isFull() && r.nextId() == 128, "" + r.nextId());

        BlockRegistry fullReg = r;
        try {
            fullReg.define("Overflow", 1f, true, true, 0, 0, 0);
            check("define na plnem registru hodi IllegalStateException", false, "nic nehodil");
        } catch (IllegalStateException e) {
            check("define na plnem registru hodi IllegalStateException", true, "");
        }

        try {
            BlockRegistry.empty().with(new BlockDef((byte) 10, "Low", 1f, true, true, 0, 0, 0));
            check("with s id pod 64 hodi IllegalArgumentException", false, "nic nehodil");
        } catch (IllegalArgumentException e) {
            check("with s id pod 64 hodi IllegalArgumentException", true, "");
        }

        Path f = dir.resolve("full").resolve("blocks.json");
        check("plny registr se ulozi", quiet(() -> fullReg.save(f)), lastErr.trim());
        BlockRegistry loaded = quiet(() -> BlockRegistry.load(f));
        check("plny registr po nacteni: vsech 64 bloku a porad plny",
                sameRegistry(fullReg, loaded) && loaded.isFull() && lastErr.isEmpty(), lastErr.trim());
    }

    // ==================================================================
    // zaloha poskozeneho souboru
    // ==================================================================

    static void backup(Path dir) throws IOException {
        System.out.println("\n--- zaloha poskozeneho souboru ---");

        // Useknuty JSON - napr. rucni uprava, ktera se nepovedla.
        Path f = dir.resolve("backup1").resolve("blocks.json");
        Path bak = f.resolveSibling("blocks.json.bak");
        byte[] broken = "{\n  \"format\": 1,\n  \"blocks\": [\n    {\"id\": 64, \"name\": \"Precious\""
                .getBytes(StandardCharsets.UTF_8);

        BlockRegistry r = loadBytes(f, broken);
        check("poskozeny soubor -> prazdny registr", r == BlockRegistry.empty(), "");

        BlockRegistry fresh = r.with(r.define("Fresh", 1f, true, true, 0, 0, 0));
        check("ulozeni pres poskozeny soubor projde", quiet(() -> fresh.save(f)), lastErr.trim());
        check("poskozeny soubor je zkopirovany do .bak", Files.isRegularFile(bak), "");
        check("obsah .bak je bajt po bajtu puvodni", Files.isRegularFile(bak)
                && Arrays.equals(Files.readAllBytes(bak), broken), "");
        check("o zaloze je hlaska na stderr", lastErr.contains(".bak"), lastErr.trim());
        check("novy soubor jde nacist", sameRegistry(fresh, quiet(() -> BlockRegistry.load(f))), "");

        // Dalsi ulozeni uz je pres platny soubor: .bak zustane, jak byl.
        BlockRegistry more = fresh.with(fresh.define("More", 1f, true, true, 0, 0, 0));
        check("dalsi ulozeni projde", quiet(() -> more.save(f)), "");
        check("platny soubor .bak neprepise", Arrays.equals(Files.readAllBytes(bak), broken), "");

        // Nahodne bajty (ani UTF-8).
        Path g = dir.resolve("backup2").resolve("blocks.json");
        byte[] noise = new byte[500];
        new Random(3).nextBytes(noise);
        loadBytes(g, noise);
        check("ulozeni pres nahodne bajty projde", quiet(() -> fresh.save(g)), lastErr.trim());
        check("nahodne bajty jsou v .bak beze zmeny",
                Arrays.equals(Files.readAllBytes(g.resolveSibling("blocks.json.bak")), noise), "");

        // Platny JSON, ale s preskocenym blokem: registr v pameti ho nema,
        // takze prepis by ho smazal - taky se zalohuje.
        Path h = dir.resolve("backup3").resolve("blocks.json");
        String partly = file("66", block("64", "Good", "1", 1, 1, 1), block("65", "Typo", "1", 64, 1, 1));
        BlockRegistry p = loadText(h, partly);
        check("soubor s jednim spatnym blokem se nacte zcasti", p != null && p.size() == 1, "");
        check("ulozeni pres zcasti nacteny soubor projde", quiet(() -> p.save(h)), lastErr.trim());
        check("zcasti nacteny soubor je v .bak",
                Files.readString(h.resolveSibling("blocks.json.bak"), StandardCharsets.UTF_8).equals(partly), "");
    }

    // ==================================================================
    // aktivni registr
    // ==================================================================

    static void activeRegistry() {
        System.out.println("\n--- aktivni registr ---");

        BlockRegistry r = sample();
        BlockRegistry.activate(r);
        check("activate: active() je ten registr", BlockRegistry.active() == r, "");
        check("lookup najde blok z labu", r.get((byte) 64).equals(BlockRegistry.lookup((byte) 64)), "");
        check("lookup: vestavene, nepouzite a zaporne id -> null", BlockRegistry.lookup((byte) 1) == null
                && BlockRegistry.lookup((byte) 100) == null && BlockRegistry.lookup((byte) -1) == null, "");

        BlockRegistry.activate(null);
        check("activate(null) -> prazdny registr", BlockRegistry.active() == BlockRegistry.empty()
                && BlockRegistry.lookup((byte) 64) == null, "");
    }
}
