package mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Overuje obrazovky svetu bez GL: vyber sveta (razeni, oznaceni, klavesy,
 * rolovani, potvrzene mazani), zalozeni sveta (jmeno, slozka, seed) a celou
 * cestu hrou: zalozit -> ulozit -> najit v seznamu -> nacist se stejnym
 * terenem.
 *
 * Obrazovky se daji postavit bez Widgets, protoze kresleni je oddelene od
 * hit-testu a stavu - konstruktor si odkazy jen schova.
 */
public class WorldScreenTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        createScreen();
        selectScreen();
        wholeFlow();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    static Path temp() throws IOException {
        return Files.createTempDirectory("mc-worlds");
    }

    static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList())
                Files.deleteIfExists(p);
        }
    }

    static double[] centre(ScreenLayout l, ScreenLayout.Rect r) {
        return new double[]{l.left() + (r.x() + r.w() / 2.0) * l.scale(), l.top() + (r.y() + r.h() / 2.0) * l.scale()};
    }

    // ==================================================================

    static void createScreen() throws IOException {
        Path root = temp();

        try {
            CreateWorldScreen screen = new CreateWorldScreen(null, root);
            int w = 1024, h = 768;
            ScreenLayout l = CreateWorldScreen.layout(w, h);

            check("nova obrazovka ma vychozi jmeno a prazdny seed",
                    screen.name().equals(WorldSaves.DEFAULT_NAME) && screen.seedText().isEmpty(), screen.name());
            check("slozka pro vychozi jmeno", screen.folder().equals("New World"), screen.folder());

            // Psani jde do pole, ve kterem je fokus.
            double[] seedField = centre(l, CreateWorldScreen.SEED);
            screen.press(seedField[0], seedField[1], w, h);
            for (char c : "hello".toCharArray()) screen.typed(c);
            check("psani jde do pole se seedem", screen.seedText().equals("hello")
                    && screen.name().equals(WorldSaves.DEFAULT_NAME), screen.seedText());

            double[] nameField = centre(l, CreateWorldScreen.NAME);
            screen.press(nameField[0], nameField[1], w, h);
            for (char c : ": Cave/World".toCharArray()) screen.typed(c);
            check("psani jde do pole se jmenem", screen.name().equals("New World: Cave/World"), screen.name());
            check("nazev slozky se ocisti od zakazanych znaku",
                    screen.folder().equals("New World_ Cave_World"), screen.folder());

            WorldSaves.create(root, screen.name(), 1L, "hello", 1L);

            // Obrazovka se pri otevreni resetuje, takze nahled slozky vznika
            // znovu - a tehdy uz vidi, ze stejna slozka existuje.
            CreateWorldScreen reopened = new CreateWorldScreen(null, root);
            reopened.press(nameField[0], nameField[1], w, h);
            for (char c : ": Cave/World".toCharArray()) reopened.typed(c);
            check("po zalozeni stejneho jmena nabidne slozka (2)",
                    reopened.folder().equals("New World_ Cave_World (2)"), reopened.folder());

            check("Tab prepne pole, Esc rusi, Enter zaklada",
                    screen.key(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB, 0, "") == CreateWorldScreen.Action.NONE
                            && screen.key(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, "") == CreateWorldScreen.Action.CANCEL
                            && screen.key(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, "") == CreateWorldScreen.Action.CREATE, "");

            // Po Tabu je fokus v seedu - vlozeni ze schranky pripoji tam.
            screen.key(org.lwjgl.glfw.GLFW.GLFW_KEY_V, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL, "-4242");
            check("Ctrl+V vlozi do pole s fokusem", screen.seedText().equals("hello-4242"), screen.seedText());

            double[] create = centre(l, CreateWorldScreen.CREATE);
            double[] cancel = centre(l, CreateWorldScreen.CANCEL);
            check("tlacitka Create a Cancel",
                    screen.press(create[0], create[1], w, h) == CreateWorldScreen.Action.CREATE
                            && screen.press(cancel[0], cancel[1], w, h) == CreateWorldScreen.Action.CANCEL, "");

            screen.reset();
            check("reset vrati vychozi stav",
                    screen.name().equals(WorldSaves.DEFAULT_NAME) && screen.seedText().isEmpty(), "");

            // Obrazovka se vejde do panelu a prvky se neprekryvaji.
            ScreenLayout.Rect[] rects = {CreateWorldScreen.NAME, CreateWorldScreen.NAME_LABEL,
                    CreateWorldScreen.FOLDER, CreateWorldScreen.SEED_LABEL, CreateWorldScreen.SEED,
                    CreateWorldScreen.SEED_HINT, CreateWorldScreen.MODE, CreateWorldScreen.MODE_HINT,
                    CreateWorldScreen.CREATE, CreateWorldScreen.CANCEL};
            check("prvky zalozeni sveta se neprekryvaji a jsou v panelu",
                    separate(rects, CreateWorldScreen.WIDTH, CreateWorldScreen.HEIGHT), "");
        } finally {
            deleteTree(root);
        }
    }

    static boolean separate(ScreenLayout.Rect[] rects, int width, int height) {
        for (int i = 0; i < rects.length; i++) {
            ScreenLayout.Rect a = rects[i];
            if (a.x() < 0 || a.y() < 0 || a.right() > width || a.bottom() > height) return false;
            for (int j = i + 1; j < rects.length; j++) {
                ScreenLayout.Rect b = rects[j];
                if (a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom()) return false;
            }
        }
        return true;
    }

    // ==================================================================

    static void selectScreen() throws IOException {
        Path root = temp();

        try {
            long now = 1_700_000_000_000L;
            WorldSaves.create(root, "Oldest", 1L, "", now - 3000);
            WorldSaves.create(root, "Middle", 2L, "", now - 2000);
            WorldSaves.create(root, "Newest", 3L, "", now - 1000);
            WorldSaves.create(root, "Fourth", 4L, "", now - 4000);
            WorldSaves.create(root, "Fifth", 5L, "", now - 5000);

            SelectWorldScreen screen = new SelectWorldScreen(null, null, root);
            screen.refresh();
            int w = 1024, h = 768;
            ScreenLayout l = SelectWorldScreen.layout(w, h);

            List<WorldSaves.WorldInfo> worlds = screen.worlds();
            check("seznam je od naposledy hraneho", worlds.size() == 5
                    && worlds.get(0).name().equals("Newest") && worlds.get(4).name().equals("Fifth"),
                    worlds.stream().map(WorldSaves.WorldInfo::name).toList().toString());
            check("vybrany je rovnou ten naposledy hrany", screen.selected().name().equals("Newest"), "");

            // Klik na druhy radek vybere druhy svet; dvojklik hraje.
            double[] second = centre(l, SelectWorldScreen.rowRect(1));
            check("prvni klik jen vybira",
                    screen.press(second[0], second[1], w, h, 10.0) == SelectWorldScreen.Action.NONE
                            && screen.selected().name().equals("Middle"), screen.selected().name());
            check("dvojklik hraje",
                    screen.press(second[0], second[1], w, h, 10.2) == SelectWorldScreen.Action.PLAY, "");
            check("pomaly druhy klik uz dvojklik neni",
                    screen.press(second[0], second[1], w, h, 11.5) == SelectWorldScreen.Action.NONE, "");

            // Klavesy: sipky posouvaji vyber, Enter hraje, Esc rusi.
            screen.key(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN);
            check("sipka dolu posune vyber", screen.selected().name().equals("Oldest"), screen.selected().name());
            check("Enter hraje, Esc se vraci",
                    screen.key(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) == SelectWorldScreen.Action.PLAY
                            && screen.key(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) == SelectWorldScreen.Action.CANCEL, "");

            // Peti svet je videt az po odrolovani (ctyri radky).
            check("paty svet neni videt bez rolovani", screen.worldAt(l, centre(l, SelectWorldScreen.rowRect(3))[0],
                    centre(l, SelectWorldScreen.rowRect(3))[1]) == 3, "");
            screen.scroll(-1);
            check("po odrolovani je na poslednim radku paty svet",
                    worlds.get(screen.worldAt(l, centre(l, SelectWorldScreen.rowRect(3))[0],
                            centre(l, SelectWorldScreen.rowRect(3))[1])).name().equals("Fifth"), "");
            for (int i = 0; i < 20; i++) screen.scroll(-1);
            check("rolovani se orizne na konec seznamu",
                    screen.worldAt(l, centre(l, SelectWorldScreen.rowRect(3))[0],
                            centre(l, SelectWorldScreen.rowRect(3))[1]) == 4, "");
            for (int i = 0; i < 20; i++) screen.scroll(1);

            // Mazani: az po potvrzeni, a smaze jen svou slozku.
            double[] delete = centre(l, SelectWorldScreen.DELETE);
            double[] confirmCancel = centre(l, SelectWorldScreen.CONFIRM_CANCEL);
            double[] confirmDelete = centre(l, SelectWorldScreen.CONFIRM_DELETE);

            screen.press(second[0], second[1], w, h, 20.0);
            String doomed = screen.selected().name();
            screen.press(delete[0], delete[1], w, h, 21.0);
            check("Delete se nejdriv zepta", screen.isConfirming() && Files.isDirectory(root.resolve(doomed)), "");
            screen.press(confirmCancel[0], confirmCancel[1], w, h, 22.0);
            check("Cancel v dialogu nic nesmaze",
                    !screen.isConfirming() && Files.isDirectory(root.resolve(doomed)) && screen.worlds().size() == 5, "");

            screen.press(delete[0], delete[1], w, h, 23.0);
            screen.press(confirmDelete[0], confirmDelete[1], w, h, 24.0);
            check("potvrzene mazani smaze slozku a zmizi ze seznamu",
                    !Files.exists(root.resolve(doomed)) && screen.worlds().size() == 4
                            && screen.worlds().stream().noneMatch(x -> x.name().equals(doomed)), doomed);
            check("ostatni svety zustaly", Files.isDirectory(root.resolve("Newest")), "");

            // Prazdny seznam: nejde hrat ani mazat, jde zalozit.
            for (WorldSaves.WorldInfo world : screen.worlds()) {
                WorldSaves.delete(root, world);
            }
            screen.refresh();
            double[] play = centre(l, SelectWorldScreen.PLAY);
            check("bez svetu nejde hrat ani mazat, ale jde zalozit",
                    screen.selected() == null && screen.worlds().isEmpty()
                            && screen.press(play[0], play[1], w, h, 30.0) == SelectWorldScreen.Action.NONE
                            && screen.press(delete[0], delete[1], w, h, 31.0) == SelectWorldScreen.Action.NONE
                            && !screen.isConfirming()
                            && screen.press(centre(l, SelectWorldScreen.CREATE)[0],
                            centre(l, SelectWorldScreen.CREATE)[1], w, h, 32.0) == SelectWorldScreen.Action.CREATE, "");
        } finally {
            deleteTree(root);
        }
    }

    // ==================================================================

    /**
     * Cesta hrou: obrazovka zalozi svet se zadanym seedem, hra do nej ulozi
     * zmeny, seznam ho ukaze prvni a nacteni vrati totez - i tentyz teren.
     */
    static void wholeFlow() throws IOException {
        Path root = temp();

        try {
            CreateWorldScreen create = new CreateWorldScreen(null, root);
            int w = 1024, h = 768;
            ScreenLayout l = CreateWorldScreen.layout(w, h);

            double[] nameField = centre(l, CreateWorldScreen.NAME);
            create.press(nameField[0], nameField[1], w, h);
            for (int i = 0; i < WorldSaves.DEFAULT_NAME.length(); i++) create.key(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE, 0, "");
            for (char c : "Cave Base".toCharArray()) create.typed(c);

            double[] seedField = centre(l, CreateWorldScreen.SEED);
            create.press(seedField[0], seedField[1], w, h);
            for (char c : "hello".toCharArray()) create.typed(c);

            long seed = Seeds.parse(create.seedText(), Seeds::random);
            long now = 1_700_000_100_000L;
            WorldSaves.WorldInfo info = WorldSaves.create(root, create.name(), seed, create.seedText(), now);

            check("zalozeny svet ma jmeno, slozku i seed z textu",
                    info != null && info.name().equals("Cave Base") && info.folder().equals("Cave Base")
                            && info.seed() == "hello".hashCode() && info.seedText().equals("hello"),
                    info == null ? "null" : info.folder() + " seed " + info.seed());

            // Hra: svet z toho seedu, zmena bloku a ulozeni do jeho slozky.
            World world = new World(info.seed());
            world.loadRadius = 2;
            world.unloadRadius = 4;
            world.updateBlocking(8f, 8f);
            int surface = world.generator().terrainHeight(8, 8);
            world.placeBlock(8, surface + 1, 8, World.STONE_BRICKS);

            ItemStack[] inventory = new ItemStack[Inventory.SIZE];
            java.util.Arrays.fill(inventory, ItemStack.EMPTY);
            inventory[0] = ItemStack.of(World.PLANKS, 5);

            check("svet se ulozi do sve slozky",
                    WorldStorage.save(info.worldFile(), new WorldStorage.Save(8, surface + 2, 8, 0, 0, false, 0,
                            world.changes(), inventory, DayCycle.START_TIME))
                            && Files.isRegularFile(root.resolve("Cave Base").resolve("world.dat")), "");

            WorldSaves.WorldInfo touched = WorldSaves.touch(info, now + 5000);
            WorldSaves.create(root, "Jiny svet", 1L, "", now + 1000);   // hran driv nez touch

            List<WorldSaves.WorldInfo> list = WorldSaves.list(root);
            check("v seznamu je prvni ten, ve kterem se hralo naposledy",
                    list.size() == 2 && list.get(0).folder().equals("Cave Base")
                            && list.get(0).lastPlayed() == touched.lastPlayed(), "" + list.get(0).name());

            // Nacteni: tytez zmeny, tentyz inventar, tentyz teren ze seedu.
            WorldSaves.WorldInfo reloaded = list.get(0);
            WorldStorage.Save save = WorldStorage.load(reloaded.worldFile());
            World again = new World(reloaded.seed());
            again.loadRadius = 2;
            again.unloadRadius = 4;
            again.restoreChanges(save.changes());
            again.updateBlocking(8f, 8f);

            check("po nacteni je postaveny blok na miste a inventar sedi",
                    again.getBlock(8, surface + 1, 8) == World.STONE_BRICKS
                            && save.inventory()[0].block() == World.PLANKS && save.inventory()[0].count() == 5, "");
            check("a teren je tentyz, protoze seed je ulozeny u sveta",
                    again.generator().terrainHeight(8, 8) == surface
                            && again.seed() == "hello".hashCode(), "");

            // Jiny seed = jiny svet (kontrola, ze se seed opravdu pouziva).
            World other = new World(seed + 1);
            boolean sameEverywhere = true;
            for (int x = 0; x < 64 && sameEverywhere; x += 7)
                for (int z = 0; z < 64; z += 7)
                    if (other.generator().terrainHeight(x, z) != again.generator().terrainHeight(x, z))
                        sameEverywhere = false;
            check("jiny seed da jiny teren", !sameEverywhere, "");

            world.shutdown();
            again.shutdown();
            other.shutdown();
        } finally {
            deleteTree(root);
        }
    }
}
