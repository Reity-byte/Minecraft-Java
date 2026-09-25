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

    /** Posuvník u rolovatelné mřížky, v GUI pixelech. Jako v Minecraftu. */
    static final int SCROLLBAR_WIDTH = 6;
    static final int SCROLLBAR_THUMB = 15;

    /**
     * Jedna mřížka na obrazovce: kus kontejneru vykreslený jako sloupce x řádky
     * na dané pozici v panelu.
     *
     * outputOnly = dá se z ní jen brát (výsledek craftingu).
     * infinite   = NEKONEČNÝ ZDROJ (creative přehled): bere se z ní kopie
     *              a nikdy se z ní nic neubere. Viz pickUp a putDown.
     */
    public record SlotGrid(Container container, int firstSlot, int columns, int rows,
                           int guiX, int guiY, boolean outputOnly, boolean infinite) {}

    private final String title;
    private final List<SlotGrid> grids = new ArrayList<>();

    /**
     * Rozměry panelu v GUI pixelech. Výchozí jsou ty z Minecraftu; creative
     * přehled je vyšší, protože nad batohem má ještě mřížku se všemi bloky.
     */
    private int panelWidth = PANEL_WIDTH;
    private int panelHeight = PANEL_HEIGHT;

    /**
     * Mřížka, která se dá rolovat kolečkem, a o kolik řádků je posunutá.
     * Je nejvýš jedna - víc rolovatelných mřížek na jedné obrazovce by
     * znamenalo řešit, ke které kolečko patří, a k ničemu to zatím není.
     */
    private SlotGrid scrollable;
    private int scrollRow = 0;

    /** Nadpis druhé části panelu, nebo null. Viz section(). */
    private String sectionLabel;
    private int sectionLabelY;

    /**
     * Mřížka, ze které se craftí, a slot na výsledek. Můžou být null - truhla
     * ani obyčejný batoh nic nevyrábí.
     */
    private Container craftingGrid;
    private int craftingColumns;
    private int craftingRows;
    private Container resultSlot;

    /**
     * Pec, jejíž sloty obrazovka ukazuje, nebo null. Kvůli šipce a plamínku
     * a shift-kliku, který z inventáře posílá surovinu a palivo do pece.
     */
    private FurnaceState furnace;

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
        grids.add(new SlotGrid(container, firstSlot, columns, rows, guiX, guiY, false, false));
        return this;
    }

    /** Výsledkový slot: dá se z něj jen brát, nedá se do něj nic položit. */
    public ContainerScreen addOutput(Container container, int guiX, int guiY)
    {
        grids.add(new SlotGrid(container, 0, 1, 1, guiX, guiY, true, false));
        resultSlot = container;
        return this;
    }

    /**
     * Výstupní slot na daném indexu kontejneru, bez craftingu - výstup pece.
     * Vzetí ho vyprázdní (nic se nespotřebovává, výsledek už je hotový).
     */
    public ContainerScreen addOutput(Container container, int index, int guiX, int guiY)
    {
        grids.add(new SlotGrid(container, index, 1, 1, guiX, guiY, true, false));
        return this;
    }

    public ContainerScreen withFurnace(FurnaceState state)
    {
        furnace = state;
        return this;
    }

    /**
     * Nekonečný zdroj: mřížka, ze které se bere kopie a která se rolováním
     * posouvá po řádcích. Kontejner může mít víc slotů, než je vidět.
     */
    public ContainerScreen addInfinite(Container container, int columns, int rows,
                                       int guiX, int guiY)
    {
        SlotGrid grid = new SlotGrid(container, 0, columns, rows, guiX, guiY, false, true);
        grids.add(grid);
        scrollable = grid;
        return this;
    }

    /** Jiná velikost panelu než ta z Minecraftu. Volat před kreslením. */
    public ContainerScreen size(int guiWidth, int guiHeight)
    {
        panelWidth = guiWidth;
        panelHeight = guiHeight;
        return this;
    }

    /** Nadpis další části panelu (creative přehled má nad batohem "Inventory"). */
    public ContainerScreen section(String label, int guiY)
    {
        sectionLabel = label;
        sectionLabelY = guiY;
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
        return panelLeft(screenWidth, scale, PANEL_WIDTH);
    }

    public static int panelBottom(int screenHeight, int scale)
    {
        return panelBottom(screenHeight, scale, PANEL_HEIGHT);
    }

    /** Totéž pro panel jiné velikosti (creative přehled je vyšší). */
    public static int panelLeft(int screenWidth, int scale, int panelWidth)
    {
        return (int) Gui.snap((screenWidth - panelWidth * scale) / 2f, scale);
    }

    public static int panelBottom(int screenHeight, int scale, int panelHeight)
    {
        return (int) Gui.snap((screenHeight - panelHeight * scale) / 2f, scale);
    }

    /**
     * Levý DOLNÍ roh slotu v pixelech obrazovky.
     *
     * Rozvržení je zadané od levého horního rohu panelu (souřadnice
     * z Minecraftu), kdežto Renderer2D kreslí od levého dolního - proto se y
     * překlápí přes výšku panelu.
     */
    private float slotX(SlotGrid grid, int column, int screenWidth, int scale)
    {
        return panelLeft(screenWidth, scale, panelWidth) + (grid.guiX() + column * SLOT_PITCH) * scale;
    }

    private float slotY(SlotGrid grid, int row, int screenHeight, int scale)
    {
        int fromTop = grid.guiY() + row * SLOT_PITCH;
        return panelBottom(screenHeight, scale, panelHeight)
                + (panelHeight - fromTop - SLOT_PITCH) * scale;
    }

    // ------------------------------------------------------------------
    // rolování
    // ------------------------------------------------------------------

    /**
     * Index slotu v kontejneru pro políčko mřížky.
     *
     * ⚠️ Rolování posouvá jen INDEXY, ne kreslení. Mřížka zůstává, kde je,
     * a mění se to, co je v ní vidět - stejně jako v seznamu světů. Kdyby se
     * posouvaly souřadnice, musely by se sloty ořezávat na okraji panelu.
     */
    private int slotIndex(SlotGrid grid, int row, int column)
    {
        int offset = grid == scrollable ? scrollRow : 0;
        return grid.firstSlot() + (row + offset) * grid.columns() + column;
    }

    /** O kolik řádků nejvýš jde rolovat. Nula, když se všechno vejde. */
    public int maxScrollRow()
    {
        if(scrollable == null)
        {
            return 0;
        }

        int items = scrollable.container().size() - scrollable.firstSlot();
        int rows = (Math.max(0, items) + scrollable.columns() - 1) / scrollable.columns();

        return Math.max(0, rows - scrollable.rows());
    }

    public int scrollRow()
    {
        return scrollRow;
    }

    /** Kolečko: nahoru (kladné) roluje k prvnímu řádku, jako v seznamu světů. */
    public void scroll(double amount)
    {
        setScrollRow(scrollRow - (int) Math.signum(amount));
    }

    public void setScrollRow(int row)
    {
        scrollRow = Math.max(0, Math.min(maxScrollRow(), row));
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
                        return new SlotHit(grid, slotIndex(grid, row, column));
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

        // Z nekonečného zdroje shift-klik KOPÍRUJE, a to do hotbaru: tam
        // je blok vidět a rovnou se dá položit. Do batohu by zmizel do řady,
        // kterou hráč zrovna nemá na očích.
        if(hit.grid().infinite())
        {
            playerInventory.insert(stackAt(hit), 0, Inventory.HOTBAR_SIZE);
            return;
        }

        if(container == playerInventory)
        {
            // U pece jde surovina a palivo z inventáře rovnou do ní.
            if(furnace == null || !moveIntoFurnace(hit.index(), playerInventory))
            {
                playerInventory.quickMove(hit.index());
            }
        }
        else if(container == craftingGrid || (furnace != null && container == furnace.slots))
        {
            container.set(hit.index(), playerInventory.add(stackAt(hit)));
        }
    }

    /**
     * Shift-klik z inventáře u pece: co se taví, do suroviny; co hoří, do
     * paliva (surovina má přednost - kmen se dá tavit i pálit, jako
     * v Minecraftu). Slije se jen se stejnou věcí. Vrací false, když to do
     * pece nepatří - pak se hromádka přesouvá obvyklým způsobem.
     */
    private boolean moveIntoFurnace(int index, Inventory playerInventory)
    {
        ItemStack stack = playerInventory.get(index);
        int target = !Smelting.resultOf(stack).isEmpty() ? FurnaceState.INPUT
                : Fuel.isFuel(stack.id()) ? FurnaceState.FUEL : -1;

        if(target < 0)
        {
            return false;
        }

        ItemStack slot = furnace.slots.get(target);

        if(slot.isEmpty())
        {
            furnace.slots.set(target, stack);
            playerInventory.set(index, ItemStack.EMPTY);
        }
        else if(slot.sameItem(stack))
        {
            int moved = Math.min(Math.max(0, slot.space()), stack.count());
            furnace.slots.set(target, slot.plus(moved));
            playerInventory.set(index, stack.plus(-moved));
        }

        return true;
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
        // Nekonečný zdroj se do tažení nezařadí - rozdělovat hromádku mezi
        // sloty, ze kterých se stejně nedá nic vzít, nedává smysl. Puštění
        // nad ním je pak obyčejný klik, tedy zahození.
        if(hit.grid().outputOnly() || hit.grid().infinite() || dragSlots.contains(hit)
                || dragSlots.size() >= held.count())
        {
            return;
        }

        ItemStack slot = stackAt(hit);

        if(slot.isEmpty() || (slot.sameItem(held) && slot.space() > 0))
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
                    held.withCount(slot.count() + moved));
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

        return held.withCount(slot.count() + dragAmount(slot));
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

        // ⚠️ Z nekonečného zdroje se bere KOPIE - ve slotu zůstává, co tam
        // bylo. Není to přesun, je to "dej mi takový blok"; přehled má pořád
        // ukazovat všechno, jinak by si ho hráč po chvíli vysbíral.
        if(hit.grid().infinite())
        {
            held = slot;
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

        // Položit něco zpátky do nekonečného zdroje znamená zahodit to -
        // jako koš v Minecraftu. Přidat se tam nedá nic: obsah přehledu
        // se počítá z bloků, které ve hře existují (CreativeInventory).
        if(hit.grid().infinite())
        {
            held = ItemStack.EMPTY;
            return;
        }

        if(!leftButton)
        {
            // Pravým se pokládá po jednom - jen do prázdna nebo na stejný blok.
            if(slot.isEmpty())
            {
                container.set(hit.index(), held.withCount(1));
                held = held.plus(-1);
            }
            else if(slot.sameItem(held) && slot.space() > 0)
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

        if(slot.sameItem(held))
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
        ItemStack result = stackAt(hit);

        if(result.isEmpty())
        {
            return;
        }

        if(held.isEmpty())
        {
            held = result;
        }
        else if(held.sameItem(result) && held.space() >= result.count())
        {
            held = held.plus(result.count());
        }
        else
        {
            // Kurzor je plný něčím jiným - výsledek putuje rovnou do batohu.
            //
            // ⚠️ VŠECHNO, NEBO NIC. add() není atomické: nejdřív dolije
            // rozdělané hromádky a vrátí jen zbytek. Dřív se tu add() zavolalo
            // rovnou a při nenulovém zbytku se skončilo BEZ spotřeby surovin -
            // jenže dolitá část už v inventáři zůstala, takže šel výsledek brát
            // zadarmo pořád dokola. Proto se nejdřív zjistí, jestli se vejde celý.
            if(playerInventory.room(result) < result.count())
            {
                return;   // není kam, takže se nic nespotřebuje
            }

            playerInventory.add(result);
        }

        // Crafting spotřebuje suroviny a výsledek přepočítá; výstup pece je
        // hotová věc, takže se jen vyprázdní.
        if(craftingGrid != null)
        {
            Recipes.consume(craftingGrid);
            refreshResult();
        }
        else
        {
            hit.grid().container().set(hit.index(), ItemStack.EMPTY);
        }
    }

    // ------------------------------------------------------------------
    // kreslení
    // ------------------------------------------------------------------

    public void render(Renderer2D shapes, TextRenderer text, BlockIcon icons,
                       int screenWidth, int screenHeight, double mouseX, double mouseY)
    {
        int scale = Gui.scale(screenWidth, screenHeight);

        float left = panelLeft(screenWidth, scale, panelWidth);
        float bottom = panelBottom(screenHeight, scale, panelHeight);

        shapes.begin(screenWidth, screenHeight);

        // Svět zůstává vidět, jen ztmavne - stejně jako u menu pauzy.
        shapes.fillRectGradient(0, 0, screenWidth, screenHeight,
                Palette.DIM_BOTTOM, Palette.DIM_TOP);

        shapes.bevelRect(left, bottom, panelWidth * scale, panelHeight * scale, scale,
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

        drawScrollbar(shapes, screenWidth, screenHeight, scale);
        drawFurnaceGauges(shapes, screenWidth, screenHeight, scale);

        shapes.end();

        // Ikony mají vlastní texturovaný shader, takže jdou samostatným
        // průchodem. Pak JEDEN textový průchod pro počty ve slotech i titulky.
        //
        // ⚠️ HROMÁDKA NA KURZORU AŽ ÚPLNĚ NAKONEC, i se svým počtem. Dřív se
        // kurzor kreslil v průchodu ikon a počty slotů až po něm, takže číslice
        // slotu pod myší ležela přes drženou kostku.
        drawSlotIcons(icons, screenWidth, screenHeight, scale);
        drawSlotBars(shapes, screenWidth, screenHeight, scale);

        text.begin(screenWidth, screenHeight, scale);
        drawCounts(text, screenWidth, screenHeight, scale);

        float panelTop = screenHeight - (bottom + panelHeight * scale);
        text.drawShadowed(title, left + 8 * scale, panelTop + 6 * scale,
                Palette.TEXT, Palette.TEXT_SHADOW);

        if(sectionLabel != null)
        {
            text.drawShadowed(sectionLabel, left + 8 * scale, panelTop + sectionLabelY * scale,
                    Palette.TEXT, Palette.TEXT_SHADOW);
        }

        text.end();

        drawCursor(shapes, icons, text, mouseX, mouseY, screenWidth, screenHeight, scale);
    }

    /**
     * Posuvník vedle rolovatelné mřížky. Kreslí se jen když je co rolovat -
     * s prázdným blocks.json se přehled vejde celý a pruh by jen mátl.
     *
     * Není to ovládací prvek, jen ukazatel: roluje se kolečkem. Tažení za
     * značku by znamenalo další stav myši v obrazovce, která už tři má
     * (klik, shift-klik, tažení hromádky).
     */
    private void drawScrollbar(Renderer2D shapes, int screenWidth, int screenHeight, int scale)
    {
        int max = maxScrollRow();

        if(scrollable == null || max <= 0)
        {
            return;
        }

        float x = panelLeft(screenWidth, scale, panelWidth)
                + (scrollable.guiX() + scrollable.columns() * SLOT_PITCH + 2) * scale;
        float top = scrollable.guiY();
        int height = scrollable.rows() * SLOT_PITCH;
        float y = panelBottom(screenHeight, scale, panelHeight)
                + (panelHeight - top - height) * scale;

        shapes.bevelRect(x, y, SCROLLBAR_WIDTH * scale, height * scale, scale,
                Palette.SLOT_OUTLINE, Palette.SLOT_FILL,
                Palette.SLOT_SHADOW, Palette.SLOT_HIGHLIGHT);

        // Značka je pevně vysoká (jako v Minecraftu) a jezdí po zbytku dráhy.
        float travel = (height - SCROLLBAR_THUMB) * scale;
        float thumbY = y + travel * (1f - scrollRow / (float) max);

        shapes.fillRect(x + scale, thumbY, (SCROLLBAR_WIDTH - 2) * scale,
                SCROLLBAR_THUMB * scale, Palette.SELECTOR);
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
        if(isDragged(grid, slotIndex(grid, row, column)))
        {
            shapes.fillRect(x + scale, y + scale, SLOT_INNER * scale, SLOT_INNER * scale,
                    Palette.SLOT_DRAG);
        }
    }

    /** Kostky ve slotech v jednom průchodu (kurzor zvlášť, viz drawCursor). */
    private void drawSlotIcons(BlockIcon icons, int screenWidth, int screenHeight, int scale)
    {
        icons.begin(screenWidth, screenHeight);

        for(SlotGrid grid : grids)
        {
            for(int row = 0; row < grid.rows(); row++)
            {
                for(int column = 0; column < grid.columns(); column++)
                {
                    ItemStack stack =
                            shownAt(grid, slotIndex(grid, row, column));

                    if(stack.isEmpty())
                    {
                        continue;
                    }

                    icons.draw(slotX(grid, column, screenWidth, scale) + scale,
                            slotY(grid, row, screenHeight, scale) + scale,
                            SLOT_INNER * scale, stack.id());
                }
            }
        }

        icons.end();
    }

    /** Šipka postupu (vpravo od suroviny) a plamínek (mezi surovinou a palivem). */
    static final int ARROW_X = 80, ARROW_Y = 35, ARROW_W = 22, ARROW_H = 16;
    static final int FLAME_X = 57, FLAME_Y = 37, FLAME_SIZE = 14;

    private static final float[] GAUGE_BACK = {0.55f, 0.55f, 0.55f, 1f};
    private static final float[] ARROW_FILL = {1f, 1f, 1f, 1f};
    private static final float[] FLAME_FILL = {1f, 0.55f, 0.1f, 1f};

    /**
     * Ukazatele pece. Šipka se plní zleva podle postupu tavení, plamínek
     * odshora ubývá podle zbývajícího ohně - jako v Minecraftu, jen z obdélníků.
     */
    private void drawFurnaceGauges(Renderer2D shapes, int screenWidth, int screenHeight, int scale)
    {
        if(furnace == null)
        {
            return;
        }

        float left = panelLeft(screenWidth, scale, panelWidth);
        float bottom = panelBottom(screenHeight, scale, panelHeight);

        // Šipka: tělo a hrot ze dvou obdélníků, nejdřív šedá, pak bílá podle postupu.
        float ax = left + ARROW_X * scale;
        float ay = bottom + (panelHeight - ARROW_Y - ARROW_H) * scale;
        float bodyH = 6 * scale, bodyY = ay + 5 * scale;
        float headW = 7 * scale, bodyW = (ARROW_W - 7) * scale;

        shapes.fillRect(ax, bodyY, bodyW, bodyH, GAUGE_BACK);
        shapes.fillRect(ax + bodyW, ay + 2 * scale, headW, (ARROW_H - 4) * scale, GAUGE_BACK);

        float filled = furnace.progress() * ARROW_W * scale;

        if(filled > 0f)
        {
            shapes.fillRect(ax, bodyY, Math.min(filled, bodyW), bodyH, ARROW_FILL);

            if(filled > bodyW)
            {
                shapes.fillRect(ax + bodyW, ay + 2 * scale, filled - bodyW, (ARROW_H - 4) * scale, ARROW_FILL);
            }
        }

        // Plamínek: šedý čtverec, zdola oranžový podle zbývajícího ohně.
        float fx = left + FLAME_X * scale;
        float fy = bottom + (panelHeight - FLAME_Y - FLAME_SIZE) * scale;
        float size = FLAME_SIZE * scale;

        shapes.fillRect(fx + 3 * scale, fy, size - 6 * scale, size, GAUGE_BACK);

        if(furnace.isBurning())
        {
            shapes.fillRect(fx + 3 * scale, fy, size - 6 * scale, Math.max(scale, size * furnace.flame()), FLAME_FILL);
        }
    }

    /** Pruhy výdrže opotřebených nástrojů ve slotech - nad ikonami, pod počty. */
    private void drawSlotBars(Renderer2D shapes, int screenWidth, int screenHeight, int scale)
    {
        shapes.begin(screenWidth, screenHeight);

        for(SlotGrid grid : grids)
        {
            for(int row = 0; row < grid.rows(); row++)
            {
                for(int column = 0; column < grid.columns(); column++)
                {
                    Durability.draw(shapes, slotX(grid, column, screenWidth, scale) + scale,
                            slotY(grid, row, screenHeight, scale) + scale, SLOT_INNER * scale,
                            shownAt(grid, slotIndex(grid, row, column)));
                }
            }
        }

        shapes.end();
    }

    /** Hromádka na kurzoru i s počtem - nad vším ostatním. */
    private void drawCursor(Renderer2D shapes, BlockIcon icons, TextRenderer text, double mouseX, double mouseY,
                            int screenWidth, int screenHeight, int scale)
    {
        ItemStack cursor = shownHeld();

        if(cursor.isEmpty())
        {
            return;
        }

        icons.begin(screenWidth, screenHeight);
        icons.draw(heldX(mouseX, scale), heldY(mouseY, screenHeight, scale),
                SLOT_INNER * scale, cursor.id());
        icons.end();

        shapes.begin(screenWidth, screenHeight);
        Durability.draw(shapes, heldX(mouseX, scale), heldY(mouseY, screenHeight, scale),
                SLOT_INNER * scale, cursor);
        shapes.end();

        if(cursor.count() > 1)
        {
            text.begin(screenWidth, screenHeight, scale);
            drawCount(text, cursor.count(),
                    heldX(mouseX, scale) - scale, heldY(mouseY, screenHeight, scale) - scale,
                    screenWidth, screenHeight, scale);
            text.end();
        }
    }

    private static float heldX(double mouseX, int scale)
    {
        return (float) mouseX - SLOT_INNER * scale / 2f;
    }

    private static float heldY(double mouseY, int screenHeight, int scale)
    {
        return (float) (screenHeight - mouseY) - SLOT_INNER * scale / 2f;
    }

    /** Počty ve slotech - uvnitř společného textového průchodu (begin/end volá render). */
    private void drawCounts(TextRenderer text, int screenWidth, int screenHeight, int scale)
    {
        for(SlotGrid grid : grids)
        {
            for(int row = 0; row < grid.rows(); row++)
            {
                for(int column = 0; column < grid.columns(); column++)
                {
                    ItemStack stack =
                            shownAt(grid, slotIndex(grid, row, column));

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

    /**
     * Pec: surovina nahoře, palivo pod ní, mezi nimi plamínek, vpravo šipka
     * a výstup - rozvržení Minecraftu. Sloty jsou přímo sloty pece, takže co
     * se do nich položí, taví se dál i po zavření obrazovky.
     */
    public static ContainerScreen furnace(Container inventory, FurnaceState state)
    {
        return new ContainerScreen("Furnace")
                .add(inventory, Inventory.HOTBAR_SIZE, 9, 3, 8, 84)
                .add(inventory, 0, 9, 1, 8, 142)
                .add(state.slots, FurnaceState.INPUT, 1, 1, 56, 17)
                .add(state.slots, FurnaceState.FUEL, 1, 1, 56, 53)
                .addOutput(state.slots, FurnaceState.OUTPUT, 116, 35)
                .withFurnace(state);
    }

    // ------------------------------------------------------------------
    // creative
    // ------------------------------------------------------------------

    /** Kolik řádků přehledu je vidět naráz; zbytek se doroluje. */
    public static final int CREATIVE_COLUMNS = 9;
    public static final int CREATIVE_ROWS = 5;

    /** Panel creative přehledu: širší o posuvník, vyšší o mřížku bloků. */
    public static final int CREATIVE_WIDTH = 186;
    public static final int CREATIVE_HEIGHT = 206;

    /**
     * Creative přehled: nahoře všechny bloky, dole obyčejný inventář hráče.
     *
     * ⚠️ JE TO JINÁ OBRAZOVKA NEŽ INVENTÁŘ NA E V SURVIVALU, ne jeho režim.
     * Crafting mřížka tu schválně není - v creative se nic vyrábět nemusí -
     * a horní mřížka je nekonečný zdroj, ne kontejner k přesouvání. Survival
     * obrazovka se tím nezměnila ani o řádek; je to jen další tovární metoda,
     * přesně jak to ContainerScreen od začátku zamýšlel.
     *
     * source se staví při každém otevření (CreativeInventory.container),
     * takže blok právě založený v labu je v přehledu hned.
     */
    public static ContainerScreen creativeInventory(Container source, Container inventory)
    {
        return new ContainerScreen("Creative Inventory")
                .size(CREATIVE_WIDTH, CREATIVE_HEIGHT)
                .addInfinite(source, CREATIVE_COLUMNS, CREATIVE_ROWS, 8, 18)
                .section("Inventory", 112)
                .add(inventory, Inventory.HOTBAR_SIZE, 9, 3, 8, 124)   // batoh
                .add(inventory, 0, 9, 1, 8, 182);                      // hotbar
    }
}
