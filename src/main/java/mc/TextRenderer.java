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
 * ---------------------------------------------------------------------------
 */
public class TextRenderer {

    private static final int MAX_CHARS = 512;
    private static final int FLOATS_PER_VERTEX = 4;    // pozice (2) + uv (2)
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
    }

    /** Šířka textu na obrazovce, tedy už včetně měřítka z begin(). */
    public float widthOf(String text)
    {
        return font.textWidth(text) * (float) scale;
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

        int floats = 0;
        int glyphs = 0;
        float penX = x;

        float scaledLineHeight = font.lineHeight() * (float) scale;

        for(int i = 0; i < text.length() && glyphs < MAX_CHARS; i++)
        {
            char c = text.charAt(i);
            float advance = font.advance(c) * (float) scale;

            // Mezera nemá co kreslit, jen posouvá pero.
            if(c != ' ')
            {
                float x0 = penX;
                float x1 = penX + font.glyphWidth(c) * (float) scale;

                // Převod z "y roste dolů" do soustavy shaderu, kde roste nahoru.
                float yBottom = screenHeight - (yTop + scaledLineHeight);
                float yTopGl = screenHeight - yTop;

                float u0 = font.u0(c), u1 = font.u1(c);
                float v0 = font.v0(c), v1 = font.v1(c);

                // Horní hrana kvádru odpovídá hornímu okraji glyfu v atlasu,
                // proto se u horních vrcholů použije v0 a u spodních v1.
                floats = quad(floats,
                        x0, yBottom, u0, v1,
                        x1, yBottom, u1, v1,
                        x1, yTopGl,  u1, v0,
                        x0, yTopGl,  u0, v0);

                glyphs++;
            }

            penX += advance;
        }

        if(floats == 0)
        {
            return;
        }

        upload.clear();
        upload.put(buffer, 0, floats);
        upload.flip();

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, upload);

        shader.setVector4("uColor", r, g, b, a);
        glDrawArrays(GL_TRIANGLES, 0, glyphs * VERTICES_PER_GLYPH);
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
    private int quad(int offset,
                     float x0, float y0, float u0, float v0,
                     float x1, float y1, float u1, float v1,
                     float x2, float y2, float u2, float v2,
                     float x3, float y3, float u3, float v3)
    {
        offset = vertex(offset, x0, y0, u0, v0);
        offset = vertex(offset, x1, y1, u1, v1);
        offset = vertex(offset, x2, y2, u2, v2);

        offset = vertex(offset, x0, y0, u0, v0);
        offset = vertex(offset, x2, y2, u2, v2);
        offset = vertex(offset, x3, y3, u3, v3);

        return offset;
    }

    private int vertex(int offset, float x, float y, float u, float v)
    {
        buffer[offset++] = x;
        buffer[offset++] = y;
        buffer[offset++] = u;
        buffer[offset++] = v;
        return offset;
    }

    public void end()
    {
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
