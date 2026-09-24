package mc;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Dlaždicované pozadí hlavního menu.
 *
 * Vlastní třída, ne metoda v Renderer2D: potřebuje jiný shader (texturovaný),
 * jiný vertex formát (pozice + UV místo pozice + barva) a jiné GL stavy.
 * Nacpat to do Renderer2D by znamenalo držet tam dvě sady VAO/VBO/shaderu
 * a přepínat mezi nimi kvůli jedinému volání za frame.
 *
 * Kreslí se JEDNÍM quadem přes celou obrazovku. Opakování řeší GL_REPEAT
 * na textuře: UV jdou od 0 do (rozměr obrazovky / velikost dlaždice), takže
 * u hodnoty 21.3 se dlaždice vyskládá jedenadvacetkrát a kus. Kreslit stovky
 * malých quadů by bylo stovky draw callů za nic.
 */
public class BackgroundRenderer {

    /** Velikost dlaždice v GUI pixelech. 16 = jeden blok, jako textury v Minecraftu. */
    private static final int TILE_GUI_PIXELS = 16;

    private static final int FLOATS_PER_VERTEX = 4;   // pozice (2) + uv (2)
    private static final int VERTICES = 6;

    private final ShaderProgram shader =
            new ShaderProgram(Shaders.UI_TEXTURED_VERTEX, Shaders.UI_TEXTURED_FRAGMENT);

    private final Texture tile;

    private final int vao;
    private final int vbo;

    private final float[] scratch = new float[VERTICES * FLOATS_PER_VERTEX];
    private final FloatBuffer upload = BufferUtils.createFloatBuffer(scratch.length);

    public BackgroundRenderer(Texture tile)
    {
        this.tile = tile;

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) scratch.length * Float.BYTES, GL_DYNAMIC_DRAW);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindVertexArray(0);
    }

    public void draw(int screenWidth, int screenHeight, float[] tint)
    {
        int scale = Gui.scale(screenWidth, screenHeight);
        float tilePixels = (float) TILE_GUI_PIXELS * scale;

        // Kolikrát se dlaždice vejde přes obrazovku. Necelý zbytek je v pořádku -
        // poslední sloupec a řádek se prostě oříznou uprostřed dlaždice.
        float u = screenWidth / tilePixels;
        float v = screenHeight / tilePixels;

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);

        shader.bind();
        shader.setVector2("uScreenSize", screenWidth, screenHeight);
        shader.setVector4("uTint", tint[0], tint[1], tint[2],
                tint.length > 3 ? tint[3] : 1f);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tile.id());
        shader.setInt("uTexture", 0);

        // V roste nahoru stejně jako y, takže se dlaždice nepřevrací.
        int i = 0;
        i = vertex(i, 0,           0,            0, 0);
        i = vertex(i, screenWidth, 0,            u, 0);
        i = vertex(i, screenWidth, screenHeight, u, v);
        i = vertex(i, 0,           0,            0, 0);
        i = vertex(i, screenWidth, screenHeight, u, v);
        i = vertex(i, 0,           screenHeight, 0, v);

        upload.clear();
        upload.put(scratch, 0, i);
        upload.flip();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, upload);

        glDrawArrays(GL_TRIANGLES, 0, VERTICES);
        GlStats.countDraw();

        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glEnable(GL_DEPTH_TEST);
    }

    private int vertex(int offset, float x, float y, float u, float v)
    {
        scratch[offset++] = x;
        scratch[offset++] = y;
        scratch[offset++] = u;
        scratch[offset++] = v;
        return offset;
    }

    public void delete()
    {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        shader.delete();
        // Dlaždici si maže ten, kdo ji vytvořil (Main).
    }
}
