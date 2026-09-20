package mc;

/**
 * Obdélník "co se od minule změnilo" v poli pixelů.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ PROČ TO VŮBEC JE. Lab maluje po jednom pixelu, ale do GL se změna musí
 * nahrát celou texturou nebo aspoň obdélníkem. Nahrávat při každém pixelu
 * celých 128x128 (64 KB) znamená 65 536 bajtů přes sběrnici kvůli čtyřem
 * bajtům. Obdélník kolem změn z toho udělá typicky jednu dlaždici 16x16
 * (1 KB), tedy 64x míň dat - a u tahu štětcem, který zůstává v jedné
 * dlaždici, je to přesně ta jedna dlaždice.
 *
 * ⚠️ Je to JEDEN obdélník, ne seznam. Dva vzdálené pixely spojí do obdélníku,
 * který obsahuje i všechno mezi nimi, takže se nahraje víc, než se změnilo -
 * ale nikdy MÍŇ. To je celá správnost: co se změnilo, musí být uvnitř.
 * Seznam obdélníků by v nejhorším případě (import celého atlasu) stejně
 * skončil u jednoho velkého a stál by víc kódu i času.
 *
 * Souřadnice jsou v pixelech pole, x doprava, y v pořadí řádků pole
 * (u atlasu tedy odspodu, u skinu shora - třídě je to jedno).
 *
 * Čistá logika bez GL - testuje ji TextureLabTest.
 * ---------------------------------------------------------------------------
 */
public final class DirtyRect {

    private int x0, y0, x1, y1;
    private boolean any = false;

    /** Je něco k nahrání? */
    public boolean isEmpty()
    {
        return !any;
    }

    /** Přibere jeden pixel. */
    public void add(int x, int y)
    {
        add(x, y, 1, 1);
    }

    /** Přibere obdélník (x, y = levý dolní roh v pořadí pole). Prázdný se ignoruje. */
    public void add(int x, int y, int width, int height)
    {
        if(width <= 0 || height <= 0)
        {
            return;
        }

        if(!any)
        {
            x0 = x;
            y0 = y;
            x1 = x + width;
            y1 = y + height;
            any = true;
            return;
        }

        x0 = Math.min(x0, x);
        y0 = Math.min(y0, y);
        x1 = Math.max(x1, x + width);
        y1 = Math.max(y1, y + height);
    }

    /** Přibere celé pole daných rozměrů - import, revert, načtení. */
    public void addAll(int width, int height)
    {
        add(0, 0, width, height);
    }

    public void clear()
    {
        any = false;
    }

    public int x()      { return any ? x0 : 0; }
    public int y()      { return any ? y0 : 0; }
    public int width()  { return any ? x1 - x0 : 0; }
    public int height() { return any ? y1 - y0 : 0; }

    /** Kolik pixelů by se nahrávalo - pro měření a testy. */
    public int area()
    {
        return width() * height();
    }

    @Override
    public String toString()
    {
        return any ? "DirtyRect[" + x0 + "," + y0 + " " + width() + "x" + height() + "]"
                : "DirtyRect[empty]";
    }
}
