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
 * Dva režimy. V ATLASU se malují dlaždice. V NOVÉM BLOKU nahradí informace
 * o dlaždici formulář (jméno, tvrdost, pevný, neprůhledný, dlaždice stěn)
 * a klik do atlasu přiřadí dlaždici vybrané stěně. Malování, paleta
 * i náhled fungují dál - náhled ukazuje rozepsaný blok, protože lab po
 * každé změně aktivuje dočasný registr s návrhem (BlockDraft) a postaví
 * náhled znovu. Cancel i zavření labu vrátí původní registr.
 *
 * Všechno bez GL (souřadnice, malování, barvy, soubory, návrh bloku) je
 * v AtlasEditor, TextureLabLayout, AtlasImage, BlockDraft a BlockRegistry;
 * tady je jen kreslení a vstup.
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

    private final AtlasEditor editor;
    private final Texture atlas;
    private final Renderer2D shapes;
    private final TextRenderer text;

    // Vlastní GL prostředky labu - vznikají s ním a s ním se mažou.
    private final ImageRenderer images = new ImageRenderer();
    private final Texture checker;
    private final BlockPreview preview = new BlockPreview();

    /** Měření času vykreslení labu po fázích - zapíná se v labu klávesou F3. */
    private final LabProfiler profiler = new LabProfiler();

    /** Odkud atlas pochází: true = textures/atlas.png, false = procedurální. */
    private boolean fromFile;

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

    /** Pro kterou dlaždici a revizi platí paleta dlaždice. */
    private int paletteRevision = -1;
    private int paletteTile = -1;

    /** Poslední zvýraznění "kde ta barva je" - pro kterou barvu a revizi platí. */
    private boolean[] usesColor = null;
    private int usesArgb = 0;
    private int usesRevision = -1;

    /** Poslední seznam bloků používajících vybranou dlaždici. */
    private List<Byte> usingBlocks = List.of();
    private int usingTile = -1;
    private BlockRegistry usingRegistry = null;

    /** Odkud se importuje: textures/import.png, nebo poslední soubor přetažený do okna. */
    private Path importFile = Textures.IMPORT_FILE;

    // --- nový blok ---

    /** Rozepsaný blok, nebo null v režimu atlasu. */
    private BlockDraft draft = null;

    /** Registr, jaký byl před návrhem - Cancel a zavření labu ho vrátí. */
    private BlockRegistry baseRegistry = null;

    private boolean editingName = false;

    /** Založené bloky, které si ještě nevyzvedl Main (dá je hráči). */
    private final List<Byte> createdBlocks = new ArrayList<>();

    public TextureLab(int[] atlasPixels, Texture atlas, boolean fromFile,
                      Renderer2D shapes, TextRenderer text)
    {
        this.editor = new AtlasEditor(atlasPixels);
        this.atlas = atlas;
        this.fromFile = fromFile;
        this.shapes = shapes;
        this.text = text;

        checker = Texture.fromArgb(new int[]{0xFF9A9A9A, 0xFF6A6A6A, 0xFF6A6A6A, 0xFF9A9A9A},
                2, 2, GL_REPEAT);

        selectTile(0);
        setColor(editor.get(0, 0));
    }

    /** Měřič fází vykreslení - pro sondy, které lab kreslí mimo hru. */
    LabProfiler profiler()
    {
        return profiler;
    }

    /** Pochází atlas teď ze souboru? Main to ukazuje v ladicím výpisu. */
    public boolean fromFile()
    {
        return fromFile;
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

    // ------------------------------------------------------------------
    // stav
    // ------------------------------------------------------------------

    private void selectTile(int tile)
    {
        editor.select(tile);
        previewChoice = 0;

        // V režimu nového bloku ukazuje náhled pořád návrh.
        if(draft == null)
        {
            refreshPreview();
        }
    }

    private void refreshPreview()
    {
        List<Byte> blocks = blocksUsingSelected();
        preview.show(blocks.isEmpty() ? World.AIR : blocks.get(previewChoice % blocks.size()));
    }

    private void setColor(int argb)
    {
        editor.setColor(argb);

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
        int alpha = editor.color() >>> 24;
        editor.setColor(AtlasEditor.hsv(hue, saturation, value, alpha == 0 ? 0xFF : alpha));
    }

    private void say(String message)
    {
        status = message;
        statusLeft = STATUS_SECONDS;
    }

    private void save()
    {
        if(AtlasImage.save(editor.pixels(), Textures.ATLAS_FILE))
        {
            editor.markSaved();
            fromFile = true;
            say("Saved " + Textures.ATLAS_FILE.toString().replace('\\', '/'));
        }
        else
        {
            say("Save failed - see console");
        }
    }

    /** Zpátky k tomu, co je na disku: uložený PNG, jinak procedurální atlas. */
    private void revert()
    {
        Textures.AtlasPixels source = Textures.atlasPixels(Textures.ATLAS_FILE);
        editor.replaceAll(source.pixels());
        fromFile = source.fromFile();
        say(fromFile ? "Reverted to saved PNG" : "Reverted to procedural");
    }

    /** Import hotového PNG do editoru. Při chybě zůstane atlas, jak byl. */
    private void importAtlas()
    {
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

        importAtlas();
    }

    public void update(float dt)
    {
        preview.update(dt);
        statusLeft -= dt;
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
        int free = BlockDraft.freeTile(baseRegistry, draft.tiles);

        if(free < 0)
        {
            say("Atlas is full - reuse an existing tile");
            return;
        }

        editor.copyTile(draft.tiles[draft.activeFace], free);
        draft.tiles[draft.activeFace] = free;
        selectTile(free);
        showDraft();
        say("Tile " + free + " is new - paint it on the canvas");
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
            say("Atlas save failed - block not created, see console");
            return;
        }

        editor.markSaved();
        fromFile = true;

        if(!created.save(BlockRegistry.FILE))
        {
            say("Saving blocks.json failed - block not created, see console");
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
    // vstup
    // ------------------------------------------------------------------

    /** Zmáčknutí tlačítka myši. Vrací true, když se kliklo na Close. */
    public boolean press(double mouseX, double mouseY, int screenWidth, int screenHeight, boolean left)
    {
        TextureLabLayout layout = new TextureLabLayout(screenWidth, screenHeight);

        // Klik jinam ukončí psaní hexu - platný se použije, neplatný zahodí.
        if(hexInput != null && !layout.hit(TextureLabLayout.HEX, mouseX, mouseY))
        {
            commitHex();
        }

        if(editingName && !layout.hit(TextureLabLayout.NAME, mouseX, mouseY))
        {
            editingName = false;
        }

        int[] pixel = layout.canvasPixelAt(mouseX, mouseY);

        if(pixel != null)
        {
            if(left)
            {
                painting = true;
                editor.beginStroke(pixel[0], pixel[1]);
            }
            else
            {
                setColor(editor.pick(pixel[0], pixel[1]));
                say("Picked " + AtlasEditor.toHex(editor.color()));
            }
            return false;
        }

        if(!left)
        {
            return false;
        }

        int tile = layout.tileAt(mouseX, mouseY);

        if(tile >= 0)
        {
            selectTile(tile);

            // V novém bloku klik do atlasu zároveň přiřadí dlaždici stěně.
            if(draft != null)
            {
                draft.tiles[draft.activeFace] = tile;
                showDraft();
            }
            return false;
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
            hexInput = new StringBuilder(AtlasEditor.toHex(editor.color()).substring(1));
            return false;
        }

        return draft != null
                ? pressBlockForm(layout, mouseX, mouseY)
                : pressAtlasButtons(layout, mouseX, mouseY);
    }

    private boolean pressAtlasButtons(TextureLabLayout layout, double mouseX, double mouseY)
    {
        if(layout.hit(TextureLabLayout.PREVIEW, mouseX, mouseY))
        {
            // Dlaždici může používat víc bloků (hlína: hlína a spodek trávy).
            previewChoice++;
            refreshPreview();
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
            importAtlas();
        }
        else if(layout.hit(TextureLabLayout.NEW_BLOCK, mouseX, mouseY))
        {
            startBlock();
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
    public void drag(double mouseX, double mouseY, int screenWidth, int screenHeight)
    {
        TextureLabLayout layout = new TextureLabLayout(screenWidth, screenHeight);

        if(painting)
        {
            // Mimo plátno se tah přitiskne k okraji, ať se čára u kraje neutrhne.
            float gx = layout.guiX(mouseX) - TextureLabLayout.CANVAS.x();
            float gy = layout.guiY(mouseY) - TextureLabLayout.CANVAS.y();

            int x = clamp((int) Math.floor(gx / TextureLabLayout.CANVAS_PIXEL));
            int y = AtlasEditor.TILE - 1 - clamp((int) Math.floor(gy / TextureLabLayout.CANVAS_PIXEL));

            editor.strokeTo(x, y);
        }
        else if(draggedSlider != null)
        {
            slide(layout, mouseX);
        }
    }

    public void release()
    {
        if(painting)
        {
            editor.endStroke();
            painting = false;
        }

        draggedSlider = null;
    }

    /**
     * Napsaný znak (GLFW char callback, ne klávesa) - na jméno nového bloku.
     * Znaky, ne kódy kláves: velká písmena, mezery i rozložení klávesnice
     * pak fungují samy. Bere se jen ASCII, font nic jiného nemá.
     */
    public void typed(int codepoint)
    {
        if(draft == null || !editingName || codepoint < 32 || codepoint > 126
                || codepoint == '"' || codepoint == '\\')
        {
            return;
        }

        if(draft.name.length() < BlockRegistry.MAX_NAME_LENGTH)
        {
            draft.name += (char) codepoint;
        }
    }

    /**
     * Klávesa. Vrací true, když se má lab zavřít (Esc, F6).
     * Při psaní hexu nebo jména patří klávesy poli, ne zkratkám.
     */
    public boolean key(int key, int mods)
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
            return false;
        }

        if(editingName)
        {
            if(key == GLFW_KEY_ESCAPE || key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER
                    || key == GLFW_KEY_TAB)
            {
                editingName = false;
            }
            else if(key == GLFW_KEY_BACKSPACE && !draft.name.isEmpty())
            {
                draft.name = draft.name.substring(0, draft.name.length() - 1);
            }
            // Písmena přijdou přes typed(); zkratky se při psaní nespouští.
            return false;
        }

        // F3 jako ladicí výpis ve hře: čas fází vykreslení labu a draw cally.
        if(key == GLFW_KEY_F3)
        {
            profiler.toggle();
            say(profiler.enabled() ? "Frame timing on" : "Frame timing off");
            return false;
        }

        if(ctrl && key == GLFW_KEY_Z)
        {
            say(editor.undo() ? "Undo" : "Nothing to undo");

            if(draft == null)
            {
                refreshPreview();
            }
            return false;
        }

        if(ctrl && key == GLFW_KEY_S)
        {
            save();
            return false;
        }

        // Esc v novém bloku ruší blok, ne celý lab - rozepsaná práce se tak
        // omylem nezahodí i s labem.
        if(key == GLFW_KEY_ESCAPE && draft != null)
        {
            cancelBlock();
            refreshPreview();
            say("New block cancelled");
            return false;
        }

        return key == GLFW_KEY_ESCAPE || key == GLFW_KEY_F6;
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

    private static int clamp(int pixel)
    {
        return Math.max(0, Math.min(AtlasEditor.TILE - 1, pixel));
    }

    // ------------------------------------------------------------------
    // kreslení
    // ------------------------------------------------------------------

    public void render(int screenWidth, int screenHeight, double mouseX, double mouseY)
    {
        profiler.beginFrame();

        // Jedno nahrání za frame, ať se maluje jakkoliv rychle - a jen
        // OBDÉLNÍK, který se změnil (při malování jedna dlaždice, 1 KB
        // místo celých 64 KB atlasu). Viz DirtyRect.
        profiler.start(LabProfiler.UPLOAD);
        DirtyRect changed = editor.dirty();

        if(!changed.isEmpty())
        {
            atlas.updateRegion(editor.pixels(), changed.x(), changed.y(),
                    changed.width(), changed.height());
            editor.clearDirty();
        }
        profiler.stop(LabProfiler.UPLOAD);

        TextureLabLayout layout = new TextureLabLayout(screenWidth, screenHeight);
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

        shapes.bevelRect(layout.left(), screenHeight - layout.top() - TextureLabLayout.HEIGHT * scale,
                TextureLabLayout.WIDTH * scale, TextureLabLayout.HEIGHT * scale, scale,
                Palette.PANEL_OUTLINE, Palette.CONTAINER_FILL,
                Palette.CONTAINER_HIGHLIGHT, Palette.CONTAINER_SHADOW);

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

        // --- atlas a plátno přímo z textury ---
        profiler.start(LabProfiler.IMAGES);
        image(layout, screenWidth, screenHeight, checker, TextureLabLayout.ATLAS, 0f, 0f, 32f, 32f);
        image(layout, screenWidth, screenHeight, atlas, TextureLabLayout.ATLAS, 0f, 0f, 1f, 1f);

        int tile = editor.tile();

        image(layout, screenWidth, screenHeight, checker, TextureLabLayout.CANVAS, 0f, 0f, 8f, 8f);
        tileImage(layout, screenWidth, screenHeight, TextureLabLayout.CANVAS, tile);

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

        for(int i = 0; i < paletteCount; i++)
        {
            drawSwatch(layout, screenHeight, TextureLabLayout.swatchRect(i), palette[i]);
        }

        for(int i = 0; i < globalColors.length; i++)
        {
            drawSwatch(layout, screenHeight, TextureLabLayout.globalSwatchRect(i), globalColors[i]);
        }

        swatch(layout, screenHeight, TextureLabLayout.CURRENT, editor.color());
        sunken(layout, screenHeight, TextureLabLayout.HEX);

        drawSlider(layout, screenHeight, TextureLabLayout.HUE, hue / 360f);
        drawSlider(layout, screenHeight, TextureLabLayout.SATURATION, saturation);
        drawSlider(layout, screenHeight, TextureLabLayout.VALUE, value);

        if(draft == null)
        {
            button(layout, screenHeight, TextureLabLayout.SAVE, mouseX, mouseY);
            button(layout, screenHeight, TextureLabLayout.REVERT, mouseX, mouseY);
            button(layout, screenHeight, TextureLabLayout.IMPORT, mouseX, mouseY);
            button(layout, screenHeight, TextureLabLayout.NEW_BLOCK, mouseX, mouseY);
            button(layout, screenHeight, TextureLabLayout.CLOSE, mouseX, mouseY);
        }
        else
        {
            drawBlockForm(layout, screenHeight, mouseX, mouseY);
        }

        shapes.end();
        profiler.stop(LabProfiler.SHAPES);

        // --- živý náhled přes světový shader ---
        profiler.start(LabProfiler.PREVIEW);
        TextureLabLayout.Rect p = TextureLabLayout.PREVIEW;
        preview.draw(atlas, (int) layout.screenX(p), (int) layout.screenBottom(p, screenHeight),
                p.w() * scale, p.h() * scale, screenWidth, screenHeight);
        profiler.stop(LabProfiler.PREVIEW);

        profiler.start(LabProfiler.TEXT);
        drawTexts(layout, screenWidth, screenHeight, mouseX, mouseY);
        profiler.stop(LabProfiler.TEXT);

        profiler.endFrame();
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
        if((paletteRevision == editor.revision() && paletteTile == editor.tile())
                || editor.isStroking())
        {
            return;
        }

        System.arraycopy(BASIC_COLORS, 0, palette, 0, BASIC_COLORS.length);
        int[] tileColors = editor.tileColors(palette.length - BASIC_COLORS.length);
        System.arraycopy(tileColors, 0, palette, BASIC_COLORS.length, tileColors.length);
        paletteCount = BASIC_COLORS.length + tileColors.length;

        paletteRevision = editor.revision();
        paletteTile = editor.tile();
    }

    /**
     * ⚠️ JEDEN průchod atlasem, ne dva. Dřív se atlasColors() volalo dvakrát -
     * jednou na zobrazených 86 barev a jednou na všechny, jen aby se zjistil
     * jejich počet - a při malování se to dělo při každém namalovaném pixelu.
     */
    private void refreshGlobalPalette()
    {
        if(globalRevision == editor.revision() || editor.isStroking())
        {
            return;
        }

        int shown = TextureLabLayout.GLOBAL_COLUMNS * TextureLabLayout.GLOBAL_ROWS;
        AtlasEditor.Colors colors = AtlasEditor.countColors(editor.pixels());

        globalColors = AtlasEditor.byHue(colors.top(shown));
        globalTotal = colors.total();
        globalRevision = editor.revision();
    }

    /**
     * Dlaždice obsahující barvu. Myš nad vzorkem stojí desítky framů v kuse,
     * takže se výsledek drží, dokud se nezmění barva ani pixely - jinak by
     * se 16 384 pixelů procházelo pořád dokola.
     */
    private boolean[] tilesWithColor(int argb)
    {
        if(usesColor == null || usesArgb != argb || usesRevision != editor.revision())
        {
            usesColor = AtlasEditor.tilesWithColor(editor.pixels(), argb);
            usesArgb = argb;
            usesRevision = editor.revision();
        }

        return usesColor;
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

        if(argb == editor.color())
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

    private void drawTexts(TextureLabLayout layout, int screenWidth, int screenHeight,
                           double mouseX, double mouseY)
    {
        int scale = layout.scale();
        text.begin(screenWidth, screenHeight, scale);

        String source = fromFile ? Textures.ATLAS_FILE.toString().replace('\\', '/') : "procedural";
        String unsaved = editor.isUnsaved() ? "  (unsaved)" : "";

        if(draft == null)
        {
            label(layout, 8, TextureLabLayout.TITLE_Y,
                    fit("Texture Lab   atlas: " + source + unsaved, TextureLabLayout.WIDTH - 16, scale));
            drawTileInfo(layout);
        }
        else
        {
            label(layout, 8, TextureLabLayout.TITLE_Y, fit("Texture Lab   new block, id "
                    + baseRegistry.nextId() + "   atlas: " + source + unsaved, TextureLabLayout.WIDTH - 16, scale));
            drawFormTexts(layout);
        }

        String hex = hexInput != null ? "#" + hexInput + "_" : AtlasEditor.toHex(editor.color());
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
                "Atlas colors: " + shown + ", by hue - hover shows where they are");

        // Zapnuté měření mluví na týchž dvou řádcích jako stav a nápověda -
        // je to dočasný ladicí režim, ne trvalá část rozhraní.
        if(profiler.enabled())
        {
            String[] lines = profiler.lines();
            label(layout, 8, TextureLabLayout.STATUS_Y, fit(lines[0], TextureLabLayout.WIDTH - 16, scale));
            text.draw(fit(lines[1], TextureLabLayout.WIDTH - 16, scale),
                    layout.textLeft(8), layout.textTop(TextureLabLayout.HELP_Y), Palette.TEXT_MUTED);
            text.end();
            return;
        }

        if(statusLeft > 0f)
        {
            label(layout, 8, TextureLabLayout.STATUS_Y, fit(status, TextureLabLayout.WIDTH - 16, scale));
        }

        text.draw(fit(help(layout, mouseX, mouseY), TextureLabLayout.WIDTH - 16, scale),
                layout.textLeft(8), layout.textTop(TextureLabLayout.HELP_Y), Palette.TEXT_MUTED);

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
        centered(layout, TextureLabLayout.CLOSE, "Close  (Esc / F6)");
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
    private String help(TextureLabLayout l, double mouseX, double mouseY)
    {
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

    /** Zkrátí text tak, aby se vešel do šířky v GUI pixelech (se třemi tečkami). */
    private String fit(String line, int guiWidth, int scale)
    {
        float limit = guiWidth * scale;

        if(text.widthOf(line) <= limit)
        {
            return line;
        }

        String cut = line;
        while(cut.length() > 1 && text.widthOf(cut + "...") > limit)
        {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    // ------------------------------------------------------------------
    // pomocné kreslení (všechno v GUI pixelech panelu)
    // ------------------------------------------------------------------

    private void fill(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r, float[] color)
    {
        int s = l.scale();
        shapes.fillRect(l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s, r.h() * s, color);
    }

    /** Zapuštěný rámeček kolem obdélníku, jako slot v inventáři. */
    private void sunken(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r)
    {
        int s = l.scale();
        shapes.bevelRect(l.screenX(r) - s, l.screenBottom(r, screenHeight) - s,
                (r.w() + 2) * s, (r.h() + 2) * s, s,
                Palette.SLOT_OUTLINE, Palette.SLOT_FILL, Palette.SLOT_SHADOW, Palette.SLOT_HIGHLIGHT);
    }

    /** Rámeček daný tloušťkou v pixelech obrazovky (1 = tenká čára i při velkém měřítku). */
    private void outline(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r,
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
        int s = l.scale();
        float x = l.screenX(r), y = l.screenBottom(r, screenHeight);
        float width = r.w() * s, height = r.h() * s;

        for(int i = 1; i < cells; i++)
        {
            shapes.fillRect(x + i * width / cells, y, 1, height, color);
            shapes.fillRect(x, y + i * height / cells, width, 1, color);
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

    private void button(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r,
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

    private void label(TextureLabLayout l, float guiX, float guiY, String line)
    {
        text.drawShadowed(line, l.textLeft(guiX), l.textTop(guiY), Palette.TEXT, Palette.TEXT_SHADOW);
    }

    private void centered(TextureLabLayout l, TextureLabLayout.Rect r, String line)
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

        images.delete();
        checker.delete();
        preview.delete();
    }
}
