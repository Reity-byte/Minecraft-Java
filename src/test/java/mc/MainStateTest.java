package mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Overuje stav, ktery NEPATRI World, ale prezije vymenu sveta v temze behu hry.
 *
 * ---------------------------------------------------------------------------
 * PROC TENHLE TEST VUBEC JE. Main.freshWorld() vymenoval World, SoundEngine,
 * WorldRenderer a polozky na zemi - tedy vsechno, co je samo o sobe objekt.
 * Inventar, crafting mrizky, vybrany slot a denni doba ale objekt sveta nejsou:
 * jsou to pole Main, ktera zustavaji tataz instance po celou dobu behu hry.
 * Na ty se zapomnelo, takze:
 *
 *   BUG 1: nove zalozeny svet ukazal v inventari veci ze sveta, ve kterem
 *          hrac byl pred chvili.
 *   BUG 2: novy svet pokracoval v denni dobe tam, kde skoncil ten predchozi -
 *          hrac odesel v noci a novy svet zacal taky v noci.
 *
 * Obe chyby maji tutez pricinu, takze je hlida jeden test.
 *
 * ⚠️ CO TENHLE TEST NEPOKRYVA. freshWorld() sam zavolat nejde - otevira
 * OpenAL a saha na WorldRenderer, ktery bez GL kontextu neexistuje. Testuje
 * se proto resetPlayerState(), tedy presne ta metoda, kterou freshWorld()
 * vola hned po vymene World. Kdyby nekdo to jedno volani z freshWorld()
 * smazal, test to nechytne - je to jeden radek a jeho smazani je videt.
 * ---------------------------------------------------------------------------
 *
 * Nesaha na GL ani na OpenAL.
 */
public class MainStateTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        freshWorldResetsInventory();
        freshWorldResetsDayCycle();
        dayCycleValues();
        timeSurvivesSaveAndLoad();
        loadedWorldKeepsItsOwnState();
        worldInPlayDecidesSaving();
        craftingGridsAreSaved();
        playerAndCameraReset();
        everyFieldIsClassified();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    /**
     * Main bez GL. Konstruktor jen zaklada obycejne objekty (Camera, Player,
     * World, Inventory, Container, DayCycle); vsechno GL-ove se vyrabi az
     * v init(), ktery se tady nevola.
     *
     * ⚠️ World v nem rozjede generujici vlakno, takze se musi zastavit -
     * jinak by testy nechaly za sebou beziciho workera.
     */
    static Main headlessMain() {
        return new Main();
    }

    static void shutdown(Main main) {
        main.world.shutdown();
    }

    // ==================================================================
    // BUG 1: inventar, crafting mrizky a vybrany slot
    // ==================================================================

    static void freshWorldResetsInventory() {
        System.out.println("\n-- Bug 1: novy svet zacina s prazdnym inventarem --");

        Main main = headlessMain();

        // --- svet A: hrac si neco nahraje a neco rozdela v craftingu ---
        main.inventory.set(0, ItemStack.of(World.STONE, 40));
        main.inventory.set(5, ItemStack.of(World.IRON_ORE, 12));
        main.inventory.set(20, ItemStack.of(World.PLANKS, 64));
        main.selectedSlot = 7;
        main.craftingSmall.set(0, ItemStack.of(World.PLANKS, 3));
        main.craftingSmall.set(3, ItemStack.of(World.PLANKS, 1));
        main.craftingLarge.set(4, ItemStack.of(World.STONE_BRICKS, 9));
        main.craftingResult.set(0, ItemStack.of(World.CRAFTING_TABLE, 1));

        check("test neni degenerovany: ve svete A je opravdu neco v inventari",
                !main.inventory.get(0).isEmpty() && !main.craftingSmall.get(0).isEmpty(), "");

        // --- prechod na svet B (novy, bez ulozeneho souboru) ---
        main.resetPlayerState();

        boolean emptyInventory = true;
        for (int i = 0; i < Inventory.SIZE; i++) {
            if (!main.inventory.get(i).isEmpty()) emptyInventory = false;
        }
        check("novy svet ma uplne prazdny inventar", emptyInventory,
                main.inventory.get(0).toString());

        check("vybrany slot je zpatky na nule", main.selectedSlot == 0, "" + main.selectedSlot);

        boolean emptyCrafting = true;
        for (int i = 0; i < 4; i++) if (!main.craftingSmall.get(i).isEmpty()) emptyCrafting = false;
        for (int i = 0; i < 9; i++) if (!main.craftingLarge.get(i).isEmpty()) emptyCrafting = false;
        if (!main.craftingResult.get(0).isEmpty()) emptyCrafting = false;

        // Crafting mrizka je TRVALY kontejner - co v ni zustane, je tam i pri
        // dalsim otevreni. Presne proto se musi pri vymene sveta vyprazdnit.
        check("obe crafting mrizky i vysledkovy slot jsou prazdne", emptyCrafting, "");

        // A jeste jednou tam a zpet, at je videt, ze to neni jednorazovka.
        main.inventory.set(3, ItemStack.of(World.SNOW, 5));
        main.selectedSlot = 2;
        main.resetPlayerState();
        check("druhy prechod resetuje stejne",
                main.inventory.get(3).isEmpty() && main.selectedSlot == 0, "");

        shutdown(main);
    }

    // ==================================================================
    // BUG 2: denni doba
    // ==================================================================

    static void freshWorldResetsDayCycle() {
        System.out.println("\n-- Bug 2: novy svet zacina rano, ne tam, kde skoncil predchozi --");

        Main main = headlessMain();

        check("cerstva hra zacina na START_TIME",
                main.day.time() == DayCycle.START_TIME, "" + main.day.time());

        // --- svet A: hrac v nem stravi pul cyklu a odejde v noci ---
        main.day.skip(0.55f);
        check("test neni degenerovany: ve svete A je opravdu noc",
                main.day.isNight(), String.format("%.1f h", main.day.hours()));

        float nightTime = main.day.time();

        // --- prechod na svet B ---
        main.resetPlayerState();

        check("novy svet zacina na START_TIME, ne v case sveta A",
                main.day.time() == DayCycle.START_TIME,
                String.format("%.1f vs %.1f", main.day.time(), nightTime));
        check("a je v nem den, ne noc", !main.day.isNight(),
                String.format("%.1f h", main.day.hours()));
        check("START_TIME je dopoledne (mezi 8. a 11. hodinou)",
                main.day.hours() > 8f && main.day.hours() < 11f,
                String.format("%.1f h", main.day.hours()));

        shutdown(main);
    }

    /**
     * setTime() musi prezit i nesmysl z poskozeneho souboru.
     *
     * ⚠️ NaN by se pres modulo protahlo dal a daylight() by vracela NaN -
     * obloha i cely svet by zcernaly a nic by nereklo proc.
     */
    static void dayCycleValues() {
        System.out.println("\n-- DayCycle.setTime: meze --");

        DayCycle day = new DayCycle();

        day.setTime(123.5f);
        check("rozumny cas se vezme, jak je", day.time() == 123.5f, "" + day.time());

        day.setTime(0f);
        check("nula je platny cas (pulnoc cyklu)", day.time() == 0f, "" + day.time());

        for (float bad : new float[]{-1f, -0.001f, DayCycle.DAY_LENGTH, DayCycle.DAY_LENGTH + 1,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1e30f}) {
            day.setTime(bad);
            if (day.time() != DayCycle.START_TIME) {
                check("nesmyslny cas " + bad + " spadne na START_TIME", false, "" + day.time());
                return;
            }
        }
        check("zaporny, prilis velky, NaN i nekonecno spadnou na START_TIME", true, "");

        // A vysledek musi byt porad pouzitelne cislo, ne NaN.
        boolean finite = true;
        for (float t : new float[]{0f, 100f, 300f, 599.9f}) {
            day.setTime(t);
            if (!Float.isFinite(day.daylight()) || !Float.isFinite(day.hours())
                    || !Float.isFinite(day.skyAngle()) || !Float.isFinite(day.nightFactor())) {
                finite = false;
            }
        }
        check("daylight, hours, skyAngle i nightFactor jsou vzdycky konecne", finite, "");

        day.reset();
        check("reset() vrati START_TIME", day.time() == DayCycle.START_TIME, "" + day.time());
    }

    // ==================================================================
    // persistence casu
    // ==================================================================

    /**
     * Cas se uklada SE SVETEM (format MCW3), takze nacteny svet pokracuje tam,
     * kde hrac skoncil - odejit v noci a vratit se do noci.
     *
     * Volba mezi "ukladat cas" a "kazdy svet zacina rano": ukladat, protoze
     * format uz jednou presne takhle rostl (MCW1 bez inventare -> MCW2 s nim),
     * takze to neni prestavba, ale ctyri radky ve stejnem vzoru. Starsi soubor
     * cas nema a dostane START_TIME, tedy presne to, co delal dosud.
     */
    static void timeSurvivesSaveAndLoad() throws IOException {
        System.out.println("\n-- cas se uklada se svetem --");

        Path dir = Files.createTempDirectory("mc-daytime");
        Path file = dir.resolve("world.dat");

        World w = new World();
        ItemStack[] inventory = new ItemStack[Inventory.SIZE];
        inventory[0] = ItemStack.of(World.STONE, 3);

        float evening = DayCycle.DAY_LENGTH * 0.47f;

        check("ulozeni projde", WorldStorage.save(file, new WorldStorage.Save(
                1, 2, 3, 0, 0, false, 0, w.changes(), inventory, evening)), "");

        WorldStorage.Save loaded = WorldStorage.load(file);
        check("svet se nacte", loaded != null, "");

        if (loaded != null) {
            check("cas prezil ulozeni a nacteni presne", loaded.dayTime() == evening,
                    loaded.dayTime() + " vs " + evening);
        }

        // ⚠️ Soubor ze starsi verze (MCW2, bez casu) se musi porad nacist
        // a dostat START_TIME - jinak by zvyseni verze formatu znamenalo,
        // ze kazdy rozehrany svet zacina v case 0, tedy o pulnoci cyklu.
        Path old = dir.resolve("old.dat");
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(old)))) {
            out.writeInt(0x4D435732);                       // MCW2
            out.writeInt(WorldStorage.GENERATOR_VERSION);
            out.writeFloat(1); out.writeFloat(2); out.writeFloat(3);
            out.writeFloat(0); out.writeFloat(0);
            out.writeBoolean(false);
            out.writeInt(0);
            out.writeInt(0);                                // zadne zmeny bloku
            out.writeInt(Inventory.SIZE);
            for (int i = 0; i < Inventory.SIZE; i++) {
                out.writeByte(i == 0 ? World.SAND : World.AIR);
                out.writeInt(i == 0 ? 9 : 0);
            }
        }

        WorldStorage.Save oldLoaded = WorldStorage.load(old);
        check("soubor z verze MCW2 (bez casu) se porad nacte", oldLoaded != null, "");
        if (oldLoaded != null) {
            check("a dostane START_TIME, tedy to, co delal dosud",
                    oldLoaded.dayTime() == DayCycle.START_TIME, "" + oldLoaded.dayTime());
            check("jeho inventar dorazi beze zmeny",
                    oldLoaded.inventory()[0].block() == World.SAND
                            && oldLoaded.inventory()[0].count() == 9, "");
        }

        w.shutdown();

        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList())
                Files.deleteIfExists(p);
        }
    }

    /**
     * Cela cesta "svet A -> ulozit -> svet B -> nacist A zpatky" na stavu Main.
     *
     * ⚠️ resetPlayerState() se vola VZDYCKY, i u nacteneho sveta, a restore()
     * az po nem. Kdyby se reset delal jen u noveho sveta, byl by rozdil mezi
     * "novy svet" a "nacteny svet, jehoz soubor danou polozku jeste nema" -
     * ta polozka by se u druheho pripadu zdedila po predchozim svete.
     */
    static void loadedWorldKeepsItsOwnState() {
        System.out.println("\n-- nacteny svet si nese svuj vlastni stav --");

        Main main = headlessMain();
        main.world.loadRadius = 1;
        main.world.unloadRadius = 3;

        // --- svet A: veci v inventari, vecer ---
        main.inventory.set(2, ItemStack.of(World.COAL_ORE, 17));
        main.selectedSlot = 4;
        main.day.setTime(DayCycle.DAY_LENGTH * 0.60f);

        ItemStack[] snapshot = new ItemStack[Inventory.SIZE];
        for (int i = 0; i < Inventory.SIZE; i++) snapshot[i] = main.inventory.get(i);

        WorldStorage.Save saveA = new WorldStorage.Save(
                10, 70, 20, 1f, -0.5f, false, main.selectedSlot,
                main.world.changes(), snapshot, main.day.time());

        // --- svet B: novy ---
        main.resetPlayerState();
        check("svet B nema po A ani inventar, ani cas",
                main.inventory.get(2).isEmpty()
                        && main.day.time() == DayCycle.START_TIME
                        && main.selectedSlot == 0, "");

        // --- zpatky do sveta A: reset a hned po nem restore ---
        main.resetPlayerState();
        main.restore(saveA);

        check("nacteny svet vrati svuj inventar",
                main.inventory.get(2).block() == World.COAL_ORE
                        && main.inventory.get(2).count() == 17,
                main.inventory.get(2).toString());
        check("nacteny svet vrati svuj vybrany slot", main.selectedSlot == 4,
                "" + main.selectedSlot);
        check("nacteny svet vrati svuj cas, ne START_TIME",
                main.day.time() == DayCycle.DAY_LENGTH * 0.60f,
                String.format("%.1f", main.day.time()));
        check("a ve svete A je porad noc", main.day.isNight(),
                String.format("%.1f h", main.day.hours()));

        // Soubor bez mrizek (tady rucne poskladany snapshot jen s inventarem,
        // stejne jako ze starsi verze hry) dava prazdnou mrizku - a hlavne
        // se v ni po nacteni ciziho sveta neobjevi suroviny z predchoziho.
        // Ulozeni mrizek samotne hlida craftingGridsAreSaved().
        boolean craftingEmpty = true;
        for (int i = 0; i < 4; i++) if (!main.craftingSmall.get(i).isEmpty()) craftingEmpty = false;
        check("crafting mrizka je i u nacteneho sveta prazdna", craftingEmpty, "");

        shutdown(main);
    }

    // ==================================================================
    // Kdy se svet uklada pri zavreni okna (a kresli za Options a labem)
    // ==================================================================

    /**
     * BUG: zavreni okna s otevrenym inventarem (CONTAINER) nebo v Options
     * otevrenych z pauzy svet neulozilo. Podminka byla vlastni vycet stavu
     * v Main.run(), inventar v nem chybel uplne a Options se ptaly na navrat
     * do PLAYING - jenze se otviraji z PAUZY, takze to nikdy neplatilo.
     *
     * Rozhodnuti je ted jedina cista funkce Main.worldInPlay() se switchem
     * bez default vetve (novy GameState neprojde prekladem bez rozhodnuti).
     * Tenhle test prochazi VSECHNY stavy, takze zmena odpovedi u kterehokoliv
     * z nich ho shodi.
     */
    static void worldInPlayDecidesSaving() {
        System.out.println("\n-- Ukladani pri zavreni okna: ve kterych stavech se hraje svet --");

        GameState menu = GameState.MAIN_MENU;

        check("PLAYING se uklada", Main.worldInPlay(GameState.PLAYING, menu, menu), "");
        check("PAUSED se uklada", Main.worldInPlay(GameState.PAUSED, menu, menu), "");
        check("CONTAINER (inventar, crafting table) se uklada",
                Main.worldInPlay(GameState.CONTAINER, menu, menu), "");

        check("Options otevrene z PAUZY se ukladaji",
                Main.worldInPlay(GameState.OPTIONS, GameState.PAUSED, menu), "");
        check("Options otevrene z hlavniho menu se neukladaji",
                !Main.worldInPlay(GameState.OPTIONS, menu, menu), "");

        check("lab otevreny ze hry se uklada",
                Main.worldInPlay(GameState.TEXTURE_LAB, menu, GameState.PLAYING), "");
        check("lab otevreny z hlavniho menu se neuklada",
                !Main.worldInPlay(GameState.TEXTURE_LAB, menu, menu), "");

        // Vsechny ostatni stavy: zadny svet se v nich nehraje.
        java.util.Set<GameState> inPlay = java.util.EnumSet.of(
                GameState.PLAYING, GameState.PAUSED, GameState.CONTAINER);
        java.util.List<GameState> wrong = new java.util.ArrayList<>();

        for (GameState state : GameState.values()) {
            if (state == GameState.OPTIONS || state == GameState.TEXTURE_LAB) {
                continue;
            }
            if (Main.worldInPlay(state, menu, menu) != inPlay.contains(state)) {
                wrong.add(state);
            }
        }

        check("ostatni stavy (menu, loading, seznam a zakladani sveta) se neukladaji",
                wrong.isEmpty(), wrong.toString());
    }

    // ==================================================================
    // crafting mrizky se ukladaji se svetem
    // ==================================================================

    /**
     * BUG: co se pri zavreni obrazovky z mrizky do plneho inventare nevejde,
     * v mrizce zustane ("trvaly kontejner") - ale world.dat mrizky neukladal,
     * takze ulozeni a nacteni sveta ty predmety smazalo. Mrizky jsou ted
     * v ulozenem poli ZA inventarem; starsi build je jen preskoci.
     */
    static void craftingGridsAreSaved() throws IOException {
        System.out.println("\n-- crafting mrizky preziji ulozeni --");

        Main main = headlessMain();
        main.inventory.set(3, ItemStack.of(World.STONE, 9));
        main.craftingSmall.set(1, ItemStack.of(World.IRON_ORE, 5));
        main.craftingLarge.set(8, ItemStack.of(World.PLANKS, 2));

        ItemStack[] snapshot = main.inventorySnapshot();
        check("ulozene pole ma inventar a za nim obe mrizky",
                snapshot.length == Inventory.SIZE + 4 + 9, "" + snapshot.length);
        check("prvnich 36 polozek je presne inventar (starsi build cte jen je)",
                snapshot[3].block() == World.STONE && snapshot[3].count() == 9
                        && snapshot[Inventory.SIZE + 1].block() == World.IRON_ORE, "");

        Path dir = Files.createTempDirectory("mc-grids");
        Path file = dir.resolve("world.dat");
        check("ulozeni projde", WorldStorage.save(file, new WorldStorage.Save(
                1, 70, 1, 0, 0, false, 0, main.world.changes(), snapshot, 100f)), "");

        main.resetPlayerState();
        check("reset mrizky vyprazdni", main.craftingSmall.isEmpty() && main.craftingLarge.isEmpty(), "");

        main.restore(WorldStorage.load(file));
        check("mala mrizka se po nacteni vrati",
                main.craftingSmall.get(1).block() == World.IRON_ORE && main.craftingSmall.get(1).count() == 5,
                main.craftingSmall.get(1).toString());
        check("velka mrizka se po nacteni vrati",
                main.craftingLarge.get(8).block() == World.PLANKS && main.craftingLarge.get(8).count() == 2,
                main.craftingLarge.get(8).toString());
        check("inventar taky", main.inventory.get(3).count() == 9, main.inventory.get(3).toString());

        shutdown(main);
    }

    // ==================================================================
    // hrac a kamera mezi svety
    // ==================================================================

    /**
     * BUG 3: Player a Camera jsou taky jedna instance po cely beh hry. Novy
     * svet po creative svete zacinal v letu (i survival), noclip prechazel
     * do kazdeho dalsiho sveta a pohled zustal natoceny jako ve starem.
     */
    static void playerAndCameraReset() {
        System.out.println("\n-- Bug 3: novy svet nezdedi let, noclip ani pohled --");

        Main main = headlessMain();
        main.player.flying = true;
        main.player.noclip = true;
        main.player.vx = 3f;
        main.player.vy = -7f;
        main.camera.yaw = 1234f;
        main.camera.pitch = -80f;
        main.camera.view = Camera.View.THIRD_PERSON_BACK;
        main.camera.mouseSensitivity = 0.3f;

        main.resetPlayerState();

        check("let je vypnuty", !main.player.flying, "");
        check("noclip je vypnuty", !main.player.noclip, "");
        check("rychlost je nulova", main.player.vx == 0 && main.player.vy == 0 && main.player.vz == 0, "");
        check("pohled je vychozi (yaw, pitch)",
                main.camera.yaw == Camera.DEFAULT_YAW && main.camera.pitch == Camera.DEFAULT_PITCH,
                main.camera.yaw + " / " + main.camera.pitch);
        check("pohled F5 a citlivost jsou nastaveni hrace - zustanou",
                main.camera.view == Camera.View.THIRD_PERSON_BACK && main.camera.mouseSensitivity == 0.3f, "");

        // Nacteny svet si let vrati ze souboru, noclip ne (ten se neuklada).
        main.player.noclip = true;
        main.resetPlayerState();
        main.restore(new WorldStorage.Save(1, 70, 1, 45f, 10f, true, 0,
                main.world.changes(), new ItemStack[0], 100f));
        check("nacteny svet vrati let ze souboru", main.player.flying, "");
        check("nacteny svet noclip nezdedi", !main.player.noclip, "");
        check("nacteny svet vrati svuj pohled", main.camera.yaw == 45f && main.camera.pitch == 10f, "");

        shutdown(main);
    }

    // ==================================================================
    // kazde pole Main, Player a Camera je zarazene
    // ==================================================================

    /**
     * Javadoc freshWorld() dlouho tvrdil, ze tenhle test "prochazi seznam"
     * a spadne, kdyz nekdo prida pole a zapomene na reset. Neprochazel nic -
     * kontroloval ctyri natvrdo vyjmenovane veci, a proto prosel Bug 3.
     *
     * Ted ano: KAZDE instancni pole Main, Player a Camera musi byt tady
     * v jednom ze seznamu - bud patri konkretnimu svetu (a pak ho musi
     * resetovat resetPlayerState() nebo nastavit freshWorld()/restore()),
     * nebo prezije vymenu sveta (okno, GL, nastaveni, obrazovky). Nove pole,
     * ktere v seznamu neni, test shodi - a s nim otazku, kam patri.
     */
    static void everyFieldIsClassified() {
        System.out.println("\n-- kazde pole Main/Player/Camera je rozhodnute: svet, nebo sezeni --");

        // Patri svetu: resetPlayerState() ho vrati, nebo ho nastavi freshWorld()/restore().
        java.util.Set<String> mainWorld = java.util.Set.of(
                "world", "selectedSlot", "inventory", "craftingSmall", "craftingLarge", "craftingResult",
                "day", "flyTap", "mining", "miningHeld", "hit", "drops", "currentWorld", "mode",
                "screen", "spawnDone", "loadingFrames", "loadingTitle", "worldCenterX", "worldCenterZ",
                "columnsTotal", "meshesTotal");

        // Prezije svet: okno, GL objekty, nastaveni, obrazovky, mys, pocitadla.
        java.util.Set<String> mainSession = java.util.Set.of(
                "options", "windowMode", "limiter", "window", "width", "height", "camera", "player",
                "sound", "worldRenderer", "shapes", "text", "font", "background", "dirtTile", "hud",
                "state", "mainMenu", "pauseMenu", "lastX", "lastY", "firstMouse", "mouseX", "mouseY",
                "mouseScale", "glReady", "blockAtlas", "playerSkin", "atlasPixels", "atlasFromFile",
                "skinPixels", "skinFromFile", "atlasEditor", "skinEditor", "widgets", "images", "optionsScreen", "selectScreen",
                "createScreen", "optionsReturnState", "lab", "createdBlocks", "labReturnState",
                "showDebug", "icons", "sky", "heldItem", "swing", "animation", "playerMesh",
                "frameCount", "fpsTimer", "currentFps", "scrollRemainder");

        // Player: stav pohybu patri svetu (resetForNewWorld + spawn/restore). Vstup se plni
        // kazdy frame a rozesly krok (footsteps) klidne pretece - je to zlomek kroku.
        java.util.Set<String> playerWorld = java.util.Set.of(
                "x", "y", "z", "vx", "vy", "vz", "onGround", "submerged", "inWater", "flying", "noclip",
                "stepped", "stepBlock");
        java.util.Set<String> playerSession = java.util.Set.of(
                "inputForward", "inputStrafe", "inputJump", "inputDescend", "inputSprint", "inputSneak",
                "footsteps");

        java.util.Set<String> cameraWorld = java.util.Set.of("x", "y", "z", "yaw", "pitch");
        java.util.Set<String> cameraSession = java.util.Set.of("view", "mouseSensitivity", "invertMouseY");

        classified(Main.class, mainWorld, mainSession);
        classified(Player.class, playerWorld, playerSession);
        classified(Camera.class, cameraWorld, cameraSession);
    }

    static void classified(Class<?> type, java.util.Set<String> world, java.util.Set<String> session) {
        java.util.List<String> unknown = new java.util.ArrayList<>();
        java.util.Set<String> present = new java.util.HashSet<>();

        for (java.lang.reflect.Field f : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
            present.add(f.getName());
            if (!world.contains(f.getName()) && !session.contains(f.getName())) unknown.add(f.getName());
        }

        check(type.getSimpleName() + ": kazde pole je zarazene (svet / sezeni)",
                unknown.isEmpty(),
                unknown.isEmpty() ? "" : "nova pole " + unknown + " - patri do resetPlayerState(), nebo preziji svet?");

        java.util.List<String> stale = new java.util.ArrayList<>();
        for (String name : world) if (!present.contains(name)) stale.add(name);
        for (String name : session) if (!present.contains(name)) stale.add(name);

        check(type.getSimpleName() + ": seznam neobsahuje pole, ktera uz neexistuji",
                stale.isEmpty(), stale.toString());
    }
}
