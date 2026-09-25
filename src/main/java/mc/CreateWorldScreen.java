package mc;

import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Obrazovka "Create New World": jméno světa a seed.
 *
 * ---------------------------------------------------------------------------
 * Tři textová pole a tlačítka, jako v Minecraftu. Jméno je to, co uvidíš
 * v seznamu; složka na disku z něj vznikne očištěním (WorldSaves.folderFor)
 * a pod obrazovkou je pořád vidět, kam se svět uloží - i se závorkou (2),
 * když stejná složka už existuje.
 *
 * Prázdný seed = náhodný svět, cokoliv jiného se použije deterministicky
 * (číslo jako číslo, text přes hashCode) - viz Seeds.parse. Obrazovka sama
 * seed neřeší, jen podrží text; převod dělá Main, aby šel zapsat do metadat.
 *
 * ⚠️ HERNÍ MÓD SE VYBÍRÁ JEN TADY. Je to vlastnost světa a ukládá se vedle
 * seedu do world.json; za běhu už se nemění, protože měnit ho by v Minecraftu
 * znamenalo příkaz /gamemode a příkazová řádka tu není. Přepínač proto cykluje
 * mezi módy jedním tlačítkem, jako v Minecraftu, a pod ním je vidět, co daný
 * mód znamená.
 * ---------------------------------------------------------------------------
 *
 * Kreslení je jediná část, která sahá na GL; vstup a hit-testy jdou testovat.
 */
public final class CreateWorldScreen {

    public enum Action { NONE, CREATE, CANCEL }

    public static final int WIDTH = 320, HEIGHT = 182;

    static final ScreenLayout.Rect NAME_LABEL = new ScreenLayout.Rect(60, 28, 200, 10);
    static final ScreenLayout.Rect NAME = new ScreenLayout.Rect(60, 40, 200, 16);
    static final ScreenLayout.Rect FOLDER = new ScreenLayout.Rect(60, 58, 200, 10);

    static final ScreenLayout.Rect SEED_LABEL = new ScreenLayout.Rect(60, 76, 200, 10);
    static final ScreenLayout.Rect SEED = new ScreenLayout.Rect(60, 88, 200, 16);
    static final ScreenLayout.Rect SEED_HINT = new ScreenLayout.Rect(60, 106, 200, 10);

    static final ScreenLayout.Rect MODE = new ScreenLayout.Rect(60, 120, 200, 20);
    static final ScreenLayout.Rect MODE_HINT = new ScreenLayout.Rect(60, 142, 200, 10);

    static final ScreenLayout.Rect CREATE = new ScreenLayout.Rect(60, 154, 98, 20);
    static final ScreenLayout.Rect CANCEL = new ScreenLayout.Rect(162, 154, 98, 20);

    /** Seed se vejde i jako nejdelší long se znaménkem; jméno jako v metadatech. */
    static final int SEED_LENGTH = 32;

    private final Widgets widgets;
    private final Path root;

    private final TextField name = new TextField(WorldSaves.MAX_NAME_LENGTH);
    private final TextField seed = new TextField(SEED_LENGTH);

    /** Vybraný mód. Survival je výchozí - jako v Minecraftu. */
    private GameMode mode = GameMode.SURVIVAL;

    // Cache náhledu složky - počítá se ze jména a sahá na disk, takže se
    // nepřepočítává každý frame, ale jen když se jméno změní.
    private String folderFor = null;
    private String folderCache = "";

    public CreateWorldScreen(Widgets widgets, Path root)
    {
        this.widgets = widgets;
        this.root = root;
        reset();
    }

    /** Nová obrazovka: výchozí jméno vybrané k přepsání, prázdný seed. */
    public void reset()
    {
        name.setText(WorldSaves.DEFAULT_NAME);
        name.setFocused(true);
        seed.clear();
        seed.setFocused(false);
        mode = GameMode.SURVIVAL;
        folderFor = null;
    }

    public String name()     { return name.text(); }
    public String seedText() { return seed.text(); }
    public GameMode mode()   { return mode; }

    /** Složka, do které svět půjde - i s odlišením, když jméno už někdo má. */
    public String folder()
    {
        if(!name.text().equals(folderFor))
        {
            folderFor = name.text();
            folderCache = WorldSaves.uniqueFolder(root, WorldSaves.folderFor(WorldSaves.cleanName(name.text())));
        }

        return folderCache;
    }

    // ------------------------------------------------------------------
    // vstup
    // ------------------------------------------------------------------

    /**
     * Trefil poslední klik tlačítko, které obrazovka obsloužila sama (bez
     * akce pro Main)? Main podle toho zahraje zvuk kliknutí - dřív zněla
     * jen tlačítka, jejichž akci vracela obrazovka, a stejně vypadající
     * přepínače vedle nich mlčely.
     */
    private boolean buttonClicked = false;

    public boolean takeClicked()
    {
        boolean was = buttonClicked;
        buttonClicked = false;
        return was;
    }

    public Action press(double mouseX, double mouseY, int screenWidth, int screenHeight)
    {
        ScreenLayout l = layout(screenWidth, screenHeight);

        name.setFocused(l.hit(NAME, mouseX, mouseY));
        seed.setFocused(l.hit(SEED, mouseX, mouseY));

        // Cyklující tlačítko, ne dvě: se dvěma módy by "vybráno / nevybráno"
        // zabralo dvakrát tolik místa a přibyl by stav navíc.
        if(l.hit(MODE, mouseX, mouseY))
        {
            mode = mode.next();
            buttonClicked = true;
            return Action.NONE;
        }

        if(l.hit(CREATE, mouseX, mouseY))
        {
            return Action.CREATE;
        }
        if(l.hit(CANCEL, mouseX, mouseY))
        {
            return Action.CANCEL;
        }

        return Action.NONE;
    }

    /** Napsaný znak z char callbacku. */
    public void typed(int codepoint)
    {
        name.type(codepoint);
        seed.type(codepoint);
    }

    /**
     * Klávesa. Enter zakládá, Esc ruší, Tab přepíná pole, Ctrl+V vloží
     * schránku (seedy se obvykle odněkud kopírují).
     */
    /**
     * Je to vložení ze schránky? Ctrl+V, a na macOS Cmd+V (GLFW_MOD_SUPER) -
     * hra Mac podporuje a tam se Ctrl+V nepoužívá. Main podle toho čte
     * schránku jen tehdy, ne při každé klávese.
     */
    static boolean isPaste(int key, int mods)
    {
        return key == GLFW_KEY_V && (mods & (GLFW_MOD_CONTROL | GLFW_MOD_SUPER)) != 0;
    }

    public Action key(int key, int mods, String clipboard)
    {
        if(isPaste(key, mods))
        {
            if(name.isFocused()) name.insert(clipboard);
            if(seed.isFocused()) seed.insert(clipboard);
            return Action.NONE;
        }

        return switch(key)
        {
            case GLFW_KEY_ESCAPE -> Action.CANCEL;
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> Action.CREATE;
            case GLFW_KEY_BACKSPACE -> { name.backspace(); seed.backspace(); yield Action.NONE; }
            case GLFW_KEY_TAB -> {
                boolean toSeed = name.isFocused();
                name.setFocused(!toSeed);
                seed.setFocused(toSeed);
                yield Action.NONE;
            }
            default -> Action.NONE;
        };
    }

    static ScreenLayout layout(int screenWidth, int screenHeight)
    {
        return new ScreenLayout(WIDTH, HEIGHT, screenWidth, screenHeight);
    }

    // ------------------------------------------------------------------
    // kreslení
    // ------------------------------------------------------------------

    public void render(int screenWidth, int screenHeight, double mouseX, double mouseY)
    {
        ScreenLayout l = layout(screenWidth, screenHeight);

        widgets.shapes.begin(screenWidth, screenHeight);
        widgets.textField(l, screenHeight, NAME, name.isFocused());
        widgets.textField(l, screenHeight, SEED, seed.isFocused());
        widgets.button(l, screenHeight, MODE, l.hit(MODE, mouseX, mouseY), true);
        widgets.button(l, screenHeight, CREATE, l.hit(CREATE, mouseX, mouseY), true);
        widgets.button(l, screenHeight, CANCEL, l.hit(CANCEL, mouseX, mouseY), true);
        widgets.shapes.end();

        widgets.text.begin(screenWidth, screenHeight, l.scale() * 2);
        widgets.text.drawCenteredShadowed("Create New World", l.textLeft(WIDTH / 2f), l.textTop(4),
                Palette.TEXT, Palette.TEXT_SHADOW);
        widgets.text.end();

        widgets.text.begin(screenWidth, screenHeight, l.scale());

        widgets.label(l, NAME_LABEL.x(), NAME_LABEL.y(), "World Name");
        // ⚠️ fitEnd: 32 znaků "W" je 256 GUI px a pole má pro text 192 -
        // dlouhé jméno nebo seed vyjížděly z pole i z panelu. Vidět je konec,
        // tedy to, co se právě píše.
        widgets.label(l, NAME.x() + 4, NAME.y() + 4, widgets.text.fitEnd(caret(name), NAME.w() - 8));
        widgets.muted(l, FOLDER.x(), FOLDER.y(),
                widgets.fit("Will be saved in: saves/" + folder(), FOLDER.w(), l.scale()));

        widgets.label(l, SEED_LABEL.x(), SEED_LABEL.y(), "Seed for the World Generator");
        widgets.label(l, SEED.x() + 4, SEED.y() + 4, widgets.text.fitEnd(caret(seed), SEED.w() - 8));
        widgets.muted(l, SEED_HINT.x(), SEED_HINT.y(), seed.text().isBlank()
                ? "Leave blank for a random seed"
                : widgets.fit("Same seed = same world", SEED_HINT.w(), l.scale()));

        widgets.centered(l, MODE, "Game Mode: " + mode.label());
        widgets.muted(l, MODE_HINT.x(), MODE_HINT.y(),
                widgets.fit(mode.description(), MODE_HINT.w(), l.scale()));

        widgets.centered(l, CREATE, "Create");
        widgets.centered(l, CANCEL, "Cancel");

        widgets.text.end();
    }

    /** Text pole s kurzorem, když je v něm fokus. */
    private static String caret(TextField field)
    {
        return field.text() + (field.isFocused() ? "_" : "");
    }
}
