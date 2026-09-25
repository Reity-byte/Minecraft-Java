package mc;

/**
 * Overuje hit-testing tlacitek v menu.
 *
 * Menu je jedina cast 2D vrstvy, ktera se da testovat bez OpenGL: rozvrzeni
 * i prevod souradnic myshi je cista aritmetika. Kresleni samotne (Renderer2D,
 * TextRenderer) sahá na GL a testuje se az spustenim hry.
 *
 * POZOR na soustavy: buttonAt bere mys z GLFW, tedy pocatek VLEVO NAHORE
 * a y roste dolu. Rozvrzeni uvnitr pocita s pocatkem vlevo dole.
 */
public class MenuTest {

    static int failures = 0;

    static final int W = 1024;
    static final int H = 768;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    /** Kde je stred i-teho tlacitka v souradnicich myshi (y roste dolu). */
    static double[] centerOf(Menu menu, int index) {
        // Rozvrzeni je private, takze se stred najde skenovanim: prvni a posledni
        // radek, na kterem buttonAt vraci hledany index.
        int first = -1, last = -1;
        for (int y = 0; y < H; y++) {
            if (menu.buttonAt(W / 2.0, y, W, H) == index) {
                if (first < 0) first = y;
                last = y;
            }
        }
        return new double[]{W / 2.0, (first + last) / 2.0, first, last};
    }

    public static void main(String[] args) {
        Menu menu = new Menu("Title", "First", "Second", "Third");

        check("menu zna pocet tlacitek", menu.buttonCount() == 3,
                String.valueOf(menu.buttonCount()));

        // ---------- kazde tlacitko je trefitelne a maji spravne poradi ----------
        double prevY = -1;
        for (int i = 0; i < menu.buttonCount(); i++) {
            double[] c = centerOf(menu, i);

            check("tlacitko " + i + " je trefitelne uprostred",
                    menu.buttonAt(c[0], c[1], W, H) == i,
                    "y=" + c[1]);

            // Index 0 je nahore, takze v souradnicich myshi musi mit NEJMENSI y.
            check("tlacitko " + i + " je pod predchozim", c[1] > prevY,
                    "y=" + c[1] + " prev=" + prevY);
            prevY = c[1];
        }

        double[] mid = centerOf(menu, 1);
        double midY = mid[1];

        // ---------- vodorovne hranice ----------
        check("daleko vlevo netrefi nic", menu.buttonAt(10, midY, W, H) == -1, "");
        check("daleko vpravo netrefi nic", menu.buttonAt(W - 10, midY, W, H) == -1, "");

        // Kraje se najdou stejnym skenovanim, jen po ose x.
        int left = -1, right = -1;
        for (int x = 0; x < W; x++) {
            if (menu.buttonAt(x, midY, W, H) == 1) {
                if (left < 0) left = x;
                right = x;
            }
        }
        check("tlacitko je vodorovne vycentrovane",
                Math.abs((left + right) / 2.0 - W / 2.0) <= 1.0,
                "left=" + left + " right=" + right);

        check("pixel tesne vlevo od tlacitka uz netrefi",
                menu.buttonAt(left - 1, midY, W, H) == -1, "x=" + (left - 1));
        check("pixel tesne vpravo od tlacitka uz netrefi",
                menu.buttonAt(right + 1, midY, W, H) == -1, "x=" + (right + 1));

        // ---------- mezera mezi tlacitky ----------
        double[] first = centerOf(menu, 0);
        double[] second = centerOf(menu, 1);
        double gapY = (first[3] + second[2]) / 2.0;   // mezi spodkem 0 a vrskem 1

        check("mezera mezi tlacitky netrefi nic",
                menu.buttonAt(W / 2.0, gapY, W, H) == -1, "y=" + gapY);

        // ---------- nad a pod blokem tlacitek ----------
        check("nad menu netrefi nic", menu.buttonAt(W / 2.0, 0, W, H) == -1, "");
        check("pod menu netrefi nic", menu.buttonAt(W / 2.0, H - 1, W, H) == -1, "");

        // ---------- zmena velikosti okna ----------
        // Blok tlacitek se drzi stredu, takze po zmene rozmeru musi stred sedet dal.
        int w2 = 1600, h2 = 900;
        int hitAtCenter = menu.buttonAt(w2 / 2.0, h2 / 2.0, w2, h2);
        check("po zmene velikosti je uprostred porad tlacitko",
                hitAtCenter >= 0 && hitAtCenter < menu.buttonCount(),
                String.valueOf(hitAtCenter));

        // Souradnice z puvodniho okna uz na jinem rozmeru platit nemusi, ale
        // stred zustava stredem - to je jedina invarianta, na kterou se spolehame.
        // Driv ">= -1", coz plati vzdycky (-1 = nic) - uzke okno se netestovalo.
        int narrow = menu.buttonAt(400 / 2.0, 600 / 2.0, 400, 600);
        check("stred sedi i na uzkem okne", narrow >= 0 && narrow < menu.buttonCount(),
                String.valueOf(narrow));

        // ---------- menu s jedinym tlacitkem ----------
        Menu single = new Menu("One", "Only");
        double[] only = centerOf(single, 0);
        check("jedno tlacitko je vycentrovane svisle",
                Math.abs(only[1] - H / 2.0) <= 1.0, "y=" + only[1]);

        actions();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    /**
     * Kazde tlacitko obou menu musi mit akci.
     *
     * ⚠️ TOHLE JE REGRESNI TEST NA SKUTECNOU CHYBU. Akce se vybira podle
     * POPISKU tlacitka, ne podle indexu (poradi se muze menit). Prejmenovani
     * tlacitka "Texture Lab" na "Lab" proto rozpojilo jeho akci - a protoze
     * tehdejsi switch mel vetev `default -> zavri okno`, tlacitko Lab
     * UKONCILO HRU s navratovym kodem 0. Zadna vyjimka, zadna hlaska, jen
     * zavrene okno; z logu to vypadalo jako normalni konec.
     *
     * Od te doby je prevod popisku na akci cista funkce a NONE uz nic nedela.
     * Kdyz se tlacitko prejmenuje a zapomene se na switch, spadne tenhle test,
     * ne hra.
     */
    static void actions() {
        System.out.println();

        boolean allHandled = true;
        String orphan = "";

        for (int i = 0; i < Main.MAIN_MENU_LABELS.buttonCount(); i++) {
            String label = Main.MAIN_MENU_LABELS.label(i);
            if (Main.mainMenuAction(label) == Main.MainMenuAction.NONE) {
                allHandled = false;
                orphan = label;
            }
        }
        check("kazde tlacitko hlavniho menu ma akci", allHandled, orphan);

        for (int i = 0; i < Main.PAUSE_MENU_LABELS.buttonCount(); i++) {
            String label = Main.PAUSE_MENU_LABELS.label(i);
            if (Main.pauseMenuAction(label) == Main.PauseMenuAction.NONE) {
                allHandled = false;
                orphan = label;
            }
        }
        check("kazde tlacitko pauzy ma akci", allHandled, orphan);

        // Kazde tlacitko dela NECO JINEHO - dve tlacitka na tutez akci by
        // znamenala, ze jedno z nich je slepe.
        java.util.Set<Main.MainMenuAction> seen = new java.util.HashSet<>();
        boolean distinct = true;
        for (int i = 0; i < Main.MAIN_MENU_LABELS.buttonCount(); i++) {
            distinct &= seen.add(Main.mainMenuAction(Main.MAIN_MENU_LABELS.label(i)));
        }
        check("zadna dve tlacitka hlavniho menu nedelaji totez", distinct, seen.toString());

        // Tlacitko Lab opravdu otevira lab, ne neco jineho.
        check("tlacitko Lab otevira lab",
                Main.mainMenuAction("Lab") == Main.MainMenuAction.LAB, "");
        check("tlacitko Quit ukoncuje hru",
                Main.mainMenuAction("Quit") == Main.MainMenuAction.QUIT, "");

        // ⚠️ A hlavne: NEZNAMY popisek uz hru NEUKONCUJE. Presne tahle vetev
        // z prejmenovaneho tlacitka udelala tichy konec hry.
        check("neznamy popisek hlavniho menu nic nedela (uz neukoncuje hru)",
                Main.mainMenuAction("Texture Lab") == Main.MainMenuAction.NONE
                        && Main.mainMenuAction("") == Main.MainMenuAction.NONE
                        && Main.mainMenuAction("neco uplne jineho") == Main.MainMenuAction.NONE, "");
        check("neznamy popisek pauzy taky nic",
                Main.pauseMenuAction("Quit") == Main.PauseMenuAction.NONE
                        && Main.pauseMenuAction("") == Main.PauseMenuAction.NONE, "");

        // Index mimo rozsah da prazdny popisek, ten padne na NONE - klik
        // vedle tlacitka tedy nesmi nic spustit.
        check("klik mimo tlacitko nic nespusti",
                Main.mainMenuAction(Main.MAIN_MENU_LABELS.label(-1)) == Main.MainMenuAction.NONE
                        && Main.mainMenuAction(Main.MAIN_MENU_LABELS.label(99))
                                == Main.MainMenuAction.NONE, "");
    }
}
