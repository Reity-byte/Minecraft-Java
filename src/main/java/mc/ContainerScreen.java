package mc;

import java.util.ArrayList;
import java.util.List;

/**
 * Obrazovka, na které se pracuje s kontejnery: kreslení slotů a myš.
 *
 * ---------------------------------------------------------------------------
 * TOHLE JE TA DRUHÁ POLOVINA ZNOVUPOUŽITELNOSTI.
 *
 * Obrazovka není "inventář" - je to SEZNAM MŘÍŽEK a jejich pozic v panelu.
 * Inventář hráče je "batoh 9x3 + hotbar 9x1 + crafting 2x2 + výsledek";
 * crafting table je to samé, jen s mřížkou 3x3; truhla by byla "batoh +
 * hotbar + 9x3 truhly". Přesuny, dělení hromádek a shift-klik jsou napsané
 * jednou a platí pro všechny.
 *
 * Rozvržení je v GUI pixelech od LEVÉHO HORNÍHO rohu panelu - to jsou přímo
 * souřadnice z Minecraftu, takže rozměry sedí (panel 176x166, slot 18x18).
 * ---------------------------------------------------------------------------
 */
public class ContainerScreen {

    /** Rozměry panelu v GUI pixelech. Přesně jako v Minecraftu. */
    public static final int PANEL_WIDTH  = 176;
    public static final int PANEL_HEIGHT = 166;

    /** Rozteč slotů. Vnitřek slotu je 16x16, kolem něj 1 px rámečku. */
    public static final int SLOT_PITCH = 18;
    public static final int SLOT_INNER = 16;

    /**
     * Jedna mřížka na obrazovce: kus kontejneru vykreslený jako sloupce x řádky
     * na dané pozici v panelu.
     */
    public record SlotGrid(Container container, int firstSlot, int columns, int rows,
                           int guiX, int guiY, boolean outputOnly) {}

    private final String title;
    private final List<SlotGrid> grids = new ArrayList<>();

    /**
     * Mřížka, ze které se craftí, a slot na výsledek. Můžou být null - truhla
     * ani obyčejný batoh nic nevyrábí.
     */
    private Container craftingGrid;
    private int craftingColumns;
    private int craftingRows;
    private Container resultSlot;

    /**
     * Co drží kurzor. Není to slot žádného kontejneru schválně: v Minecraftu
     * to při zavření obrazovky spadne zpátky do inventáře, a takhle je jasné,
     * kdo je za to zodpovědný.
     */
    private ItemStack held = ItemStack.EMPTY;

    // ------------------------------------------------------------------
    // tažení přes sloty
    //
    // ⚠️ Během tažení se NIC NEPŘESOUVÁ. Sbírají se jen sloty, přes které
    // kurzor přejel, a rozdělí se teprve při puštění tlačítka - jinak by se
    // při každém dalším slotu musely přerozdělovat kusy, které už leží
    // v předchozích. Kreslení mezitím ukazuje náhled (viz shownAt()).
    // ------------------------------------------------------------------

    /** Táhne se s drženou hromádkou? Začíná zmáčknutím, končí puštěním. */
    private boolean dragging = false;

    /** Levým tlačítkem se dělí rovnoměrně, pravým pokládá po jednom kuse. */
    private boolean dragWithLeft = true;

    /** Slot pod kurzorem při zmáčknutí - kam jde obyčejný klik, když se netáhlo. */
    private SlotHit pressed;

    private final List<SlotHit> dragSlots = new ArrayList<>();

    public ContainerScreen(String title)
    {
        this.title = title;
    }

    public ContainerScreen add(Container container, int firstSlot, int columns, int rows,
                               int guiX, int guiY)
    {
        grids.add(new SlotGrid(container, firstSlot, columns, rows, guiX, guiY, false));
        return this;
    }

    /** Výsledkový slot: dá se z něj jen brát, nedá se do něj nic položit. */
    public ContainerScreen addOutput(Container container, int guiX, int guiY)
    {
        grids.add(new SlotGrid(container, 0, 1, 1, guiX, guiY, true));
        resultSlot = container;
        return this;
    }

    public ContainerScreen withCrafting(Container grid, int columns, int rows)
    {
        craftingGrid = grid;
        craftingColumns = columns;
        craftingRows = rows;
        return this;
    }

    public ItemStack held()
    {
        return held;
    }

    public String title()
    {
        return title;
    }

    /** Přepočítá výsledek craftingu. Volat po každé změně mřížky. */
    public void refreshResult()
    {
        if(craftingGrid == null || resultSlot == null)
        {
            return;
        }

        resultSlot.set(0, Recipes.match(craftingGrid, craftingColumns, craftingRows));
    }

    /**
     * Vysype obsah kurzoru a crafting mřížky do zadaného kontejneru.
     * Volat při zavření obrazovky, jinak by se hromádky ztratily.
     *
     * Vrací, co z kurzoru nebylo kam dát - volající to vyhodí na zem. Dřív
     * se ten zbytek tiše zahodil. Co se nevejde z mřížky, zůstává v ní: mřížka
     * je trvalý kontejner a při dalším otevření tam bude.
     */
    public ItemStack returnItems(Container inventory)
    {
        dragging = false;
        dragSlots.clear();

        ItemStack leftover = inventory.add(held);
        held = ItemStack.EMPTY;

        if(craftingGrid == null)
        {
            return leftover;
        }

        for(int i = 0; i < craftingGrid.size(); i++)
        {
            ItemStack stack = craftingGrid.get(i);

            if(!stack.isEmpty())
            {
                craftingGrid.set(i, inventory.add(stack));
            }
        }

        refreshResult();
        return leftover;
    }

    // ------------------------------------------------------------------
    // rozvržení
    // ------------------------------------------------------------------

    public static int panelLeft(int screenWidth, int scale)
    {
        return (int) Gui.snap((screenWidth - PANEL_WIDTH * scale) / 2f, scale);
    }

    public static int panelBottom(int screenHeight, int scale)
    {
        return (int) Gui.snap((screenHeight - PANEL_HEIGHT * scale) / 2f, scale);
    }

    /**
     * Levý DOLNÍ roh slotu v pixelech obrazovky.
     *
     * Rozvržení je zadané od levého horního rohu panelu (souřadnice
     * z Minecraftu), kdežto Renderer2D kreslí od levého dolního - proto se y
     * překlápí přes výšku panelu.
     */
    private static float slotX(SlotGrid grid, int column, int screenWidth, int scale)
    {
        return panelLeft(screenWidth, scale) + (grid.guiX() + column * SLOT_PITCH) * scale;
    }

    private static float slotY(SlotGrid grid, int row, int screenHeight, int scale)
    {
        int fromTop = grid.guiY() + row * SLOT_PITCH;
        return panelBottom(screenHeight, scale)
                + (PANEL_HEIGHT - fromTop - SLOT_PITCH) * scale;
    }

    // ------------------------------------------------------------------
    // myš
    // ------------------------------------------------------------------

    /** Který slot je pod myší? Vrací null, když žádný. mouseY je z GLFW (počátek nahoře). */
    private SlotHit slotAt(double mouseX, double mouseY, int screenWidth, int screenHeight, int scale)
    {
        float y = (float) (screenHeight - mouseY);

        for(SlotGrid grid : grids)
        {
            for(int row = 0; row < grid.rows(); row++)
            {
                for(int column = 0; column < grid.columns(); column++)
                {
                    float x0 = slotX(grid, column, screenWidth, scale);
                    float y0 = slotY(grid, row, screenHeight, scale);
                    float size = SLOT_PITCH * scale;

                    if(mouseX >= x0 && mouseX < x0 + size && y >= y0 && y < y0 + size)
                    {
                        return new SlotHit(grid, grid.firstSlot() + row * grid.columns() + column);
                    }
                }
            }
        }

        return null;
    }

    private record SlotHit(SlotGrid grid, int index) {}

    private SlotHit slotAt(double mouseX, double mouseY, int screenWidth, int screenHeight)
    {
        return slotAt(mouseX, mouseY, screenWidth, screenHeight, Gui.scale(screenWidth, screenHeight));
    }

    private static ItemStack stackAt(SlotHit hit)
    {
        return hit.grid().container().get(hit.index());
    }

    /**
     * Zmáčknutí tlačítka. leftButton = false znamená pravé tlačítko.
     *
     * S prázdnou rukou se jedná hned: bere se (levým celá hromádka, pravým
     * polovina), se Shiftem a levým se hromádka rovnou přesune (quickMove).
     * S něčím v ruce se teprve začíná TAŽENÍ - co se stane, rozhodne až
     * puštění, podle toho, přes kolik slotů se mezitím přejelo.
     *
     * Shift s něčím v ruce se ignoruje, stejně jako v Minecraftu: nešlo by
     * poznat, jestli se má přesouvat slot pod kurzorem, nebo pokládat ruka.
     */
    public void press(double mouseX, double mouseY, int screenWidth, int screenHeight,
                      boolean leftButton, boolean shift, Inventory playerInventory)
    {
        // Druhé tlačítko během tažení tažení zruší a nic se nepřesune.
        if(dragging)
        {
            dragging = false;
            dragSlots.clear();
            pressed = null;
            return;
        }

        SlotHit hit = slotAt(mouseX, mouseY, screenWidth, screenHeight);

        if(held.isEmpty())
        {
            if(hit == null)
            {
                return;
            }

            // Výstupní slot se Shiftem zůstává obyčejným klikem - "vyrob,
            // kolik to jde" je jiná a větší věc než přesun hromádky.
            if(shift && leftButton && !hit.grid().outputOnly())
            {
                quickMove(hit, playerInventory);
            }
            else
            {
                clickSlot(hit, leftButton, playerInventory);
            }

            refreshResult();
            return;
        }

        // Z výstupního slotu se jen bere, tažení by tam nemělo co pokládat.
        if(hit != null && hit.grid().outputOnly())
        {
            clickSlot(hit, leftButton, playerInventory);
            refreshResult();
            return;
        }

        dragging = true;
        dragWithLeft = leftButton;
        pressed = hit;
        dragSlots.clear();

        if(hit != null)
        {
            addDragSlot(hit);
        }
    }

    /** Pohyb myši s drženým tlačítkem - přidá slot pod kurzorem do tažení. */
    public void drag(double mouseX, double mouseY, int screenWidth, int screenHeight)
    {
        if(!dragging)
        {
            return;
        }

        SlotHit hit = slotAt(mouseX, mouseY, screenWidth, screenHeight);

        if(hit != null)
        {
            addDragSlot(hit);
        }
    }

    /**
     * Puštění tlačítka: konec tažení.
     *
     * Přes dva a víc slotů se držená hromádka rozdělí. Zůstalo-li u jednoho
     * slotu (nebo u žádného), je to obyčejný klik - takže zmáčknout a pustit
     * na tomtéž místě dělá přesně to, co klikání dělalo vždycky.
     */
    public void release(double mouseX, double mouseY, int screenWidth, int screenHeight,
                        boolean leftButton, Inventory playerInventory)
    {
        // Puštění jiného tlačítka, než kterým se táhne, nic neukončuje.
        if(!dragging || leftButton != dragWithLeft)
        {
            return;
        }

        // Kurzor mohl na poslední slot dojet bez události pohybu.
        drag(mouseX, mouseY, screenWidth, screenHeight);
        dragging = false;

        if(dragSlots.size() > 1)
        {
            spreadHeld();
        }
        else
        {
            SlotHit target = dragSlots.isEmpty() ? pressed : dragSlots.get(0);

            if(target != null)
            {
                clickSlot(target, leftButton, playerInventory);
            }
        }

        dragSlots.clear();
        pressed = null;
        refreshResult();
    }

    /**
     * Kliknutí: zmáčknutí a puštění na tomtéž místě. Main posílá obojí zvlášť
     * (mezi nimi může být tažení); tohle je zkratka pro testy.
     */
    public void click(double mouseX, double mouseY, int screenWidth, int screenHeight,
                      boolean leftButton, Inventory playerInventory)
    {
        press(mouseX, mouseY, screenWidth, screenHeight, leftButton, false, playerInventory);
        release(mouseX, mouseY, screenWidth, screenHeight, leftButton, playerInventory);
    }

    /**
     * Obyčejný klik na slot. Pravidla jsou z Minecraftu: levým se bere
     * a pokládá celá hromádka, pravým se bere polovina a pokládá jeden kus.
     */
    private void clickSlot(SlotHit hit, boolean leftButton, Inventory playerInventory)
    {
        if(hit.grid().outputOnly())
        {
            takeResult(hit, playerInventory);
            return;
        }

        ItemStack slot = stackAt(hit);

        if(held.isEmpty())
        {
            pickUp(hit, slot, leftButton);
        }
        else
        {
            putDown(hit, slot, leftButton);
        }
    }

    /**
     * Shift-klik: celá hromádka jinam, bez braní do ruky.
     *
     * Vlastní sloty hráče přehazuje Inventory mezi hotbarem a batohem.
     * Z crafting mřížky se hromádka vrací do inventáře stejně jako při zavření
     * obrazovky; co se nevejde, zůstane ležet v mřížce.
     */
    private void quickMove(SlotHit hit, Inventory playerInventory)
    {
        Container container = hit.grid().container();

        if(container == playerInventory)
        {
            playerInventory.quickMove(hit.index());
        }
        else if(container == craftingGrid)
        {
            container.set(hit.index(), playerInventory.add(stackAt(hit)));
        }
    }

    /**
     * Zařadí slot do tažení, pokud do něj jde něco položit: prázdný, nebo se
     * stejným blokem a volným místem. Plný slot se nezařadí - jinak by si
     * vzal podíl, který by se do něj stejně nevešel.
     *
     * Slotů nesmí být víc než kusů v ruce, jinak by některý nedostal nic.
     * Minecraft to omezuje stejně.
     */
    private void addDragSlot(SlotHit hit)
    {
        if(hit.grid().outputOnly() || dragSlots.contains(hit)
                || dragSlots.size() >= held.count())
        {
            return;
        }

        ItemStack slot = stackAt(hit);

        if(slot.isEmpty() || (slot.block() == held.block() && slot.space() > 0))
        {
            dragSlots.add(hit);
        }
    }

    /**
     * Kolik kusů puštění položí do slotu s daným obsahem. Totéž číslo ukazuje
     * náhled během tažení, takže se nemůže stát, že náhled slíbí něco jiného.
     *
     * Levým tlačítkem dostane každý slot celou část z held / počet slotů
     * (zbytek po dělení zůstane v ruce), pravým jeden kus. Součet podílů
     * nikdy nepřeleze to, co je v ruce: slotů je nejvýš tolik co kusů.
     */
    private int dragAmount(ItemStack slot)
    {
        int share = dragWithLeft ? held.count() / dragSlots.size() : 1;
        return Math.min(share, slot.space());
    }

    private void spreadHeld()
    {
        int remaining = held.count();

        for(SlotHit hit : dragSlots)
        {
            ItemStack slot = stackAt(hit);
            int moved = dragAmount(slot);

            hit.grid().container().set(hit.index(),
                    ItemStack.of(held.block(), slot.count() + moved));
            remaining -= moved;
        }

        held = held.withCount(remaining);
    }

    /** Je slot součástí právě probíhajícího tažení? */
    private boolean isDragged(SlotGrid grid, int index)
    {
        return dragging && dragSlots.contains(new SlotHit(grid, index));
    }

    /** Co se ve slotu ukáže: během tažení i s kusy, které tam puštění položí. */
    private ItemStack shownAt(SlotGrid grid, int index)
    {
        ItemStack slot = grid.container().get(index);

        if(!isDragged(grid, index))
        {
            return slot;
        }

        return ItemStack.of(held.block(), slot.count() + dragAmount(slot));
    }

    /** Co se ukáže na kurzoru: během tažení jen to, co po rozdělení zbude. */
    private ItemStack shownHeld()
    {
        if(!dragging || dragSlots.isEmpty())
        {
            return held;
        }

        int remaining = held.count();

        for(SlotHit hit : dragSlots)
        {
            remaining -= dragAmount(stackAt(hit));
        }

        return held.withCount(remaining);
    }

    private void pickUp(SlotHit hit, ItemStack slot, boolean leftButton)
    {
        if(slot.isEmpty())
        {
            return;
        }

        if(leftButton)
        {
            held = slot;
            hit.grid().container().set(hit.index(), ItemStack.EMPTY);
            return;
        }

        // Pravé tlačítko bere polovinu, u lichého počtu tu větší.
        int taken = (slot.count() + 1) / 2;
        held = slot.withCount(taken);
        hit.grid().container().set(hit.index(), slot.plus(-taken));
    }

    private void putDown(SlotHit hit, ItemStack slot, boolean leftButton)
    {
        Container container = hit.grid().container();

        if(!leftButton)
        {
            // Pravým se pokládá po jednom - jen do prázdna nebo na stejný blok.
            if(slot.isEmpty())
            {
                container.set(hit.index(), held.withCount(1));
                held = held.plus(-1);
            }
            else if(slot.block() == held.block() && slot.space() > 0)
            {
                container.set(hit.index(), slot.plus(1));
                held = held.plus(-1);
            }

            return;
        }

        if(slot.isEmpty())
        {
            container.set(hit.index(), held);
            held = ItemStack.EMPTY;
            return;
        }

        if(slot.block() == held.block())
        {
            int moved = Math.min(slot.space(), held.count());
            container.set(hit.index(), slot.plus(moved));
            held = held.plus(-moved);
            return;
        }

        // Jiný blok: hromádky se prohodí.
        container.set(hit.index(), held);
        held = slot;
    }

    /**
     * Výsledek se dá jen vzít, a to celý. Teprve odebrání spotřebuje suroviny -
     * dokud na výsledek nikdo neklikne, dá se mřížka rozmyslet a rozebrat.
     */
    private void takeResult(SlotHit hit, Container playerInventory)
    {
        ItemStack result = hit.grid().container().get(0);

        if(result.isEmpty())
        {
            return;
        }

        if(held.isEmpty())
        {
            held = result;
        }
        else if(held.block() == result.block() && held.space() >= result.count())
        {
            held = held.plus(result.count());
        }
        else
        {
            // Kurzor je plný něčím jiným - výsledek putuje rovnou do batohu.
            ItemStack leftover = playerInventory.add(result);

            if(!leftover.isEmpty())
            {
                return;   // není kam, takže se nic nespotřebuje
            }
        }

        Recipes.consume(craftingGrid);
        refreshResult();
    }

    // ------------------------------------------------------------------
    // kreslení
    // ------------------------------------------------------------------

    public void render(Renderer2D shapes, TextRenderer text, BlockIcon icons,
                       int screenWidth, int screenHeight, double mouseX, double mouseY)
    {
        int scale = Gui.scale(screenWidth, screenHeight);

        float left = panelLeft(screenWidth, scale);
        float bottom = panelBottom(screenHeight, scale);

        shapes.begin(screenWidth, screenHeight);

        // Svět zůstává vidět, jen ztmavne - stejně jako u menu pauzy.
        shapes.fillRectGradient(0, 0, screenWidth, screenHeight,
                Palette.DIM_BOTTOM, Palette.DIM_TOP);

        shapes.bevelRect(left, bottom, PANEL_WIDTH * scale, PANEL_HEIGHT * scale, scale,
                Palette.PANEL_OUTLINE, Palette.CONTAINER_FILL,
                Palette.CONTAINER_HIGHLIGHT, Palette.CONTAINER_SHADOW);

        for(SlotGrid grid : grids)
        {
            for(int row = 0; row < grid.rows(); row++)
            {
                for(int column = 0; column < grid.columns(); column++)
                {
                    drawSlot(shapes, grid, row, column, screenWidth, screenHeight, scale);
                }
            }
        }

        shapes.end();

        // Ikony mají vlastní texturovaný shader, takže jdou samostatným
        // průchodem. Kurzor se kreslí uvnitř něj NAPOSLED, aby byl nad vším.
        drawIcons(icons, mouseX, mouseY, screenWidth, screenHeight, scale);

        drawCounts(text, screenWidth, screenHeight, scale);
        drawHeldCount(text, mouseX, mouseY, screenWidth, screenHeight, scale);

        text.begin(screenWidth, screenHeight, scale);
        text.drawShadowed(title, left + 8 * scale,
                screenHeight - (bottom + PANEL_HEIGHT * scale) + 6 * scale,
                Palette.TEXT, Palette.TEXT_SHADOW);
        text.end();
    }

    private void drawSlot(Renderer2D shapes, SlotGrid grid,
                          int row, int column, int screenWidth, int screenHeight, int scale)
    {
        float x = slotX(grid, column, screenWidth, scale);
        float y = slotY(grid, row, screenHeight, scale);
        float size = SLOT_PITCH * scale;

        // Slot je zapuštěný: tmavá hrana nahoře a vlevo je opak tlačítka.
        shapes.bevelRect(x, y, size, size, scale,
                Palette.SLOT_OUTLINE, Palette.SLOT_FILL,
                Palette.SLOT_SHADOW, Palette.SLOT_HIGHLIGHT);

        // Sloty v tažení se zesvětlí, aby bylo vidět, kam puštění položí kusy.
        if(isDragged(grid, grid.firstSlot() + row * grid.columns() + column))
        {
            shapes.fillRect(x + scale, y + scale, SLOT_INNER * scale, SLOT_INNER * scale,
                    Palette.SLOT_DRAG);
        }
    }

    /** Všechny kostky v jednom průchodu, kurzor naposled. */
    private void drawIcons(BlockIcon icons, double mouseX, double mouseY,
                           int screenWidth, int screenHeight, int scale)
    {
        icons.begin(screenWidth, screenHeight);

        for(SlotGrid grid : grids)
        {
            for(int row = 0; row < grid.rows(); row++)
            {
                for(int column = 0; column < grid.columns(); column++)
                {
                    ItemStack stack =
                            shownAt(grid, grid.firstSlot() + row * grid.columns() + column);

                    if(stack.isEmpty())
                    {
                        continue;
                    }

                    icons.draw(slotX(grid, column, screenWidth, scale) + scale,
                            slotY(grid, row, screenHeight, scale) + scale,
                            SLOT_INNER * scale, stack.block());
                }
            }
        }

        ItemStack cursor = shownHeld();

        if(!cursor.isEmpty())
        {
            icons.draw(heldX(mouseX, scale), heldY(mouseY, screenHeight, scale),
                    SLOT_INNER * scale, cursor.block());
        }

        icons.end();
    }

    private static float heldX(double mouseX, int scale)
    {
        return (float) mouseX - SLOT_INNER * scale / 2f;
    }

    private static float heldY(double mouseY, int screenHeight, int scale)
    {
        return (float) (screenHeight - mouseY) - SLOT_INNER * scale / 2f;
    }

    /** Počty se kreslí v jednom textovém průchodu, ať se shader nepřepíná po slotech. */
    private void drawCounts(TextRenderer text, int screenWidth, int screenHeight, int scale)
    {
        text.begin(screenWidth, screenHeight, scale);

        for(SlotGrid grid : grids)
        {
            for(int row = 0; row < grid.rows(); row++)
            {
                for(int column = 0; column < grid.columns(); column++)
                {
                    ItemStack stack =
                            shownAt(grid, grid.firstSlot() + row * grid.columns() + column);

                    if(stack.isEmpty() || stack.count() <= 1)
                    {
                        continue;
                    }

                    float x = slotX(grid, column, screenWidth, scale);
                    float y = slotY(grid, row, screenHeight, scale);

                    drawCount(text, stack.count(), x, y, screenWidth, screenHeight, scale);
                }
            }
        }

        text.end();
    }

    /** Počet se sází vpravo dole ve slotu, jako v Minecraftu. */
    private void drawCount(TextRenderer text, int count, float slotX, float slotY,
                           int screenWidth, int screenHeight, int scale)
    {
        String label = Integer.toString(count);

        float right = slotX + (SLOT_PITCH - 1) * scale;
        float topFromScreen = screenHeight - (slotY + text.lineHeight()) - scale;

        text.drawShadowed(label, right - text.widthOf(label), topFromScreen,
                Palette.TEXT, Palette.TEXT_SHADOW);
    }

    private void drawHeldCount(TextRenderer text, double mouseX, double mouseY,
                               int screenWidth, int screenHeight, int scale)
    {
        ItemStack cursor = shownHeld();

        if(cursor.isEmpty() || cursor.count() <= 1)
        {
            return;
        }

        text.begin(screenWidth, screenHeight, scale);
        drawCount(text, cursor.count(),
                heldX(mouseX, scale) - scale, heldY(mouseY, screenHeight, scale) - scale,
                screenWidth, screenHeight, scale);
        text.end();
    }

    // ------------------------------------------------------------------
    // hotové obrazovky
    // ------------------------------------------------------------------

    /**
     * Inventář na klávesu E: batoh, hotbar a malá crafting mřížka 2x2.
     * Souřadnice jsou přímo z Minecraftu.
     */
    public static ContainerScreen playerInventory(Container inventory,
                                                  Container crafting, Container result)
    {
        return new ContainerScreen("Inventory")
                .add(inventory, Inventory.HOTBAR_SIZE, 9, 3, 8, 84)   // batoh
                .add(inventory, 0, 9, 1, 8, 142)                       // hotbar
                .add(crafting, 0, 2, 2, 98, 18)
                .addOutput(result, 154, 28)
                .withCrafting(crafting, 2, 2);
    }

    /** Crafting table: to samé, jen s mřížkou 3x3. Stejná třída, jiné rozvržení. */
    public static ContainerScreen craftingTable(Container inventory,
                                                Container crafting, Container result)
    {
        return new ContainerScreen("Crafting")
                .add(inventory, Inventory.HOTBAR_SIZE, 9, 3, 8, 84)
                .add(inventory, 0, 9, 1, 8, 142)
                .add(crafting, 0, 3, 3, 30, 17)
                .addOutput(result, 124, 35)
                .withCrafting(crafting, 3, 3);
    }
}
