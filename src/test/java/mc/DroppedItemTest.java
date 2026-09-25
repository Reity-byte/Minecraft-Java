package mc;

/**
 * Overuje predmety na zemi: padani a kolize, vyhozeni z ruky, sber, zanik
 * a stavbu meshe.
 *
 * DroppedItem, DroppedItems i DroppedItemMesh.build() nesahaji na GL, takze
 * jde vsechno krome samotneho kresleni. Arena je plosina vysoko nad terenem,
 * stejne jako v PhysicsTest - terén pod ni do testu nezasahuje.
 */
public class DroppedItemTest {

    static int failures = 0;
    static final float DT = 1f / 60f;

    static final int FLOOR = 100;
    /** Horni povrch plosiny - tam ma polozka po dopadu lezet. */
    static final float TOP = FLOOR + 1;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    static boolean near(float a, float b, float tol) { return Math.abs(a - b) <= tol; }

    static Player playerAt(float x, float y, float z) {
        Player p = new Player();
        p.x = x; p.y = y; p.z = z;
        return p;
    }

    static void run(DroppedItems drops, World w, Player p, Container inv, int frames) {
        for (int i = 0; i < frames; i++) drops.update(w, p, inv, DT);
    }

    /** Nejvyssi hodnota jedne slozky vrcholu v meshi (5 = slunecni, 6 = blokove svetlo). */
    static float maxChannel(DroppedItemMesh mesh, int offset) {
        float max = 0f;
        for (int i = 0; i < mesh.vertexCount(); i++)
            max = Math.max(max, mesh.vertices()[i * DroppedItemMesh.FLOATS_PER_VERTEX + offset]);
        return max;
    }

    public static void main(String[] args) {
        World w = new World();
        w.loadRadius = 1;
        w.unloadRadius = 3;
        w.updateBlocking(8f, 8f);

        for (int x = -4; x <= 20; x++)
            for (int z = -4; z <= 20; z++)
                w.placeBlock(x, FLOOR, z, World.STONE);

        // Hrac daleko od vsech polozek - fyzika se testuje bez sberu.
        Player far = playerAt(30.5f, 150f, 30.5f);
        Inventory nothing = new Inventory();

        // ---------- gravitace a dopad ----------
        DroppedItems drops = new DroppedItems();
        DroppedItem fall = drops.dropFromBlock(8, FLOOR + 6, 8, ItemStack.of(World.DIRT, 1));

        check("vytezeny blok vypadne ze stredu sve bunky",
                near(fall.x, 8.5f, 1e-4f) && near(fall.z, 8.5f, 1e-4f)
                        && fall.y > FLOOR + 6 && fall.y + DroppedItem.SIZE < FLOOR + 7,
                fall.x + " " + fall.y + " " + fall.z);

        run(drops, w, far, nothing, 180);
        check("polozka spadne a zastavi se na plosine", near(fall.y, TOP, 0.01f), "y=" + fall.y);
        check("po dopadu je onGround", fall.onGround, "");

        float restY = fall.y;
        run(drops, w, far, nothing, 600);
        check("lezeni je stabilni (nepropada se ani necuka)", near(fall.y, restY, 1e-4f),
                restY + " -> " + fall.y);
        check("svisly pad nehne s x ani z",
                near(fall.x, 8.5f, 1e-4f) && near(fall.z, 8.5f, 1e-4f), fall.x + " " + fall.z);

        // ---------- blok polozeny primo na polozku ----------
        // Pojistka proti uvezneni: dopad sam o sobe by polozku vracel na
        // horni hranu bunky POD novym blokem, tedy dovnitr nej.
        w.placeBlock(8, FLOOR + 1, 8, World.STONE);
        w.placeBlock(8, FLOOR + 2, 8, World.STONE);
        run(drops, w, far, nothing, 30);
        check("polozka, na kterou se polozi bloky, se vytlaci nad ne",
                near(fall.y, TOP + 2, 0.01f) && fall.onGround, "y=" + fall.y);
        w.breakBlock(8, FLOOR + 1, 8);
        w.breakBlock(8, FLOOR + 2, 8);
        run(drops, w, far, nothing, 120);
        check("a po jejich rozbiti zase spadne na plosinu", near(fall.y, TOP, 0.01f), "y=" + fall.y);

        // ---------- tunelovani ----------
        DroppedItem fast = drops.dropFromBlock(10, FLOOR + 40, 10, ItemStack.of(World.DIRT, 1));
        fast.vy = -40f;
        for (int i = 0; i < 100; i++) drops.update(w, far, nothing, 5f);   // dt si orizne sama
        check("velke dt neprotuneluje jednou vrstvou bloku", near(fast.y, TOP, 0.01f), "y=" + fast.y);

        // ---------- zed ----------
        for (int z = -4; z <= 20; z++)
            for (int y = FLOOR + 1; y <= FLOOR + 3; y++)
                w.placeBlock(14, y, z, World.STONE);

        DroppedItem slide = drops.dropFromBlock(8, FLOOR + 1, 12, ItemStack.of(World.DIRT, 1));
        slide.vx = 30f;
        run(drops, w, far, nothing, 120);
        check("polozka se zastavi o zed, ne v ni",
                slide.x + DroppedItem.SIZE / 2f <= 14f + 1e-3f, "prava hrana=" + (slide.x + DroppedItem.SIZE / 2f));
        check("a zastavi se az u zdi, ne pred ni", slide.x > 13.5f, "x=" + slide.x);

        // ---------- treni o zem ----------
        DroppedItem skid = drops.dropFromBlock(2, FLOOR + 1, 2, ItemStack.of(World.DIRT, 1));
        run(drops, w, far, nothing, 60);
        skid.vx = 3f;
        run(drops, w, far, nothing, 60);
        check("polozka na zemi se rychle zastavi", Math.abs(skid.vx) < 0.01f && skid.x - 2.5f < 0.5f,
                "vx=" + skid.vx + " ujela " + (skid.x - 2.5f));

        // ---------- voda ----------
        for (int y = FLOOR + 1; y <= FLOOR + 10; y++) w.placeBlock(0, y, 0, World.WATER);

        DroppedItems water = new DroppedItems();
        DroppedItem wet = water.dropFromBlock(0, FLOOR + 9, 0, ItemStack.of(World.SAND, 1));
        DroppedItem dry = water.dropFromBlock(4, FLOOR + 9, 0, ItemStack.of(World.SAND, 1));
        wet.vy = 0f;
        dry.vy = 0f;
        float startY = wet.y;

        run(water, w, far, nothing, 45);
        float wetFall = startY - wet.y;
        float dryFall = startY - dry.y;
        check("ve vode polozka klesa mnohem pomaleji nez ve vzduchu", wetFall > 0 && wetFall < dryFall * 0.5f,
                String.format("voda %.2f, vzduch %.2f bloku za 0,75 s", wetFall, dryFall));

        run(water, w, far, nothing, 1200);
        check("a nakonec lezi na dne", near(wet.y, TOP, 0.01f), "y=" + wet.y);

        // ---------- vyhozeni z ruky ----------
        Player thrower = playerAt(4.5f, TOP, 4.5f);
        Inventory throwerInv = new Inventory();
        DroppedItems thrownDrops = new DroppedItems();

        DroppedItem thrown = thrownDrops.throwFrom(thrower, new float[]{1f, 0f, 0f},
                ItemStack.of(World.SAND, 1));
        check("vyhozena polozka se objevi u hrace, kousek pod ocima",
                near(thrown.x, thrower.x, 1e-4f) && near(thrown.y, thrower.eyeY() - 0.3f, 1e-3f),
                thrown.x + " " + thrown.y);

        run(thrownDrops, w, thrower, throwerInv, 180);
        check("vyleti pred hrace ve smeru pohledu",
                thrown.x > thrower.x + 1.5f && near(thrown.z, thrower.z, 1e-3f),
                "x=" + thrown.x + " z=" + thrown.z);
        check("a dopadne na zem", near(thrown.y, TOP, 0.01f) && thrown.onGround, "y=" + thrown.y);
        check("polozka mimo dosah se nesebere", thrownDrops.size() == 1 && throwerInv.isEmpty(), "");
        check("prazdna ruka nic nevyhodi",
                thrownDrops.throwFrom(thrower, new float[]{1f, 0f, 0f}, ItemStack.EMPTY) == null
                        && thrownDrops.size() == 1, "");

        // ---------- zpozdeni sberu ----------
        // Pod sebe: polozka dopadne hraci k nohum, tedy do dosahu. Nesebere
        // se jen diky zpozdeni.
        Player stand = playerAt(12.5f, TOP, 4.5f);
        Inventory bag = new Inventory();
        DroppedItems down = new DroppedItems();
        down.throwFrom(stand, new float[]{0f, -1f, 0f}, ItemStack.of(World.DIRT, 1));

        run(down, w, stand, bag, 60);
        check("vyhozena polozka se prvni 2 s nesebere, i kdyz lezi u nohou",
                down.size() == 1 && bag.isEmpty(), "polozek " + down.size());

        run(down, w, stand, bag, 90);
        check("po zpozdeni ji hrac sebere", down.size() == 0 && bag.countOf(World.DIRT) == 1,
                "polozek " + down.size() + ", hliny " + bag.countOf(World.DIRT));

        // ---------- dosah ----------
        float edge = stand.x + Player.WIDTH / 2f + DroppedItems.PICKUP_REACH_XZ + DroppedItem.SIZE / 2f;
        DroppedItem inside = new DroppedItem(ItemStack.of(World.DIRT, 1), edge - 0.01f, TOP, stand.z, 0, 0, 0, 0, 0);
        DroppedItem outside = new DroppedItem(ItemStack.of(World.DIRT, 1), edge + 0.01f, TOP, stand.z, 0, 0, 0, 0, 0);
        check("dosah sberu je blok od hitboxu hrace",
                DroppedItems.reaches(stand, inside) && !DroppedItems.reaches(stand, outside), "hrana x=" + edge);

        DroppedItem above = new DroppedItem(ItemStack.of(World.DIRT, 1), stand.x, stand.y + Player.HEIGHT + 0.6f,
                stand.z, 0, 0, 0, 0, 0);
        check("nad hlavou dosah konci po pul bloku", !DroppedItems.reaches(stand, above), "");

        // ---------- slevani pri sberu ----------
        Inventory merge = new Inventory();
        merge.set(5, ItemStack.of(World.DIRT, 10));
        DroppedItems mergeDrops = new DroppedItems();
        mergeDrops.dropFromBlock(12, FLOOR + 1, 4, ItemStack.of(World.DIRT, 3));

        run(mergeDrops, w, stand, merge, 60);
        check("sebrana polozka se slije s existujici hromadkou",
                merge.get(5).count() == 13 && InventoryTest.usedSlots(merge) == 1 && mergeDrops.size() == 0,
                merge.get(5) + ", obsazenych slotu " + InventoryTest.usedSlots(merge));

        // ---------- plny inventar ----------
        Inventory full = InventoryTest.filled(World.STONE);
        DroppedItems fullDrops = new DroppedItems();
        DroppedItem lying = fullDrops.dropFromBlock(12, FLOOR + 1, 4, ItemStack.of(World.DIRT, 1));

        run(fullDrops, w, stand, full, 120);
        check("pri plnem inventari polozka zustane lezet",
                fullDrops.size() == 1 && lying.stack().count() == 1 && full.countOf(World.DIRT) == 0, "");
        check("a inventar se nezmeni", full.countOf(World.STONE) == Inventory.SIZE * ItemStack.MAX_COUNT, "");

        // ---------- skoro plny: sebere se, co se vejde ----------
        Inventory almost = InventoryTest.filled(World.STONE);
        almost.set(7, ItemStack.of(World.DIRT, 62));
        DroppedItems partial = new DroppedItems();
        DroppedItem five = partial.dropFromBlock(12, FLOOR + 1, 4, ItemStack.of(World.DIRT, 5));

        run(partial, w, stand, almost, 60);
        check("co se vejde, sebere se; zbytek zustane lezet",
                almost.get(7).count() == 64 && partial.size() == 1 && five.stack().count() == 3,
                almost.get(7) + ", na zemi " + five.stack());

        // ---------- zanik po LIFETIME ----------
        DroppedItems old = new DroppedItems();
        old.dropFromBlock(2, FLOOR + 1, 12, ItemStack.of(World.DIRT, 1));
        int frames = (int) Math.ceil(DroppedItems.LIFETIME / 0.05f);
        for (int i = 0; i < frames - 20; i++) old.update(w, far, nothing, 0.05f);
        check("pred LIFETIME polozka jeste lezi", old.size() == 1, "");
        for (int i = 0; i < 40; i++) old.update(w, far, nothing, 0.05f);
        check("po LIFETIME zmizi", old.size() == 0, "");

        // ---------- casovace skutecnym casem i pod 20 FPS (INV-8) ----------
        // Strop dt (0,05 s) patri jen fyzice. Driv se pocital i do stari, takze
        // pri 10 FPS byla polozka po 10 s "stara" jen 5 s.
        DroppedItems slow = new DroppedItems();
        DroppedItem slowItem = slow.dropFromBlock(2, FLOOR + 1, 12, ItemStack.of(World.DIRT, 1));
        for (int i = 0; i < 100; i++) slow.update(w, far, nothing, 0.1f);   // 10 s pri 10 FPS
        check("pri 10 FPS bezi stari skutecnym casem", Math.abs(slowItem.age() - 10f) < 0.01f,
                "age=" + slowItem.age());
        check("a zpozdeni sberu po nem davno vyprselo", slowItem.canBePickedUp(), "");

        // ---------- mesh ----------
        DroppedItems visible = new DroppedItems();
        DroppedItem cube = visible.dropFromBlock(4, FLOOR + 1, 12, ItemStack.of(World.DIRT, 1));
        visible.dropFromBlock(6, FLOOR + 1, 12, ItemStack.of(World.TORCH, 1));
        run(visible, w, far, nothing, 60);

        DroppedItemMesh mesh = new DroppedItemMesh();
        float camX = 4.5f, camY = TOP + 1.62f, camZ = 8.5f;
        mesh.build(visible.items(), w, camX, camY, camZ, 64f);
        check("kazda polozka je jeden kvadr, tedy 36 vrcholu (i pochoden)",
                mesh.vertexCount() == 2 * 36, "" + mesh.vertexCount());

        boolean relative = true;
        float[] v = mesh.vertices();
        for (int i = 0; i < 36; i++) {
            int o = i * DroppedItemMesh.FLOATS_PER_VERTEX;
            relative &= Math.abs(v[o] - (cube.x - camX)) < 0.2f
                    && Math.abs(v[o + 2] - (cube.z - camZ)) < 0.2f
                    && v[o + 1] - (cube.y - camY) > -1e-3f
                    && v[o + 1] - (cube.y - camY) < 0.5f;
        }
        check("vrcholy lezi kolem polozky, relativne ke kamere", relative, "");

        // Svetlo se bere z bunky polozky. Pod sirym nebem je to plne slunce -
        // i primo na plosine, tedy v sekci, kterou teprve polozene bloky
        // zalozily. Driv tam zustala tma (viz LightTest 8b), proto polozka
        // visela o sekci vys.
        DroppedItem sunny = new DroppedItem(ItemStack.of(World.DIRT, 1),
                4.5f, TOP, 12.5f, 0, 0, 0, 0, 0);
        mesh.build(java.util.List.of(sunny), w, camX, camY, camZ, 64f);
        check("polozka pod sirym nebem ma plne slunce (horni stena bez ztmaveni)",
                near(maxChannel(mesh, 5), 1f, 1e-4f), "" + maxChannel(mesh, 5));

        // ...a u pochodne blokove svetlo te bunky.
        w.placeBlock(2, FLOOR + 1, 16, World.TORCH);
        w.updateBlocking(8f, 8f);
        DroppedItem lit = new DroppedItem(ItemStack.of(World.DIRT, 1),
                3.5f, TOP, 16.5f, 0, 0, 0, 0, 0);
        mesh.build(java.util.List.of(lit), w, camX, camY, camZ, 64f);
        int torchLight = w.blockLightAt(3, FLOOR + 1, 16);
        check("u pochodne dostane blokove svetlo sve bunky",
                torchLight > 0 && near(maxChannel(mesh, 6), torchLight / (float) LightEngine.MAX_LIGHT, 1e-4f),
                maxChannel(mesh, 6) + " vs " + torchLight + "/15");

        mesh.build(visible.items(), w, camX + 100f, camY, camZ, 64f);
        check("polozky dal nez dohled se nestavi", mesh.isEmpty(), "" + mesh.vertexCount());

        // Daleko od pocatku musi vrcholy zustat mala cisla - jinak by se
        // kostka trasla stejne, jako by se trasl teren (viz Shaders).
        DroppedItem distant = new DroppedItem(ItemStack.of(World.STONE, 1),
                100000.25f, 70f, 100000.25f, 0, 0, 0, 0, 0);
        mesh.build(java.util.List.of(distant), w, 100000f, 70f, 100000f, 64f);
        boolean small = true;
        float[] dv = mesh.vertices();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int o = i * DroppedItemMesh.FLOATS_PER_VERTEX;
            small &= Math.abs(dv[o]) < 0.5f && Math.abs(dv[o + 2]) < 0.5f;
        }
        check("i daleko od pocatku jsou vrcholy mala cisla", small && mesh.vertexCount() == 36, "");

        // ---------- zahozeny sloupec ----------
        DroppedItems unload = new DroppedItems();
        unload.dropFromBlock(8, FLOOR + 1, 8, ItemStack.of(World.DIRT, 1));
        run(unload, w, far, nothing, 30);
        check("v nactenem sloupci polozka lezi", unload.size() == 1, "");

        w.updateBlocking(8f + 16 * 10, 8f);
        check("sloupec pod polozkou se opravdu zahodil", !w.isColumnLoaded(8, 8), "");
        run(unload, w, far, nothing, 1);
        check("polozka v zahozenem sloupci zmizi - neuklada se", unload.size() == 0, "");

        w.shutdown();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
