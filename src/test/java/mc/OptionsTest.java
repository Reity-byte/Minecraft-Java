package mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Overuje nastaveni bez GL: meze a vztah render <= simulation, prevod na
 * cisla enginu, options.json tam a zpet, chybejici a poskozeny soubor,
 * zalohu .bak, obrazovku Options (klikani a tazeni posuvniku), vyber
 * monitoru pro fullscreen, strop FPS, jas a GUI meritko.
 */
public class OptionsTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        defaultsAndLimits();
        file();
        screen();
        widgets();
        window();
        limiter();
        brightness();
        guiScale();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ==================================================================

    static void defaultsAndLimits() {
        Options o = Options.defaults();
        World w = new World();
        check("vychozi nastaveni = dnesni hra: dohled 96 bloku, nacitani 8/10 chunku, FOV 70",
                o.renderDistanceBlocks() == w.renderDistance && o.loadRadius() == w.loadRadius
                        && o.unloadRadius() == w.unloadRadius && o.fov() == 70, "");
        w.shutdown();
        check("vychozi citlivost = dnesnich 0,12 stupne na pixel",
                o.mouseDegreesPerPixel() == Camera.DEFAULT_SENSITIVITY, "");
        check("vychozi: okno, vsync, bez stropu FPS, GUI auto, jas beze zmeny",
                !o.fullscreen() && o.vsync() && o.maxFps() == Options.UNLIMITED_FPS
                        && o.guiScale() == Options.AUTO_GUI_SCALE && o.brightness() == 0f && !o.invertMouse(), "");

        // ---------- meze ----------
        o.setRenderDistance(1000);
        check("render distance se orizne nahoru na 16", o.renderDistance() == Options.MAX_RENDER, "" + o.renderDistance());
        o.setSimulationDistance(-5);
        check("simulation distance se orizne dolu na 2", o.simulationDistance() == Options.MIN_SIMULATION, "");
        o.setFov(5);
        int low = o.fov();
        o.setFov(500);
        check("FOV se orizne na 30 az 110", low == 30 && o.fov() == 110, low + " / " + o.fov());
        o.setSensitivity(99f);
        float high = o.sensitivity();
        o.setSensitivity(-1f);
        check("citlivost se orizne na 10 az 200 %", high == 2f && o.sensitivity() == 0.1f, high + " / " + o.sensitivity());
        o.setSensitivity(Float.NaN);
        check("citlivost NaN nerozbije hodnotu", o.sensitivity() == 0.1f, "" + o.sensitivity());
        o.setBrightness(3f);
        check("jas se orizne na 0 az 1", o.brightness() == 1f, "");
        o.setGuiScale(9);
        check("GUI meritko nejvys 4", o.guiScale() == 4, "");
        o.cycleGuiScale();
        check("GUI meritko dokola: po 4 prijde Auto", o.guiScale() == Options.AUTO_GUI_SCALE, "");

        o.setMaxFps(57);
        int snapped = o.maxFps();
        o.setMaxFps(10);
        int floor = o.maxFps();
        o.setMaxFps(300);
        check("strop FPS po desitkach, nejmin 30, nad 250 bez stropu",
                snapped == 60 && floor == 30 && o.maxFps() == Options.UNLIMITED_FPS, snapped + " / " + floor);

        // ---------- render <= simulation ----------
        Options r = Options.defaults();
        r.setSimulationDistance(8);
        r.setRenderDistance(12);
        check("render nad simulation potahne simulation s sebou",
                r.renderDistance() == 12 && r.simulationDistance() == 12, r.renderDistance() + "/" + r.simulationDistance());
        r.setSimulationDistance(4);
        check("simulation pod render stahne render s sebou",
                r.simulationDistance() == 4 && r.renderDistance() == 4, "");
        r.setRenderDistance(2);
        r.setSimulationDistance(10);
        check("mensi render a vetsi simulation se nemeni",
                r.renderDistance() == 2 && r.simulationDistance() == 10, "");

        boolean always = true;
        Options any = Options.defaults();
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) {
            if (rnd.nextBoolean()) any.setRenderDistance(rnd.nextInt(40) - 10);
            else any.setSimulationDistance(rnd.nextInt(40) - 10);
            always &= any.renderDistance() <= any.simulationDistance()
                    && any.renderDistance() >= Options.MIN_RENDER && any.simulationDistance() <= Options.MAX_SIMULATION;
        }
        check("po 2000 nahodnych zmenach plati render <= simulation a meze", always, "");

        // ---------- prevod na engine ----------
        boolean meshable = true;
        for (int s = Options.MIN_SIMULATION; s <= Options.MAX_SIMULATION; s++) {
            Options e = Options.defaults();
            e.setSimulationDistance(s);
            e.setRenderDistance(s);
            // Sekce se mesuje jen s nactenymi sousedy: nejvzdalenejsi viditelny
            // sloupec (render + 1 od hracova chunku) musi mit nactene sousedy.
            meshable &= e.renderDistanceBlocks() < (e.loadRadius() - 1) * 16f
                    && e.unloadRadius() > e.loadRadius();
        }
        check("pri render = simulation je vsechno viditelne nacteni i se sousedy (dohled < (loadRadius-1)*16)",
                meshable, "");
    }

    // ==================================================================

    static void file() throws IOException {
        Path dir = Files.createTempDirectory("mc-options");
        Path file = dir.resolve("options.json");

        try {
            check("chybejici soubor -> vychozi hodnoty",
                    Options.load(file).toJson().equals(Options.defaults().toJson()), "");

            Options o = Options.defaults();
            o.setFullscreen(true);
            o.setVsync(false);
            o.setMaxFps(144);
            o.setSimulationDistance(12);
            o.setRenderDistance(9);
            o.setFov(95);
            o.setBrightness(0.37f);
            o.setGuiScale(3);
            o.setSensitivity(1.35f);
            o.setInvertMouse(true);

            check("ulozeni se povede", o.save(file) && Files.isRegularFile(file), "");
            Options back = Options.load(file);
            check("tam a zpet: vsechny hodnoty stejne",
                    back.fullscreen() && !back.vsync() && back.maxFps() == 140 && back.simulationDistance() == 12
                            && back.renderDistance() == 9 && back.fov() == 95 && back.brightness() == 0.37f
                            && back.guiScale() == 3 && back.sensitivity() == 1.35f && back.invertMouse(),
                    back.toJson());
            check("zapsany text je presne toJson()", Files.readString(file).equals(o.toJson()), "");
            check("po ulozeni neni ani .tmp, ani .bak",
                    !Files.exists(dir.resolve("options.json.tmp")) && !Files.exists(SafeFiles.backupOf(file)), "");

            // ---------- poskozene soubory ----------
            String[] broken = {"", "{", "tohle neni json", "[1, 2, 3]", "{\"fov\": }", "\0\1\2"};
            boolean safe = true;
            for (String text : broken) {
                Files.writeString(file, text);
                safe &= Options.load(file).toJson().equals(Options.defaults().toJson());
            }
            check("poskozeny soubor (6 druhu) -> vychozi hodnoty, bez vyjimky", safe, "");

            Files.write(file, new byte[]{(byte) 0xC3, (byte) 0x28, 0x7B});
            check("soubor, ktery neni UTF-8 -> vychozi hodnoty", Options.load(file).fov() == Options.DEFAULT_FOV, "");

            // Jedna spatna hodnota nezahodi ostatni.
            Files.writeString(file, "{\"format\": 1, \"fov\": \"hodne\", \"renderDistance\": 5, "
                    + "\"simulationDistance\": 7, \"vsync\": 3, \"guiScale\": 2}");
            Options partial = Options.load(file);
            check("spatny typ jedne hodnoty -> jen ta vychozi, ostatni zustanou",
                    partial.fov() == Options.DEFAULT_FOV && partial.vsync() && partial.renderDistance() == 5
                            && partial.simulationDistance() == 7 && partial.guiScale() == 2, partial.toJson());

            // Mimo meze a render > simulation v souboru.
            Files.writeString(file, "{\"format\": 1, \"renderDistance\": 40, \"simulationDistance\": 9, "
                    + "\"fov\": 1, \"maxFps\": 55, \"sensitivity\": -3}");
            Options clamped = Options.load(file);
            check("hodnoty ze souboru se oriznou a render se stahne na simulation",
                    clamped.renderDistance() == 9 && clamped.simulationDistance() == 9 && clamped.fov() == 30
                            && clamped.maxFps() == 60 && clamped.sensitivity() == 0.1f, clamped.toJson());

            Files.writeString(file, "{\"format\": 2, \"fov\": 80, \"novinka\": true}");
            check("novejsi format se nacte, nezname pole se ignoruje", Options.load(file).fov() == 80, "");

            List<String> problems = new ArrayList<>();
            Options.fromJson("{\"format\": 1}", problems);
            check("soubor jen s formatem (starsi verze) = vychozi hodnoty bez vyhrad", problems.isEmpty(), "" + problems);

            // ---------- zaloha ----------
            Files.writeString(file, "{ poskozene");
            Options fresh = Options.defaults();
            fresh.setFov(100);
            check("ulozeni pres poskozeny soubor se povede", fresh.save(file), "");
            check("poskozeny soubor je zalohovany v options.json.bak",
                    Files.readString(SafeFiles.backupOf(file)).equals("{ poskozene"), "");
            check("a novy soubor jde nacist", Options.load(file).fov() == 100, "");

            Files.delete(SafeFiles.backupOf(file));
            fresh.save(file);
            check("citelny soubor se pri prepsani nezalohuje", !Files.exists(SafeFiles.backupOf(file)), "");

            check("kopie nastaveni je nezavisla",
                    fresh.copy().toJson().equals(fresh.toJson()) && fresh.copy() != fresh, "");
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList())
                    Files.deleteIfExists(p);
            }
        }
    }

    // ==================================================================

    /** Stred obdelniku v souradnicich mysi. */
    static double[] centre(ScreenLayout l, ScreenLayout.Rect r) {
        return new double[]{l.left() + (r.x() + r.w() / 2.0) * l.scale(), l.top() + (r.y() + r.h() / 2.0) * l.scale()};
    }

    /** Bod na drazi posuvniku v podilu t (0 az 1). */
    static double[] onTrack(ScreenLayout l, ScreenLayout.Rect track, double t) {
        return new double[]{l.left() + (track.x() + t * track.w()) * l.scale(),
                l.top() + (track.y() + track.h() / 2.0) * l.scale()};
    }

    static void screen() {
        int w = 1024, h = 768;
        Options o = Options.defaults();
        OptionsScreen screen = new OptionsScreen(o, null);
        ScreenLayout l = OptionsScreen.layout(w, h);

        check("obrazovka nastaveni se vejde i na 320 x 240 GUI pixelu",
                OptionsScreen.WIDTH <= 320 && OptionsScreen.HEIGHT <= 240, "");

        // Zadne dve polozky se neprekryvaji a vsechny jsou v panelu.
        List<ScreenLayout.Rect> rects = new ArrayList<>();
        for (OptionsScreen.Item item : OptionsScreen.Item.values()) rects.add(item.rect());
        rects.add(OptionsScreen.DONE);
        rects.add(OptionsScreen.HELP);
        boolean separate = true;
        for (int i = 0; i < rects.size(); i++) {
            ScreenLayout.Rect a = rects.get(i);
            separate &= a.x() >= 0 && a.y() >= 0 && a.right() <= OptionsScreen.WIDTH && a.bottom() <= OptionsScreen.HEIGHT;
            for (int j = i + 1; j < rects.size(); j++) {
                ScreenLayout.Rect b = rects.get(j);
                separate &= !(a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom());
            }
        }
        check("polozky se neprekryvaji a jsou uvnitr panelu", separate, "");

        boolean hits = true;
        for (OptionsScreen.Item item : OptionsScreen.Item.values()) {
            double[] c = centre(l, item.rect());
            hits &= OptionsScreen.itemAt(l, c[0], c[1]) == item;
        }
        check("kazda polozka jde trefit", hits, "");

        // Prepinace.
        double[] fs = centre(l, OptionsScreen.Item.FULLSCREEN.rect());
        screen.press(fs[0], fs[1], w, h);
        check("klik na Fullscreen ho zapne a nahlasi zmenu", o.fullscreen() && screen.takeChanged(), "");
        check("zmena se hlasi jen jednou", !screen.takeChanged(), "");

        // Tazeni posuvniku render distance: zmena hned, i kdyz mys sjede mimo.
        ScreenLayout.Rect track = OptionsScreen.Item.RENDER.track();
        double[] start = onTrack(l, track, ScreenLayout.stepPosition(6, 2, 16));
        screen.press(start[0], start[1], w, h);
        double[] far = onTrack(l, track, 1.0);
        screen.drag(far[0] + 300, far[1] + 200, w, h);
        check("tazeni render distance na konec (i pres okraj) = 16 a potahne simulation",
                o.renderDistance() == 16 && o.simulationDistance() == 16 && screen.takeChanged(),
                o.renderDistance() + "/" + o.simulationDistance());
        // UI-6: dalsi pohyb mysi na tentyz krok zmenu nehlasi (driv kazda
        // udalost kurzoru spustila applyOptions()).
        screen.drag(far[0] + 310, far[1] + 200, w, h);
        check("tazeni na tentyz krok zmenu nehlasi", !screen.takeChanged(), "");
        screen.release();
        screen.drag(start[0], start[1], w, h);
        check("po pusteni tazeni nic nemeni", o.renderDistance() == 16, "");

        // Simulation dolu stahne render.
        ScreenLayout.Rect sim = OptionsScreen.Item.SIMULATION.track();
        double[] s4 = onTrack(l, sim, ScreenLayout.stepPosition(4, 2, 16));
        screen.press(s4[0], s4[1], w, h);
        screen.release();
        check("posuvnik simulation na 4 stahne render na 4",
                o.simulationDistance() == 4 && o.renderDistance() == 4, o.renderDistance() + "/" + o.simulationDistance());

        // Kazdy posuvnik: znacka lezi tam, kam se kliklo (tam a zpet).
        boolean roundTrip = true;
        String broken = "";
        for (OptionsScreen.Item item : OptionsScreen.Item.values()) {
            if (!item.slider) continue;
            for (double t : new double[]{0.0, 0.33, 0.5, 1.0}) {
                double[] p = onTrack(l, item.track(), t);
                screen.press(p[0], p[1], w, h);
                screen.release();
                float back = screen.position(item);
                double[] q = onTrack(l, item.track(), back);
                screen.press(q[0], q[1], w, h);
                screen.release();
                if (Math.abs(screen.position(item) - back) > 1e-4) {
                    roundTrip = false;
                    broken += item + "@" + t + " ";
                }
            }
        }
        check("u kazdeho posuvniku klik na znacku hodnotu nezmeni", roundTrip, broken);

        double[] fps = onTrack(l, OptionsScreen.Item.MAX_FPS.track(), 1.0);
        screen.press(fps[0], fps[1], w, h);
        screen.release();
        check("konec posuvniku Max Framerate = Unlimited",
                o.maxFps() == Options.UNLIMITED_FPS && screen.caption(OptionsScreen.Item.MAX_FPS).endsWith("Unlimited"), "");

        double[] done = centre(l, OptionsScreen.DONE);
        check("Done a Esc zavrou obrazovku",
                screen.press(done[0], done[1], w, h) && screen.key(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE), "");

        check("popisky nesou aktualni hodnotu",
                screen.caption(OptionsScreen.Item.RENDER).equals("Render Distance: " + o.renderDistance())
                        && screen.caption(OptionsScreen.Item.FULLSCREEN).equals("Fullscreen: ON")
                        && screen.caption(OptionsScreen.Item.GUI_SCALE).equals("GUI Scale: Auto"),
                screen.caption(OptionsScreen.Item.RENDER));
    }

    // ==================================================================

    /** Spolecne kousky obrazovek: textove pole a mapovani posuvniku. */
    static void widgets() {
        TextField field = new TextField(8);
        check("bez fokusu se nic nenapise", !field.type('a') && field.text().isEmpty(), "");
        field.setFocused(true);
        for (char c : "Hello".toCharArray()) field.type(c);
        check("psani znaku", field.text().equals("Hello"), field.text());
        check("diakritika a rizeni znaky se zahodi (font je jen ASCII)",
                !field.type(0x10F) && !field.type(10) && field.text().equals("Hello"), field.text());
        for (char c : "World!!".toCharArray()) field.type(c);
        check("delka je omezena", field.text().equals("HelloWor") && field.text().length() == 8, field.text());
        field.backspace();
        check("backspace ubere znak", field.text().equals("HelloWo"), field.text());
        field.setText("");
        field.insert("seed" + (char) 10 + (char) 9 + "text s diakritikou: zlutoucky");
        check("vlozeni ze schranky: konce radku na mezeru, orez na delku, jen ASCII",
                field.text().equals("seed  te"), field.text());

        // Driv test vkladal jen ASCII a usekl se driv, nez by k diakritice dosel,
        // takze filtr v insert() nic nehlidalo. Znaky se pisou jako (char) cisla,
        // at je soubor cisty ASCII.
        field.setText("");
        field.insert("a" + (char) 0x10D + "b" + (char) 0x159 + (char) 0x1F600 + "c");
        check("vlozeni s diakritikou: ne-ASCII znaky se zahodi, zbytek zustane",
                field.text().equals("abc"), field.text());
        field.clear();
        field.setFocused(false);
        field.backspace();
        check("prazdne pole backspace nerozbije", field.text().isEmpty(), "");

        // Posuvnik: hodnota a poloha jsou navzajem opakem.
        boolean roundTrip = true;
        for (int v = 2; v <= 16; v++) {
            float t = ScreenLayout.stepPosition(v, 2, 16);
            roundTrip &= ScreenLayout.steppedValue(t, 2, 16) == v;
        }
        check("kazdy krok posuvniku ma svou vysec (hodnota -> poloha -> hodnota)", roundTrip, "");
        check("posuvnik se orizne na meze",
                ScreenLayout.steppedValue(-5f, 2, 16) == 2 && ScreenLayout.steppedValue(9f, 2, 16) == 16
                        && ScreenLayout.stepPosition(100, 2, 16) == 1f, "");

        ScreenLayout l = new ScreenLayout(320, 200, 1024, 768);
        check("panel je vycentrovany, zarovnany na GUI pixel a vejde se",
                l.left() >= 0 && l.top() >= 0 && l.left() % l.scale() == 0 && l.top() % l.scale() == 0
                        && l.left() + 320 * l.scale() <= 1024 && l.top() + 200 * l.scale() <= 768, "");
        ScreenLayout.Rect r = new ScreenLayout.Rect(10, 20, 30, 40);
        check("hit-test a prevod souradnic sedi",
                l.hit(r, l.left() + 10 * l.scale() + 1, l.top() + 20 * l.scale() + 1)
                        && !l.hit(r, l.left() + 9 * l.scale(), l.top() + 20 * l.scale())
                        && l.screenX(r) == l.left() + 10 * l.scale()
                        && l.screenBottom(r, 768) == 768 - (l.top() + 60 * l.scale()), "");
    }

    static void window() {
        int[] left = {0, 0, 1920, 1080}, right = {1920, 0, 2560, 1440};
        check("okno na levem monitoru -> levy",
                WindowMode.bestMonitor(new int[]{100, 100, 800, 600}, new int[][]{left, right}) == 0, "");
        check("okno vetsinou na pravem monitoru -> pravy",
                WindowMode.bestMonitor(new int[]{1800, 100, 800, 600}, new int[][]{left, right}) == 1, "");
        check("okno mimo vsechny monitory -> zadny (pouzije se primarni)",
                WindowMode.bestMonitor(new int[]{-5000, 0, 800, 600}, new int[][]{left, right}) == -1, "");
        check("prekryv obdelniku", WindowMode.overlap(new int[]{0, 0, 10, 10}, new int[]{5, 5, 10, 10}) == 25
                && WindowMode.overlap(new int[]{0, 0, 10, 10}, new int[]{10, 0, 5, 5}) == 0, "");

        // ---------- ⚠️ kolikrat se OPRAVDU prepne monitor ----------
        //
        // glfwSetWindowMonitor prestavi framebuffer a synchronne spusti
        // callbacky velikosti, takze na poctu skutecnych prepnuti zalezi.
        // init() vola apply() dvakrat s touz hodnotou (primo a pak jeste
        // z applyOptions na konci) - podruhe se prepnout NESMI.
        check("needsSwitch je pravda jen pri skutecne zmene",
                !WindowMode.needsSwitch(false, false) && !WindowMode.needsSwitch(true, true)
                        && WindowMode.needsSwitch(false, true) && WindowMode.needsSwitch(true, false), "");

        check("start v okne: dve apply(false) neprepnou vubec",
                switches(false, false, false) == 0, "" + switches(false, false, false));
        check("⚠️ start ve fullscreenu: dve apply(true) prepnou presne jednou",
                switches(false, true, true) == 1, "" + switches(false, true, true));

        // F11 za behu musi dal prepinat - guard nesmi zamknout stav.
        check("F11 tam a zpet prepne pokazde",
                switches(false, true, true, false, true) == 3,
                "" + switches(false, true, true, false, true));
        check("F11 z okna do fullscreenu a zpet jsou dve prepnuti",
                switches(false, true, false) == 2, "" + switches(false, true, false));
        check("opakovane apply touz hodnotou uz nikdy neprepne",
                switches(false, true, true, true, true) == 1, "" + switches(false, true, true, true, true));
    }

    /**
     * Kolikrat by se opravdu prepnul monitor, kdyby se na okno v pocatecnim
     * stavu start postupne volalo apply() s danymi hodnotami. Replika smycky
     * z WindowMode.apply() bez GLFW.
     */
    static int switches(boolean start, boolean... wanted) {
        boolean current = start;
        int count = 0;

        for (boolean want : wanted) {
            if (WindowMode.needsSwitch(current, want)) {
                count++;
                current = want;
            }
        }

        return count;
    }

    static void limiter() {
        long period = 1_000_000_000L / 120;
        long first = FrameLimiter.nextDeadline(0, 5_000, 120);
        long second = FrameLimiter.nextDeadline(first, first + 1_000_000, 120);
        check("strop FPS: termin se posouva o celou periodu (ne o periodu od konce framu)",
                first == 5_000 + period && second == first + period, "");
        long late = FrameLimiter.nextDeadline(first, first + 10 * period, 120);
        check("po velkem zdrzeni se termin srovna na ted (zadne dohanenni)",
                late == first + 10 * period + period, "");
    }

    static void brightness() {
        boolean identity = true, monotonic = true, full = true;
        float previous = -1f;
        for (int i = 0; i <= 100; i++) {
            float l = i / 100f;
            identity &= Options.brighten(l, 0f) == l;
            float b = Options.brighten(l, 1f);
            monotonic &= b >= previous - 1e-6f && b >= l - 1e-6f;
            previous = b;
        }
        full = Math.abs(Options.brighten(1f, 1f) - 1f) < 1e-6f;
        check("jas 0 nemeni svetlo (dnesni vzhled)", identity, "");
        check("jas je rostouci funkce svetla a nic neztmavi; plne svetlo zustane plne", monotonic && full, "");
        check("jas zveda tmu, ale osvetlene steny skoro nechava (stineni sten zustane)",
                Options.brighten(0.05f, 1f) > 0.25f && Options.brighten(0.6f, 1f) - 0.6f < 0.01f
                        && Options.brighten(0.8f, 1f) - 0.8f < 0.001f,
                Options.brighten(0.05f, 1f) + " / " + Options.brighten(0.6f, 1f));
    }

    static void guiScale() {
        try {
            Gui.setPreferredScale(0);
            int auto = Gui.scale(1920, 1080);
            Gui.setPreferredScale(2);
            int two = Gui.scale(1920, 1080);
            Gui.setPreferredScale(4);
            int small = Gui.scale(800, 600);
            check("GUI Auto = nejvetsi, co se vejde; zvolene 2 = 2",
                    auto == 4 && two == 2, auto + " / " + two);
            check("zvolene meritko nikdy nepreroste, co se vejde (4 na 800x600 -> 2)", small == 2, "" + small);
        } finally {
            Gui.setPreferredScale(0);
        }
    }
}
