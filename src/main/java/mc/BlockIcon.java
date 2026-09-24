package mc;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.Arrays;

import static org.lwjgl.opengl.GL33.*;

/**
 * Izometrická ikona bloku - do hotbaru i do slotů inventáře.
 *
 * ---------------------------------------------------------------------------
 * Kreslí se podle MODELU bloku, ne jako pevná krychle. Pochodeň je v ikoně
 * tenká tyčka a plot sloupek, stejně jako ve světě; dokud se kreslila krychle,
 * vypadal plot v hotbaru jako kostka prken a nešel od nich rozeznat.
 *
 * Textura se bere z blokového atlasu a UV z rozsahu kvádru - přesně jako
 * v ChunkMesh, takže ikona a blok ve světě vypadají stejně.
 *
 * Vlastní shader a VAO: vertex je pozice(2) + uv(2) + odstín(1), což se
 * do formátu Renderer2D (pozice + barva) nevejde.
 *
 * ⚠️ JEDEN DRAW CALL NA CELOU DÁVKU begin() … end(), NE NA KVÁDR. Dřív
 * každý kvádr každé ikony dělal vlastní glBufferSubData do téhož místa téhož
 * VBO a vlastní glDrawArrays: creative inventář ~82 draw callů za frame jen
 * za ikony, přehled v Recipes až ~98 - přesně ten vzor, který na macOS
 * sekal lab (viz "Výkon labu" v ARCHITECTURE.md), a přepis bufferu, ze
 * kterého předchozí draw call ještě čte, nutil ovladač čekat. Teď draw()
 * jen skládá vrcholy a end() je pošle najednou do čerstvě alokovaného
 * bufferu (orphaning - ovladač nemusí čekat na předchozí frame).
 *
 * Pořadí se zachová: trojúhelníky jednoho draw callu se kreslí v pořadí,
 * v jakém přišly, takže kurzor nakreslený naposled zůstane nahoře stejně
 * jako dřív. Mezi begin() a end() se proto nesmí kreslit nic jiného, co
 * by mělo ležet mezi ikonami - dnes to nikdo nedělá.
 * ---------------------------------------------------------------------------
 */
public class BlockIcon {

    // Stejné odstíny jako SHADE_* v ChunkMesh, aby ikona seděla se světem.
    private static final float SHADE_TOP   = 1.00f;
    private static final float SHADE_LEFT  = 0.80f;   // stěna +Z
    private static final float SHADE_RIGHT = 0.60f;   // stěna +X

    private static final int FLOATS_PER_VERTEX = 5;
    private static final int VERTICES_PER_QUAD = 6;

    /** Tři viditelné stěny na kvádr. */
    private static final int FLOATS_PER_BOX = 3 * VERTICES_PER_QUAD * FLOATS_PER_VERTEX;

    /** Počáteční místo: zhruba plný creative inventář krychlí. Roste podle potřeby. */
    private static final int INITIAL_FLOATS = 96 * FLOATS_PER_BOX;

    private final ShaderProgram shader =
            new ShaderProgram(Shaders.UI_BLOCK_VERTEX, Shaders.UI_BLOCK_FRAGMENT);

    private final Texture atlas;

    private final int vao;
    private final int vbo;

    private float[] scratch = new float[INITIAL_FLOATS];
    private FloatBuffer upload = BufferUtils.createFloatBuffer(INITIAL_FLOATS);

    private int floats = 0;

    // Střed a měřítko aktuálně kreslené ikony - viz project().
    private float centerX, centerY, halfWidth, quarter;

    public BlockIcon(Texture atlas)
    {
        this.atlas = atlas;

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) INITIAL_FLOATS * Float.BYTES, GL_STREAM_DRAW);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 4L * Float.BYTES);
        glEnableVertexAttribArray(2);

        glBindVertexArray(0);
    }

    public void begin(int screenWidth, int screenHeight)
    {
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.bind();
        shader.setVector2("uScreenSize", screenWidth, screenHeight);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlas.id());
        shader.setInt("uAtlas", 0);

        glBindVertexArray(vao);

        floats = 0;
    }

    /** Pošle všechny ikony od begin() jedním draw callem a vrátí stav GL. */
    public void end()
    {
        flush();

        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    /**
     * Nakreslí blok jako izometrický tvar do čtverce o straně size.
     *
     * Vidět jsou vždycky jen tři stěny každého kvádru: horní (+Y), levá (+Z)
     * a pravá (+X). Zbylé tři jsou odvrácené, takže se nekreslí vůbec - žádný
     * depth test tu není a kreslit je by znamenalo, že by mohly přebít ty přední.
     */
    public void draw(float x, float y, float size, byte block)
    {
        centerX = x + size / 2f;
        centerY = y + size / 2f;
        halfWidth = size / 2f;
        quarter = size / 4f;

        int top = BlockAtlas.tile(block, BlockAtlas.FACE_TOP);
        int side = BlockAtlas.tile(block, BlockAtlas.FACE_SIDE);

        for(BlockModels.BlockBox box : BlockModels.of(block))
        {
            ensureRoom();

            // Horní stěna (+Y): u podle x, v podle z.
            face(top, SHADE_TOP,
                    box.minX(), box.maxY(), box.minZ(),  box.minX(), box.minZ(),
                    box.minX(), box.maxY(), box.maxZ(),  box.minX(), box.maxZ(),
                    box.maxX(), box.maxY(), box.maxZ(),  box.maxX(), box.maxZ(),
                    box.maxX(), box.maxY(), box.minZ(),  box.maxX(), box.minZ());

            // Levá stěna (+Z): u podle x, v podle y.
            face(side, SHADE_LEFT,
                    box.maxX(), box.maxY(), box.maxZ(),  box.maxX(), box.maxY(),
                    box.minX(), box.maxY(), box.maxZ(),  box.minX(), box.maxY(),
                    box.minX(), box.minY(), box.maxZ(),  box.minX(), box.minY(),
                    box.maxX(), box.minY(), box.maxZ(),  box.maxX(), box.minY());

            // Pravá stěna (+X): u podle z, v podle y.
            face(side, SHADE_RIGHT,
                    box.maxX(), box.maxY(), box.maxZ(),  box.maxZ(), box.maxY(),
                    box.maxX(), box.minY(), box.maxZ(),  box.maxZ(), box.minY(),
                    box.maxX(), box.minY(), box.minZ(),  box.minZ(), box.minY(),
                    box.maxX(), box.maxY(), box.minZ(),  box.minZ(), box.maxY());
        }
    }

    private void ensureRoom()
    {
        if(floats + FLOATS_PER_BOX > scratch.length)
        {
            scratch = Arrays.copyOf(scratch, scratch.length * 2);
        }
    }

    /**
     * Izometrická projekce bodu z bloku (0-1) do pixelů obrazovky.
     *
     * Poměr 2:1 - posun o blok do strany je půl šířky ikony vodorovně
     * a čtvrtina svisle, posun nahoru je polovina výšky.
     */
    private float projectX(float bx, float bz)
    {
        return centerX + (bx - bz) * halfWidth;
    }

    private float projectY(float bx, float by, float bz)
    {
        return centerY + (2f - bx - bz) * quarter + (by - 1f) * 2f * quarter;
    }

    /** Jedna stěna: čtyři rohy, každý se svou pozicí v bloku a svým (u, v) v dlaždici. */
    private void face(int tile, float shade,
                      float ax, float ay, float az, float au, float av,
                      float bx, float by, float bz, float bu, float bv,
                      float cx, float cy, float cz, float cu, float cv,
                      float dx, float dy, float dz, float du, float dv)
    {
        float u0 = BlockAtlas.u0(tile), u1 = BlockAtlas.u1(tile);
        float v0 = BlockAtlas.v0(tile), v1 = BlockAtlas.v1(tile);

        vertex(projectX(ax, az), projectY(ax, ay, az), lerp(u0, u1, au), lerp(v0, v1, av), shade);
        vertex(projectX(bx, bz), projectY(bx, by, bz), lerp(u0, u1, bu), lerp(v0, v1, bv), shade);
        vertex(projectX(cx, cz), projectY(cx, cy, cz), lerp(u0, u1, cu), lerp(v0, v1, cv), shade);

        vertex(projectX(ax, az), projectY(ax, ay, az), lerp(u0, u1, au), lerp(v0, v1, av), shade);
        vertex(projectX(cx, cz), projectY(cx, cy, cz), lerp(u0, u1, cu), lerp(v0, v1, cv), shade);
        vertex(projectX(dx, dz), projectY(dx, dy, dz), lerp(u0, u1, du), lerp(v0, v1, dv), shade);
    }

    private static float lerp(float a, float b, float t)
    {
        return a + (b - a) * t;
    }

    private void vertex(float x, float y, float u, float v, float shade)
    {
        scratch[floats++] = x;
        scratch[floats++] = y;
        scratch[floats++] = u;
        scratch[floats++] = v;
        scratch[floats++] = shade;
    }

    private void flush()
    {
        if(floats == 0)
        {
            return;
        }

        if(upload.capacity() < floats)
        {
            upload = BufferUtils.createFloatBuffer(scratch.length);
        }

        upload.clear();
        upload.put(scratch, 0, floats);
        upload.flip();

        // glBufferData, ne SubData: nový obsah jde do nového úložiště, takže
        // ovladač nečeká, až draw call minulého framu dočte to staré.
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, upload, GL_STREAM_DRAW);

        glDrawArrays(GL_TRIANGLES, 0, floats / FLOATS_PER_VERTEX);
        GlStats.countDraw();

        floats = 0;
    }

    public void delete()
    {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        shader.delete();
        // Atlas si maže ten, kdo ho vytvořil (Main).
    }
}
