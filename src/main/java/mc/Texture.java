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

    /** 0xAARRGGBB → bajty R, G, B, A, jak je chce glTexImage2D. */
    private static ByteBuffer toRgba(int[] argb)
    {
        ByteBuffer pixels = BufferUtils.createByteBuffer(argb.length * 4);

        for(int value : argb)
        {
            pixels.put((byte) ((value >> 16) & 0xFF));   // R
            pixels.put((byte) ((value >> 8) & 0xFF));    // G
            pixels.put((byte) (value & 0xFF));           // B
            pixels.put((byte) ((value >> 24) & 0xFF));   // A
        }

        pixels.flip();
        return pixels;
    }

    public int id()     { return id; }
    public int width()  { return width; }
    public int height() { return height; }

    public void delete()
    {
        glDeleteTextures(id);
    }
}
