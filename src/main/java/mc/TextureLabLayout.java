package mc;

/**
 * Rozvržení texture labu a hit-testy myši. Nesahá na GL.
 *
 * ---------------------------------------------------------------------------
 * Všechno je v GUI PIXELECH od LEVÉHO HORNÍHO rohu panelu, jako rozvržení
 * v ContainerScreen, a na obrazovku se násobí CELÝM měřítkem. Lab má ale
 * vlastní referenční velikost (448 x 300), ne 320 x 240 z Gui: potřebuje
 * vedle sebe atlas, plátno i náhled a pod nimi paletu celého atlasu, takže
 * bere největší celé měřítko, při kterém se vejde. Na 1024 x 768 to je 2,
 * na Full HD 3. (S výškou 256, bez palety celého atlasu, to na Full HD
 * byla 4 - paleta stála jeden stupeň, ale i 3 dává lab 1344 x 900.)
 *
 * ⚠️ Plátno i přehled atlasu mají řádek 0 DOLE, jako atlas a GL. Myš chodí
 * z GLFW s počátkem nahoře, takže se řádek při hit-testu překlápí - na jednom
 * místě, tady.
 *
 * ⚠️ OBSAHOVÉ OBDÉLNÍKY JSOU OD LEVÉHO OKRAJE OBSAHU, NE PANELU. Vlevo od
 * obsahu leží boční panel s módy (LabSidebar) a je široký SIDEBAR_WIDTH.
 * Počítá se s tím na JEDNOM místě, v konstruktoru: `left` je levý okraj
 * OBSAHU (panelLeft + šířka pruhu), takže se ani jeden z obdélníků níž
 * zavedením bočního panelu nemusel posunout. Panel sám si říká o `panelLeft()`
 * a `panelGuiX()`.
 *
 * ⚠️ REŽIM SKIN POUŽÍVÁ TYTÉŽ TŘI OBDÉLNÍKY. Vlevo místo přehledu atlasu
 * leží celá kůže 64x64 (dva GUI pixely na pixel kůže - vyjde přesně na
 * 128x128, které má přehled atlasu), uprostřed místo dlaždice vybraná stěna
 * dílu těla a vpravo místo kostky postava. Druhé rozvržení by znamenalo
 * druhý hit-test na každý prvek a dvě místa, kde se to může rozejít.
 *
 * ⚠️ Kůže má ale řádek 0 NAHOŘE (viz SkinLayout) a stěny nejsou čtvercové
 * (obličej 8x8, bok ruky 4x12). Plátno proto dostane největší CELÉ zvětšení,
 * při kterém se stěna vejde, a vycentruje se - půlpixelové zvětšení by
 * u pixel-artu rozmazalo mřížku.
 * ---------------------------------------------------------------------------
 */
public final class TextureLabLayout {

    /** Šířka obsahové části - to, co lab kreslil, než přibyl boční panel. */
    public static final int CONTENT_WIDTH = 448;

    /** Celý panel i s pruhem módů vlevo. */
    public static final int WIDTH = LabSidebar.WIDTH + CONTENT_WIDTH;
    public static final int HEIGHT = 300;

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
    public static final Rect SAVE = new Rect(284, 154, 76, 18);
    public static final Rect REVERT = new Rect(364, 154, 76, 18);
    public static final Rect IMPORT = new Rect(284, 176, 76, 18);
    public static final Rect NEW_BLOCK = new Rect(364, 176, 76, 18);
    public static final Rect CLOSE = new Rect(284, 198, 156, 18);

    /**
     * Barvy celého atlasu - široký pruh pod vším ostatním. Stejný krok
     * a velikost vzorku jako paleta dlaždice, jen 43 sloupců přes celý panel.
     */
    public static final int GLOBAL_COLUMNS = 43;
    public static final int GLOBAL_ROWS = 2;
    public static final Rect GLOBAL = new Rect(8, 252,
            GLOBAL_COLUMNS * SWATCH_PITCH, GLOBAL_ROWS * SWATCH_PITCH);

    // --- nový blok: formulář vlevo místo informací o dlaždici ---
    public static final Rect NAME = new Rect(8, 154, 128, 12);
    public static final Rect SOFTER = new Rect(8, 168, 12, 12);
    public static final Rect HARDER = new Rect(124, 168, 12, 12);

    /** Celý řádek tvrdosti; hodnota se píše mezi - a +. */
    public static final Rect HARDNESS_ROW = new Rect(20, 168, 104, 12);
    public static final Rect SOLID = new Rect(8, 182, 62, 12);
    public static final Rect OPAQUE = new Rect(74, 182, 62, 12);

    /** Políčko stěny: náhled dlaždice a pod ním jméno stěny. Vršek, bok, spodek. */
    public static final int FACE_SLOT_W = 40, FACE_SLOT_H = 30;
    public static final int FACE_SLOT_Y = 196;
    private static final int[] FACE_SLOT_X = {8, 96, 52};   // index = BlockAtlas.FACE_*

    // ... a vpravo místo tlačítek atlasu
    public static final Rect NEW_TILE = new Rect(284, 154, 156, 18);
    public static final Rect CREATE = new Rect(284, 176, 156, 18);
    public static final Rect CANCEL = new Rect(284, 198, 156, 18);

    // ------------------------------------------------------------------
    // mód Recipes
    //
    // ⚠️ Leží ve STEJNÉ obsahové ploše jako Blocks a Skin, ne ve vlastní.
    // Druhé rozvržení by znamenalo druhý hit-test na každý prvek a dvě
    // místa, kde se to může rozejít - tentýž důvod, proč Skin používá
    // tytéž tři obdélníky jako Blocks.
    // ------------------------------------------------------------------

    /** Rozteč buněk mřížky receptu. Slot je 18x18 jako v inventáři. */
    public static final int RECIPE_SLOT = ContainerScreen.SLOT_PITCH;

    /** Mřížka receptu 3x3 - největší, co umí crafting table. */
    public static final Rect RECIPE_GRID = new Rect(24, 40,
            RecipeBook.MAX_SIZE * RECIPE_SLOT, RecipeBook.MAX_SIZE * RECIPE_SLOT);

    /** Výsledek: jeden slot vpravo od mřížky, za šipkou. */
    public static final Rect RECIPE_RESULT = new Rect(
            RECIPE_GRID.x() + RECIPE_GRID.w() + 34, RECIPE_GRID.y() + RECIPE_SLOT,
            RECIPE_SLOT, RECIPE_SLOT);

    /** Šipka mezi mřížkou a výsledkem. */
    public static final Rect RECIPE_ARROW = new Rect(
            RECIPE_GRID.x() + RECIPE_GRID.w() + 8, RECIPE_GRID.y() + RECIPE_SLOT + 7,
            22, 4);

    /** Počet kusů na výstupu: - hodnota +. */
    public static final Rect RECIPE_LESS = new Rect(RECIPE_RESULT.x() - 2, RECIPE_RESULT.y() + 24, 12, 12);
    public static final Rect RECIPE_COUNT = new Rect(RECIPE_RESULT.x() + 10, RECIPE_RESULT.y() + 24, 24, 12);
    public static final Rect RECIPE_MORE = new Rect(RECIPE_RESULT.x() + 34, RECIPE_RESULT.y() + 24, 12, 12);

    /**
     * Přehled bloků, ze kterých se recept skládá. Mřížka slotů přes celou
     * šířku obsahu pod mřížkou receptu; rolovací, protože bloků z labu
     * může být až 64.
     */
    public static final int PICKER_COLUMNS = 22;
    public static final int PICKER_ROWS = 4;
    public static final Rect RECIPE_PICKER = new Rect(8, 128,
            PICKER_COLUMNS * RECIPE_SLOT, PICKER_ROWS * RECIPE_SLOT);

    // Tlačítka módu Recipes - v tomtéž sloupci jako tlačítka atlasu.
    public static final Rect RECIPE_SAVE = new Rect(284, 20, 156, 18);
    public static final Rect RECIPE_CLEAR = new Rect(284, 42, 156, 18);
    public static final Rect RECIPE_CLOSE = new Rect(284, 64, 156, 18);

    /** Kde se vypisuje, co je v souboru a jestli recept platí. */
    public static final int RECIPE_INFO_Y = 92;
    public static final int RECIPE_LIST_Y = 106;
    public static final int PICKER_LABEL_Y = 118;

    // ------------------------------------------------------------------
    // mód Keybinds
    //
    // ⚠️ DVA SLOUPCE, NE JEDEN. Akcí je 27 a na jeden sloupec by při rozteči
    // 14 GUI pixelů potřebovaly 378 pixelů výšky - obsah má 300. Rolování by
    // bylo horší: klávesu hledá uživatel očima podle jména a rolovací seznam
    // znamená, že polovina jmen není vidět, právě když je porovnává.
    // ------------------------------------------------------------------

    public static final int KEY_COLUMNS = 2;
    public static final int KEY_ROWS = 14;

    /** Rozteč řádků; řádek je vysoký 12, takže mezi nimi zbývají 2 pixely. */
    public static final int KEY_ROW_PITCH = 14;
    public static final int KEY_ROW_HEIGHT = 12;

    /**
     * Šířka jednoho sloupce i s mezerou k dalšímu.
     *
     * ⚠️ POČÍTÁ SE Z ŠÍŘKY OBSAHU, neodhaduje se: obsah je 448 a okraje
     * po 8, takže na dva sloupce zbývá 432 a na jeden 216. S 224 by druhý
     * sloupec přetekl přes pravý okraj panelu a jeho tlačítka by končila
     * až za ním. TextureLabTest to hlídá výčtem obdélníků.
     */
    public static final int KEY_COLUMN_PITCH = (CONTENT_WIDTH - 2 * 8) / KEY_COLUMNS;

    /** Tlačítko s klávesou u pravého okraje sloupce; vlevo od něj je jméno akce. */
    public static final int KEY_BUTTON_WIDTH = 74;

    public static final int KEY_FIRST_Y = 22;
    public static final int KEY_LEFT = 8;

    /** Kde se vypisují kolize - dva řádky, víc se jich najednou nestane. */
    public static final int KEY_CONFLICT_Y = 226;
    public static final int KEY_CONFLICT_Y2 = 238;

    public static final Rect KEYBIND_SAVE  = new Rect(8, 252, 104, 18);
    public static final Rect KEYBIND_RESET = new Rect(118, 252, 104, 18);
    public static final Rect KEYBIND_CLOSE = new Rect(228, 252, 104, 18);

    /** Tlačítko s klávesou u akce `index` (index = pořadí v Keybinds.Action). */
    public static Rect keyButton(int index)
    {
        int column = index / KEY_ROWS;
        int row = index % KEY_ROWS;

        // Tlačítko sedí u pravého okraje svého sloupce, o 8 pixelů dovnitř -
        // ta mezera odděluje sloupec od jména akce v tom vedlejším.
        return new Rect(KEY_LEFT + column * KEY_COLUMN_PITCH
                + KEY_COLUMN_PITCH - 8 - KEY_BUTTON_WIDTH,
                KEY_FIRST_Y + row * KEY_ROW_PITCH,
                KEY_BUTTON_WIDTH, KEY_ROW_HEIGHT);
    }

    /** Levý okraj jména akce u toho řádku, v GUI pixelech. */
    public static int keyLabelX(int index)
    {
        return KEY_LEFT + (index / KEY_ROWS) * KEY_COLUMN_PITCH;
    }

    public static int keyLabelY(int index)
    {
        return KEY_FIRST_Y + (index % KEY_ROWS) * KEY_ROW_PITCH + 2;
    }

    /** Index akce, na jejíž tlačítko myš ukazuje, nebo -1. */
    public int keyButtonAt(double mouseX, double mouseY, int count)
    {
        for(int i = 0; i < count; i++)
        {
            if(keyButton(i).contains(guiX(mouseX), guiY(mouseY)))
            {
                return i;
            }
        }

        return -1;
    }

    // ------------------------------------------------------------------
    // mód Biomes (Ore / Biome Tuner)
    //
    // Vlevo řádky čísel, vpravo živý náhled jednoho stromu. Stejná obsahová
    // plocha jako ostatní módy - viz poznámka u módu Recipes.
    // ------------------------------------------------------------------

    /** Záložka biomu nahoře; osm vedle sebe přes celou šířku obsahu. */
    public static final int BIOME_TAB_PITCH = 54;
    public static final int BIOME_TAB_W = 52, BIOME_TAB_H = 16;
    public static final int BIOME_TAB_Y = 20;

    public static Rect biomeTab(int index)
    {
        return new Rect(8 + index * BIOME_TAB_PITCH, BIOME_TAB_Y, BIOME_TAB_W, BIOME_TAB_H);
    }

    public int biomeTabAt(double mouseX, double mouseY, int count)
    {
        for(int i = 0; i < count; i++)
        {
            if(biomeTab(i).contains(guiX(mouseX), guiY(mouseY)))
            {
                return i;
            }
        }

        return -1;
    }

    /** Řádek jednoho čísla: jméno, [-], hodnota, [+]. */
    public static final int TUNE_ROW_PITCH = 18;
    public static final int TUNE_FIRST_Y = 48;
    public static final int TUNE_LESS_X = 130, TUNE_VALUE_X = 146, TUNE_MORE_X = 194;
    public static final int TUNE_BUTTON = 12;

    public static Rect tuneLess(int row)
    {
        return new Rect(TUNE_LESS_X, TUNE_FIRST_Y + row * TUNE_ROW_PITCH,
                TUNE_BUTTON, TUNE_BUTTON);
    }

    public static Rect tuneValue(int row)
    {
        return new Rect(TUNE_VALUE_X, TUNE_FIRST_Y + row * TUNE_ROW_PITCH, 44, TUNE_BUTTON);
    }

    public static Rect tuneMore(int row)
    {
        return new Rect(TUNE_MORE_X, TUNE_FIRST_Y + row * TUNE_ROW_PITCH,
                TUNE_BUTTON, TUNE_BUTTON);
    }

    public static int tuneLabelY(int row)
    {
        return TUNE_FIRST_Y + row * TUNE_ROW_PITCH + 2;
    }

    /** Řádek, na jehož [-] myš ukazuje, nebo -1. */
    public int tuneLessAt(double mouseX, double mouseY, int rows)
    {
        return tuneRowAt(mouseX, mouseY, rows, true);
    }

    /** Řádek, na jehož [+] myš ukazuje, nebo -1. */
    public int tuneMoreAt(double mouseX, double mouseY, int rows)
    {
        return tuneRowAt(mouseX, mouseY, rows, false);
    }

    private int tuneRowAt(double mouseX, double mouseY, int rows, boolean less)
    {
        for(int row = 0; row < rows; row++)
        {
            if((less ? tuneLess(row) : tuneMore(row)).contains(guiX(mouseX), guiY(mouseY)))
            {
                return row;
            }
        }

        return -1;
    }

    /** Živý náhled stromu - vpravo, na místě náhledu bloku a ještě o kus níž. */
    public static final Rect TREE_PREVIEW = new Rect(220, 40, 220, 188);

    public static final Rect TUNE_SAVE   = new Rect(8, 234, 100, 18);
    public static final Rect TUNE_RESET  = new Rect(114, 234, 100, 18);
    public static final Rect TUNE_REROLL = new Rect(220, 234, 104, 18);
    public static final Rect TUNE_CLOSE  = new Rect(330, 234, 110, 18);

    /** Kde se vypisuje, co je v souboru a co tuning udělá. */
    public static final int TUNE_INFO_Y = 258;

    public static final int TITLE_Y = 6;
    public static final int INFO_Y = 154;
    public static final int GLOBAL_LABEL_Y = 241;
    public static final int STATUS_Y = 276;
    public static final int HELP_Y = 288;

    private final int scale;
    private final int panelLeft;
    private final int left;
    private final int top;

    public TextureLabLayout(int screenWidth, int screenHeight)
    {
        scale = scaleFor(screenWidth, screenHeight);
        panelLeft = (int) Gui.snap((screenWidth - WIDTH * scale) / 2f, scale);
        top = (int) Gui.snap((screenHeight - HEIGHT * scale) / 2f, scale);

        // ⚠️ left je levý okraj OBSAHU, ne panelu. Díky tomu zůstaly všechny
        // obdélníky obsahu na svých souřadnicích i po zavedení bočního pruhu.
        left = panelLeft + LabSidebar.WIDTH * scale;
    }

    /** Největší celé měřítko, při kterém se lab vejde. Aspoň 1. */
    public static int scaleFor(int screenWidth, int screenHeight)
    {
        return Math.max(1, Math.min(screenWidth / WIDTH, screenHeight / HEIGHT));
    }

    public int scale() { return scale; }

    /** Levý okraj OBSAHU v pixelech obrazovky (za bočním pruhem). */
    public int left()  { return left; }

    /** Levý okraj celého panelu i s bočním pruhem. */
    public int panelLeft() { return panelLeft; }

    public int top()   { return top; }

    // ------------------------------------------------------------------
    // boční panel
    // ------------------------------------------------------------------

    /** Myš na GUI pixely BOČNÍHO PANELU (počátek na jeho levém okraji). */
    public float panelGuiX(double mouseX) { return (float) ((mouseX - panelLeft) / scale); }

    /** Svislá osa je pro panel i obsah tatáž - oba začínají na `top`. */
    public float panelGuiY(double mouseY) { return guiY(mouseY); }

    public boolean hitPanel(Rect r, double mouseX, double mouseY)
    {
        return r.contains(panelGuiX(mouseX), panelGuiY(mouseY));
    }

    /** Levý okraj obdélníku bočního panelu v pixelech obrazovky. */
    public float panelScreenX(Rect r)
    {
        return panelLeft + r.x() * scale;
    }

    public float panelTextLeft(float guiX)
    {
        return panelLeft + guiX * scale;
    }

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
        return gridAt(SWATCHES, SWATCH_COLUMNS, mouseX, mouseY);
    }

    public static Rect swatchRect(int index)
    {
        return gridRect(SWATCHES, SWATCH_COLUMNS, index);
    }

    /** Vzorek globální palety (barvy celého atlasu) pod myší, nebo -1. */
    public int globalSwatchAt(double mouseX, double mouseY)
    {
        return gridAt(GLOBAL, GLOBAL_COLUMNS, mouseX, mouseY);
    }

    public static Rect globalSwatchRect(int index)
    {
        return gridRect(GLOBAL, GLOBAL_COLUMNS, index);
    }

    private int gridAt(Rect area, int columns, double mouseX, double mouseY)
    {
        float gx = guiX(mouseX) - area.x();
        float gy = guiY(mouseY) - area.y();

        if(gx < 0 || gy < 0 || gx >= area.w() || gy >= area.h()
                || gx % SWATCH_PITCH >= SWATCH_SIZE || gy % SWATCH_PITCH >= SWATCH_SIZE)
        {
            return -1;
        }

        return (int) (gy / SWATCH_PITCH) * columns + (int) (gx / SWATCH_PITCH);
    }

    private static Rect gridRect(Rect area, int columns, int index)
    {
        return new Rect(area.x() + (index % columns) * SWATCH_PITCH,
                area.y() + (index / columns) * SWATCH_PITCH,
                SWATCH_SIZE, SWATCH_SIZE);
    }

    // ------------------------------------------------------------------
    // formulář nového bloku
    // ------------------------------------------------------------------

    /** Políčko stěny; face je BlockAtlas.FACE_*. */
    /** Buňka mřížky receptu (sloupec, řádek) - řádek 0 je NAHOŘE jako v inventáři. */
    public static Rect recipeCell(int column, int row)
    {
        return new Rect(RECIPE_GRID.x() + column * RECIPE_SLOT,
                RECIPE_GRID.y() + row * RECIPE_SLOT, RECIPE_SLOT, RECIPE_SLOT);
    }

    /** Index buňky mřížky receptu pod myší (řádky shora dolů), nebo -1. */
    public int recipeCellAt(double mouseX, double mouseY)
    {
        float gx = guiX(mouseX), gy = guiY(mouseY);

        if(!RECIPE_GRID.contains(gx, gy))
        {
            return -1;
        }

        int column = (int) ((gx - RECIPE_GRID.x()) / RECIPE_SLOT);
        int row = (int) ((gy - RECIPE_GRID.y()) / RECIPE_SLOT);

        return row * RecipeBook.MAX_SIZE + column;
    }

    /** Slot přehledu bloků (index od nuly, po řádcích). */
    public static Rect pickerSlot(int index)
    {
        return new Rect(RECIPE_PICKER.x() + (index % PICKER_COLUMNS) * RECIPE_SLOT,
                RECIPE_PICKER.y() + (index / PICKER_COLUMNS) * RECIPE_SLOT,
                RECIPE_SLOT, RECIPE_SLOT);
    }

    /** Index slotu přehledu pod myší (bez posunu rolováním), nebo -1. */
    public int pickerSlotAt(double mouseX, double mouseY)
    {
        float gx = guiX(mouseX), gy = guiY(mouseY);

        if(!RECIPE_PICKER.contains(gx, gy))
        {
            return -1;
        }

        int column = (int) ((gx - RECIPE_PICKER.x()) / RECIPE_SLOT);
        int row = (int) ((gy - RECIPE_PICKER.y()) / RECIPE_SLOT);

        return row * PICKER_COLUMNS + column;
    }

    public static Rect faceSlot(int face)
    {
        return new Rect(FACE_SLOT_X[face], FACE_SLOT_Y, FACE_SLOT_W, FACE_SLOT_H);
    }

    /** Náhled dlaždice uvnitř políčka stěny - 16 x 16, pixel na pixel jako přehled atlasu. */
    public static Rect faceTileRect(int face)
    {
        Rect slot = faceSlot(face);
        return new Rect(slot.x() + (slot.w() - AtlasEditor.TILE) / 2, slot.y() + 2,
                AtlasEditor.TILE, AtlasEditor.TILE);
    }

    /** Stěna, na jejíž políčko myš ukazuje, nebo -1. */
    public int faceAt(double mouseX, double mouseY)
    {
        for(int face : BlockDraft.FACES)
        {
            if(hit(faceSlot(face), mouseX, mouseY))
            {
                return face;
            }
        }

        return -1;
    }

    // ------------------------------------------------------------------
    // režim skin: celá kůže vlevo, vybraná stěna na plátně
    // ------------------------------------------------------------------

    /** Kolik GUI pixelů zabere jeden pixel kůže v přehledu vlevo. 64 * 2 = 128. */
    public static final int SKIN_ZOOM = ATLAS.w() / SkinLayout.SIZE;

    /** Přehled celé kůže - tentýž obdélník jako přehled atlasu. */
    public static final Rect SKIN_SHEET = ATLAS;

    /**
     * Pixel kůže pod myší jako {u, v} s počátkem VLEVO NAHOŘE (tak leží
     * kůže v poli i v obrázku), nebo null mimo přehled.
     */
    public int[] skinPixelAt(double mouseX, double mouseY)
    {
        float gx = guiX(mouseX), gy = guiY(mouseY);

        if(!SKIN_SHEET.contains(gx, gy))
        {
            return null;
        }

        return new int[]{(int) ((gx - SKIN_SHEET.x()) / SKIN_ZOOM),
                (int) ((gy - SKIN_SHEET.y()) / SKIN_ZOOM)};
    }

    /** Obdélník stěny v přehledu kůže - pro orámování dílů. */
    public static Rect skinFaceRect(int face)
    {
        SkinLayout.Rect r = SkinLayout.rect(face);

        return new Rect(SKIN_SHEET.x() + r.u() * SKIN_ZOOM,
                SKIN_SHEET.y() + r.v() * SKIN_ZOOM,
                r.width() * SKIN_ZOOM, r.height() * SKIN_ZOOM);
    }

    /** Kolik GUI pixelů zabere jeden pixel stěny na plátně. Největší celé, co se vejde. */
    public static int skinCanvasZoom(int face)
    {
        SkinLayout.Rect r = SkinLayout.rect(face);
        return Math.max(1, Math.min(CANVAS.w() / r.width(), CANVAS.h() / r.height()));
    }

    /** Plátno pro vybranou stěnu - vycentrované uvnitř CANVAS. */
    public static Rect skinCanvas(int face)
    {
        SkinLayout.Rect r = SkinLayout.rect(face);
        int zoom = skinCanvasZoom(face);
        int w = r.width() * zoom, h = r.height() * zoom;

        return new Rect(CANVAS.x() + (CANVAS.w() - w) / 2, CANVAS.y() + (CANVAS.h() - h) / 2, w, h);
    }

    /** Pixel stěny pod myší jako {x, y} (y = 0 DOLE, jako u dlaždice), nebo null. */
    public int[] skinCanvasPixelAt(int face, double mouseX, double mouseY)
    {
        Rect canvas = skinCanvas(face);
        float gx = guiX(mouseX), gy = guiY(mouseY);

        if(!canvas.contains(gx, gy))
        {
            return null;
        }

        int zoom = skinCanvasZoom(face);
        int x = (int) ((gx - canvas.x()) / zoom);
        int rowFromTop = (int) ((gy - canvas.y()) / zoom);

        return new int[]{x, SkinLayout.height(face) - 1 - rowFromTop};
    }

    /** Obdélník pixelu (x, y) stěny na plátně - pro zvýraznění pod myší. */
    public static Rect skinCanvasPixelRect(int face, int x, int y)
    {
        Rect canvas = skinCanvas(face);
        int zoom = skinCanvasZoom(face);

        return new Rect(canvas.x() + x * zoom,
                canvas.y() + (SkinLayout.height(face) - 1 - y) * zoom, zoom, zoom);
    }

    /** Poloha myši na posuvníku jako 0 až 1 (mimo se ořízne - kvůli tažení přes okraj). */
    public float sliderValue(Rect bar, double mouseX)
    {
        float t = (guiX(mouseX) - bar.x()) / bar.w();
        return Math.max(0f, Math.min(1f, t));
    }
}
