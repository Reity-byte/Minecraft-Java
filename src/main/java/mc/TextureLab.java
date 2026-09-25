package mc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.GL_REPEAT;

/**
 * Texture lab: vývojářská obrazovka na úpravy dlaždic atlasu bloků
 * a na zakládání nových bloků.
 *
 * ---------------------------------------------------------------------------
 * Vlevo přehled atlasu (klik vybere dlaždici), uprostřed dlaždice zvětšená
 * jako plátno (levé tlačítko maluje, pravé bere barvu), vpravo živý 3D náhled
 * bloku, který dlaždici používá, a pod tím paleta, HSV posuvníky, hex
 * a tlačítka. Úplně dole barvy celého atlasu.
 *
 * ⚠️ Smysl celé obrazovky je živý náhled přes SKUTEČNÝ shader. Dlaždice
 * 16x16 vypadá na plátně jinak než na bloku: boky jsou ztmavené na 0,6 a 0,8,
 * spodek na 0,5, a vzor, který se na plátně jeví jako nenápadný šum, se na
 * šesti stěnách vedle sebe opakuje jako tapeta. Malovat naslepo a pak
 * restartovat hru, aby bylo vidět, jak to dopadlo, je přesně to, čemu lab
 * zabraňuje. Každá změna pixelu se hned nahraje do TÉŽE textury atlasu, ze
 * které kreslí svět, a kostka v náhledu (BlockPreview) se změní ve stejném
 * framu - viz AtlasEditor a Texture.update().
 *
 * ⚠️ HUB S BOČNÍM PANELEM A ŠESTI MÓDY (LabMode): Blocks, Items, Skin,
 * Recipes, Keys, Biomes. Tahle třída je hub (panel, stavový řádek, zavírání,
 * pojistka neuložené práce) a navíc oba pixelové módy: Blocks maluje
 * dlaždice atlasu bloků, Skin kůži postavy (textures/skin.png). Plátno,
 * paleta, kapátko, HSV, hex, undo i import PNG jsou pro oba TYTÉŽ - liší
 * se jen to, do čeho míří: mřížka dlaždic proti rozbalení kvádrů těla
 * (SkinLayout). Společný základ obou editorů je PixelEditor. Módy se
 * přepínají VIDITELNÝMI ikonami, ne zkratkou: režim, o kterém se nedá
 * dozvědět jinak než z kódu, je skoro totéž jako žádný.
 *
 * Mód Items je Blocks nad ATLASEM PŘEDMĚTŮ (textures/items.png, stejná
 * mřížka): při vstupu se `editor` a `atlas` přepnou na předměty a plátno,
 * paleta, undo i import jedou beze změny. Místo nového bloku má NOVÝ
 * PŘEDMĚT (ItemDraft): jméno, velikost hromádky, nástroj a rychlost; Create
 * uloží items.png a items.json. Náhled je obrázek dlaždice jako v inventáři.
 *
 * V módu Blocks je navíc NOVÝ BLOK: informace o dlaždici nahradí formulář
 * (jméno, tvrdost, pevný, neprůhledný, dlaždice stěn) a klik do atlasu
 * přiřadí dlaždici vybrané stěně. Malování, paleta i náhled fungují dál -
 * náhled ukazuje rozepsaný blok, protože lab po každé změně aktivuje dočasný
 * registr s návrhem (BlockDraft) a postaví náhled znovu. Cancel i zavření
 * labu vrátí původní registr.
 *
 * Všechno bez GL (souřadnice, malování, barvy, soubory, návrh bloku) je
 * v PixelEditor, AtlasEditor, SkinEditor, SkinLayout, TextureLabLayout,
 * AtlasImage, BlockDraft a BlockRegistry; tady je jen kreslení a vstup.
 * ---------------------------------------------------------------------------
 */
public class TextureLab {

    /**
     * Pevný první řádek palety: průhledná (guma), odstíny šedi a pár sytých
     * barev. Druhý řádek jsou nejčastější barvy vybrané dlaždice - pixel-art
     * se maluje hlavně odstíny, které v dlaždici už jsou.
     */
    static final int[] BASIC_COLORS = {
            0x00000000, 0xFF000000, 0xFF3F3F3F, 0xFF7F7F7F, 0xFFBFBFBF, 0xFFFFFFFF,
            0xFFB02E26, 0xFFF9801D, 0xFFFED83D, 0xFF5E7C16, 0xFF3C44AA, 0xFF835432
    };

    /** Kolik kusů nového bloku dostane hráč - jedna plná hromádka. */
    static final int CREATED_STACK = ItemStack.MAX_COUNT;

    private static final int HSV_SEGMENTS = 32;
    private static final float STATUS_SECONDS = 5f;

    private static final float[] GRID_LINE  = {0f, 0f, 0f, 0.22f};
    private static final float[] TILE_LINE  = {0f, 0f, 0f, 0.35f};
    private static final float[] HOVER      = {1f, 1f, 1f, 0.8f};
    private static final float[] USES_COLOR = {1f, 0.85f, 0.2f, 0.9f};
    private static final float[] SWATCH_BASE = {0.45f, 0.45f, 0.45f, 1f};
    private static final float[] GUM_CHECK   = {0.75f, 0.75f, 0.75f, 1f};
    private static final float[] SKY = {WorldRenderer.SKY_R, WorldRenderer.SKY_G, WorldRenderer.SKY_B, 1f};

    /**
     * Do čeho se maluje v pixelovém módu.
     *
     * ⚠️ Zůstalo to enum, protože oba pixelové módy sdílí plátno, paletu,
     * HSV, hex i undo a liší se jen cílem (dlaždice atlasu proti stěně dílu
     * těla). Navenek jsou to ale dva samostatné `LabMode` v bočním panelu -
     * tenhle příznak je vnitřní věc malování, ne navigace.
     */
    public enum Mode { BLOCKS, SKIN, ITEMS }

    /**
     * Editor a textura, do kterých pixelový mód právě maluje: atlas bloků
     * (Blocks), nebo atlas předmětů (Items). Přepíná je PixelMode.onEnter;
     * všechno malování, paleta, undo a import jdou přes ně, takže oba módy
     * sdílí jeden kód.
     */
    private AtlasEditor editor;
    private Texture atlas;

    private final AtlasEditor blockEditor;
    private final Texture blockTexture;
    private final AtlasEditor itemEditor;
    private final Texture itemTexture;

    private final SkinEditor skin;
    private final Texture skinTexture;
    private final Renderer2D shapes;
    private final TextRenderer text;

    private Mode mode = Mode.BLOCKS;

    // Vlastní GL prostředky labu - vznikají s ním a s ním se mažou.
    private final ImageRenderer images = new ImageRenderer();
    private final Texture checker;
    private final BlockPreview preview = new BlockPreview();
    private final SkinPreview skinPreview = new SkinPreview();

    /**
     * Měření času vykreslení labu po fázích - zapíná se klávesou ladicího
     * výpisu (Keybinds DEBUG, výchozí F3), stejnou jako ve hře, a ve všech módech.
     */
    private final LabProfiler profiler = new LabProfiler();

    /** Pojistka proti zavření nebo přepnutí, které by zahodilo rozepsanou práci. */
    private final LabGuard guard = new LabGuard();

    /**
     * Ikony bloků pro mód Recipes - tytéž izometrické kostky jako v hotbaru
     * a ve slotech inventáře, takže blok vypadá v receptu stejně jako ve hře.
     */
    private final BlockIcon blockIcons;

    /** Odkud atlas pochází: true = textures/atlas.png, false = procedurální. */
    private boolean fromFile;

    /** Odkud kůže pochází: true = textures/skin.png, false = procedurální. */
    private boolean skinFromFile;

    /** Odkud atlas předmětů pochází: true = textures/items.png, false = procedurální. */
    private boolean itemsFromFile;

    /** Kolikátý z bloků, které dlaždici používají, je v náhledu. */
    private int previewChoice = 0;

    /**
     * Aktuální barva jako HSV. Drží se zvlášť, ne jen jako přepočet z barvy:
     * u šedé by se jinak odstín ztratil a posuvník odstínu by po každém
     * pohybu sytosti skočil na červenou.
     */
    private float hue = 0f, saturation = 0f, value = 0f;

    private boolean painting = false;
    private TextureLabLayout.Rect draggedSlider = null;

    /** Rozepsaný hex, nebo null, když se nepíše. */
    private StringBuilder hexInput = null;

    private String status = "";
    private float statusLeft = 0f;

    private final int[] palette = new int[TextureLabLayout.SWATCH_COLUMNS * TextureLabLayout.SWATCH_ROWS];

    /** Kolik vzorků palety je platných - dlaždice může mít míň barev, než je míst. */
    private int paletteCount = 0;

    /**
     * Barvy celého atlasu. Přepočítávají se jen když se pixely změní
     * (AtlasEditor.revision) - projít 16 384 pixelů každý frame by bylo zbytečné.
     */
    private int[] globalColors = new int[0];
    private int globalTotal = 0;
    private int globalRevision = -1;
    private Mode globalMode = null;

    /** Pro kterou oblast, revizi a režim platí paleta oblasti. */
    private int paletteRevision = -1;
    private int paletteRegion = -1;
    private Mode paletteMode = null;

    /** Poslední zvýraznění "kde ta barva je" - pro kterou barvu, revizi a režim platí. */
    private boolean[] usesColor = null;
    private int usesArgb = 0;
    private int usesRevision = -1;
    private Mode usesMode = null;

    /** Poslední seznam bloků používajících vybranou dlaždici. */
    private List<Byte> usingBlocks = List.of();
    private int usingTile = -1;
    private BlockRegistry usingRegistry = null;

    /** Odkud se importuje: textures/import.png, nebo poslední soubor přetažený do okna. */
    private Path importFile = Textures.IMPORT_FILE;

    /** Poslední kliknutá stěna kůže - jen pro hlášku, výběr drží SkinEditor. */
    private int hoveredFace = -1;

    // --- nový blok ---

    /** Rozepsaný blok, nebo null v režimu atlasu. */
    private BlockDraft draft = null;

    /** Registr, jaký byl před návrhem - Cancel a zavření labu ho vrátí. */
    private BlockRegistry baseRegistry = null;

    private boolean editingName = false;

    /** Založené bloky, které si ještě nevyzvedl Main (dá je hráči). */
    private final List<Byte> createdBlocks = new ArrayList<>();

    // --- nový předmět ---

    /** Rozepsaný předmět, nebo null. Žádný dočasný registr - viz ItemDraft. */
    private ItemDraft itemDraft = null;

    /** Založené předměty, které si ještě nevyzvedl Main. */
    private final List<Integer> createdItems = new ArrayList<>();

    /**
     * ⚠️ Pole pixelů jsou TA SAMÁ, ze kterých jsou nahrané textury hry -
     * atlas bloků i kůže postavy. Lab do nich maluje přímo a změněný
     * obdélník nahraje do téže textury, takže náhled, hotbar i svět
     * (a postava ve třetí osobě) změnu vidí v tom samém framu.
     */
    public TextureLab(AtlasEditor editor, Texture atlas, boolean fromFile,
                      SkinEditor skin, Texture skinTexture, boolean skinFromFile,
                      AtlasEditor itemEditor, Texture itemTexture, boolean itemsFromFile,
                      Renderer2D shapes, TextRenderer text)
    {
        // ⚠️ Editory dostává lab ZVENKU a Main je drží po celý běh hry. Lab
        // vzniká při každém otevření znovu, ale neuložené pixely zůstávají
        // v poli hry i po jeho zavření - kdyby si lab editory stavěl sám,
        // začínaly by pokaždé s "(unsaved)" = false a prázdným undo, a po
        // F6 -> F6 by nic neříkalo, že je co ukládat.
        this.editor = editor;
        this.atlas = atlas;
        this.blockEditor = editor;
        this.blockTexture = atlas;
        this.itemEditor = itemEditor;
        this.itemTexture = itemTexture;
        this.skin = skin;
        this.skinTexture = skinTexture;
        this.fromFile = fromFile;
        this.skinFromFile = skinFromFile;
        this.itemsFromFile = itemsFromFile;
        this.shapes = shapes;
        this.text = text;

        checker = Texture.fromArgb(new int[]{0xFF9A9A9A, 0xFF6A6A6A, 0xFF6A6A6A, 0xFF9A9A9A},
                2, 2, GL_REPEAT);

        blockIcons = new BlockIcon(atlas, itemTexture);
        itemEditor.select(ItemRegistry.BUILT_IN_TILES);

        selectTile(0);
        setColor(editor.get(0, 0));

        // ⚠️ POŘADÍ V SEZNAMU = POŘADÍ V BOČNÍM PANELU. Další mód se přidá
        // sem a nikam jinam; LabSidebar zná jen počet. Módy vznikají s labem
        // a žijí, dokud je otevřený - proto rozepsaná práce přežije přepnutí.
        modes.add(new PixelMode(Mode.BLOCKS, "Blocks",
                "Blocks: pick an atlas tile on the left, paint it in the middle"));
        modes.add(new PixelMode(Mode.ITEMS, "Items",
                "Items: pick a tile, paint it, New item turns it into an item"));
        modes.add(new PixelMode(Mode.SKIN, "Skin",
                "Skin: pick a body face on the left, paint it in the middle"));
        modes.add(new RecipeLab(this, shapes, text));
        modes.add(new KeybindLab(this, shapes, text));
        modes.add(new BiomeTunerLab(this, shapes, text));

        current().onEnter();
    }

    // ==================================================================
    // módy
    // ==================================================================

    /**
     * Blocks a Skin jako položky bočního panelu.
     *
     * Obě jen přepnou vnitřní příznak `mode` a pak nechají pracovat týž
     * kód, který lab měl odjakživa - sdílené plátno, paletu, undo a import
     * tenhle refaktor vědomě nerozebíral (viz `LabMode`). Navenek jsou to
     * přitom dva samostatné módy, takže se sidebar nemusí ptát, jestli jsou
     * "vlastně jeden".
     */
    private final class PixelMode implements LabMode {

        private final Mode which;
        private final String title;
        private final String hint;

        PixelMode(Mode which, String title, String hint)
        {
            this.which = which;
            this.title = title;
            this.hint = hint;
        }

        @Override public String title() { return title; }
        @Override public String hint()  { return hint; }

        @Override
        public void drawIcon(Renderer2D shapes, float left, float bottom, float size)
        {
            switch(which)
            {
                case BLOCKS -> drawCubeIcon(shapes, left, bottom, size);
                case ITEMS -> drawStickIcon(shapes, left, bottom, size);
                case SKIN -> drawSkinIcon(shapes, left, bottom, size);
            }
        }

        @Override
        public void onEnter()
        {
            mode = which;

            // Blocks a Items malují do jiného atlasu - přepne se editor
            // i textura a zbytek pixelového kódu o tom neví.
            editor = which == Mode.ITEMS ? itemEditor : blockEditor;
            atlas = which == Mode.ITEMS ? itemTexture : blockTexture;

            if(which == Mode.BLOCKS)
            {
                refreshPreview();
            }
        }

        @Override
        public void onLeave()
        {
            // Rozepsaný blok patří k atlasu; nechat ho viset v jiném módu
            // by znamenalo aktivní dočasný registr, o kterém není nic vidět.
            // Sem se ale dojde až po potvrzení - viz leaveWarning().
            cancelBlock();
            itemDraft = null;
            editingName = false;
            release();

            // Rozepsaný hex se zahodí: po návratu by si pole dál bralo klávesy
            // a F6 by lab nezavřel, aniž by bylo vidět proč.
            hexInput = null;
        }

        /**
         * Rozepsaný blok je jediná práce v labu, kterou přepnutí módu
         * zahodí (viz `LabMode.leaveWarning()`), takže se na ni ptá.
         */
        @Override
        public String leaveWarning()
        {
            return draft != null ? "The new block is not created yet"
                    : itemDraft != null ? (itemDraft.isEdit() ? "The item edit is not saved yet"
                    : "The new item is not created yet") : null;
        }

        /**
         * Neuložený je tu jen rozepsaný blok. Pixely se nepočítají - zůstávají
         * ve hře i po zavření labu a "(unsaved)" v titulku o nich mluví dál.
         */
        @Override
        public boolean unsaved()
        {
            return (which == Mode.BLOCKS && draft != null) || (which == Mode.ITEMS && itemDraft != null);
        }

        @Override
        public String help(TextureLabLayout layout, double mouseX, double mouseY)
        {
            return pixelHelp(layout, mouseX, mouseY);
        }

        @Override
        public boolean fileDropped(Path file)
        {
            importImage();
            return true;
        }

        @Override public void update(float dt) { updatePixel(dt); }

        @Override
        public void drawShapes(TextureLabLayout layout, int screenWidth, int screenHeight,
                               double mouseX, double mouseY)
        {
            drawPixelContent(layout, screenWidth, screenHeight, mouseX, mouseY);
        }

        @Override
        public void drawText(TextureLabLayout layout, int screenWidth, int screenHeight,
                             double mouseX, double mouseY)
        {
            drawPixelTexts(layout, screenWidth, screenHeight, mouseX, mouseY);
        }

        @Override
        public boolean press(TextureLabLayout layout, double mouseX, double mouseY,
                             int screenWidth, int screenHeight, boolean left)
        {
            return pressPixel(layout, mouseX, mouseY, screenWidth, screenHeight, left);
        }

        @Override
        public void drag(TextureLabLayout layout, double mouseX, double mouseY,
                         int screenWidth, int screenHeight)
        {
            dragPixel(layout, mouseX, mouseY, screenWidth, screenHeight);
        }

        @Override public void release() { TextureLab.this.release(); }
        @Override public boolean key(int key, int mods) { return keyPixel(key, mods); }
        @Override public void typed(int codepoint) { typedPixel(codepoint); }
    }

    /**
     * Ikona módu Blocks: izometrická kostka ze tří kosodélníků.
     *
     * ⚠️ Kreslí se přes `Renderer2D`, ne přes `BlockIcon`. Ten má vlastní
     * shader a VAO, takže by uprostřed dávky bočního panelu musel dávku
     * vyprázdnit - a lab má 8 draw callů místo 422 právě proto, že se
     * nevyprazdňuje (viz "Výkon labu"). Odstíny stěn jsou tytéž konstanty
     * jako v `ChunkMesh`, aby ikona seděla s tím, jak blok vypadá ve světě.
     */
    private static void drawCubeIcon(Renderer2D shapes, float left, float bottom, float size)
    {
        float cx = left + size / 2f;
        float cy = bottom + size / 2f;
        float half = size * 0.42f;
        float quarter = half / 2f;

        float[] top   = {0.36f, 0.55f, 0.23f, 1f};   // tráva shora
        float[] leftF = {0.44f, 0.34f, 0.23f, 1f};   // hlína, bok +Z (0,8)
        float[] rightF = {0.33f, 0.26f, 0.17f, 1f};  // hlína, bok +X (0,6)

        shapes.fillQuad(cx, cy + half, cx - half, cy + quarter,
                cx, cy, cx + half, cy + quarter, top);
        shapes.fillQuad(cx - half, cy + quarter, cx - half, cy - quarter,
                cx, cy - half, cx, cy, leftF);
        shapes.fillQuad(cx, cy, cx, cy - half,
                cx + half, cy - quarter, cx + half, cy + quarter, rightF);
    }

    /** Ikona módu Items: šikmý klacek ze čtverečků, jako obrázek předmětu. */
    private static void drawStickIcon(Renderer2D shapes, float left, float bottom, float size)
    {
        float unit = size / 8f;
        float[] light = {0.56f, 0.42f, 0.23f, 1f};
        float[] dark = {0.37f, 0.26f, 0.13f, 1f};

        for(int i = 0; i < 6; i++)
        {
            shapes.fillRect(left + (1 + i) * unit, bottom + (1 + i) * unit, unit, unit, light);
            shapes.fillRect(left + (2 + i) * unit, bottom + (1 + i) * unit, unit, unit, dark);
        }
    }

    /** Ikona módu Skin: hlava, trup a dvě ruce z obdélníků. */
    private static void drawSkinIcon(Renderer2D shapes, float left, float bottom, float size)
    {
        float unit = size / 8f;
        float[] skinColor = {0.78f, 0.61f, 0.49f, 1f};
        float[] shirt = {0.12f, 0.61f, 0.61f, 1f};

        shapes.fillRect(left + 2.5f * unit, bottom + 5.5f * unit, 3 * unit, 2.5f * unit, skinColor);
        shapes.fillRect(left + 2.5f * unit, bottom + 2f * unit, 3 * unit, 3.5f * unit, shirt);
        shapes.fillRect(left + 1f * unit, bottom + 2.5f * unit, 1.5f * unit, 3 * unit, skinColor);
        shapes.fillRect(left + 5.5f * unit, bottom + 2.5f * unit, 1.5f * unit, 3 * unit, skinColor);
        shapes.fillRect(left + 2.5f * unit, bottom + 0.5f * unit, 3 * unit, 1.5f * unit, skinColor);
    }

    // ==================================================================
    // vstup a kreslení hubu
    // ==================================================================

    /**
     * Zmáčknutí myši. Vrací true, když se má lab zavřít.
     *
     * Boční panel má přednost před módem: klik na ikonu přepíná, i když má
     * mód rozepsané cokoliv - návrh přežije, jen rozepsaný blok se napoprvé
     * nepustí (viz `selectMode`). Tlačítko Close módu jde přes tutéž
     * pojistku jako Esc (`mayClose`).
     */
    public boolean press(double mouseX, double mouseY, int screenWidth, int screenHeight, boolean left)
    {
        TextureLabLayout layout = new TextureLabLayout(screenWidth, screenHeight);

        if(left)
        {
            int button = LabSidebar.buttonAt(layout.panelGuiX(mouseX), layout.panelGuiY(mouseY),
                    modes.size());

            if(button >= 0)
            {
                selectMode(button);
                return false;
            }
        }

        return current().press(layout, mouseX, mouseY, screenWidth, screenHeight, left)
                && mayClose();
    }

    /**
     * Smí se lab zavřít? Když má některý mód neuloženou práci, napoprvé ne:
     * řekne se, kde co je, a zavře až další pokus do pár sekund (LabGuard).
     *
     * ⚠️ Zavřením se návrhy Recipes, Keys a Biomes ztratí - lab vzniká při
     * každém otevření znovu. Pixely se neztratí (drží je Main), proto se na
     * ně pojistka neptá.
     */
    private boolean mayClose()
    {
        String unsaved = LabGuard.unsavedModes(modes);

        if(guard.allow("close", unsaved))
        {
            return true;
        }

        say("Unsaved work in " + unsaved + " - close again to discard it");
        return false;
    }

    public void drag(double mouseX, double mouseY, int screenWidth, int screenHeight)
    {
        current().drag(new TextureLabLayout(screenWidth, screenHeight),
                mouseX, mouseY, screenWidth, screenHeight);
    }

    public void releaseMouse()
    {
        current().release();
    }

    /** Co se s klávesou v labu stalo - viz key(). */
    public enum KeyResult {
        /** Vzal si ji mód (psaní, Ctrl+Z, čekání na klávesu v Keybind Labu). */
        CONSUMED,
        /** Esc nebo klávesa labu, kterou si nevzal mód: lab se zavře. */
        CLOSE,
        /** Nikdo ji nechtěl - Main ji smí použít (celá obrazovka). */
        UNUSED
    }

    /**
     * Klávesa.
     *
     * ⚠️ O ZAVŘENÍ ROZHODUJE HUB, NE MÓD. Mód jen řekne, jestli si klávesu
     * vzal (`LabMode.key`); zavírá se, až když si ji nevzal nikdo a je to
     * Esc nebo klávesa labu. Dřív vracel mód rovnou "zavři" a dvě jeho
     * implementace si to vyložily opačně - `PixelMode` vracel true na Esc
     * (tedy "zavři"), `RecipeLab` na Delete (chtěl říct "spotřebováno"),
     * takže Delete v módu Recipes vymazal mřížku A ZAVŘEL LAB, kdežto Esc
     * v něm lab nezavíral vůbec. Tady je ta otázka jen jedna a ptá se na
     * ni jedno místo.
     *
     * Třetí výsledek UNUSED je kvůli celé obrazovce: F11 má přepínat
     * odkudkoliv, ale v Keybind Labu při čekání na klávesu si ji mód musí
     * vzít (jinak by nešla přiřadit) - proto to Main pozná až odsud.
     *
     * Nad `route()` jsou ještě dvě věci hubu: klávesa ladicího výpisu
     * (měření labu) a pojistka zavření. Obě jen na skutečné zmáčknutí -
     * držená F3 by měření blikala a opakovaný Esc by "potvrdil" varování,
     * které uživatel ještě nestihl přečíst.
     *
     * @param repeat GLFW_REPEAT (držená klávesa), ne první zmáčknutí
     */
    public KeyResult key(int key, int mods, boolean repeat)
    {
        KeyResult result = route(current(), key, mods);

        if(result == KeyResult.UNUSED
                && Keybinds.active().actionFor(key) == Keybinds.Action.DEBUG)
        {
            if(!repeat)
            {
                profiler.toggle();
                say(profiler.enabled() ? "Frame timing on" : "Frame timing off");
            }
            return KeyResult.CONSUMED;
        }

        if(result == KeyResult.CLOSE && (repeat || !mayClose()))
        {
            return KeyResult.CONSUMED;
        }

        return result;
    }

    /**
     * Celé rozhodnutí hubu jako statická funkce, aby šla konvence
     * `LabMode.key()` otestovat se skutečnými módy bez GL (LabModesTest).
     */
    static KeyResult route(LabMode mode, int key, int mods)
    {
        if(mode.key(key, mods))
        {
            return KeyResult.CONSUMED;
        }

        // Klávesa labu zavírá lab, ať je přebindovaná kamkoliv; Esc platí
        // vždycky, aby se z labu šlo dostat i s rozbitým keybinds.json.
        if(key == GLFW_KEY_ESCAPE
                || Keybinds.active().actionFor(key) == Keybinds.Action.LAB)
        {
            return KeyResult.CLOSE;
        }

        return KeyResult.UNUSED;
    }

    /** Zavře tahle klávesa lab? Zkratka nad route() pro testy. */
    static boolean closesLab(LabMode mode, int key, int mods)
    {
        return route(mode, key, mods) == KeyResult.CLOSE;
    }

    public void typed(int codepoint)
    {
        current().typed(codepoint);
    }

    /**
     * Kolečko myši. Recipes jím roluje přehledem bloků, Biomes přepíná biom;
     * ostatní módy ho ignorují, takže se nic neděje.
     */
    public void scroll(double yoffset)
    {
        current().scroll(yoffset);
    }

    public void update(float dt)
    {
        if(statusLeft > 0f)
        {
            statusLeft = Math.max(0f, statusLeft - dt);
        }

        guard.update(dt);
        current().update(dt);
    }

    /**
     * Jeden frame labu: panel, boční pruh, obsah aktivního módu a nakonec
     * texty.
     *
     * ⚠️ TEXT AŽ PO VŠECH TVARECH, ve dvou průchodech. Kdyby si mód kreslil
     * text mezi svoje tvary, přebil by ho další panel nakreslený po něm -
     * je to tentýž důvod, proč to takhle dělá seznam světů s potvrzovacím
     * dialogem.
     */
    public void render(int screenWidth, int screenHeight, double mouseX, double mouseY)
    {
        profiler.beginFrame();

        TextureLabLayout layout = new TextureLabLayout(screenWidth, screenHeight);
        int scale = layout.scale();

        // --- panel a boční pruh ---
        profiler.start(LabProfiler.SHAPES);
        shapes.begin(screenWidth, screenHeight);

        shapes.bevelRect(layout.panelLeft(),
                screenHeight - layout.top() - TextureLabLayout.HEIGHT * scale,
                TextureLabLayout.WIDTH * scale, TextureLabLayout.HEIGHT * scale, scale,
                Palette.PANEL_OUTLINE, Palette.CONTAINER_FILL,
                Palette.CONTAINER_HIGHLIGHT, Palette.CONTAINER_SHADOW);

        drawSidebar(layout, screenHeight, mouseX, mouseY);

        shapes.end();
        profiler.stop(LabProfiler.SHAPES);

        // Obsah módu se měří jako "shapes" celý. Mód, který si měří jemněji
        // (Blocks a Skin: upload, paleta, obrázky, náhled), vnořené fáze
        // odečte od téhle - LabProfiler fáze vnořovat umí. Dřív Recipes,
        // Keys a Biomes neležely v žádné fázi a měření o nich mlčelo.
        profiler.start(LabProfiler.SHAPES);
        current().drawShapes(layout, screenWidth, screenHeight, mouseX, mouseY);
        profiler.stop(LabProfiler.SHAPES);

        profiler.start(LabProfiler.TEXT);
        current().drawText(layout, screenWidth, screenHeight, mouseX, mouseY);
        drawStatusAndHelp(layout, screenWidth, screenHeight, mouseX, mouseY);
        profiler.stop(LabProfiler.TEXT);

        profiler.endFrame();
    }

    /**
     * Boční pruh: jedno tlačítko na mód, aktivní zapuštěný a orámovaný.
     *
     * ⚠️ NEZNÁ ŽÁDNÝ KONKRÉTNÍ MÓD. Jen projde seznam, řekne si u `LabSidebar`
     * o obdélník a nechá mód nakreslit vlastní ikonu. Proto přidání módu
     * nesahá sem ani do `LabSidebar`.
     */
    private void drawSidebar(TextureLabLayout layout, int screenHeight,
                             double mouseX, double mouseY)
    {
        for(int i = 0; i < modes.size(); i++)
        {
            TextureLabLayout.Rect r = LabSidebar.button(i);
            float x = layout.panelScreenX(r);
            float y = layout.screenBottom(r, screenHeight);
            float w = r.w() * layout.scale(), h = r.h() * layout.scale();

            if(i == currentMode)
            {
                // Aktivní mód je ZAPUŠTĚNÝ (světlá hrana dole a vpravo) a
                // orámovaný - stejné rozlišení jako u slotů inventáře proti
                // tlačítkům, takže je na první pohled poznat, který mód běží.
                shapes.bevelRect(x, y, w, h, layout.scale(), Palette.PANEL_OUTLINE,
                        Palette.SLOT_FILL, Palette.CONTAINER_SHADOW, Palette.CONTAINER_HIGHLIGHT);
                shapes.border(x, y, w, h, layout.scale(), Palette.SELECTOR);
            }
            else
            {
                boolean hovered = LabSidebar.button(i)
                        .contains(layout.panelGuiX(mouseX), layout.panelGuiY(mouseY));

                shapes.bevelRect(x, y, w, h, layout.scale(),
                        hovered ? Palette.BUTTON_HOVER_OUTLINE : Palette.PANEL_OUTLINE,
                        hovered ? Palette.BUTTON_HOVER_FILL : Palette.BUTTON_FILL,
                        hovered ? Palette.BUTTON_HOVER_HIGHLIGHT : Palette.CONTAINER_HIGHLIGHT,
                        hovered ? Palette.BUTTON_HOVER_SHADOW : Palette.CONTAINER_SHADOW);
            }

            TextureLabLayout.Rect icon = LabSidebar.icon(i);
            modes.get(i).drawIcon(shapes, layout.panelScreenX(icon),
                    layout.screenBottom(icon, screenHeight), icon.w() * layout.scale());
        }
    }

    /**
     * Stavový řádek a nápověda dole - společné všem módům, takže je kreslí
     * hub, ne každý mód znovu.
     */
    private void drawStatusAndHelp(TextureLabLayout layout, int screenWidth, int screenHeight,
                                   double mouseX, double mouseY)
    {
        int scale = layout.scale();
        text.begin(screenWidth, screenHeight, scale);

        // Zapnuté měření mluví na týchž dvou řádcích jako stav a nápověda -
        // je to dočasný ladicí režim, ne trvalá část rozhraní.
        //
        // ⚠️ HLÁŠKA MÁ PŘEDNOST před prvním řádkem měření. Dřív měření
        // přebilo oba řádky vždycky, takže se zapnutým F3 nebylo vidět
        // "Could not write …" ani "Press a key for …" v Keys. Nápovědu
        // zakrývá dál: ta se dá přečíst po vypnutí, hláška by zmizela.
        String[] measured = profiler.enabled() ? profiler.lines() : null;

        if(statusLeft > 0f)
        {
            label(layout, 8, TextureLabLayout.STATUS_Y,
                    fit(status, TextureLabLayout.CONTENT_WIDTH - 16, scale));
        }
        else if(measured != null)
        {
            label(layout, 8, TextureLabLayout.STATUS_Y,
                    fit(measured[0], TextureLabLayout.CONTENT_WIDTH - 16, scale));
        }

        String bottom = measured != null ? measured[1] : hubHelp(layout, mouseX, mouseY);
        text.draw(fit(bottom, TextureLabLayout.CONTENT_WIDTH - 16, scale),
                layout.textLeft(8), layout.textTop(TextureLabLayout.HELP_Y), Palette.TEXT_MUTED);

        text.end();
    }

    /**
     * Nápověda dole. Najetí na ikonu módu řekne jeho jméno - to je jediné
     * místo, kde se jméno módu dá přečíst, protože do 28 pixelů široké ikony
     * se popisek nevejde (viz LabSidebar).
     */
    private String hubHelp(TextureLabLayout layout, double mouseX, double mouseY)
    {
        int hovered = LabSidebar.buttonAt(layout.panelGuiX(mouseX), layout.panelGuiY(mouseY),
                modes.size());

        if(hovered >= 0)
        {
            return modes.get(hovered).title() + " - " + modes.get(hovered).hint();
        }

        return current().help(layout, mouseX, mouseY);
    }

    /** Měřič fází vykreslení - pro sondy, které lab kreslí mimo hru. */
    LabProfiler profiler()
    {
        return profiler;
    }

    /** Pochází atlas teď ze souboru? Main to ukazuje v ladicím výpisu. */
    public boolean itemsFromFile()
    {
        return itemsFromFile;
    }

    public boolean fromFile()
    {
        return fromFile;
    }

    /** Pochází kůže teď ze souboru? */
    public boolean skinFromFile()
    {
        return skinFromFile;
    }

    // ------------------------------------------------------------------
    // hub: seznam módů a boční panel
    // ------------------------------------------------------------------

    /**
     * Všechny módy labu, v pořadí, v jakém je vypíše boční panel.
     *
     * ⚠️ PŘIDAT MÓD = TŘÍDA + JEDEN ŘÁDEK TADY. `LabSidebar` ani jeho test
     * se nemění - panel zná jen počet módů a nechává každý, aby si nakreslil
     * svou ikonu sám.
     */
    private final List<LabMode> modes = new ArrayList<>();

    private int currentMode = 0;

    private LabMode current()
    {
        return modes.get(currentMode);
    }

    /**
     * Přepnutí módu.
     *
     * Odcházející mód dostane `onLeave()` (zruší rozepsaný blok, ukončí tah),
     * příchozí `onEnter()`. Bez toho by v jiném módu visel dočasný registr
     * s návrhem bloku, o kterém není nic vidět.
     *
     * Rozepsané návrhy (recept, klávesy, tuning) přepnutí přežijí. Když by
     * odchod přece něco zahodil (rozepsaný blok), napoprvé se nepřepne
     * a řekne se proč - stejně jako Esc v rozepsaném bloku.
     */
    void selectMode(int index)
    {
        if(index < 0 || index >= modes.size() || index == currentMode)
        {
            return;
        }

        String loses = current().leaveWarning();

        if(!guard.allow("mode " + index, loses))
        {
            say(loses + " - click " + modes.get(index).title() + " again to discard it");
            return;
        }

        current().onLeave();
        currentMode = index;
        current().onEnter();
        say(current().hint());
    }

    private boolean skinMode()
    {
        return mode == Mode.SKIN;
    }

    private boolean itemsMode()
    {
        return mode == Mode.ITEMS;
    }

    /** Editor, do kterého se právě maluje. */
    private PixelEditor active()
    {
        return skinMode() ? skin : editor;
    }

    /** Textura, kterou právě editor upravuje. */
    private Texture activeTexture()
    {
        return skinMode() ? skinTexture : atlas;
    }

    /**
     * Bloky založené od minulého dotazu - Main je dá hráči do inventáře.
     * Každý vrací jen jednou.
     */
    public List<Byte> takeCreatedBlocks()
    {
        List<Byte> taken = new ArrayList<>(createdBlocks);
        createdBlocks.clear();
        return taken;
    }

    /** Předměty založené od minulého dotazu - Main je dá hráči. Každý jen jednou. */
    public List<Integer> takeCreatedItems()
    {
        List<Integer> taken = new ArrayList<>(createdItems);
        createdItems.clear();
        return taken;
    }

    // ------------------------------------------------------------------
    // stav
    // ------------------------------------------------------------------

    private void selectTile(int tile)
    {
        editor.select(tile);
        previewChoice = 0;

        // V režimu nového bloku ukazuje náhled pořád návrh. Items náhled
        // bloku nemají (kreslí obrázek dlaždice).
        if(draft == null && mode == Mode.BLOCKS)
        {
            refreshPreview();
        }
    }

    private void refreshPreview()
    {
        List<Byte> blocks = blocksUsingSelected();
        preview.show(blocks.isEmpty() ? World.AIR : blocks.get(previewChoice % blocks.size()));
    }

    /**
     * ⚠️ Barva je SPOLEČNÁ pro oba režimy - obě záložky ji dostanou. Odstín
     * vytažený kapátkem z kamene tak jde rovnou použít na kalhoty, což je
     * přesně to, proč je editace kůže v labu a ne zvlášť.
     */
    private void setColor(int argb)
    {
        blockEditor.setColor(argb);
        itemEditor.setColor(argb);
        skin.setColor(argb);

        float[] hsv = AtlasEditor.toHsv(argb);

        // U šedé nemá odstín smysl a u černé ani sytost - necháme původní.
        if(hsv[1] > 0f && hsv[2] > 0f)
        {
            hue = hsv[0];
        }
        if(hsv[2] > 0f)
        {
            saturation = hsv[1];
        }
        value = hsv[2];
    }

    private void applyHsv()
    {
        // Z průhledné (gumy) přechod na HSV znamená "chci barvu" - plně krycí.
        //
        // ⚠️ OBA EDITORY, jako setColor(): barva je společná. Dřív se tu
        // nastavoval jen atlas, takže v módu Skin posuvníky hýbaly jen
        // značkou a štětec, vzorek i hex zůstaly na staré barvě.
        int alpha = active().color() >>> 24;
        int argb = AtlasEditor.hsv(hue, saturation, value, alpha == 0 ? 0xFF : alpha);
        blockEditor.setColor(argb);
        itemEditor.setColor(argb);
        skin.setColor(argb);
    }

    void say(String message)
    {
        status = message;
        statusLeft = STATUS_SECONDS;
    }

    private void save()
    {
        if(skinMode())
        {
            saveSkin();
            return;
        }

        if(itemsMode())
        {
            saveItems();
            return;
        }

        if(AtlasImage.save(editor.pixels(), Textures.ATLAS_FILE))
        {
            editor.markSaved();
            fromFile = true;
            say("Saved " + SafeFiles.shown(Textures.ATLAS_FILE));
        }
        else
        {
            say(SafeFiles.writeFailed(Textures.ATLAS_FILE));
        }
    }

    /**
     * ⚠️ Kůže jde do VLASTNÍHO souboru a bez překlápění řádků. Atlas se
     * překlápí (jeho řádek 0 je dole), kůže ne - její pole je rovnou
     * v pořadí obrázku, viz SkinLayout. Formátu atlasu se nesahá.
     */
    private void saveSkin()
    {
        if(AtlasImage.save(skin.pixels(), Textures.SKIN_FILE, SkinEditor.SIZE, false))
        {
            skin.markSaved();
            skinFromFile = true;
            say("Saved " + SafeFiles.shown(Textures.SKIN_FILE));
        }
        else
        {
            say(SafeFiles.writeFailed(Textures.SKIN_FILE));
        }
    }

    /** Atlas předmětů do textures/items.png - stejný formát jako atlas bloků. */
    private void saveItems()
    {
        if(AtlasImage.save(itemEditor.pixels(), ItemTextures.FILE))
        {
            itemEditor.markSaved();
            itemsFromFile = true;
            say("Saved " + SafeFiles.shown(ItemTextures.FILE));
        }
        else
        {
            say(SafeFiles.writeFailed(ItemTextures.FILE));
        }
    }

    /** Zpátky k tomu, co je na disku: uložený PNG, jinak procedurální obrázek. */
    private void revert()
    {
        if(skinMode())
        {
            Textures.SkinPixels source = Textures.skinPixels(Textures.SKIN_FILE);
            skin.replaceAll(source.pixels());
            skinFromFile = source.fromFile();
            say(skinFromFile ? "Reverted to saved skin.png" : "Reverted to the built-in skin");
            return;
        }

        if(itemsMode())
        {
            Textures.AtlasPixels items = ItemTextures.pixels(ItemTextures.FILE, ItemRegistry.active());
            itemEditor.replaceAll(items.pixels());
            itemsFromFile = items.fromFile();
            say(itemsFromFile ? "Reverted to saved items.png" : "Reverted to the built-in items");
            return;
        }

        Textures.AtlasPixels source = Textures.atlasPixels(Textures.ATLAS_FILE);
        editor.replaceAll(source.pixels());
        fromFile = source.fromFile();
        say(fromFile ? "Reverted to saved PNG" : "Reverted to procedural");
    }

    /** Import hotového PNG do editoru. Při chybě zůstane obrázek, jak byl. */
    private void importImage()
    {
        if(skinMode())
        {
            say(AtlasImage.importInto(skin, importFile, SkinEditor.SIZE, false, "skin"));
            return;
        }

        if(itemsMode())
        {
            say(AtlasImage.importItems(itemEditor, importFile));
            return;
        }

        say(AtlasImage.importInto(editor, importFile));

        if(draft == null)
        {
            refreshPreview();
        }
    }

    /**
     * Soubor přetažený do okna (Main ho předá z GLFW). Naimportuje se hned:
     * přetažení je samo o sobě jasný pokyn a zapamatuje se pro tlačítko Import.
     */
    public void fileDropped(String path)
    {
        try
        {
            importFile = Path.of(path);
        }
        catch(RuntimeException e)
        {
            say("Can't use that file path");
            return;
        }

        // ⚠️ Import jen tam, kde je vidět: v Recipes, Keys ani Biomes plátno
        // není, změněný obdélník se na grafiku nenahrával a příští Save by
        // zapsal obrázek, který uživatel nikdy neviděl (a do atlasu, nebo do
        // kůže podle toho, který pixelový mód byl otevřený naposledy).
        // Rozhoduje o tom mód sám (LabMode.fileDropped), výchozí odpověď je ne.
        if(!current().fileDropped(importFile))
        {
            say("Import works in Blocks, Items and Skin - switch there and press Import PNG");
        }
    }

    private void updatePixel(float dt)
    {
        // Hláška se odpočítává jednou, v update() hubu - dřív tu byl ještě
        // druhý odpočet a hlášky v Blocks a Skin mizely po 2,5 s místo 5 s.
        preview.update(dt);
        skinPreview.update(dt);
    }

    // ------------------------------------------------------------------
    // nový blok
    // ------------------------------------------------------------------

    private void startBlock()
    {
        BlockRegistry registry = BlockRegistry.active();

        if(registry.isFull())
        {
            say("No free block id - the lab has used all "
                    + (BlockRegistry.LAST_ID - BlockRegistry.FIRST_ID + 1));
            return;
        }

        baseRegistry = registry;
        draft = new BlockDraft(editor.tile());
        editingName = true;
        showDraft();
        say("New block: type a name, pick or paint tiles, then Create");
    }

    /**
     * Aktivuje dočasný registr s návrhem a postaví náhled znovu.
     *
     * ⚠️ Náhled jde celou cestou jako hra (BlockPreview), takže se návrh musí
     * opravdu tvářit jako zaregistrovaný blok - BlockAtlas, World.isOpaque
     * i světlo se ptají aktivního registru. Id návrhu je to, které blok po
     * Create dostane; ve světě ho zatím nic nenese, takže dočasný registr
     * hře za labem nic nezmění.
     */
    private void showDraft()
    {
        BlockDef def = draft.toDef(baseRegistry);
        BlockRegistry.activate(baseRegistry.with(def));
        preview.show(def.id(), true);
    }

    private void cancelBlock()
    {
        if(draft == null)
        {
            return;
        }

        BlockRegistry.activate(baseRegistry);
        draft = null;
        baseRegistry = null;
        editingName = false;
    }

    /** Stěně dá čerstvou buňku atlasu s kopií její dosavadní dlaždice. */
    private void newTile()
    {
        int free = BlockDraft.freeTileFor(baseRegistry, editor.pixels(), draft.tiles);

        if(free < 0)
        {
            say("Atlas is full - reuse an existing tile");
            return;
        }

        // Prázdná buňka má přednost; když žádná není, přepíše se volná buňka
        // s něčím namalovaným - a řekne se to, jde to vrátit.
        boolean overwrites = !Textures.tileEmpty(editor.pixels(), free);

        editor.copyTile(draft.tiles[draft.activeFace], free);
        draft.tiles[draft.activeFace] = free;
        selectTile(free);
        showDraft();
        say(overwrites
                ? "Tile " + free + " had unused paint - replaced (Ctrl+Z brings it back)"
                : "Tile " + free + " is new - paint it on the canvas");
    }

    /**
     * Založí blok: uloží atlas i blocks.json, aktivuje nový registr a nechá
     * blok vyzvednout Mainu, který ho dá hráči.
     *
     * ⚠️ NEJDŘÍV ATLAS, PAK BLOKY. Nové dlaždice existují jen v pixelech
     * atlasu; kdyby se uložil blocks.json a atlas ne, příští start by blok
     * znal, ale jeho dlaždice by byly prázdné. Když selže atlas, blok se
     * nezaloží vůbec.
     */
    private void createBlock()
    {
        String problem = draft.problem(baseRegistry);

        if(problem != null)
        {
            say(problem);
            return;
        }

        BlockDef def = draft.toDef(baseRegistry);
        BlockRegistry created = baseRegistry.with(def);

        if(!AtlasImage.save(editor.pixels(), Textures.ATLAS_FILE))
        {
            say(SafeFiles.writeFailed(Textures.ATLAS_FILE) + " - block not created");
            return;
        }

        editor.markSaved();
        fromFile = true;

        if(!created.save(BlockRegistry.FILE))
        {
            say(SafeFiles.writeFailed(BlockRegistry.FILE) + " - block not created");
            return;
        }

        BlockRegistry.activate(created);
        draft = null;
        baseRegistry = null;
        editingName = false;
        createdBlocks.add(def.id());

        preview.show(def.id(), true);
        say("Created " + def.name() + " (id " + def.id() + ") - " + CREATED_STACK + " go to your inventory");
    }

    // ------------------------------------------------------------------
    // nový předmět
    // ------------------------------------------------------------------

    private void startItem()
    {
        if(ItemRegistry.active().isFull())
        {
            say("No free item id - the lab has used all "
                    + (ItemRegistry.LAST_ID - ItemRegistry.FIRST_ID + 1));
            return;
        }

        // Vestavěné dlaždice (klacek, uhlí) nový předmět nedostane - začne
        // na čerstvé, ať se klacek omylem nepřemaluje.
        int tile = editor.tile();

        if(tile < ItemRegistry.BUILT_IN_TILES)
        {
            tile = ItemDraft.freeTile(ItemRegistry.active(), itemEditor.pixels(), -1);

            if(tile < 0)
            {
                say("Item atlas is full - no free tile for a new item");
                return;
            }

            selectTile(tile);
        }

        itemDraft = new ItemDraft(tile);
        editingName = true;
        say("New item: type a name, paint the tile, set stack and tool, then Create");
    }

    /** Předmět z labu, který má vybranou dlaždici - ten jde upravit; jinak null. */
    private ItemDef labItemOnSelectedTile()
    {
        for(ItemDef def : ItemRegistry.active().labItems())
        {
            if(def.tile() == editor.tile())
            {
                return def;
            }
        }

        return null;
    }

    /** Otevře formulář s existujícím předmětem z labu. */
    private void editItem(ItemDef def)
    {
        itemDraft = new ItemDraft(def);
        editingName = false;
        say("Editing " + def.name() + " - change it, then Save item (or Delete item)");
    }

    /**
     * Smaže upravovaný předmět - napoprvé jen varuje (LabGuard), smaže až
     * druhý klik. Hromádky v uložených světech zůstanou jako neznámý předmět
     * (šachovnice); id se znovu nepoužije, takže se nikdy nepromění v jiný.
     * Recepty, které ho potřebují, se přestanou nabízet (RecipeBook se
     * znovu načte a neplatné přeskočí).
     */
    private void deleteItem()
    {
        ItemDef def = ItemRegistry.active().get(itemDraft.editing);

        if(def == null)
        {
            cancelItem();
            return;
        }

        if(!guard.allow("delete item " + def.id(), "delete"))
        {
            say("Click Delete item again to delete " + def.name() + " - worlds keep it as an unknown item");
            return;
        }

        ItemRegistry without = ItemRegistry.active().without(def.id());

        if(!without.save(ItemRegistry.FILE))
        {
            say(SafeFiles.writeFailed(ItemRegistry.FILE) + " - item not deleted");
            return;
        }

        ItemRegistry.activate(without);
        RecipeBook.activate(RecipeBook.load(RecipeBook.FILE));
        itemDraft = null;
        editingName = false;
        say("Deleted " + def.name() + " (id " + def.id() + " stays retired)");
    }

    /** Návrhu dá čerstvou dlaždici s kopií té dosavadní. */
    private void newItemTile()
    {
        int free = ItemDraft.freeTile(ItemRegistry.active(), itemEditor.pixels(), itemDraft.tile);

        if(free < 0)
        {
            say("Item atlas is full - reuse an existing tile");
            return;
        }

        boolean overwrites = !Textures.tileEmpty(itemEditor.pixels(), free);
        itemEditor.copyTile(itemDraft.tile, free);
        itemDraft.tile = free;
        selectTile(free);
        say(overwrites
                ? "Tile " + free + " had unused paint - replaced (Ctrl+Z brings it back)"
                : "Tile " + free + " is new - paint it on the canvas");
    }

    /**
     * Založí předmět: uloží items.png i items.json, aktivuje nový registr
     * a nechá předmět vyzvednout Mainu. NEJDŘÍV OBRÁZEK, PAK PŘEDMĚTY -
     * stejný důvod jako u bloku (createBlock).
     */
    private void createItem()
    {
        ItemRegistry base = ItemRegistry.active();
        String problem = itemDraft.problem(base);

        if(problem != null)
        {
            say(problem);
            return;
        }

        ItemDef def = itemDraft.toDef(base);
        ItemRegistry created = base.with(def);
        boolean edit = itemDraft.isEdit();

        if(!AtlasImage.save(itemEditor.pixels(), ItemTextures.FILE))
        {
            say(SafeFiles.writeFailed(ItemTextures.FILE) + (edit ? " - item not saved" : " - item not created"));
            return;
        }

        itemEditor.markSaved();
        itemsFromFile = true;

        if(!created.save(ItemRegistry.FILE))
        {
            say(SafeFiles.writeFailed(ItemRegistry.FILE) + (edit ? " - item not saved" : " - item not created"));
            return;
        }

        ItemRegistry.activate(created);
        itemDraft = null;
        editingName = false;

        // Upravený předmět hráč nedostává znovu - už ho má (nebo si ho vyrobí).
        if(edit)
        {
            RecipeBook.activate(RecipeBook.load(RecipeBook.FILE));
            say("Saved " + def.name() + " (id " + def.id() + ")");
            return;
        }

        createdItems.add(def.id());
        say("Created " + def.name() + " (id " + def.id() + ") - " + def.maxStack() + " go to your inventory");
    }

    private void cancelItem()
    {
        say(itemDraft != null && itemDraft.isEdit() ? "Edit cancelled" : "New item cancelled");
        itemDraft = null;
        editingName = false;
    }

    private boolean pressItemForm(TextureLabLayout layout, double mouseX, double mouseY)
    {
        if(layout.hit(TextureLabLayout.NAME, mouseX, mouseY))
        {
            editingName = true;
        }
        else if(layout.hit(TextureLabLayout.SOFTER, mouseX, mouseY))
        {
            itemDraft.fewer();
        }
        else if(layout.hit(TextureLabLayout.HARDER, mouseX, mouseY))
        {
            itemDraft.more();
        }
        else if(layout.hit(TextureLabLayout.SOLID, mouseX, mouseY))
        {
            itemDraft.nextTool();
        }
        else if(layout.hit(TextureLabLayout.OPAQUE, mouseX, mouseY))
        {
            if(itemDraft.tool == ItemDef.Tool.NONE)
            {
                say("Pick a tool first - speed is for tools");
            }
            else
            {
                itemDraft.nextSpeed();
            }
        }
        else if(layout.hit(TextureLabLayout.DURABILITY, mouseX, mouseY))
        {
            itemDraft.nextDurability();
        }
        else if(layout.hit(TextureLabLayout.NEW_TILE, mouseX, mouseY))
        {
            // U úpravy je na místě "New tile" smazání - obrázek se maluje rovnou.
            if(itemDraft.isEdit())
            {
                deleteItem();
            }
            else
            {
                newItemTile();
            }
        }
        else if(layout.hit(TextureLabLayout.CREATE, mouseX, mouseY))
        {
            createItem();
        }
        else if(layout.hit(TextureLabLayout.CANCEL, mouseX, mouseY))
        {
            cancelItem();
        }

        return false;
    }

    // ------------------------------------------------------------------
    // vstup
    // ------------------------------------------------------------------

    /** Zmáčknutí tlačítka myši. Vrací true, když se kliklo na Close. */
    private boolean pressPixel(TextureLabLayout layout, double mouseX, double mouseY,
                               int screenWidth, int screenHeight, boolean left)
    {

        // Klik jinam ukončí psaní hexu - platný se použije, neplatný zahodí.
        if(hexInput != null && !layout.hit(TextureLabLayout.HEX, mouseX, mouseY))
        {
            commitHex();
        }

        if(editingName && !layout.hit(TextureLabLayout.NAME, mouseX, mouseY))
        {
            editingName = false;
        }

        int[] pixel = canvasPixelAt(layout, mouseX, mouseY);

        if(pixel != null)
        {
            if(left)
            {
                painting = true;
                active().beginStroke(pixel[0], pixel[1]);
            }
            else
            {
                setColor(active().pick(pixel[0], pixel[1]));
                say("Picked " + AtlasEditor.toHex(active().color()));
            }
            return false;
        }

        if(!left)
        {
            return false;
        }

        // Vlevo: mřížka dlaždic, nebo celá kůže s díly těla.
        if(skinMode())
        {
            int[] skinPixel = layout.skinPixelAt(mouseX, mouseY);

            if(skinPixel != null)
            {
                int face = SkinLayout.faceAt(skinPixel[0], skinPixel[1]);

                if(face >= 0)
                {
                    skin.select(face);
                    say(SkinLayout.name(face) + "  "
                            + SkinLayout.width(face) + "x" + SkinLayout.height(face));
                }
                else
                {
                    // Nepokryté místo šablony (druhá vrstva) model nekreslí.
                    say("Nothing here - the model does not draw this part of the sheet");
                }
                return false;
            }
        }
        else
        {
            int tile = layout.tileAt(mouseX, mouseY);

            if(tile >= 0)
            {
                selectTile(tile);

                // V novém bloku klik do atlasu zároveň přiřadí dlaždici stěně,
                // v novém předmětu dlaždici předmětu.
                if(draft != null)
                {
                    draft.tiles[draft.activeFace] = tile;
                    showDraft();
                }
                else if(itemDraft != null)
                {
                    itemDraft.tile = tile;
                }
                return false;
            }
        }

        int swatch = layout.swatchAt(mouseX, mouseY);

        if(swatch >= 0)
        {
            if(swatch < paletteCount)
            {
                setColor(palette[swatch]);
            }
            return false;
        }

        int global = layout.globalSwatchAt(mouseX, mouseY);

        if(global >= 0)
        {
            if(global < globalColors.length)
            {
                setColor(globalColors[global]);
            }
            return false;
        }

        for(TextureLabLayout.Rect bar : new TextureLabLayout.Rect[]{
                TextureLabLayout.HUE, TextureLabLayout.SATURATION, TextureLabLayout.VALUE})
        {
            if(layout.hit(bar, mouseX, mouseY))
            {
                draggedSlider = bar;
                slide(layout, mouseX);
                return false;
            }
        }

        if(layout.hit(TextureLabLayout.HEX, mouseX, mouseY))
        {
            hexInput = new StringBuilder(AtlasEditor.toHex(active().color()).substring(1));
            return false;
        }

        if(draft != null)
        {
            return pressBlockForm(layout, mouseX, mouseY);
        }

        return itemDraft != null
                ? pressItemForm(layout, mouseX, mouseY)
                : pressMainButtons(layout, mouseX, mouseY);
    }

    private boolean pressMainButtons(TextureLabLayout layout, double mouseX, double mouseY)
    {
        if(layout.hit(TextureLabLayout.PREVIEW, mouseX, mouseY))
        {
            // Dlaždici může používat víc bloků (hlína: hlína a spodek trávy).
            if(mode == Mode.BLOCKS)
            {
                previewChoice++;
                refreshPreview();
            }
        }
        else if(layout.hit(TextureLabLayout.SAVE, mouseX, mouseY))
        {
            save();
        }
        else if(layout.hit(TextureLabLayout.REVERT, mouseX, mouseY))
        {
            revert();
        }
        else if(layout.hit(TextureLabLayout.IMPORT, mouseX, mouseY))
        {
            importImage();
        }
        else if(!skinMode() && layout.hit(TextureLabLayout.NEW_BLOCK, mouseX, mouseY))
        {
            if(itemsMode())
            {
                ItemDef existing = labItemOnSelectedTile();

                if(existing != null)
                {
                    editItem(existing);
                }
                else
                {
                    startItem();
                }
            }
            else
            {
                startBlock();
            }
        }

        return layout.hit(TextureLabLayout.CLOSE, mouseX, mouseY);
    }

    private boolean pressBlockForm(TextureLabLayout layout, double mouseX, double mouseY)
    {
        int face = layout.faceAt(mouseX, mouseY);

        if(face >= 0)
        {
            // Vybraná stěna se zároveň otevře na plátně - hned jde malovat.
            draft.activeFace = face;
            selectTile(draft.tiles[face]);
        }
        else if(layout.hit(TextureLabLayout.NAME, mouseX, mouseY))
        {
            editingName = true;
        }
        else if(layout.hit(TextureLabLayout.SOFTER, mouseX, mouseY))
        {
            draft.softer();
        }
        else if(layout.hit(TextureLabLayout.HARDER, mouseX, mouseY))
        {
            draft.harder();
        }
        else if(layout.hit(TextureLabLayout.SOLID, mouseX, mouseY))
        {
            draft.solid = !draft.solid;
        }
        else if(layout.hit(TextureLabLayout.OPAQUE, mouseX, mouseY))
        {
            draft.opaque = !draft.opaque;
            showDraft();
        }
        else if(layout.hit(TextureLabLayout.NEW_TILE, mouseX, mouseY))
        {
            newTile();
        }
        else if(layout.hit(TextureLabLayout.CREATE, mouseX, mouseY))
        {
            createBlock();
        }
        else if(layout.hit(TextureLabLayout.CANCEL, mouseX, mouseY))
        {
            cancelBlock();
            refreshPreview();
            say("New block cancelled");
        }

        return false;
    }

    /** Pohyb myši s drženým tlačítkem. */
    private void dragPixel(TextureLabLayout layout, double mouseX, double mouseY,
                           int screenWidth, int screenHeight)
    {
        if(painting)
        {
            // Mimo plátno se tah přitiskne k okraji, ať se čára u kraje neutrhne.
            TextureLabLayout.Rect canvas = skinMode()
                    ? TextureLabLayout.skinCanvas(skin.face()) : TextureLabLayout.CANVAS;
            int zoom = skinMode()
                    ? TextureLabLayout.skinCanvasZoom(skin.face()) : TextureLabLayout.CANVAS_PIXEL;

            float gx = layout.guiX(mouseX) - canvas.x();
            float gy = layout.guiY(mouseY) - canvas.y();

            int width = active().regionWidth(), height = active().regionHeight();
            int x = clamp((int) Math.floor(gx / zoom), width);
            int y = height - 1 - clamp((int) Math.floor(gy / zoom), height);

            active().strokeTo(x, y);
        }
        else if(draggedSlider != null)
        {
            slide(layout, mouseX);
        }
    }

    void release()
    {
        if(painting)
        {
            active().endStroke();
            painting = false;
        }

        draggedSlider = null;
    }

    /**
     * Napsaný znak (GLFW char callback, ne klávesa) - na jméno nového bloku.
     * Znaky, ne kódy kláves: velká písmena, mezery i rozložení klávesnice
     * pak fungují samy. Bere se jen ASCII, font nic jiného nemá.
     */
    private void typedPixel(int codepoint)
    {
        if((draft == null && itemDraft == null) || !editingName || codepoint < 32 || codepoint > 126
                || codepoint == '"' || codepoint == '\\')
        {
            return;
        }

        if(draft != null && draft.name.length() < BlockRegistry.MAX_NAME_LENGTH)
        {
            draft.name += (char) codepoint;
        }
        else if(itemDraft != null && itemDraft.name.length() < ItemRegistry.MAX_NAME_LENGTH)
        {
            itemDraft.name += (char) codepoint;
        }
    }

    /**
     * Klávesy módů Blocks a Skin. Vrací true = KLÁVESU JSEM SI VZAL, takže
     * ji hub už nemá brát jako "zavři lab" (viz TextureLab.key). Při psaní
     * do hexu nebo do jména bloku si mód bere všechno, jinak by Esc uprostřed
     * psaní zavřel celý lab místo toho, aby zrušil rozepsané pole.
     */
    private boolean keyPixel(int key, int mods)
    {
        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        if(hexInput != null)
        {
            if(key == GLFW_KEY_ESCAPE)
            {
                hexInput = null;
            }
            else if(key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER)
            {
                commitHex();
            }
            else if(key == GLFW_KEY_BACKSPACE && hexInput.length() > 0)
            {
                hexInput.setLength(hexInput.length() - 1);
            }
            else if(hexInput.length() < 8)
            {
                char digit = hexDigit(key);

                if(digit != 0)
                {
                    hexInput.append(digit);
                }
            }
            return true;
        }

        if(editingName)
        {
            if(key == GLFW_KEY_ESCAPE || key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER
                    || key == GLFW_KEY_TAB)
            {
                editingName = false;
            }
            else if(key == GLFW_KEY_BACKSPACE && draft != null && !draft.name.isEmpty())
            {
                draft.name = draft.name.substring(0, draft.name.length() - 1);
            }
            else if(key == GLFW_KEY_BACKSPACE && itemDraft != null && !itemDraft.name.isEmpty())
            {
                itemDraft.name = itemDraft.name.substring(0, itemDraft.name.length() - 1);
            }
            // Písmena přijdou přes typed(); zkratky se při psaní nespouští.
            return true;
        }

        // Měření labu (F3) tu není: zapíná ho hub ve všech módech, klávesou
        // ladicího výpisu z Keybinds - viz key().

        if(ctrl && key == GLFW_KEY_Z)
        {
            say(active().undo() ? "Undo" : "Nothing to undo");

            if(draft == null && mode == Mode.BLOCKS)
            {
                refreshPreview();
            }
            return true;
        }

        if(ctrl && key == GLFW_KEY_S)
        {
            save();
            return true;
        }

        // Esc v novém bloku ruší blok, ne celý lab - rozepsaná práce se tak
        // omylem nezahodí i s labem.
        if(key == GLFW_KEY_ESCAPE && draft != null)
        {
            cancelBlock();
            refreshPreview();
            say("New block cancelled");
            return true;
        }

        if(key == GLFW_KEY_ESCAPE && itemDraft != null)
        {
            cancelItem();
            return true;
        }

        return false;
    }

    private void commitHex()
    {
        Integer parsed = AtlasEditor.parseHex(hexInput.toString());
        hexInput = null;

        if(parsed != null)
        {
            setColor(parsed);
        }
        else
        {
            say("Hex needs 6 or 8 digits");
        }
    }

    private void slide(TextureLabLayout layout, double mouseX)
    {
        float t = layout.sliderValue(draggedSlider, mouseX);

        if(draggedSlider == TextureLabLayout.HUE)
        {
            hue = t * 360f;
        }
        else if(draggedSlider == TextureLabLayout.SATURATION)
        {
            saturation = t;
        }
        else
        {
            value = t;
        }

        applyHsv();
    }

    private static char hexDigit(int key)
    {
        if(key >= GLFW_KEY_0 && key <= GLFW_KEY_9) return (char) ('0' + key - GLFW_KEY_0);
        if(key >= GLFW_KEY_KP_0 && key <= GLFW_KEY_KP_9) return (char) ('0' + key - GLFW_KEY_KP_0);
        if(key >= GLFW_KEY_A && key <= GLFW_KEY_F) return (char) ('A' + key - GLFW_KEY_A);
        return 0;
    }

    private static int clamp(int pixel, int limit)
    {
        return Math.max(0, Math.min(limit - 1, pixel));
    }

    /** Pixel plátna pod myší - dlaždice, nebo stěna dílu těla. */
    private int[] canvasPixelAt(TextureLabLayout layout, double mouseX, double mouseY)
    {
        return skinMode() ? layout.skinCanvasPixelAt(skin.face(), mouseX, mouseY)
                : layout.canvasPixelAt(mouseX, mouseY);
    }

    // ------------------------------------------------------------------
    // kreslení
    // ------------------------------------------------------------------

    /**
     * Obsah obou pixelových módů (Blocks i Skin).
     *
     * Zůstalo to jedna metoda se `skinMode()` větvemi schválně: obě
     * záložky sdílí plátno, paletu, HSV, hex i undo a liší se jen tím,
     * do čeho míří. Rozdělit je na dvě kopie by znamenalo, že se každá
     * oprava malování dělá dvakrát - přesně to, čemu `PixelEditor`
     * odjakživa brání. Navigaci to nebrání: navenek jsou to dva `LabMode`.
     */
    private void drawPixelContent(TextureLabLayout layout, int screenWidth, int screenHeight,
                                  double mouseX, double mouseY)
    {

        // Jedno nahrání za frame, ať se maluje jakkoliv rychle - a jen
        // OBDÉLNÍK, který se změnil (při malování jedna dlaždice, 1 KB
        // místo celých 64 KB atlasu). Viz DirtyRect.
        profiler.start(LabProfiler.UPLOAD);
        upload(blockEditor, blockTexture);
        upload(itemEditor, itemTexture);
        upload(skin, skinTexture);
        profiler.stop(LabProfiler.UPLOAD);

        int scale = layout.scale();

        profiler.start(LabProfiler.PALETTE);
        refreshTilePalette();
        refreshGlobalPalette();

        // Najetí na vzorek ukáže, ve kterých dlaždicích ta barva je. Hledání
        // je nad CELÝM atlasem, takže patří k počítání barev, ne ke kreslení -
        // a pamatuje si poslední výsledek, dokud se nezmění barva ani pixely.
        boolean[] uses = isSwatchHovered(layout, mouseX, mouseY)
                ? tilesWithColor(hoveredSwatchColor(layout, mouseX, mouseY))
                : null;
        profiler.stop(LabProfiler.PALETTE);

        // --- podklady ---
        profiler.start(LabProfiler.SHAPES);
        shapes.begin(screenWidth, screenHeight);

        sunken(layout, screenHeight, TextureLabLayout.ATLAS);
        sunken(layout, screenHeight, TextureLabLayout.CANVAS);
        sunken(layout, screenHeight, TextureLabLayout.PREVIEW);
        fill(layout, screenHeight, TextureLabLayout.PREVIEW, SKY);

        if(draft != null)
        {
            for(int face : BlockDraft.FACES)
            {
                sunken(layout, screenHeight, TextureLabLayout.faceTileRect(face));
            }
        }

        shapes.end();
        profiler.stop(LabProfiler.SHAPES);

        // --- přehled a plátno přímo z textury ---
        profiler.start(LabProfiler.IMAGES);
        int tile = editor.tile();

        if(skinMode())
        {
            drawSkinImages(layout, screenWidth, screenHeight);
        }
        else
        {
            image(layout, screenWidth, screenHeight, checker, TextureLabLayout.ATLAS, 0f, 0f, 32f, 32f);
            image(layout, screenWidth, screenHeight, atlas, TextureLabLayout.ATLAS, 0f, 0f, 1f, 1f);

            image(layout, screenWidth, screenHeight, checker, TextureLabLayout.CANVAS, 0f, 0f, 8f, 8f);
            tileImage(layout, screenWidth, screenHeight, TextureLabLayout.CANVAS, tile);
        }

        if(draft != null)
        {
            for(int face : BlockDraft.FACES)
            {
                TextureLabLayout.Rect r = TextureLabLayout.faceTileRect(face);
                image(layout, screenWidth, screenHeight, checker, r, 0f, 0f, 1f, 1f);
                tileImage(layout, screenWidth, screenHeight, r, draft.tiles[face]);
            }
        }

        profiler.stop(LabProfiler.IMAGES);

        // --- mřížky, výběr, paleta, posuvníky, tlačítka ---
        profiler.start(LabProfiler.SHAPES);
        shapes.begin(screenWidth, screenHeight);

        if(skinMode())
        {
            drawSkinSelection(layout, screenHeight, scale, mouseX, mouseY, uses);
        }
        else
        {
            drawGrid(layout, screenHeight, TextureLabLayout.CANVAS, AtlasEditor.TILE, GRID_LINE);
            drawGrid(layout, screenHeight, TextureLabLayout.ATLAS, AtlasEditor.TILES_PER_ROW, TILE_LINE);

            if(uses != null)
            {
                for(int t = 0; t < uses.length; t++)
                {
                    if(uses[t])
                    {
                        outline(layout, screenHeight, TextureLabLayout.tileRect(t), scale, USES_COLOR);
                    }
                }
            }

            outline(layout, screenHeight, TextureLabLayout.tileRect(tile), scale, Palette.SELECTOR);

            int hoveredTile = layout.tileAt(mouseX, mouseY);
            if(hoveredTile >= 0 && hoveredTile != tile)
            {
                outline(layout, screenHeight, TextureLabLayout.tileRect(hoveredTile), 1, HOVER);
            }

            int[] hoveredPixel = layout.canvasPixelAt(mouseX, mouseY);
            if(hoveredPixel != null)
            {
                outline(layout, screenHeight,
                        TextureLabLayout.canvasPixelRect(hoveredPixel[0], hoveredPixel[1]), 1, HOVER);
            }
        }

        for(int i = 0; i < paletteCount; i++)
        {
            drawSwatch(layout, screenHeight, TextureLabLayout.swatchRect(i), palette[i]);
        }

        for(int i = 0; i < globalColors.length; i++)
        {
            drawSwatch(layout, screenHeight, TextureLabLayout.globalSwatchRect(i), globalColors[i]);
        }

        swatch(layout, screenHeight, TextureLabLayout.CURRENT, active().color());
        sunken(layout, screenHeight, TextureLabLayout.HEX);

        drawSlider(layout, screenHeight, TextureLabLayout.HUE, hue / 360f);
        drawSlider(layout, screenHeight, TextureLabLayout.SATURATION, saturation);
        drawSlider(layout, screenHeight, TextureLabLayout.VALUE, value);

        if(draft == null && itemDraft == null)
        {
            button(layout, screenHeight, TextureLabLayout.SAVE, mouseX, mouseY);
            button(layout, screenHeight, TextureLabLayout.REVERT, mouseX, mouseY);
            button(layout, screenHeight, TextureLabLayout.IMPORT, mouseX, mouseY);

            if(!skinMode())
            {
                button(layout, screenHeight, TextureLabLayout.NEW_BLOCK, mouseX, mouseY);
            }

            button(layout, screenHeight, TextureLabLayout.CLOSE, mouseX, mouseY);
        }
        else if(draft != null)
        {
            drawBlockForm(layout, screenHeight, mouseX, mouseY);
        }
        else
        {
            drawItemForm(layout, screenHeight, mouseX, mouseY);
        }

        shapes.end();
        profiler.stop(LabProfiler.SHAPES);

        // --- živý náhled přes světový shader ---
        profiler.start(LabProfiler.PREVIEW);
        TextureLabLayout.Rect p = TextureLabLayout.PREVIEW;
        int px = (int) layout.screenX(p), py = (int) layout.screenBottom(p, screenHeight);

        if(skinMode())
        {
            skinPreview.draw(skinTexture, px, py, p.w() * scale, p.h() * scale,
                    screenWidth, screenHeight);
        }
        else if(itemsMode())
        {
            // Obrázek předmětu, jak ho ukáže inventář - uprostřed náhledu.
            int size = Math.min(p.w(), p.h()) * scale * 3 / 4;
            blockIcons.begin(screenWidth, screenHeight);
            blockIcons.drawItemTile(px + (p.w() * scale - size) / 2f, py + (p.h() * scale - size) / 2f,
                    size, itemDraft != null ? itemDraft.tile : editor.tile());
            blockIcons.end();
        }
        else
        {
            preview.draw(atlas, px, py, p.w() * scale, p.h() * scale, screenWidth, screenHeight);
        }
        profiler.stop(LabProfiler.PREVIEW);

    }

    /** Nahraje na grafiku obdélník, který se v editoru změnil - nic víc. */
    private static void upload(PixelEditor source, Texture target)
    {
        DirtyRect changed = source.dirty();

        if(changed.isEmpty())
        {
            return;
        }

        target.updateRegion(source.pixels(), changed.x(), changed.y(),
                changed.width(), changed.height());
        source.clearDirty();
    }

    /**
     * Paleta vybrané dlaždice. Přepočítává se jen při změně dlaždice nebo
     * pixelů - 256 pixelů do mapy a seřadit se každý frame dělat nemusí.
     *
     * ⚠️ BĚHEM TAHU ŠTĚTCEM NE. Tah mění pixely každý frame, takže by se
     * palety počítaly znovu desetkrát za vteřinu - a navíc by se vzorky pod
     * kurzorem přerovnávaly, takže by uživatel klikal na barvu, která se mu
     * pod myší zrovna odstěhovala. Přepočet přijde, až tah skončí; to je
     * okamžik, kdy se atlas SKUTEČNĚ změnil z pohledu uživatele.
     */
    private void refreshTilePalette()
    {
        PixelEditor source = active();

        if((paletteRevision == source.revision() && paletteRegion == regionKey()
                && paletteMode == mode) || source.isStroking())
        {
            return;
        }

        System.arraycopy(BASIC_COLORS, 0, palette, 0, BASIC_COLORS.length);
        int[] regionColors = source.regionColors(palette.length - BASIC_COLORS.length);
        System.arraycopy(regionColors, 0, palette, BASIC_COLORS.length, regionColors.length);
        paletteCount = BASIC_COLORS.length + regionColors.length;

        paletteRevision = source.revision();
        paletteRegion = regionKey();
        paletteMode = mode;
    }

    /** Která dlaždice / stěna je na plátně - klíč do keší palet. */
    private int regionKey()
    {
        return skinMode() ? skin.face() : editor.tile();
    }

    /**
     * ⚠️ JEDEN průchod atlasem, ne dva. Dřív se atlasColors() volalo dvakrát -
     * jednou na zobrazených 86 barev a jednou na všechny, jen aby se zjistil
     * jejich počet - a při malování se to dělo při každém namalovaném pixelu.
     */
    private void refreshGlobalPalette()
    {
        PixelEditor source = active();

        if((globalRevision == source.revision() && globalMode == mode) || source.isStroking())
        {
            return;
        }

        int shown = TextureLabLayout.GLOBAL_COLUMNS * TextureLabLayout.GLOBAL_ROWS;
        PixelEditor.Colors colors = PixelEditor.countColors(source.pixels(), false);

        globalColors = AtlasEditor.byHue(colors.top(shown));
        globalTotal = colors.total();
        globalRevision = source.revision();
        globalMode = mode;
    }

    /**
     * Dlaždice obsahující barvu. Myš nad vzorkem stojí desítky framů v kuse,
     * takže se výsledek drží, dokud se nezmění barva ani pixely - jinak by
     * se 16 384 pixelů procházelo pořád dokola.
     */
    private boolean[] tilesWithColor(int argb)
    {
        PixelEditor source = active();

        if(usesColor == null || usesArgb != argb || usesRevision != source.revision()
                || usesMode != mode)
        {
            usesColor = skinMode() ? facesWithColor(skin.pixels(), argb)
                    : AtlasEditor.tilesWithColor(editor.pixels(), argb);
            usesArgb = argb;
            usesRevision = source.revision();
            usesMode = mode;
        }

        return usesColor;
    }

    /** Které stěny těla barvu obsahují - obdoba tilesWithColor() pro kůži. */
    static boolean[] facesWithColor(int[] skinPixels, int argb)
    {
        boolean[] found = new boolean[SkinLayout.FACE_COUNT];

        for(int face = 0; face < found.length; face++)
        {
            SkinLayout.Rect r = SkinLayout.rect(face);

            for(int y = 0; y < r.height() && !found[face]; y++)
            {
                for(int x = 0; x < r.width(); x++)
                {
                    if(skinPixels[(r.v() + y) * SkinLayout.SIZE + r.u() + x] == argb)
                    {
                        found[face] = true;
                        break;
                    }
                }
            }
        }

        return found;
    }

    /**
     * Bloky, které vybranou dlaždici používají. Projít 127 id se v kreslení
     * textů dělalo každý frame; mění se to jen s dlaždicí a s registrem.
     */
    private List<Byte> blocksUsingSelected()
    {
        if(usingTile != editor.tile() || usingRegistry != BlockRegistry.active())
        {
            usingBlocks = AtlasEditor.blocksUsing(editor.tile());
            usingTile = editor.tile();
            usingRegistry = BlockRegistry.active();
        }

        return usingBlocks;
    }

    /**
     * Přehled celé kůže vlevo a vybraná stěna na plátně.
     *
     * ⚠️ v0 = 1, v1 = 0. Pole kůže jde do GL v pořadí OBRÁZKU (horní řádek
     * první), takže GL má t = 0 nahoře - a ImageRenderer kreslí v0 u DOLNÍHO
     * okraje. Bez prohození by kůže i stěna stály vzhůru nohama.
     */
    private void drawSkinImages(TextureLabLayout layout, int screenWidth, int screenHeight)
    {
        TextureLabLayout.Rect sheet = TextureLabLayout.SKIN_SHEET;
        image(layout, screenWidth, screenHeight, checker, sheet, 0f, 0f, 32f, 32f);
        image(layout, screenWidth, screenHeight, skinTexture, sheet, 0f, 1f, 1f, 0f);

        int face = skin.face();
        TextureLabLayout.Rect canvas = TextureLabLayout.skinCanvas(face);
        SkinLayout.Rect r = SkinLayout.rect(face);
        float unit = 1f / SkinLayout.SIZE;

        image(layout, screenWidth, screenHeight, checker, canvas, 0f, 0f, 8f, 8f);
        image(layout, screenWidth, screenHeight, skinTexture, canvas,
                r.u() * unit, (r.v() + r.height()) * unit,
                (r.u() + r.width()) * unit, r.v() * unit);
    }

    /** Rámečky dílů v přehledu kůže, výběr a pixel pod myší. */
    private void drawSkinSelection(TextureLabLayout layout, int screenHeight, int scale,
                                   double mouseX, double mouseY, boolean[] uses)
    {
        int face = skin.face();

        drawGridXY(layout, screenHeight, TextureLabLayout.skinCanvas(face),
                SkinLayout.width(face), SkinLayout.height(face), GRID_LINE);

        // Tenké rámečky VŠECH stěn: jinak by na kůži nebylo vidět, kde končí
        // obličej a začíná týl - v šabloně jsou vedle sebe bez mezery.
        for(int f = 0; f < SkinLayout.FACE_COUNT; f++)
        {
            outline(layout, screenHeight, TextureLabLayout.skinFaceRect(f), 1, TILE_LINE);
        }

        if(uses != null)
        {
            for(int f = 0; f < uses.length; f++)
            {
                if(uses[f])
                {
                    outline(layout, screenHeight, TextureLabLayout.skinFaceRect(f), scale, USES_COLOR);
                }
            }
        }

        outline(layout, screenHeight, TextureLabLayout.skinFaceRect(face), scale, Palette.SELECTOR);

        int[] sheetPixel = layout.skinPixelAt(mouseX, mouseY);
        hoveredFace = sheetPixel == null ? -1 : SkinLayout.faceAt(sheetPixel[0], sheetPixel[1]);

        if(hoveredFace >= 0 && hoveredFace != face)
        {
            outline(layout, screenHeight, TextureLabLayout.skinFaceRect(hoveredFace), 1, HOVER);
        }

        int[] hoveredPixel = layout.skinCanvasPixelAt(face, mouseX, mouseY);

        if(hoveredPixel != null)
        {
            outline(layout, screenHeight,
                    TextureLabLayout.skinCanvasPixelRect(face, hoveredPixel[0], hoveredPixel[1]),
                    1, HOVER);
        }
    }

    private boolean isSwatchHovered(TextureLabLayout layout, double mouseX, double mouseY)
    {
        int swatch = layout.swatchAt(mouseX, mouseY);
        int global = layout.globalSwatchAt(mouseX, mouseY);
        return (swatch >= 0 && swatch < paletteCount) || (global >= 0 && global < globalColors.length);
    }

    /** Barva vzorku pod myší; platí jen když isSwatchHovered() - guma je taky 0. */
    private int hoveredSwatchColor(TextureLabLayout layout, double mouseX, double mouseY)
    {
        int swatch = layout.swatchAt(mouseX, mouseY);

        if(swatch >= 0 && swatch < paletteCount)
        {
            return palette[swatch];
        }

        int global = layout.globalSwatchAt(mouseX, mouseY);
        return global >= 0 && global < globalColors.length ? globalColors[global] : 0;
    }

    private void drawSwatch(TextureLabLayout layout, int screenHeight, TextureLabLayout.Rect r, int argb)
    {
        swatch(layout, screenHeight, r, argb);

        if(argb == active().color())
        {
            outline(layout, screenHeight, grow(r), layout.scale(), Palette.SELECTOR);
        }
    }

    /** Formulář nového bloku: ovládání vlevo a tlačítka vpravo (texty kreslí drawTexts). */
    private void drawBlockForm(TextureLabLayout layout, int screenHeight, double mouseX, double mouseY)
    {
        int scale = layout.scale();

        sunken(layout, screenHeight, TextureLabLayout.NAME);

        if(editingName)
        {
            outline(layout, screenHeight, TextureLabLayout.NAME, scale, Palette.SELECTOR);
        }

        button(layout, screenHeight, TextureLabLayout.SOFTER, mouseX, mouseY);
        button(layout, screenHeight, TextureLabLayout.HARDER, mouseX, mouseY);

        checkbox(layout, screenHeight, TextureLabLayout.SOLID, draft.solid);
        checkbox(layout, screenHeight, TextureLabLayout.OPAQUE, draft.opaque);

        outline(layout, screenHeight, TextureLabLayout.faceSlot(draft.activeFace), scale, Palette.SELECTOR);

        button(layout, screenHeight, TextureLabLayout.NEW_TILE, mouseX, mouseY);
        button(layout, screenHeight, TextureLabLayout.CREATE, mouseX, mouseY);
        button(layout, screenHeight, TextureLabLayout.CANCEL, mouseX, mouseY);
    }

    /** Formulář nového předmětu - tatáž místa jako formulář bloku, bez stěn. */
    private void drawItemForm(TextureLabLayout layout, int screenHeight, double mouseX, double mouseY)
    {
        sunken(layout, screenHeight, TextureLabLayout.NAME);

        if(editingName)
        {
            outline(layout, screenHeight, TextureLabLayout.NAME, layout.scale(), Palette.SELECTOR);
        }

        button(layout, screenHeight, TextureLabLayout.SOFTER, mouseX, mouseY);
        button(layout, screenHeight, TextureLabLayout.HARDER, mouseX, mouseY);

        // Nástroj a rychlost jsou tlačítka, která se klikáním přepínají dokola.
        button(layout, screenHeight, TextureLabLayout.SOLID, mouseX, mouseY);
        button(layout, screenHeight, TextureLabLayout.OPAQUE, mouseX, mouseY);
        button(layout, screenHeight, TextureLabLayout.DURABILITY, mouseX, mouseY);

        button(layout, screenHeight, TextureLabLayout.NEW_TILE, mouseX, mouseY);
        button(layout, screenHeight, TextureLabLayout.CREATE, mouseX, mouseY);
        button(layout, screenHeight, TextureLabLayout.CANCEL, mouseX, mouseY);
    }

    /** Zaškrtávátko: zapuštěný čtvereček na začátku řádku, zaplněný když platí. */
    private void checkbox(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect row, boolean on)
    {
        TextureLabLayout.Rect box = new TextureLabLayout.Rect(row.x() + 1, row.y() + 2, 8, 8);
        sunken(l, screenHeight, box);

        if(on)
        {
            fill(l, screenHeight, new TextureLabLayout.Rect(box.x() + 2, box.y() + 2, 4, 4), Palette.SELECTOR);
        }
    }

    private void drawPixelTexts(TextureLabLayout layout, int screenWidth, int screenHeight,
                                double mouseX, double mouseY)
    {
        int scale = layout.scale();
        text.begin(screenWidth, screenHeight, scale);

        String source = skinMode()
                ? (skinFromFile ? SafeFiles.shown(Textures.SKIN_FILE) : "built-in")
                : itemsMode()
                ? (itemsFromFile ? SafeFiles.shown(ItemTextures.FILE) : "procedural")
                : (fromFile ? SafeFiles.shown(Textures.ATLAS_FILE) : "procedural");
        String unsaved = active().isUnsaved() ? "  (unsaved)" : "";

        if(skinMode())
        {
            label(layout, 8, TextureLabLayout.TITLE_Y,
                    fit("Lab   skin: " + source + unsaved, TextureLabLayout.CONTENT_WIDTH - 16, scale));
            drawSkinInfo(layout);
        }
        else if(itemsMode() && itemDraft == null)
        {
            label(layout, 8, TextureLabLayout.TITLE_Y,
                    fit("Lab   items: " + source + unsaved, TextureLabLayout.CONTENT_WIDTH - 16, scale));
            drawItemTileInfo(layout);
        }
        else if(itemsMode())
        {
            label(layout, 8, TextureLabLayout.TITLE_Y, fit((itemDraft.isEdit()
                    ? "Lab   edit item, id " + itemDraft.editing
                    : "Lab   new item, id " + ItemRegistry.active().nextId())
                    + "   items: " + source + unsaved, TextureLabLayout.CONTENT_WIDTH - 16, scale));
            drawItemFormTexts(layout);
        }
        else if(draft == null)
        {
            label(layout, 8, TextureLabLayout.TITLE_Y,
                    fit("Lab   atlas: " + source + unsaved, TextureLabLayout.CONTENT_WIDTH - 16, scale));
            drawTileInfo(layout);
        }
        else
        {
            label(layout, 8, TextureLabLayout.TITLE_Y, fit("Lab   new block, id "
                    + baseRegistry.nextId() + "   atlas: " + source + unsaved, TextureLabLayout.CONTENT_WIDTH - 16, scale));
            drawFormTexts(layout);
        }

        String hex = hexInput != null ? "#" + hexInput + "_" : AtlasEditor.toHex(active().color());
        label(layout, TextureLabLayout.HEX.x() + 3, TextureLabLayout.HEX.y() + 2, hex);

        label(layout, TextureLabLayout.HUE.x() + TextureLabLayout.HUE.w() + 4, TextureLabLayout.HUE.y() - 2, "H");
        label(layout, TextureLabLayout.SATURATION.x() + TextureLabLayout.SATURATION.w() + 4,
                TextureLabLayout.SATURATION.y() - 2, "S");
        label(layout, TextureLabLayout.VALUE.x() + TextureLabLayout.VALUE.w() + 4,
                TextureLabLayout.VALUE.y() - 2, "V");

        String shown = globalTotal > globalColors.length
                ? globalColors.length + " of " + globalTotal
                : "all " + globalTotal;
        label(layout, 8, TextureLabLayout.GLOBAL_LABEL_Y,
                (skinMode() ? "Skin colors: " : itemsMode() ? "Item colors: " : "Atlas colors: ") + shown
                        + ", by hue - hover shows where they are");

        text.end();
    }

    private void drawTileInfo(TextureLabLayout layout)
    {
        int tile = editor.tile();
        List<Byte> blocks = blocksUsingSelected();

        label(layout, 8, TextureLabLayout.INFO_Y, "Tile " + tile + "  ("
                + BlockAtlas.column(tile) + ", " + BlockAtlas.row(tile) + ")");

        if(blocks.isEmpty())
        {
            label(layout, 8, TextureLabLayout.INFO_Y + 12, "No block uses it");
        }
        else
        {
            label(layout, 8, TextureLabLayout.INFO_Y + 12, "Used by:");

            for(int i = 0; i < Math.min(blocks.size(), 4); i++)
            {
                label(layout, 12, TextureLabLayout.INFO_Y + 24 + i * 11, blockName(blocks.get(i)));
            }

            TextureLabLayout.Rect p = TextureLabLayout.PREVIEW;
            label(layout, p.x() + 3, p.y() + 3, blockName(preview.block())
                    + (blocks.size() > 1 ? "  (click: next)" : ""));
        }

        centered(layout, TextureLabLayout.SAVE, "Save");
        centered(layout, TextureLabLayout.REVERT, "Revert");
        centered(layout, TextureLabLayout.IMPORT, "Import PNG");
        centered(layout, TextureLabLayout.NEW_BLOCK, "New block");
        centered(layout, TextureLabLayout.CLOSE, "Close  (Esc / " + Keybinds.activeKeyName(Keybinds.Action.LAB) + ")");
    }

    /** Informace o vybrané dlaždici atlasu předmětů: který předmět ji má. */
    private void drawItemTileInfo(TextureLabLayout layout)
    {
        int tile = editor.tile();

        label(layout, 8, TextureLabLayout.INFO_Y, "Tile " + tile + "  ("
                + BlockAtlas.column(tile) + ", " + BlockAtlas.row(tile) + ")");

        List<String> users = new ArrayList<>();

        for(ItemDef def : ItemRegistry.active().items())
        {
            if(def.tile() == tile)
            {
                users.add(def.name() + (def.isTool()
                        ? "  " + def.tool().key() + " " + (int) def.toolSpeed() + "x" : "  stack " + def.maxStack())
                        + (def.wears() ? "  " + def.durability() + " uses" : ""));
            }
        }

        if(users.isEmpty())
        {
            label(layout, 8, TextureLabLayout.INFO_Y + 12,
                    tile < ItemRegistry.BUILT_IN_TILES ? "Reserved for built-in items" : "No item uses it");
        }
        else
        {
            label(layout, 8, TextureLabLayout.INFO_Y + 12, "Used by:");

            for(int i = 0; i < Math.min(users.size(), 4); i++)
            {
                label(layout, 12, TextureLabLayout.INFO_Y + 24 + i * 11, users.get(i));
            }
        }

        TextureLabLayout.Rect p = TextureLabLayout.PREVIEW;
        label(layout, p.x() + 3, p.y() + 3, users.isEmpty() ? "Tile " + tile : users.get(0).split("  ")[0]);

        centered(layout, TextureLabLayout.SAVE, "Save");
        centered(layout, TextureLabLayout.REVERT, "Revert");
        centered(layout, TextureLabLayout.IMPORT, "Import PNG");
        centered(layout, TextureLabLayout.NEW_BLOCK, labItemOnSelectedTile() != null ? "Edit item" : "New item");
        centered(layout, TextureLabLayout.CLOSE, "Close  (Esc / " + Keybinds.activeKeyName(Keybinds.Action.LAB) + ")");
    }

    private void drawItemFormTexts(TextureLabLayout layout)
    {
        TextureLabLayout.Rect name = TextureLabLayout.NAME;

        if(itemDraft.name.isEmpty() && !editingName)
        {
            text.draw("Name?", layout.textLeft(name.x() + 3), layout.textTop(name.y() + 2), Palette.TEXT_MUTED);
        }
        else
        {
            label(layout, name.x() + 3, name.y() + 2, itemDraft.name + (editingName ? "_" : ""));
        }

        centered(layout, TextureLabLayout.SOFTER, "-");
        centered(layout, TextureLabLayout.HARDER, "+");
        centered(layout, TextureLabLayout.HARDNESS_ROW, "Stack " + itemDraft.stack());
        centered(layout, TextureLabLayout.SOLID, itemDraft.toolLabel());
        centered(layout, TextureLabLayout.OPAQUE, itemDraft.tool == ItemDef.Tool.NONE
                ? "-" : "Speed " + (int) itemDraft.speed() + "x");

        centered(layout, TextureLabLayout.DURABILITY, itemDraft.durabilityLabel());
        label(layout, 8, TextureLabLayout.DURABILITY.y() + 16, "Tile " + itemDraft.tile + " (click atlas)");

        TextureLabLayout.Rect p = TextureLabLayout.PREVIEW;
        label(layout, p.x() + 3, p.y() + 3, itemDraft.name.isBlank() ? "New item" : itemDraft.name.trim());

        centered(layout, TextureLabLayout.NEW_TILE, itemDraft.isEdit() ? "Delete item" : "New tile");
        centered(layout, TextureLabLayout.CREATE, itemDraft.isEdit() ? "Save item" : "Create item");
        centered(layout, TextureLabLayout.CANCEL, "Cancel  (Esc)");
    }

    /** Informace o vybrané stěně kůže - nalevo místo informací o dlaždici. */
    private void drawSkinInfo(TextureLabLayout layout)
    {
        int face = skin.face();
        SkinLayout.Rect r = SkinLayout.rect(face);

        label(layout, 8, TextureLabLayout.INFO_Y, SkinLayout.name(face)
                + "   " + r.width() + "x" + r.height());
        label(layout, 8, TextureLabLayout.INFO_Y + 12,
                "skin u " + r.u() + "-" + (r.u() + r.width())
                        + "  v " + r.v() + "-" + (r.v() + r.height()));

        label(layout, 8, TextureLabLayout.INFO_Y + 26, hoveredFace >= 0
                ? SkinLayout.name(hoveredFace) : "Click a body face on the sheet");

        TextureLabLayout.Rect p = TextureLabLayout.PREVIEW;
        label(layout, p.x() + 3, p.y() + 3, "Player");

        centered(layout, TextureLabLayout.SAVE, "Save");
        centered(layout, TextureLabLayout.REVERT, "Revert");
        centered(layout, TextureLabLayout.IMPORT, "Import PNG");
        centered(layout, TextureLabLayout.CLOSE, "Close  (Esc / " + Keybinds.activeKeyName(Keybinds.Action.LAB) + ")");
    }

    private void drawFormTexts(TextureLabLayout layout)
    {
        TextureLabLayout.Rect name = TextureLabLayout.NAME;

        if(draft.name.isEmpty() && !editingName)
        {
            text.draw("Name?", layout.textLeft(name.x() + 3), layout.textTop(name.y() + 2), Palette.TEXT_MUTED);
        }
        else
        {
            label(layout, name.x() + 3, name.y() + 2, draft.name + (editingName ? "_" : ""));
        }

        centered(layout, TextureLabLayout.SOFTER, "-");
        centered(layout, TextureLabLayout.HARDER, "+");

        float hardness = draft.hardness();
        String like = BlockDraft.hardnessLike(hardness);
        centered(layout, TextureLabLayout.HARDNESS_ROW, seconds(hardness) + (like.isEmpty() ? "" : "  " + like));

        label(layout, TextureLabLayout.SOLID.x() + 12, TextureLabLayout.SOLID.y() + 2, "Solid");
        label(layout, TextureLabLayout.OPAQUE.x() + 12, TextureLabLayout.OPAQUE.y() + 2, "Opaque");

        String[] faceNames = new String[3];
        faceNames[BlockAtlas.FACE_TOP] = "Top";
        faceNames[BlockAtlas.FACE_SIDE] = "Side";
        faceNames[BlockAtlas.FACE_BOTTOM] = "Bottom";

        for(int face : BlockDraft.FACES)
        {
            TextureLabLayout.Rect slot = TextureLabLayout.faceSlot(face);
            TextureLabLayout.Rect label = new TextureLabLayout.Rect(slot.x(), slot.y() + 19, slot.w(), 10);
            centered(layout, label, faceNames[face]);
        }

        TextureLabLayout.Rect p = TextureLabLayout.PREVIEW;
        label(layout, p.x() + 3, p.y() + 3, draft.name.isBlank() ? "New block" : draft.name.trim());

        centered(layout, TextureLabLayout.NEW_TILE, "New tile for " + faceNames[draft.activeFace]);
        centered(layout, TextureLabLayout.CREATE, "Create block");
        centered(layout, TextureLabLayout.CANCEL, "Cancel  (Esc)");
    }

    /** Nápověda dole podle toho, na čem je myš. */
    private String pixelHelp(TextureLabLayout l, double mouseX, double mouseY)
    {
        if(skinMode())
        {
            if(l.skinPixelAt(mouseX, mouseY) != null)
                return "The whole skin sheet - click a body face to open it on the canvas";
            if(l.hit(TextureLabLayout.PREVIEW, mouseX, mouseY))
                return "The real player model with the skin you are painting right now";
            if(l.hit(TextureLabLayout.IMPORT, mouseX, mouseY))
                return "Loads textures/import.png, or drop a PNG on the window ("
                        + SkinEditor.SIZE + "x" + SkinEditor.SIZE + ")";
            if(l.hit(TextureLabLayout.SAVE, mouseX, mouseY))
                return "Saves " + SafeFiles.shown(Textures.SKIN_FILE)
                        + " - delete it to get the built-in skin back";
            return "LMB paint  RMB pick  Ctrl+Z undo  Ctrl+S save  click hex to type";
        }

        if(draft != null)
        {
            if(l.hit(TextureLabLayout.NAME, mouseX, mouseY))
                return "Click and type the block name (ASCII, up to " + BlockRegistry.MAX_NAME_LENGTH + ")";
            if(l.hit(TextureLabLayout.HARDNESS_ROW, mouseX, mouseY))
                return "Hardness: seconds to break by hand, same scale as the built-in blocks";
            if(l.hit(TextureLabLayout.SOLID, mouseX, mouseY))
                return "Solid: the player collides with it (off = walk through)";
            if(l.hit(TextureLabLayout.OPAQUE, mouseX, mouseY))
                return "Opaque: hides neighbour faces and stops light (off = like glass)";
            if(l.faceAt(mouseX, mouseY) >= 0 || l.tileAt(mouseX, mouseY) >= 0)
                return "Choose a face, then click an atlas tile for it - or press New tile";
            if(l.hit(TextureLabLayout.NEW_TILE, mouseX, mouseY))
                return "Copies the face's tile into a free atlas cell, ready to paint";
            if(l.hit(TextureLabLayout.CREATE, mouseX, mouseY))
                return "Saves atlas.png and blocks.json; always a full cube, no recipe";
            return "Full cube block. Paint its tiles on the canvas; Esc cancels";
        }

        if(l.hit(TextureLabLayout.IMPORT, mouseX, mouseY))
            return "Loads textures/import.png, or drop a PNG on the window ("
                    + AtlasEditor.SIZE + "x" + AtlasEditor.SIZE + ")";
        if(l.hit(TextureLabLayout.NEW_BLOCK, mouseX, mouseY))
            return "Create a new full-cube block with its own tiles";
        if(l.globalSwatchAt(mouseX, mouseY) >= 0 || l.swatchAt(mouseX, mouseY) >= 0)
            return "Click to paint with it - highlighted tiles already use it";
        return "LMB paint  RMB pick  Ctrl+Z undo  Ctrl+S save  click hex to type";
    }

    /** Tvrdost jako "1.8 s" - bez zbytečných nul. */
    static String seconds(float hardness)
    {
        String number = String.format(Locale.ROOT, "%.2f", hardness)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
        return number + " s";
    }

    /** Zkrátí text tak, aby se vešel do šířky v GUI pixelech (viz TextRenderer.fit). */
    String fit(String line, int guiWidth, int scale)
    {
        return text.fit(line, guiWidth);
    }

    // ------------------------------------------------------------------
    // pomocné kreslení (všechno v GUI pixelech panelu)
    // ------------------------------------------------------------------

    /** Atlas bloků - náhled stromu kreslí týmiž texturami jako hra. */
    /** Atlas BLOKŮ (ne ten, do kterého se zrovna maluje - to může být atlas předmětů). */
    Texture atlasTexture()
    {
        return blockTexture;
    }

    /**
     * Izometrické ikony bloků - tytéž kostky jako v hotbaru a ve slotech
     * inventáře, takže blok vypadá v receptu stejně jako ve hře.
     *
     * ⚠️ JE TO TROJICE begin / draw / end, ne jedna metoda na ikonu. `begin()`
     * naváže shader, texturu a VAO, `blockIcon()` jen přidá vrcholy do dávky
     * a `end()` pošle všechny ikony JEDNÍM draw callem (viz BlockIcon) -
     * přehled bloků v Recipes tak nestojí desítky draw callů za frame.
     *
     * Vlastní shader se musí navázat MIMO dávku `Renderer2D`, takže si o ikony
     * mód říká až po `shapes.end()`.
     */
    void blockIconsBegin(int screenWidth, int screenHeight)
    {
        blockIcons.begin(screenWidth, screenHeight);
    }

    /** Ikona věci (Items): blok izometricky, předmět jako obrázek. */
    void blockIcon(int id, float x, float y, float size)
    {
        blockIcons.draw(x, y, size, id);
    }

    void blockIconsEnd()
    {
        blockIcons.end();
    }

    void fill(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r, float[] color)
    {
        int s = l.scale();
        shapes.fillRect(l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s, r.h() * s, color);
    }

    /** Zapuštěný rámeček kolem obdélníku, jako slot v inventáři. */
    void sunken(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r)
    {
        int s = l.scale();
        shapes.bevelRect(l.screenX(r) - s, l.screenBottom(r, screenHeight) - s,
                (r.w() + 2) * s, (r.h() + 2) * s, s,
                Palette.SLOT_OUTLINE, Palette.SLOT_FILL, Palette.SLOT_SHADOW, Palette.SLOT_HIGHLIGHT);
    }

    /** Rámeček daný tloušťkou v pixelech obrazovky (1 = tenká čára i při velkém měřítku). */
    void outline(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r,
                         int thickness, float[] color)
    {
        int s = l.scale();
        shapes.border(l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s, r.h() * s,
                thickness, color);
    }

    private static TextureLabLayout.Rect grow(TextureLabLayout.Rect r)
    {
        return new TextureLabLayout.Rect(r.x() - 1, r.y() - 1, r.w() + 2, r.h() + 2);
    }

    /** Čáry mezi buňkami - jeden pixel obrazovky, ať nezakrývají malované pixely. */
    private void drawGrid(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r,
                          int cells, float[] color)
    {
        drawGridXY(l, screenHeight, r, cells, cells, color);
    }

    /** Mřížka s různým počtem sloupců a řádků - stěny těla nejsou čtvercové. */
    private void drawGridXY(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r,
                            int columns, int rows, float[] color)
    {
        int s = l.scale();
        float x = l.screenX(r), y = l.screenBottom(r, screenHeight);
        float width = r.w() * s, height = r.h() * s;

        for(int i = 1; i < columns; i++)
        {
            shapes.fillRect(x + i * width / columns, y, 1, height, color);
        }

        for(int i = 1; i < rows; i++)
        {
            shapes.fillRect(x, y + i * height / rows, width, 1, color);
        }
    }

    /** Vzorek barvy. Průhlednost je vidět proti šedé pod ním. */
    private void swatch(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r, int argb)
    {
        fill(l, screenHeight, r, SWATCH_BASE);

        if((argb >>> 24) == 0)
        {
            // Guma: úhlopříčný dílek, ať je odlišná od šedého vzorku.
            int s = l.scale();
            shapes.fillRect(l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s / 2f, r.h() * s / 2f,
                    GUM_CHECK);
            shapes.fillRect(l.screenX(r) + r.w() * s / 2f, l.screenBottom(r, screenHeight) + r.h() * s / 2f,
                    r.w() * s / 2f, r.h() * s / 2f, GUM_CHECK);
            return;
        }

        fill(l, screenHeight, r, argbToColor(argb));
    }

    /** Posuvník HSV: pruh s přechodem v dílcích a značka na aktuální hodnotě. */
    private void drawSlider(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect bar, float at)
    {
        int s = l.scale();
        float x = l.screenX(bar), y = l.screenBottom(bar, screenHeight);
        float segment = bar.w() * s / (float) HSV_SEGMENTS;

        for(int i = 0; i < HSV_SEGMENTS; i++)
        {
            float t = (i + 0.5f) / HSV_SEGMENTS;

            int argb;
            if(bar == TextureLabLayout.HUE)             argb = AtlasEditor.hsv(t * 360f, 1f, 1f, 0xFF);
            else if(bar == TextureLabLayout.SATURATION) argb = AtlasEditor.hsv(hue, t, Math.max(value, 0.2f), 0xFF);
            else                                        argb = AtlasEditor.hsv(hue, saturation, t, 0xFF);

            shapes.fillRect(x + i * segment, y, segment + 1, bar.h() * s, argbToColor(argb));
        }

        float marker = x + at * bar.w() * s;
        shapes.fillRect(marker - s, y - s, 2 * s, (bar.h() + 2) * s, Palette.PANEL_OUTLINE);
        shapes.fillRect(marker - s / 2f, y, s, bar.h() * s, Palette.SELECTOR);
    }

    void button(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r,
                        double mouseX, double mouseY)
    {
        int s = l.scale();
        boolean hovered = l.hit(r, mouseX, mouseY);

        shapes.bevelRect(l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s, r.h() * s, s,
                Palette.BUTTON_OUTLINE,
                hovered ? Palette.BUTTON_HOVER_FILL : Palette.BUTTON_FILL,
                hovered ? Palette.BUTTON_HOVER_HIGHLIGHT : Palette.BUTTON_HIGHLIGHT,
                hovered ? Palette.BUTTON_HOVER_SHADOW : Palette.BUTTON_SHADOW);
    }

    private void image(TextureLabLayout l, int screenWidth, int screenHeight, Texture texture,
                       TextureLabLayout.Rect r, float u0, float v0, float u1, float v1)
    {
        int s = l.scale();
        images.draw(texture, screenWidth, screenHeight,
                l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s, r.h() * s,
                u0, v0, u1, v1);
    }

    /**
     * Dlaždice z atlasu do obdélníku. Výřez PŘESNĚ na hranách, ne se zúžením
     * z BlockAtlas: to by krajní pixely ukázalo poloviční. Obdélníky jsou
     * zarovnané na celé pixely, takže středy fragmentů hranu netrefí.
     */
    private void tileImage(TextureLabLayout l, int screenWidth, int screenHeight,
                           TextureLabLayout.Rect r, int tile)
    {
        float u0 = AtlasEditor.tileX0(tile) / (float) AtlasEditor.SIZE;
        float v0 = AtlasEditor.tileY0(tile) / (float) AtlasEditor.SIZE;
        float span = AtlasEditor.TILE / (float) AtlasEditor.SIZE;

        image(l, screenWidth, screenHeight, atlas, r, u0, v0, u0 + span, v0 + span);
    }

    void label(TextureLabLayout l, float guiX, float guiY, String line)
    {
        text.drawShadowed(line, l.textLeft(guiX), l.textTop(guiY), Palette.TEXT, Palette.TEXT_SHADOW);
    }

    void centered(TextureLabLayout l, TextureLabLayout.Rect r, String line)
    {
        float centerX = l.textLeft(r.x() + r.w() / 2f);
        float top = l.textTop(r.y()) + (r.h() * l.scale() - text.lineHeight()) / 2f;
        text.drawCenteredShadowed(line, centerX, Gui.snap(top, l.scale()), Palette.TEXT, Palette.TEXT_SHADOW);
    }

    private static float[] argbToColor(int argb)
    {
        return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f, (argb >>> 24) / 255f};
    }

    /** Jméno bloku do UI - anglicky, atlas fontu je jen ASCII. Bloky z labu mají svoje. */
    static String blockName(byte block)
    {
        BlockDef custom = BlockRegistry.lookup(block);

        if(custom != null)
        {
            return custom.name();
        }

        return switch(block)
        {
            case World.GRASS -> "Grass";
            case World.STONE -> "Stone";
            case World.DIRT -> "Dirt";
            case World.SAND -> "Sand";
            case World.PLANKS -> "Planks";
            case World.COAL_ORE -> "Coal ore";
            case World.IRON_ORE -> "Iron ore";
            case World.WATER -> "Water";
            case World.CRAFTING_TABLE -> "Crafting table";
            case World.STONE_BRICKS -> "Stone bricks";
            case World.LOG -> "Log";
            case World.LEAVES -> "Leaves";
            case World.SNOW -> "Snow";
            case World.BIRCH_LOG -> "Birch log";
            case World.BIRCH_LEAVES -> "Birch leaves";
            case World.SPRUCE_LOG -> "Spruce log";
            case World.SPRUCE_LEAVES -> "Spruce leaves";
            case World.JUNGLE_LEAVES -> "Jungle leaves";
            case World.TORCH -> "Torch";
            case World.FENCE -> "Fence";
            default -> "Block " + block;
        };
    }

    public void delete()
    {
        // Zavření labu s rozepsaným blokem ho zahodí - dočasný registr
        // s návrhem nesmí zůstat aktivní ve hře.
        cancelBlock();

        // Tah dokončený ve stejném framu jako zavření (malování a hned F6)
        // by se jinak na grafiku dostal až při příštím otevření labu.
        upload(editor, atlas);
        upload(skin, skinTexture);

        images.delete();
        checker.delete();
        blockIcons.delete();
        preview.delete();
        skinPreview.delete();

        for(LabMode mode : modes)
        {
            mode.delete();
        }
    }
}
