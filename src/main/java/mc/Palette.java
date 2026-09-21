package mc;

/**
 * Barvy uživatelského rozhraní na jednom místě.
 *
 * Proč zvlášť a ne rozsypané po Hud a Menu: HUD i menu mají vypadat jako jedna
 * věc, a to jde uhlídat jen tak, že se odstíny berou ze společné sady. Změna
 * vzhledu je pak úprava tohohle souboru, ne hledání magických čísel v kreslení.
 *
 * Formát je {r, g, b, a} v rozsahu 0-1. Trojice bez alfy jsou taky platné -
 * Renderer2D si u nich domyslí a = 1. Pole jsou static final a nikdo je nesmí
 * přepisovat; kreslení z nich jen čte.
 *
 * ---------------------------------------------------------------------------
 * Barvy jsou PLOCHÉ, ne přechody. Plastičnost dělá bevel - světlá hrana nahoře
 * a vlevo, tmavá dole a vpravo - ne plynulý přechod přes celou plochu. Přechod
 * je právě to, co dělá rozhraní "hladkým"; bevel dělá opak.
 * ---------------------------------------------------------------------------
 */
public final class Palette {

    // --- tlačítka ---

    public static final float[] BUTTON_OUTLINE   = {0f, 0f, 0f, 1f};
    public static final float[] BUTTON_FILL      = {0.42f, 0.42f, 0.42f, 1f};
    public static final float[] BUTTON_HIGHLIGHT = {0.56f, 0.56f, 0.56f, 1f};
    public static final float[] BUTTON_SHADOW    = {0.20f, 0.20f, 0.20f, 1f};

    // Zvýraznění má modravý nádech, ať se pozná i na šedém pozadí.
    public static final float[] BUTTON_HOVER_OUTLINE   = {0f, 0f, 0f, 1f};
    public static final float[] BUTTON_HOVER_FILL      = {0.44f, 0.49f, 0.64f, 1f};
    public static final float[] BUTTON_HOVER_HIGHLIGHT = {0.62f, 0.67f, 0.82f, 1f};
    public static final float[] BUTTON_HOVER_SHADOW    = {0.22f, 0.25f, 0.35f, 1f};

    // --- kontejnery (inventář, crafting) ---

    /** Panel inventáře je světle šedý jako v Minecraftu, ne tmavý jako HUD. */
    public static final float[] CONTAINER_FILL      = {0.78f, 0.78f, 0.78f, 1f};
    public static final float[] CONTAINER_HIGHLIGHT = {1f, 1f, 1f, 1f};
    public static final float[] CONTAINER_SHADOW    = {0.33f, 0.33f, 0.33f, 1f};

    /**
     * Slot je ZAPUŠTĚNÝ, ne vystouplý: tmavá hrana nahoře a vlevo, světlá dole
     * a vpravo - přesně opačně než tlačítko. Bez toho vypadá mřížka jako pole
     * tlačítek místo jako přihrádky.
     */
    public static final float[] SLOT_OUTLINE   = {0.33f, 0.33f, 0.33f, 1f};
    public static final float[] SLOT_FILL      = {0.54f, 0.54f, 0.54f, 1f};
    public static final float[] SLOT_HIGHLIGHT = {1f, 1f, 1f, 1f};
    public static final float[] SLOT_SHADOW    = {0.21f, 0.21f, 0.21f, 1f};

    /** Zesvětlení slotu, přes který se táhne hromádka. Stejné jako v Minecraftu. */
    public static final float[] SLOT_DRAG      = {1f, 1f, 1f, 0.5f};

    // --- panely (hotbar, ladicí výpis) ---

    public static final float[] PANEL_OUTLINE   = {0f, 0f, 0f, 0.85f};
    public static final float[] PANEL_FILL      = {0.04f, 0.04f, 0.04f, 0.62f};
    public static final float[] PANEL_HIGHLIGHT = {0.55f, 0.55f, 0.55f, 0.75f};
    public static final float[] PANEL_SHADOW    = {0.14f, 0.14f, 0.14f, 0.75f};

    // --- hotbar ---

    /** Tenká čára mezi sloty; hotbar tím dostane mřížku místo jedné plochy. */
    public static final float[] SLOT_SEPARATOR = {0.55f, 0.55f, 0.55f, 0.55f};

    /** Rámeček kolem vybraného slotu - v Minecraftu je to světlý obdélník navíc. */
    public static final float[] SELECTOR = {1f, 1f, 1f, 0.95f};

    // --- pozadí ---

    /** Ztmavení dlaždice hlíny v hlavním menu. Násobí se s texturou. */
    public static final float[] BACKGROUND_TINT = {0.25f, 0.25f, 0.25f, 1f};

    /** Pauza: svět zůstává vidět, jen ztmavne. Nahoře o kus míň než dole. */
    public static final float[] DIM_BOTTOM = {0.05f, 0.05f, 0.05f, 0.82f};
    public static final float[] DIM_TOP    = {0.05f, 0.05f, 0.05f, 0.72f};

    /**
     * Modrý filtr přes celou obrazovku, když je kamera pod vodou.
     *
     * Mlha sama nestačí: blízké bloky mají mlhový podíl skoro nulový, takže
     * by zůstaly úplně bez nádechu a scéna by vypadala jako nad hladinou.
     */
    public static final float[] UNDERWATER_TINT = {0.10f, 0.28f, 0.52f, 0.35f};

    /** Ztmavení pod loading screenem, kde ještě není co ukazovat. */
    public static final float[] LOADING_BACKDROP = {0.04f, 0.04f, 0.05f, 0.88f};

    // --- ukazatel postupu ---

    public static final float[] BAR_TRACK = {0.13f, 0.13f, 0.13f, 1f};
    public static final float[] BAR_FILL  = {0.30f, 0.75f, 0.25f, 1f};

    // --- text ---

    public static final float[] TEXT       = {1f, 1f, 1f, 1f};
    public static final float[] TEXT_MUTED = {0.63f, 0.63f, 0.63f, 1f};

    /**
     * Stín textu. V Minecraftu je to čtvrtina barvy písma, ne poloprůhledná
     * čerň - proto je krycí. Kreslí se posunutý přesně o JEDEN GUI pixel.
     */
    public static final float[] TEXT_SHADOW = {0.25f, 0.25f, 0.25f, 1f};

    /**
     * Červená pro chybu, kterou musí uživatel vidět, ne přečíst na konzoli -
     * dnes kolize kláves v Keybind Labu.
     *
     * ⚠️ Chyba se NEHLÁSÍ JEN BARVOU. Vedle obarveného tlačítka stojí i věta,
     * která kolizi pojmenuje - barva sama by pro barvoslepého uživatele
     * nebyla žádná informace a na tmavém pozadí se ztratí i jinak.
     */
    public static final float[] TEXT_WARNING = {1f, 0.44f, 0.40f, 1f};

    /** Výplň prvku v chybovém stavu - tlačítko kolidující klávesy. */
    public static final float[] WARNING_FILL      = {0.52f, 0.20f, 0.18f, 1f};
    public static final float[] WARNING_HIGHLIGHT = {0.70f, 0.30f, 0.28f, 1f};
    public static final float[] WARNING_SHADOW    = {0.28f, 0.10f, 0.09f, 1f};

    /** Prvek, který čeká na vstup - tlačítko, kam se zrovna mačká nová klávesa. */
    public static final float[] ARMED_FILL      = {0.30f, 0.46f, 0.26f, 1f};
    public static final float[] ARMED_HIGHLIGHT = {0.44f, 0.62f, 0.38f, 1f};
    public static final float[] ARMED_SHADOW    = {0.15f, 0.24f, 0.13f, 1f};

    private Palette() {}
}
