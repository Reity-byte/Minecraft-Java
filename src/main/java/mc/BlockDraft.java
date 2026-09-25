package mc;

import java.util.Arrays;

/**
 * Rozepsaný nový blok v texture labu - všechno kromě kreslení a vstupu.
 *
 * ---------------------------------------------------------------------------
 * Návrh drží jméno, tvrdost, obě vlastnosti a dlaždici pro každou stěnu.
 * Blok z něj vzniká přes BlockRegistry.define() s id, které by dostal - lab
 * si z toho staví dočasný registr, aby živý náhled ukazoval návrh přesně
 * tak, jak ho postaví hra. Do souboru jde teprve po Create.
 *
 * ⚠️ NOVÉ DLAŽDICE SE BEROU OD KONCE ATLASU (63, 62, ...). Dlaždice
 * vestavěných bloků přibývají v kódu od začátku (dnes končí na 26), takže
 * se ty dvě skupiny potkají až úplně na konci. Kdyby lab bral první volnou
 * buňku, další dlaždice přidaná do kódu by přistála přesně na té, kterou
 * už používá blok z labu. Stejná úvaha jako rozdělení id v BlockRegistry.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, takže jde celé otestovat headless.
 */
final class BlockDraft {

    /**
     * Tvrdosti na výběr, v sekundách jako World.hardness(): všechny, které
     * mají vestavěné bloky, a pár mezi nimi a nad nimi. Krokování po známých
     * hodnotách místo psaní čísla drží blok z labu na stejné škále - "1.8 s"
     * je poznat jako kámen.
     */
    static final float[] HARDNESS_STEPS = {0f, 0.05f, 0.2f, 0.5f, 0.8f, 1.0f, 1.5f, 1.8f, 2.5f, 3.0f, 4.0f, 5.0f, 10.0f};

    /** Výchozí tvrdost je hlína - nový blok jde vyzkoušet, aniž by se čekalo. */
    private static final int DEFAULT_STEP = 3;

    /** Pořadí stěn ve formuláři: vršek, bok, spodek - tak, jak jdou na bloku shora dolů. */
    static final int[] FACES = {BlockAtlas.FACE_TOP, BlockAtlas.FACE_SIDE, BlockAtlas.FACE_BOTTOM};

    String name = "";
    int hardnessStep = DEFAULT_STEP;
    boolean solid = true;
    boolean opaque = true;

    /** Dlaždice po stěnách; index je BlockAtlas.FACE_*. */
    final int[] tiles = new int[3];

    /** Stěna, které se přiřadí dlaždice vybraná v atlasu nebo nová dlaždice. */
    int activeFace = BlockAtlas.FACE_SIDE;

    /** Všechny stěny začínají na téže dlaždici - té, která je v labu zrovna vybraná. */
    BlockDraft(int startTile)
    {
        Arrays.fill(tiles, startTile);
    }

    float hardness()
    {
        return HARDNESS_STEPS[hardnessStep];
    }

    void harder()
    {
        hardnessStep = Math.min(HARDNESS_STEPS.length - 1, hardnessStep + 1);
    }

    void softer()
    {
        hardnessStep = Math.max(0, hardnessStep - 1);
    }

    /** Blok podle návrhu s id, které by teď v registru dostal. Registr nemění. */
    BlockDef toDef(BlockRegistry registry)
    {
        return registry.define(name, hardness(), solid, opaque,
                tiles[BlockAtlas.FACE_TOP], tiles[BlockAtlas.FACE_SIDE], tiles[BlockAtlas.FACE_BOTTOM]);
    }

    /** Proč blok nejde založit (anglicky, pro stavový řádek), nebo null. */
    String problem(BlockRegistry registry)
    {
        if(registry.isFull())
        {
            return "No free block id - the lab has used all "
                    + (BlockRegistry.LAST_ID - BlockRegistry.FIRST_ID + 1);
        }

        String invalid = BlockRegistry.validate(name, hardness(),
                tiles[BlockAtlas.FACE_TOP], tiles[BlockAtlas.FACE_SIDE], tiles[BlockAtlas.FACE_BOTTOM]);

        if(invalid != null)
        {
            return invalid;
        }

        if(registry.hasName(name) || isBuiltinName(name))
        {
            return "A block named " + name.trim() + " already exists";
        }

        return null;
    }

    /** Jmenuje se tak některý vestavěný blok? */
    static boolean isBuiltinName(String name)
    {
        String wanted = name.trim();

        for(int id = 1; id < BlockRegistry.FIRST_ID; id++)
        {
            if(isBuiltin((byte) id) && TextureLab.blockName((byte) id).equalsIgnoreCase(wanted))
            {
                return true;
            }
        }

        return false;
    }

    /** Vestavěný blok je ten, kterému BlockAtlas dá skutečnou dlaždici. */
    private static boolean isBuiltin(byte id)
    {
        return BlockAtlas.tile(id, BlockAtlas.FACE_SIDE) != BlockAtlas.TILE_UNKNOWN;
    }

    /**
     * Vestavěný blok se stejnou tvrdostí, ať je vidět, s čím se hodnota
     * srovnává ("1.8 s = Stone"). Prázdné, když žádný takový není.
     */
    static String hardnessLike(float hardness)
    {
        for(int id = 1; id < BlockRegistry.FIRST_ID; id++)
        {
            byte block = (byte) id;

            if(isBuiltin(block) && World.hardness(block) == hardness)
            {
                return TextureLab.blockName(block);
            }
        }

        return "";
    }

    /**
     * Buňka atlasu pro novou dlaždici: nejvyšší, kterou nepoužívá žádný
     * vestavěný blok, žádný blok z labu, praskliny, dlaždice "neznámý blok"
     * ani nic z reserved (stěny právě rozepsaného návrhu). -1 = atlas je plný.
     */
    static int freeTile(BlockRegistry registry, int... reserved)
    {
        boolean[] used = usedTiles(registry, reserved);

        for(int tile = used.length - 1; tile >= 0; tile--)
        {
            if(!used[tile])
            {
                return tile;
            }
        }

        return -1;
    }

    /**
     * Totéž, ale s ohledem na to, co v atlasu je: přednost má buňka, ve které
     * není ani jeden pixel. Až když žádná taková není, vrátí volnou buňku
     * s něčím namalovaným (volající to pak řekne - jde to vrátit Ctrl+Z).
     *
     * ⚠️ Dřív New tile tiše přepsal, co si uživatel namaloval do buňky, kterou
     * zatím žádný blok nepoužívá - třeba rozdělanou dlaždici na příští blok.
     */
    static int freeTileFor(BlockRegistry registry, int[] atlasPixels, int... reserved)
    {
        boolean[] used = usedTiles(registry, reserved);
        int painted = -1;

        for(int tile = used.length - 1; tile >= 0; tile--)
        {
            if(used[tile])
            {
                continue;
            }

            if(Textures.tileEmpty(atlasPixels, tile))
            {
                return tile;
            }

            if(painted < 0)
            {
                painted = tile;
            }
        }

        return painted;
    }

    /** Buňky, které nová dlaždice vzít nesmí - viz freeTile(). */
    private static boolean[] usedTiles(BlockRegistry registry, int... reserved)
    {
        boolean[] used = registry.usedTiles();

        for(int id = 1; id < BlockRegistry.FIRST_ID; id++)
        {
            byte block = (byte) id;

            if(isBuiltin(block))
            {
                for(int face : FACES)
                {
                    used[BlockAtlas.tile(block, face)] = true;
                }
            }
        }

        used[BlockAtlas.TILE_UNKNOWN] = true;

        for(int stage = 0; stage < BlockAtlas.CRACK_STAGES; stage++)
        {
            used[BlockAtlas.TILE_CRACK_FIRST + stage] = true;
        }

        for(int tile : reserved)
        {
            if(tile >= 0 && tile < used.length)
            {
                used[tile] = true;
            }
        }

        return used;
    }
}
