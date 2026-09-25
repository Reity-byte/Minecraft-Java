package mc;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;

/**
 * Recipe Lab: skládání craftovacích receptů do `textures/recipes.json`.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ TATÁŽ MŘÍŽKA A TÁŽ PRAVIDLA SHODY JAKO VE HŘE. Mřížka je 3x3, tedy
 * přesně crafting table, a slot je `ContainerScreen.SLOT_PITCH` - tytéž
 * rozměry, jaké kreslí inventář. Vyhodnocení si lab NEVYMÝŠLÍ: složený recept
 * strčí do obyčejného `Container` a zeptá se `Recipes.match()`, tedy funkce,
 * kterou se ptá crafting table i inventář. Kdyby si lab nesl vlastní
 * porovnávání, byly by dvě odpovědi na otázku "co je shoda" a nikdo by
 * nepoznal, která je ta pravá.
 *
 * Díky tomu je i NÁHLED v labu skutečnost: co lab ukáže jako výsledek, to
 * ve hře opravdu vyjde - a když lab nic neukáže, ve hře taky nic nevyjde,
 * protože to počítá tentýž kód.
 *
 * ⚠️ RECEPT SE OŘEŽE NA NEJMENŠÍ OBDÉLNÍK (`RecipeBook.normalize`). Lab
 * skládá vždycky do 3x3, ale recept si nese svou velikost a hledá se kdekoliv
 * v mřížce; bez ořezu by se recept 3x3 s jednou surovinou uprostřed do malé
 * mřížky 2x2 u inventáře NEVEŠEL VŮBEC. S ořezem se z něj stane recept 1x1
 * a funguje v obou mřížkách, přesně jak hráč čeká.
 *
 * ⚠️ NOVÝ RECEPT PLATÍ HNED, BEZ RESTARTU. Save zapíše soubor A ZÁROVEŇ
 * aktivuje nový `RecipeBook`; `Recipes.match()` se ptá aktivního seznamu,
 * ne souboru. Zkusit si recept jde tedy okamžitě po zavření labu.
 * ---------------------------------------------------------------------------
 *
 * Logika bez GL je v `RecipeBook` a `TextureLabLayout`; tady je kreslení,
 * vstup a držení rozepsaného receptu.
 */
public final class RecipeLab implements LabMode {

    private final Renderer2D shapes;
    private final TextRenderer text;
    private final TextureLab lab;

    /**
     * Rozepsaný recept jako obyčejný `Container` 3x3 - tentýž typ, jaký drží
     * crafting mřížka ve hře. Není to pole bajtů schválně: `Recipes.match()`
     * bere `Container`, takže se náhled ptá úplně stejně jako hra.
     */
    private final Container grid = new Container(RecipeBook.MAX_SIZE * RecipeBook.MAX_SIZE);

    /** Blok, který se pokládá klikem do mřížky. Vybírá se v přehledu dole. */
    private int picked = World.STONE;

    /** Výsledek receptu a počet kusů. */
    private int result = World.STONE;
    private int resultCount = 1;

    /**
     * Režim Smelting: recept pro pec (SmeltBook) místo crafting receptu.
     * Surovina je jedna věc v prostředním políčku; výsledek a počet sdílí
     * s craftingem. Obě rozepsané práce přežijí přepnutí režimu.
     */
    private boolean smelting = false;
    private ItemStack smeltInput = ItemStack.EMPTY;

    /** Prostřední políčko mřížky 3x3 - tam leží surovina tavení. */
    static final int SMELT_CELL = 4;

    /** O kolik řádků je přehled bloků odrolovaný. */
    private int pickerScroll = 0;

    /** Bloky v přehledu. Přepočítá se při každém vstupu do módu. */
    private List<Integer> available = new ArrayList<>();

    // ------------------------------------------------------------------
    // hlášky
    // ------------------------------------------------------------------

    /** Poslední hláška - aby šla logika módu otestovat bez labu (a bez GL). */
    private String lastMessage = "";

    /** Hláška do stavového řádku labu. Bez labu (headless test) si ji jen zapamatuje. */
    private void say(String message)
    {
        lastMessage = message;

        if(lab != null)
        {
            lab.say(message);
        }
    }

    String lastMessage()
    {
        return lastMessage;
    }

    public RecipeLab(TextureLab lab, Renderer2D shapes, TextRenderer text)
    {
        this.lab = lab;
        this.shapes = shapes;
        this.text = text;
    }

    @Override
    public String title()
    {
        return "Recipes";
    }

    @Override
    public String hint()
    {
        return "Pick a block or item below, click the grid, then set the result and Save";
    }

    /**
     * Ikona: mřížka 2x2 a šipka. Kreslí se obdélníky z `Renderer2D`, takže
     * nevyprázdní dávku bočního panelu (viz "Výkon labu").
     */
    @Override
    public void drawIcon(Renderer2D shapes, float left, float bottom, float size)
    {
        float unit = size / 9f;
        float[] cell = {0.36f, 0.30f, 0.22f, 1f};
        float[] arrow = {0.92f, 0.92f, 0.92f, 1f};

        for(int row = 0; row < 2; row++)
        {
            for(int column = 0; column < 2; column++)
            {
                shapes.fillRect(left + (0.5f + column * 2.2f) * unit,
                        bottom + (2.5f + row * 2.2f) * unit, 2 * unit, 2 * unit, cell);
            }
        }

        shapes.fillRect(left + 5.4f * unit, bottom + 4f * unit, 3f * unit, unit, arrow);
        shapes.fillRect(left + 7f * unit, bottom + 3.2f * unit, unit, 2.6f * unit, arrow);
    }

    @Override
    public void onEnter()
    {
        refreshBlocks();
    }

    @Override
    public void onLeave()
    {
        // Rozepsaný recept se schválně NEMAŽE: přepnout se na chvíli do
        // Blocks a podívat se, jak surovina vypadá, je normální postup.
        // Zahodí ho až tlačítko Clear (nebo zavření labu, viz unsaved()).
    }

    /**
     * Je v mřížce recept, který kniha nezná? Po Save mřížka zůstává, ale
     * její vzor už v knize je, takže zavření labu nic nezahodí.
     */
    @Override
    public boolean unsaved()
    {
        Recipes.Recipe draft = draft();
        Smelting.Recipe smelt = smeltDraft();
        return (draft != null && !RecipeBook.active().containsPattern(draft))
                || (smelt != null && !smelt.equals(SmeltBook.active().find(smelt.input())));
    }

    // ------------------------------------------------------------------
    // tavení
    // ------------------------------------------------------------------

    boolean isSmelting()
    {
        return smelting;
    }

    void setSmelting(boolean on)
    {
        smelting = on;
    }

    void setSmeltInput(int id)
    {
        smeltInput = ItemStack.of(id, 1);
    }

    /** Rozepsaný recept tavení, nebo null bez suroviny. */
    Smelting.Recipe smeltDraft()
    {
        return smeltInput.isEmpty() ? null : new Smelting.Recipe(smeltInput.id(), result, resultCount);
    }

    /** Proč recept tavení nejde uložit, nebo null. */
    String smeltProblem()
    {
        Smelting.Recipe draft = smeltDraft();

        if(draft == null)
        {
            return "Put one block or item in the middle";
        }

        String invalid = SmeltBook.validate(draft);

        if(invalid != null)
        {
            return invalid;
        }

        if(draft.equals(SmeltBook.active().find(draft.input())))
        {
            return "This smelting recipe is already saved";
        }

        return null;
    }

    /** Uloží recept tavení (nahradí dosavadní pro tutéž surovinu) a aktivuje ho. */
    void saveSmelting()
    {
        String problem = smeltProblem();

        if(problem != null)
        {
            say(problem);
            return;
        }

        SmeltBook updated = SmeltBook.active().with(smeltDraft());

        if(!updated.save(SmeltBook.FILE))
        {
            say(SafeFiles.writeFailed(SmeltBook.FILE));
            return;
        }

        SmeltBook.activate(updated);
        say("Saved - " + Items.name(smeltInput.id()) + " smelts to " + Items.name(result)
                + " x" + resultCount + ", works right now");
    }

    /** Smaže recept tavení z labu pro surovinu v políčku. */
    void deleteSmelting()
    {
        SmeltBook updated = SmeltBook.active().without(smeltInput.id());

        if(!updated.save(SmeltBook.FILE))
        {
            say(SafeFiles.writeFailed(SmeltBook.FILE));
            return;
        }

        SmeltBook.activate(updated);
        say("Deleted the smelting recipe for " + Items.name(smeltInput.id()));
    }

    /** Má surovina v políčku recept tavení z labu (ten jde smazat)? */
    private boolean hasLabSmelting()
    {
        return !smeltInput.isEmpty() && SmeltBook.active().find(smeltInput.id()) != null;
    }

    /**
     * Co se dá do receptu dát: všechny umístitelné vestavěné bloky, bloky
     * z labu a všechny předměty. Je to týž seznam, jaký nabízí creative přehled,
     * takže se nový blok i předmět z labu objeví i tady sám od sebe.
     */
    private void refreshBlocks()
    {
        available = CreativeInventory.ids(BlockRegistry.active(), ItemRegistry.active());

        if(!available.contains(picked))
        {
            picked = available.isEmpty() ? World.STONE : available.get(0);
        }

        if(!available.contains(result))
        {
            result = picked;
        }
    }

    // ------------------------------------------------------------------
    // rozepsaný recept
    // ------------------------------------------------------------------

    /**
     * Recept, jak by se uložil: rozměr 3x3 z mřížky, pak ořezaný na nejmenší
     * obdélník. Vrací null, když je mřížka prázdná.
     */
    Recipes.Recipe draft()
    {
        int[] pattern = new int[RecipeBook.MAX_SIZE * RecipeBook.MAX_SIZE];
        boolean any = false;

        for(int i = 0; i < pattern.length; i++)
        {
            ItemStack stack = grid.get(i);
            pattern[i] = stack.isEmpty() ? World.AIR : stack.id();

            if(pattern[i] != World.AIR)
            {
                any = true;
            }
        }

        if(!any)
        {
            return null;
        }

        return RecipeBook.normalize(new Recipes.Recipe(RecipeBook.MAX_SIZE, RecipeBook.MAX_SIZE,
                pattern, result, resultCount));
    }

    /**
     * Co by z mřížky vyšlo DNES, tedy podle už existujících receptů.
     *
     * ⚠️ Ptá se `Recipes.match()`, ne vlastního porovnání. Je to ta samá
     * otázka, jakou položí crafting table, takže se lab nemůže rozejít
     * se hrou v tom, co je shoda.
     */
    ItemStack existingResult()
    {
        return Recipes.match(grid, RecipeBook.MAX_SIZE, RecipeBook.MAX_SIZE);
    }

    /** Proč nejde uložit, nebo null, když jde. Text se ukazuje v labu. */
    String problem()
    {
        Recipes.Recipe draft = draft();

        if(draft == null)
        {
            return "Put at least one block or item in the grid";
        }

        String invalid = RecipeBook.validate(draft);

        if(invalid != null)
        {
            return invalid;
        }

        // Vestavěný recept se přebít nedá - match() je zkouší první, takže
        // by uložený recept nikdy nevyhrál a tiše by nic nedělal.
        if(Recipes.builtInHasPattern(draft))
        {
            return "A built-in recipe already uses this pattern";
        }

        if(RecipeBook.active().containsPattern(draft))
        {
            return "A saved recipe already uses this pattern";
        }

        return null;
    }

    /**
     * Rozepsaná mřížka. Balíčkově viditelná kvůli `RecipeLabTest`: ten si do
     * ní naskládá bloky přímo, protože `press()` potřebuje živý lab kvůli
     * stavové hlášce, kdežto `draft()` a `problem()` jsou čistá logika a jdou
     * ověřit bez GL.
     */
    Container grid()
    {
        return grid;
    }

    void clear()
    {
        grid.clear();
    }

    // ------------------------------------------------------------------
    // vstup
    // ------------------------------------------------------------------

    @Override
    public boolean press(TextureLabLayout layout, double mouseX, double mouseY,
                         int screenWidth, int screenHeight, boolean left)
    {
        int cell = layout.recipeCellAt(mouseX, mouseY);

        if(cell >= 0)
        {
            // Tavení má jen jednu surovinu - kamkoliv do mřížky se klikne,
            // jde do prostředního políčka.
            if(smelting)
            {
                smeltInput = left ? ItemStack.of(picked, 1) : ItemStack.EMPTY;
                return false;
            }

            // Levé tlačítko položí vybraný blok, pravé buňku vyprázdní -
            // stejné rozdělení jako u malování a kapátka v ostatních módech.
            grid.set(cell, left ? ItemStack.of(picked, 1) : ItemStack.EMPTY);
            return false;
        }

        if(!left)
        {
            return false;
        }

        int slot = pickerSlotAt(layout, mouseX, mouseY);

        if(slot >= 0)
        {
            picked = available.get(slot);
            say("Picked " + Items.name(picked) + " - click the grid to place it");
            return false;
        }

        if(layout.hit(TextureLabLayout.RECIPE_RESULT, mouseX, mouseY))
        {
            // Klik na výsledek do něj dá právě vybraný blok - není potřeba
            // druhý přehled jen pro výstup.
            result = picked;
            say("Result: " + Items.name(result));
            return false;
        }

        if(layout.hit(TextureLabLayout.RECIPE_LESS, mouseX, mouseY))
        {
            resultCount = Math.max(1, resultCount - 1);
            return false;
        }

        if(layout.hit(TextureLabLayout.RECIPE_MORE, mouseX, mouseY))
        {
            resultCount = Math.min(Items.maxStack(result), resultCount + 1);
            return false;
        }

        if(layout.hit(TextureLabLayout.RECIPE_KIND, mouseX, mouseY))
        {
            smelting = !smelting;
            say(smelting ? "Smelting: one item in the middle, set the result, Save"
                    : "Crafting: fill the grid, set the result, Save");
            return false;
        }

        if(layout.hit(TextureLabLayout.RECIPE_CLEAR, mouseX, mouseY))
        {
            if(smelting && hasLabSmelting())
            {
                deleteSmelting();
            }
            else if(smelting)
            {
                smeltInput = ItemStack.EMPTY;
                say("Cleared");
            }
            else
            {
                clear();
                say("Grid cleared");
            }
            return false;
        }

        if(layout.hit(TextureLabLayout.RECIPE_SAVE, mouseX, mouseY))
        {
            if(smelting)
            {
                saveSmelting();
            }
            else
            {
                save();
            }
            return false;
        }

        if(layout.hit(TextureLabLayout.RECIPE_CLOSE, mouseX, mouseY))
        {
            return true;
        }

        return false;
    }

    /**
     * Uloží recept do souboru A ZÁROVEŇ ho aktivuje.
     *
     * ⚠️ Pořadí je "zapsat, pak aktivovat". Kdyby se aktivovalo dřív, hrálo
     * by se s receptem, který na disku není, a po restartu by zmizel - tentýž
     * důvod, proč Create u bloku z labu ukládá atlas dřív, než blok založí.
     */
    void save()
    {
        String problem = problem();

        if(problem != null)
        {
            say(problem);
            return;
        }

        RecipeBook updated = RecipeBook.active().with(draft());

        if(!updated.save(RecipeBook.FILE))
        {
            say(SafeFiles.writeFailed(RecipeBook.FILE));
            return;
        }

        RecipeBook.activate(updated);
        say("Saved - " + Items.name(result) + " x" + resultCount
                + " works right now, no restart");
    }

    @Override
    public boolean key(int key, int mods)
    {
        if(key == GLFW_KEY_DELETE || key == GLFW_KEY_BACKSPACE)
        {
            if(smelting)
            {
                smeltInput = ItemStack.EMPTY;
            }
            else
            {
                clear();
            }

            say("Grid cleared");
            return true;
        }

        return false;
    }

    /** Kolečko roluje přehledem bloků, když se nevejdou. */
    @Override
    public void scroll(double yoffset)
    {
        int rows = (available.size() + TextureLabLayout.PICKER_COLUMNS - 1)
                / TextureLabLayout.PICKER_COLUMNS;
        int maxScroll = Math.max(0, rows - TextureLabLayout.PICKER_ROWS);

        pickerScroll = Math.max(0, Math.min(maxScroll,
                pickerScroll - (int) Math.signum(yoffset)));
    }

    /** Index bloku v seznamu pod myší, se započítaným odrolováním, nebo -1. */
    private int pickerSlotAt(TextureLabLayout layout, double mouseX, double mouseY)
    {
        int slot = layout.pickerSlotAt(mouseX, mouseY);

        if(slot < 0)
        {
            return -1;
        }

        int index = slot + pickerScroll * TextureLabLayout.PICKER_COLUMNS;
        return index < available.size() ? index : -1;
    }

    // ------------------------------------------------------------------
    // kreslení
    // ------------------------------------------------------------------

    @Override
    public void drawShapes(TextureLabLayout layout, int screenWidth, int screenHeight,
                           double mouseX, double mouseY)
    {
        int scale = layout.scale();

        shapes.begin(screenWidth, screenHeight);

        for(int row = 0; row < RecipeBook.MAX_SIZE; row++)
        {
            for(int column = 0; column < RecipeBook.MAX_SIZE; column++)
            {
                // Při tavení je vidět jen prostřední políčko - surovina.
                if(!smelting || row * RecipeBook.MAX_SIZE + column == SMELT_CELL)
                {
                    lab.sunken(layout, screenHeight, TextureLabLayout.recipeCell(column, row));
                }
            }
        }

        lab.sunken(layout, screenHeight, TextureLabLayout.RECIPE_RESULT);
        lab.fill(layout, screenHeight, TextureLabLayout.RECIPE_ARROW, Palette.TEXT);

        lab.sunken(layout, screenHeight, TextureLabLayout.RECIPE_PICKER);

        for(int i = 0; i < visibleSlots(); i++)
        {
            int index = i + pickerScroll * TextureLabLayout.PICKER_COLUMNS;

            if(index >= available.size())
            {
                break;
            }

            TextureLabLayout.Rect slot = TextureLabLayout.pickerSlot(i);
            lab.sunken(layout, screenHeight, slot);

            if(available.get(index) == picked)
            {
                lab.outline(layout, screenHeight, slot, scale, Palette.SELECTOR);
            }
        }

        lab.button(layout, screenHeight, TextureLabLayout.RECIPE_SAVE, mouseX, mouseY);
        lab.button(layout, screenHeight, TextureLabLayout.RECIPE_CLEAR, mouseX, mouseY);
        lab.button(layout, screenHeight, TextureLabLayout.RECIPE_CLOSE, mouseX, mouseY);
        lab.button(layout, screenHeight, TextureLabLayout.RECIPE_KIND, mouseX, mouseY);
        lab.button(layout, screenHeight, TextureLabLayout.RECIPE_LESS, mouseX, mouseY);
        lab.button(layout, screenHeight, TextureLabLayout.RECIPE_MORE, mouseX, mouseY);

        shapes.end();

        // Ikony bloků mají vlastní shader, takže jdou AŽ PO dávce tvarů -
        // jinak by se dávka musela uprostřed vyprázdnit.
        drawIcons(layout, screenWidth, screenHeight);
    }

    private int visibleSlots()
    {
        return TextureLabLayout.PICKER_COLUMNS * TextureLabLayout.PICKER_ROWS;
    }

    private void drawIcons(TextureLabLayout layout, int screenWidth, int screenHeight)
    {
        int scale = layout.scale();
        int inner = ContainerScreen.SLOT_INNER * scale;

        // Jedno navázání shaderu na všechny ikony - viz TextureLab.blockIconsBegin.
        lab.blockIconsBegin(screenWidth, screenHeight);

        for(int row = 0; row < RecipeBook.MAX_SIZE; row++)
        {
            for(int column = 0; column < RecipeBook.MAX_SIZE; column++)
            {
                int index = row * RecipeBook.MAX_SIZE + column;
                ItemStack stack = !smelting ? grid.get(index)
                        : index == SMELT_CELL ? smeltInput : ItemStack.EMPTY;

                if(!stack.isEmpty())
                {
                    TextureLabLayout.Rect cell = TextureLabLayout.recipeCell(column, row);
                    lab.blockIcon(stack.id(), layout.screenX(cell) + scale,
                            layout.screenBottom(cell, screenHeight) + scale, inner);
                }
            }
        }

        lab.blockIcon(result, layout.screenX(TextureLabLayout.RECIPE_RESULT) + scale,
                layout.screenBottom(TextureLabLayout.RECIPE_RESULT, screenHeight) + scale, inner);

        for(int i = 0; i < visibleSlots(); i++)
        {
            int index = i + pickerScroll * TextureLabLayout.PICKER_COLUMNS;

            if(index >= available.size())
            {
                break;
            }

            TextureLabLayout.Rect slot = TextureLabLayout.pickerSlot(i);
            lab.blockIcon(available.get(index), layout.screenX(slot) + scale,
                    layout.screenBottom(slot, screenHeight) + scale, inner);
        }

        lab.blockIconsEnd();
    }

    @Override
    public void drawText(TextureLabLayout layout, int screenWidth, int screenHeight,
                         double mouseX, double mouseY)
    {
        int scale = layout.scale();
        text.begin(screenWidth, screenHeight, scale);

        String file = SafeFiles.shown(smelting ? SmeltBook.FILE : RecipeBook.FILE);
        lab.label(layout, 8, TextureLabLayout.TITLE_Y,
                (smelting ? "Lab   smelting: " : "Lab   recipes: ") + file + "   saved: "
                        + (smelting ? SmeltBook.active().size() : RecipeBook.active().size())
                        + (unsaved() ? "   (unsaved)" : ""));

        lab.centered(layout, TextureLabLayout.RECIPE_SAVE, "Save recipe");
        lab.centered(layout, TextureLabLayout.RECIPE_CLEAR,
                smelting ? (hasLabSmelting() ? "Delete recipe" : "Clear") : "Clear grid");
        lab.centered(layout, TextureLabLayout.RECIPE_CLOSE, "Close  (Esc)");
        lab.centered(layout, TextureLabLayout.RECIPE_KIND, smelting ? "Mode: Smelting" : "Mode: Crafting");

        lab.centered(layout, TextureLabLayout.RECIPE_LESS, "-");
        lab.centered(layout, TextureLabLayout.RECIPE_MORE, "+");
        lab.centered(layout, TextureLabLayout.RECIPE_COUNT, "x" + resultCount);

        lab.label(layout, TextureLabLayout.RECIPE_GRID.x(),
                TextureLabLayout.RECIPE_GRID.y() - 12, smelting
                        ? "Smelting (one item in the middle)" : "Recipe (same grid as a crafting table)");

        if(smelting)
        {
            drawSmeltingInfo(layout);
            text.end();
            return;
        }

        lab.label(layout, 8, TextureLabLayout.PICKER_LABEL_Y,
                "Blocks & items: " + available.size() + " (LMB picks, wheel scrolls)"
                        + "   holding: " + Items.name(picked));

        // Co dnes z mřížky vyjde - přes tutéž Recipes.match(), jakou používá
        // crafting table. Když to něco vrátí, tenhle vzor už recept má.
        ItemStack existing = existingResult();
        String info = existing.isEmpty()
                ? "This pattern makes nothing yet"
                : "Already makes " + Items.name(existing.id()) + " x" + existing.count();

        lab.label(layout, 8, TextureLabLayout.RECIPE_INFO_Y, info);

        String problem = problem();
        lab.label(layout, 8, TextureLabLayout.RECIPE_LIST_Y,
                problem == null
                        ? "Ready: " + Items.name(result) + " x" + resultCount
                        : problem);

        text.end();
    }

    /** Texty režimu Smelting: co surovina dnes dává a jestli jde uložit. */
    private void drawSmeltingInfo(TextureLabLayout layout)
    {
        lab.label(layout, 8, TextureLabLayout.PICKER_LABEL_Y,
                "Blocks & items: " + available.size() + " (LMB picks, wheel scrolls)"
                        + "   holding: " + Items.name(picked));

        Smelting.Recipe existing = smeltInput.isEmpty() ? null : Smelting.of(smeltInput.id());
        String info = existing == null
                ? "Nothing smelts from this yet"
                : "Smelts to " + Items.name(existing.result()) + " x" + existing.count()
                + (Smelting.builtInHas(existing.input()) ? " (built-in)" : " (lab)");
        lab.label(layout, 8, TextureLabLayout.RECIPE_INFO_Y, info);

        String problem = smeltProblem();
        lab.label(layout, 8, TextureLabLayout.RECIPE_LIST_Y, problem == null
                ? "Ready: smelts to " + Items.name(result) + " x" + resultCount : problem);
    }

    /** Nápověda dole podle toho, na čem je myš. */
    @Override
    public String help(TextureLabLayout layout, double mouseX, double mouseY)
    {
        if(layout.recipeCellAt(mouseX, mouseY) >= 0)
        {
            return "LMB places the held block, RMB clears the cell";
        }

        if(layout.pickerSlotAt(mouseX, mouseY) >= 0)
        {
            return "Every placeable block, built-in and from the lab - wheel scrolls";
        }

        if(layout.hit(TextureLabLayout.RECIPE_RESULT, mouseX, mouseY))
        {
            return "Click to make the held block the result";
        }

        if(layout.hit(TextureLabLayout.RECIPE_SAVE, mouseX, mouseY))
        {
            return "Writes " + SafeFiles.shown(smelting ? SmeltBook.FILE : RecipeBook.FILE)
                    + " and the recipe works right away, no restart";
        }

        if(layout.hit(TextureLabLayout.RECIPE_KIND, mouseX, mouseY))
        {
            return "Switch between crafting table recipes and furnace (smelting) recipes";
        }

        return smelting
                ? "Smelting: pick a block or item, click the grid, set the result, Save"
                : "Build a recipe: pick a block or item, fill the grid, set the result, Save";
    }
}
