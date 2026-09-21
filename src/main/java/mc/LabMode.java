package mc;

/**
 * Jeden mód labu: co se kreslí do obsahové části a co dělá myš a klávesnice.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ PROČ ROZHRANÍ, A NE DALŠÍ POLOŽKA V ENUMU. Lab měl dva režimy (Blocks
 * a Skin) přepínané dvěma natvrdo napsanými tlačítky a odlišené příznakem
 * `skinMode()`. Třetí mód by znamenal třetí konstantu v rozvržení, třetí
 * větev v hit-testu, třetí v kreslení a čtvrtou v titulku - a čtvrtý mód zas
 * to samé. Tady je mód OBJEKT: ví, jak se jmenuje, jak vypadá jeho ikona,
 * co se v něm kreslí a co dělá vstup. Boční panel (`LabSidebar`) zná jen
 * jejich POČET.
 *
 * **Přidat mód = nová třída, která tohle implementuje, a jeden řádek
 * v seznamu v `TextureLab`.** Nic jiného.
 *
 * ⚠️ KRESLÍ SE VE DVOU PRŮCHODECH: nejdřív tvary a obrázky, pak texty.
 * Je to totéž, co dělá seznam světů a dialog v něm - text jde na obrazovku
 * až po všech tvarech, jinak by prosvítal skrz panely nakreslené po něm.
 * Mód, který to nedodrží a namaluje text v `drawShapes()`, ho uvidí zmizet
 * pod dalším tvarem.
 *
 * Sdílené kusy labu (PixelEditor, paleta, undo, import) tímhle rozhraním
 * NEPROCHÁZEJÍ - zůstávají tam, kde byly. Rozhraní řeší jen navigaci
 * a rozvržení kolem nich.
 * ---------------------------------------------------------------------------
 *
 * Kreslicí metody sahají na GL (přes Renderer2D a TextRenderer); logika módu
 * má být jinde, aby zůstala testovatelná headless - stejné dělení jako
 * u `TextureLabLayout` proti `TextureLab`.
 */
public interface LabMode {

    /** Jméno módu. Ukazuje se v titulku labu a ve stavovém řádku při najetí na ikonu. */
    String title();

    /** Jednořádková nápověda, co se v módu dělá. Řekne se po přepnutí. */
    String hint();

    /**
     * Nakreslí ikonu do bočního panelu.
     *
     * Souřadnice jsou v pixelech OBRAZOVKY s počátkem vlevo DOLE (jako
     * `Renderer2D`), `size` je hrana čtverce. Mód si ikonu kreslí sám, aby
     * panel nemusel znát žádný z nich - viz `LabSidebar`.
     *
     * ⚠️ Volá se UVNITŘ `shapes.begin()/end()` panelu, takže se v ní nesmí
     * dávka vyprázdnit ani změnit stav GL - jinak se rozpadne dávkování,
     * kvůli kterému má lab 8 draw callů místo 422 (viz "Výkon labu").
     */
    void drawIcon(Renderer2D shapes, float left, float bottom, float size);

    /** Mód se právě stal aktivním. */
    default void onEnter() {}

    /** Odchází se z módu - uklidit rozepsané (tah štětcem, rozepsaný blok…). */
    default void onLeave() {}

    /** Čas běží i v labu: animace náhledu, doběh stavové hlášky. */
    default void update(float dt) {}

    /** Tvary a obrázky obsahové části. */
    void drawShapes(TextureLabLayout layout, int screenWidth, int screenHeight,
                    double mouseX, double mouseY);

    /** Texty obsahové části - druhý průchod, viz poznámka u rozhraní. */
    void drawText(TextureLabLayout layout, int screenWidth, int screenHeight,
                  double mouseX, double mouseY);

    /**
     * Zmáčknutí tlačítka myši. Vrací true, když se má lab zavřít.
     *
     * Tlačítko na bočním panelu se sem nedostane - to si vezme panel dřív.
     */
    default boolean press(TextureLabLayout layout, double mouseX, double mouseY,
                          int screenWidth, int screenHeight, boolean left)
    {
        return false;
    }

    /** Tažení se zmáčknutým tlačítkem. */
    default void drag(TextureLabLayout layout, double mouseX, double mouseY,
                      int screenWidth, int screenHeight) {}

    /** Puštění tlačítka. */
    default void release() {}

    /** Klávesa. Vrací true, když ji mód spotřeboval a Esc už nemá zavírat lab. */
    default boolean key(int key, int mods)
    {
        return false;
    }

    /** Napsaný znak (GLFW char callback) - psaní do textových polí. */
    default void typed(int codepoint) {}
}
