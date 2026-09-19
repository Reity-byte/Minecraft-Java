package mc;

/**
 * Svislé menu s tlačítky uprostřed obrazovky.
 *
 * Rozvržení se počítá při každém vykreslení, aby menu sedělo i po změně
 * velikosti okna. Tlačítka se drží v soustavě s počátkem vlevo dole, stejně
 * jako Renderer2D; myš z GLFW chodí s počátkem vlevo nahoře, takže se
 * v buttonAt() převádí.
 *
 * ---------------------------------------------------------------------------
 * ROZMĚRY JSOU V GUI PIXELECH a na obrazovku se násobí měřítkem z Gui.scale().
 * Tlačítko 200x20 je přímo míra z Minecraftu. Měřítko se počítá uvnitř, ne
 * předává zvenku - jinak by se dalo poslat jinou hodnotu do kreslení než
 * do hit-testu a tlačítka by reagovala vedle toho, kde jsou vidět.
 * ---------------------------------------------------------------------------
 *
 * Hover není skok, ale krátký přechod: každé tlačítko si drží vlastní postup
 * 0..1, který se v render() posouvá podle dt. Velikost se přitom NEMĚNÍ -
 * roztažení by muselo být necelým počtem GUI pixelů a rozmazalo by hrany.
 */
public class Menu {

    // rozměry v GUI pixelech
    private static final float BUTTON_WIDTH  = 200f;
    private static final float BUTTON_HEIGHT = 20f;
    private static final float BUTTON_GAP    = 4f;
    private static final float BUTTON_BEVEL  = 1f;
    private static final float TITLE_GAP     = 24f;

    /** Jak rychle najíždí zvýraznění, v podílech za sekundu. */
    private static final float HOVER_SPEED = 18f;

    private final String title;
    private final String[] labels;

    /** Postup najetí pro každé tlačítko, 0 = klid, 1 = plné zvýraznění. */
    private final float[] hover;

    // Přepisují se při každém kreslení místo alokace - viz Hud.faceColor.
    private final float[] fill      = new float[4];
    private final float[] highlight = new float[4];
    private final float[] shadow    = new float[4];

    public Menu(String title, String... labels)
    {
        this.title = title;
        this.labels = labels;
        this.hover = new float[labels.length];
    }

    public int buttonCount()
    {
        return labels.length;
    }

    /** Popisek tlačítka - podle něj se rozhoduje, co klik udělá (pořadí se mění). */
    public String label(int index)
    {
        return index >= 0 && index < labels.length ? labels[index] : "";
    }

    private static float buttonLeft(int screenWidth, int scale)
    {
        return Gui.snap((screenWidth - BUTTON_WIDTH * scale) / 2f, scale);
    }

    /** Spodní hrana i-tého tlačítka v pixelech obrazovky, počátek vlevo dole. */
    private float buttonBottom(int screenHeight, int scale, int index)
    {
        float blockHeight =
                (labels.length * BUTTON_HEIGHT + (labels.length - 1) * BUTTON_GAP) * scale;

        float topOfBlock = Gui.snap(screenHeight / 2f + blockHeight / 2f, scale);

        return topOfBlock - (index + 1) * BUTTON_HEIGHT * scale - index * BUTTON_GAP * scale;
    }

    /**
     * Které tlačítko je pod myší? Vrací -1, když žádné.
     * mouseY přichází z GLFW s počátkem nahoře, proto se překlápí.
     */
    public int buttonAt(double mouseX, double mouseY, int screenWidth, int screenHeight)
    {
        int scale = Gui.scale(screenWidth, screenHeight);

        float x = buttonLeft(screenWidth, scale);
        float width = BUTTON_WIDTH * scale;
        float height = BUTTON_HEIGHT * scale;

        float y = (float) (screenHeight - mouseY);

        if(mouseX < x || mouseX > x + width)
        {
            return -1;
        }

        for(int i = 0; i < labels.length; i++)
        {
            float bottom = buttonBottom(screenHeight, scale, i);
            if(y >= bottom && y <= bottom + height)
            {
                return i;
            }
        }

        return -1;
    }

    /**
     * dimBackground = kreslit ztmavení scény za menu. Hlavní menu ho nechce -
     * tam je pod menu dlaždicované pozadí z BackgroundRenderer, ne svět.
     */
    public void render(Renderer2D shapes, TextRenderer text,
                       int screenWidth, int screenHeight, int hovered,
                       boolean dimBackground, float dt)
    {
        int scale = Gui.scale(screenWidth, screenHeight);

        advanceHover(hovered, dt);

        shapes.begin(screenWidth, screenHeight);

        if(dimBackground)
        {
            shapes.fillRectGradient(0, 0, screenWidth, screenHeight,
                    Palette.DIM_BOTTOM, Palette.DIM_TOP);
        }

        for(int i = 0; i < labels.length; i++)
        {
            mix(fill, Palette.BUTTON_FILL, Palette.BUTTON_HOVER_FILL, hover[i]);
            mix(highlight, Palette.BUTTON_HIGHLIGHT, Palette.BUTTON_HOVER_HIGHLIGHT, hover[i]);
            mix(shadow, Palette.BUTTON_SHADOW, Palette.BUTTON_HOVER_SHADOW, hover[i]);

            shapes.bevelRect(buttonLeft(screenWidth, scale),
                    buttonBottom(screenHeight, scale, i),
                    BUTTON_WIDTH * scale, BUTTON_HEIGHT * scale, BUTTON_BEVEL * scale,
                    Palette.BUTTON_OUTLINE, fill, highlight, shadow);
        }

        shapes.end();

        // Text se kreslí až po tvarech, ať není překrytý.
        // Nadpis je stejný font, jen dvojnásobným měřítkem - u pixelového písma
        // je zvětšování ten správný nástroj, druhý atlas by měl jiné zrno.
        float titleBottom = buttonBottom(screenHeight, scale, 0) + BUTTON_HEIGHT * scale;

        text.begin(screenWidth, screenHeight, scale * 2);
        text.drawCenteredShadowed(title, screenWidth / 2f,
                screenHeight - titleBottom - TITLE_GAP * scale,
                Palette.TEXT, Palette.TEXT_SHADOW);
        text.end();

        text.begin(screenWidth, screenHeight, scale);

        for(int i = 0; i < labels.length; i++)
        {
            float bottom = buttonBottom(screenHeight, scale, i);

            // Svislé vycentrování textu v tlačítku, zarovnané na celý GUI pixel.
            float labelTop = Gui.snap(
                    screenHeight - bottom - BUTTON_HEIGHT * scale
                            + (BUTTON_HEIGHT * scale - text.lineHeight()) / 2f, scale);

            text.drawCenteredShadowed(labels[i], screenWidth / 2f, labelTop,
                    Palette.TEXT, Palette.TEXT_SHADOW);
        }

        text.end();
    }

    /**
     * Posune zvýraznění každého tlačítka k cíli. Exponenciální náběh nejde
     * použít beze změny - při velkém dt by přestřelil, proto je krok omezený na 1.
     */
    private void advanceHover(int hovered, float dt)
    {
        float step = Math.min(1f, HOVER_SPEED * Math.max(0f, dt));

        for(int i = 0; i < hover.length; i++)
        {
            float target = i == hovered ? 1f : 0f;
            hover[i] += (target - hover[i]) * step;
        }
    }

    private static void mix(float[] out, float[] a, float[] b, float t)
    {
        for(int c = 0; c < 4; c++)
        {
            out[c] = a[c] + (b[c] - a[c]) * t;
        }
    }
}
