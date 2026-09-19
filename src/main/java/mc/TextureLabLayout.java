package mc;

/**
 * Rozvržení texture labu a hit-testy myši. Nesahá na GL.
 *
 * ---------------------------------------------------------------------------
 * Všechno je v GUI PIXELECH od LEVÉHO HORNÍHO rohu panelu, jako rozvržení
 * v ContainerScreen, a na obrazovku se násobí CELÝM měřítkem. Lab má ale
 * vlastní referenční velikost (448 x 256), ne 320 x 240 z Gui: potřebuje
 * vedle sebe atlas, plátno i náhled, takže bere největší celé měřítko, při
 * kterém se vejde. Na 1024 x 768 to je 2, na Full HD 4.
 *
 * ⚠️ Plátno i přehled atlasu mají řádek 0 DOLE, jako atlas a GL. Myš chodí
 * z GLFW s počátkem nahoře, takže se řádek při hit-testu překlápí - na jednom
 * místě, tady.
 * ---------------------------------------------------------------------------
 */
public final class TextureLabLayout {

    public static final int WIDTH = 448;
    public static final int HEIGHT = 256;

    /** Obdélník v GUI pixelech, počátek vlevo nahoře. */
    public record Rect(int x, int y, int w, int h) {

        public boolean contains(float gx, float gy)
        {
            return gx >= x && gx < x + w && gy >= y && gy < y + h;
        }
    }

    /** Přehled atlasu: jeden GUI pixel na pixel atlasu. */
    public static final Rect ATLAS = new Rect(8, 20, AtlasEditor.SIZE, AtlasEditor.SIZE);

    /** Plátno: dlaždice 16 x 16, pixel dlaždice je 8 x 8 GUI pixelů. */
    public static final int CANVAS_PIXEL = 8;
    public static final Rect CANVAS = new Rect(146, 20,
            AtlasEditor.TILE * CANVAS_PIXEL, AtlasEditor.TILE * CANVAS_PIXEL);

    /** Živý 3D náhled bloku. */
    public static final Rect PREVIEW = new Rect(284, 20, 156, 128);

    // --- paleta ---
    public static final int SWATCH_COLUMNS = 12;
    public static final int SWATCH_ROWS = 2;
    public static final int SWATCH_PITCH = 10;
    public static final int SWATCH_SIZE = 9;
    public static final Rect SWATCHES = new Rect(146, 154,
            SWATCH_COLUMNS * SWATCH_PITCH, SWATCH_ROWS * SWATCH_PITCH);

    public static final Rect CURRENT = new Rect(146, 178, 20, 20);
    public static final Rect HEX = new Rect(170, 182, 104, 12);

    public static final Rect HUE = new Rect(146, 204, 128, 8);
    public static final Rect SATURATION = new Rect(146, 216, 128, 8);
    public static final Rect VALUE = new Rect(146, 228, 128, 8);

    // --- tlačítka a texty ---
    public static final Rect SAVE = new Rect(284, 154, 156, 18);
    public static final Rect REVERT = new Rect(284, 176, 156, 18);
    public static final Rect CLOSE = new Rect(284, 198, 156, 18);

    public static final int TITLE_Y = 6;
    public static final int INFO_Y = 154;
    public static final int STATUS_Y = 222;
    public static final int HELP_Y = 244;

    private final int scale;
    private final int left;
    private final int top;

    public TextureLabLayout(int screenWidth, int screenHeight)
    {
        scale = scaleFor(screenWidth, screenHeight);
        left = (int) Gui.snap((screenWidth - WIDTH * scale) / 2f, scale);
        top = (int) Gui.snap((screenHeight - HEIGHT * scale) / 2f, scale);
    }

    /** Největší celé měřítko, při kterém se lab vejde. Aspoň 1. */
    public static int scaleFor(int screenWidth, int screenHeight)
    {
        return Math.max(1, Math.min(screenWidth / WIDTH, screenHeight / HEIGHT));
    }

    public int scale() { return scale; }
    public int left()  { return left; }
    public int top()   { return top; }

    // ------------------------------------------------------------------
    // převody
    // ------------------------------------------------------------------

    /** Myš (GLFW, počátek nahoře) na GUI pixely panelu. */
    public float guiX(double mouseX) { return (float) ((mouseX - left) / scale); }
    public float guiY(double mouseY) { return (float) ((mouseY - top) / scale); }

    /** Levý okraj obdélníku v pixelech obrazovky. */
    public float screenX(Rect r)
    {
        return left + r.x() * scale;
    }

    /** DOLNÍ okraj obdélníku v pixelech obrazovky s počátkem dole (Renderer2D). */
    public float screenBottom(Rect r, int screenHeight)
    {
        return screenHeight - (top + (r.y() + r.h()) * scale);
    }

    /** Horní okraj řádku textu, v pixelech obrazovky s počátkem nahoře (TextRenderer). */
    public float textTop(float guiY)
    {
        return top + guiY * scale;
    }

    public float textLeft(float guiX)
    {
        return left + guiX * scale;
    }

    public boolean hit(Rect r, double mouseX, double mouseY)
    {
        return r.contains(guiX(mouseX), guiY(mouseY));
    }

    // ------------------------------------------------------------------
    // plátno a přehled atlasu
    // ------------------------------------------------------------------

    /** Pixel dlaždice pod myší jako {x, y} (y = 0 dole), nebo null mimo plátno. */
    public int[] canvasPixelAt(double mouseX, double mouseY)
    {
        float gx = guiX(mouseX), gy = guiY(mouseY);

        if(!CANVAS.contains(gx, gy))
        {
            return null;
        }

        int x = (int) ((gx - CANVAS.x()) / CANVAS_PIXEL);
        int rowFromTop = (int) ((gy - CANVAS.y()) / CANVAS_PIXEL);

        return new int[]{x, AtlasEditor.TILE - 1 - rowFromTop};
    }

    /** Obdélník pixelu (x, y) dlaždice na plátně - pro zvýraznění pod myší. */
    public static Rect canvasPixelRect(int x, int y)
    {
        return new Rect(CANVAS.x() + x * CANVAS_PIXEL,
                CANVAS.y() + (AtlasEditor.TILE - 1 - y) * CANVAS_PIXEL,
                CANVAS_PIXEL, CANVAS_PIXEL);
    }

    /** Dlaždice pod myší v přehledu atlasu, nebo -1. */
    public int tileAt(double mouseX, double mouseY)
    {
        float gx = guiX(mouseX), gy = guiY(mouseY);

        if(!ATLAS.contains(gx, gy))
        {
            return -1;
        }

        int atlasX = (int) (gx - ATLAS.x());
        int atlasY = AtlasEditor.SIZE - 1 - (int) (gy - ATLAS.y());

        return AtlasEditor.tileAt(atlasX, atlasY);
    }

    /** Buňka dlaždice v přehledu atlasu. Řádek 0 atlasu je dole. */
    public static Rect tileRect(int tile)
    {
        int rowFromTop = AtlasEditor.TILES_PER_ROW - 1 - BlockAtlas.row(tile);

        return new Rect(ATLAS.x() + BlockAtlas.column(tile) * AtlasEditor.TILE,
                ATLAS.y() + rowFromTop * AtlasEditor.TILE,
                AtlasEditor.TILE, AtlasEditor.TILE);
    }

    // ------------------------------------------------------------------
    // paleta
    // ------------------------------------------------------------------

    /** Index vzorku pod myší (po řádcích), nebo -1. Mezera mezi vzorky nic netrefí. */
    public int swatchAt(double mouseX, double mouseY)
    {
        float gx = guiX(mouseX) - SWATCHES.x();
        float gy = guiY(mouseY) - SWATCHES.y();

        if(gx < 0 || gy < 0 || gx >= SWATCHES.w() || gy >= SWATCHES.h()
                || gx % SWATCH_PITCH >= SWATCH_SIZE || gy % SWATCH_PITCH >= SWATCH_SIZE)
        {
            return -1;
        }

        return (int) (gy / SWATCH_PITCH) * SWATCH_COLUMNS + (int) (gx / SWATCH_PITCH);
    }

    public static Rect swatchRect(int index)
    {
        return new Rect(SWATCHES.x() + (index % SWATCH_COLUMNS) * SWATCH_PITCH,
                SWATCHES.y() + (index / SWATCH_COLUMNS) * SWATCH_PITCH,
                SWATCH_SIZE, SWATCH_SIZE);
    }

    /** Poloha myši na posuvníku jako 0 až 1 (mimo se ořízne - kvůli tažení přes okraj). */
    public float sliderValue(Rect bar, double mouseX)
    {
        float t = (guiX(mouseX) - bar.x()) / bar.w();
        return Math.max(0f, Math.min(1f, t));
    }
}
