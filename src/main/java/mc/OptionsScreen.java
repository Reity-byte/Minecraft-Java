package mc;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

/**
 * Obrazovka Options - z hlavního menu i z pauzy.
 *
 * ---------------------------------------------------------------------------
 * Každá změna se projeví HNED, jako ve vanilla Minecraftu: obrazovka mění
 * přímo Options a hlásí to přes takeChanged(); Main nastavení při každé změně
 * znovu aplikuje (dohled, načítaný okruh, FOV, citlivost, fullscreen...).
 * Otevřená z pauzy kreslí za sebou svět, takže posunutí render distance je
 * vidět už při tažení. Na disk se nastavení zapíše při zavření obrazovky.
 *
 * Rozvržení je ve dvou sloupcích po 150 GUI pixelech jako Options
 * v Minecraftu; číselné hodnoty jsou posuvníky ve stylu HSV posuvníků labu,
 * přepínače jsou tlačítka s bevelem jako v menu.
 *
 * Hit-testy a změny hodnot nesahají na GL - test si obrazovku postaví bez
 * Widgets a kliká do ní.
 * ---------------------------------------------------------------------------
 */
public final class OptionsScreen {

    public static final int WIDTH = 320, HEIGHT = 196;

    /** Jedna položka: posuvník, nebo tlačítko (přepínač). */
    enum Item {
        FULLSCREEN(0, 0, false, "Fullscreen: toggles with %FULLSCREEN% too"),
        VSYNC(1, 0, false, "VSync: wait for the monitor refresh (%VSYNC% in game)"),
        RENDER(0, 1, true, "How many chunks are drawn. Never more than simulation."),
        SIMULATION(1, 1, true, "How many chunks are loaded and lit. Render follows it down."),
        FOV(0, 2, true, "Field of view in degrees"),
        MAX_FPS(1, 2, true, "Frame cap when VSync is off"),
        BRIGHTNESS(0, 3, true, "Lifts dark places like the brightness option in Minecraft"),
        GUI_SCALE(1, 3, false, "Size of menus and HUD; Auto = largest that fits"),
        SENSITIVITY(0, 4, true, "Mouse look speed"),
        INVERT_MOUSE(1, 4, false, "Moving the mouse up looks down");

        final int column, row;
        final boolean slider;
        final String help;

        Item(int column, int row, boolean slider, String help)
        {
            this.column = column;
            this.row = row;
            this.slider = slider;
            this.help = help;
        }

        /**
         * Nápověda s aktuálními klávesami: %AKCE% se nahradí jménem klávesy
         * z Keybinds. Natvrdo napsaná F11 by po přebindování lhala.
         */
        String help()
        {
            return help.replace("%FULLSCREEN%", Keybinds.activeKeyName(Keybinds.Action.FULLSCREEN))
                    .replace("%VSYNC%", Keybinds.activeKeyName(Keybinds.Action.VSYNC));
        }

        /** Celý obdélník položky (tlačítko, nebo popisek s dráhou posuvníku). */
        ScreenLayout.Rect rect()
        {
            return new ScreenLayout.Rect(COLUMN_X[column], FIRST_ROW_Y + row * ROW_PITCH, CELL_W, CELL_H);
        }

        /** Dráha posuvníku pod popiskem. */
        ScreenLayout.Rect track()
        {
            ScreenLayout.Rect r = rect();
            return new ScreenLayout.Rect(r.x() + 1, r.y() + TRACK_Y, r.w() - 2, TRACK_H);
        }
    }

    static final int CELL_W = 150, CELL_H = 20;
    static final int[] COLUMN_X = {5, 165};
    static final int FIRST_ROW_Y = 26, ROW_PITCH = 25;
    static final int TRACK_Y = 12, TRACK_H = 7;

    static final ScreenLayout.Rect TITLE = new ScreenLayout.Rect(0, 4, WIDTH, 18);
    static final ScreenLayout.Rect HELP = new ScreenLayout.Rect(5, 152, 310, 10);
    static final ScreenLayout.Rect DONE = new ScreenLayout.Rect(60, 168, 200, 20);

    private final Options options;
    private final Widgets widgets;

    /** Posuvník, který se právě táhne, nebo null. */
    private Item dragged = null;

    private boolean changed = false;

    OptionsScreen(Options options, Widgets widgets)
    {
        this.options = options;
        this.widgets = widgets;
    }

    /** Změnilo se od minulého dotazu něco? Main pak nastavení aplikuje. */
    public boolean takeChanged()
    {
        boolean was = changed;
        changed = false;
        return was;
    }

    static ScreenLayout layout(int screenWidth, int screenHeight)
    {
        return new ScreenLayout(WIDTH, HEIGHT, screenWidth, screenHeight);
    }

    /** Položka pod myší, nebo null. */
    static Item itemAt(ScreenLayout l, double mouseX, double mouseY)
    {
        for(Item item : Item.values())
        {
            if(l.hit(item.rect(), mouseX, mouseY))
            {
                return item;
            }
        }

        return null;
    }

    // ------------------------------------------------------------------
    // vstup
    // ------------------------------------------------------------------

    /**
     * Trefil poslední klik tlačítko, které obrazovka obsloužila sama (bez
     * akce pro Main)? Main podle toho zahraje zvuk kliknutí - dřív zněla
     * jen tlačítka, jejichž akci vracela obrazovka, a stejně vypadající
     * přepínače vedle nich mlčely.
     */
    private boolean buttonClicked = false;

    public boolean takeClicked()
    {
        boolean was = buttonClicked;
        buttonClicked = false;
        return was;
    }

    /** Zmáčknutí levého tlačítka. Vrací true, když se má obrazovka zavřít (Done). */
    public boolean press(double mouseX, double mouseY, int screenWidth, int screenHeight)
    {
        ScreenLayout l = layout(screenWidth, screenHeight);

        if(l.hit(DONE, mouseX, mouseY))
        {
            return true;
        }

        Item item = itemAt(l, mouseX, mouseY);

        if(item == null)
        {
            return false;
        }

        if(item.slider)
        {
            dragged = item;
            slide(l, mouseX);
        }
        else
        {
            toggle(item);
            buttonClicked = true;
        }

        return false;
    }

    /** Pohyb myši s drženým tlačítkem - táhne posuvník, i když myš sjede mimo něj. */
    public void drag(double mouseX, double mouseY, int screenWidth, int screenHeight)
    {
        if(dragged != null)
        {
            slide(layout(screenWidth, screenHeight), mouseX);
        }
    }

    public void release()
    {
        dragged = null;
    }

    /** Klávesa. Vrací true, když se má obrazovka zavřít (Esc). */
    public boolean key(int key)
    {
        return key == GLFW_KEY_ESCAPE;
    }

    private void toggle(Item item)
    {
        switch(item)
        {
            case FULLSCREEN   -> options.setFullscreen(!options.fullscreen());
            case VSYNC        -> options.setVsync(!options.vsync());
            case GUI_SCALE    -> options.cycleGuiScale();
            case INVERT_MOUSE -> options.setInvertMouse(!options.invertMouse());
            default -> { return; }
        }

        changed = true;
    }

    private void slide(ScreenLayout l, double mouseX)
    {
        float t = l.sliderValue(dragged.track(), mouseX);

        switch(dragged)
        {
            case RENDER -> options.setRenderDistance(
                    ScreenLayout.steppedValue(t, Options.MIN_RENDER, Options.MAX_RENDER));
            case SIMULATION -> options.setSimulationDistance(
                    ScreenLayout.steppedValue(t, Options.MIN_SIMULATION, Options.MAX_SIMULATION));
            case FOV -> options.setFov(ScreenLayout.steppedValue(t, Options.MIN_FOV, Options.MAX_FOV));
            case MAX_FPS -> {
                // Poslední krok za MAX_FPS je "Unlimited", jako v Minecraftu.
                int step = ScreenLayout.steppedValue(t, Options.MIN_FPS / Options.FPS_STEP,
                        Options.MAX_FPS / Options.FPS_STEP + 1);
                options.setMaxFps(step * Options.FPS_STEP);
            }
            case BRIGHTNESS -> options.setBrightness(ScreenLayout.steppedValue(t, 0, 100) / 100f);
            case SENSITIVITY -> options.setSensitivity(ScreenLayout.steppedValue(t,
                    Math.round(Options.MIN_SENSITIVITY * 20), Math.round(Options.MAX_SENSITIVITY * 20)) / 20f);
            default -> { return; }
        }

        changed = true;
    }

    /** Kde na dráze leží aktuální hodnota posuvníku (0 až 1). */
    float position(Item item)
    {
        return switch(item)
        {
            case RENDER -> ScreenLayout.stepPosition(options.renderDistance(), Options.MIN_RENDER, Options.MAX_RENDER);
            case SIMULATION -> ScreenLayout.stepPosition(options.simulationDistance(),
                    Options.MIN_SIMULATION, Options.MAX_SIMULATION);
            case FOV -> ScreenLayout.stepPosition(options.fov(), Options.MIN_FOV, Options.MAX_FOV);
            case MAX_FPS -> options.maxFps() == Options.UNLIMITED_FPS ? 1f
                    : ScreenLayout.stepPosition(options.maxFps() / Options.FPS_STEP,
                    Options.MIN_FPS / Options.FPS_STEP, Options.MAX_FPS / Options.FPS_STEP + 1);
            case BRIGHTNESS -> options.brightness();
            case SENSITIVITY -> ScreenLayout.stepPosition(Math.round(options.sensitivity() * 20),
                    Math.round(Options.MIN_SENSITIVITY * 20), Math.round(Options.MAX_SENSITIVITY * 20));
            default -> 0f;
        };
    }

    /** Popisek položky s aktuální hodnotou. */
    String caption(Item item)
    {
        return switch(item)
        {
            case FULLSCREEN   -> "Fullscreen: " + onOff(options.fullscreen());
            case VSYNC        -> "VSync: " + onOff(options.vsync());
            // Bez jednotky: "Simulation Distance: 16 chunks" se do sloupce
            // při velkém GUI měřítku nevejde. Chunky říká nápověda dole.
            case RENDER       -> "Render Distance: " + options.renderDistance();
            case SIMULATION   -> "Simulation Distance: " + options.simulationDistance();
            case FOV          -> "FOV: " + options.fov() + (options.fov() == Options.DEFAULT_FOV ? " (Normal)" : "");
            case MAX_FPS      -> "Max Framerate: " + options.fpsLabel();
            case BRIGHTNESS   -> "Brightness: " + options.brightnessLabel();
            case GUI_SCALE    -> "GUI Scale: " + options.guiScaleLabel();
            case SENSITIVITY  -> "Sensitivity: " + options.sensitivityLabel();
            case INVERT_MOUSE -> "Invert Mouse: " + onOff(options.invertMouse());
        };
    }

    private static String onOff(boolean on)
    {
        return on ? "ON" : "OFF";
    }

    // ------------------------------------------------------------------
    // kreslení
    // ------------------------------------------------------------------

    /** overWorld = za obrazovkou je svět (z pauzy) a ztmaví se; z menu je pod ní pozadí menu. */
    public void render(int screenWidth, int screenHeight, double mouseX, double mouseY, boolean overWorld)
    {
        ScreenLayout l = layout(screenWidth, screenHeight);
        Item hovered = dragged != null ? dragged : itemAt(l, mouseX, mouseY);
        Renderer2D shapes = widgets.shapes;
        TextRenderer text = widgets.text;

        shapes.begin(screenWidth, screenHeight);

        if(overWorld)
        {
            widgets.dim(screenWidth, screenHeight);
        }

        for(Item item : Item.values())
        {
            if(item.slider)
            {
                widgets.slider(l, screenHeight, item.track(), position(item), item == hovered);
            }
            else
            {
                widgets.button(l, screenHeight, item.rect(), item == hovered, true);
            }
        }

        widgets.button(l, screenHeight, DONE, l.hit(DONE, mouseX, mouseY), true);

        shapes.end();

        // Nadpis dvojnásobným měřítkem, jako nadpis menu.
        text.begin(screenWidth, screenHeight, l.scale() * 2);
        text.drawCenteredShadowed("Options", l.textLeft(WIDTH / 2f), l.textTop(TITLE.y()),
                Palette.TEXT, Palette.TEXT_SHADOW);
        text.end();

        text.begin(screenWidth, screenHeight, l.scale());

        for(Item item : Item.values())
        {
            if(item.slider)
            {
                ScreenLayout.Rect r = item.rect();
                widgets.label(l, r.x() + 1, r.y(), widgets.fit(caption(item), r.w() - 2, l.scale()));
            }
            else
            {
                widgets.centered(l, item.rect(), widgets.fit(caption(item), CELL_W - 6, l.scale()));
            }
        }

        if(hovered != null)
        {
            widgets.muted(l, HELP.x(), HELP.y(), widgets.fit(hovered.help(), HELP.w(), l.scale()));
        }

        widgets.centered(l, DONE, "Done");

        text.end();
    }
}
