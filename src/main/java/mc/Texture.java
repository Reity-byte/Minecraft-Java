package mc;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * RGBA textura nahraná do GL.
 *
 * ---------------------------------------------------------------------------
 * FILTROVÁNÍ JE **GL_NEAREST**, ne GL_LINEAR. To je u pixel-artu zásadní:
 * lineární filtr sousední texely průměruje, takže zvětšená 16x16 dlaždice
 * vyjde jako rozmazaná skvrna. NEAREST bere nejbližší texel, takže jeden
 * texel = ostrý čtvereček. Přesně tohle dělá Minecraft blockově vypadající.
 *
 * Opakování je GL_REPEAT, aby šla jedna dlaždice vyskládat přes celou plochu
 * pouhým protažením UV za hranici 0-1.
 * ---------------------------------------------------------------------------
 *
 * Až přijde na řadu blokový texture atlas, použije se tahle třída znovu -
 * jen s wrapem CLAMP_TO_EDGE, aby se sousední dlaždice v atlasu nepřetahovaly.
 */
public class Texture {

    private final int id;
    private final int width;
    private final int height;

    /** Buffer na výřez v updateRegion() - drží se, ať se při malování nealokuje. */
    private ByteBuffer region;

    /** pixels je RGBA po bajtech, řádek po řádku odspodu (jako u GL). */
    public Texture(ByteBuffer pixels, int width, int height, int wrap)
    {
        this.width = width;
        this.height = height;

        id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);

        // Řádek nemusí mít délku dělitelnou 4, což je výchozí zarovnání.
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, pixels);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);

        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /** Postaví texturu z pole 0xAARRGGBB, řádek po řádku odspodu. */
    public static Texture fromArgb(int[] argb, int width, int height, int wrap)
    {
        return new Texture(toRgba(argb), width, height, wrap);
    }

    /**
     * Přepíše celý obsah textury novými pixely stejných rozměrů. Stejná
     * textura, stejné id - všechno, co ji používá (svět, ikony, ruka), uvidí
     * změnu hned v příštím kreslení. Na tom stojí živý náhled v texture labu.
     */
    public void update(int[] argb)
    {
        glBindTexture(GL_TEXTURE_2D, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL_RGBA, GL_UNSIGNED_BYTE, toRgba(argb));
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Přepíše jen OBDÉLNÍK textury, ne celou. Pixely se berou ze stejného
     * pole celého obrázku (řádek po řádku odspodu), jen se z něj vyřízne
     * daný výřez.
     *
     * ⚠️ Kvůli tomuhle existuje DirtyRect. Lab maluje po jednom pixelu;
     * nahrát kvůli němu celých 128x128 je 64 KB místo 4 bajtů. Jedna
     * dlaždice 16x16 je 1 KB, tedy 64x míň dat a 64x míň práce pro ovladač.
     *
     * Rychlý buffer se drží mezi voláními, ať se každý tah štětcem
     * nealokuje nové pole.
     */
    public void updateRegion(int[] argb, int x, int y, int regionWidth, int regionHeight)
    {
        if(regionWidth <= 0 || regionHeight <= 0)
        {
            return;
        }

        if(x == 0 && y == 0 && regionWidth == width && regionHeight == height)
        {
            update(argb);
            return;
        }

        int needed = regionWidth * regionHeight * 4;

        if(region == null || region.capacity() < needed)
        {
            region = BufferUtils.createByteBuffer(needed);
        }

        region.clear();

        for(int row = 0; row < regionHeight; row++)
        {
            int start = (y + row) * width + x;

            for(int column = 0; column < regionWidth; column++)
            {
                putRgba(region, argb[start + column]);
            }
        }

        region.flip();

        glBindTexture(GL_TEXTURE_2D, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, regionWidth, regionHeight,
                GL_RGBA, GL_UNSIGNED_BYTE, region);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /** 0xAARRGGBB → bajty R, G, B, A, jak je chce glTexImage2D. */
    private static ByteBuffer toRgba(int[] argb)
    {
        ByteBuffer pixels = BufferUtils.createByteBuffer(argb.length * 4);

        for(int value : argb)
        {
            putRgba(pixels, value);
        }

        pixels.flip();
        return pixels;
    }

    private static void putRgba(ByteBuffer target, int value)
    {
        target.put((byte) ((value >> 16) & 0xFF));   // R
        target.put((byte) ((value >> 8) & 0xFF));    // G
        target.put((byte) (value & 0xFF));           // B
        target.put((byte) ((value >>> 24) & 0xFF));  // A
    }

    public int id()     { return id; }
    public int width()  { return width; }
    public int height() { return height; }

    public void delete()
    {
        glDeleteTextures(id);
    }
}
