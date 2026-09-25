package mc;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Kreslení textu z atlasu fontu.
 *
 * Souřadnice se zadávají s počátkem VLEVO NAHOŘE a y roste dolů - tak se text
 * sází přirozeně (řádek po řádku shora). Převod do soustavy shaderu, která má
 * počátek vlevo dole, se dělá uvnitř.
 *
 * ---------------------------------------------------------------------------
 * VELIKOST SE ŘÍDÍ CELOČÍSELNÝM MĚŘÍTKEM, ne velikostí fontu.
 *
 * Atlas se rasterizuje jednou a malý; nadpis je ten samý font kreslený
 * s větším scale. Druhý atlas pro nadpisy by tady byl chyba - u pixelového
 * písma je zvětšování právě ten mechanismus, který se má použít, a dva
 * atlasy by znamenaly dvě různá zrna pixelů v jednom rozhraní.
 *
 * Metriky (advance, lineHeight) jsou proto v GUI pixelech a při kreslení
 * se násobí měřítkem. Kdo si počítá rozvržení dopředu, musí násobit taky -
 * viz Hud a Menu.
 *
 * ⚠️ VŠECHNO MEZI begin() A end() JE JEDEN DRAW CALL, stejně jako u tvarů
 * v Renderer2D. Kvůli tomu je barva ve VRCHOLU, ne v uniformu: uniform se
 * mezi draw cally mění, takže každý řádek - a každý jeho stín zvlášť - byl
 * dřív vlastní draw call. Texture lab jich tak měl přes třicet jen za texty,
 * a na macOS je jeden draw call řádově dražší než na Windows. Cena za to
 * jsou čtyři floaty navíc na vrchol, tedy 96 B na glyf.
 * ---------------------------------------------------------------------------
 */
public class TextRenderer {

    /** Kolik glyfů se vejde do jedné dávky. Nejdelší obrazovka je ladicí výpis. */
    private static final int MAX_CHARS = 2048;

    private static final int FLOATS_PER_VERTEX = 8;    // pozice (2) + uv (2) + barva (4)
    private static final int VERTICES_PER_GLYPH = 6;   // dva trojúhelníky

    private final ShaderProgram shader = new ShaderProgram(Shaders.TEXT_VERTEX, Shaders.TEXT_FRAGMENT);
    private final FontAtlas font;

    private final int vao;
    private final int vbo;

    private final float[] buffer =
            new float[MAX_CHARS * VERTICES_PER_GLYPH * FLOATS_PER_VERTEX];

    /** Trvalý buffer, ať se každý řádek textu nealokuje pole na nahrání do VBO. */
    private final FloatBuffer upload = BufferUtils.createFloatBuffer(buffer.length);

    private int screenHeight;

    /** Kolik floatů a glyfů v dávce čeká na nahrání. */
    private int pending = 0;
    private int glyphs = 0;

    /** Měřítko navázané posledním begin(). Celé číslo, viz Gui. */
    private int scale = 1;

    public TextRenderer(FontAtlas font)
    {
        this.font = font;

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) buffer.length * Float.BYTES, GL_DYNAMIC_DRAW);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 4L * Float.BYTES);
        glEnableVertexAttribArray(2);

        glBindVertexArray(0);
    }

    /** Metriky jsou v GUI pixelech - pro obrazovku je vynásob měřítkem. */
    public FontAtlas font()
    {
        return font;
    }

    /** Měřítko posledního begin(). Stín textu se posouvá právě o tolik. */
    public int scale()
    {
        return scale;
    }

    public void begin(int screenWidth, int screenHeight, int scale)
    {
        this.screenHeight = screenHeight;
        this.scale = Math.max(1, scale);

        glDisable(GL_DEPTH_TEST);

        // Glyfy jsou v textuře jako krytí, takže se musí míchat průhledností.
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.bind();
        shader.setVector2("uScreenSize", screenWidth, screenHeight);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, font.textureId());
        shader.setInt("uFont", 0);

        glBindVertexArray(vao);

        pending = 0;
        glyphs = 0;
    }

    /**
     * Pošle nasbírané glyfy na grafiku. Volá se z end() a při plné dávce -
     * pořadí kreslení se tím nesmí změnit.
     */
    private void flush()
    {
        if(glyphs == 0)
        {
            return;
        }

        upload.clear();
        upload.put(buffer, 0, pending);
        upload.flip();

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, upload);

        glDrawArrays(GL_TRIANGLES, 0, glyphs * VERTICES_PER_GLYPH);
        GlStats.countDraw();

        pending = 0;
        glyphs = 0;
    }

    /** Šířka textu na obrazovce, tedy už včetně měřítka z begin(). */
    public float widthOf(String text)
    {
        return font.textWidth(text) * (float) scale;
    }

    /**
     * Zkrátí text tak, aby se vešel do šířky v GUI pixelech (se třemi tečkami).
     *
     * ⚠️ MĚŘÍ SE V GUI PIXELECH, BEZ MĚŘÍTKA. Dřív fit() v labu i ve Widgets
     * násobilo šířku měřítkem z parametru a text měřítkem z posledního
     * begin() (widthOf) - dnes se ty dvě hodnoty shodují, ale nic to
     * nehlídalo. Font měří v GUI pixelech rovnou, takže měřítko tu není
     * potřeba vůbec. Délka se hledá půlením (log n měření), ne ubíráním po
     * znaku, které každý frame stavělo n řetězců.
     */
    public String fit(String line, int guiWidth)
    {
        float limit = guiWidth;   // v GUI pixelech, font měří taky v nich

        if(font.textWidth(line) <= limit)
        {
            return line;
        }

        // Nejdelší prefix, se kterým se "..." ještě vejde (aspoň jeden znak).
        int low = 1, high = line.length() - 1;

        while(low < high)
        {
            int mid = (low + high + 1) >>> 1;

            if(font.textWidth(line.substring(0, mid) + "...") <= limit)
            {
                low = mid;
            }
            else
            {
                high = mid - 1;
            }
        }

        return line.substring(0, low) + "...";
    }

    /**
     * Jako fit(), ale nechá KONEC textu a tečky dá na začátek. Pro textová
     * pole: píše se na konec, takže kurzor a poslední znaky musí být vidět.
     */
    public String fitEnd(String line, int guiWidth)
    {
        if(font.textWidth(line) <= guiWidth)
        {
            return line;
        }

        // Nejmenší začátek suffixu, se kterým se "..." vejde.
        int low = 1, high = line.length() - 1;

        while(low < high)
        {
            int mid = (low + high) >>> 1;

            if(font.textWidth("..." + line.substring(mid)) <= guiWidth)
            {
                high = mid;
            }
            else
            {
                low = mid + 1;
            }
        }

        return "..." + line.substring(low);
    }

    /** Výška řádku na obrazovce, tedy už včetně měřítka z begin(). */
    public float lineHeight()
    {
        return font.lineHeight() * (float) scale;
    }

    /** x, yTop jsou levý horní roh řádku, v pixelech obrazovky. */
    public void draw(String text, float x, float yTop, float r, float g, float b, float a)
    {
        if(text.isEmpty())
        {
            return;
        }

        float penX = x;
        float scaledLineHeight = font.lineHeight() * (float) scale;

        for(int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            float advance = font.advance(c) * (float) scale;

            // Mezera nemá co kreslit, jen posouvá pero.
            if(c != ' ')
            {
                if(glyphs >= MAX_CHARS)
                {
                    flush();
                }

                float x0 = penX;
                float x1 = penX + font.glyphWidth(c) * (float) scale;

                // Převod z "y roste dolů" do soustavy shaderu, kde roste nahoru.
                float yBottom = screenHeight - (yTop + scaledLineHeight);
                float yTopGl = screenHeight - yTop;

                float u0 = font.u0(c), u1 = font.u1(c);
                float v0 = font.v0(c), v1 = font.v1(c);

                // Horní hrana kvádru odpovídá hornímu okraji glyfu v atlasu,
                // proto se u horních vrcholů použije v0 a u spodních v1.
                quad(x0, yBottom, u0, v1,
                     x1, yBottom, u1, v1,
                     x1, yTopGl,  u1, v0,
                     x0, yTopGl,  u0, v0,
                     r, g, b, a);

                glyphs++;
            }

            penX += advance;
        }
    }

    public void draw(String text, float x, float yTop, float[] color)
    {
        draw(text, x, yTop, color[0], color[1], color[2], alphaOf(color));
    }

    public void drawCentered(String text, float centerX, float yTop, float[] color)
    {
        // Zarovnání na celý GUI pixel: půlpixelový posun by u pixelového písma
        // rozmazal svislé tahy, které mají být ostré.
        draw(text, Gui.snap(centerX - widthOf(text) / 2f, scale), yTop, color);
    }

    /**
     * Text se stínem posunutým přesně o JEDEN GUI pixel doprava dolů -
     * tak to má Minecraft. Stín drží čitelnost nad libovolným pozadím,
     * aniž by za text musel panel.
     */
    public void drawShadowed(String text, float x, float yTop, float[] color, float[] shadow)
    {
        draw(text, x + scale, yTop + scale, shadow);
        draw(text, x, yTop, color);
    }

    public void drawCenteredShadowed(String text, float centerX, float yTop,
                                     float[] color, float[] shadow)
    {
        float x = Gui.snap(centerX - widthOf(text) / 2f, scale);
        drawShadowed(text, x, yTop, color, shadow);
    }

    private static float alphaOf(float[] color)
    {
        return color.length > 3 ? color[3] : 1f;
    }

    /** Vrcholy proti směru hodinových ručiček - kvůli zapnutému backface cullingu. */
    private void quad(float x0, float y0, float u0, float v0,
                      float x1, float y1, float u1, float v1,
                      float x2, float y2, float u2, float v2,
                      float x3, float y3, float u3, float v3,
                      float r, float g, float b, float a)
    {
        vertex(x0, y0, u0, v0, r, g, b, a);
        vertex(x1, y1, u1, v1, r, g, b, a);
        vertex(x2, y2, u2, v2, r, g, b, a);

        vertex(x0, y0, u0, v0, r, g, b, a);
        vertex(x2, y2, u2, v2, r, g, b, a);
        vertex(x3, y3, u3, v3, r, g, b, a);
    }

    private void vertex(float x, float y, float u, float v,
                        float r, float g, float b, float a)
    {
        buffer[pending++] = x;
        buffer[pending++] = y;
        buffer[pending++] = u;
        buffer[pending++] = v;
        buffer[pending++] = r;
        buffer[pending++] = g;
        buffer[pending++] = b;
        buffer[pending++] = a;
    }

    public void end()
    {
        flush();

        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    public void delete()
    {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        shader.delete();
        // Atlas si maže ten, kdo ho vytvořil (Main) - TextRenderer je jen půjčený.
    }
}
