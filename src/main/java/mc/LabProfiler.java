package mc;

import java.util.Locale;

/**
 * Měření času jednotlivých fází vykreslení texture labu.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ PROČ PO FÁZÍCH, A NE JEN FPS. Lab na slabší grafice sekal, ale FPS samo
 * o sobě neřekne, jestli za to může nahrávání atlasu, počítání barev na CPU,
 * nebo kreslení tvarů. Každá fáze se proto měří zvlášť a vedle času se
 * ukazuje POČET DRAW CALLŮ (GlStats) - ten je nezávislý na stroji, takže
 * podle něj jde poznat zlepšení i na počítači, kde se seká neprojevovalo.
 *
 * ⚠️ Časy jsou CPU čas, ne GPU. U 2D UI je to přesně to, co má být vidět:
 * náklad jednoho draw callu (sestavit příkaz, nahrát vrcholy, ověřit stav)
 * platí ovladač na CPU. Na macOS je ten náklad několikanásobný oproti
 * Windows - proto tam lab sekal a jinde ne.
 *
 * Průměruje se přes okno posledních WINDOW framů, ať čísla neposkakují.
 * Zapíná se v labu klávesou ladicího výpisu (výchozí F3), jako ve hře.
 * ---------------------------------------------------------------------------
 */
public final class LabProfiler {

    public static final int UPLOAD = 0, PALETTE = 1, SHAPES = 2, IMAGES = 3, PREVIEW = 4, TEXT = 5;
    public static final int PHASES = 6;

    static final String[] NAMES = {"upload", "palette", "shapes", "images", "preview", "text"};

    /** Přes kolik framů se průměruje. Při 60 FPS je to půl sekundy. */
    static final int WINDOW = 30;

    private boolean enabled = false;

    private final long[] current = new long[PHASES];
    private final long[] total = new long[PHASES];
    private final double[] average = new double[PHASES];

    private long frameStart = 0;
    private long frameTotal = 0;
    private double frameAverage = 0;

    private int drawTotal = 0;
    private int drawAverage = 0;

    private int frames = 0;

    /**
     * Rozběhnuté fáze, vnořené do sebe. Hub měří obsah módu jako celek
     * (SHAPES) a Blocks a Skin uvnitř něj měří jemněji; vnořená fáze
     * vnější POZASTAVÍ, takže se žádný čas nepočítá dvakrát.
     */
    private final int[] stack = new int[8];
    private int depth = 0;

    /** Od kdy běží fáze na vrcholu zásobníku. */
    private long running = 0;

    public boolean enabled()
    {
        return enabled;
    }

    public void toggle()
    {
        enabled = !enabled;
        reset();
    }

    private void reset()
    {
        java.util.Arrays.fill(total, 0L);
        frameTotal = 0;
        drawTotal = 0;
        frames = 0;
    }

    /** Začátek vykreslení labu. Vynuluje počítadlo draw callů. */
    public void beginFrame()
    {
        if(!enabled)
        {
            return;
        }

        java.util.Arrays.fill(current, 0L);
        depth = 0;
        GlStats.resetFrame();
        frameStart = System.nanoTime();
    }

    public void start(int phase)
    {
        if(!enabled || depth == stack.length)
        {
            return;
        }

        long now = System.nanoTime();

        if(depth > 0)
        {
            // Vnější fáze se pozastaví - vnořená si svůj čas započítá sama.
            current[stack[depth - 1]] += now - running;
        }

        stack[depth++] = phase;
        running = now;
    }

    public void stop(int phase)
    {
        if(!enabled || depth == 0 || stack[depth - 1] != phase)
        {
            return;
        }

        long now = System.nanoTime();
        current[phase] += now - running;
        depth--;

        // Vnější fáze (je-li) běží dál od teď.
        running = now;
    }

    /** Konec vykreslení labu - přičte frame do okna a případně přepočítá průměry. */
    public void endFrame()
    {
        if(!enabled)
        {
            return;
        }

        frameTotal += System.nanoTime() - frameStart;
        drawTotal += GlStats.drawCalls();

        for(int i = 0; i < PHASES; i++)
        {
            total[i] += current[i];
        }

        if(++frames < WINDOW)
        {
            return;
        }

        for(int i = 0; i < PHASES; i++)
        {
            average[i] = total[i] / (double) frames / 1_000_000.0;
        }

        frameAverage = frameTotal / (double) frames / 1_000_000.0;
        drawAverage = Math.round(drawTotal / (float) frames);
        reset();
    }

    /** Poslední spočtený průměr fáze v milisekundách - pro testy a sondy. */
    public double millis(int phase)
    {
        return average[phase];
    }

    public double frameMillis()
    {
        return frameAverage;
    }

    public int drawCalls()
    {
        return drawAverage;
    }

    /**
     * Dva řádky do labu: celkový čas s počtem draw callů a rozpis fází.
     * Anglicky jako zbytek UI (atlas fontu je jen ASCII).
     */
    public String[] lines()
    {
        StringBuilder phases = new StringBuilder();

        for(int i = 0; i < PHASES; i++)
        {
            phases.append(i == 0 ? "" : "  ")
                    .append(NAMES[i])
                    .append(String.format(Locale.ROOT, " %.2f", average[i]));
        }

        return new String[]{
                String.format(Locale.ROOT, "lab frame %.2f ms   draw calls %d   (%s hides)",
                        frameAverage, drawAverage, Keybinds.activeKeyName(Keybinds.Action.DEBUG)),
                phases.toString()
        };
    }
}
