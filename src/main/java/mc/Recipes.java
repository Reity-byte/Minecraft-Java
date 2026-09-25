package mc;

import java.util.ArrayList;
import java.util.List;

/**
 * Crafting recepty a jejich vyhodnocení nad mřížkou.
 *
 * ---------------------------------------------------------------------------
 * Recept si NESE SVOU VELIKOST a hledá se kdekoliv v mřížce. Díky tomu funguje
 * recept 2x2 stejně v malé mřížce u inventáře jako v 3x3 na crafting table -
 * jen se v té větší dá položit do kteréhokoliv rohu. Přesně tak se to chová
 * v Minecraftu a je to jediný důvod, proč se recepty nemusí psát dvakrát.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, takže jde celé otestovat headless.
 */
public final class Recipes {

    /**
     * Tvarovaný recept. pattern se čte po řádcích SHORA DOLŮ, World.AIR
     * znamená "tady musí být prázdno". Buňky i výsledek jsou id věcí
     * (Items) - blok i předmět. Buňky i výsledek jsou id věcí
     * (Items) - blok i předmět.
     *
     * ⚠️ POLE pattern SE NEKOPÍRUJE - ani tady, ani v RecipeBook. Neměnnost
     * receptů je dohoda: nikdo do vráceného pole nepíše. Kopírovat by se
     * muselo při každém čtení, a porovnávání vzorů v crafting mřížce čte
     * pattern() pro každou polohu receptu. `equals` záznamu porovnává pole
     * podle reference, proto se vzory porovnávají přes Arrays.equals.
     */
    public record Recipe(int width, int height, int[] pattern, int result, int resultCount) {}

    private static final List<Recipe> SHAPED = new ArrayList<>();

    /** Bezetvarové recepty: rozhoduje jen to, co v mřížce leží, ne kde. */
    private static final List<Recipe> SHAPELESS = new ArrayList<>();

    static
    {
        // Čtyři prkna do čtverce = crafting table. Klasika a zároveň jediný
        // recept, kvůli kterému se vyplatí mít v inventáři malou mřížku.
        SHAPED.add(new Recipe(2, 2, new int[]{
                World.PLANKS, World.PLANKS,
                World.PLANKS, World.PLANKS
        }, World.CRAFTING_TABLE, 1));

        // Čtyři kameny do čtverce = čtyři cihly. Poměr 4:4 jako v Minecraftu.
        SHAPED.add(new Recipe(2, 2, new int[]{
                World.STONE, World.STONE,
                World.STONE, World.STONE
        }, World.STONE_BRICKS, 4));

        // ⚠️ Druhá cesta ke crafting table, a jediná DOSAŽITELNÁ: prkna se
        // v terénu negenerují (stromy zatím nejsou), takže by recept z prken
        // sám o sobě znamenal, že se první crafting table nedá vyrobit vůbec.
        // Přes kámen -> cihly -> stůl to jde od začátku hry.
        SHAPED.add(new Recipe(2, 2, new int[]{
                World.STONE_BRICKS, World.STONE_BRICKS,
                World.STONE_BRICKS, World.STONE_BRICKS
        }, World.CRAFTING_TABLE, 1));

        // Hromadná varianta: devět cihel dá dva stoly. Je to zároveň jediný
        // recept, který se do malé mřížky NEVEJDE - na něm je vidět, k čemu
        // je crafting table dobrá.
        SHAPED.add(new Recipe(3, 3, new int[]{
                World.STONE_BRICKS, World.STONE_BRICKS, World.STONE_BRICKS,
                World.STONE_BRICKS, World.STONE_BRICKS, World.STONE_BRICKS,
                World.STONE_BRICKS, World.STONE_BRICKS, World.STONE_BRICKS
        }, World.CRAFTING_TABLE, 2));

        // Klacek: dvě prkna nad sebou = čtyři klacky, jako v Minecraftu.
        // Vejde se do malé mřížky - klacky jsou potřeba hned od začátku.
        SHAPED.add(new Recipe(1, 2, new int[]{
                World.PLANKS,
                World.PLANKS
        }, ItemRegistry.STICK, 4));

        // Pochodeň: uhlí nad klackem = čtyři pochodně, jako v Minecraftu.
        SHAPED.add(new Recipe(1, 2, new int[]{
                ItemRegistry.COAL,
                ItemRegistry.STICK
        }, World.TORCH, 4));

        // Plot jako v Minecraftu: prkno, klacek, prkno ve dvou řadách = tři
        // ploty. Potřebuje tři sloupce, tedy crafting table.
        SHAPED.add(new Recipe(3, 2, new int[]{
                World.PLANKS, ItemRegistry.STICK, World.PLANKS,
                World.PLANKS, ItemRegistry.STICK, World.PLANKS
        }, World.FENCE, 3));

        // Kmen na čtyři prkna. Tímhle je crafting kompletní: prkna konečně
        // mají v terénu zdroj, takže kanonická cesta "prkna -> crafting table"
        // je dosažitelná bez oklikou přes kámen.
        SHAPELESS.add(new Recipe(0, 0, new int[]{World.LOG}, World.PLANKS, 4));

        // Bříza a smrk dávají tatáž prkna. Bez toho by hráč, který začne
        // v tajze nebo v březovém lese, neměl na prkna ŽÁDNÝ zdroj - pravidlo
        // "řetěz receptů musí být dosažitelný z terénu" platí i pro biomy.
        // Vlastní druhy prken by znamenaly další bloky, a ty biomy nepotřebují.
        SHAPELESS.add(new Recipe(0, 0, new int[]{World.BIRCH_LOG}, World.PLANKS, 4));
        SHAPELESS.add(new Recipe(0, 0, new int[]{World.SPRUCE_LOG}, World.PLANKS, 4));

        // Uhelná ruda jako blok (z inventáře před zavedením předmětů, nebo
        // z creative) se dá rozbít na uhlí - jinak by zůstala k ničemu,
        // pochodeň se z ní už nedělá. V Minecraftu to nejde, tady je to
        // přechod pro staré světy.
        SHAPELESS.add(new Recipe(0, 0, new int[]{World.COAL_ORE}, ItemRegistry.COAL, 1));

        // Tráva se dá oloupat na hlínu. Jeden kus kdekoliv v mřížce.
        SHAPELESS.add(new Recipe(0, 0, new int[]{World.GRASS}, World.DIRT, 1));
    }

    private Recipes() {}

    /**
     * Co vyjde z mřížky? Vrací prázdnou hromádku, když nic.
     *
     * grid je kontejner o rozměrech columns x rows, čtený po řádcích shora dolů.
     */
    public static ItemStack match(Container grid, int columns, int rows)
    {
        for(Recipe recipe : SHAPED)
        {
            if(matchesShaped(grid, columns, rows, recipe))
            {
                return ItemStack.of(recipe.result(), recipe.resultCount());
            }
        }

        for(Recipe recipe : SHAPELESS)
        {
            if(matchesShapeless(grid, recipe))
            {
                return ItemStack.of(recipe.result(), recipe.resultCount());
            }
        }

        // ⚠️ Recepty z labu se zkoušejí AŽ PO vestavěných a TÝMŽ porovnáním.
        //
        // Až po nich proto, že vestavěné recepty jsou součást hry a soubor
        // s daty je nesmí přebít - recept z recipes.json, který by měl stejný
        // vzor jako "čtyři prkna do čtverce", by jinak tiše změnil, co z něj
        // vyleze. Tímtéž porovnáním proto, že "co je shoda" musí mít jednu
        // odpověď: kdyby si lab nesl vlastní pravidla, choval by se v něm
        // recept jinak než ve hře a nikdo by nepoznal které je to pravé.
        //
        // Prázdný, chybějící i poškozený recipes.json dá prázdný seznam,
        // takže se tahle smyčka ani jednou neprotočí a hra je jako dřív.
        for(Recipe recipe : RecipeBook.active().recipes())
        {
            if(matchesShaped(grid, columns, rows, recipe))
            {
                return ItemStack.of(recipe.result(), recipe.resultCount());
            }
        }

        return ItemStack.EMPTY;
    }

    /**
     * Vyhrál by na tenhle vzor nějaký VESTAVĚNÝ recept? Pak recept z labu
     * s tímhle vzorem nikdy nic neudělá - `match()` zkouší vestavěné první.
     *
     * ⚠️ PTÁ SE TÉHOŽ POROVNÁNÍ JAKO HRA, ne rovnosti vzorů. Dřív se tu
     * porovnávaly jen tvarované recepty, takže vzor "jedna tráva" nebo
     * "prkno a uhlí" prošel, lab ho uložil s hláškou "works right now"
     * a bezetvarý vestavěný recept ho pak vždycky přebil. Vzor se proto
     * položí do mřížky 3x3 a zkusí se na něj oba seznamy vestavěných
     * receptů, stejně jako v crafting table.
     */
    public static boolean builtInHasPattern(Recipe wanted)
    {
        Recipe normalized = RecipeBook.normalize(wanted);
        int size = RecipeBook.MAX_SIZE;
        Container grid = new Container(size * size);

        for(int y = 0; y < normalized.height(); y++)
        {
            for(int x = 0; x < normalized.width(); x++)
            {
                int id = normalized.pattern()[y * normalized.width() + x];

                if(id != World.AIR)
                {
                    grid.set(y * size + x, ItemStack.of(id, 1));
                }
            }
        }

        for(Recipe recipe : SHAPED)
        {
            if(matchesShaped(grid, size, size, recipe))
            {
                return true;
            }
        }

        for(Recipe recipe : SHAPELESS)
        {
            if(matchesShapeless(grid, recipe))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Spotřebuje po jednom kuse z každého obsazeného slotu mřížky.
     * Volat až po odebrání výsledku.
     */
    public static void consume(Container grid)
    {
        for(int i = 0; i < grid.size(); i++)
        {
            grid.removeOne(i);
        }
    }

    /**
     * Zkusí recept nasadit na každou pozici v mřížce. Mimo obdélník receptu
     * musí být prázdno - jinak by "prkna ve čtverci a kámen navíc" pořád
     * vyrábělo crafting table.
     */
    private static boolean matchesShaped(Container grid, int columns, int rows, Recipe recipe)
    {
        if(recipe.width() > columns || recipe.height() > rows)
        {
            return false;
        }

        for(int offsetY = 0; offsetY <= rows - recipe.height(); offsetY++)
        {
            for(int offsetX = 0; offsetX <= columns - recipe.width(); offsetX++)
            {
                if(matchesAt(grid, columns, rows, recipe, offsetX, offsetY))
                {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean matchesAt(Container grid, int columns, int rows,
                                     Recipe recipe, int offsetX, int offsetY)
    {
        for(int y = 0; y < rows; y++)
        {
            for(int x = 0; x < columns; x++)
            {
                int wanted = World.AIR;

                int inX = x - offsetX;
                int inY = y - offsetY;

                if(inX >= 0 && inX < recipe.width() && inY >= 0 && inY < recipe.height())
                {
                    wanted = recipe.pattern()[inY * recipe.width() + inX];
                }

                ItemStack slot = grid.get(y * columns + x);
                int actual = slot.isEmpty() ? World.AIR : slot.id();

                if(actual != wanted)
                {
                    return false;
                }
            }
        }

        return true;
    }

    /** Bezetvarový recept: musí sedět počet obsazených slotů i jejich obsah. */
    private static boolean matchesShapeless(Container grid, Recipe recipe)
    {
        int[] needed = recipe.pattern().clone();
        int remaining = needed.length;

        for(int i = 0; i < grid.size(); i++)
        {
            ItemStack slot = grid.get(i);

            if(slot.isEmpty())
            {
                continue;
            }

            boolean used = false;

            for(int n = 0; n < needed.length; n++)
            {
                if(needed[n] == slot.id())
                {
                    needed[n] = World.AIR;
                    remaining--;
                    used = true;
                    break;
                }
            }

            // Něco v mřížce, co recept nechce -> recept neplatí.
            if(!used)
            {
                return false;
            }
        }

        return remaining == 0;
    }
}
