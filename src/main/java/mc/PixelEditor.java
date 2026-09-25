package mc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Malování do pole pixelů: barva, tah štětcem, undo, sledování změn.
 * Společný základ editoru atlasu bloků a editoru kůže postavy.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ PROČ SPOLEČNÝ ZÁKLAD, A NE DVĚ TŘÍDY. Texture lab maluje do dvou úplně
 * různých obrázků - atlas bloků 128x128 v mřížce dlaždic a kůže postavy
 * 64x64 v rozbalení kvádrů z Minecraftu - ale plátno, paleta, kapátko, undo
 * i import PNG jsou pro obě stejné. Kopie téhož kódu by znamenala, že se
 * oprava malování (a hlavně jeho zrychlení, viz DirtyRect) musí udělat
 * dvakrát a jednou na to někdo zapomene.
 *
 * ⚠️ EDITOR PRACUJE PŘÍMO NA POLI, ze kterého je nahraná textura, ne na
 * kopii. Lab po každé změně nahraje změněný obdélník do TÉŽE textury, takže
 * změnu vidí náhled i svět za labem v tom samém framu.
 *
 * ⚠️ OBLAST ("region") je to, co je zrovna na plátně: u atlasu jedna
 * dlaždice 16x16, u kůže jedna stěna dílu těla (8x8 obličej, 4x12 bok ruky).
 * Souřadnice v oblasti jsou x doprava a y NAHORU, y = 0 je DOLNÍ řádek
 * plátna - u obou obrázků stejně, ať se lab nemusí ptát, s čím pracuje.
 * Kam ten pixel doopravdy patří v poli, ví jen potomek (index()).
 * ---------------------------------------------------------------------------
 */
public abstract class PixelEditor {

    /** Kolik tahů jde vrátit. */
    static final int UNDO_LIMIT = 100;

    /** Snímek oblasti před tahem, nebo celého obrázku (region = WHOLE_IMAGE). */
    record Snapshot(int region, int width, int height, int[] pixels) {}

    static final int WHOLE_IMAGE = -1;

    protected final int[] pixels;

    /** Obrázek je čtvercový - atlas 128, kůže 64. */
    public final int size;

    private int color = 0xFF000000;

    /**
     * Které pixely se změnily a ještě se nenahrály na grafiku.
     *
     * ⚠️ Obdélník, ne příznak. Nahrávat při každém namalovaném pixelu celou
     * texturu znamená u atlasu 64 KB kvůli čtyřem bajtům. Viz DirtyRect
     * a Texture.updateRegion().
     */
    private final DirtyRect dirty = new DirtyRect();

    /** Změny od posledního uložení nebo načtení. */
    private boolean unsaved = false;

    /**
     * Počítadlo změn pixelů. Na rozdíl od dirty ho nikdo nenuluje - kdo si
     * z pixelů něco počítá (paleta), pozná podle něj, že je čas přepočítat,
     * a nemusí to dělat každý frame.
     */
    private int revision = 0;

    private final Deque<Snapshot> undo = new ArrayDeque<>();

    private boolean stroking = false;
    private int lastX, lastY;

    protected PixelEditor(int[] imagePixels, int size)
    {
        if(imagePixels.length != size * size)
        {
            throw new IllegalArgumentException("obrazek ma " + imagePixels.length
                    + " pixelu, ceka se " + (size * size));
        }

        this.pixels = imagePixels;
        this.size = size;
    }

    // ------------------------------------------------------------------
    // co musí dodat potomek
    // ------------------------------------------------------------------

    /** Šířka právě editované oblasti v pixelech. */
    public abstract int regionWidth();

    /** Výška právě editované oblasti v pixelech. */
    public abstract int regionHeight();

    /** Index pixelu (x, y) oblasti v poli obrázku. y = 0 je DOLNÍ řádek plátna. */
    public abstract int index(int x, int y);

    /** Které oblasti (dlaždice, stěna) se tah týká - klíč pro undo. */
    protected abstract int region();

    /** Vrátí výběr na danou oblast - undo tah vrací i tam, kde vznikl. */
    protected abstract void selectRegion(int region);

    // ------------------------------------------------------------------
    // stav
    // ------------------------------------------------------------------

    public int[] pixels() { return pixels; }
    public int color()    { return color; }
    public boolean isUnsaved() { return unsaved; }
    public int revision()      { return revision; }

    public void setColor(int argb)
    {
        color = argb;
    }

    /** Pixel oblasti - to, co je na plátně na souřadnicích (x, y). */
    public int get(int x, int y)
    {
        return pixels[index(x, y)];
    }

    /** Obdélník změněných pixelů - co se musí nahrát na grafiku. */
    public DirtyRect dirty()
    {
        return dirty;
    }

    /** Po nahrání na grafiku: od teď se zase počítá od nuly. */
    public void clearDirty()
    {
        dirty.clear();
    }

    /** Vrátí true jednou po každé změně - pak se má obrázek nahrát na grafiku. */
    public boolean takeDirty()
    {
        boolean was = !dirty.isEmpty();
        dirty.clear();
        return was;
    }

    /** Po uložení: změny jsou na disku. */
    public void markSaved()
    {
        unsaved = false;
    }

    /**
     * Nahradí celý obrázek (návrat k uloženému souboru nebo k procedurálnímu).
     * Undo se zahodí - vracelo by tahy na jiný obsah, než na jakém vznikly.
     */
    public void replaceAll(int[] newPixels)
    {
        endStroke();
        System.arraycopy(newPixels, 0, pixels, 0, pixels.length);
        undo.clear();
        changedAll();
        unsaved = false;
    }

    /**
     * Import hotového obrázku do editoru - k dalšímu dolaďování, ne rovnou
     * na disk. Na rozdíl od replaceAll() jde vrátit (Ctrl+Z vrátí celý
     * obrázek) a zůstává neuložený, dokud nepřijde Save.
     */
    public void importImage(int[] newPixels)
    {
        if(newPixels.length != pixels.length)
        {
            throw new IllegalArgumentException("obrazek ma " + newPixels.length + " pixelu");
        }

        endStroke();
        pushUndo(new Snapshot(WHOLE_IMAGE, size, size, pixels.clone()));
        System.arraycopy(newPixels, 0, pixels, 0, pixels.length);
        changedAll();
    }

    // ------------------------------------------------------------------
    // malování
    // ------------------------------------------------------------------

    /**
     * Začátek tahu (zmáčknutí tlačítka). Před prvním pixelem se uloží snímek
     * oblasti, takže celý tah - třeba dlouhá čára tažením - je JEDEN krok undo.
     */
    public void beginStroke(int x, int y)
    {
        endStroke();
        pushUndo(snapshot());

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

    /** Kapátko: vezme barvu pixelu jako aktuální. */
    public int pick(int x, int y)
    {
        color = get(x, y);
        return color;
    }

    /**
     * Vrátí poslední tah. Výběr se přepne na oblast, ve které tah byl;
     * po vrácení importu zůstane vybraná ta, co byla.
     */
    public boolean undo()
    {
        endStroke();
        Snapshot snapshot = undo.poll();

        if(snapshot == null)
        {
            return false;
        }

        if(snapshot.region() == WHOLE_IMAGE)
        {
            System.arraycopy(snapshot.pixels(), 0, pixels, 0, pixels.length);
            changedAll();
        }
        else
        {
            selectRegion(snapshot.region());

            for(int y = 0; y < snapshot.height(); y++)
            {
                for(int x = 0; x < snapshot.width(); x++)
                {
                    pixels[index(x, y)] = snapshot.pixels()[y * snapshot.width() + x];
                }
            }

            markRegionDirty();
            touched();
        }

        return true;
    }

    public int undoDepth()
    {
        return undo.size();
    }

    protected void pushUndo(Snapshot snapshot)
    {
        undo.push(snapshot);

        while(undo.size() > UNDO_LIMIT)
        {
            undo.removeLast();
        }
    }

    /** Snímek právě editované oblasti - základ undo. */
    protected Snapshot snapshot()
    {
        int w = regionWidth(), h = regionHeight();
        int[] copy = new int[w * h];

        for(int y = 0; y < h; y++)
        {
            for(int x = 0; x < w; x++)
            {
                copy[y * w + x] = pixels[index(x, y)];
            }
        }

        return new Snapshot(region(), w, h, copy);
    }

    private void paint(int x, int y)
    {
        if(x < 0 || y < 0 || x >= regionWidth() || y >= regionHeight())
        {
            return;
        }

        int i = index(x, y);

        if(pixels[i] != color)
        {
            pixels[i] = color;
            dirty.add(i % size, i / size);
            touched();
        }
    }

    // ------------------------------------------------------------------
    // sledování změn
    // ------------------------------------------------------------------

    /** Změnil se celý obrázek (import, revert, undo importu). */
    protected void changedAll()
    {
        dirty.addAll(size, size);
        touched();
    }

    /**
     * Přibere do změn celou právě vybranou oblast. Oblasti jsou obdélníky,
     * takže stačí dva protilehlé rohy.
     */
    protected void markRegionDirty()
    {
        int a = index(0, 0);
        int b = index(regionWidth() - 1, regionHeight() - 1);

        int ax = a % size, ay = a / size;
        int bx = b % size, by = b / size;

        dirty.add(Math.min(ax, bx), Math.min(ay, by),
                Math.abs(ax - bx) + 1, Math.abs(ay - by) + 1);
    }

    protected void touched()
    {
        unsaved = true;
        revision++;
    }

    // ------------------------------------------------------------------
    // pomůcky nad pixely
    // ------------------------------------------------------------------

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

    /**
     * Nejčastější barvy právě editované oblasti, nejvýš max. Paleta labu je
     * z nich: pixel-art se maluje odstíny, které v obrázku už jsou.
     */
    public int[] regionColors(int max)
    {
        int w = regionWidth(), h = regionHeight();
        int[] source = new int[w * h];

        for(int y = 0; y < h; y++)
        {
            for(int x = 0; x < w; x++)
            {
                source[y * w + x] = get(x, y);
            }
        }

        return topColors(source, max, true);
    }

    /**
     * Barvy s četnostmi: KOLIK jich celkem je a kterých je nejvíc.
     *
     * ⚠️ Lab potřeboval obojí a volal proto počítání DVAKRÁT - jednou na
     * prvních 86 barev a jednou na všechny, jen aby zjistil, kolik jich je.
     * To je dvakrát průchod 16 384 pixelů a dvakrát seřazení, při každém
     * namalovaném pixelu. Jeden průchod dá obojí.
     */
    public record Colors(int[] values, int[] counts) {

        /** Kolik různých barev obrázek má. */
        public int total()
        {
            return values.length;
        }

        /** Nejčastější první, nejvýš max. Řazení je stabilní - shody v pořadí výskytu. */
        public int[] top(int max)
        {
            Integer[] order = new Integer[values.length];

            for(int i = 0; i < order.length; i++)
            {
                order[i] = i;
            }

            java.util.Arrays.sort(order, (a, b) -> counts[b] - counts[a]);

            int taken = Math.min(max, order.length);
            int[] result = new int[taken];

            for(int i = 0; i < taken; i++)
            {
                result[i] = values[order[i]];
            }

            return result;
        }
    }

    /**
     * Jeden průchod pixely: každá barva a kolikrát je.
     *
     * withTransparent = true počítá i plně průhledné pixely. V oblasti je
     * průhledná pixelem jako každý jiný (praskliny, sklo, druhá vrstva skinu)
     * a paleta ji má nabídnout; v paletě CELÉHO obrázku jsou to naopak
     * hlavně prázdná místa a guma je už v pevném řádku palety.
     */
    public static Colors countColors(int[] source, boolean withTransparent)
    {
        Map<Integer, Integer> counts = new LinkedHashMap<>();

        for(int argb : source)
        {
            if(withTransparent || (argb >>> 24) != 0)
            {
                counts.merge(argb, 1, Integer::sum);
            }
        }

        int[] values = new int[counts.size()];
        int[] howMany = new int[counts.size()];
        int i = 0;

        for(Map.Entry<Integer, Integer> entry : counts.entrySet())
        {
            values[i] = entry.getKey();
            howMany[i] = entry.getValue();
            i++;
        }

        return new Colors(values, howMany);
    }

    static int[] topColors(int[] source, int max, boolean withTransparent)
    {
        return countColors(source, withTransparent).top(max);
    }
}
