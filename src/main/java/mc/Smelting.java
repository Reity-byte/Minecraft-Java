package mc;

/**
 * Co se v peci taví na co.
 *
 * ---------------------------------------------------------------------------
 * Tavení bere JEDEN kus suroviny a dá výsledek (i víc kusů). Vestavěné
 * recepty jsou tady v kódu; recepty z labu (SmeltBook) přijdou za nimi,
 * a vestavěné mají přednost - stejně jako u crafting receptů.
 *
 * Kmen dává uhlí: Minecraft z něj dělá dřevěné uhlí (charcoal), které hoří
 * stejně - samostatný předmět by nic nepřidal, jen další dlaždici.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL.
 */
public final class Smelting {

    /** Tavení jednoho kusu: surovina -> výsledek. */
    public record Recipe(int input, int result, int count) {}

    private static final Recipe[] BUILT_IN = {
            new Recipe(World.IRON_ORE, ItemRegistry.IRON_INGOT, 1),
            new Recipe(World.LOG, ItemRegistry.COAL, 1),
            new Recipe(World.BIRCH_LOG, ItemRegistry.COAL, 1),
            new Recipe(World.SPRUCE_LOG, ItemRegistry.COAL, 1),
            // Stará uhelná ruda jako blok (z inventáře před předměty) - jako v receptu.
            new Recipe(World.COAL_ORE, ItemRegistry.COAL, 1),
    };

    private Smelting() {}

    public static java.util.List<Recipe> builtIn()
    {
        return java.util.List.of(BUILT_IN);
    }

    /** Recept pro surovinu - vestavěný, pak z labu; null, když se netaví. */
    public static Recipe of(int input)
    {
        for(Recipe recipe : BUILT_IN)
        {
            if(recipe.input() == input)
            {
                return recipe;
            }
        }

        return SmeltBook.active().find(input);
    }

    /** Co z jednoho kusu suroviny vyjde, nebo EMPTY. */
    public static ItemStack resultOf(ItemStack input)
    {
        if(input.isEmpty())
        {
            return ItemStack.EMPTY;
        }

        Recipe recipe = of(input.id());
        return recipe == null ? ItemStack.EMPTY : ItemStack.of(recipe.result(), recipe.count());
    }

    /** Taví se vestavěně? (Lab pak recept se stejnou surovinou odmítne - nikdy by nevyhrál.) */
    public static boolean builtInHas(int input)
    {
        for(Recipe recipe : BUILT_IN)
        {
            if(recipe.input() == input)
            {
                return true;
            }
        }

        return false;
    }
}
