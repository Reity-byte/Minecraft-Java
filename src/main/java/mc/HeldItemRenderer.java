package mc;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Blok, který hráč drží v ruce.
 *
 * ---------------------------------------------------------------------------
 * Kreslí se ve VLASTNÍ perspektivě, ne ve světové. Je to kus geometrie kousek
 * před kamerou, ne objekt ve světě - kdyby se kreslil s maticí světa, prorážel
 * by stěny a mizel v blocích.
 *
 * ⚠️ Před kreslením se maže hloubkový buffer. Ruka má být VŽDY vepředu; bez
 * vyčištění by ji zakryl terén, do kterého hráč strká hlavu.
 *
 * Tvar se bere z BlockModels, takže pochodeň se v ruce drží jako tyčka a plot
 * jako sloupek - stejně jako ikona v hotbaru a jako blok ve světě.
 * ---------------------------------------------------------------------------
 */
public class HeldItemRenderer {

    private static final float SHADE_TOP    = 1.00f;
    private static final float SHADE_BOTTOM = 0.50f;
    private static final float SHADE_SIDE_X = 0.60f;
    private static final float SHADE_SIDE_Z = 0.80f;

    private static final int FLOATS_PER_VERTEX = 6;   // pozice(3) + uv(2) + odstín(1)

    /** Nejvíc kvádrů, které model může mít; se šesti stěnami po šesti vrcholech. */
    private static final int MAX_BOXES = 4;
    private static final int MAX_FLOATS = MAX_BOXES * 6 * 6 * FLOATS_PER_VERTEX;

    private final ShaderProgram shader =
            new ShaderProgram(Shaders.HAND_VERTEX, Shaders.HAND_FRAGMENT);

    private final Texture atlas;

    private final int vao;
    private final int vbo;

    private final float[] data = new float[MAX_FLOATS];
    private final FloatBuffer upload = BufferUtils.createFloatBuffer(MAX_FLOATS);
    private final Matrix4f mvp = new Matrix4f();

    private int floats = 0;

    public HeldItemRenderer(Texture atlas)
    {
        this.atlas = atlas;

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_FLOATS * Float.BYTES, GL_DYNAMIC_DRAW);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 5L * Float.BYTES);
        glEnableVertexAttribArray(2);

        glBindVertexArray(0);
    }

    /**
     * @param fastSwing rychlá křivka máchnutí - zdvih a překlopení zápěstí
     * @param slowSwing pomalá křivka máchnutí - odklon do strany
     * @param light 0 až 1 - ruka tmavne v jeskyni a v noci stejně jako svět
     */
    public void draw(int screenWidth, int screenHeight, float fovDegrees,
                     byte block, float fastSwing, float slowSwing, float light)
    {
        if(block == World.AIR)
        {
            return;
        }

        buildModel(block);

        if(floats == 0)
        {
            return;
        }

        // ⚠️ Hloubka se maže, ne jen vypíná test: ruka musí být vepředu,
        // ale sama se sebou se hloubkově porovnávat má - jinak by zadní stěny
        // kostky přebily přední.
        glClear(GL_DEPTH_BUFFER_BIT);

        buildMatrix(screenWidth, screenHeight, fovDegrees, fastSwing, slowSwing);

        shader.bind();
        shader.setMatrix4("uMvp", mvp);
        shader.setFloat("uLight", light);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlas.id());
        shader.setInt("uAtlas", 0);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        upload.clear();
        upload.put(data, 0, floats);
        upload.flip();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, upload);
        glDrawArrays(GL_TRIANGLES, 0, floats / FLOATS_PER_VERTEX);
        glBindVertexArray(0);

        glDisable(GL_BLEND);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Umístění ruky a máchnutí.
     *
     * ⚠️ Máchnutí je ROTACE PO OBLOUKU, ne posun dolů. Posunem vzniklo jen
     * houpnutí sem a tam; minecraftí švih dělá teprve to, že se ruka otáčí
     * kolem tří os naráz a každá podle JINÉ křivky:
     *
     *   rychlá  velký zdvih kolem X (ruka se rozmáchne) a náklon kolem Z
     *           (zápěstí se překlopí) - obojí vystřelí hned na začátku
     *   pomalá  odklon kolem Y - rozjede se až v druhé půlce, takže se ruka
     *           nevrací po stejné dráze, ale opíše oblouk
     *
     * Klidová poloha je to samé s nulami: posun vpravo dolů a natočení o 45°,
     * aby se kostka nedívala na kameru čelem.
     *
     * Blízká ořezová rovina je stejná jako u světa, daleká stačí malá - ruka
     * je kousek od oka a nic za ní se v tomhle průchodu nekreslí.
     */
    private void buildMatrix(int screenWidth, int screenHeight, float fovDegrees,
                             float fastSwing, float slowSwing)
    {
        mvp.setPerspective((float) Math.toRadians(fovDegrees),
                        (float) screenWidth / screenHeight, 0.05f, 10f)
                .translate(0.56f, -0.52f, -0.72f)
                .rotateY((float) Math.toRadians(45f - 20f * slowSwing))
                .rotateZ((float) Math.toRadians(-20f * fastSwing))
                .rotateX((float) Math.toRadians(-80f * fastSwing))
                .scale(0.4f)
                // Model má počátek v rohu; tímhle se otáčí kolem svého středu.
                .translate(-0.5f, -0.5f, -0.5f);
    }

    /** Všech šest stěn každého kvádru modelu - ruku vidíme z několika stran. */
    private void buildModel(byte block)
    {
        floats = 0;

        int top = BlockAtlas.tile(block, BlockAtlas.FACE_TOP);
        int bottom = BlockAtlas.tile(block, BlockAtlas.FACE_BOTTOM);
        int side = BlockAtlas.tile(block, BlockAtlas.FACE_SIDE);

        for(BlockModels.BlockBox box : BlockModels.of(block))
        {
            if(floats + 6 * 6 * FLOATS_PER_VERTEX > MAX_FLOATS)
            {
                return;
            }

            float x0 = box.minX(), x1 = box.maxX();
            float y0 = box.minY(), y1 = box.maxY();
            float z0 = box.minZ(), z1 = box.maxZ();

            face(top, SHADE_TOP,       x0, y1, z0,  x0, y1, z1,  x1, y1, z1,  x1, y1, z0);
            face(bottom, SHADE_BOTTOM, x0, y0, z0,  x1, y0, z0,  x1, y0, z1,  x0, y0, z1);
            face(side, SHADE_SIDE_X,   x1, y0, z0,  x1, y1, z0,  x1, y1, z1,  x1, y0, z1);
            face(side, SHADE_SIDE_X,   x0, y0, z0,  x0, y0, z1,  x0, y1, z1,  x0, y1, z0);
            face(side, SHADE_SIDE_Z,   x0, y0, z1,  x1, y0, z1,  x1, y1, z1,  x0, y1, z1);
            face(side, SHADE_SIDE_Z,   x0, y0, z0,  x0, y1, z0,  x1, y1, z0,  x1, y0, z0);
        }
    }

    /** Celá dlaždice na stěnu; u tenkých modelů je textura oříznutá jinde než ve světě. */
    private void face(int tile, float shade,
                      float ax, float ay, float az, float bx, float by, float bz,
                      float cx, float cy, float cz, float dx, float dy, float dz)
    {
        float u0 = BlockAtlas.u0(tile), u1 = BlockAtlas.u1(tile);
        float v0 = BlockAtlas.v0(tile), v1 = BlockAtlas.v1(tile);

        vertex(ax, ay, az, u0, v0, shade);
        vertex(bx, by, bz, u0, v1, shade);
        vertex(cx, cy, cz, u1, v1, shade);

        vertex(ax, ay, az, u0, v0, shade);
        vertex(cx, cy, cz, u1, v1, shade);
        vertex(dx, dy, dz, u1, v0, shade);
    }

    private void vertex(float x, float y, float z, float u, float v, float shade)
    {
        data[floats++] = x;
        data[floats++] = y;
        data[floats++] = z;
        data[floats++] = u;
        data[floats++] = v;
        data[floats++] = shade;
    }

    public void delete()
    {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        shader.delete();
        // Atlas si maže ten, kdo ho vytvořil (Main).
    }
}
