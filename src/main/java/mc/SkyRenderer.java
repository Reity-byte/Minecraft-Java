package mc;

import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL33.*;

/**
 * Slunce, měsíc a hvězdy.
 *
 * ---------------------------------------------------------------------------
 * Obloha je PEVNÁ SKOŘÁPKA kolem počátku, ne objekt ve světě. Funguje to jen
 * díky tomu, na čem stojí celý renderer: kamera sedí v počátku a svět se
 * posouvá k ní (viz Shaders). Nebeská tělesa se proto kreslí na pevném
 * poloměru kolem nuly a nemusí se s hráčem nikam posouvat - obloha tak
 * zůstává "nekonečně daleko" sama od sebe.
 *
 * Denní doba otáčí celou skořápkou najednou. Otočení se vmíchá do matice
 * na CPU, takže se geometrie hvězd nahraje do VBO JEDNOU a pak už se jen
 * kreslí - jinak by se každý frame přenášelo přes pět tisíc vrcholů (5412).
 * ---------------------------------------------------------------------------
 */
public class SkyRenderer {

    /** Poloměr skořápky. Musí být menší než blízká ořezová rovina krát hodně a přitom daleko. */
    private static final float RADIUS = 200f;

    private static final float SUN_SIZE  = 26f;
    private static final float MOON_SIZE = 20f;

    /**
     * Velikost hvězdy. Malá jako pixel by při pohybu blikala a mizela mezi
     * vzorky, tohle je zhruba desetina slunce - je vidět a nepůsobí jako mucha.
     */
    private static final float STAR_SIZE = 2.6f;

    private static final int STAR_COUNT = 900;

    private static final float[] SUN_COLOR  = {1.0f, 0.96f, 0.74f, 1f};
    private static final float[] MOON_COLOR = {0.86f, 0.88f, 0.95f, 1f};
    private static final float[] STAR_COLOR = {1f, 1f, 1f, 1f};

    private static final int VERTICES_PER_QUAD = 6;

    /** Obrys bloku potřebuje totéž: pozice a jedna barva v uniformu. */
    private final ShaderProgram shader =
            new ShaderProgram(Shaders.OUTLINE_VERTEX, Shaders.OUTLINE_FRAGMENT);

    private final int vao;
    private final int vbo;

    // Rozsahy v jednom společném bufferu.
    private static final int SUN_FIRST  = 0;
    private static final int MOON_FIRST = VERTICES_PER_QUAD;
    private static final int STAR_FIRST = 2 * VERTICES_PER_QUAD;

    private final Matrix4f rotated = new Matrix4f();

    public SkyRenderer()
    {
        float[] data = buildGeometry();

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);

        glBindVertexArray(0);
    }

    /**
     * Slunce v nadhlavníku, měsíc naproti, hvězdy rozházené po celé kouli.
     * Souřadnice jsou v "nebeské" soustavě, kterou pak otáčí denní doba.
     */
    static float[] buildGeometry()
    {
        float[] data = new float[(2 + STAR_COUNT) * VERTICES_PER_QUAD * 3];
        int i = 0;

        i = quad(data, i, 0f, 1f, 0f, SUN_SIZE);
        i = quad(data, i, 0f, -1f, 0f, MOON_SIZE);

        for(int star = 0; star < STAR_COUNT; star++)
        {
            // Rovnoměrně po kouli: z je rovnoměrné, úhel taky - jinak by se
            // hvězdy nahustily k pólům.
            float u = hash01(star, 1);
            float v = hash01(star, 2);

            float z = 2f * u - 1f;
            float r = (float) Math.sqrt(Math.max(0f, 1f - z * z));
            float angle = (float) (v * Math.PI * 2);

            i = quad(data, i,
                    r * (float) Math.cos(angle), z, r * (float) Math.sin(angle),
                    STAR_SIZE * (0.6f + hash01(star, 3)));
        }

        return data;
    }

    /**
     * Čtverec kolmý na směr pohledu, na skořápce ve směru (dx, dy, dz).
     *
     * Kolmé osy se odvodí z libovolného vektoru, který není rovnoběžný se
     * směrem - proto ta výjimka pro směr nahoru a dolů.
     */
    static int quad(float[] data, int i, float dx, float dy, float dz, float size)
    {
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= length; dy /= length; dz /= length;

        float ux = 0f, uy = 1f, uz = 0f;

        if(Math.abs(dy) > 0.99f)
        {
            ux = 1f; uy = 0f; uz = 0f;
        }

        // right = up x dir, pak up' = dir x right
        float rx = uy * dz - uz * dy;
        float ry = uz * dx - ux * dz;
        float rz = ux * dy - uy * dx;

        float rl = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rl; ry /= rl; rz /= rl;

        float vx = dy * rz - dz * ry;
        float vy = dz * rx - dx * rz;
        float vz = dx * ry - dy * rx;

        float cx = dx * RADIUS, cy = dy * RADIUS, cz = dz * RADIUS;
        float h = size / 2f;

        float[] corner = {
                cx - rx * h - vx * h, cy - ry * h - vy * h, cz - rz * h - vz * h,
                cx + rx * h - vx * h, cy + ry * h - vy * h, cz + rz * h - vz * h,
                cx + rx * h + vx * h, cy + ry * h + vy * h, cz + rz * h + vz * h,
                cx - rx * h + vx * h, cy - ry * h + vy * h, cz - rz * h + vz * h
        };

        // ⚠️ Pořadí je OTOČENÉ proti obvyklému 0,1,2 / 0,2,3.
        //
        // Skořápku vidíme ZEVNITŘ. Osy r a v vycházejí tak, že r x v míří
        // od počátku ven, takže obvyklé pořadí by dalo stěnu odvrácenou
        // od kamery - a backface culling ji beze slova zahodí. Celá obloha
        // pak byla neviditelná, aniž by cokoliv zahlásilo chybu.
        int[] order = {0, 2, 1, 0, 3, 2};

        for(int o : order)
        {
            data[i++] = corner[o * 3];
            data[i++] = corner[o * 3 + 1];
            data[i++] = corner[o * 3 + 2];
        }

        return i;
    }

    /** Deterministický rozhoz 0-1. Hvězdy tak vyjdou pokaždé stejné. */
    private static float hash01(int index, int salt)
    {
        int h = index * 374761393 + salt * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) >>> 1) / (float) Integer.MAX_VALUE;
    }

    /**
     * ⚠️ Kreslit PŘED světem, s vypnutým testem i zápisem hloubky.
     *
     * Obloha je nekonečně daleko, takže ji má přebít cokoliv, co se nakreslí
     * po ní. Kdyby se kreslila až po světě, přetřela by terén; kdyby zapisovala
     * hloubku, zaclonila by ho.
     */
    public void draw(Matrix4f viewProjection, DayCycle day)
    {
        // Otočení skořápky se vmíchá do matice - geometrie zůstává, jak je.
        rotated.set(viewProjection).rotateZ(day.skyAngle());

        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.bind();
        shader.setMatrix4("uViewProjection", rotated);
        shader.setVector3("uChunkOffset", 0f, 0f, 0f);

        glBindVertexArray(vao);

        // Hvězdy první: slunce ani měsíc přes ně nemají prosvítat.
        float night = day.nightFactor();

        if(night > 0.01f)
        {
            shader.setVector4("uColor", STAR_COLOR[0], STAR_COLOR[1], STAR_COLOR[2], night);
            glDrawArrays(GL_TRIANGLES, STAR_FIRST, STAR_COUNT * VERTICES_PER_QUAD);
            GlStats.countDraw();
        }

        shader.setVector4("uColor", SUN_COLOR[0], SUN_COLOR[1], SUN_COLOR[2], SUN_COLOR[3]);
        glDrawArrays(GL_TRIANGLES, SUN_FIRST, VERTICES_PER_QUAD);
        GlStats.countDraw();

        shader.setVector4("uColor", MOON_COLOR[0], MOON_COLOR[1], MOON_COLOR[2], MOON_COLOR[3]);
        glDrawArrays(GL_TRIANGLES, MOON_FIRST, VERTICES_PER_QUAD);
        GlStats.countDraw();

        glBindVertexArray(0);

        glDisable(GL_BLEND);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
    }

    public void delete()
    {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        shader.delete();
    }
}
