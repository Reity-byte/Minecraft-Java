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
 * v seznamu v `TextureLab`.** Nic jiného. Proto se hub na nic neptá podle
 * identity módu (`current() == recipeLab`): kolečko, nápověda, přetažený
 * soubor, úklid i neuložená práce jdou přes metody tady, s výchozí
 * odpovědí "nic". Dřív šestý mód přidaný jedním řádkem ukazoval nápovědu
 * Biomes, ignoroval kolečko a neuvolnil své GL prostředky.
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

    /**
     * Mód se právě stal aktivním.
     *
     * ⚠️ ROZEPSANOU PRÁCI NEZAHAZOVAT. Přepnout se na chvíli jinam (podívat
     * se v Blocks, jak surovina receptu vypadá) je normální postup, takže
     * návrh módu přežívá přepnutí, dokud je lab otevřený. Dřív to každý
     * mód dělal jinak: Recipes návrh držel, Keys a Biomes ho tady tiše
     * přepsaly aktivním nastavením.
     */
    default void onEnter() {}

    /**
     * Odchází se z módu - ukončit, co běží (tah štětcem, čekání na klávesu).
     * Rozepsaný návrh zůstává, viz `onEnter()`; výjimku hlásí `leaveWarning()`.
     */
    default void onLeave() {}

    /**
     * Zahodí odchod z módu něco rozepsaného? Pak vrací, CO (anglicky, jde to
     * do hlášky), jinak null.
     *
     * Hub pak přepnutí napoprvé neudělá, jen řekne "click again to discard".
     * Týká se to jen návrhu bloku - ten drží aktivní dočasný registr, a ten
     * nesmí viset v módu, kde o něm není nic vidět (Recipes by z něj nabízel
     * blok, který neexistuje).
     */
    default String leaveWarning()
    {
        return null;
    }

    /**
     * Je tu rozepsaná práce, která se zavřením labu ztratí?
     *
     * ⚠️ POROVNÁVÁ SE S TÍM, CO PLATÍ, ne s výchozími hodnotami. Dřív titulek
     * Keys a Biomes říkal "built-in"/"custom" podle návrhu, takže po kliknutí
     * na Defaults ukázal "built-in", i když ve hře a na disku byly vlastní
     * klávesy. Pixely atlasu a kůže sem nepatří: žijí ve hře i po zavření
     * labu a mají vlastní "(unsaved)".
     */
    default boolean unsaved()
    {
        return false;
    }

    /**
     * Nápověda dole podle toho, na čem je myš. Hub ji ukazuje, když myš
     * není nad bočním panelem; bez vlastní verze je to `hint()`.
     */
    default String help(TextureLabLayout layout, double mouseX, double mouseY)
    {
        return hint();
    }

    /** Kolečko myši. Mód, který ho nepotřebuje, ho nechá být. */
    default void scroll(double yoffset) {}

    /**
     * Soubor přetažený do okna. Vrací false, když s ním mód neumí nic
     * udělat - hub pak řekne, kde to jde.
     */
    default boolean fileDropped(java.nio.file.Path file)
    {
        return false;
    }

    /** Lab se zavírá - uvolnit GL prostředky módu (náhledy, textury). */
    default void delete() {}

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
