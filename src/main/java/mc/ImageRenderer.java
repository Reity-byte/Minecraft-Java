package mc;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Libovolný výřez textury jako obdélník na obrazovce.
 *
 * Používá existující shader UI_TEXTURED (ten, co kreslí pozadí menu), jen
 * s volným obdélníkem a UV místo celé obrazovky. Texture lab tím ukazuje
 * atlas a zvětšenou dlaždici PŘÍMO Z TEXTURY NA GRAFICE - tedy přesně to,
 * co pak vzorkuje svět, ne kopii pixelů na CPU.
 *
 * Míchání je zapnuté, aby průhledné pixely (voda, praskliny) prosvítaly
 * na šachovnici pod nimi.
 */
public class ImageRenderer {

    private static final int FLOATS_PER_VERTEX = 4;   // pozice (2) + uv (2)
    private static final int VERTICES = 6;

    private final ShaderProgram shader =
            new ShaderProgram(Shaders.UI_TEXTURED_VERTEX, Shaders.UI_TEXTURED_FRAGMENT);

    private final int vao;
    private final int vbo;

    private final float[] scratch = new float[VERTICES * FLOATS_PER_VERTEX];
    private final FloatBuffer upload = BufferUtils.createFloatBuffer(scratch.length);

    public ImageRenderer()
    {
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

    /**
     * Obdélník (x, y = levý DOLNÍ roh v pixelech obrazovky) s výřezem textury
     * u0..u1, v0..v1. v0 je dole - stejně jako řádek 0 atlasu.
     */
    public void draw(Texture texture, int screenWidth, int screenHeight,
                     float x, float y, float width, float height,
                     float u0, float v0, float u1, float v1)
    {
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.bind();
        shader.setVector2("uScreenSize", screenWidth, screenHeight);
        shader.setVector4("uTint", 1f, 1f, 1f, 1f);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture.id());
        shader.setInt("uTexture", 0);

        // Proti směru hodinových ručiček - backface culling je zapnutý.
        int i = 0;
        i = vertex(i, x,         y,          u0, v0);
        i = vertex(i, x + width, y,          u1, v0);
        i = vertex(i, x + width, y + height, u1, v1);
        i = vertex(i, x,         y,          u0, v0);
        i = vertex(i, x + width, y + height, u1, v1);
        i = vertex(i, x,         y + height, u0, v1);

        upload.clear();
        upload.put(scratch, 0, i);
        upload.flip();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, upload);
        glDrawArrays(GL_TRIANGLES, 0, VERTICES);
        glBindVertexArray(0);

        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_BLEND);
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
    }
}
