package mc;

/**
 * Mapování blok + stěna → dlaždice v atlasu a její UV souřadnice.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ TAHLE TŘÍDA NESMÍ SAHAT NA GL. Je to čistá tabulka a aritmetika, protože
 * ji používá ChunkMesh.build(), který musí zůstat testovatelný headless.
 * Samotná textura atlasu žije ve WorldRendereru, ne tady - data a jejich
 * nahrání na grafiku jsou schválně oddělené.
 * ---------------------------------------------------------------------------
 *
 * Atlas je mřížka 8x8 dlaždic po 16x16 pixelech, tedy 128x128. Dlaždice se
 * číslují po řádcích od nuly; řádek 0 je DOLNÍ, protože OpenGL má počátek
 * textury vlevo dole a generovaná data jsou v tomhle pořadí (viz Texture).
 */
public final class BlockAtlas {

    /**
     * Mřížka 8x8, i když je zaplněná zhruba z poloviny. Rozšíření později by
     * znamenalo přepočítat všechna UV - a to je přesně ta změna, u které se
     * snadno zapomene na jednu dlaždici.
     */
    public static final int TILES_PER_ROW = 8;
    public static final int TILE_PIXELS   = 16;
    public static final int ATLAS_PIXELS  = TILES_PER_ROW * TILE_PIXELS;

    /** Která stěna bloku se kreslí. Boky mají všechny stejnou texturu. */
    public static final int FACE_TOP    = 0;
    public static final int FACE_BOTTOM = 1;
    public static final int FACE_SIDE   = 2;

    /**
     * Boky podle směru. Rozlišují je jen bloky s čelem (pec); všem ostatním
     * je každý z nich prostě FACE_SIDE. Mesher, ikona i položka si je berou
     * zvlášť, takže čelo pece je jen na jedné stěně.
     */
    public static final int FACE_EAST  = 3;   // +X
    public static final int FACE_WEST  = 4;   // -X
    public static final int FACE_SOUTH = 5;   // +Z
    public static final int FACE_NORTH = 6;   // -Z

    // Indexy dlaždic v atlasu. Přidat blok = nová dlaždice tady,
    // její vykreslení v Textures.blockAtlasPixels() a case v tile().
    // ⚠️ Nové vestavěné dlaždice přidávat ODSPODU (27, 28, ...): texture lab
    // přiděluje buňky blokům z labu od konce atlasu (63, 62, ...) - viz BlockDraft.
    public static final int TILE_GRASS_TOP  = 0;
    public static final int TILE_GRASS_SIDE = 1;
    public static final int TILE_DIRT       = 2;
    public static final int TILE_STONE      = 3;
    public static final int TILE_SAND       = 4;
    public static final int TILE_PLANKS     = 5;
    public static final int TILE_COAL_ORE   = 6;
    public static final int TILE_IRON_ORE   = 7;
    public static final int TILE_WATER      = 8;
    public static final int TILE_TABLE_TOP  = 9;
    public static final int TILE_TABLE_SIDE = 10;
    public static final int TILE_BRICKS     = 11;
    public static final int TILE_LOG_TOP    = 12;
    public static final int TILE_LOG_SIDE   = 13;
    public static final int TILE_LEAVES     = 14;
    public static final int TILE_TORCH      = 16;

    /**
     * Deset stádií prasklin, za sebou v atlasu. Kreslí se jako druhá vrstva
     * přes rozbíjený blok, ne jako jeho textura.
     */
    public static final int TILE_CRACK_FIRST = 17;
    public static final int CRACK_STAGES = 10;

    /** Křiklavá dlaždice pro "zapomněls case" a neznámý blok - šachovnice, ať je chyba vidět. */
    public static final int TILE_UNKNOWN    = 15;

    // Dlaždice biomů. Leží ZA prasklinami (od 27), protože nové vestavěné
    // dlaždice se berou odspodu a lab si bere buňky od konce atlasu (63, 62...).
    public static final int TILE_SNOW             = 27;
    public static final int TILE_BIRCH_LOG_SIDE   = 28;
    public static final int TILE_BIRCH_LOG_TOP    = 29;
    public static final int TILE_BIRCH_LEAVES     = 30;
    public static final int TILE_SPRUCE_LOG_SIDE  = 31;
    public static final int TILE_SPRUCE_LOG_TOP   = 32;
    public static final int TILE_SPRUCE_LEAVES    = 33;
    public static final int TILE_JUNGLE_LEAVES    = 34;

    public static final int TILE_FURNACE_FRONT = 35;
    public static final int TILE_FURNACE_SIDE  = 36;
    public static final int TILE_FURNACE_TOP   = 37;
    public static final int TILE_COUNT = TILE_FURNACE_TOP + 1;

    /**
     * Půl texelu dovnitř dlaždice.
     *
     * ⚠️ Bez tohohle vzniká na hranách bloků prosakování sousední dlaždice.
     * UV pravého okraje dlaždice je totiž totožné s UV levého okraje té další,
     * a interpolace přes stěnu na té hodnotě klidně skončí - vzorkování pak
     * sáhne o texel vedle a na hraně bloku se objeví proužek cizí textury.
     * Zúžením o půl texelu se rozsah zastaví přesně ve STŘEDU krajních texelů,
     * takže při GL_NEAREST je pořád dostupných všech 16, ale mimo dlaždici
     * se sáhnout nedá.
     */
    private static final float INSET = 0.5f / ATLAS_PIXELS;

    private BlockAtlas() {}

    /**
     * face je jedna z konstant FACE_*.
     *
     * Bloky z texture labu (id od BlockRegistry.FIRST_ID) mají dlaždice
     * v datech - v textures/blocks.json, ne v tomhle switchi.
     */
    public static int tile(byte blockId, int face)
    {
        if(World.isFurnace(blockId))
        {
            return switch(face)
            {
                case FACE_TOP, FACE_BOTTOM -> TILE_FURNACE_TOP;
                default -> face == World.furnaceFront(blockId) ? TILE_FURNACE_FRONT : TILE_FURNACE_SIDE;
            };
        }

        // Směrový bok je pro všechny ostatní bloky obyčejný bok.
        if(face >= FACE_EAST)
        {
            face = FACE_SIDE;
        }

        if(blockId >= BlockRegistry.FIRST_ID)
        {
            BlockDef custom = BlockRegistry.lookup(blockId);
            return custom != null ? custom.tile(face) : TILE_UNKNOWN;
        }

        return switch(blockId)
        {
            case World.GRASS -> switch(face)
            {
                case FACE_TOP    -> TILE_GRASS_TOP;
                // Spodek travnatého bloku je hlína - koukáš na něj zespoda.
                case FACE_BOTTOM -> TILE_DIRT;
                default          -> TILE_GRASS_SIDE;
            };
            case World.DIRT   -> TILE_DIRT;
            case World.STONE  -> TILE_STONE;
            case World.SAND   -> TILE_SAND;
            case World.PLANKS   -> TILE_PLANKS;
            case World.COAL_ORE -> TILE_COAL_ORE;
            case World.IRON_ORE -> TILE_IRON_ORE;
            case World.WATER    -> TILE_WATER;
            case World.STONE_BRICKS -> TILE_BRICKS;
            case World.LEAVES -> TILE_LEAVES;
            case World.SNOW   -> TILE_SNOW;
            case World.BIRCH_LEAVES  -> TILE_BIRCH_LEAVES;
            case World.SPRUCE_LEAVES -> TILE_SPRUCE_LEAVES;
            case World.JUNGLE_LEAVES -> TILE_JUNGLE_LEAVES;
            // Bříza i smrk mají letokruhy na řezu a kůru z boku, stejně jako dub.
            case World.BIRCH_LOG  -> face == FACE_SIDE ? TILE_BIRCH_LOG_SIDE : TILE_BIRCH_LOG_TOP;
            case World.SPRUCE_LOG -> face == FACE_SIDE ? TILE_SPRUCE_LOG_SIDE : TILE_SPRUCE_LOG_TOP;
            case World.TORCH -> TILE_TORCH;
            // Plot je ze dřeva, takže si prostě bere dlaždici prken.
            case World.FENCE -> TILE_PLANKS;
            // Kmen má letokruhy na řezu a kůru z boku - třetí blok s rozdílem
            // mezi vrškem a boky, po trávě a crafting table.
            case World.LOG -> face == FACE_SIDE ? TILE_LOG_SIDE : TILE_LOG_TOP;
            // Crafting table je druhý blok po trávě, který se shora liší od boků.
            case World.CRAFTING_TABLE -> face == FACE_TOP ? TILE_TABLE_TOP : TILE_TABLE_SIDE;
            default           -> TILE_UNKNOWN;
        };
    }

    public static int column(int tile) { return tile % TILES_PER_ROW; }
    public static int row(int tile)    { return tile / TILES_PER_ROW; }

    public static float u0(int tile) { return edge(column(tile))     + INSET; }
    public static float u1(int tile) { return edge(column(tile) + 1) - INSET; }
    public static float v0(int tile) { return edge(row(tile))        + INSET; }
    public static float v1(int tile) { return edge(row(tile) + 1)    - INSET; }

    private static float edge(int tileIndex)
    {
        return tileIndex * (float) TILE_PIXELS / ATLAS_PIXELS;
    }
}
