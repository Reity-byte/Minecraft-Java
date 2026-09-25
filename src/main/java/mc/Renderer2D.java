package mc;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Kreslení 2D tvarů v pixelech obrazovky: obdélníky, rámečky, tvary s bevelem
 * a libovolné čtyřúhelníky (pro izometrické kostky v hotbaru).
 *
 * Sdílí ho HUD i menu - obojí potřebuje to samé. Počátek je vlevo dole,
 * stejně jako v shaderu.
 *
 * ---------------------------------------------------------------------------
 * ROZHODNUTÍ, KTERÁ SE TÁHNOU CELOU TŘÍDOU:
 *
 * 1) Barva je v každém vrcholu (pozice 2 + RGBA 4 floaty), ne v uniformu.
 *    Kvůli ztmavení pozadí, které je jediný přechod, co v UI zůstal.
 *
 * 2) Rámečky se NEKRESLÍ obrysem. glLineWidth > 1 není v core profilu zaručeně
 *    podporovaný (a ovladače se v tom liší), takže by tloušťka byla loterie.
 *    Rámeček je proto čtveřice plných pruhů.
 *
 * 3) Plastičnost dělá BEVEL, ne přechod: světlá hrana nahoře a vlevo, tmavá
 *    dole a vpravo, kolem černý obrys. Je to přesně to, co má Minecraft
 *    v widgets.png - a jde to nakreslit obdélníky, takže to nepotřebuje texturu.
 *
 * 4) ⚠️ VŠECHNO MEZI begin() A end() JE JEDEN DRAW CALL. Vrcholy se sypou
 *    do jednoho pole a na grafiku jdou až na konci. Dřív měl každý obdélník
 *    vlastní glBufferSubData + glDrawArrays, takže texture lab dělal přes
 *    400 draw callů na frame (paleta 86 barev je 172 z nich, tři HSV
 *    posuvníky po 32 dílcích dalších 102). Na Windows s NVIDIÍ to stálo
 *    desetiny milisekundy, na integrované grafice v MacBooku, kde je ovladač
 *    OpenGL na jeden draw call řádově dražší, právě tam lab sekal. Počet
 *    draw callů ukazuje GlStats a měří ho LabProfiler.
 *
 *    Míchání se uvnitř dávky měnit nesmí - beginInvertBlend() proto dávku
 *    vyprázdní, jinak by se zaměřovač nakreslil normálním mícháním.
 * ---------------------------------------------------------------------------
 */
public class Renderer2D {

    /** pozice (2) + barva RGBA (4) */
    private static final int FLOATS_PER_VERTEX = 6;

    /** Jeden obdélník nebo čtyřúhelník = dva trojúhelníky. */
    private static final int VERTICES_PER_QUAD = 6;

    /**
     * Kolik čtyřúhelníků se vejde do jedné dávky. Nejhustší obrazovka je
     * texture lab (přes 400), takže 1024 stačí na celou i s rezervou; plná
     * dávka se stejně sama vyprázdní a pokračuje se v další, jen o draw call
     * navíc.
     *
     * 1024 * 6 vrcholů * 6 floatů * 4 B = 144 KB, jednou za běh hry.
     */
    private static final int BATCH_QUADS = 1024;

    private final ShaderProgram shader = new ShaderProgram(Shaders.HUD_VERTEX, Shaders.HUD_FRAGMENT);

    private final int vao;
    private final int vbo;

    private final float[] scratch = new float[BATCH_QUADS * VERTICES_PER_QUAD * FLOATS_PER_VERTEX];

    /** Kolik floatů v dávce čeká na nahrání. */
    private int pending = 0;

    /** Trvalý buffer, ať se každý frame nealokuje pole na nahrání do VBO. */
    private final FloatBuffer upload = BufferUtils.createFloatBuffer(scratch.length);

    public Renderer2D()
    {
        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) scratch.length * Float.BYTES, GL_DYNAMIC_DRAW);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindVertexArray(0);
    }

    public void begin(int screenWidth, int screenHeight)
    {
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.bind();
        shader.setVector2("uScreenSize", screenWidth, screenHeight);
        glBindVertexArray(vao);

        pending = 0;
    }

    public void end()
    {
        flush();

        glBindVertexArray(0);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    /**
     * Pošle nasbírané vrcholy na grafiku. Volá se z end(), při plné dávce
     * a všude, kde se mění stav GL - pořadí kreslení se tím nesmí změnit.
     */
    private void flush()
    {
        if(pending == 0)
        {
            return;
        }

        upload.clear();
        upload.put(scratch, 0, pending);
        upload.flip();

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, upload);

        glDrawArrays(GL_TRIANGLES, 0, pending / FLOATS_PER_VERTEX);
        GlStats.countDraw();

        pending = 0;
    }

    // ------------------------------------------------------------------
    // obdélníky
    // ------------------------------------------------------------------

    /** x, y je levý DOLNÍ roh. */
    public void fillRect(float x, float y, float width, float height,
                         float r, float g, float b, float a)
    {
        float x1 = x + width;
        float y1 = y + height;

        // proti směru hodinových ručiček kvůli zapnutému backface cullingu
        reserve();
        vertex(x,  y,  r, g, b, a);
        vertex(x1, y,  r, g, b, a);
        vertex(x1, y1, r, g, b, a);
        vertex(x,  y,  r, g, b, a);
        vertex(x1, y1, r, g, b, a);
        vertex(x,  y1, r, g, b, a);
    }

    public void fillRect(float x, float y, float width, float height, float[] color)
    {
        fillRect(x, y, width, height, color[0], color[1], color[2], alphaOf(color));
    }

    /**
     * Svislý přechod: bottom je barva u spodní hrany, top u horní.
     * V hranatém UI zbyl jediný případ - ztmavení scény za menu pauzy.
     */
    public void fillRectGradient(float x, float y, float width, float height,
                                 float[] bottom, float[] top)
    {
        float x1 = x + width;
        float y1 = y + height;

        float br = bottom[0], bg = bottom[1], bb = bottom[2], ba = alphaOf(bottom);
        float tr = top[0],    tg = top[1],    tb = top[2],    ta = alphaOf(top);

        reserve();
        vertex(x,  y,  br, bg, bb, ba);
        vertex(x1, y,  br, bg, bb, ba);
        vertex(x1, y1, tr, tg, tb, ta);
        vertex(x,  y,  br, bg, bb, ba);
        vertex(x1, y1, tr, tg, tb, ta);
        vertex(x,  y1, tr, tg, tb, ta);
    }

    /** Rámeček bez výplně - čtyři plné pruhy dokola, uvnitř zůstane pozadí. */
    public void border(float x, float y, float width, float height,
                       float thickness, float[] color)
    {
        fillRect(x, y, width, thickness, color);                              // dole
        fillRect(x, y + height - thickness, width, thickness, color);         // nahoře
        fillRect(x, y + thickness, thickness, height - 2 * thickness, color); // vlevo
        fillRect(x + width - thickness, y + thickness,
                thickness, height - 2 * thickness, color);                    // vpravo
    }

    /**
     * Obdélník s bevelem: černý obrys, výplň, světlá hrana nahoře a vlevo,
     * tmavá dole a vpravo. Tloušťka hran je jeden GUI pixel.
     *
     * Pořadí kreslení: nejdřív tmavá hrana, pak světlá. Ve dvou rozích se
     * kříží a musí to dopadnout stejně jako v Minecraftu - vlevo nahoře světlá.
     */
    public void bevelRect(float x, float y, float width, float height, float unit,
                          float[] outline, float[] fill,
                          float[] highlight, float[] shadow)
    {
        if(width <= 2 * unit || height <= 2 * unit)
        {
            return;
        }

        fillRect(x, y, width, height, outline);

        float ix = x + unit, iy = y + unit;
        float iw = width - 2 * unit, ih = height - 2 * unit;

        fillRect(ix, iy, iw, ih, fill);

        fillRect(ix, iy, iw, unit, shadow);                     // dole
        fillRect(ix + iw - unit, iy, unit, ih, shadow);         // vpravo

        fillRect(ix, iy + ih - unit, iw, unit, highlight);      // nahoře
        fillRect(ix, iy, unit, ih, highlight);                  // vlevo
    }

    // ------------------------------------------------------------------

    /**
     * Libovolný čtyřúhelník ze čtyř bodů, zadaných PROTI směru hodinových
     * ručiček. Používají ho izometrické kostky v hotbaru, kde stěny nejsou
     * osově zarovnané a obdélník na ně nestačí.
     */
    public void fillQuad(float x0, float y0, float x1, float y1,
                         float x2, float y2, float x3, float y3,
                         float[] color)
    {
        float r = color[0], g = color[1], b = color[2], a = alphaOf(color);

        reserve();
        vertex(x0, y0, r, g, b, a);
        vertex(x1, y1, r, g, b, a);
        vertex(x2, y2, r, g, b, a);
        vertex(x0, y0, r, g, b, a);
        vertex(x2, y2, r, g, b, a);
        vertex(x3, y3, r, g, b, a);
    }

    // ------------------------------------------------------------------

    /**
     * Přepne míchání na invertování pozadí: výsledek = 1 - to, co je pod ním.
     * Používá zaměřovač, aby byl vidět na světlé obloze i na tmavém kameni.
     *
     * POZOR: tvary kreslené v tomhle režimu se NESMÍ překrývat. Dvojí inverze
     * vrátí původní barvu, takže by průsečík zmizel.
     */
    public void beginInvertBlend()
    {
        // ⚠️ Nasbírané tvary musí ven DŘÍV, než se míchání přepne - jinak by
        // se nakreslily až s ním, tedy invertovaně.
        flush();
        glBlendFunc(GL_ONE_MINUS_DST_COLOR, GL_ZERO);
    }

    public void endInvertBlend()
    {
        flush();
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    // ------------------------------------------------------------------

    /** Trojice bez alfy je platná barva - bere se jako plně krycí. */
    private static float alphaOf(float[] color)
    {
        return color.length > 3 ? color[3] : 1f;
    }

    /** Místo na další čtyřúhelník; když se do dávky nevejde, vyprázdní se. */
    private void reserve()
    {
        if(pending + VERTICES_PER_QUAD * FLOATS_PER_VERTEX > scratch.length)
        {
            flush();
        }
    }

    private void vertex(float x, float y, float r, float g, float b, float a)
    {
        scratch[pending++] = x;
        scratch[pending++] = y;
        scratch[pending++] = r;
        scratch[pending++] = g;
        scratch[pending++] = b;
        scratch[pending++] = a;
    }

    public void delete()
    {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        shader.delete();
    }
}
