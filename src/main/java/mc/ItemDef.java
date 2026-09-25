package mc;

import java.util.Locale;

/**
 * Předmět: věc v inventáři, která není blok - nedá se položit (klacek,
 * uhlí, nástroj). Jen data, žádný kód.
 *
 * ---------------------------------------------------------------------------
 * Obrázek je dlaždice v atlasu předmětů (ItemTextures, textures/items.png),
 * se STEJNOU mřížkou jako atlas bloků. V ruce a na zemi se z něj dělá 3D
 * model vytažením pixelů do tloušťky (ItemModel), v inventáři je plochý.
 *
 * Vestavěné předměty (klacek, uhlí) jsou v ItemRegistry v kódu, předměty
 * z labu v textures/items.json. Obojí má tentýž tvar.
 * ---------------------------------------------------------------------------
 *
 * @param id        id z rozsahu Items (256 a výš)
 * @param tile      dlaždice v atlasu předmětů
 * @param maxStack  kolik se vejde do slotu, 1 až ItemStack.MAX_COUNT (nástroj 1)
 * @param tool      druh nástroje, NONE = obyčejný předmět
 * @param toolSpeed  kolikrát rychleji nástroj těží materiál, na který je (1 = jako ruka)
 * @param durability kolik bloků předmět vykope, než praskne; 0 = nerozbitný.
 *                   Předmět s výdrží se nestackuje (maxStack 1) - poškození
 *                   nese hromádka (ItemStack.damage) a dvě různě opotřebené
 *                   by nešly slít.
 */
public record ItemDef(int id, String name, int tile, int maxStack, Tool tool, float toolSpeed,
                      int durability) {

    /**
     * Druh nástroje a materiál (Sound.Material), na který je. Stejné
     * rozdělení materiálů jako u zvuků a tvrdosti: co zní jako kámen,
     * kope se krumpáčem.
     */
    public enum Tool {
        NONE(null),
        PICKAXE(Sound.Material.STONE),
        AXE(Sound.Material.WOOD),
        SHOVEL(Sound.Material.EARTH);

        /** Na co nástroj je, nebo null (NONE). */
        public final Sound.Material material;

        Tool(Sound.Material material)
        {
            this.material = material;
        }

        /** Jméno v items.json a v labu: "pickaxe". */
        public String key()
        {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Nástroj podle jména z items.json, nebo null, když ho nezná. */
        public static Tool byKey(String key)
        {
            for(Tool tool : values())
            {
                if(tool.key().equals(key))
                {
                    return tool;
                }
            }

            return null;
        }
    }

    /** Nejrychlejší nástroj. Minecraftí zlatý krumpáč je 12x, diamantový 8x. */
    public static final float MAX_TOOL_SPEED = 16f;

    /** Nejvyšší výdrž. Minecraftí netheritový nástroj má 2031; short v uloženém světě unese víc. */
    public static final int MAX_DURABILITY = 9999;

    /** Obyčejný předmět: stack 64, žádný nástroj. */
    public static ItemDef plain(int id, String name, int tile)
    {
        return new ItemDef(id, name, tile, ItemStack.MAX_COUNT, Tool.NONE, 1f, 0);
    }

    public boolean isTool()
    {
        return tool != Tool.NONE;
    }

    /** Opotřebuje se (má výdrž)? */
    public boolean wears()
    {
        return durability > 0;
    }

    public ItemDef withName(String newName)
    {
        return new ItemDef(id, newName, tile, maxStack, tool, toolSpeed, durability);
    }

    public ItemDef withTile(int newTile)
    {
        return new ItemDef(id, name, newTile, maxStack, tool, toolSpeed, durability);
    }

    public ItemDef withStack(int newMaxStack)
    {
        return new ItemDef(id, name, tile, newMaxStack, tool, toolSpeed, durability);
    }

    public ItemDef withTool(Tool newTool, float newSpeed)
    {
        return new ItemDef(id, name, tile, maxStack, newTool, newSpeed, durability);
    }

    public ItemDef withDurability(int newDurability)
    {
        return new ItemDef(id, name, tile, maxStack, tool, toolSpeed, newDurability);
    }
}
