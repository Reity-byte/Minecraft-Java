package mc;

/**
 * Celočíselné měřítko uživatelského rozhraní.
 *
 * ---------------------------------------------------------------------------
 * TOHLE JE TA VĚC, KTERÁ DĚLÁ "BLOCKY" VZHLED.
 *
 * UI se nenavrhuje v pixelech obrazovky, ale v GUI pixelech, a ty se na
 * obrazovku zvětší CELÝM číslem (2x, 3x, 4x). Jeden GUI pixel je pak přesně
 * NxN stejných obrazovkových pixelů - žádné poloviční hrany, žádné vyhlazování.
 *
 * Kdyby se měřítko počítalo plovoucí čárkou (třeba "vyplň 80 % výšky"), hrany
 * by padly mezi pixely a rozmazaly se. Právě to dělá rozdíl mezi hranatým
 * a hladkým rozhraním - víc než tvar rohů.
 * ---------------------------------------------------------------------------
 */
public final class Gui {

    /**
     * Nejmenší rozlišení, na které se musí UI vejít, v GUI pixelech.
     * Měřítko se volí jako největší celé číslo, při kterém se sem obraz ještě vejde.
     */
    private static final int MIN_WIDTH  = 320;
    private static final int MIN_HEIGHT = 240;

    /** Nad 4x už je UI zbytečně obrovské i na 4K. */
    private static final int MAX_SCALE = 4;

    /**
     * Měřítko zvolené v nastavení (GUI Scale), 0 = automaticky. Statické
     * schválně: ptá se ho HUD, menu, inventář i nastavení samo, a všechno
     * musí mít v jednom framu totéž měřítko.
     */
    private static volatile int preferred = 0;

    private Gui() {}

    public static int scale(int screenWidth, int screenHeight)
    {
        int fit = Math.max(1, Math.min(MAX_SCALE, Math.min(screenWidth / MIN_WIDTH, screenHeight / MIN_HEIGHT)));

        // Zvolené měřítko nikdy nepřeroste to, co se vejde - jinak by se
        // UI na malém okně nevešlo a tlačítka by byla mimo obrazovku.
        return preferred > 0 ? Math.min(preferred, fit) : fit;
    }

    /** 0 = automaticky, jinak požadované měřítko (oříznuté na to, co se vejde). */
    public static void setPreferredScale(int scale)
    {
        preferred = Math.max(0, Math.min(MAX_SCALE, scale));
    }

    /**
     * Zarovná souřadnici na celý GUI pixel.
     *
     * Používá se u vycentrování: (šířka - prvek) / 2 vyjde skoro vždy na
     * necelý násobek měřítka a prvek by seděl "půl pixelu vedle", takže by
     * se jeho hrany rozmazaly přesně tam, kde mají být ostré.
     */
    public static float snap(float screenValue, int scale)
    {
        return Math.round(screenValue / scale) * scale;
    }
}
