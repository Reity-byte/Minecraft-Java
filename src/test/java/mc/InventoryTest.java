package mc;

/**
 * Overuje inventar, hromadky a crafting.
 *
 * Vsechno krome kresleni: Container, ItemStack i Recipes jsou bez GL. Presuny
 * mysi resi ContainerScreen, ktery uz na GL sahá pri kresleni - jeho logika
 * je ale oddelena od vykreslovani, takze i klikani jde otestovat.
 */
public class InventoryTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        // ---------- hromadky ----------
        check("prazdna hromadka se pozna", ItemStack.EMPTY.isEmpty(), "");
        check("nulovy pocet je prazdno", ItemStack.of(World.STONE, 0).isEmpty(), "");
        check("vzduch je prazdno", ItemStack.of(World.AIR, 5).isEmpty(), "");
        check("odebrani posledniho kusu udela prazdno",
                ItemStack.of(World.STONE, 1).plus(-1).isEmpty(), "");

        ItemStack full = ItemStack.of(World.DIRT, ItemStack.MAX_COUNT);
        check("plna hromadka nema misto", full.space() == 0, "" + full.space());
        check("hromadka o 10 ma misto na 54", ItemStack.of(World.DIRT, 10).space() == 54, "");

        // ---------- slevani pri sberu ----------
        Inventory inv = new Inventory();
        check("novy inventar je prazdny", inv.isEmpty(), "");

        for (int i = 0; i < 5; i++) inv.add(ItemStack.of(World.STONE, 1));

        check("pet kamenu se slilo do jednoho slotu", inv.countOf(World.STONE) == 5, "");
        check("obsazeny je jen jeden slot", usedSlots(inv) == 1, "" + usedSlots(inv));

        // Doplnovani rozdelanych hromadek ma prednost pred zabranim prazdneho slotu.
        Inventory partial = new Inventory();
        partial.set(0, ItemStack.of(World.DIRT, 60));
        partial.set(1, ItemStack.of(World.DIRT, 60));
        ItemStack leftover = partial.add(ItemStack.of(World.DIRT, 10));

        check("dolije se do rozdelanych hromadek nejdriv",
                partial.get(0).count() == 64 && partial.get(1).count() == 64,
                partial.get(0) + " / " + partial.get(1));
        check("zbytek jde do prazdneho slotu",
                partial.get(2).count() == 2 && leftover.isEmpty(),
                partial.get(2) + " zbylo " + leftover);

        // ---------- preteceni ----------
        Container tiny = new Container(1);
        ItemStack rest = tiny.add(ItemStack.of(World.SAND, 100));
        check("do jednoho slotu se vejde jen 64", tiny.get(0).count() == 64, "");
        check("co se nevejde, vrati se volajicimu",
                rest.count() == 36 && rest.block() == World.SAND, rest.toString());

        // ---------- recepty ----------
        Container grid2 = new Container(4);
        check("prazdna mrizka nic nedava", Recipes.match(grid2, 2, 2).isEmpty(), "");

        for (int i = 0; i < 4; i++) grid2.set(i, ItemStack.of(World.PLANKS, 1));
        ItemStack table = Recipes.match(grid2, 2, 2);
        check("4 prkna ve ctverci = crafting table",
                table.block() == World.CRAFTING_TABLE && table.count() == 1, table.toString());

        // Neuplny ctverec nesmi projit.
        grid2.set(3, ItemStack.EMPTY);
        check("tri prkna uz crafting table nedaji", Recipes.match(grid2, 2, 2).isEmpty(), "");

        grid2.set(3, ItemStack.of(World.STONE, 1));
        check("prkna s kamenem navic taky ne", Recipes.match(grid2, 2, 2).isEmpty(), "");

        for (int i = 0; i < 4; i++) grid2.set(i, ItemStack.of(World.STONE, 1));
        ItemStack bricks = Recipes.match(grid2, 2, 2);
        check("4 kameny = 4 cihly",
                bricks.block() == World.STONE_BRICKS && bricks.count() == 4, bricks.toString());

        // Bezetvarovy recept: staci jeden kus kdekoliv.
        Container shapeless = new Container(4);
        shapeless.set(2, ItemStack.of(World.GRASS, 1));
        ItemStack dirt = Recipes.match(shapeless, 2, 2);
        check("trava se olopne na hlinu", dirt.block() == World.DIRT, dirt.toString());

        // ---------- recept 2x2 funguje i ve velke mrizce ----------
        // Tohle je duvod, proc si recept nese svou velikost: kdyby se porovnaval
        // s celou mrizkou, musel by se kazdy maly recept psat pro 3x3 znovu.
        Container grid3 = new Container(9);
        int[] corners = {0, 1, 3, 4};   // levy horni ctverec 3x3 mrizky
        for (int i : corners) grid3.set(i, ItemStack.of(World.PLANKS, 1));
        check("recept 2x2 sedne do rohu mrizky 3x3",
                Recipes.match(grid3, 3, 3).block() == World.CRAFTING_TABLE, "");

        grid3.clear();
        int[] middle = {4, 5, 7, 8};    // posunuty do praveho dolniho rohu
        for (int i : middle) grid3.set(i, ItemStack.of(World.PLANKS, 1));
        check("a stejne tak do jineho rohu",
                Recipes.match(grid3, 3, 3).block() == World.CRAFTING_TABLE, "");

        // Recept 3x3 se do male mrizky nevejde - kvuli tomu crafting table existuje.
        Container big = new Container(9);
        for (int i = 0; i < 9; i++) big.set(i, ItemStack.of(World.STONE_BRICKS, 1));
        check("9 cihel ve velke mrizce neco da",
                !Recipes.match(big, 3, 3).isEmpty(), "");

        // 4 cihly v male mrizce daji stul (dosazitelna cesta), 9 ve velke dva.
        Container small = new Container(4);
        for (int i = 0; i < 4; i++) small.set(i, ItemStack.of(World.STONE_BRICKS, 1));
        check("4 cihly v male mrizce daji crafting table",
                Recipes.match(small, 2, 2).block() == World.CRAFTING_TABLE, "");
        check("9 cihel ve velke mrizce da dva stoly",
                Recipes.match(big, 3, 3).count() == 2, "" + Recipes.match(big, 3, 3).count());

        // ---------- cely retez je dosazitelny z kamene ----------
        // Prkna se v terenu negeneruji, takze bez teto cesty by se prvni
        // crafting table nedala vyrobit vubec.
        Container chain = new Container(4);
        for (int i = 0; i < 4; i++) chain.set(i, ItemStack.of(World.STONE, 1));
        ItemStack step1 = Recipes.match(chain, 2, 2);
        check("kamen -> cihly", step1.block() == World.STONE_BRICKS && step1.count() == 4, "");

        chain.clear();
        for (int i = 0; i < 4; i++) chain.set(i, step1.withCount(1));
        check("cihly -> crafting table",
                Recipes.match(chain, 2, 2).block() == World.CRAFTING_TABLE, "");

        // ---------- spotreba surovin ----------
        Container consume = new Container(4);
        consume.set(0, ItemStack.of(World.PLANKS, 3));
        consume.set(1, ItemStack.of(World.PLANKS, 1));
        Recipes.consume(consume);
        check("spotrebuje se po jednom z kazdeho slotu",
                consume.get(0).count() == 2 && consume.get(1).isEmpty(),
                consume.get(0) + " / " + consume.get(1));

        // ---------- obrazovka: braní a pokládání ----------
        Inventory backpack = new Inventory();
        backpack.set(0, ItemStack.of(World.STONE, 10));

        Container craft = new Container(4);
        Container result = new Container(1);
        ContainerScreen screen = ContainerScreen.playerInventory(backpack, craft, result);

        int scale = Gui.scale(1024, 768);
        double[] slot0 = hotbarSlotCenter(0, 1024, 768, scale);

        // levy klik vezme celou hromadku
        screen.click(slot0[0], slot0[1], 1024, 768, true, backpack);
        check("levy klik vezme celou hromadku",
                screen.held().count() == 10 && backpack.get(0).isEmpty(),
                screen.held().toString());

        // a druhy ji polozi zpatky
        screen.click(slot0[0], slot0[1], 1024, 768, true, backpack);
        check("dalsi klik ji polozi zpatky",
                backpack.get(0).count() == 10 && screen.held().isEmpty(), "");

        // pravy klik bere polovinu, u licheho tu vetsi
        backpack.set(0, ItemStack.of(World.STONE, 7));
        screen.click(slot0[0], slot0[1], 1024, 768, false, backpack);
        check("pravy klik vezme vetsi polovinu",
                screen.held().count() == 4 && backpack.get(0).count() == 3,
                screen.held() + " / " + backpack.get(0));

        // pravym se pokládá po jednom
        double[] slot1 = hotbarSlotCenter(1, 1024, 768, scale);
        screen.click(slot1[0], slot1[1], 1024, 768, false, backpack);
        check("pravy klik polozi jeden kus",
                backpack.get(1).count() == 1 && screen.held().count() == 3,
                backpack.get(1) + " / " + screen.held());

        // ---------- zavreni obrazovky nic neztrati ----------
        Inventory keeper = new Inventory();
        Container keeperCraft = new Container(4);
        Container keeperResult = new Container(1);
        ContainerScreen closing =
                ContainerScreen.playerInventory(keeper, keeperCraft, keeperResult);

        keeperCraft.set(0, ItemStack.of(World.PLANKS, 2));
        keeperCraft.set(1, ItemStack.of(World.DIRT, 5));
        closing.returnItems(keeper);

        check("crafting mrizka se pri zavreni vysype do batohu",
                keeper.countOf(World.PLANKS) == 2 && keeper.countOf(World.DIRT) == 5,
                keeper.countOf(World.PLANKS) + " prken, " + keeper.countOf(World.DIRT) + " hliny");
        check("mrizka zustane prazdna", keeperCraft.isEmpty(), "");

        // Zbytek z kurzoru, ktery se do plneho inventare nevejde, se driv
        // tise zahodil. Ted ho obrazovka vrati volajicimu a Main ho vyhodi na zem.
        Inventory stuffed = filled(World.DIRT);
        Container stuffedCraft = new Container(4);
        ContainerScreen stuffedScreen =
                ContainerScreen.playerInventory(stuffed, stuffedCraft, new Container(1));
        stuffedCraft.set(0, ItemStack.of(World.STONE, 5));
        stuffedScreen.click(craft2(0)[0], craft2(0)[1], W, H, true, stuffed);
        ItemStack overflow = stuffedScreen.returnItems(stuffed);
        check("zbytek z kurzoru, ktery se nevejde, se vrati volajicimu",
                overflow.block() == World.STONE && overflow.count() == 5
                        && stuffedScreen.held().isEmpty(), overflow.toString());

        takeAndInsert();
        shiftClick();
        dragging();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ==================================================================
    // shift-klik a tazeni
    // ==================================================================

    static final int W = 1024, H = 768;
    static final int SCALE = Gui.scale(W, H);

    /** Container.take a Container.insert, na kterych stoji vyhazovani a shift-klik. */
    static void takeAndInsert() {
        Container c = new Container(2);
        c.set(0, ItemStack.of(World.SAND, 10));

        ItemStack one = c.take(0, 1);
        check("take(1) vezme jeden kus", one.count() == 1 && c.get(0).count() == 9,
                one + " / " + c.get(0));

        ItemStack rest = c.take(0, ItemStack.MAX_COUNT);
        check("take(64) vezme, co ve slotu je, a slot vyprazdni",
                rest.count() == 9 && c.get(0).isEmpty(), rest + " / " + c.get(0));
        check("take z prazdneho slotu nic neda", c.take(1, 1).isEmpty(), "");

        Inventory range = new Inventory();
        ItemStack left = range.insert(ItemStack.of(World.STONE, 70), 9, 10);
        check("insert plni jen zadany rozsah slotu",
                range.get(9).count() == 64 && left.count() == 6 && usedSlots(range) == 1,
                range.get(9) + " zbylo " + left);
    }

    static void shiftClick() {
        // ---------- prazdny cil ----------
        Inventory inv = new Inventory();
        ContainerScreen screen =
                ContainerScreen.playerInventory(inv, new Container(4), new Container(1));

        inv.set(0, ItemStack.of(World.STONE, 10));
        shift(screen, hotbar(0), inv);
        check("shift-klik z hotbaru presune hromadku do batohu",
                inv.get(0).isEmpty() && inv.get(9).block() == World.STONE && inv.get(9).count() == 10,
                inv.get(0) + " / " + inv.get(9));
        check("shift-klik nic nebere do ruky", screen.held().isEmpty(), screen.held().toString());

        // ---------- dolevani a preteceni ----------
        // Z batohu do hotbaru: nejdriv se doleje rozdelana hromadka, zbytek
        // pretece do prvniho prazdneho slotu hotbaru.
        inv.set(3, ItemStack.of(World.STONE, 60));
        inv.set(20, ItemStack.of(World.STONE, 10));
        shift(screen, backpack(20), inv);
        check("shift-klik z batohu nejdriv doleje rozdelanou hromadku v hotbaru",
                inv.get(3).count() == 64, inv.get(3).toString());
        check("preteceni skonci v prvnim prazdnem slotu hotbaru",
                inv.get(0).block() == World.STONE && inv.get(0).count() == 6 && inv.get(20).isEmpty(),
                inv.get(0) + " / " + inv.get(20));
        check("pri presunu se zadny kus neztrati ani nepribude",
                inv.countOf(World.STONE) == 80, "" + inv.countOf(World.STONE));

        // ---------- plny cil ----------
        Inventory full = new Inventory();
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) full.set(i, ItemStack.of(World.DIRT, 64));
        full.set(15, ItemStack.of(World.STONE, 5));
        ContainerScreen fullScreen =
                ContainerScreen.playerInventory(full, new Container(4), new Container(1));

        shift(fullScreen, backpack(15), full);
        check("do plneho hotbaru se nic nepresune",
                full.get(15).count() == 5 && full.countOf(World.DIRT) == 9 * 64,
                full.get(15).toString());

        // ---------- cil se vejde jen z casti ----------
        full.set(4, ItemStack.of(World.STONE, 60));
        full.set(20, ItemStack.of(World.STONE, 10));
        shift(fullScreen, backpack(20), full);
        check("presune se, co se vejde, zbytek zustane ve vychozim slotu",
                full.get(4).count() == 64 && full.get(20).count() == 6,
                full.get(4) + " / " + full.get(20));

        // ---------- crafting mrizka a vystup ----------
        Inventory crafter = new Inventory();
        Container grid = new Container(4);
        Container result = new Container(1);
        ContainerScreen craftScreen = ContainerScreen.playerInventory(crafter, grid, result);

        for (int i = 0; i < 4; i++) grid.set(i, ItemStack.of(World.PLANKS, 1));
        craftScreen.refreshResult();

        shift(craftScreen, craft2(0), crafter);
        check("shift-klik z crafting mrizky vrati hromadku do inventare",
                grid.get(0).isEmpty() && crafter.countOf(World.PLANKS) == 1, "");
        check("a vysledek se hned prepocita", result.get(0).isEmpty(), result.get(0).toString());

        grid.set(0, ItemStack.of(World.PLANKS, 1));
        craftScreen.refreshResult();
        shift(craftScreen, result2(), crafter);
        check("shift-klik na vysledek funguje jako obycejny klik",
                craftScreen.held().block() == World.CRAFTING_TABLE && grid.isEmpty(),
                craftScreen.held() + ", mrizka prazdna: " + grid.isEmpty());

        // ---------- s plnou rukou je to obycejny klik (jako v Minecraftu) ----------
        crafter.clear();
        ContainerScreen handScreen =
                ContainerScreen.playerInventory(crafter, new Container(4), new Container(1));
        crafter.set(1, ItemStack.of(World.SAND, 7));
        handScreen.click(hotbar(1)[0], hotbar(1)[1], W, H, true, crafter);
        shift(handScreen, hotbar(5), crafter);
        check("shift-klik s necim v ruce hromadku jen polozi",
                crafter.get(5).count() == 7 && handScreen.held().isEmpty() && crafter.get(9).isEmpty(),
                crafter.get(5) + " / " + handScreen.held());

        // ---------- crafting table: ta sama pravidla ----------
        Inventory tableInv = new Inventory();
        Container tableGrid = new Container(9);
        ContainerScreen table =
                ContainerScreen.craftingTable(tableInv, tableGrid, new Container(1));

        tableInv.set(2, ItemStack.of(World.LOG, 7));
        shift(table, hotbar(2), tableInv);
        check("shift-klik funguje i na crafting table (hotbar -> batoh)",
                tableInv.get(2).isEmpty() && tableInv.get(9).count() == 7, tableInv.get(9).toString());

        shift(table, backpack(9), tableInv);
        check("a zpatky (batoh -> hotbar)",
                tableInv.get(0).count() == 7 && tableInv.get(9).isEmpty(), tableInv.get(0).toString());

        tableGrid.set(4, ItemStack.of(World.STONE, 3));
        shift(table, craft3(4), tableInv);
        check("i z mrizky 3x3 zpet do inventare",
                tableGrid.isEmpty() && tableInv.countOf(World.STONE) == 3, "");
    }

    static void dragging() {
        Inventory inv = new Inventory();
        ContainerScreen screen =
                ContainerScreen.playerInventory(inv, new Container(4), new Container(1));

        // ---------- levym rovnomerne ----------
        inv.set(0, ItemStack.of(World.STONE, 10));
        take(screen, hotbar(0), inv);

        screen.press(backpack(9)[0], backpack(9)[1], W, H, true, false, inv);
        screen.drag(backpack(10)[0], backpack(10)[1], W, H);
        screen.drag(backpack(11)[0], backpack(11)[1], W, H);
        check("behem tazeni se nic nepresune",
                inv.get(9).isEmpty() && inv.get(10).isEmpty() && screen.held().count() == 10, "");

        screen.release(backpack(11)[0], backpack(11)[1], W, H, true, inv);
        check("levym tazenim pres 3 sloty dostane kazdy 10/3 = 3",
                inv.get(9).count() == 3 && inv.get(10).count() == 3 && inv.get(11).count() == 3,
                inv.get(9) + " / " + inv.get(10) + " / " + inv.get(11));
        check("zbytek po deleni zustane v ruce", screen.held().count() == 1, screen.held().toString());

        // ---------- rozdelana hromadka dostane jen kolik se vejde ----------
        inv.clear();
        screen = ContainerScreen.playerInventory(inv, new Container(4), new Container(1));
        inv.set(0, ItemStack.of(World.STONE, 10));
        inv.set(12, ItemStack.of(World.STONE, 62));
        take(screen, hotbar(0), inv);

        drag(screen, true, inv, backpack(9), backpack(12));
        check("do rozdelane hromadky se doleje jen do 64",
                inv.get(9).count() == 5 && inv.get(12).count() == 64,
                inv.get(9) + " / " + inv.get(12));
        check("co se nevejde, zustane v ruce", screen.held().count() == 3, screen.held().toString());

        // ---------- slot s jinym blokem se preskoci ----------
        inv.set(13, ItemStack.of(World.DIRT, 4));
        drag(screen, true, inv, backpack(13), backpack(14), backpack(15));
        check("slot s jinym blokem se do tazeni nezaradi",
                inv.get(13).block() == World.DIRT && inv.get(13).count() == 4
                        && inv.get(14).count() == 1 && inv.get(15).count() == 1,
                inv.get(13) + " / " + inv.get(14) + " / " + inv.get(15));
        check("a zbytek po deleni mezi dva sloty zustane v ruce",
                screen.held().count() == 1, screen.held().toString());

        // ---------- pravym po jednom ----------
        inv.clear();
        screen = ContainerScreen.playerInventory(inv, new Container(4), new Container(1));
        inv.set(0, ItemStack.of(World.SAND, 5));
        take(screen, hotbar(0), inv);

        drag(screen, false, inv, backpack(9), backpack(10), backpack(11));
        check("pravym tazenim dostane kazdy slot jeden kus",
                inv.get(9).count() == 1 && inv.get(10).count() == 1 && inv.get(11).count() == 1,
                inv.get(9) + " / " + inv.get(10) + " / " + inv.get(11));
        check("zbytek zustane v ruce", screen.held().count() == 2, screen.held().toString());

        // Kusu je min nez slotu: dalsi sloty se uz nezaradi.
        drag(screen, false, inv, backpack(18), backpack(19), backpack(20), backpack(21));
        check("slotu nemuze byt vic nez kusu v ruce",
                inv.get(18).count() == 1 && inv.get(19).count() == 1
                        && inv.get(20).isEmpty() && inv.get(21).isEmpty() && screen.held().isEmpty(),
                inv.get(20) + " / " + inv.get(21) + " / " + screen.held());

        // ---------- tazeni po jedinem slotu je obycejny klik ----------
        inv.set(0, ItemStack.of(World.PLANKS, 8));
        take(screen, hotbar(0), inv);
        drag(screen, true, inv, backpack(30));
        check("zmacknout a pustit na jednom slotu polozi celou hromadku",
                inv.get(30).count() == 8 && screen.held().isEmpty(), inv.get(30).toString());

        // ---------- druhe tlacitko tazeni zrusi ----------
        take(screen, backpack(30), inv);
        screen.press(backpack(31)[0], backpack(31)[1], W, H, true, false, inv);
        screen.drag(backpack(32)[0], backpack(32)[1], W, H);
        screen.press(backpack(32)[0], backpack(32)[1], W, H, false, false, inv);
        screen.release(backpack(32)[0], backpack(32)[1], W, H, true, inv);
        screen.release(backpack(32)[0], backpack(32)[1], W, H, false, inv);
        check("druhe tlacitko behem tazeni tazeni zrusi a nic se nepresune",
                inv.get(31).isEmpty() && inv.get(32).isEmpty() && screen.held().count() == 8,
                inv.get(31) + " / " + inv.get(32) + " / " + screen.held());

        // ---------- do crafting mrizky, pres vystupni slot ne ----------
        Inventory crafter = new Inventory();
        Container grid = new Container(4);
        Container result = new Container(1);
        ContainerScreen craft = ContainerScreen.playerInventory(crafter, grid, result);

        crafter.set(0, ItemStack.of(World.PLANKS, 4));
        take(craft, hotbar(0), crafter);
        drag(craft, true, crafter, craft2(0), craft2(1), result2(), craft2(3), craft2(2));

        boolean onePerCell = true;
        for (int i = 0; i < 4; i++) onePerCell &= grid.get(i).count() == 1;
        check("tazenim pres mrizku 2x2 se polozi prkno do kazdeho policka",
                onePerCell && craft.held().isEmpty(), craft.held().toString());
        check("vystupni slot se do tazeni nezaradi a vysledek se prepocita",
                result.get(0).block() == World.CRAFTING_TABLE && result.get(0).count() == 1,
                result.get(0).toString());
    }

    /** Shift + levy klik: zmacknuti se Shiftem a pusteni. */
    static void shift(ContainerScreen screen, double[] at, Inventory inv) {
        screen.press(at[0], at[1], W, H, true, true, inv);
        screen.release(at[0], at[1], W, H, true, inv);
    }

    /** Levy klik s prazdnou rukou - vezme celou hromadku. */
    static void take(ContainerScreen screen, double[] at, Inventory inv) {
        screen.click(at[0], at[1], W, H, true, inv);
    }

    /** Zmackne na prvnim slotu, prejede pres ostatni a pusti na poslednim. */
    static void drag(ContainerScreen screen, boolean left, Inventory inv, double[]... path) {
        screen.press(path[0][0], path[0][1], W, H, left, false, inv);
        for (int i = 1; i < path.length; i++) screen.drag(path[i][0], path[i][1], W, H);
        double[] last = path[path.length - 1];
        screen.release(last[0], last[1], W, H, left, inv);
    }

    /**
     * Stred slotu na obrazovce, v souradnicich mysi (y od horniho okraje).
     * guiX/guiY je roh mrizky v panelu, stejne cisla jako v tovarnich
     * metodach ContainerScreen.
     */
    static double[] slot(int guiX, int guiY, int column, int row) {
        int left = ContainerScreen.panelLeft(W, SCALE);
        int bottom = ContainerScreen.panelBottom(H, SCALE);
        int pitch = ContainerScreen.SLOT_PITCH;

        double x = left + (guiX + column * pitch + pitch / 2) * SCALE;
        double yFromBottom = bottom
                + (ContainerScreen.PANEL_HEIGHT - guiY - row * pitch - pitch + pitch / 2) * SCALE;

        return new double[]{x, H - yFromBottom};
    }

    static double[] hotbar(int i)   { return slot(8, 142, i, 0); }
    static double[] backpack(int i) { int k = i - Inventory.HOTBAR_SIZE; return slot(8, 84, k % 9, k / 9); }
    static double[] craft2(int i)   { return slot(98, 18, i % 2, i / 2); }
    static double[] result2()       { return slot(154, 28, 0, 0); }
    static double[] craft3(int i)   { return slot(30, 17, i % 3, i / 3); }

    /** Inventar plny az po okraj jednim blokem. */
    static Inventory filled(byte block) {
        Inventory inv = new Inventory();
        for (int i = 0; i < Inventory.SIZE; i++) inv.set(i, ItemStack.of(block, ItemStack.MAX_COUNT));
        return inv;
    }

    /** Stred i-teho slotu hotbaru na obrazovce inventare, v souradnicich myshi. */
    static double[] hotbarSlotCenter(int slot, int screenWidth, int screenHeight, int scale) {
        int left = ContainerScreen.panelLeft(screenWidth, scale);
        int bottom = ContainerScreen.panelBottom(screenHeight, scale);

        // rozvrzeni hotbaru v playerInventory(): guiX = 8, guiY = 142
        double x = left + (8 + slot * ContainerScreen.SLOT_PITCH + 9) * scale;
        double yFromBottom = bottom
                + (ContainerScreen.PANEL_HEIGHT - 142 - ContainerScreen.SLOT_PITCH + 9) * scale;

        return new double[]{x, screenHeight - yFromBottom};
    }

    static int usedSlots(Container c) {
        int used = 0;
        for (int i = 0; i < c.size(); i++) if (!c.get(i).isEmpty()) used++;
        return used;
    }
}
