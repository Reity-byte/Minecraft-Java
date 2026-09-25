package mc;

import java.util.Locale;

/**
 * Rozepsaný nový předmět v labu (mód Items) - všechno kromě kreslení a vstupu.
 *
 * ---------------------------------------------------------------------------
 * Stejný princip jako BlockDraft: hodnoty se KROKUJÍ po známých stupních,
 * ne píšou číslem. Velikost hromádky po 1, 8, 16, 32, 64; rychlost nástroje
 * po 2x až 16x - Minecraft má dřevo 2x, kámen 4x, železo 6x, diamant 8x
 * a zlato 12x, takže "4x" je poznat jako kamenný nástroj.
 *
 * Nástroj se v Minecraftu nestackuje: přepnutí na nástroj nastaví hromádku
 * na 1 a výdrž kamene (131), zpátky na obyčejný předmět hromádku 64 a bez
 * výdrže. Výdrž se krokuje po stupních Minecraftu: zlato 32, dřevo 59,
 * kámen 131, železo 250, diamant 1561. Předmět s výdrží má vždycky
 * hromádku 1 (opotřebení nese hromádka, různé by nešly slít).
 *
 * Na rozdíl od bloku nepotřebuje dočasný registr: náhled je obrázek dlaždice
 * a nic ve hře se na předmět z návrhu neptá.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL.
 */
final class ItemDraft {

    static final int[] STACK_STEPS = {1, 8, 16, 32, 64};
    static final float[] SPEED_STEPS = {2f, 4f, 6f, 8f, 12f, 16f};
    static final int[] DURABILITY_STEPS = {0, 32, 59, 131, 250, 1561};

    /** Výdrž, kterou dostane nový nástroj: kámen. */
    private static final int TOOL_DURABILITY = 3;

    private static final int FULL_STACK = STACK_STEPS.length - 1;
    private static final int DEFAULT_SPEED = 1;   // 4x, kámen

    String name = "";

    /** Dlaždice atlasu předmětů - ta, která je v labu zrovna vybraná. */
    int tile;

    int stackStep = FULL_STACK;
    ItemDef.Tool tool = ItemDef.Tool.NONE;
    int speedStep = DEFAULT_SPEED;
    int durabilityStep = 0;

    ItemDraft(int startTile)
    {
        tile = startTile;
    }

    int stack()
    {
        return STACK_STEPS[stackStep];
    }

    void more()
    {
        // S výdrží zůstává hromádka 1 - viz třída.
        if(durability() > 0)
        {
            return;
        }

        stackStep = Math.min(STACK_STEPS.length - 1, stackStep + 1);
    }

    int durability()
    {
        return DURABILITY_STEPS[durabilityStep];
    }

    /** Další stupeň výdrže dokola; výdrž nastaví hromádku na 1. */
    void nextDurability()
    {
        durabilityStep = (durabilityStep + 1) % DURABILITY_STEPS.length;

        if(durability() > 0)
        {
            stackStep = 0;
        }
    }

    /** Popisek výdrže do labu: "Unbreakable", "Uses 131". */
    String durabilityLabel()
    {
        return durability() == 0 ? "Unbreakable" : "Uses " + durability();
    }

    void fewer()
    {
        stackStep = Math.max(0, stackStep - 1);
    }

    /** Další druh nástroje dokola; s nástrojem hromádka 1, bez něj 64. */
    void nextTool()
    {
        ItemDef.Tool[] tools = ItemDef.Tool.values();
        boolean wasTool = tool != ItemDef.Tool.NONE;
        tool = tools[(tool.ordinal() + 1) % tools.length];

        if(tool == ItemDef.Tool.NONE)
        {
            stackStep = FULL_STACK;
            durabilityStep = 0;
        }
        else
        {
            stackStep = 0;

            // Jen při přechodu z obyčejného předmětu - krokování mezi
            // krumpáčem a sekerou nastavenou výdrž nemění.
            if(!wasTool && durabilityStep == 0)
            {
                durabilityStep = TOOL_DURABILITY;
            }
        }
    }

    void nextSpeed()
    {
        speedStep = (speedStep + 1) % SPEED_STEPS.length;
    }

    /** Rychlost, kterou předmět dostane - bez nástroje 1 (jako ruka). */
    float speed()
    {
        return tool == ItemDef.Tool.NONE ? 1f : SPEED_STEPS[speedStep];
    }

    /** Popisek nástroje do labu: "No tool", "Pickaxe". */
    String toolLabel()
    {
        if(tool == ItemDef.Tool.NONE)
        {
            return "No tool";
        }

        String key = tool.key();
        return key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1);
    }

    /** Předmět podle návrhu s id, které by v registru dostal. Registr nemění. */
    ItemDef toDef(ItemRegistry registry)
    {
        return registry.define(name, tile).withStack(stack()).withTool(tool, speed())
                .withDurability(durability());
    }

    /** Proč předmět nejde založit (anglicky, pro stavový řádek), nebo null. */
    String problem(ItemRegistry registry)
    {
        if(registry.isFull())
        {
            return "No free item id - the lab has used all "
                    + (ItemRegistry.LAST_ID - ItemRegistry.FIRST_ID + 1);
        }

        String invalid = ItemRegistry.validate(name, tile, stack(), tool, speed(), durability());

        if(invalid != null)
        {
            return invalid;
        }

        if(registry.hasName(name))
        {
            return "An item named " + name.trim() + " already exists";
        }

        // Jméno bloku taky ne - v receptech a v creative by nešly rozeznat.
        if(BlockRegistry.active().hasName(name) || BlockDraft.isBuiltinName(name))
        {
            return "A block named " + name.trim() + " already exists";
        }

        if(tile < ItemRegistry.BUILT_IN_TILES)
        {
            return "Tile " + tile + " belongs to built-in items - use New tile";
        }

        return null;
    }

    /**
     * Dlaždice pro nový obrázek: první, kterou nemá žádný předmět a ve které
     * nic není; když prázdná není, první nepoužitá (s nepoužitou malbou).
     * -1 = atlas předmětů je plný.
     */
    static int freeTile(ItemRegistry registry, int[] pixels, int except)
    {
        boolean[] used = registry.usedTiles();
        int fallback = -1;

        for(int t = ItemRegistry.BUILT_IN_TILES; t < ItemRegistry.TILES; t++)
        {
            if(used[t] || t == except)
            {
                continue;
            }

            if(Textures.tileEmpty(pixels, t))
            {
                return t;
            }

            if(fallback < 0)
            {
                fallback = t;
            }
        }

        return fallback;
    }
}
