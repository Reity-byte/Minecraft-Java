package mc;

/**
 * 3D model předmětu z jeho obrázku: pixely vytažené do tloušťky jednoho
 * pixelu, jako v Minecraftu. V ruce, na zemi i ve třetí osobě.
 *
 * ---------------------------------------------------------------------------
 * Model leží v souřadnicích BLOKU (0 až 1), obrázek v rovině x-y a tloušťka
 * kolem z = 0,5 - takže na něj platí tytéž matice jako na kostku bloku
 * (střed v 0,5; 0,5; 0,5) a volající nemusí rozlišovat.
 *
 *   přední a zadní stěna   po řádcích: souvislý běh plných pixelů je JEDEN
 *                          čtyřúhelník, ne jeden na pixel
 *   boky                   jen tam, kde vedle plného pixelu je průhledný
 *                          (nebo okraj dlaždice) - obrys předmětu
 *
 * ⚠️ PRŮHLEDNÉ PIXELY SE VŮBEC NEKRESLÍ, místo aby se zahazovaly ve shaderu.
 * Svět i ruka kreslí neprůhledně (alfa se tam ignoruje, viz World.isTranslucent)
 * - čtverec přes celou dlaždici by měl kolem klacku černé pozadí.
 *
 * Boky berou barvu STŘEDU svého pixelu (UV všech čtyř rohů v jednom bodě),
 * takže hrana má barvu pixelu, ze kterého vyrůstá.
 * ---------------------------------------------------------------------------
 *
 * Vrchol: pozice(3) + uv(2) + odstín(1). Nesahá na GL.
 */
public final class ItemModel {

    public static final int FLOATS_PER_VERTEX = 6;
    static final int FLOATS_PER_QUAD = 6 * FLOATS_PER_VERTEX;

    static final int TILE = BlockAtlas.TILE_PIXELS;

    /** Tloušťka: jeden pixel obrázku. */
    static final float THICKNESS = 1f / TILE;
    static final float Z_FRONT = 0.5f + THICKNESS / 2f;
    static final float Z_BACK = 0.5f - THICKNESS / 2f;

    /** Pixel s menší alfou je průhledný. */
    static final int ALPHA_CUTOFF = 128;

    // Odstíny jako u stěn bloku: přední plná, zadní jako bok Z, hrany jako boky.
    static final float SHADE_FRONT = 1.00f;
    static final float SHADE_BACK = 0.80f;
    static final float SHADE_TOP = 1.00f;
    static final float SHADE_BOTTOM = 0.50f;
    static final float SHADE_SIDE = 0.60f;

    /**
     * Nejvíc čtyřúhelníků: řádek má nejvýš 8 běhů (šachovnice), dva na běh,
     * a každý pixel nejvýš čtyři hrany.
     */
    static final int MAX_QUADS = TILE * (TILE / 2) * 2 + TILE * TILE * 4;
    public static final int MAX_FLOATS = MAX_QUADS * FLOATS_PER_QUAD;

    /** Rezerva UV od hrany texelu, ať nejbližší vzorek nesklouzne do souseda. */
    private static final float UV_EPSILON = 0.01f;

    private ItemModel() {}

    /** Je pixel dlaždice (x, y; y = 0 dole) plný? Mimo dlaždici = ne. */
    static boolean solid(int[] tile, int x, int y)
    {
        return x >= 0 && x < TILE && y >= 0 && y < TILE && (tile[y * TILE + x] >>> 24) >= ALPHA_CUTOFF;
    }

    /**
     * Postaví model do out od offsetu; vrací offset za posledním floatem.
     *
     * @param tile      pixely dlaždice (ItemTextures.tilePixels), řádek 0 dole
     * @param tileIndex dlaždice v atlasu předmětů - odtud UV
     */
    public static int build(int[] tile, int tileIndex, float[] out, int offset)
    {
        int at = offset;

        // Texel (x, y) dlaždice leží v atlasu na (baseX + x, baseY + y).
        float texel = 1f / BlockAtlas.ATLAS_PIXELS;
        float baseU = BlockAtlas.column(tileIndex) * TILE * texel;
        float baseV = BlockAtlas.row(tileIndex) * TILE * texel;

        for(int y = 0; y < TILE; y++)
        {
            float y0 = y / (float) TILE, y1 = (y + 1) / (float) TILE;
            float v0 = baseV + (y + UV_EPSILON) * texel, v1 = baseV + (y + 1 - UV_EPSILON) * texel;

            // Přední a zadní stěna po bězích plných pixelů.
            int x = 0;

            while(x < TILE)
            {
                if(!solid(tile, x, y))
                {
                    x++;
                    continue;
                }

                int start = x;

                while(x < TILE && solid(tile, x, y))
                {
                    x++;
                }

                float x0 = start / (float) TILE, x1 = x / (float) TILE;
                float u0 = baseU + (start + UV_EPSILON) * texel, u1 = baseU + (x - UV_EPSILON) * texel;

                // +Z, proti směru hodinových ručiček zepředu.
                at = quad(out, at,
                        x0, y0, Z_FRONT, u0, v0,
                        x1, y0, Z_FRONT, u1, v0,
                        x1, y1, Z_FRONT, u1, v1,
                        x0, y1, Z_FRONT, u0, v1, SHADE_FRONT);

                // -Z, zrcadlově - zezadu je obrázek vidět převráceně, jako v Minecraftu.
                at = quad(out, at,
                        x0, y0, Z_BACK, u0, v0,
                        x0, y1, Z_BACK, u0, v1,
                        x1, y1, Z_BACK, u1, v1,
                        x1, y0, Z_BACK, u1, v0, SHADE_BACK);
            }

            // Hrany kolem každého plného pixelu, kde je vedle prázdno.
            for(x = 0; x < TILE; x++)
            {
                if(!solid(tile, x, y))
                {
                    continue;
                }

                float x0 = x / (float) TILE, x1 = (x + 1) / (float) TILE;
                float u = baseU + (x + 0.5f) * texel, v = baseV + (y + 0.5f) * texel;

                if(!solid(tile, x - 1, y))   // -X
                {
                    at = quad(out, at,
                            x0, y0, Z_BACK, u, v,  x0, y0, Z_FRONT, u, v,
                            x0, y1, Z_FRONT, u, v, x0, y1, Z_BACK, u, v, SHADE_SIDE);
                }
                if(!solid(tile, x + 1, y))   // +X
                {
                    at = quad(out, at,
                            x1, y0, Z_BACK, u, v,  x1, y1, Z_BACK, u, v,
                            x1, y1, Z_FRONT, u, v, x1, y0, Z_FRONT, u, v, SHADE_SIDE);
                }
                if(!solid(tile, x, y + 1))   // +Y
                {
                    at = quad(out, at,
                            x0, y1, Z_BACK, u, v,  x0, y1, Z_FRONT, u, v,
                            x1, y1, Z_FRONT, u, v, x1, y1, Z_BACK, u, v, SHADE_TOP);
                }
                if(!solid(tile, x, y - 1))   // -Y
                {
                    at = quad(out, at,
                            x0, y0, Z_BACK, u, v,  x1, y0, Z_BACK, u, v,
                            x1, y0, Z_FRONT, u, v, x0, y0, Z_FRONT, u, v, SHADE_BOTTOM);
                }
            }
        }

        return at;
    }

    /** Čtyřúhelník jako dva trojúhelníky (a,b,c) + (a,c,d). */
    private static int quad(float[] out, int at,
                            float ax, float ay, float az, float au, float av,
                            float bx, float by, float bz, float bu, float bv,
                            float cx, float cy, float cz, float cu, float cv,
                            float dx, float dy, float dz, float du, float dv, float shade)
    {
        at = vertex(out, at, ax, ay, az, au, av, shade);
        at = vertex(out, at, bx, by, bz, bu, bv, shade);
        at = vertex(out, at, cx, cy, cz, cu, cv, shade);

        at = vertex(out, at, ax, ay, az, au, av, shade);
        at = vertex(out, at, cx, cy, cz, cu, cv, shade);
        at = vertex(out, at, dx, dy, dz, du, dv, shade);
        return at;
    }

    private static int vertex(float[] out, int at, float x, float y, float z, float u, float v, float shade)
    {
        out[at++] = x;
        out[at++] = y;
        out[at++] = z;
        out[at++] = u;
        out[at++] = v;
        out[at++] = shade;
        return at;
    }
}
