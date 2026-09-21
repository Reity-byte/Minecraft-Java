package mc;

/**
 * Boční panel labu: svislý seznam tlačítek, jedno na každý mód.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ PANEL NEVÍ, JAKÉ MÓDY EXISTUJÍ, ANI CO DĚLAJÍ. Zná jen jejich POČET
 * a z něj spočítá obdélníky. Přidat další mód (Keybind Lab, Ore Tuner…) proto
 * znamená přidat položku do seznamu v `TextureLab` a nic víc - tahle třída
 * ani její test se nemění. To je celý smysl toho, že se navigace oddělila od
 * obsahu: dřív byly Blocks a Skin dvě natvrdo napsaná tlačítka
 * (`MODE_BLOCKS`, `MODE_SKIN`) a třetí mód by znamenal třetí konstantu,
 * třetí větev v hit-testu a třetí v kreslení.
 *
 * ⚠️ IKONY SI KRESLÍ MÓD SÁM (`LabMode.drawIcon`). Panel jen řekne "tady máš
 * čtverec" - jinak by musel znát všechny módy a byli bychom zpátky u switche.
 *
 * ⚠️ ŠÍŘKA 32 GUI PIXELŮ JE ZVOLENÁ PODLE MĚŘÍTKA, ne od oka. Lab bere
 * největší CELÉ měřítko, při kterém se vejde (viz `TextureLabLayout`), takže
 * každý pixel šířky navíc může celé měřítko srazit o stupeň. S obsahem 448
 * vyjde celek na 480 a měřítko zůstává na všech běžných rozlišeních přesně
 * takové, jaké bylo před bočním panelem - včetně 1440x900, kde by při 484
 * spadlo ze 3 na 2. `TextureLabTest` to kontroluje výčtem rozlišení.
 *
 * Důsledek té šířky: tlačítko je 28x28, tedy JEN IKONA bez popisku - "Recipes"
 * by potřebovalo zhruba 49 pixelů. Jméno módu je vidět v titulku labu a ve
 * stavovém řádku při najetí myší, takže se pořád dá zjistit bez čtení kódu.
 * ---------------------------------------------------------------------------
 *
 * Čistá aritmetika - nesahá na GL, takže jde celá otestovat headless.
 */
public final class LabSidebar {

    /** Šířka pruhu v GUI pixelech. Viz poznámka u třídy - nezvětšovat bezmyšlenkovitě. */
    public static final int WIDTH = 32;

    public static final int BUTTON_SIZE = 28;

    /** Odsazení tlačítka od okraje pruhu: (WIDTH - BUTTON_SIZE) / 2. */
    public static final int MARGIN = (WIDTH - BUTTON_SIZE) / 2;

    /** Kde začíná první tlačítko - pod titulkem labu. */
    public static final int FIRST_Y = 20;

    /** Mezera mezi tlačítky. */
    public static final int GAP = 4;

    /** Ikona uvnitř tlačítka, vycentrovaná. */
    public static final int ICON_SIZE = 18;
    public static final int ICON_INSET = (BUTTON_SIZE - ICON_SIZE) / 2;

    private LabSidebar() {}

    /**
     * Obdélník tlačítka módu, v GUI pixelech PANELU (tedy s počátkem na levém
     * okraji bočního panelu, ne obsahu).
     */
    public static TextureLabLayout.Rect button(int index)
    {
        return new TextureLabLayout.Rect(MARGIN, FIRST_Y + index * (BUTTON_SIZE + GAP),
                BUTTON_SIZE, BUTTON_SIZE);
    }

    /** Obdélník ikony uvnitř tlačítka. */
    public static TextureLabLayout.Rect icon(int index)
    {
        TextureLabLayout.Rect b = button(index);
        return new TextureLabLayout.Rect(b.x() + ICON_INSET, b.y() + ICON_INSET,
                ICON_SIZE, ICON_SIZE);
    }

    /**
     * Index tlačítka pod danou pozicí v GUI pixelech panelu, nebo -1.
     *
     * ⚠️ Mezera mezi tlačítky NENÍ tlačítko. Počítat index dělením roztečí by
     * byl klik i do mezery, takže by se mód přepnul, i když uživatel minul.
     */
    public static int buttonAt(float panelX, float panelY, int count)
    {
        for(int i = 0; i < count; i++)
        {
            if(button(i).contains(panelX, panelY))
            {
                return i;
            }
        }

        return -1;
    }

    /**
     * Kolik tlačítek se do panelu vejde, než přeteče dolů.
     *
     * Není to mez, kterou by kód vynucoval - je to číslo pro test a pro
     * příštího, kdo bude přidávat mód. Až se do toho někdo opře, bude potřeba
     * rolování; dokud se módy vejdou, je to zbytečná složitost.
     */
    public static int capacity(int panelHeight)
    {
        int usable = panelHeight - FIRST_Y - GAP;
        return Math.max(0, usable / (BUTTON_SIZE + GAP));
    }
}
