package mc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Úpravy pixelů atlasu bloků - všechno z texture labu, co nesahá na GL.
 *
 * ---------------------------------------------------------------------------
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
public final class AtlasEditor {

    public static final int TILE = BlockAtlas.TILE_PIXELS;
    public static final int SIZE = BlockAtlas.ATLAS_PIXELS;
    public static final int TILES_PER_ROW = BlockAtlas.TILES_PER_ROW;

    /** Kolik tahů jde vrátit. Tah je snímek jedné dlaždice, tedy 1 KB. */
    static final int UNDO_LIMIT = 100;

    /** Snímek dlaždice před tahem - na tom stojí undo. */
    private record Snapshot(int tile, int[] pixels) {}

    private final int[] pixels;
    private int tile = 0;
    private int color = 0xFF000000;

    /** Pixely se změnily a ještě se nenahrály na grafiku. */
    private boolean dirty = false;

    /** Změny od posledního uložení nebo načtení. */
    private boolean unsaved = false;

    private final Deque<Snapshot> undo = new ArrayDeque<>();

    private boolean stroking = false;
    private int lastX, lastY;

    public AtlasEditor(int[] atlasPixels)
    {
        if(atlasPixels.length != SIZE * SIZE)
        {
            throw new IllegalArgumentException("atlas ma " + atlasPixels.length + " pixelu");
        }

        this.pixels = atlasPixels;
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
    // stav
    // ------------------------------------------------------------------

    public int[] pixels() { return pixels; }
    public int tile()     { return tile; }
    public int color()    { return color; }
    public boolean isUnsaved() { return unsaved; }

    public void select(int tile)
    {
        if(tile >= 0 && tile < tileCount())
        {
            endStroke();
            this.tile = tile;
        }
    }

    public void setColor(int argb)
    {
        color = argb;
    }

    public int get(int x, int y)
    {
        return pixels[pixelIndex(tile, x, y)];
    }

    /** Vrátí true jednou po každé změně - pak se má atlas nahrát na grafiku. */
    public boolean takeDirty()
    {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    /** Po uložení: změny jsou na disku. */
    public void markSaved()
    {
        unsaved = false;
    }

    /**
     * Nahradí celý atlas (návrat k uloženému souboru nebo k procedurálnímu).
     * Undo se zahodí - vracelo by tahy na jiný obsah, než na jakém vznikly.
     */
    public void replaceAll(int[] newPixels)
    {
        endStroke();
        System.arraycopy(newPixels, 0, pixels, 0, pixels.length);
        undo.clear();
        dirty = true;
        unsaved = false;
    }

    // ------------------------------------------------------------------
    // malování
    // ------------------------------------------------------------------

    /**
     * Začátek tahu (zmáčknutí tlačítka). Před prvním pixelem se uloží snímek
     * dlaždice, takže celý tah - třeba dlouhá čára tažením - je JEDEN krok undo.
     */
    public void beginStroke(int x, int y)
    {
        endStroke();

        undo.push(new Snapshot(tile, copyTile(tile)));

        while(undo.size() > UNDO_LIMIT)
        {
            undo.removeLast();
        }

        stroking = true;
        lastX = x;
        lastY = y;
        paint(x, y);
    }

    /**
     * Tažení na další pixel. Maluje se ČÁRA od minulého pixelu, ne jen bod -
     * myš se za frame posune o několik pixelů plátna a bez čáry by po rychlém
     * tahu zůstala řada teček.
     */
    public void strokeTo(int x, int y)
    {
        if(!stroking || (x == lastX && y == lastY))
        {
            return;
        }

        for(int[] p : line(lastX, lastY, x, y))
        {
            paint(p[0], p[1]);
        }

        lastX = x;
        lastY = y;
    }

    public void endStroke()
    {
        stroking = false;
    }

    public boolean isStroking()
    {
        return stroking;
    }

    /** Vrátí poslední tah. Dlaždice se přepne na tu, ve které tah byl. */
    public boolean undo()
    {
        endStroke();
        Snapshot snapshot = undo.poll();

        if(snapshot == null)
        {
            return false;
        }

        tile = snapshot.tile();

        for(int y = 0; y < TILE; y++)
        {
            System.arraycopy(snapshot.pixels(), y * TILE, pixels, pixelIndex(tile, 0, y), TILE);
        }

        dirty = true;
        unsaved = true;
        return true;
    }

    public int undoDepth()
    {
        return undo.size();
    }

    /** Kapátko: vezme barvu pixelu jako aktuální. */
    public int pick(int x, int y)
    {
        color = get(x, y);
        return color;
    }

    private void paint(int x, int y)
    {
        if(x < 0 || y < 0 || x >= TILE || y >= TILE)
        {
            return;
        }

        int index = pixelIndex(tile, x, y);

        if(pixels[index] != color)
        {
            pixels[index] = color;
            dirty = true;
            unsaved = true;
        }
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
     * Pixely úsečky od (x0, y0) do (x1, y1) včetně obou konců - Bresenham.
     * Každý další pixel se od předchozího liší nejvýš o 1 v každé ose, takže
     * čára nemá mezery.
     */
    public static List<int[]> line(int x0, int y0, int x1, int y1)
    {
        List<int[]> points = new ArrayList<>();

        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;

        while(true)
        {
            points.add(new int[]{x0, y0});

            if(x0 == x1 && y0 == y1)
            {
                return points;
            }

            int e2 = 2 * error;

            if(e2 >= dy)
            {
                error += dy;
                x0 += sx;
            }

            if(e2 <= dx)
            {
                error += dx;
                y0 += sy;
            }
        }
    }

    // ------------------------------------------------------------------
    // dlaždice a bloky
    // ------------------------------------------------------------------

    /**
     * Nejčastější barvy vybrané dlaždice, nejvýš max. Paleta labu je z nich:
     * pixel-art se maluje odstíny, které v dlaždici už jsou, ne náhodnou
     * barvou z kola.
     */
    public int[] tileColors(int max)
    {
        Map<Integer, Integer> counts = new LinkedHashMap<>();

        for(int y = 0; y < TILE; y++)
        {
            for(int x = 0; x < TILE; x++)
            {
                counts.merge(get(x, y), 1, Integer::sum);
            }
        }

        return counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(max)
                .mapToInt(Map.Entry::getKey)
                .toArray();
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

            // Neznámé id dá TILE_UNKNOWN - to nejsou skutečné bloky.
            if(BlockAtlas.tile(block, BlockAtlas.FACE_SIDE) == BlockAtlas.TILE_UNKNOWN)
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

    /** Pro testy: kopie pixelů dlaždice po řádcích odspodu. */
    int[] tileSnapshot(int tile)
    {
        return copyTile(tile);
    }
}
