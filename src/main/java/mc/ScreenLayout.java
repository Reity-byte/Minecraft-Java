package mc;

/**
 * Rozvržení obrazovky v GUI pixelech a hit-testy myši - pro nastavení,
 * vytvoření a výběr světa. Nesahá na GL.
 *
 * ---------------------------------------------------------------------------
 * Stejný princip jako TextureLabLayout: všechno je v GUI PIXELECH od LEVÉHO
 * HORNÍHO rohu panelu a na obrazovku se násobí CELÝM měřítkem. Rozdíl je
 * v měřítku - tady je to Gui.scale(), tedy totéž co menu a HUD, včetně volby
 * GUI Scale z nastavení. Panel je vycentrovaný a zarovnaný na celý GUI pixel.
 *
 * ⚠️ Myš chodí z GLFW s počátkem NAHOŘE, Renderer2D kreslí s počátkem DOLE.
 * Převádí se na jednom místě, tady (screenBottom).
 * ---------------------------------------------------------------------------
 */
public final class ScreenLayout {

    /** Obdélník v GUI pixelech, počátek vlevo nahoře. */
    public record Rect(int x, int y, int w, int h) {

        public boolean contains(float gx, float gy)
        {
            return gx >= x && gx < x + w && gy >= y && gy < y + h;
        }

        public int right()  { return x + w; }
        public int bottom() { return y + h; }
    }

    private final int width, height;
    private final int scale;
    private final int left, top;

    /** Panel width x height GUI pixelů vycentrovaný na obrazovce. */
    public ScreenLayout(int width, int height, int screenWidth, int screenHeight)
    {
        this.width = width;
        this.height = height;
        this.scale = Gui.scale(screenWidth, screenHeight);
        this.left = (int) Gui.snap((screenWidth - width * scale) / 2f, scale);
        this.top = (int) Gui.snap((screenHeight - height * scale) / 2f, scale);
    }

    public int width()  { return width; }
    public int height() { return height; }
    public int scale()  { return scale; }
    public int left()   { return left; }
    public int top()    { return top; }

    /** Myš (GLFW, počátek nahoře) na GUI pixely panelu. */
    public float guiX(double mouseX) { return (float) ((mouseX - left) / scale); }
    public float guiY(double mouseY) { return (float) ((mouseY - top) / scale); }

    public boolean hit(Rect r, double mouseX, double mouseY)
    {
        return r.contains(guiX(mouseX), guiY(mouseY));
    }

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

    /** Horní okraj řádku textu v pixelech obrazovky s počátkem nahoře (TextRenderer). */
    public float textTop(float guiY)
    {
        return top + guiY * scale;
    }

    public float textLeft(float guiX)
    {
        return left + guiX * scale;
    }

    /**
     * Poloha myši na posuvníku jako 0 až 1. Mimo se ořízne - tah přes okraj
     * tak dojede na kraj stupnice, místo aby se zasekl kousek před ním.
     */
    public float sliderValue(Rect track, double mouseX)
    {
        float t = (guiX(mouseX) - track.x()) / track.w();
        return Math.max(0f, Math.min(1f, t));
    }

    /**
     * Hodnota posuvníku s celočíselnými kroky: 0..1 na min..max, zaokrouhleno
     * na nejbližší krok. Každý krok tak má na stupnici stejně širokou výseč.
     */
    public static int steppedValue(float t, int min, int max)
    {
        return min + Math.round(Math.max(0f, Math.min(1f, t)) * (max - min));
    }

    /** Opak steppedValue - kde na stupnici leží hodnota (značka posuvníku). */
    public static float stepPosition(int value, int min, int max)
    {
        return max == min ? 0f : Math.max(0f, Math.min(1f, (value - min) / (float) (max - min)));
    }
}
