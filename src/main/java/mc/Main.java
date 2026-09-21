package mc;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Minecraft-like base:
 *  - GLFW okno, OpenGL 3.3 core profile
 *  - nekonečný svět po chuncích (World), mesh v VBO (WorldRenderer)
 *  - hráč s hitboxem, gravitací a kolizemi (Player)
 *  - stavový automat menu / loading / hra (GameState)
 */
public class Main {

    /**
     * Nastavení z options.json. Načte se při startu, mění ho obrazovka Options
     * a klávesy F11 / V; applyOptions() je pak rozveze tam, kam patří.
     */
    private final Options options = Options.load(Options.FILE);

    /** Okno / celá obrazovka a strop FPS - obojí řídí nastavení. */
    private final WindowMode windowMode = new WindowMode();
    private final FrameLimiter limiter = new FrameLimiter();

    /** Odkud se začíná hledat suchá zem pro spawn. */
    private static final int SPAWN_SEARCH_X = 8;
    private static final int SPAWN_SEARCH_Z = 8;
    private static final int SPAWN_SEARCH_RADIUS = 64;

    private long window;
    private int width = 1024, height = 768;

    private final Camera camera = new Camera();
    private final Player player = new Player();
    World world = new World(); // nahrazuje se při vytvoření nového světa

    /**
     * Zvuk. Žije stejně jako svět: při založení nebo načtení světa se zavře
     * a otevře nový, při ukončení hry zavře. Otevírá se v init().
     */
    private SoundEngine sound;

    // Vytváří se až po GL.createCapabilities(), proto nejsou inicializované u deklarace.
    private WorldRenderer worldRenderer;
    private Renderer2D shapes;
    private TextRenderer text;
    private FontAtlas font;
    private BackgroundRenderer background;
    private Texture dirtTile;
    private Hud hud;

    private GameState state = GameState.MAIN_MENU;

    /**
     * Denní doba. Běží jen ve hře, v menu i v inventáři stojí.
     *
     * ⚠️ Balíčkově viditelné schválně: patří mezi stav, který se při výměně
     * světa musí resetovat (viz resetPlayerState()), a `MainStateTest` ho
     * na tom kontroluje. Totéž platí pro inventory, crafting mřížky
     * a selectedSlot níž.
     */
    final DayCycle day = new DayCycle();

    // Texty UI jsou anglicky schválně: atlas fontu pokrývá jen ASCII 32-126,
    // takže česká diakritika by se vykreslila jako otazníky.
    // Světy jsou v saves/<složka>/ a vybírají se na vlastní obrazovce, takže
    // hlavní menu má jen "Singleplayer" a nemusí se skládat znovu.
    static final Menu MAIN_MENU_LABELS =
            new Menu("Minecraft Base", "Singleplayer", "Options", "Lab", "Quit");
    static final Menu PAUSE_MENU_LABELS =
            new Menu("Paused", "Resume", "Options", "Save and Quit to Title");

    private final Menu mainMenu = MAIN_MENU_LABELS;
    private final Menu pauseMenu = PAUSE_MENU_LABELS;

    // mouse look state
    private double lastX, lastY;
    private boolean firstMouse = true;

    /**
     * Poloha kurzoru pro menu a obrazovky: počátek vlevo nahoře jako v GLFW,
     * ale už PŘEPOČÍTANÁ na pixely framebufferu - v nich se kreslí i hit-testuje.
     * Viz MouseScale (na Retině je framebuffer dvakrát větší než okno).
     */
    private double mouseX, mouseY;

    /** Poměr pixelů framebufferu na bod okna; na běžném displeji 1. */
    private final MouseScale mouseScale = new MouseScale();

    /**
     * Existují už ukazatele na funkce OpenGL (GL.createCapabilities())?
     *
     * ⚠️ Volat GL dřív je pád v nativním kódu, ne výjimka - a callbacky GLFW
     * se umí spustit ještě během init(). Viz callback velikosti framebufferu.
     */
    private boolean glReady = false;

    private Raycaster.RaycastHit hit;

    /** Kopání: drží se tlačítko a jak daleko je rozbíjení. */
    private final Mining mining = new Mining();
    private boolean miningHeld = false;

    int selectedSlot = 0;

    // --- inventář ---
    final Inventory inventory = new Inventory();

    /** Malá mřížka u inventáře a velká na crafting table; výsledek je sdílený. */
    final Container craftingSmall = new Container(4);
    final Container craftingLarge = new Container(9);
    final Container craftingResult = new Container(1);

    /** Vytváří se až po GL kontextu, proto ne u deklarace. */
    private Texture blockAtlas;
    private Texture playerSkin;

    /**
     * Pixely atlasu, ze kterých je nahraná textura blockAtlas. Drží se, protože
     * je texture lab upravuje na místě a přenahrává do téže textury.
     */
    private int[] atlasPixels;
    private boolean atlasFromFile;

    /**
     * Pixely kůže postavy - stejný vzor jako atlasPixels: pole, ze kterého je
     * nahraná textura, a texture lab ho upravuje na místě a přenahrává.
     */
    private int[] skinPixels;
    private boolean skinFromFile;

    /** Obrazovky mimo lab: nastavení, výběr a založení světa. */
    private Widgets widgets;
    private ImageRenderer images;
    private OptionsScreen optionsScreen;
    private SelectWorldScreen selectScreen;
    private CreateWorldScreen createScreen;

    /** Kam se vrátit z nastavení - do menu, nebo do pauzy. */
    private GameState optionsReturnState = GameState.MAIN_MENU;

    /** Svět, který se právě hraje (kam se ukládá a kam jde náhled), nebo null. */
    private WorldSaves.WorldInfo currentWorld;

    /**
     * Herní mód hraného světa. Bere se z world.json při načtení a za běhu
     * se nemění - viz GameMode. V menu je to survival, aby nic nezáviselo
     * na tom, co se hrálo naposledy.
     */
    private GameMode mode = GameMode.SURVIVAL;

    /**
     * Dvojstisk mezerníku = přepnutí letu, jako v Minecraftu. Jen v creative;
     * ladicí klávesa F je vedle toho a na mód nekouká.
     */
    private final DoubleTap flyTap = new DoubleTap();

    /** Otevřený texture lab, nebo null; a kam se z něj vrací. */
    private TextureLab lab;

    /** Bloky založené v labu, které hráč ještě nedostal - viz giveCreatedBlocks(). */
    private final List<Byte> createdBlocks = new ArrayList<>();
    private GameState labReturnState = GameState.MAIN_MENU;

    /** Ladicí výpis vlevo nahoře. F3 ho schová a zase ukáže. */
    private boolean showDebug = true;
    private BlockIcon icons;
    private SkyRenderer sky;
    private HeldItemRenderer heldItem;

    /** Máchnutí rukou - spouští ho kopání i pokládání. */
    private final HandSwing swing = new HandSwing();

    /** Předměty na zemi. Neukládají se, takže nový i načtený svět začíná bez nich. */
    private final DroppedItems drops = new DroppedItems();

    /**
     * Postava hráče ve třetí osobě. Animace běží i v první osobě, aby po F5
     * nenaskočila z klidu uprostřed kroku.
     */
    private final PlayerAnimation animation = new PlayerAnimation();
    private final PlayerModelMesh playerMesh = new PlayerModelMesh();

    /** Otevřená obrazovka kontejneru, nebo null. */
    private ContainerScreen screen;

    // stav loadingu
    private boolean spawnDone = false;
    private int loadingFrames = 0;
    private String loadingTitle = "Creating world";

    // Kolem čeho se svět generuje: spawn u nového světa, uložená pozice u načteného.
    private float worldCenterX = SPAWN_SEARCH_X + 0.5f;
    private float worldCenterZ = SPAWN_SEARCH_Z + 0.5f;

    // Loading má dvě fáze s vlastními maximy - viz loadingProgress().
    private int columnsTotal = 0;
    private int meshesTotal = 0;

    /** Jakou část pruhu zabírá generování sloupců. Zbytek patří stavbě meshů. */
    private static final float COLUMN_PHASE = 0.4f;

    // měření FPS
    private int frameCount = 0;
    private double fpsTimer = 0;
    private int currentFps = 0;

    public static void main(String[] args) {
        // Fonty se rasterizují přes AWT (BufferedImage + Graphics2D). Headless režim
        // znamená "žádná okna", ne "žádné kreslení" - offscreen rendering funguje
        // dál, a na macOS to zabrání AWT sáhnout si o hlavní vlákno, které patří GLFW.
        System.setProperty("java.awt.headless", "true");

        new Main().run();
    }

    public void run() {
        init();
        loop();

        // GL objekty se musí uvolnit, dokud je kontext ještě aktivní
        // Zavření okna uprostřed hry je běžný způsob, jak skončit - svět
        // se proto uloží i tady, ne jen při odchodu do menu.
        if (state == GameState.PLAYING || state == GameState.PAUSED
                || (state == GameState.OPTIONS && optionsReturnState == GameState.PLAYING)
                || (state == GameState.TEXTURE_LAB && labReturnState == GameState.PLAYING)) {
            // Zavřít okno uprostřed hry je běžný způsob, jak skončit - svět
            // se proto uloží i s náhledem, stejně jako při odchodu do menu.
            captureThumbnail();
            saveWorld();
        }

        saveOptions();

        if (lab != null) {
            lab.delete();
        }

        selectScreen.delete();
        images.delete();

        world.shutdown();
        sound.shutdown();
        worldRenderer.delete();
        text.delete();
        font.delete();
        background.delete();
        dirtTile.delete();
        icons.delete();
        heldItem.delete();
        playerMesh.delete();
        playerSkin.delete();
        blockAtlas.delete();
        sky.delete();
        shapes.delete();

        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private void init() {
        // Zvuk se otevírá DŘÍV než okno: poprvé to trvá ~0,3 s (načtení
        // nativní knihovny a kontext OpenAL) a to je lepší prosedět před
        // oknem než na zamrzlém černém obrazu. Menu ho potřebuje na kliknutí.
        sound = SoundEngine.open(SoundLibrary.SOUND_DIR);

        if (!glfwInit()) throw new IllegalStateException("Unable to init GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        // Core profil 3.3: žádné glBegin, glMatrixMode ani GL_FOG - všechno jde
        // přes shadery a VBO. FORWARD_COMPAT je nutný na macOS, jinde neškodí.
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(width, height, "Minecraft Base", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create window");

        glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            width = w;
            height = h;

            // ⚠️ GL až potom, co existují ukazatele na funkce (glReady).
            //
            // GLFW volá tenhle callback SYNCHRONNĚ zevnitř svých funkcí -
            // mimo jiné z glfwSetWindowMonitor při přepnutí na celou
            // obrazovku. To se v init() stane dřív, než se stihne cokoliv
            // nakreslit, a volat GL dřív než GL.createCapabilities() je pád
            // v nativním kódu, ne výjimka. Pořadí v init() je nastavené tak,
            // aby k tomu dojít nemohlo; tohle je pojistka, aby se to
            // nerozbilo přidáním další GLFW volačky nad createCapabilities.
            //
            // Přeskočené nastavení se nikde neztratí: init() si velikost
            // framebufferu po createCapabilities() stejně zjistí sám.
            if (glReady) {
                glViewport(0, 0, w, h);
            }

            refreshMouseScale();
        });

        // Okno se může přesunout na monitor s jiným měřítkem, aniž by se
        // změnil framebuffer - poměr se proto hlídá i při změně velikosti okna.
        glfwSetWindowSizeCallback(window, (win, w, h) -> refreshMouseScale());

        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            // ⚠️ Myš chodí v bodech OKNA, UI se kreslí v pixelech FRAMEBUFFERU.
            // Na Retině je to dvojnásobek, takže se to musí přepočítat, než
            // se s tím začne cokoliv porovnávat - viz MouseScale.
            mouseX = mouseScale.toFramebufferX(xpos);
            mouseY = mouseScale.toFramebufferY(ypos);

            // S drženým tlačítkem nad kontejnerem se táhne přes sloty.
            if (state == GameState.CONTAINER) {
                screen.drag(mouseX, mouseY, width, height);
                return;
            }

            // V labu tažení maluje (nebo posouvá posuvník barvy).
            if (state == GameState.TEXTURE_LAB) {
                lab.drag(mouseX, mouseY, width, height);
                return;
            }

            // V nastavení tažení posouvá posuvník - a hodnota se hned použije.
            if (state == GameState.OPTIONS) {
                optionsScreen.drag(mouseX, mouseY, width, height);

                if (optionsScreen.takeChanged()) {
                    applyOptions();
                }
                return;
            }

            // Rozhlížení jen ve hře. V menu by kamera utíkala pod kurzorem.
            if (state != GameState.PLAYING) {
                return;
            }

            // ⚠️ Rozhlížení jede z NEPŘEPOČÍTANÝCH bodů okna: citlivost myši
            // je v nich a přepočtem by se na Retině zdvojnásobila.
            if (firstMouse) {
                lastX = xpos;
                lastY = ypos;
                firstMouse = false;
            }
            double dx = xpos - lastX;
            double dy = lastY - ypos; // inverted: moving mouse up looks up
            lastX = xpos;
            lastY = ypos;
            camera.processMouse(dx, dy);
        });

        // Jméno nového bloku v labu se píše ZNAKY, ne kódy kláves - velká
        // písmena, mezery a rozložení klávesnice pak fungují samy.
        glfwSetCharCallback(window, (win, codepoint) -> {
            if (state == GameState.TEXTURE_LAB) {
                lab.typed(codepoint);
            } else if (state == GameState.CREATE_WORLD) {
                createScreen.typed(codepoint);
            }
        });

        // PNG přetažené do okna lab rovnou naimportuje. Okno výběru souboru
        // nabídnout nejde - AWT běží headless (viz main()).
        glfwSetDropCallback(window, (win, count, names) -> {
            if (state == GameState.TEXTURE_LAB && count > 0) {
                lab.fileDropped(GLFWDropCallback.getName(names, 0));
            }
        });

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            // ⚠️ VŠECHNY KLÁVESY HRY JDOU PŘES Keybinds, ŽÁDNÁ UŽ NENÍ
            // NAPEVNO. actionFor() vrátí null, když klávesa nikomu nepatří
            // NEBO když ji mají dvě akce - kolize tedy nespustí ani jednu,
            // místo aby tiše spustila obě (viz Keybinds).
            Keybinds.Action bound = Keybinds.active().actionFor(key);

            // Q vyhodí z ruky jeden kus, Ctrl+Q celou hromádku - jako v Minecraftu.
            // Držené Q sype dál po jednom, proto se bere i opakování klávesy.
            if (bound == Keybinds.Action.DROP && state == GameState.PLAYING
                    && (action == GLFW_PRESS || action == GLFW_REPEAT)) {
                dropFromHand((mods & GLFW_MOD_CONTROL) != 0);
                return;
            }

            // Lab dostává i opakování klávesy - držené Backspace nebo Ctrl+Z.
            // Zavírá se ale jen stiskem: podržená klávesa labu by ho jinak
            // otevřela a opakováním hned zase zavřela.
            //
            // ⚠️ Lab dostává SUROVÝ kód klávesy, ne akci. Keybind Lab totiž
            // potřebuje zachytit i tu klávesu, na kterou se zrovna něco
            // přebindovává - a ta v tu chvíli žádnou akci znamenat nemá.
            if (state == GameState.TEXTURE_LAB) {
                if (action != GLFW_RELEASE && lab.key(key, mods) && action == GLFW_PRESS) {
                    closeTextureLab();
                }
                return;
            }

            // Nové obrazovky berou i opakování klávesy (držené Backspace
            // v poli se jménem), zavírají se ale jen stiskem.
            if (state == GameState.OPTIONS || state == GameState.SELECT_WORLD
                    || state == GameState.CREATE_WORLD) {
                if (action == GLFW_RELEASE || bound == Keybinds.Action.FULLSCREEN) {
                    // Fullscreen propadne dolů k přepnutí celé obrazovky.
                    if (action != GLFW_PRESS) {
                        return;
                    }
                } else {
                    screenKey(key, mods);
                    return;
                }
            }

            if (action != GLFW_PRESS) {
                return;
            }

            // Fullscreen se přepíná odkudkoliv, jako v Minecraftu.
            if (bound == Keybinds.Action.FULLSCREEN) {
                options.setFullscreen(!options.fullscreen());
                applyOptions();
                saveOptions();
                return;
            }

            // Lab jde otevřít ze hry i z hlavního menu.
            if (bound == Keybinds.Action.LAB
                    && (state == GameState.PLAYING || state == GameState.MAIN_MENU)) {
                openTextureLab();
                return;
            }

            // ⚠️ ESCAPE ZAVÍRÁ VŽDYCKY, i když je akce PAUSE přebindovaná
            // jinam nebo je v kolizi. Je to jediná klávesa, kterou se dá
            // zavřít inventář a vyvolat pauza; bez téhle pojistky by stačil
            // jeden překlep v keybinds.json a hráč by se z otevřené
            // obrazovky nedostal jinak než zabitím procesu.
            if (key == GLFW_KEY_ESCAPE || bound == Keybinds.Action.PAUSE) {
                if (state == GameState.CONTAINER) {
                    closeContainer();
                    return;
                }
                if (state == GameState.PLAYING) {
                    setState(GameState.PAUSED);
                } else if (state == GameState.PAUSED) {
                    setState(GameState.PLAYING);
                }
                return;
            }

            // Inventář zavírá otevřený kontejner. Musí být před testem na
            // PLAYING, protože ve stavu CONTAINER se hra nehýbe.
            if (bound == Keybinds.Action.INVENTORY) {
                if (state == GameState.CONTAINER) {
                    closeContainer();
                } else if (state == GameState.PLAYING) {
                    openInventory();
                }
                return;
            }

            if (state != GameState.PLAYING) {
                return;
            }

            // ⚠️ NOCLIP a FLY jsou LADICÍ klávesy a zůstávají jimi: platí
            // v obou módech a obcházejí pravidla schválně, stejně jako
            // posun času. Jsou to dvě různé věci: v letu kolize pořád platí,
            // a právě proto se noclip do creativu nehodí - creative let
            // v Minecraftu koliduje.
            if (bound == Keybinds.Action.NOCLIP) {
                player.noclip = !player.noclip;
            }
            if (bound == Keybinds.Action.FLY) {
                toggleFlight();
            }

            // Herní přepnutí letu: dvojstisk skoku, jen v creative.
            if (bound == Keybinds.Action.JUMP && mode.canFly() && flyTap.tap(glfwGetTime())) {
                toggleFlight();
            }
            if (bound == Keybinds.Action.DEBUG) {
                showDebug = !showDebug;
            }
            // Pohled: první osoba -> zezadu -> zepředu -> zpět.
            if (bound == Keybinds.Action.VIEW) {
                camera.view = camera.view.next();
            }
            // Výběr slotu hotbaru. Slot si nese sama akce, takže se dá
            // přebindovat i jednotlivý slot, ne jen celá řada.
            if (bound != null && bound.hotbarSlot() >= 0) {
                selectedSlot = bound.hotbarSlot();
            }
            // Posun času o desetinu cyklu - na noc se jinak čeká minuty.
            if (bound == Keybinds.Action.SKIP_TIME) {
                day.skip(0.1f);
            }
            if (bound == Keybinds.Action.VSYNC) {
                options.setVsync(!options.vsync());
                applyOptions();
                saveOptions();
            }
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            // Kopání potřebuje vědět, jestli se tlačítko DRŽÍ, ne jen že
            // bylo stisknuté - proto se sleduje i puštění.
            if (button == GLFW_MOUSE_BUTTON_LEFT && state == GameState.PLAYING) {
                miningHeld = action == GLFW_PRESS;

                // Zmáčknutí máchne i do vzduchu, s blokem i s prázdnou rukou -
                // jako v Minecraftu. Držení do vzduchu už znovu nemáchá; opakované
                // máchání při kopání obstarává update() jen se zaměřeným blokem.
                if (action == GLFW_PRESS) {
                    swing.trigger();
                }
            }

            // Obrazovka kontejneru potřebuje zmáčknutí i puštění zvlášť -
            // mezi nimi se může táhnout přes sloty.
            if (state == GameState.CONTAINER) {
                boolean left = button == GLFW_MOUSE_BUTTON_LEFT;

                if (!left && button != GLFW_MOUSE_BUTTON_RIGHT) {
                    return;
                }

                if (action == GLFW_PRESS) {
                    screen.press(mouseX, mouseY, width, height, left,
                            (mods & GLFW_MOD_SHIFT) != 0, inventory);
                } else if (action == GLFW_RELEASE) {
                    screen.release(mouseX, mouseY, width, height, left, inventory);
                }
                return;
            }

            if (state == GameState.TEXTURE_LAB) {
                boolean left = button == GLFW_MOUSE_BUTTON_LEFT;

                if (action == GLFW_PRESS && (left || button == GLFW_MOUSE_BUTTON_RIGHT)) {
                    if (lab.press(mouseX, mouseY, width, height, left)) {
                        closeTextureLab();
                    }
                } else if (action == GLFW_RELEASE) {
                    lab.releaseMouse();
                }
                return;
            }

            // Nastavení potřebuje zmáčknutí i puštění zvlášť - mezi nimi
            // se táhne posuvníkem.
            if (state == GameState.OPTIONS) {
                if (button != GLFW_MOUSE_BUTTON_LEFT) {
                    return;
                }

                if (action == GLFW_PRESS) {
                    if (optionsScreen.press(mouseX, mouseY, width, height)) {
                        sound.play(Sound.CLICK);
                        closeOptions();
                    } else if (optionsScreen.takeChanged()) {
                        applyOptions();
                    }
                } else if (action == GLFW_RELEASE) {
                    optionsScreen.release();
                }
                return;
            }

            if (state == GameState.SELECT_WORLD && action == GLFW_PRESS
                    && button == GLFW_MOUSE_BUTTON_LEFT) {
                switch (selectScreen.press(mouseX, mouseY, width, height, glfwGetTime())) {
                    case PLAY -> { sound.play(Sound.CLICK); playWorld(selectScreen.selected()); }
                    case CREATE -> { sound.play(Sound.CLICK); openCreateWorld(); }
                    case CANCEL -> { sound.play(Sound.CLICK); setState(GameState.MAIN_MENU); }
                    default -> { }
                }
                return;
            }

            if (state == GameState.CREATE_WORLD && action == GLFW_PRESS
                    && button == GLFW_MOUSE_BUTTON_LEFT) {
                switch (createScreen.press(mouseX, mouseY, width, height)) {
                    case CREATE -> { sound.play(Sound.CLICK); createWorld(); }
                    case CANCEL -> { sound.play(Sound.CLICK); openSelectWorld(); }
                    default -> { }
                }
                return;
            }

            if (action != GLFW_PRESS) {
                return;
            }

            if (state == GameState.MAIN_MENU || state == GameState.PAUSED) {
                if (button == GLFW_MOUSE_BUTTON_LEFT) {
                    handleMenuClick();
                }
                return;
            }

            if (state != GameState.PLAYING || hit == null) {
                return;
            }

            if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                // Kliknutí na crafting table ji otevře. Tohle je ta výplata
                // za oddělený Container a ContainerScreen: jiná obrazovka
                // je tady jedno volání, ne nová třída s vlastním kreslením.
                if (world.getBlock(hit.x(), hit.y(), hit.z()) == World.CRAFTING_TABLE) {
                    openCraftingTable();
                    return;
                }

                // Pokládá se do buňky PŘED zasaženou stěnou, ne do zasaženého
                // bloku - proto potřebuje raycast vracet normálu.
                int px = hit.placeX();
                int py = hit.placeY();
                int pz = hit.placeZ();

                // Do vlastního hitboxu si blok položit nemůžeš - jinak
                // se dá zazdít sám do sebe pohledem pod nohy.
                ItemStack selected = inventory.hotbar(selectedSlot);

                if (!selected.isEmpty() && !player.intersectsBlock(px, py, pz)
                        && world.placeBlock(px, py, pz, selected.block())) {
                    // V creative se z hotbaru neubírá - hráč má čehokoliv
                    // v ruce nekonečno. Rozhoduje o tom mód, ne tenhle kód.
                    mode.afterPlace(inventory, selectedSlot);
                    swing.trigger();
                    // V prostoru, ze středu položeného bloku.
                    sound.playAt(Sound.placeOf(selected.block()), px + 0.5f, py + 0.5f, pz + 0.5f);
                }
            }
        });

        glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
            // Lab je přes celou obrazovku a jeho mód si kolečko může vzít
            // (Recipes jím roluje přehledem bloků).
            if (state == GameState.TEXTURE_LAB && lab != null) {
                lab.scroll(yoffset);
                return;
            }

            if (state == GameState.SELECT_WORLD) {
                selectScreen.scroll(yoffset);
                return;
            }

            // Creative přehled se kolečkem roluje; obyčejný inventář nemá co.
            if (state == GameState.CONTAINER) {
                screen.scroll(yoffset);
                return;
            }

            if (state != GameState.PLAYING) {
                return;
            }
            // Kolečko nahoru = doleva po hotbaru, jako v Minecraftu.
            // floorMod, aby se to na krajích správně přetočilo dokola.
            selectedSlot = Math.floorMod(
                    selectedSlot - (int) Math.signum(yoffset), Inventory.HOTBAR_SIZE);
        });

        glfwMakeContextCurrent(window);

        // ⚠️ MUSÍ BÝT HNED PO makeContextCurrent, JEŠTĚ PŘED windowMode.apply().
        //
        // Tady se nevytváří žádný GL objekt - jen se naplní tabulka ukazatelů
        // na funkce OpenGL pro tohle vlákno. Bez ní je KAŽDÉ volání GL pád
        // v nativním kódu (EXCEPTION_ACCESS_VIOLATION v lwjgl_opengl.dll),
        // protože se skáče na nulovou adresu.
        //
        // A pád tu opravdu hrozí: glfwSetWindowMonitor uvnitř apply() změní
        // velikost framebufferu a GLFW kvůli tomu zavolá SYNCHRONNĚ callback
        // velikosti framebufferu - ten, co je registrovaný nahoře a volá
        // glViewport. Se startem ve fullscreenu se tak GL volalo dřív, než
        // existovaly ukazatele, a hra spadla na černé obrazovce dřív, než se
        // stihla vykreslit. S oknem se apply() nepřepíná (viz guard ve
        // WindowMode), callback se nezavolal a chyba se neprojevila.
        GL.createCapabilities();
        glReady = true;

        glfwSwapInterval(options.vsync() ? 1 : 0);

        // Celá obrazovka až po vytvoření okna: přepíná se tentýž window
        // a tentýž GL kontext, takže se nic nemusí nahrávat znovu.
        windowMode.apply(window, options.fullscreen(), options.vsync());
        glfwShowWindow(window);

        // Framebuffer nemusí mít stejnou velikost jako okno (DPI škálování).
        // Ptáme se ručně, protože při startu v okně se callback nezavolá
        // vůbec; při startu ve fullscreenu se zavolá (přepnutí monitoru) a
        // tohle jen zopakuje totéž s konečnou velikostí - je to idempotentní.
        int[] fbWidth = new int[1];
        int[] fbHeight = new int[1];
        glfwGetFramebufferSize(window, fbWidth, fbHeight);
        width = fbWidth[0];
        height = fbHeight[0];
        glViewport(0, 0, width, height);

        // Poměr okno : framebuffer se musí znát dřív, než přijde první pohyb
        // myši - a při startu v okně se callback velikosti nezavolá.
        refreshMouseScale();

        glEnable(GL_DEPTH_TEST);

        // Backface culling: zahodí stěny odvrácené od kamery ještě před
        // rasterizací. Funguje to jen proto, že všechny stěny mají vrcholy
        // konzistentně proti směru hodinových ručiček při pohledu zvenku.
        glEnable(GL_CULL_FACE);

        glClearColor(WorldRenderer.SKY_R, WorldRenderer.SKY_G, WorldRenderer.SKY_B, 1.0f);

        // Starý svět ze saves/world.dat (jeden slot) se přesune do saves/<složka>/.
        // Původní soubor se nemaže, jen přejmenuje - viz WorldSaves.migrateLegacy.
        WorldSaves.Migration migration = WorldSaves.migrateLegacy(WorldSaves.ROOT);

        if (migration != WorldSaves.Migration.NONE) {
            System.out.println("Stary svet: " + migration);
        }

        // Bloky z texture labu (textures/blocks.json) PŘED atlasem: procedurální
        // atlas podle nich vyznačí dlaždice, které bez atlas.png nemá. Když
        // soubor není, registr je prázdný a hra se chová přesně jako dřív.
        BlockRegistry.activate(BlockRegistry.load(BlockRegistry.FILE));
        System.out.println("Bloky z labu: " + BlockRegistry.active().size()
                + (Files.isRegularFile(BlockRegistry.FILE) ? " (" + BlockRegistry.FILE.toAbsolutePath() + ")"
                : " (" + BlockRegistry.FILE + " neni)"));

        // Recepty z labu (textures/recipes.json). AŽ PO blocích, protože
        // recept smí odkazovat na blok z labu a neznámý blok recept vyřadí.
        // Chybějící soubor = prázdný seznam a jen vestavěné recepty.
        RecipeBook.activate(RecipeBook.load(RecipeBook.FILE));

        // Klávesy z labu (keybinds.json). Nezávislé na všem ostatním -
        // chybějící soubor znamená přesně ty klávesy, které měl Main
        // dřív natvrdo.
        Keybinds.activate(Keybinds.load(Keybinds.FILE));
        System.out.println("Klavesy: " + (Keybinds.active().isDefault() ? "vychozi" : "vlastni")
                + (Files.isRegularFile(Keybinds.FILE) ? " (" + Keybinds.FILE.toAbsolutePath() + ")"
                : " (" + Keybinds.FILE + " neni)"));

        // Doladění generátoru po biomech (biome_tuning.json). MUSÍ být před
        // prvním světem: TerrainGenerator si tuning bere při svém vzniku,
        // ne za běhu - viz BiomeTuning.
        BiomeTuning.activate(BiomeTuning.load(BiomeTuning.FILE));
        System.out.println("Biomy: " + (BiomeTuning.active().isDefault() ? "vychozi hodnoty" : "vlastni tuning")
                + (Files.isRegularFile(BiomeTuning.FILE) ? " (" + BiomeTuning.FILE.toAbsolutePath() + ")"
                : " (" + BiomeTuning.FILE + " neni)"));
        System.out.println("Recepty z labu: " + RecipeBook.active().size()
                + (Files.isRegularFile(RecipeBook.FILE) ? " (" + RecipeBook.FILE.toAbsolutePath() + ")"
                : " (" + RecipeBook.FILE + " neni)"));

        // Až tady, protože shadery a textury potřebují aktivní kontext
        // Atlas vlastní Main a půjčuje ho renderu světa i ikonám bloků.
        // textures/atlas.png z texture labu, když existuje; jinak procedurální.
        Textures.AtlasPixels atlasSource = Textures.atlasPixels(Textures.ATLAS_FILE);
        atlasPixels = atlasSource.pixels();
        atlasFromFile = atlasSource.fromFile();
        System.out.println("Atlas bloku: " + (atlasFromFile
                ? Textures.ATLAS_FILE.toAbsolutePath() : "proceduralni (" + Textures.ATLAS_FILE + " neni)"));
        blockAtlas = Textures.blockAtlas(atlasPixels);
        icons = new BlockIcon(blockAtlas);

        // textures/skin.png z texture labu, když existuje; jinak vygenerovaná.
        Textures.SkinPixels skinSource = Textures.skinPixels(Textures.SKIN_FILE);
        skinPixels = skinSource.pixels();
        skinFromFile = skinSource.fromFile();
        System.out.println("Kuze postavy: " + (skinFromFile
                ? Textures.SKIN_FILE.toAbsolutePath() : "vestavena (" + Textures.SKIN_FILE + " neni)"));
        playerSkin = Textures.playerSkin(skinPixels);
        worldRenderer = new WorldRenderer(blockAtlas, playerSkin);
        sky = new SkyRenderer();
        heldItem = new HeldItemRenderer(blockAtlas, playerSkin);
        shapes = new Renderer2D();
        // Font se rasterizuje MALÝ a na obrazovku se kreslí zvětšený celým
        // číslem (Gui.scale). Při větší velikosti by zvětšení vyšlo obrovské
        // a pixely by přestaly být vidět jako pixely.
        //
        // Devítka je změřená, ne odhad: při 7 a 8 px se SansSerif bez
        // antialiasingu slévá ("Quit" vyjde jako "Quft"), od 9 px jsou tahy
        // oddělené. Míň než 9 = nečitelné, víc = zbytečně velké písmo.
        font = new FontAtlas("SansSerif", 9);
        text = new TextRenderer(font);

        dirtTile = Textures.dirt();
        background = new BackgroundRenderer(dirtTile);
        hud = new Hud(shapes, text, icons);

        widgets = new Widgets(shapes, text);
        images = new ImageRenderer();
        optionsScreen = new OptionsScreen(options, widgets);
        selectScreen = new SelectWorldScreen(widgets, images, WorldSaves.ROOT);
        createScreen = new CreateWorldScreen(widgets, WorldSaves.ROOT);

        // Teprve teď existuje všechno, co nastavení řídí (svět, renderer, kamera).
        applyOptions();

        setState(GameState.MAIN_MENU);
    }

    /**
     * Rozveze nastavení tam, kam patří. Volá se po každé změně v Options -
     * posuvník v nastavení se tak projeví HNED, ne až po restartu.
     *
     * Simulation distance se propisuje do okruhů načítání světa a render
     * distance do dohledu; obojí se použije v nejbližším World.update()
     * a v nejbližším kreslení, takže se svět sám dogeneruje nebo zahodí.
     */
    private void applyOptions() {
        Gui.setPreferredScale(options.guiScale());

        camera.mouseSensitivity = options.mouseDegreesPerPixel();
        camera.invertMouseY = options.invertMouse();

        world.loadRadius = options.loadRadius();
        world.unloadRadius = options.unloadRadius();
        world.renderDistance = options.renderDistanceBlocks();

        if (worldRenderer != null) {
            worldRenderer.setBrightness(options.brightness());
        }

        glfwSwapInterval(options.vsync() ? 1 : 0);
        windowMode.apply(window, options.fullscreen(), options.vsync());
    }

    /** Uloží nastavení na disk. Chyba se jen ohlásí - hra kvůli ní nepadá. */
    private void saveOptions() {
        options.save(Options.FILE);
    }

    /**
     * Zjistí, kolik pixelů framebufferu připadá na bod okna. Volá se při startu
     * a po každé změně velikosti okna nebo framebufferu (i po přepnutí
     * fullscreenu a po přesunu na monitor s jiným měřítkem).
     */
    private void refreshMouseScale() {
        int[] windowWidth = new int[1];
        int[] windowHeight = new int[1];
        int[] frameWidth = new int[1];
        int[] frameHeight = new int[1];

        glfwGetWindowSize(window, windowWidth, windowHeight);
        glfwGetFramebufferSize(window, frameWidth, frameHeight);

        mouseScale.update(windowWidth[0], windowHeight[0], frameWidth[0], frameHeight[0]);
    }

    private void setState(GameState next) {
        state = next;

        // Kurzor je chycený jen při hraní; v menu musí být vidět a volný.
        glfwSetInputMode(window, GLFW_CURSOR,
                next == GameState.PLAYING ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);

        // Po návratu do hry se musí zahodit stará pozice myši, jinak by se
        // kamera skokem otočila o rozdíl nasbíraný v menu.
        if (next == GameState.PLAYING) {
            firstMouse = true;
        }

        // Odejít do menu s drženým tlačítkem by po návratu pokračovalo v kopání.
        // Rozdělaný dvojstisk mezerníku se zahazuje ze stejného důvodu: skok
        // před odchodem a skok po návratu spolu nemají co dělat.
        if (next != GameState.PLAYING) {
            miningHeld = false;
            mining.cancel();
            flyTap.reset();
        }
    }

    private void loop() {
        double lastTime = glfwGetTime();

        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            float dt = (float) (now - lastTime);
            lastTime = now;

            updateFps(dt);

            switch (state) {
                case MAIN_MENU -> {
                    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    background.draw(width, height, Palette.BACKGROUND_TINT);
                    mainMenu.render(shapes, text, width, height, hoveredButton(mainMenu), false, dt);
                }
                case CREATING_WORLD -> updateCreatingWorld();
                case PLAYING -> {
                    day.advance(dt);
                    updatePlaying(dt);
                }
                case PAUSED -> {
                    // Svět se pořád kreslí, jen se nehýbe - pauza je vidět "skrz".
                    renderWorld();
                    pauseMenu.render(shapes, text, width, height, hoveredButton(pauseMenu), true, dt);
                }
                case CONTAINER -> {
                    // Svět se dál generuje a kreslí, jen se nehýbe hráč.
                    world.update(player.x, player.z);
                    renderWorld();
                    screen.render(shapes, text, icons, width, height, mouseX, mouseY);
                }
                case OPTIONS -> {
                    // Z pauzy se za nastavením dál kreslí (a generuje) svět -
                    // posunutí render distance je tak vidět rovnou při tažení.
                    if (optionsReturnState == GameState.PLAYING) {
                        world.update(player.x, player.z);
                        renderWorld();
                    } else {
                        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                        background.draw(width, height, Palette.BACKGROUND_TINT);
                    }

                    optionsScreen.render(width, height, mouseX, mouseY,
                            optionsReturnState == GameState.PLAYING);

                    if (optionsScreen.takeChanged()) {
                        applyOptions();
                    }
                }
                case SELECT_WORLD -> {
                    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    background.draw(width, height, Palette.BACKGROUND_TINT);
                    selectScreen.render(width, height, mouseX, mouseY);
                }
                case CREATE_WORLD -> {
                    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    background.draw(width, height, Palette.BACKGROUND_TINT);
                    createScreen.render(width, height, mouseX, mouseY);
                }
                case TEXTURE_LAB -> {
                    // Otevřený ze hry: svět za labem se kreslí dál, i s upraveným
                    // atlasem. Z menu: pozadí menu.
                    if (labReturnState == GameState.PLAYING) {
                        world.update(player.x, player.z);
                        renderWorld();
                    } else {
                        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                        background.draw(width, height, Palette.BACKGROUND_TINT);
                    }

                    lab.update(dt);
                    lab.render(width, height, mouseX, mouseY);
                }
            }

            glfwSwapBuffers(window);
            glfwPollEvents();

            // Strop FPS má smysl jen bez vsyncu - ten už frame časuje sám.
            limiter.sync(options.vsync() ? Options.UNLIMITED_FPS : options.maxFps());
        }
    }

    // ------------------------------------------------------------------
    // stavy
    // ------------------------------------------------------------------

    /**
     * Začne hrát vybraný svět: s uloženým souborem ho načte, bez něj (nově
     * založený svět) vygeneruje terén jeho seedem a najde spawn.
     *
     * ⚠️ Poškozený soubor světa se NEPŘEPISUJE novým světem - hráč zůstane
     * v seznamu a uvidí důvod na konzoli. Dřív byl svět jeden a nový svět se
     * prostě založil; teď by to znamenalo přepsat konkrétní uložený svět.
     */
    private void playWorld(WorldSaves.WorldInfo world) {
        WorldStorage.Save save = null;

        if (WorldStorage.exists(world.worldFile())) {
            save = WorldStorage.load(world.worldFile());

            if (save == null) {
                System.err.println("Svet " + world.folder() + " nejde nacist - zustava v seznamu");
                return;
            }
        }

        currentWorld = world;

        // Mód je vlastnost světa a bere se z jeho metadat. Svět bez něj
        // (založený před creativem) je survival - viz WorldSaves.
        mode = world.mode();

        freshWorld(world.seed());

        if (save == null) {
            newWorldSpawn();
        } else {
            restore(save);
        }

        setState(GameState.CREATING_WORLD);
    }

    private void newWorldSpawn() {
        // Terén u počátku souřadnic vychází pod hladinou, takže se spawn hledá:
        // jinak by hra začínala po pás ve vodě. Výška terénu je čistá funkce
        // šumu a seedu, takže to nepotřebuje vygenerovaný svět.
        int[] spawn = world.generator().findLandSpawn(SPAWN_SEARCH_X, SPAWN_SEARCH_Z, SPAWN_SEARCH_RADIUS);

        loadingTitle = "Creating world";
        worldCenterX = spawn[0] + 0.5f;
        worldCenterZ = spawn[1] + 0.5f;
        spawnDone = false;   // hráče postaví na terén až updateCreatingWorld
    }

    void restore(WorldStorage.Save save) {

        // Změny se musí nasadit PŘED prvním update(), aby si je sloupce
        // vzaly rovnou při generování a nikdy nebyly vidět bez nich.
        world.restoreChanges(save.changes());

        player.x = save.x();
        player.y = save.y();
        player.z = save.z();
        player.vx = player.vy = player.vz = 0;
        player.flying = save.flying();
        player.onGround = false;

        camera.yaw = save.yaw();
        camera.pitch = save.pitch();
        camera.setPosition(player.x, player.eyeY(), player.z);

        selectedSlot = Math.floorMod(save.selectedSlot(), Inventory.HOTBAR_SIZE);
        inventory.clear();
        for (int i = 0; i < Math.min(Inventory.SIZE, save.inventory().length); i++) {
            inventory.set(i, save.inventory()[i]);
        }

        // Denní doba je uložená se světem (formát MCW3). Soubor ze starší
        // verze ji nemá a WorldStorage za něj dosadí DayCycle.START_TIME,
        // takže se otevře dopoledne - přesně to, co dělal dosud.
        day.setTime(save.dayTime());

        loadingTitle = "Loading world";
        worldCenterX = player.x;
        worldCenterZ = player.z;
        spawnDone = true;    // pozice je z uloženého světa, hledat terén netřeba

    }

    /**
     * Společný začátek nového i načteného světa.
     *
     * ---------------------------------------------------------------------
     * ⚠️ RESETUJE SE I STAV, KTERÝ NENÍ VE `World`. Tohle je celá příčina
     * dvou chyb, které tu byly: inventář, crafting mřížky, vybraný slot ani
     * denní doba nejsou součástí `World` - jsou to samostatná pole v `Main`,
     * která přežijí výměnu světa. `freshWorld()` vyměnil `World`, `SoundEngine`,
     * `WorldRenderer` a položky na zemi, ale na tyhle čtyři se zapomnělo, takže
     * si nový svět bral inventář i denní dobu po tom předchozím v témže běhu hry.
     *
     * Pravidlo pro příští pole: co drží `Main` a co se vztahuje ke KONKRÉTNÍMU
     * světu, patří sem. `MainStateTest` prochází tenhle seznam a kdyby se sem
     * přidalo pole a zapomnělo na reset, spadne.
     *
     * Načtený svět si potom svoje hodnoty vrátí v `restore()` - resetuje se
     * VŽDYCKY a přepisuje se až potom, aby nebyl rozdíl mezi "nový svět"
     * a "načtený svět, jehož soubor tu položku ještě nemá".
     * ---------------------------------------------------------------------
     */
    private void freshWorld(long seed) {
        // Starý svět musí zastavit svoje generující vlákno, jinak by běžela dvě.
        world.shutdown();
        world = new World(seed);

        resetPlayerState();

        // Zvuk symetricky se světem: nový svět nezdědí nic, co ještě hraje
        // ze starého. Změřeno: zavření ~30 ms, nové otevření 55-150 ms.
        sound.shutdown();
        sound = SoundEngine.open(SoundLibrary.SOUND_DIR);

        // Nový World má výchozí okruhy - přepsat je z nastavení.
        applyOptions();

        worldRenderer.reset();
        worldRenderer.setBuildBudget(WorldRenderer.BUILD_BUDGET_LOADING);

        drops.clear();

        loadingFrames = 0;
        columnsTotal = 0;
        meshesTotal = 0;
    }

    /**
     * Stav hráče, který nepatří světu, ale sezení - vrátit na výchozí.
     *
     * Je to vlastní metoda, a ne pár řádků uvnitř `freshWorld()`, aby šla
     * zavolat z testu bez GL, GLFW i OpenAL. `MainStateTest` na ní ověřuje
     * obě opravené chyby.
     */
    void resetPlayerState() {
        // Nový svět = nový začátek. Bez tohohle ukázal inventář věci
        // ze světa, ve kterém hráč byl před chvílí.
        inventory.clear();
        selectedSlot = 0;

        // Crafting mřížky jsou trvalé kontejnery (co v nich zůstane, je tam
        // i při dalším otevření), takže se musí vyprázdnit taky - jinak by
        // v novém světě ležely v mřížce suroviny z minulého.
        craftingSmall.clear();
        craftingLarge.clear();
        craftingResult.clear();

        // Denní doba je pole Main, ne World - bez resetu začne nový svět
        // v tu dobu, ve kterou skončil ten předchozí.
        day.reset();
    }

    /**
     * Přepne volný let. Nulování svislé rychlosti je tu proto, aby se
     * po vypnutí letu nezačalo padat setrvačností z poslední hodnoty vy.
     */
    private void toggleFlight() {
        player.flying = !player.flying;
        player.vy = 0;
    }

    /**
     * E: v survivalu obyčejný inventář s crafting mřížkou, v creative přehled
     * všech bloků. Jsou to dvě různé obrazovky, ne dva režimy jedné.
     *
     * ⚠️ Přehled se staví ZNOVU při každém otevření, z aktivního registru -
     * blok právě založený v labu je v něm tím pádem hned.
     */
    private void openInventory() {
        screen = mode == GameMode.CREATIVE
                ? ContainerScreen.creativeInventory(
                        CreativeInventory.container(BlockRegistry.active()), inventory)
                : ContainerScreen.playerInventory(inventory, craftingSmall, craftingResult);

        screen.refreshResult();
        setState(GameState.CONTAINER);
    }

    private void openCraftingTable() {
        screen = ContainerScreen.craftingTable(inventory, craftingLarge, craftingResult);
        screen.refreshResult();
        setState(GameState.CONTAINER);
    }

    /**
     * Texture lab upravuje TENTÝŽ atlas, ze kterého kreslí hra - pixely i GL
     * texturu. Neuložené úpravy proto zůstanou vidět i po zavření labu, dokud
     * se hra neukončí.
     */
    private void openTextureLab() {
        lab = new TextureLab(atlasPixels, blockAtlas, atlasFromFile,
                skinPixels, playerSkin, skinFromFile, shapes, text);
        labReturnState = state;
        setState(GameState.TEXTURE_LAB);
    }

    private void closeTextureLab() {
        atlasFromFile = lab.fromFile();
        skinFromFile = lab.skinFromFile();
        createdBlocks.addAll(lab.takeCreatedBlocks());
        lab.delete();
        lab = null;
        setState(labReturnState);

        if (state == GameState.PLAYING) {
            giveCreatedBlocks();
        }
    }

    /**
     * Blok založený v labu dostane hráč hromádku do inventáře, ať ho jde hned
     * vyzkoušet - receptem ho vyrobit nejde. Z labu otevřeného v menu počká
     * na první svět, jinak by ho načtení uloženého inventáře přepsalo.
     * Co se do plného inventáře nevejde, hráč vyhodí před sebe.
     */
    private void giveCreatedBlocks() {
        for (byte block : createdBlocks) {
            ItemStack rest = inventory.add(ItemStack.of(block, TextureLab.CREATED_STACK));
            drops.throwFrom(player, camera.getLookDirection(), rest);
        }

        createdBlocks.clear();
    }

    private void closeContainer() {
        // Co zůstalo na kurzoru a v mřížce, musí spadnout zpátky do batohu -
        // jinak by se to zavřením obrazovky ztratilo. Co se z kurzoru nevejde
        // ani tam, hráč vyhodí před sebe.
        ItemStack leftover = screen.returnItems(inventory);
        drops.throwFrom(player, camera.getLookDirection(), leftover);
        screen = null;
        setState(GameState.PLAYING);
    }

    /**
     * Náhled světa do saves/<složka>/icon.png: svět se překreslí BEZ HUD
     * a pauzy a hned přečte ze zadního bufferu. Vzniká při odchodu do menu
     * a při zavření okna, tedy přesně když se svět ukládá - v seznamu je pak
     * vidět, kde hráč naposledy skončil.
     */
    private void captureThumbnail() {
        if (currentWorld == null || width <= 0 || height <= 0) {
            return;
        }

        renderWorld();

        java.nio.ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(width * height * 4);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

        int[] argb = Thumbnails.fromFramebuffer(pixels, width, height, Thumbnails.WIDTH, Thumbnails.HEIGHT);
        Thumbnails.save(argb, Thumbnails.WIDTH, Thumbnails.HEIGHT, currentWorld.iconFile());
    }

    private void saveWorld() {
        if (currentWorld == null) {
            return;
        }

        WorldStorage.save(currentWorld.worldFile(), new WorldStorage.Save(
                player.x, player.y, player.z,
                camera.yaw, camera.pitch,
                player.flying, selectedSlot,
                world.changes(), inventorySnapshot(), day.time()));

        // Poslední hraní se posune až po uložení - seznam světů se podle něj řadí.
        currentWorld = WorldSaves.touch(currentWorld, System.currentTimeMillis());
    }

    /** Uloží svět i s náhledem a vrátí se do hlavního menu. */
    private void quitToTitle() {
        captureThumbnail();
        saveWorld();
        currentWorld = null;

        // V menu se na mód nikdo neptá, ale ať tam po creative světě nezůstane
        // viset - příští svět si ho stejně nastaví sám z metadat.
        mode = GameMode.SURVIVAL;
        setState(GameState.MAIN_MENU);
    }

    /** Klávesy obrazovek nastavení, výběru a založení světa. */
    private void screenKey(int key, int mods) {
        switch (state) {
            case OPTIONS -> {
                if (optionsScreen.key(key)) {
                    closeOptions();
                }
            }
            case SELECT_WORLD -> {
                switch (selectScreen.key(key)) {
                    case PLAY -> playWorld(selectScreen.selected());
                    case CANCEL -> setState(GameState.MAIN_MENU);
                    default -> { }
                }
            }
            case CREATE_WORLD -> {
                String clipboard = glfwGetClipboardString(window);

                switch (createScreen.key(key, mods, clipboard)) {
                    case CREATE -> createWorld();
                    case CANCEL -> openSelectWorld();
                    default -> { }
                }
            }
            default -> { }
        }
    }

    private void openCreateWorld() {
        createScreen.reset();
        setState(GameState.CREATE_WORLD);
    }

    private void openOptions() {
        optionsReturnState = state;
        setState(GameState.OPTIONS);
    }

    private void closeOptions() {
        saveOptions();
        setState(optionsReturnState);
    }

    private void openSelectWorld() {
        selectScreen.refresh();
        setState(GameState.SELECT_WORLD);
    }

    /** Založí svět podle obrazovky a rovnou ho začne hrát. */
    private void createWorld() {
        long seed = Seeds.parse(createScreen.seedText(), Seeds::random);
        WorldSaves.WorldInfo world = WorldSaves.create(WorldSaves.ROOT, createScreen.name(),
                seed, createScreen.seedText(), createScreen.mode(), System.currentTimeMillis());

        if (world == null) {
            System.err.println("Svet se nepovedlo zalozit - viz vyse");
            return;
        }

        playWorld(world);
    }

    private void updateCreatingWorld() {
        loadingFrames++;

        // ⚠️ KAŽDÝ frame, ne jednorázově. update() od zavedení worker vlákna
        // práci jen ZADÁ a převezme, co je hotové - jedním voláním se svět
        // nevygeneruje. Dokud tohle bylo uvnitř if(!worldGenerated), loading
        // uvázl na jednom procentu: hotové sloupce neměl kdo vyzvednout.
        world.update(worldCenterX, worldCenterZ);

        if (!spawnDone) {
            // Sloupec pod spawnem je po prvním update() zaručeně hotový
            // (CRITICAL_RADIUS ve World), takže hráč má na co dopadnout.
            player.spawn(world, worldCenterX, worldCenterZ);
            camera.setPosition(player.x, player.eyeY(), player.z);
            spawnDone = true;
        }

        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        worldRenderer.render(world, camera, width, height, options.fov(), false, day, drops.items(), null);

        int missingColumns = world.pendingColumns();
        int pendingMeshes = worldRenderer.pendingBuilds();

        hud.drawLoading(width, height, loadingTitle,
                loadingProgress(missingColumns, pendingMeshes));

        // Pár framů rezervy, aby se fronty stihly vůbec naplnit.
        if (missingColumns == 0 && pendingMeshes == 0 && loadingFrames > 3) {
            worldRenderer.setBuildBudget(WorldRenderer.BUILD_BUDGET_PLAYING);
            setState(GameState.PLAYING);
            giveCreatedBlocks();
        }
    }

    /**
     * Postup loadingu ve dvou fázích: nejdřív se generují sloupce, pak se z nich
     * staví meshe.
     *
     * Sečíst obojí do jednoho čísla nejde. Fronta meshů roste teprve s tím, jak
     * sloupce přibývají, takže by celkový počet během loadingu narůstal a pruh
     * by couval. Každá fáze má proto vlastní maximum i vlastní úsek pruhu.
     */
    private float loadingProgress(int missingColumns, int pendingMeshes) {
        if (missingColumns > 0) {
            columnsTotal = Math.max(columnsTotal, missingColumns);
            return COLUMN_PHASE * (1f - missingColumns / (float) columnsTotal);
        }

        meshesTotal = Math.max(meshesTotal, pendingMeshes);

        if (meshesTotal == 0) {
            return 1f;
        }

        return COLUMN_PHASE + (1f - COLUMN_PHASE)
                * (1f - pendingMeshes / (float) meshesTotal);
    }

    private void updatePlaying(float dt) {
        handleInput();

        // Musí být před fyzikou: hráč nesmí spadnout skrz sloupec,
        // který se ještě nevygeneroval.
        world.update(player.x, player.z);

        float beforeX = player.x;
        float beforeZ = player.z;

        player.update(world, dt, camera.yaw);

        // Animace se řídí SKUTEČNÝM posunem, ne vstupem - chůze do zdi
        // nohama nemáchá, i když se drží W.
        animation.update(dt, (float) Math.hypot(player.x - beforeX, player.z - beforeZ));

        // V první osobě kamera v očích, ve třetí za hráčem nebo před ním.
        camera.follow(world, player.x, player.eyeY(), player.z);

        // Posluchač je tam, kde kamera - poziční zvuky pak sedí s obrazem
        // i ve třetí osobě. Kroky jsou nepoziční, hrají "v hlavě".
        sound.listen(camera.x, camera.y, camera.z, camera.viewDirection());

        if (player.stepped) {
            sound.play(Sound.stepOf(player.stepBlock));
        }

        // ⚠️ Míří se z OČÍ, ne z kamery. Ve třetí osobě by paprsek z kamery
        // za zády trefil blok mezi kamerou a hráčem a zepředu by mířil úplně
        // jinam, než kam hráč kouká. Minecraft to dělá stejně.
        float[] dir = camera.getLookDirection();
        hit = Raycaster.cast(world, player.x, player.eyeY(), player.z, dir[0], dir[1], dir[2], 8f);

        swing.update(dt);

        if (miningHeld && hit != null) {
            swing.trigger();
        }

        if (mining.update(world, dt, miningHeld, hit, mode)) {
            // Survival: vytěžený kus jde do inventáře, co se nevejde na zem.
            // Creative: blok zmizí a nic po něm nezbude.
            mining.harvest(world, inventory, drops, sound, mode);
        }

        // Až po pohybu hráče, ať se sbírá podle toho, kde hráč stojí teď.
        drops.update(world, player, inventory, dt);

        renderWorld();

        drawHeldItem();

        hud.draw(width, height, world, inventory, selectedSlot, showDebug ? debugLines() : null);
    }

    /**
     * Vyhodí z vybraného slotu hotbaru jeden kus, nebo celou hromádku.
     *
     * Q / Ctrl+Q je schéma z Minecraftu, takže sedí do ruky. Ctrl tu sice
     * zároveň znamená plížení, ale to na vyhození nemá vliv - hráč se jen
     * na okamžik přikrčí.
     */
    private void dropFromHand(boolean wholeStack) {
        ItemStack thrown = inventory.take(selectedSlot, wholeStack ? ItemStack.MAX_COUNT : 1);

        if (thrown.isEmpty()) {
            return;
        }

        drops.throwFrom(player, camera.getLookDirection(), thrown);
        swing.trigger();
    }

    /**
     * Ruka se kreslí až po světě a po filtru vody, ale PŘED HUD - hotbar
     * i zaměřovač jsou nad ní. S prázdným slotem je vidět holá ruka.
     */
    private void drawHeldItem() {
        ItemStack held = inventory.hotbar(selectedSlot);

        // Ve třetí osobě drží blok (i ruku) model postavy - viz playerBody().
        if (camera.view != Camera.View.FIRST_PERSON) {
            return;
        }

        // Ruka je u oka, takže jí stačí osvětlení místa, kde hráč stojí.
        int bx = (int) Math.floor(camera.x);
        int by = (int) Math.floor(camera.y);
        int bz = (int) Math.floor(camera.z);

        float light = Math.max(
                Math.max(world.skyLightAt(bx, by, bz) / 15f * day.daylight(),
                        world.blockLightAt(bx, by, bz) / 15f),
                DayCycle.AMBIENT);

        // Tentýž jas jako ve světovém shaderu - ruka nesmí zůstat tmavá,
        // když se zbytek obrazu rozsvítí.
        light = Options.brighten(light, options.brightness());

        heldItem.draw(width, height, options.fov(), held.block(),
                swing.fast(), swing.slow(), light);
    }

    /**
     * Postava v aktuální póze, nebo null v první osobě - tam se vlastní tělo
     * nekreslí a místo něj je vidět jen ruka (HeldItemRenderer).
     */
    private PlayerModelMesh playerBody() {
        if (camera.view == Camera.View.FIRST_PERSON) {
            return null;
        }

        ItemStack held = inventory.hotbar(selectedSlot);
        PlayerPose pose = animation.pose(camera.pitch, swing.fast(), swing.slow(), !held.isEmpty());

        // Jedno světlo pro celou postavu, z buňky s hlavou - jako u ruky
        // v první osobě. Denní dobu dopočítá shader.
        int cell = world.cellAt((int) Math.floor(player.x),
                (int) Math.floor(player.eyeY()), (int) Math.floor(player.z));

        playerMesh.build(pose, player.x, player.y, player.z, camera.yaw, held.block(),
                World.cellSky(cell) / 15f, World.cellBlockLight(cell) / 15f,
                camera.x, camera.y, camera.z);

        return playerMesh;
    }

    private void renderWorld() {
        // Rozhoduje blok, ve kterém jsou OČI, ne nohy: po pás ve vodě se pod
        // hladinu ještě nekouká, takže by filtr přes obrazovku byl matoucí.
        boolean underwater = world.isWater(
                (int) Math.floor(camera.x),
                (int) Math.floor(camera.y),
                (int) Math.floor(camera.z));

        // Obloha musí mít stejnou barvu jako mlha, jinak se v dálce objeví
        // světlý pruh nebe tam, kde má být kalná voda.
        if (underwater) {
            glClearColor(WorldRenderer.WATER_FOG_R, WorldRenderer.WATER_FOG_G,
                    WorldRenderer.WATER_FOG_B, 1.0f);
        } else {
            float[] sky = day.skyColor();
            glClearColor(sky[0], sky[1], sky[2], 1.0f);
        }

        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Obloha před světem: je nekonečně daleko, takže ji terén má přebít.
        // Pod vodou se nekreslí - přes kalnou vodu není vidět ani slunce.
        if (!underwater) {
            sky.draw(worldRenderer.viewProjection(camera, width, height, options.fov()), day);
        }

        worldRenderer.render(world, camera, width, height, options.fov(), underwater, day,
                drops.items(), playerBody());

        if (state == GameState.PLAYING && hit != null) {
            worldRenderer.drawBlockOutline(hit.x(), hit.y(), hit.z(), camera);

            if (mining.isActive()) {
                worldRenderer.drawCracks(mining.x(), mining.y(), mining.z(),
                        mining.stage(), camera);
            }
        }

        // Až po obrysu bloku a před HUD - zaměřovač ani hotbar se tónovat nemají.
        if (underwater) {
            hud.drawUnderwaterTint(width, height);
        }
    }

    // ------------------------------------------------------------------
    // vstup
    // ------------------------------------------------------------------

    private int hoveredButton(Menu menu) {
        return menu.buttonAt(mouseX, mouseY, width, height);
    }

    /**
     * Kliknutí v menu. Zvuk kliknutí hraje AŽ PO akci tlačítka: "Create World"
     * a "Load World" zvukový engine zavřou a otevřou nový (jako svět), takže
     * zvuk pouštěný předtím by se hned uťal.
     */
    private void handleMenuClick() {
        if (state == GameState.MAIN_MENU) {
            int index = hoveredButton(mainMenu);

            if (index < 0) {
                return;
            }

            switch (mainMenuAction(mainMenu.label(index))) {
                case SINGLEPLAYER -> openSelectWorld();
                case OPTIONS -> openOptions();
                case LAB -> openTextureLab();
                case QUIT -> glfwSetWindowShouldClose(window, true);
                case NONE -> unhandled("hlavniho menu", mainMenu.label(index));
            }

            sound.play(Sound.CLICK);
            return;
        }

        int index = hoveredButton(pauseMenu);

        switch (pauseMenuAction(pauseMenu.label(index))) {
            case RESUME -> setState(GameState.PLAYING);
            case OPTIONS -> openOptions();
            // Odchod do menu svět zahazuje, takže se musí uložit teď -
            // i s náhledem, který se pak ukazuje v seznamu světů.
            case SAVE_AND_QUIT -> quitToTitle();
            case NONE -> {
                if (index >= 0) {
                    unhandled("pauzy", pauseMenu.label(index));
                }
            }
        }

        if (index >= 0) {
            sound.play(Sound.CLICK);
        }
    }

    // ------------------------------------------------------------------
    // co dělají tlačítka menu
    //
    // ⚠️ ROZHODUJE POPISEK, NE INDEX - pořadí tlačítek se může měnit. Cena
    // za to je, že přejmenování tlačítka rozpojí jeho akci, a to TIŠE: dokud
    // tu byla větev `default -> zavri okno`, znamenalo přejmenování
    // "Texture Lab" na "Lab", že tlačítko Lab ukončilo hru s návratovým
    // kódem 0 - žádná výjimka, žádná hláška, jen zavřené okno.
    //
    // Proto je převod popisku na akci VYTAŽENÝ do čisté funkce: `MenuTest`
    // projde všechna tlačítka obou menu a trvá na tom, že žádné nespadne na
    // NONE. Přejmenování tlačítka tak shodí test, ne hru. A NONE už nic
    // nedělá - zavření okna je vlastní větev QUIT, aby se na něj nedalo
    // spadnout omylem.
    // ------------------------------------------------------------------

    enum MainMenuAction { SINGLEPLAYER, OPTIONS, LAB, QUIT, NONE }

    enum PauseMenuAction { RESUME, OPTIONS, SAVE_AND_QUIT, NONE }

    static MainMenuAction mainMenuAction(String label) {
        return switch (label) {
            case "Singleplayer" -> MainMenuAction.SINGLEPLAYER;
            case "Options" -> MainMenuAction.OPTIONS;
            case "Lab" -> MainMenuAction.LAB;
            case "Quit" -> MainMenuAction.QUIT;
            default -> MainMenuAction.NONE;
        };
    }

    static PauseMenuAction pauseMenuAction(String label) {
        return switch (label) {
            case "Resume" -> PauseMenuAction.RESUME;
            case "Options" -> PauseMenuAction.OPTIONS;
            case "Save and Quit to Title" -> PauseMenuAction.SAVE_AND_QUIT;
            default -> PauseMenuAction.NONE;
        };
    }

    private static void unhandled(String menu, String label) {
        System.err.println("Tlacitko " + menu + " \"" + label + "\" nema akci - nic se nestalo");
    }

    /**
     * Přečte klávesnici do vstupních polí hráče. Samotný pohyb i kolize
     * řeší Player - tady se jen sbírá, co je zmáčknuté.
     */
    private void handleInput() {
        player.inputForward = 0;
        player.inputStrafe = 0;

        if (down(Keybinds.Action.FORWARD)) player.inputForward += 1;
        if (down(Keybinds.Action.BACK))    player.inputForward -= 1;
        if (down(Keybinds.Action.RIGHT))   player.inputStrafe += 1;
        if (down(Keybinds.Action.LEFT))    player.inputStrafe -= 1;

        player.inputJump   = down(Keybinds.Action.JUMP);
        player.inputSprint = down(Keybinds.Action.SPRINT);

        // Jedna klávesa, dva efekty: klesání v letu a plížení při chůzi -
        // co z toho platí, rozhodne Player. Dvě samostatné akce by musely
        // mít různé výchozí klávesy, jinak by hra startovala s kolizí.
        boolean sneak = down(Keybinds.Action.SNEAK);
        player.inputDescend = sneak;
        player.inputSneak   = sneak;
    }

    /**
     * Je klávesa téhle akce zmáčknutá?
     *
     * ⚠️ Ptá se `effectiveKey()`, ne `key()`. Akce v kolizi (dvě akce na
     * jedné klávese) vrací NONE, takže se ani jedna nespustí - jinak by
     * se dalo přiřadit W na dopředu i dozadu a hráč by stál na místě bez
     * vysvětlení. `glfwGetKey` se na NONE (-1) nesmí ptát vůbec: GLFW na
     * neplatný kód hlásí chybu.
     */
    private boolean down(Keybinds.Action action) {
        int key = Keybinds.active().effectiveKey(action);
        return key != Keybinds.NONE && glfwGetKey(window, key) == GLFW_PRESS;
    }

    // ------------------------------------------------------------------

    private void updateFps(float dt) {
        frameCount++;
        fpsTimer += dt;

        if (fpsTimer < 0.5) {
            return;
        }

        currentFps = (int) Math.round(frameCount / fpsTimer);
        frameCount = 0;
        fpsTimer = 0;
    }

    /** Ladicí výpis vlevo nahoře - nahradil dřívější zprávy v titulku okna. */
    private String[] debugLines() {
        return new String[]{
                String.format("%d FPS   vsync %s   max %s   render %d   sim %d",
                        currentFps, options.vsync() ? "on" : "off", options.fpsLabel(),
                        options.renderDistance(), options.simulationDistance()),
                String.format("XYZ  %.2f  %.2f  %.2f", player.x, player.y, player.z),
                String.format("chunk  %d %d   columns %d   edits %d",
                        (int) Math.floor(player.x) >> Chunk.BITS,
                        (int) Math.floor(player.z) >> Chunk.BITS,
                        world.loadedColumnCount(),
                        world.changedBlockCount()),
                String.format("sections %d   faces %d   queue %d",
                        worldRenderer.drawnSections(),
                        worldRenderer.drawnFaces(),
                        worldRenderer.pendingBuilds()),
                String.format("E inventory   held %d/%d slots   on ground %d",
                        usedSlots(), Inventory.SIZE, drops.size()),
                String.format("sound %s   atlas %s   skin %s   lab blocks %d",
                        sound.isOpen() ? String.format("on (%.0f ms)", sound.openMillis()) : "off",
                        atlasFromFile ? Textures.ATLAS_FILE.toString().replace('\\', '/') : "procedural",
                        skinFromFile ? Textures.SKIN_FILE.toString().replace('\\', '/') : "built-in",
                        BlockRegistry.active().size()),
                mining.isActive()
                        ? String.format("mining %.0f%%   stage %d",
                                mining.progress() * 100, mining.stage())
                        : "not mining",
                String.format("time %04.1f   sun %.2f   light sky %d block %d",
                        day.hours(), day.daylight(),
                        world.skyLightAt((int) Math.floor(camera.x),
                                (int) Math.floor(camera.y), (int) Math.floor(camera.z)),
                        world.blockLightAt((int) Math.floor(camera.x),
                                (int) Math.floor(camera.y), (int) Math.floor(camera.z))),
                String.format("%s   vy %.2f   mode %s",
                        player.noclip ? "NOCLIP" : player.flying ? "FLYING"
                                : player.inWater
                                    ? String.format("swimming %.0f%%", player.submerged * 100)
                                : player.onGround ? "on ground" : "in air",
                        player.vy, mode.label()),
                mode.canFly()
                        ? "Space x2 fly   Space/Ctrl up/down   Q drop   T time   V vsync   Esc pause"
                        : "F fly   C noclip   1-9 slot   Q drop   T time   V vsync   Esc pause",
                "F3 debug   F5 view   F6 texture lab   F11 fullscreen"
        };
    }

    private ItemStack[] inventorySnapshot() {
        ItemStack[] stacks = new ItemStack[Inventory.SIZE];
        for (int i = 0; i < Inventory.SIZE; i++) {
            stacks[i] = inventory.get(i);
        }
        return stacks;
    }

    private int usedSlots() {
        int used = 0;
        for (int i = 0; i < Inventory.SIZE; i++) {
            if (!inventory.get(i).isEmpty()) used++;
        }
        return used;
    }
}
