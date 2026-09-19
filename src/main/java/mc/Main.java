package mc;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;
import java.nio.file.Path;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Minecraft-like base:
 *  - GLFW okno, OpenGL 3.3 core profile
 *  - nekonečný svět po chuncích (World), mesh v VBO (WorldRenderer)
 *  - hráč s hitboxem, gravitací a kolizemi (Player)
 *  - stavový automat menu / loading / hra (GameState)
 */
public class Main {

    private static final float FOV = 70f;

    /** Odkud se začíná hledat suchá zem pro spawn. */
    private static final int SPAWN_SEARCH_X = 8;
    private static final int SPAWN_SEARCH_Z = 8;
    private static final int SPAWN_SEARCH_RADIUS = 64;

    private long window;
    private int width = 1024, height = 768;

    private final Camera camera = new Camera();
    private final Player player = new Player();
    private World world = new World(); // nahrazuje se při vytvoření nového světa

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

    /** Denní doba. Běží jen ve hře, v menu i v inventáři stojí. */
    private final DayCycle day = new DayCycle();

    // Texty UI jsou anglicky schválně: atlas fontu pokrývá jen ASCII 32-126,
    // takže česká diakritika by se vykreslila jako otazníky.
    /** Kam se ukládá svět. Jeden slot; víc světů by chtělo výběr v menu. */
    private static final Path SAVE_PATH = Path.of("saves", "world.dat");

    // Hlavní menu se skládá znovu při každém návratu do něj: tlačítko
    // "Load World" má smysl ukazovat, jen když nějaký uložený svět existuje.
    private Menu mainMenu = new Menu("Minecraft Base", "Create World", "Quit");
    private boolean mainMenuHasLoad = false;
    private final Menu pauseMenu = new Menu("Paused", "Resume", "Back to Menu");

    // mouse look state
    private double lastX, lastY;
    private boolean firstMouse = true;

    // pozice kurzoru pro menu (v soustavě GLFW, počátek vlevo nahoře)
    private double mouseX, mouseY;

    private Raycaster.RaycastHit hit;

    /** Kopání: drží se tlačítko a jak daleko je rozbíjení. */
    private final Mining mining = new Mining();
    private boolean miningHeld = false;

    private int selectedSlot = 0;

    // --- inventář ---
    private final Inventory inventory = new Inventory();

    /** Malá mřížka u inventáře a velká na crafting table; výsledek je sdílený. */
    private final Container craftingSmall = new Container(4);
    private final Container craftingLarge = new Container(9);
    private final Container craftingResult = new Container(1);

    /** Vytváří se až po GL kontextu, proto ne u deklarace. */
    private Texture blockAtlas;
    private Texture playerSkin;
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
    private boolean vsync = true;

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
        if (state == GameState.PLAYING || state == GameState.PAUSED) {
            saveWorld();
        }

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
            glViewport(0, 0, w, h);
        });

        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            mouseX = xpos;
            mouseY = ypos;

            // S drženým tlačítkem nad kontejnerem se táhne přes sloty.
            if (state == GameState.CONTAINER) {
                screen.drag(xpos, ypos, width, height);
                return;
            }

            // Rozhlížení jen ve hře. V menu by kamera utíkala pod kurzorem.
            if (state != GameState.PLAYING) {
                return;
            }

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

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            // Q vyhodí z ruky jeden kus, Ctrl+Q celou hromádku - jako v Minecraftu.
            // Držené Q sype dál po jednom, proto se bere i opakování klávesy.
            if (key == GLFW_KEY_Q && state == GameState.PLAYING
                    && (action == GLFW_PRESS || action == GLFW_REPEAT)) {
                dropFromHand((mods & GLFW_MOD_CONTROL) != 0);
                return;
            }

            if (action != GLFW_PRESS) {
                return;
            }

            // Escape už hru nezavírá - přepíná pauzu. Zavřít jde z menu.
            if (key == GLFW_KEY_ESCAPE) {
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

            // E zavírá otevřený kontejner. Musí být před testem na PLAYING,
            // protože ve stavu CONTAINER se hra nehýbe.
            if (key == GLFW_KEY_E) {
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

            // C = noclip (proletět čímkoliv), F = volný let (bez gravitace).
            // Jsou to dvě různé věci: v letu kolize pořád platí.
            if (key == GLFW_KEY_C) {
                player.noclip = !player.noclip;
            }
            if (key == GLFW_KEY_F) {
                player.flying = !player.flying;
                player.vy = 0; // ať se po vypnutí letu nezačne padat setrvačností
            }
            // F5 přepíná pohled: první osoba -> zezadu -> zepředu -> zpět.
            if (key == GLFW_KEY_F5) {
                camera.view = camera.view.next();
            }
            // výběr slotu hotbaru číselnými klávesami
            if (key >= GLFW_KEY_1 && key <= GLFW_KEY_9) {
                selectedSlot = key - GLFW_KEY_1;
            }
            // Vypnutí vsync odstropuje FPS - teprve pak je vidět, co renderer stíhá.
            // T posune čas o desetinu cyklu - na noc se jinak čeká minuty.
            if (key == GLFW_KEY_T) {
                day.skip(0.1f);
            }
            if (key == GLFW_KEY_V) {
                vsync = !vsync;
                glfwSwapInterval(vsync ? 1 : 0);
            }
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            // Kopání potřebuje vědět, jestli se tlačítko DRŽÍ, ne jen že
            // bylo stisknuté - proto se sleduje i puštění.
            if (button == GLFW_MOUSE_BUTTON_LEFT && state == GameState.PLAYING) {
                miningHeld = action == GLFW_PRESS;
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
                    inventory.removeOne(selectedSlot);
                    swing.trigger();
                    // V prostoru, ze středu položeného bloku.
                    sound.playAt(Sound.placeOf(selected.block()), px + 0.5f, py + 0.5f, pz + 0.5f);
                }
            }
        });

        glfwSetScrollCallback(window, (win, xoffset, yoffset) -> {
            if (state != GameState.PLAYING) {
                return;
            }
            // Kolečko nahoru = doleva po hotbaru, jako v Minecraftu.
            // floorMod, aby se to na krajích správně přetočilo dokola.
            selectedSlot = Math.floorMod(
                    selectedSlot - (int) Math.signum(yoffset), Inventory.HOTBAR_SIZE);
        });

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // vsync
        glfwShowWindow(window);

        GL.createCapabilities();

        // Framebuffer nemusí mít stejnou velikost jako okno (DPI škálování),
        // a callback se při startu nezavolá - je potřeba se zeptat ručně.
        int[] fbWidth = new int[1];
        int[] fbHeight = new int[1];
        glfwGetFramebufferSize(window, fbWidth, fbHeight);
        width = fbWidth[0];
        height = fbHeight[0];
        glViewport(0, 0, width, height);

        glEnable(GL_DEPTH_TEST);

        // Backface culling: zahodí stěny odvrácené od kamery ještě před
        // rasterizací. Funguje to jen proto, že všechny stěny mají vrcholy
        // konzistentně proti směru hodinových ručiček při pohledu zvenku.
        glEnable(GL_CULL_FACE);

        glClearColor(WorldRenderer.SKY_R, WorldRenderer.SKY_G, WorldRenderer.SKY_B, 1.0f);

        // Až tady, protože shadery a textury potřebují aktivní kontext
        // Atlas vlastní Main a půjčuje ho renderu světa i ikonám bloků.
        blockAtlas = Textures.blockAtlas();
        icons = new BlockIcon(blockAtlas);

        // Skin se nahrazuje v jediném místě - v Textures.playerSkin().
        playerSkin = Textures.playerSkin();
        worldRenderer = new WorldRenderer(blockAtlas, playerSkin);
        sky = new SkyRenderer();
        heldItem = new HeldItemRenderer(blockAtlas);
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

        setState(GameState.MAIN_MENU);
    }

    private void setState(GameState next) {
        state = next;

        if (next == GameState.MAIN_MENU) {
            mainMenuHasLoad = WorldStorage.exists(SAVE_PATH);
            mainMenu = mainMenuHasLoad
                    ? new Menu("Minecraft Base", "Create World", "Load World", "Quit")
                    : new Menu("Minecraft Base", "Create World", "Quit");
        }

        // Kurzor je chycený jen při hraní; v menu musí být vidět a volný.
        glfwSetInputMode(window, GLFW_CURSOR,
                next == GameState.PLAYING ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);

        // Po návratu do hry se musí zahodit stará pozice myši, jinak by se
        // kamera skokem otočila o rozdíl nasbíraný v menu.
        if (next == GameState.PLAYING) {
            firstMouse = true;
        }

        // Odejít do menu s drženým tlačítkem by po návratu pokračovalo v kopání.
        if (next != GameState.PLAYING) {
            miningHeld = false;
            mining.cancel();
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
            }

            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    // ------------------------------------------------------------------
    // stavy
    // ------------------------------------------------------------------

    private void startWorldCreation() {
        freshWorld();

        // Terén u počátku souřadnic vychází pod hladinou, takže se spawn hledá:
        // jinak by hra začínala po pás ve vodě. Výška terénu je čistá funkce
        // šumu, takže to nepotřebuje vygenerovaný svět.
        int[] spawn = World.findLandSpawn(SPAWN_SEARCH_X, SPAWN_SEARCH_Z, SPAWN_SEARCH_RADIUS);

        loadingTitle = "Creating world";
        worldCenterX = spawn[0] + 0.5f;
        worldCenterZ = spawn[1] + 0.5f;
        spawnDone = false;   // hráče postaví na terén až updateCreatingWorld

        setState(GameState.CREATING_WORLD);
    }

    private void loadWorld() {
        WorldStorage.Save save = WorldStorage.load(SAVE_PATH);

        if (save == null) {
            // Soubor je pryč nebo poškozený; zpráva už je na stderr.
            // Spadnout kvůli tomu nemá smysl - založí se nový svět.
            startWorldCreation();
            return;
        }

        freshWorld();

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

        loadingTitle = "Loading world";
        worldCenterX = player.x;
        worldCenterZ = player.z;
        spawnDone = true;    // pozice je z uloženého světa, hledat terén netřeba

        setState(GameState.CREATING_WORLD);
    }

    /** Společný začátek nového i načteného světa. */
    private void freshWorld() {
        // Starý svět musí zastavit svoje generující vlákno, jinak by běžela dvě.
        world.shutdown();
        world = new World();

        // Zvuk symetricky se světem: nový svět nezdědí nic, co ještě hraje
        // ze starého. Změřeno: zavření ~30 ms, nové otevření 55-150 ms.
        sound.shutdown();
        sound = SoundEngine.open(SoundLibrary.SOUND_DIR);

        worldRenderer.reset();
        worldRenderer.setBuildBudget(WorldRenderer.BUILD_BUDGET_LOADING);

        drops.clear();

        loadingFrames = 0;
        columnsTotal = 0;
        meshesTotal = 0;
    }

    private void openInventory() {
        screen = ContainerScreen.playerInventory(inventory, craftingSmall, craftingResult);
        screen.refreshResult();
        setState(GameState.CONTAINER);
    }

    private void openCraftingTable() {
        screen = ContainerScreen.craftingTable(inventory, craftingLarge, craftingResult);
        screen.refreshResult();
        setState(GameState.CONTAINER);
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

    private void saveWorld() {
        WorldStorage.save(SAVE_PATH, new WorldStorage.Save(
                player.x, player.y, player.z,
                camera.yaw, camera.pitch,
                player.flying, selectedSlot,
                world.changes(), inventorySnapshot()));
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
        worldRenderer.render(world, camera, width, height, FOV, false, day, drops.items(), null);

        int missingColumns = world.pendingColumns();
        int pendingMeshes = worldRenderer.pendingBuilds();

        hud.drawLoading(width, height, loadingTitle,
                loadingProgress(missingColumns, pendingMeshes));

        // Pár framů rezervy, aby se fronty stihly vůbec naplnit.
        if (missingColumns == 0 && pendingMeshes == 0 && loadingFrames > 3) {
            worldRenderer.setBuildBudget(WorldRenderer.BUILD_BUDGET_PLAYING);
            setState(GameState.PLAYING);
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

        if (mining.update(world, dt, miningHeld, hit)) {
            // Vytěžený kus jde do inventáře; co se nevejde, vypadne na zem.
            mining.harvest(world, inventory, drops, sound);
        }

        // Až po pohybu hráče, ať se sbírá podle toho, kde hráč stojí teď.
        drops.update(world, player, inventory, dt);

        renderWorld();

        drawHeldItem();

        hud.draw(width, height, world, inventory, selectedSlot, debugLines());
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
     * i zaměřovač jsou nad ní.
     */
    private void drawHeldItem() {
        ItemStack held = inventory.hotbar(selectedSlot);

        // Ve třetí osobě drží blok model postavy - viz playerBody().
        if (held.isEmpty() || camera.view != Camera.View.FIRST_PERSON) {
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

        heldItem.draw(width, height, FOV, held.block(),
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
            sky.draw(worldRenderer.viewProjection(camera, width, height, FOV), day);
        }

        worldRenderer.render(world, camera, width, height, FOV, underwater, day,
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

            // Pořadí tlačítek se liší podle toho, jestli je co načítat -
            // proto se neporovnává s pevnými indexy dvakrát.
            if (index == 0) {
                startWorldCreation();
            } else if (mainMenuHasLoad && index == 1) {
                loadWorld();
            } else {
                glfwSetWindowShouldClose(window, true);
            }

            sound.play(Sound.CLICK);
            return;
        }

        int index = hoveredButton(pauseMenu);

        switch (index) {
            case 0 -> setState(GameState.PLAYING);
            case 1 -> {
                // Odchod do menu svět zahazuje, takže se musí uložit teď.
                saveWorld();
                setState(GameState.MAIN_MENU);
            }
            default -> { }
        }

        if (index >= 0) {
            sound.play(Sound.CLICK);
        }
    }

    /**
     * Přečte klávesnici do vstupních polí hráče. Samotný pohyb i kolize
     * řeší Player - tady se jen sbírá, co je zmáčknuté.
     */
    private void handleInput() {
        player.inputForward = 0;
        player.inputStrafe = 0;

        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) player.inputForward += 1;
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) player.inputForward -= 1;
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) player.inputStrafe += 1;
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) player.inputStrafe -= 1;

        player.inputJump    = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        player.inputSprint  = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS;
        // Ctrl je klesání v letu a plížení při chůzi - co z toho, rozhodne Player
        player.inputDescend = glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS;
        player.inputSneak   = glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS;
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
                String.format("%d FPS   vsync %s", currentFps, vsync ? "on" : "off"),
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
                String.format("E inventory   held %d/%d slots   on ground %d   sound %s",
                        usedSlots(), Inventory.SIZE, drops.size(),
                        sound.isOpen() ? String.format("on (%.0f ms)", sound.openMillis()) : "off"),
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
                String.format("%s   vy %.2f",
                        player.noclip ? "NOCLIP" : player.flying ? "FLYING"
                                : player.inWater
                                    ? String.format("swimming %.0f%%", player.submerged * 100)
                                : player.onGround ? "on ground" : "in air",
                        player.vy),
                "F fly   C noclip   1-9 slot   Q drop   F5 view   T time   V vsync   Esc pause"
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
