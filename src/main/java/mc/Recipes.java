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
     * znamená "tady musí být prázdno".
     */
    public record Recipe(int width, int height, byte[] pattern, byte result, int resultCount) {}

    private static final List<Recipe> SHAPED = new ArrayList<>();

    /** Bezetvarové recepty: rozhoduje jen to, co v mřížce leží, ne kde. */
    private static final List<Recipe> SHAPELESS = new ArrayList<>();

    static
    {
        // Čtyři prkna do čtverce = crafting table. Klasika a zároveň jediný
        // recept, kvůli kterému se vyplatí mít v inventáři malou mřížku.
        SHAPED.add(new Recipe(2, 2, new byte[]{
                World.PLANKS, World.PLANKS,
                World.PLANKS, World.PLANKS
        }, World.CRAFTING_TABLE, 1));

        // Čtyři kameny do čtverce = čtyři cihly. Poměr 4:4 jako v Minecraftu.
        SHAPED.add(new Recipe(2, 2, new byte[]{
                World.STONE, World.STONE,
                World.STONE, World.STONE
        }, World.STONE_BRICKS, 4));

        // ⚠️ Druhá cesta ke crafting table, a jediná DOSAŽITELNÁ: prkna se
        // v terénu negenerují (stromy zatím nejsou), takže by recept z prken
        // sám o sobě znamenal, že se první crafting table nedá vyrobit vůbec.
        // Přes kámen -> cihly -> stůl to jde od začátku hry.
        SHAPED.add(new Recipe(2, 2, new byte[]{
                World.STONE_BRICKS, World.STONE_BRICKS,
                World.STONE_BRICKS, World.STONE_BRICKS
        }, World.CRAFTING_TABLE, 1));

        // Hromadná varianta: devět cihel dá dva stoly. Je to zároveň jediný
        // recept, který se do malé mřížky NEVEJDE - na něm je vidět, k čemu
        // je crafting table dobrá.
        SHAPED.add(new Recipe(3, 3, new byte[]{
                World.STONE_BRICKS, World.STONE_BRICKS, World.STONE_BRICKS,
                World.STONE_BRICKS, World.STONE_BRICKS, World.STONE_BRICKS,
                World.STONE_BRICKS, World.STONE_BRICKS, World.STONE_BRICKS
        }, World.CRAFTING_TABLE, 2));

        // Plot: tři prkna v řadě. Je to druhý recept, který se do malé mřížky
        // NEVEJDE - potřebuje tři sloupce, tedy crafting table.
        SHAPED.add(new Recipe(3, 1, new byte[]{
                World.PLANKS, World.PLANKS, World.PLANKS
        }, World.FENCE, 3));

        // Kmen na čtyři prkna. Tímhle je crafting kompletní: prkna konečně
        // mají v terénu zdroj, takže kanonická cesta "prkna -> crafting table"
        // je dosažitelná bez oklikou přes kámen.
        SHAPELESS.add(new Recipe(0, 0, new byte[]{World.LOG}, World.PLANKS, 4));

        // Pochodeň: prkno a uhlí. V Minecraftu je to klacek místo prkna -
        // klacek je ale PŘEDMĚT, ne blok, a předměty zatím neexistují
        // (ItemStack drží id bloku). Až přibudou, recept se opraví.
        SHAPELESS.add(new Recipe(0, 0, new byte[]{World.PLANKS, World.COAL_ORE},
                World.TORCH, 4));

        // Tráva se dá oloupat na hlínu. Jeden kus kdekoliv v mřížce.
        SHAPELESS.add(new Recipe(0, 0, new byte[]{World.GRASS}, World.DIRT, 1));
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

        return ItemStack.EMPTY;
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
                byte wanted = World.AIR;

                int inX = x - offsetX;
                int inY = y - offsetY;

                if(inX >= 0 && inX < recipe.width() && inY >= 0 && inY < recipe.height())
                {
                    wanted = recipe.pattern()[inY * recipe.width() + inX];
                }

                ItemStack slot = grid.get(y * columns + x);
                byte actual = slot.isEmpty() ? World.AIR : slot.block();

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
        byte[] needed = recipe.pattern().clone();
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
                if(needed[n] == slot.block())
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
