package mc;

import java.nio.file.Path;

import static org.lwjgl.opengl.GL33.GL_CLAMP_TO_EDGE;

/**
 * Atlas předmětů: obrázky klacku, uhlí a předmětů z labu.
 *
 * ---------------------------------------------------------------------------
 * STEJNÁ MŘÍŽKA JAKO ATLAS BLOKŮ (128 x 128, dlaždice 16 x 16, řádek 0
 * dole), takže platí tytéž funkce BlockAtlas.column/row/u0/v0 a lab na něj
 * pustí tentýž pixel editor (AtlasEditor). Oddělený je proto, že atlas
 * bloků má jen 64 dlaždic a lab ho ukazuje pixel na pixel - zvětšit ho by
 * rozbilo rozložení labu.
 *
 * Na rozdíl od bloků je tu PRŮHLEDNOST PODSTATNÁ: předmět je obrys na
 * průhledném pozadí. Ikona i 3D model (ItemModel) berou jen pixely s alfou.
 *
 * Stejný přepínač jako atlas.png: textures/items.png, když existuje,
 * jinak procedurálně. Prázdné dlaždice vestavěných předmětů se doplní,
 * prázdné dlaždice předmětů z labu dostanou šachovnici "chybí obrázek".
 * ---------------------------------------------------------------------------
 */
public final class ItemTextures {

    public static final Path FILE = GameDirs.path("textures", "items.png");

    public static final int SIZE = BlockAtlas.ATLAS_PIXELS;
    static final int TILE = BlockAtlas.TILE_PIXELS;

    private ItemTextures() {}

    /** Pixely atlasu předmětů pro hru a odkud přišly. */
    public static Textures.AtlasPixels pixels(Path file, ItemRegistry registry)
    {
        int[] fromFile = AtlasImage.load(file);

        if(fromFile != null)
        {
            complete(fromFile, registry);
            return new Textures.AtlasPixels(fromFile, true);
        }

        int[] pixels = procedural();
        complete(pixels, registry);
        return new Textures.AtlasPixels(pixels, false);
    }

    /** Doplní prázdné dlaždice: vestavěné procedurálně, z labu šachovnicí. */
    static void complete(int[] pixels, ItemRegistry registry)
    {
        int[] procedural = null;

        for(ItemDef def : ItemRegistry.builtIn())
        {
            if(Textures.tileEmpty(pixels, def.tile()))
            {
                if(procedural == null)
                {
                    procedural = procedural();
                }

                copyTile(procedural, pixels, def.tile());
            }
        }

        boolean[] used = registry.usedTiles();

        for(int tile = ItemRegistry.BUILT_IN_TILES; tile < used.length; tile++)
        {
            if(used[tile] && Textures.tileEmpty(pixels, tile))
            {
                fillTile(pixels, tile, Textures::unknown);
            }
        }
    }

    /** Atlas jako GL textura - CLAMP jako u bloků, ať UV nesáhne na protější kraj. */
    public static Texture texture(int[] pixels)
    {
        return Texture.fromArgb(pixels, SIZE, SIZE, GL_CLAMP_TO_EDGE);
    }

    /** Procedurální atlas: jen vestavěné předměty, zbytek průhledný. */
    static int[] procedural()
    {
        int[] pixels = new int[SIZE * SIZE];
        fillTile(pixels, ItemRegistry.TILE_STICK, ItemTextures::stick);
        fillTile(pixels, ItemRegistry.TILE_COAL, ItemTextures::coal);
        fillTile(pixels, ItemRegistry.TILE_IRON_INGOT, ItemTextures::ingot);
        return pixels;
    }

    /** Pixel dlaždice ze souřadnic uvnitř ní (y = 0 je dolní řádek). */
    private interface Texel {
        int at(int x, int y);
    }

    private static void fillTile(int[] pixels, int tile, Texel texel)
    {
        int originX = BlockAtlas.column(tile) * TILE;
        int originY = BlockAtlas.row(tile) * TILE;

        for(int y = 0; y < TILE; y++)
        {
            for(int x = 0; x < TILE; x++)
            {
                pixels[(originY + y) * SIZE + originX + x] = texel.at(x, y);
            }
        }
    }

    private static void copyTile(int[] from, int[] to, int tile)
    {
        int originX = BlockAtlas.column(tile) * TILE;
        int originY = BlockAtlas.row(tile) * TILE;

        for(int y = 0; y < TILE; y++)
        {
            for(int x = 0; x < TILE; x++)
            {
                int index = (originY + y) * SIZE + originX + x;
                to[index] = from[index];
            }
        }
    }

    /** Pixely jedné dlaždice (16 x 16, řádek 0 dole) - pro 3D model předmětu. */
    public static int[] tilePixels(int[] atlas, int tile)
    {
        int[] out = new int[TILE * TILE];
        int originX = BlockAtlas.column(tile) * TILE;
        int originY = BlockAtlas.row(tile) * TILE;

        for(int y = 0; y < TILE; y++)
        {
            System.arraycopy(atlas, (originY + y) * SIZE + originX, out, y * TILE, TILE);
        }

        return out;
    }

    // ------------------------------------------------------------------
    // procedurální obrázky
    // ------------------------------------------------------------------

    private static final int CLEAR = 0x00000000;

    /**
     * Klacek: šikmá tyčka zleva dole doprava nahoru, dva pixely široká,
     * světlejší horní hrana a tmavší spodní - jako v Minecraftu.
     */
    private static int stick(int x, int y)
    {
        // Úhlopříčka x == y od (3,2) do (12,13); tyčka je pás x - y v {0, 1}.
        int d = x - y;

        if(x < 3 || x > 13 || y < 2 || y > 13 || (d != 0 && d != 1))
        {
            return CLEAR;
        }

        boolean knot = (x + y) % 7 == 0;
        return d == 0 ? (knot ? 0xFF7A5A2E : 0xFF8F6A3A) : (knot ? 0xFF4E3719 : 0xFF5E4220);
    }

    /** Uhlí: nepravidelná hrouda s lesklými ploškami. */
    private static int coal(int x, int y)
    {
        double dx = (x - 7.5) / 6.0, dy = (y - 7.5) / 5.2;
        double r = dx * dx + dy * dy + ((hash(x, y) & 7) - 3.5) * 0.02;

        if(r > 1.0)
        {
            return CLEAR;
        }

        int tone = hash(x / 2, y / 2) & 3;
        if(r > 0.8)
        {
            return 0xFF141414;                   // obrys
        }
        if(tone == 0 && y > 7)
        {
            return 0xFF4A4A52;                   // odlesk
        }
        return tone == 1 ? 0xFF262628 : 0xFF1C1C1E;
    }

    /**
     * Železný ingot: šikmá cihlička (lichoběžník) se světlou horní plochou,
     * tmavším bokem a obrysem - jako ingot v Minecraftu.
     */
    private static int ingot(int x, int y)
    {
        // Horní plocha y 7..9, bok y 4..6; zkosení o jeden pixel na řádek.
        if(y >= 7 && y <= 9 && x >= 3 + (9 - y) && x <= 12 - (y - 7))
        {
            return (x + y) % 5 == 0 ? 0xFFF4F4F4 : 0xFFD8D8D8;
        }
        if(y >= 4 && y <= 6 && x >= 2 && x <= 13)
        {
            return y == 4 ? 0xFF5E5E5E : 0xFF9A9A9A;
        }
        if(y == 10 && x >= 5 && x <= 10)
        {
            return 0xFF5E5E5E;
        }
        return CLEAR;
    }

    private static int hash(int x, int y)
    {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) >>> 1;
    }
}
