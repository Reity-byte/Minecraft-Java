package mc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Úpravy pixelů atlasu bloků - všechno z texture labu, co nesahá na GL.
 *
 * ---------------------------------------------------------------------------
 * Malování, barva, undo a sledování změn jsou v PixelEditor - sdílí je
 * s editorem kůže postavy (SkinEditor). Tady zůstává jen to, co je vlastní
 * atlasu: mřížka dlaždic, mapování dlaždice na bloky a barvy celého atlasu.
 *
 * ⚠️ Editor pracuje PŘÍMO NA POLI, ze kterého je nahraná textura atlasu, ne
 * na kopii. Lab po každé změně to samé pole nahraje do té samé textury, takže
 * změnu vidí náhledová kostka, ikony v hotbaru i svět za labem v tom samém
 * framu. Neuložené úpravy zůstanou ve hře až do jejího ukončení; na disk
 * je dostane teprve uložení.
 *
 * Souřadnice pixelu v dlaždici jsou x doprava a y NAHORU, 0 až 15 - stejně
 * jako řádky atlasu (řádek 0 dole, jak ho čte GL). Plátno v labu je proto
 * kreslené s řádkem 0 dole a dlaždice v něm stojí stejně jako na boku bloku.
 * ---------------------------------------------------------------------------
 */
public final class AtlasEditor extends PixelEditor {

    public static final int TILE = BlockAtlas.TILE_PIXELS;
    public static final int SIZE = BlockAtlas.ATLAS_PIXELS;
    public static final int TILES_PER_ROW = BlockAtlas.TILES_PER_ROW;

    private int tile = 0;

    public AtlasEditor(int[] atlasPixels)
    {
        super(atlasPixels, SIZE);
    }

    // ------------------------------------------------------------------
    // oblast = dlaždice
    // ------------------------------------------------------------------

    @Override public int regionWidth()  { return TILE; }
    @Override public int regionHeight() { return TILE; }

    @Override public int index(int x, int y)
    {
        return pixelIndex(tile, x, y);
    }

    @Override protected int region()
    {
        return tile;
    }

    @Override protected void selectRegion(int region)
    {
        tile = region;
    }

    public int tile()
    {
        return tile;
    }

    public void select(int tile)
    {
        if(tile >= 0 && tile < tileCount())
        {
            endStroke();
            this.tile = tile;
        }
    }

    // ------------------------------------------------------------------
    // souřadnice
    // ------------------------------------------------------------------

    /** Levý dolní pixel dlaždice v atlasu. Stejné sloupce a řádky jako BlockAtlas. */
    public static int tileX0(int tile) { return BlockAtlas.column(tile) * TILE; }
    public static int tileY0(int tile) { return BlockAtlas.row(tile) * TILE; }

    /** Index pixelu (x, y) dlaždice v poli atlasu. y = 0 je dolní řádek. */
    public static int pixelIndex(int tile, int x, int y)
    {
        return (tileY0(tile) + y) * SIZE + tileX0(tile) + x;
    }

    /** Dlaždice, do které patří pixel atlasu, nebo -1 mimo atlas. */
    public static int tileAt(int atlasX, int atlasY)
    {
        if(atlasX < 0 || atlasY < 0 || atlasX >= SIZE || atlasY >= SIZE)
        {
            return -1;
        }

        return (atlasY / TILE) * TILES_PER_ROW + atlasX / TILE;
    }

    public static int tileCount()
    {
        return TILES_PER_ROW * TILES_PER_ROW;
    }

    // ------------------------------------------------------------------
    // dlaždice
    // ------------------------------------------------------------------

    /** Import hotového atlasu - jen jméno, které zná zbytek labu a testy. */
    public void importAtlas(int[] newPixels)
    {
        importImage(newPixels);
    }

    /**
     * Zkopíruje obsah dlaždice do jiné (výchozí obsah nové dlaždice bloku).
     * Jde vrátit jako tah - cílová buňka mohla mít namalované něco svého.
     */
    public void copyTile(int source, int target)
    {
        endStroke();

        int was = tile;
        tile = target;
        pushUndo(snapshot());

        int[] content = copyTile(source);

        for(int y = 0; y < TILE; y++)
        {
            System.arraycopy(content, y * TILE, pixels, pixelIndex(target, 0, y), TILE);
        }

        markRegionDirty();
        touched();
        tile = was;
    }

    private int[] copyTile(int tile)
    {
        int[] copy = new int[TILE * TILE];

        for(int y = 0; y < TILE; y++)
        {
            System.arraycopy(pixels, pixelIndex(tile, 0, y), copy, y * TILE, TILE);
        }

        return copy;
    }

    /**
     * Nejčastější barvy vybrané dlaždice, nejvýš max. Paleta labu je z nich:
     * pixel-art se maluje odstíny, které v dlaždici už jsou, ne náhodnou
     * barvou z kola.
     */
    public int[] tileColors(int max)
    {
        return regionColors(max);
    }

    /** Pro testy: kopie pixelů dlaždice po řádcích odspodu. */
    int[] tileSnapshot(int tile)
    {
        return copyTile(tile);
    }

    // ------------------------------------------------------------------
    // barvy celého atlasu (globální paleta)
    //
    // Paleta dlaždice ukazuje jen odstíny té jedné dlaždice. Na sjednocení
    // odstínů NAPŘÍČ bloky - aby hlína, bok trávy a nový blok neměly tři
    // skoro stejné hnědé - je potřeba vidět barvy celého atlasu vedle sebe.
    // Všechno tady jsou čisté funkce nad polem pixelů, bez stavu editoru.
    // ------------------------------------------------------------------

    /** Pod touhle sytostí je barva "šedá" a odstín u ní nic neznamená. */
    static final float GRAY_SATURATION = 0.12f;

    /**
     * Barvy použité KDEKOLIV v atlasu, nejčastější první, nejvýš max. Při
     * shodném počtu rozhoduje, která se v poli objevila dřív, takže výsledek
     * je pro tentýž atlas pokaždé stejný.
     *
     * Plně průhledné pixely se nepočítají: jsou to hlavně prázdné buňky
     * atlasu a guma je v pevném řádku palety.
     */
    public static int[] atlasColors(int[] atlasPixels, int max)
    {
        return topColors(atlasPixels, max, false);
    }

    /**
     * Seřadí barvy tak, aby PODOBNÉ ODSTÍNY LEŽELY VEDLE SEBE: nejdřív šedé
     * od tmavé ke světlé, pak po výsečích odstínu (12 po 30°) a v každé
     * od tmavé ke světlé. Dvě skoro stejné hnědé z různých bloků tak skončí
     * hned u sebe a je vidět, že jde o dvě barvy místo jedné. Podle četnosti
     * by byly rozházené po celém řádku.
     */
    public static int[] byHue(int[] colors)
    {
        // ⚠️ HSV se počítá JEDNOU pro každou barvu, ne uvnitř porovnávače.
        // Tam by se pro n barev volalo toHsv() řádově n*log(n) krát a každé
        // volání navíc alokuje pole tří floatů.
        int n = colors.length;
        int[] group = new int[n];
        float[] value = new float[n];
        float[] saturation = new float[n];
        Integer[] order = new Integer[n];

        for(int i = 0; i < n; i++)
        {
            float[] hsv = toHsv(colors[i]);
            group[i] = hsv[1] < GRAY_SATURATION ? -1 : Math.min(11, (int) (hsv[0] / 30f));
            saturation[i] = hsv[1];
            value[i] = hsv[2];
            order[i] = i;
        }

        Arrays.sort(order, (a, b) -> group[a] != group[b] ? Integer.compare(group[a], group[b])
                : value[a] != value[b] ? Float.compare(value[a], value[b])
                : Float.compare(saturation[a], saturation[b]));

        int[] result = new int[n];

        for(int i = 0; i < n; i++)
        {
            result[i] = colors[order[i]];
        }

        return result;
    }

    /** -1 pro šedé, jinak výseč odstínu 0 až 11. */
    static int hueGroup(int argb)
    {
        float[] hsv = toHsv(argb);

        if(hsv[1] < GRAY_SATURATION)
        {
            return -1;
        }

        return Math.min(11, (int) (hsv[0] / 30f));
    }

    /** Které dlaždice barvu obsahují (index = dlaždice) - lab je při najetí na vzorek zvýrazní. */
    public static boolean[] tilesWithColor(int[] atlasPixels, int argb)
    {
        boolean[] found = new boolean[tileCount()];

        for(int i = 0; i < atlasPixels.length; i++)
        {
            if(atlasPixels[i] == argb)
            {
                found[tileAt(i % SIZE, i / SIZE)] = true;
            }
        }

        return found;
    }

    /**
     * Bloky, které dlaždici používají, podle toho, na kolika stěnách - první
     * je ten, komu dlaždice patří nejvíc (hlína je hlína na šesti stěnách,
     * tráva ji má jen zespodu). Prázdné pro praskliny a volné buňky.
     *
     * Mapování je čistě BlockAtlas.tile() - lab ho nemění, jen čte.
     */
    public static List<Byte> blocksUsing(int tile)
    {
        List<byte[]> found = new ArrayList<>();   // {blok, počet stěn}

        for(int id = 1; id < 128; id++)
        {
            byte block = (byte) id;

            // Neznámé id dá TILE_UNKNOWN - to nejsou skutečné bloky. Blok
            // z labu je skutečný, i kdyby si tu křiklavou dlaždici vybral.
            if(BlockAtlas.tile(block, BlockAtlas.FACE_SIDE) == BlockAtlas.TILE_UNKNOWN
                    && BlockRegistry.lookup(block) == null)
            {
                continue;
            }

            int faces = (BlockAtlas.tile(block, BlockAtlas.FACE_TOP) == tile ? 1 : 0)
                    + (BlockAtlas.tile(block, BlockAtlas.FACE_BOTTOM) == tile ? 1 : 0)
                    + (BlockAtlas.tile(block, BlockAtlas.FACE_SIDE) == tile ? 4 : 0);

            if(faces > 0)
            {
                found.add(new byte[]{block, (byte) faces});
            }
        }

        found.sort((a, b) -> b[1] - a[1]);

        List<Byte> blocks = new ArrayList<>();
        for(byte[] entry : found)
        {
            blocks.add(entry[0]);
        }

        return blocks;
    }

    // ------------------------------------------------------------------
    // barvy
    // ------------------------------------------------------------------

    /**
     * Barva z hexa: "RRGGBB" (plně krycí) nebo "AARRGGBB", s # nebo bez.
     * Na cokoliv jiného vrací null.
     */
    public static Integer parseHex(String text)
    {
        String hex = text.trim();

        if(hex.startsWith("#"))
        {
            hex = hex.substring(1);
        }

        if(hex.length() != 6 && hex.length() != 8)
        {
            return null;
        }

        try
        {
            long value = Long.parseLong(hex, 16);
            return hex.length() == 6 ? (int) (0xFF000000L | value) : (int) value;
        }
        catch(NumberFormatException e)
        {
            return null;
        }
    }

    /** "#AARRGGBB" - s alfou vždycky, aby šla vidět průhlednost vody. */
    public static String toHex(int argb)
    {
        return String.format("#%08X", argb);
    }

    /** HSV (odstín 0-360, sytost a jas 0-1) a alfa 0-255 na 0xAARRGGBB. */
    public static int hsv(float hue, float saturation, float value, int alpha)
    {
        float h = ((hue % 360f) + 360f) % 360f / 60f;
        float c = value * saturation;
        float x = c * (1f - Math.abs(h % 2f - 1f));
        float m = value - c;

        float r, g, b;
        switch((int) h)
        {
            case 0  -> { r = c; g = x; b = 0; }
            case 1  -> { r = x; g = c; b = 0; }
            case 2  -> { r = 0; g = c; b = x; }
            case 3  -> { r = 0; g = x; b = c; }
            case 4  -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }

        return (alpha & 0xFF) << 24
                | Math.round((r + m) * 255) << 16
                | Math.round((g + m) * 255) << 8
                | Math.round((b + m) * 255);
    }

    /** 0xAARRGGBB na {odstín 0-360, sytost 0-1, jas 0-1}. */
    public static float[] toHsv(int argb)
    {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;

        float hue;
        if(delta == 0f)      hue = 0f;
        else if(max == r)    hue = 60f * (((g - b) / delta) % 6f);
        else if(max == g)    hue = 60f * ((b - r) / delta + 2f);
        else                 hue = 60f * ((r - g) / delta + 4f);

        return new float[]{(hue + 360f) % 360f, max == 0f ? 0f : delta / max, max};
    }

}
