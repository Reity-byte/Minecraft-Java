package mc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Overuje creative mod: okamzitou tezbu, pokladani bez spotreby, obsah
 * creative prehledu, jeho nekonecny zdroj, let a ulozeni modu ke svetu.
 *
 * ⚠️ TEZISKO JE NA IZOLACI OD SURVIVALU. Ke kazdemu creative pravidlu je tu
 * i kontrola, ze survival vetev dela presne to, co delala - volani BEZ modu
 * musi vyjit stejne jako volani s GameMode.SURVIVAL a stejne jako driv.
 *
 * ⚠️ Aktivni registr je staticky - test ho na konci VZDY vrati na prazdny,
 * jinak by ovlivnil testy, ktere po nem bezi ve stejne JVM (vzor LabBlockTest).
 */
public class CreativeTest {

    static int failures = 0;
    static final float DT = 1f / 60f;

    /** Obrazovka creative prehledu; rozmery panelu jsou jine nez u inventare. */
    static final int W = 1024, H = 768;
    static final int SCALE = Gui.scale(W, H);

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        try {
            mode();
            createScreen();
            mining();
            placing();
            contents();
            screen();
            flight();
            doubleTap();
            metadata();
        } finally {
            BlockRegistry.activate(BlockRegistry.empty());
        }

        System.out.println(failures == 0 ? "\nCreativeTest: OK" : "\nCreativeTest: " + failures + " FAIL");
    }

    // ==================================================================
    // GameMode jako rozcestnik
    // ==================================================================

    static void mode() {
        System.out.println("\n-- herni mod --");

        check("survival je vychozi a neumi nic navic",
                !GameMode.SURVIVAL.instantMining() && !GameMode.SURVIVAL.canFly()
                        && GameMode.SURVIVAL.keepsMinedBlock(), "");
        check("creative ma vsechna tri pravidla",
                GameMode.CREATIVE.instantMining() && GameMode.CREATIVE.canFly()
                        && !GameMode.CREATIVE.keepsMinedBlock(), "");
        check("next() cykluje mezi dvema mody",
                GameMode.SURVIVAL.next() == GameMode.CREATIVE
                        && GameMode.CREATIVE.next() == GameMode.SURVIVAL, "");

        // Zapis do world.json a zpatky.
        boolean roundTrip = true;
        for (GameMode m : GameMode.values()) {
            roundTrip &= GameMode.byId(m.id()) == m && GameMode.known(m.id());
        }
        check("id tam a zpet pro oba mody", roundTrip, "");
        check("id je male pismeny (soubor jde psat rucne)",
                GameMode.CREATIVE.id().equals("creative") && GameMode.SURVIVAL.id().equals("survival"), "");
        check("nezname id neni known() a padne na survival",
                !GameMode.known("adventure") && !GameMode.known(null)
                        && GameMode.byId("adventure") == GameMode.SURVIVAL
                        && GameMode.byId(null) == GameMode.SURVIVAL, "");
        check("label i popis jsou anglicky a neprazdne",
                GameMode.CREATIVE.label().equals("Creative")
                        && !GameMode.CREATIVE.description().isBlank()
                        && GameMode.SURVIVAL.description().chars().allMatch(c -> c >= 32 && c <= 126), "");
    }

    // ==================================================================
    // vyber modu pri zakladani sveta
    // ==================================================================

    /** Stred obdelniku v souradnicich mysi (y od horniho okraje). */
    static double[] centre(ScreenLayout l, ScreenLayout.Rect r) {
        return new double[]{l.screenX(r) + r.w() * l.scale() / 2.0,
                H - (l.screenBottom(r, H) + r.h() * l.scale() / 2.0)};
    }

    static void createScreen() throws IOException {
        System.out.println("\n-- vyber modu pri zakladani sveta --");

        Path root = tempRoot();

        try {
            CreateWorldScreen s = new CreateWorldScreen(null, root);
            ScreenLayout l = CreateWorldScreen.layout(W, H);

            double[] modeButton = centre(l, CreateWorldScreen.MODE);
            double[] create = centre(l, CreateWorldScreen.CREATE);
            double[] cancel = centre(l, CreateWorldScreen.CANCEL);
            double[] nameField = centre(l, CreateWorldScreen.NAME);

            check("novy svet je ve vychozim stavu survival", s.mode() == GameMode.SURVIVAL, "");

            check("klik na prepinac zapne creative a obrazovku nezavre",
                    s.press(modeButton[0], modeButton[1], W, H) == CreateWorldScreen.Action.NONE
                            && s.mode() == GameMode.CREATIVE, "" + s.mode());
            check("dalsi klik se vrati na survival",
                    s.press(modeButton[0], modeButton[1], W, H) == CreateWorldScreen.Action.NONE
                            && s.mode() == GameMode.SURVIVAL, "" + s.mode());

            // Prepinac nesmi prekryt Create ani Cancel.
            s.press(modeButton[0], modeButton[1], W, H);
            check("Create a Cancel dal reaguji na svem miste",
                    s.press(create[0], create[1], W, H) == CreateWorldScreen.Action.CREATE
                            && s.press(cancel[0], cancel[1], W, H) == CreateWorldScreen.Action.CANCEL, "");
            check("a mod se jimi nezmenil", s.mode() == GameMode.CREATIVE, "" + s.mode());

            // Psani do poli po prepnuti modu porad funguje.
            s.press(nameField[0], nameField[1], W, H);
            for (int i = 0; i < WorldSaves.MAX_NAME_LENGTH; i++) {
                s.key(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE, 0, "");
            }
            s.typed('H');
            s.typed('r');
            check("pole se jmenem jde po prepnuti modu porad psat",
                    s.name().equals("Hr"), s.name());

            check("Enter dal zaklada a Esc rusi",
                    s.key(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, "") == CreateWorldScreen.Action.CREATE
                            && s.key(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, "") == CreateWorldScreen.Action.CANCEL, "");

            s.reset();
            check("reset vrati vychozi survival (novy svet nezdedi minulou volbu)",
                    s.mode() == GameMode.SURVIVAL && s.name().equals(WorldSaves.DEFAULT_NAME), "");

            // Klik mimo prepinac mod nemeni.
            s.press(nameField[0], nameField[1], W, H);
            check("klik jinam mod nemeni", s.mode() == GameMode.SURVIVAL, "");
        } finally {
            deleteRecursively(root);
        }
    }

    // ==================================================================
    // tezba
    // ==================================================================

    /** Arena s podlahou z ruznych bloku nad sebou v jedne rade. */
    static World arena(int floor) {
        World w = new World();
        w.loadRadius = 1;
        w.unloadRadius = 3;
        w.updateBlocking(8f, 8f);

        w.placeBlock(8, floor, 8, World.DIRT);
        w.placeBlock(9, floor, 8, World.STONE);
        w.placeBlock(10, floor, 8, World.IRON_ORE);
        return w;
    }

    static Raycaster.RaycastHit at(int x, int y, int z) {
        return new Raycaster.RaycastHit(x, y, z, 0, 1, 0);
    }

    static void mining() {
        System.out.println("\n-- okamzita tezba --");

        final int FLOOR = 100;
        World w = arena(FLOOR);
        Mining m = new Mining();

        // ---------- creative rozbiji na prvni frame, bez ohledu na tvrdost ----------
        boolean instant = true;
        int[][] blocks = {{8, FLOOR, 8}, {9, FLOOR, 8}, {10, FLOOR, 8}};
        for (int[] b : blocks) {
            m.cancel();
            instant &= m.update(w, DT, true, at(b[0], b[1], b[2]), GameMode.CREATIVE);
        }
        check("v creative praskne kazdy blok uz v prvnim framu", instant, "");
        check("a to i u bloku, ktery survival kope pres dve vteriny",
                World.hardness(World.IRON_ORE) > 2f, String.format("%.1f s", World.hardness(World.IRON_ORE)));

        // Doba kopani v survivalu se tim nesmi zmenit.
        m.cancel();
        int survivalFrames = 0;
        while (survivalFrames < 2000 && !m.update(w, DT, true, at(9, FLOOR, 8), GameMode.SURVIVAL)) survivalFrames++;
        check("survival kope dal podle tvrdosti (kamen ~1.8 s)",
                Math.abs((survivalFrames + 1) * DT - World.hardness(World.STONE)) < 0.05f,
                String.format("%.2f s", (survivalFrames + 1) * DT));

        m.cancel();
        int defaultFrames = 0;
        while (defaultFrames < 2000 && !m.update(w, DT, true, at(9, FLOOR, 8))) defaultFrames++;
        check("volani BEZ modu je presne survival",
                defaultFrames == survivalFrames, defaultFrames + " vs " + survivalFrames);

        // ---------- pravidla zamereni plati i v creative ----------
        m.cancel();
        check("vzduch se nerozbije ani v creative",
                !m.update(w, DT, true, at(8, FLOOR + 5, 8), GameMode.CREATIVE), "");
        check("puste tlacitko nerozbiji ani v creative",
                !m.update(w, DT, false, at(8, FLOOR, 8), GameMode.CREATIVE), "");
        check("kurzor mimo blok nerozbiji ani v creative",
                !m.update(w, DT, true, null, GameMode.CREATIVE), "");

        // Voda neni zamerovatelna - creative na tom nic nemeni.
        World water = new World();
        water.loadRadius = 1;
        water.unloadRadius = 3;
        water.updateBlocking(8f, 8f);
        water.placeBlock(8, FLOOR, 8, World.WATER);
        m.cancel();
        check("voda se nerozbije ani v creative (isTargetable plati dal)",
                !m.update(water, DT, true, at(8, FLOOR, 8), GameMode.CREATIVE)
                        && !World.isTargetable(World.WATER), "");

        // ---------- ⚠️ vyteZeny blok v creative MIZI ----------
        World harvestWorld = arena(FLOOR);
        Inventory inv = new Inventory();
        DroppedItems drops = new DroppedItems();
        Mining hm = new Mining();

        hm.update(harvestWorld, DT, true, at(9, FLOOR, 8), GameMode.CREATIVE);
        boolean broke = hm.harvest(harvestWorld, inv, drops, SoundSink.SILENT, GameMode.CREATIVE);

        check("creative blok opravdu rozbije", broke && !harvestWorld.isSolid(9, FLOOR, 8), "");
        check("⚠️ v creative nic nepribude do inventare",
                inv.isEmpty(), inv.countOf(World.STONE) + " ks kamene");
        check("⚠️ v creative nic nezustane na zemi", drops.size() == 0, "" + drops.size());

        // Plny inventar v creative taky nic nevyhodi - neni co.
        Inventory full = new Inventory();
        for (int i = 0; i < Inventory.SIZE; i++) full.set(i, ItemStack.of(World.SAND, ItemStack.MAX_COUNT));
        DroppedItems fullDrops = new DroppedItems();

        hm.update(harvestWorld, DT, true, at(10, FLOOR, 8), GameMode.CREATIVE);
        hm.harvest(harvestWorld, full, fullDrops, SoundSink.SILENT, GameMode.CREATIVE);
        check("plny inventar v creative nic nevyhodi na zem",
                fullDrops.size() == 0 && full.countOf(World.IRON_ORE) == 0, "");

        // ---------- survival tezba je bajt po bajtu tataz ----------
        World survivalWorld = arena(FLOOR);
        Inventory sInv = new Inventory();
        DroppedItems sDrops = new DroppedItems();
        Mining sm = new Mining();

        while (!sm.update(survivalWorld, DT, true, at(9, FLOOR, 8), GameMode.SURVIVAL)) { /* kope se */ }
        sm.harvest(survivalWorld, sInv, sDrops, SoundSink.SILENT, GameMode.SURVIVAL);

        check("survival dal dava vytezeny blok do inventare",
                sInv.countOf(World.STONE) == 1, "" + sInv.countOf(World.STONE));

        // Bez modu (stara signatura) musi vyjit totez.
        World legacyWorld = arena(FLOOR);
        Inventory lInv = new Inventory();
        Mining lm = new Mining();
        while (!lm.update(legacyWorld, DT, true, at(9, FLOOR, 8))) { /* kope se */ }
        lm.harvest(legacyWorld, lInv, new DroppedItems(), SoundSink.SILENT);
        check("harvest BEZ modu je presne survival", lInv.countOf(World.STONE) == 1, "");

        // ---------- prasklinam v creative nezbyde cas ----------
        Mining stage = new Mining();
        stage.update(w, DT, true, at(8, FLOOR, 8), GameMode.CREATIVE);
        check("v creative se nekresli zadne stadium prasklin",
                stage.stage() == -1 && !stage.isActive(), "" + stage.stage());
    }

    // ==================================================================
    // pokladani
    // ==================================================================

    static void placing() {
        System.out.println("\n-- pokladani --");

        Inventory creative = new Inventory();
        creative.set(0, ItemStack.of(World.STONE, 5));

        for (int i = 0; i < 50; i++) {
            GameMode.CREATIVE.afterPlace(creative, 0);
        }
        check("⚠️ 50 polozeni v creative neubere z hotbaru ani kus",
                creative.hotbar(0).count() == 5, "" + creative.hotbar(0).count());

        Inventory survival = new Inventory();
        survival.set(0, ItemStack.of(World.STONE, 5));
        GameMode.SURVIVAL.afterPlace(survival, 0);
        check("survival dal ubira po jednom", survival.hotbar(0).count() == 4,
                "" + survival.hotbar(0).count());

        for (int i = 0; i < 4; i++) GameMode.SURVIVAL.afterPlace(survival, 0);
        check("survival hromadku dojede do prazdna", survival.hotbar(0).isEmpty(), "");

        // Jediny kus v creative vydrzi - proto staci STACK = 1 v prehledu.
        Inventory single = new Inventory();
        single.set(3, ItemStack.of(World.TORCH, CreativeInventory.STACK));
        for (int i = 0; i < 200; i++) GameMode.CREATIVE.afterPlace(single, 3);
        check("jeden kus v creative vydrzi 200 polozeni",
                single.hotbar(3).count() == CreativeInventory.STACK, "" + single.hotbar(3).count());
    }

    // ==================================================================
    // obsah prehledu
    // ==================================================================

    /** Vsechny id v kontejneru, v poradi slotu. */
    static List<Byte> idsOf(Container c) {
        List<Byte> ids = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) {
            if (!c.get(i).isEmpty()) ids.add(c.get(i).block());
        }
        return ids;
    }

    static void contents() {
        System.out.println("\n-- obsah creative prehledu --");

        // ---------- bez blocks.json: jen vestavene ----------
        BlockRegistry.activate(BlockRegistry.empty());
        List<Byte> builtIn = CreativeInventory.blocks(BlockRegistry.empty());

        // Ocekavany seznam se pocita NEZAVISLE, z rozsahu id, ne z te same metody.
        List<Byte> expected = new ArrayList<>();
        for (byte id = 1; id <= World.LAST_BUILT_IN; id++) expected.add(id);

        check("bez blocks.json je v prehledu presne id 1 az LAST_BUILT_IN",
                builtIn.equals(expected), builtIn.size() + " polozek");
        check("vzduch v prehledu neni (je to prazdna bunka, ne blok)",
                !builtIn.contains(World.AIR) && !CreativeInventory.isPlaceable(World.AIR), "");
        check("vsechny vestavene zname bloky jsou uvnitr",
                builtIn.contains(World.GRASS) && builtIn.contains(World.WATER)
                        && builtIn.contains(World.TORCH) && builtIn.contains(World.FENCE)
                        && builtIn.contains(World.CRAFTING_TABLE), "");

        Set<Byte> unique = new HashSet<>(builtIn);
        check("⚠️ presne jeden zaznam na blok (zadny dvakrat)",
                unique.size() == builtIn.size(), builtIn.size() + " vs " + unique.size());

        Container source = CreativeInventory.container(BlockRegistry.empty());
        boolean oneEach = source.size() == builtIn.size();
        for (int i = 0; i < source.size(); i++) {
            oneEach &= source.get(i).count() == CreativeInventory.STACK;
        }
        check("kazdy slot prehledu ma jeden kus a slotu je presne tolik co bloku",
                oneEach, source.size() + " slotu");

        // ---------- s bloky z labu ----------
        BlockRegistry r = BlockRegistry.empty();
        BlockDef marble = r.define("Marble", 1.0f, true, true, 63, 62, 61);
        r = r.with(marble);
        BlockDef glass = r.define("Glass", 0.2f, true, false, 60, 60, 60);
        r = r.with(glass);
        BlockDef ghost = r.define("Ghost", 0.5f, false, false, 59, 58, 57);
        r = r.with(ghost);

        BlockRegistry.activate(r);
        List<Byte> withLab = CreativeInventory.blocks(r);

        check("lab bloky prehled rozsiri presne o svuj pocet",
                withLab.size() == builtIn.size() + 3, withLab.size() + " polozek");
        check("kazdy nacteny lab blok ma v prehledu prave jeden zaznam",
                withLab.contains(marble.id()) && withLab.contains(glass.id())
                        && withLab.contains(ghost.id())
                        && new HashSet<>(withLab).size() == withLab.size(), "");
        check("vestavene bloky zustaly na zacatku a ve stejnem poradi",
                withLab.subList(0, builtIn.size()).equals(builtIn), "");
        check("lab bloky jdou za nimi, serazene podle id",
                withLab.get(builtIn.size()) == marble.id()
                        && withLab.get(builtIn.size() + 2) == ghost.id(), "");
        check("i neprubezny lab blok (ne pevny, ne nepruhledny) je v prehledu",
                withLab.contains(ghost.id()) && !World.blocksMovement(ghost.id()), "");

        check("prehled z aktivniho registru je tentyz jako z predaneho",
                idsOf(CreativeInventory.container(BlockRegistry.active())).equals(withLab), "");

        // Poradi se mezi otevrenimi nemeni - blok neskace po mrizce.
        check("prehled je deterministicky",
                idsOf(CreativeInventory.container(r)).equals(idsOf(CreativeInventory.container(r))), "");

        BlockRegistry.activate(BlockRegistry.empty());
        check("po zmizeni blocks.json se prehled vrati na vestavene",
                CreativeInventory.blocks(BlockRegistry.active()).equals(builtIn), "");
    }

    // ==================================================================
    // obrazovka: nekonecny zdroj
    // ==================================================================

    /** Stred slotu creative panelu v souradnicich mysi (y od horniho okraje). */
    static double[] slot(int guiX, int guiY, int column, int row) {
        int left = ContainerScreen.panelLeft(W, SCALE, ContainerScreen.CREATIVE_WIDTH);
        int bottom = ContainerScreen.panelBottom(H, SCALE, ContainerScreen.CREATIVE_HEIGHT);
        int pitch = ContainerScreen.SLOT_PITCH;

        double x = left + (guiX + column * pitch + pitch / 2) * SCALE;
        double yFromBottom = bottom
                + (ContainerScreen.CREATIVE_HEIGHT - guiY - row * pitch - pitch + pitch / 2) * SCALE;

        return new double[]{x, H - yFromBottom};
    }

    static double[] sourceSlot(int index) {
        return slot(8, 18, index % ContainerScreen.CREATIVE_COLUMNS, index / ContainerScreen.CREATIVE_COLUMNS);
    }

    static double[] hotbarSlot(int i) { return slot(8, 182, i, 0); }

    static double[] backpackSlot(int i) { return slot(8, 124, i % 9, i / 9); }

    static void click(ContainerScreen s, double[] p, boolean left, boolean shift, Inventory inv) {
        s.press(p[0], p[1], W, H, left, shift, inv);
        s.release(p[0], p[1], W, H, left, inv);
    }

    static void screen() {
        System.out.println("\n-- creative prehled jako obrazovka --");

        BlockRegistry.activate(BlockRegistry.empty());

        Container source = CreativeInventory.container(BlockRegistry.empty());
        Inventory inv = new Inventory();
        ContainerScreen s = ContainerScreen.creativeInventory(source, inv);

        check("obrazovka se jmenuje jinak nez survival inventar",
                s.title().equals("Creative Inventory"), s.title());

        // ---------- ⚠️ z prehledu se bere KOPIE ----------
        ItemStack before = source.get(0);
        click(s, sourceSlot(0), true, false, inv);
        check("po vzeti drzi kurzor blok z prehledu",
                s.held().block() == before.block() && !s.held().isEmpty(), s.held().toString());
        check("⚠️ v prehledu blok ZUSTAL (nekonecny zdroj, ne presun)",
                source.get(0).equals(before), source.get(0).toString());

        // Polozit ho do hotbaru (druhy klik, jako v Minecraftu).
        click(s, hotbarSlot(0), true, false, inv);
        check("blok se polozi do hotbaru", inv.hotbar(0).block() == before.block(),
                inv.hotbar(0).toString());
        check("kurzor je po polozeni prazdny", s.held().isEmpty(), s.held().toString());
        check("a v prehledu je porad", source.get(0).equals(before), "");

        // Deset odberu toho sameho bloku prehled nevycerpa.
        for (int i = 0; i < 10; i++) {
            click(s, sourceSlot(0), true, false, inv);
            click(s, hotbarSlot(1), true, false, inv);
        }
        check("deset odberu prehled nevycerpa", source.get(0).equals(before), source.get(0).toString());

        // Pravym tlacitkem se taky bere cely zaznam, ne polovina.
        Inventory inv2 = new Inventory();
        ContainerScreen s2 = ContainerScreen.creativeInventory(source, inv2);
        click(s2, sourceSlot(1), false, false, inv2);
        check("pravym tlacitkem se z prehledu bere totez co levym (ne polovina)",
                s2.held().equals(source.get(1)), s2.held().toString());
        check("a taky se tim z prehledu nic neubere",
                source.get(1).count() == CreativeInventory.STACK, "");

        // ---------- shift-klik kopiruje do hotbaru ----------
        Inventory inv3 = new Inventory();
        ContainerScreen s3 = ContainerScreen.creativeInventory(source, inv3);
        click(s3, sourceSlot(2), true, true, inv3);
        check("shift-klik z prehledu doda blok do hotbaru",
                inv3.hotbar(0).block() == source.get(2).block(), inv3.hotbar(0).toString());
        check("a z prehledu opet nic neubylo", source.get(2).count() == CreativeInventory.STACK, "");

        // ---------- polozit neco do prehledu = zahodit ----------
        Inventory inv4 = new Inventory();
        inv4.set(0, ItemStack.of(World.SAND, 30));
        ContainerScreen s4 = ContainerScreen.creativeInventory(source, inv4);
        click(s4, hotbarSlot(0), true, false, inv4);          // vzit pisek
        check("pisek je v ruce", s4.held().count() == 30, s4.held().toString());

        int sourceSlots = source.size();
        click(s4, sourceSlot(0), true, false, inv4);          // pustit ho do prehledu
        check("polozeni do prehledu hromadku zahodi (kos)", s4.held().isEmpty(), s4.held().toString());
        check("a prehled se tim nezmeni ani o slot",
                source.size() == sourceSlots && source.get(0).count() == CreativeInventory.STACK, "");

        // ---------- rolovani ----------
        BlockRegistry big = BlockRegistry.empty();
        for (int i = 0; i < 40; i++) {
            big = big.with(big.define("Lab " + i, 1f, true, true, 63, 63, 63));
        }
        Container bigSource = CreativeInventory.container(big);
        ContainerScreen s5 = ContainerScreen.creativeInventory(bigSource, new Inventory());

        int visible = ContainerScreen.CREATIVE_COLUMNS * ContainerScreen.CREATIVE_ROWS;
        int rows = (bigSource.size() + ContainerScreen.CREATIVE_COLUMNS - 1) / ContainerScreen.CREATIVE_COLUMNS;

        check("s vic bloky, nez se vejde, jde rolovat",
                bigSource.size() > visible && s5.maxScrollRow() == rows - ContainerScreen.CREATIVE_ROWS,
                bigSource.size() + " bloku, " + s5.maxScrollRow() + " radku navic");

        s5.scroll(-1);
        check("kolecko dolu roluje o radek", s5.scrollRow() == 1, "" + s5.scrollRow());
        s5.scroll(1);
        check("kolecko nahoru se vrati", s5.scrollRow() == 0, "" + s5.scrollRow());
        s5.scroll(1);
        check("na prvnim radku uz se nerozjede nahoru", s5.scrollRow() == 0, "" + s5.scrollRow());

        for (int i = 0; i < 100; i++) s5.scroll(-1);
        check("dolu se zastavi na poslednim radku", s5.scrollRow() == s5.maxScrollRow(),
                s5.scrollRow() + " / " + s5.maxScrollRow());

        // Po odrolovani ukazuje mrizka jine bloky - a klik bere ten, ktery je videt.
        s5.setScrollRow(1);
        Inventory inv5 = new Inventory();
        ContainerScreen s6 = ContainerScreen.creativeInventory(bigSource, inv5);
        s6.setScrollRow(1);
        click(s6, sourceSlot(0), true, false, inv5);
        click(s6, hotbarSlot(0), true, false, inv5);
        check("⚠️ po odrolovani bere klik blok o radek dal",
                inv5.hotbar(0).block() == bigSource.get(ContainerScreen.CREATIVE_COLUMNS).block(),
                inv5.hotbar(0).toString());

        ContainerScreen small = ContainerScreen.creativeInventory(
                CreativeInventory.container(BlockRegistry.empty()), new Inventory());
        check("kdyz se vsechno vejde, nema se kam rolovat", small.maxScrollRow() == 0, "");

        // ---------- batoh a hotbar se chovaji jako vzdycky ----------
        Inventory inv6 = new Inventory();
        inv6.set(0, ItemStack.of(World.STONE, 10));
        ContainerScreen s7 = ContainerScreen.creativeInventory(source, inv6);
        click(s7, hotbarSlot(0), true, true, inv6);          // shift-klik hotbar -> batoh
        check("shift-klik v inventari dal prehazuje hotbar a batoh",
                inv6.hotbar(0).isEmpty() && inv6.get(Inventory.HOTBAR_SIZE).count() == 10, "");

        click(s7, backpackSlot(0), true, false, inv6);        // vzit z batohu
        check("z batohu se bere normalne (nekonecny je jen prehled)",
                inv6.get(Inventory.HOTBAR_SIZE).isEmpty() && s7.held().count() == 10,
                s7.held().toString());

        ItemStack leftover = s7.returnItems(inv6);
        check("zavreni obrazovky vrati kurzor do inventare",
                leftover.isEmpty() && inv6.countOf(World.STONE) == 10, "");

        // ---------- survival obrazovka se nezmenila ----------
        Inventory sInv = new Inventory();
        sInv.set(0, ItemStack.of(World.STONE, 8));
        Container crafting = new Container(4);
        ContainerScreen survival = ContainerScreen.playerInventory(sInv, crafting, new Container(1));

        int left = ContainerScreen.panelLeft(W, SCALE);
        int bottom = ContainerScreen.panelBottom(H, SCALE);
        double hx = left + (8 + ContainerScreen.SLOT_PITCH / 2) * SCALE;
        double hy = H - (bottom + (ContainerScreen.PANEL_HEIGHT - 142 - ContainerScreen.SLOT_PITCH
                + ContainerScreen.SLOT_PITCH / 2) * SCALE);

        double[] survivalHotbar = {hx, hy};
        click(survival, survivalHotbar, true, false, sInv);
        check("⚠️ survival inventar dal BERE ze slotu (nekopiruje)",
                sInv.hotbar(0).isEmpty() && survival.held().count() == 8, sInv.hotbar(0).toString());

        click(survival, survivalHotbar, true, false, sInv);
        check("a druhym klikem se vrati zpatky",
                sInv.hotbar(0).count() == 8 && survival.held().isEmpty(), sInv.hotbar(0).toString());
        check("survival inventar nema co rolovat", survival.maxScrollRow() == 0, "");
    }

    // ==================================================================
    // let
    // ==================================================================

    /** Hrac stojici na kamenne plosine ve vysce FLOOR. */
    static World platform(int floor) {
        World w = new World();
        w.loadRadius = 1;
        w.unloadRadius = 3;
        w.updateBlocking(8f, 8f);

        for (int x = 6; x <= 11; x++) {
            for (int z = 6; z <= 11; z++) {
                w.placeBlock(x, floor, z, World.STONE);
            }
        }
        return w;
    }

    static void step(Player p, World w, int frames) {
        for (int i = 0; i < frames; i++) p.update(w, DT, 0f);
    }

    static void flight() {
        System.out.println("\n-- let --");

        final int FLOOR = 100;
        World w = platform(FLOOR);

        Player p = new Player();
        p.x = 8.5f; p.z = 8.5f; p.y = FLOOR + 1;
        p.flying = true;

        // ---------- bez vstupu se v letu nepada ----------
        float start = p.y;
        step(p, w, 120);
        check("v letu se bez vstupu nepada (zadna gravitace)",
                Math.abs(p.y - start) < 1e-3f && p.vy == 0f, String.format("%.4f", p.y - start));

        // ---------- mezernik nahoru ----------
        p.inputJump = true;
        float before = p.y;
        step(p, w, 60);
        float rise = p.y - before;
        check("mezernik v letu stoupa", rise > 5f, String.format("%.2f b/s", rise));

        p.inputJump = false;
        p.inputDescend = true;
        before = p.y;
        step(p, w, 60);
        float fall = before - p.y;
        check("Ctrl v letu klesa", fall > 5f, String.format("%.2f b/s", fall));
        check("nahoru a dolu jdou stejne rychle", Math.abs(rise - fall) < 0.3f,
                String.format("%.2f vs %.2f", rise, fall));

        // ---------- obe klavesy naraz se vyrusi ----------
        p.inputJump = true;
        before = p.y;
        step(p, w, 30);
        check("mezernik a Ctrl naraz se vyrusi", Math.abs(p.y - before) < 1e-3f,
                String.format("%.4f", p.y - before));

        // ---------- Shift zrychluje ----------
        p.inputDescend = false;
        p.inputSprint = false;
        before = p.y;
        step(p, w, 30);
        float normal = p.y - before;

        p.inputSprint = true;
        before = p.y;
        step(p, w, 30);
        float sprint = p.y - before;
        check("Shift let zrychli (a vodorovne i svisle stejne)",
                sprint > normal * 2f, String.format("%.2f vs %.2f", sprint, normal));

        // ---------- ⚠️ v letu kolize PORAD PLATI ----------
        p.inputJump = false;
        p.inputSprint = false;
        p.inputDescend = true;
        step(p, w, 200);
        check("⚠️ v letu se nepropadne podlahou (kolize plati, to je rozdil proti noclipu)",
                p.y >= FLOOR + 1 - 1e-2f, String.format("y %.3f, podlaha %d", p.y, FLOOR + 1));

        // Noclip je ta druha vec - s nim se propadne.
        Player ghost = new Player();
        ghost.x = 8.5f; ghost.z = 8.5f; ghost.y = FLOOR + 1;
        ghost.flying = true;
        ghost.noclip = true;
        ghost.inputDescend = true;
        step(ghost, w, 60);
        check("noclip podlahou propadne - je to jina vec nez let",
                ghost.y < FLOOR, String.format("%.2f", ghost.y));

        // ---------- vypnuti letu vrati gravitaci ----------
        Player landing = new Player();
        landing.x = 8.5f; landing.z = 8.5f; landing.y = FLOOR + 10;
        landing.flying = true;
        step(landing, w, 30);
        check("v letu se drzi ve vysce", landing.y > FLOOR + 9, String.format("%.2f", landing.y));

        landing.flying = false;
        landing.vy = 0;
        step(landing, w, 180);
        check("po vypnuti letu dopadne na podlahu",
                landing.onGround && Math.abs(landing.y - (FLOOR + 1)) < 0.01f,
                String.format("%.3f", landing.y));

        // ---------- survival hrac bez letu je nezmeneny ----------
        Player survival = new Player();
        survival.x = 8.5f; survival.z = 8.5f; survival.y = FLOOR + 5;
        survival.inputJump = true;
        step(survival, w, 120);
        check("survival hrac s drzenym mezernikem neleti, jen skace",
                !survival.flying && survival.y < FLOOR + 3, String.format("%.2f", survival.y));
    }

    // ==================================================================
    // dvojstisk mezerniku
    // ==================================================================

    static void doubleTap() {
        System.out.println("\n-- dvojstisk mezerniku --");

        DoubleTap tap = new DoubleTap();

        check("prvni stisk nikdy neprepina", !tap.tap(10.0), "");
        check("druhy stisk v okne prepne", tap.tap(10.0 + DoubleTap.WINDOW - 0.01), "");

        tap.reset();
        check("prvni stisk po resetu neprepina", !tap.tap(20.0), "");
        check("stisk az po okne neprepina", !tap.tap(20.0 + DoubleTap.WINDOW + 0.01), "");
        check("ale ten po nem uz ano", tap.tap(20.0 + DoubleTap.WINDOW + 0.02), "");

        // ⚠️ Treti stisk uz neprepina - jinak by rychle poskakovani blikalo letem.
        DoubleTap triple = new DoubleTap();
        triple.tap(30.0);
        boolean second = triple.tap(30.1);
        boolean third = triple.tap(30.2);
        boolean fourth = triple.tap(30.3);
        check("⚠️ z trojice stisku prepne jen druhy", second && !third, second + " / " + third);
        check("ctvrty stisk prepne zas (kazda dvojice = jedno prepnuti)", fourth, "");

        // Odchod do menu rozdelany dvojstisk zahodi.
        DoubleTap paused = new DoubleTap();
        paused.tap(40.0);
        paused.reset();
        check("reset rozdelany dvojstisk zahodi", !paused.tap(40.05), "");

        // Bezne skakani (delsi pauzy) letem neprepina.
        DoubleTap jumping = new DoubleTap();
        boolean anyToggle = false;
        for (int i = 0; i < 20; i++) anyToggle |= jumping.tap(50.0 + i * 0.5);
        check("skakani po pul vterine let neprepne", !anyToggle, "");
    }

    // ==================================================================
    // ulozeni modu ke svetu
    // ==================================================================

    static Path tempRoot() throws IOException {
        return Files.createTempDirectory("mc-creative-");
    }

    static WorldSaves.WorldInfo find(List<WorldSaves.WorldInfo> list, String name) {
        for (WorldSaves.WorldInfo w : list) {
            if (w.name().equals(name) || w.folder().equals(name)) return w;
        }
        return null;
    }

    static void metadata() throws IOException {
        System.out.println("\n-- mod ve world.json --");

        Path root = tempRoot();

        try {
            // ---------- tam a zpet ----------
            WorldSaves.WorldInfo created = WorldSaves.create(root, "Stavitel", 42L, "42",
                    GameMode.CREATIVE, 1000L);
            check("svet se zalozi s creative modem",
                    created != null && created.mode() == GameMode.CREATIVE, "");

            WorldSaves.WorldInfo back = find(WorldSaves.list(root), "Stavitel");
            check("⚠️ mod prezije zapis a nacteni, stejne jako seed",
                    back.mode() == GameMode.CREATIVE && back.seed() == 42L, "" + back.mode());

            String json = Files.readString(created.metaFile(), StandardCharsets.UTF_8);
            check("mod je v souboru textem vedle seedu",
                    json.contains("\"gameMode\": \"creative\"") && json.contains("\"seed\": \"42\""), json);

            WorldSaves.WorldInfo survival = WorldSaves.create(root, "Prezitel", 7L, "",
                    GameMode.SURVIVAL, 2000L);
            check("survival svet se ulozi jako survival",
                    find(WorldSaves.list(root), "Prezitel").mode() == GameMode.SURVIVAL, "");

            // ---------- posledni hrani mod nesmi shodit ----------
            WorldSaves.WorldInfo played = WorldSaves.touch(back, 9000L);
            check("touch() mod zachova v pameti", played.mode() == GameMode.CREATIVE, "");
            check("touch() mod zachova i na disku",
                    find(WorldSaves.list(root), "Stavitel").mode() == GameMode.CREATIVE, "");

            // ---------- volani bez modu = survival ----------
            WorldSaves.create(root, "Bez modu", 1L, "", 3000L);
            check("create() BEZ modu zaklada survival",
                    find(WorldSaves.list(root), "Bez modu").mode() == GameMode.SURVIVAL, "");

            // ---------- ⚠️ stary svet bez klice gameMode ----------
            Path old = root.resolve("stary");
            Files.createDirectories(old);
            Files.writeString(old.resolve(WorldSaves.META_FILE),
                    "{\n  \"format\": 1,\n  \"name\": \"Stary\",\n  \"seed\": \"12345\",\n"
                            + "  \"seedText\": \"\",\n  \"created\": 1,\n  \"lastPlayed\": 2\n}\n",
                    StandardCharsets.UTF_8);

            WorldSaves.WorldInfo legacy = find(WorldSaves.list(root), "Stary");
            check("⚠️ svet z formatu 1 (bez gameMode) je survival",
                    legacy != null && legacy.mode() == GameMode.SURVIVAL && legacy.seed() == 12345L, "");

            // ---------- ⚠️ nesmyslna hodnota svet neschova ----------
            Path broken = root.resolve("preklep");
            Files.createDirectories(broken);
            Files.writeString(broken.resolve(WorldSaves.META_FILE),
                    "{\n  \"format\": 2,\n  \"name\": \"Preklep\",\n  \"seed\": \"5\",\n"
                            + "  \"gameMode\": \"kreativ\",\n  \"created\": 1,\n  \"lastPlayed\": 2\n}\n",
                    StandardCharsets.UTF_8);

            WorldSaves.WorldInfo typo = find(WorldSaves.list(root), "Preklep");
            check("⚠️ neznamy gameMode svet nezahodi, jen se hraje jako survival",
                    typo != null && typo.mode() == GameMode.SURVIVAL && typo.seed() == 5L, "");

            Path wrongType = root.resolve("cislo");
            Files.createDirectories(wrongType);
            Files.writeString(wrongType.resolve(WorldSaves.META_FILE),
                    "{\n  \"format\": 2,\n  \"name\": \"Cislo\",\n  \"seed\": \"5\",\n"
                            + "  \"gameMode\": 1,\n  \"created\": 1,\n  \"lastPlayed\": 2\n}\n",
                    StandardCharsets.UTF_8);
            check("gameMode, ktery neni text, taky padne na survival",
                    find(WorldSaves.list(root), "Cislo").mode() == GameMode.SURVIVAL, "");

            check("format world.json se kvuli modu zvysil na 2", WorldSaves.FORMAT == 2, "");
        } finally {
            deleteRecursively(root);
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;

        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
