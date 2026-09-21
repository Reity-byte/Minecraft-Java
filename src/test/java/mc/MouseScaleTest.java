package mc;

/**
 * Overuje prepocet polohy kurzoru z bodu OKNA na pixely FRAMEBUFFERU.
 *
 * ⚠️ Na Retine je framebuffer dvakrat vetsi nez okno: UI se kresli a
 * hit-testuje v pixelech framebufferu, ale GLFW vraci mys v bodech okna.
 * Bez prepoctu trefi klik misto dvakrat bliz ke stredu - tlacitka se kresli
 * spravne, ale reaguji "vedle". Test proto nekontroluje jen nasobeni, ale
 * projde vsechny klikaci obrazovky: pro kazdy prvek vezme misto, kde je
 * NAKRESLENY, prevede ho na bod okna (jak by ho poslalo GLFW na Retine)
 * a overi, ze po prepoctu trefi zase ten samy prvek.
 */
public class MouseScaleTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    /** Okno v bodech; framebuffer je nasobek podle meritka displeje. */
    static final int WINDOW_W = 1024, WINDOW_H = 768;

    /** 1 = bezny displej, 2 = Retina, 1,5 = napr. 150 % na Windows s DPI awareness. */
    static final double[] SCALES = {1.0, 1.5, 2.0, 3.0};

    public static void main(String[] args) {
        factor();
        conversion();
        menuClicks();
        screenClicks();
        labClicks();
        containerClicks();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    static MouseScale scaleFor(double scale) {
        MouseScale mouse = new MouseScale();
        mouse.update(WINDOW_W, WINDOW_H, (int) (WINDOW_W * scale), (int) (WINDOW_H * scale));
        return mouse;
    }

    // ==================================================================

    static void factor() {
        check("bezny displej: framebuffer = okno -> pomer 1",
                MouseScale.factor(1024, 1024, 9) == 1.0, "");
        check("Retina: framebuffer dvakrat vetsi -> pomer 2",
                MouseScale.factor(1024, 2048, 9) == 2.0, "");
        check("meritko 1,5", MouseScale.factor(1280, 1920, 9) == 1.5, "");
        check("nesmyslna velikost (minimalizovane okno) nechá pomer, jaky byl",
                MouseScale.factor(0, 2048, 2.0) == 2.0 && MouseScale.factor(1024, 0, 2.0) == 2.0
                        && MouseScale.factor(-5, 2048, 1.0) == 1.0, "");

        MouseScale mouse = new MouseScale();
        check("vychozi pomer je 1 (nez dorazi prvni velikost okna)",
                mouse.scaleX() == 1.0 && mouse.scaleY() == 1.0, "");
        mouse.update(0, 0, 0, 0);
        check("minimalizace pomer nerozbije (zadne NaN)",
                mouse.scaleX() == 1.0 && !Double.isNaN(mouse.toFramebufferX(10)), "");
    }

    static void conversion() {
        boolean ok = true;
        String detail = "";

        for (double scale : SCALES) {
            MouseScale mouse = scaleFor(scale);

            if (mouse.scaleX() != scale || mouse.scaleY() != scale
                    || mouse.toFramebufferX(0) != 0 || mouse.toFramebufferY(0) != 0
                    || mouse.toFramebufferX(100) != 100 * scale
                    || mouse.toFramebufferY(250.5) != 250.5 * scale) {
                ok = false;
                detail += scale + " ";
            }
        }
        check("prepocet pro meritka 1; 1,5; 2 a 3 (x i y, vcetne nuly a desetin)", ok, detail);

        // Osy se pocitaji zvlast - okno muze mit jiny pomer sirky nez vysky
        // (nastane napr. pri zmene velikosti, nez dorazi novy framebuffer).
        MouseScale odd = new MouseScale();
        odd.update(1000, 500, 2000, 500);
        check("kazda osa ma svuj pomer", odd.toFramebufferX(10) == 20 && odd.toFramebufferY(10) == 10, "");
    }

    // ==================================================================

    /** Klik v bodech okna: kam se opravdu trefi po prepoctu. */
    static double[] click(MouseScale mouse, double windowX, double windowY) {
        return new double[]{mouse.toFramebufferX(windowX), mouse.toFramebufferY(windowY)};
    }

    /** Z pixelu framebufferu zpatky na bod okna - tam, kam hrac miri mysi. */
    static double[] point(double scale, double frameX, double frameY) {
        return new double[]{frameX / scale, frameY / scale};
    }

    static void menuClicks() {
        // ⚠️ SKUTECNE hlavni menu, ne kopie jeho popisku. Kopie se rozejde
        // pri prvnim prejmenovani tlacitka a test pak meri neco jineho, nez
        // co hra kresli - presne to se stalo pri prejmenovani "Texture Lab"
        // na "Lab".
        Menu menu = Main.MAIN_MENU_LABELS;

        for (double scale : SCALES) {
            int fbW = (int) (WINDOW_W * scale), fbH = (int) (WINDOW_H * scale);
            MouseScale mouse = scaleFor(scale);

            boolean all = true;
            for (int i = 0; i < menu.buttonCount(); i++) {
                // Kde je tlacitko NAKRESLENE (framebuffer): najde se skenovanim
                // stredu obrazovky - Menu si rozvrzeni pocita samo.
                double[] centre = buttonCentre(menu, i, fbW, fbH);
                double[] inWindow = point(scale, centre[0], centre[1]);
                double[] hit = click(mouse, inWindow[0], inWindow[1]);
                all &= menu.buttonAt(hit[0], hit[1], fbW, fbH) == i;
            }
            check("menu pri meritku " + scale + ": klik na kazde tlacitko trefi to sve", all, "");
        }

        // A bez prepoctu by to na Retine bylo spatne - to je presne ta chyba.
        int fbW = WINDOW_W * 2, fbH = WINDOW_H * 2;
        double[] centre = buttonCentre(menu, 0, fbW, fbH);
        double[] inWindow = point(2.0, centre[0], centre[1]);
        check("bez prepoctu by klik na prvni tlacitko trefil jine (nebo nic)",
                menu.buttonAt(inWindow[0], inWindow[1], fbW, fbH) != 0,
                "" + menu.buttonAt(inWindow[0], inWindow[1], fbW, fbH));
    }

    /**
     * Stred i-teho tlacitka v pixelech framebufferu. Rozvrzeni je uvnitr Menu,
     * takze se hleda tim, na co uz Menu odpovida - buttonAt.
     */
    static double[] buttonCentre(Menu menu, int index, int fbW, int fbH) {
        int top = -1, bottom = -1;

        for (int y = 0; y < fbH; y++) {
            if (menu.buttonAt(fbW / 2.0, y, fbW, fbH) == index) {
                if (top < 0) top = y;
                bottom = y;
            }
        }

        return new double[]{fbW / 2.0, (top + bottom) / 2.0};
    }

    // ==================================================================

    static void screenClicks() {
        Options options = Options.defaults();
        OptionsScreen screen = new OptionsScreen(options, null);

        for (double scale : SCALES) {
            int fbW = (int) (WINDOW_W * scale), fbH = (int) (WINDOW_H * scale);
            MouseScale mouse = scaleFor(scale);
            ScreenLayout l = OptionsScreen.layout(fbW, fbH);

            boolean all = true;
            for (OptionsScreen.Item item : OptionsScreen.Item.values()) {
                double[] centre = rectCentre(l, item.rect());
                double[] inWindow = point(scale, centre[0], centre[1]);
                double[] hit = click(mouse, inWindow[0], inWindow[1]);
                all &= OptionsScreen.itemAt(l, hit[0], hit[1]) == item;
            }

            // Tazeni posuvniku: kdyz mys sjede k pravemu okraji drahy,
            // hodnota musi dojet na maximum (a ne zustat v pulce).
            options.setRenderDistance(Options.MIN_RENDER);
            ScreenLayout.Rect track = OptionsScreen.Item.RENDER.track();
            double[] end = {l.left() + (track.x() + track.w()) * l.scale(),
                    l.top() + (track.y() + track.h() / 2.0) * l.scale()};
            double[] endInWindow = point(scale, end[0], end[1]);
            double[] endHit = click(mouse, endInWindow[0], endInWindow[1]);
            screen.press(endHit[0], endHit[1], fbW, fbH);
            screen.release();

            check("nastaveni pri meritku " + scale + ": kazda polozka i konec posuvniku sedi",
                    all && options.renderDistance() == Options.MAX_RENDER,
                    all ? "render " + options.renderDistance() : "polozka vedle");
        }

        // Seznam svetu: radek, na ktery se klikne, se opravdu vybere.
        for (double scale : SCALES) {
            int fbW = (int) (WINDOW_W * scale), fbH = (int) (WINDOW_H * scale);
            MouseScale mouse = scaleFor(scale);
            ScreenLayout l = SelectWorldScreen.layout(fbW, fbH);

            double[] centre = rectCentre(l, SelectWorldScreen.rowRect(2));
            double[] inWindow = point(scale, centre[0], centre[1]);
            double[] hit = click(mouse, inWindow[0], inWindow[1]);

            check("vyber sveta pri meritku " + scale + ": klik na treti radek je nad tretim radkem",
                    l.hit(SelectWorldScreen.rowRect(2), hit[0], hit[1])
                            && !l.hit(SelectWorldScreen.rowRect(1), hit[0], hit[1]), "");
        }

        // Zalozeni sveta: pole se jmenem.
        for (double scale : SCALES) {
            int fbW = (int) (WINDOW_W * scale), fbH = (int) (WINDOW_H * scale);
            MouseScale mouse = scaleFor(scale);
            ScreenLayout l = CreateWorldScreen.layout(fbW, fbH);

            double[] centre = rectCentre(l, CreateWorldScreen.SEED);
            double[] inWindow = point(scale, centre[0], centre[1]);
            double[] hit = click(mouse, inWindow[0], inWindow[1]);

            check("zalozeni sveta pri meritku " + scale + ": klik do pole se seedem sedi",
                    l.hit(CreateWorldScreen.SEED, hit[0], hit[1])
                            && !l.hit(CreateWorldScreen.NAME, hit[0], hit[1]), "");
        }
    }

    static double[] rectCentre(ScreenLayout l, ScreenLayout.Rect r) {
        return new double[]{l.left() + (r.x() + r.w() / 2.0) * l.scale(),
                l.top() + (r.y() + r.h() / 2.0) * l.scale()};
    }

    // ==================================================================

    /** Texture lab: vyber dlazdice, malovani na platne i tazeni pres pixely. */
    static void labClicks() {
        for (double scale : SCALES) {
            int fbW = (int) (WINDOW_W * scale), fbH = (int) (WINDOW_H * scale);
            MouseScale mouse = scaleFor(scale);
            TextureLabLayout l = new TextureLabLayout(fbW, fbH);

            boolean tiles = true;
            for (int tile : new int[]{0, 7, 27, 63}) {
                TextureLabLayout.Rect r = TextureLabLayout.tileRect(tile);
                double[] centre = {l.left() + (r.x() + r.w() / 2.0) * l.scale(),
                        l.top() + (r.y() + r.h() / 2.0) * l.scale()};
                double[] inWindow = point(scale, centre[0], centre[1]);
                double[] hit = click(mouse, inWindow[0], inWindow[1]);
                tiles &= l.tileAt(hit[0], hit[1]) == tile;
            }

            boolean pixels = true;
            for (int[] px : new int[][]{{0, 0}, {5, 11}, {15, 15}}) {
                TextureLabLayout.Rect r = TextureLabLayout.canvasPixelRect(px[0], px[1]);
                double[] centre = {l.left() + (r.x() + r.w() / 2.0) * l.scale(),
                        l.top() + (r.y() + r.h() / 2.0) * l.scale()};
                double[] inWindow = point(scale, centre[0], centre[1]);
                double[] hit = click(mouse, inWindow[0], inWindow[1]);
                int[] found = l.canvasPixelAt(hit[0], hit[1]);
                pixels &= found != null && found[0] == px[0] && found[1] == px[1];
            }

            check("texture lab pri meritku " + scale + ": dlazdice i pixel platna pod mysi sedi",
                    tiles && pixels, tiles ? "pixel vedle" : "dlazdice vedle");
        }
    }

    // ==================================================================

    /** Inventar: klik na slot hotbaru vezme jeho obsah na kurzor. */
    static void containerClicks() {
        for (double scale : SCALES) {
            int fbW = (int) (WINDOW_W * scale), fbH = (int) (WINDOW_H * scale);
            MouseScale mouse = scaleFor(scale);
            int guiScale = Gui.scale(fbW, fbH);

            Inventory inventory = new Inventory();
            inventory.set(2, ItemStack.of(World.STONE, 7));
            ContainerScreen screen = ContainerScreen.playerInventory(
                    inventory, new Container(4), new Container(1));

            // Stred slotu 2 v hotbaru je v pixelech framebufferu; hrac ale
            // mysi miri na tentyz bod v bodech okna.
            double[] centre = InventoryTest.hotbarSlotCenter(2, fbW, fbH, guiScale);
            double[] inWindow = point(scale, centre[0], centre[1]);
            double[] hit = click(mouse, inWindow[0], inWindow[1]);

            screen.click(hit[0], hit[1], fbW, fbH, true, inventory);

            check("inventar pri meritku " + scale + ": klik na slot hotbaru vezme jeho obsah",
                    inventory.get(2).isEmpty() && screen.held().block() == World.STONE
                            && screen.held().count() == 7,
                    screen.held().toString());
        }
    }
}
