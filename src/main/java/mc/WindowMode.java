package mc;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVidMode;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Přepínání okno / celá obrazovka za běhu.
 *
 * ---------------------------------------------------------------------------
 * Jedno okno a jeden GL kontext po celou dobu: glfwSetWindowMonitor okno jen
 * přesune na monitor v jeho nativním rozlišení a zpátky. Nové okno by znamenalo
 * nový kontext a znovu nahrát všechny textury a meshe.
 *
 * ⚠️ Poloha a velikost okna se pamatují PŘED přepnutím na celou obrazovku.
 * glfwSetWindowMonitor s NULL monitorem potřebuje, kam okno vrátit - bez
 * zapamatované polohy by se vrátilo do rohu a v rozlišení monitoru.
 *
 * Fullscreen jde na monitor, na kterém okno leží nejvíc (overlap), ne vždycky
 * na primární - na dvou monitorech by jinak hra skočila na ten druhý.
 * ---------------------------------------------------------------------------
 *
 * Výběr monitoru (bestMonitor) je čistá funkce a testuje se bez GLFW.
 */
final class WindowMode {

    /** Velikost okna, když není co obnovit (hra startuje rovnou na celé obrazovce). */
    static final int DEFAULT_WIDTH = 1024, DEFAULT_HEIGHT = 768;

    private boolean fullscreen = false;
    private int windowedX = -1, windowedY = -1;
    private int windowedWidth = DEFAULT_WIDTH, windowedHeight = DEFAULT_HEIGHT;

    /**
     * Má se opravdu přepínat, nebo už je okno v požadovaném režimu?
     *
     * ⚠️ TOHLE NENÍ JEN ÚSPORA. glfwSetWindowMonitor přestaví framebuffer
     * a synchronně spustí callbacky velikosti - redundantní volání by tedy
     * nebylo "nic se nestane", ale zbytečná přestavba uprostřed běhu.
     * Proto se při startu přepíná nejvýš jednou, i když applyOptions()
     * na konci init() zavolá apply() podruhé s touž hodnotou.
     *
     * Čistá funkce schválně - jako jediná část rozhodování jde otestovat
     * bez GLFW (viz OptionsTest).
     */
    static boolean needsSwitch(boolean current, boolean wanted)
    {
        return current != wanted;
    }

    /**
     * Přepne okno do požadovaného režimu (když už v něm je, nic nedělá).
     * Po přepnutí se znovu nastaví vsync - některé ovladače ho při změně
     * režimu zapomenou.
     *
     * Vrací false, když se celá obrazovka zapnout nepovedla (žádný monitor
     * ani video mód) - volající pak vrátí nastavení na okno, jinak by Options
     * ukazovaly "Fullscreen: ON" a každé applyOptions() by pokus opakovalo.
     */
    boolean apply(long window, boolean wantFullscreen, boolean vsync)
    {
        if(!needsSwitch(fullscreen, wantFullscreen))
        {
            return true;
        }

        if(wantFullscreen)
        {
            rememberWindow(window);

            long monitor = monitorFor(window);
            GLFWVidMode mode = monitor == NULL ? null : glfwGetVideoMode(monitor);

            if(mode == null)
            {
                System.err.println("Fullscreen: zadny monitor - zustava okno");
                return false;
            }

            glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
        }
        else
        {
            int x = windowedX, y = windowedY;

            // Poprvé z celé obrazovky (hra startovala ve fullscreenu): vycentrovat.
            if(x < 0 || y < 0)
            {
                GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
                x = mode == null ? 50 : Math.max(0, (mode.width() - windowedWidth) / 2);
                y = mode == null ? 50 : Math.max(0, (mode.height() - windowedHeight) / 2);
            }

            glfwSetWindowMonitor(window, NULL, x, y, windowedWidth, windowedHeight, GLFW_DONT_CARE);
        }

        fullscreen = wantFullscreen;
        glfwSwapInterval(vsync ? 1 : 0);
        return true;
    }

    private void rememberWindow(long window)
    {
        int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
        glfwGetWindowPos(window, x, y);
        glfwGetWindowSize(window, w, h);

        windowedX = x[0];
        windowedY = y[0];

        if(w[0] > 0 && h[0] > 0)
        {
            windowedWidth = w[0];
            windowedHeight = h[0];
        }
    }

    /** Monitor, na kterém okno leží největší plochou; bez překryvu primární. */
    private static long monitorFor(long window)
    {
        PointerBuffer monitors = glfwGetMonitors();

        if(monitors == null || monitors.limit() == 0)
        {
            return glfwGetPrimaryMonitor();
        }

        int[] wx = new int[1], wy = new int[1], ww = new int[1], wh = new int[1];
        glfwGetWindowPos(window, wx, wy);
        glfwGetWindowSize(window, ww, wh);

        int[][] rects = new int[monitors.limit()][];

        for(int i = 0; i < monitors.limit(); i++)
        {
            long monitor = monitors.get(i);
            int[] mx = new int[1], my = new int[1];
            glfwGetMonitorPos(monitor, mx, my);
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            rects[i] = mode == null ? new int[]{0, 0, 0, 0} : new int[]{mx[0], my[0], mode.width(), mode.height()};
        }

        int best = bestMonitor(new int[]{wx[0], wy[0], ww[0], wh[0]}, rects);
        return best < 0 ? glfwGetPrimaryMonitor() : monitors.get(best);
    }

    /**
     * Index obdélníku monitoru {x, y, w, h}, který se s oknem překrývá
     * největší plochou; -1, když žádný. Při shodě vyhrává dřívější (primární
     * je v seznamu GLFW první).
     */
    static int bestMonitor(int[] window, int[][] monitors)
    {
        int best = -1;
        long bestArea = 0;

        for(int i = 0; i < monitors.length; i++)
        {
            long area = overlap(window, monitors[i]);

            if(area > bestArea)
            {
                bestArea = area;
                best = i;
            }
        }

        return best;
    }

    static long overlap(int[] a, int[] b)
    {
        long w = Math.min(a[0] + a[2], b[0] + b[2]) - Math.max(a[0], b[0]);
        long h = Math.min(a[1] + a[3], b[1] + b[3]) - Math.max(a[1], b[1]);
        return w > 0 && h > 0 ? w * h : 0;
    }
}
