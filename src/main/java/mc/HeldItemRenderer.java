package mc;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Co hráč drží v ruce v první osobě: blok, a když nedrží nic, holou pravou ruku.
 *
 * ---------------------------------------------------------------------------
 * Kreslí se ve VLASTNÍ perspektivě, ne ve světové. Je to kus geometrie kousek
 * před kamerou, ne objekt ve světě - kdyby se kreslil s maticí světa, prorážel
 * by stěny a mizel v blocích.
 *
 * ⚠️ Před kreslením se maže hloubkový buffer. Ruka má být VŽDY vepředu; bez
 * vyčištění by ji zakryl terén, do kterého hráč strká hlavu.
 *
 * Tvar bloku se bere z BlockModels, takže pochodeň se v ruce drží jako tyčka
 * a plot jako sloupek - stejně jako ikona v hotbaru a jako blok ve světě.
 *
 * Holá ruka je TENTÝŽ kvádr jako pravá ruka modelu postavy (PlayerModelMesh)
 * se stejným rozbalením do skinu, takže vypadá v první i třetí osobě stejně.
 * Kreslí se stejným shaderem, jen s navázaným skinem místo atlasu.
 * ---------------------------------------------------------------------------
 *
 * Stavba vrcholů a matice (build(), matrix()) nesahá na GL a jde otestovat
 * headless; draw() jen nahraje a vykreslí.
 */
public class HeldItemRenderer {

    private static final float SHADE_TOP    = 1.00f;
    private static final float SHADE_BOTTOM = 0.50f;
    private static final float SHADE_SIDE_X = 0.60f;
    private static final float SHADE_SIDE_Z = 0.80f;

    static final int FLOATS_PER_VERTEX = 6;   // pozice(3) + uv(2) + odstín(1)
    static final int VERTICES_PER_BOX = 36;

    /** Nejvíc kvádrů, které model může mít; se šesti stěnami po šesti vrcholech. */
    private static final int MAX_BOXES = 4;
    static final int MAX_FLOATS = MAX_BOXES * VERTICES_PER_BOX * FLOATS_PER_VERTEX;

    /** Holá ruka je pravá ruka modelu postavy - kvádr 4x12x4 px i jeho UV. */
    static final PlayerModelMesh.Part ARM = PlayerModelMesh.PARTS[PlayerModelMesh.PART_RIGHT_ARM];

    /**
     * Ramenní kloub holé ruky v prostoru pohledu: vpravo dole, těsně před
     * okem a pod okrajem obrazu - vidět je jen předloktí a pěst. Jednotky jsou
     * stejné jako u bloku v ruce (pixel modelu = 1/16, jako v Minecraftu).
     */
    private static final float ARM_X = 0.50f, ARM_Y = -0.82f, ARM_Z = -0.66f;
    private static final float ARM_SCALE = 1f / 16f;

    /** Klidová póza paže ve stupních - viz armMatrix(). */
    private static final float ARM_ROLL = 55f, ARM_RAISE = 125f, ARM_OUT = -25f;

    /** Máchnutí holé ruky ve stupních - viz armMatrix(). */
    private static final float SWING_ACROSS = 22f, SWING_LIFT = 18f, SWING_DROP = 25f, SWING_TURN = 65f;

    private final ShaderProgram shader =
            new ShaderProgram(Shaders.HAND_VERTEX, Shaders.HAND_FRAGMENT);

    private final Texture atlas;
    private final Texture skin;

    private final int vao;
    private final int vbo;

    private final float[] data = new float[MAX_FLOATS];
    private final FloatBuffer upload = BufferUtils.createFloatBuffer(MAX_FLOATS);
    private final Matrix4f mvp = new Matrix4f();

    /**
     * @param atlas atlas bloků - pro blok v ruce
     * @param skin  skin postavy - pro holou ruku
     */
    public HeldItemRenderer(Texture atlas, Texture skin)
    {
        this.atlas = atlas;
        this.skin = skin;

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
     * @param block     co je ve vybraném slotu; World.AIR = holá ruka
     * @param fastSwing rychlá křivka máchnutí - zdvih a překlopení zápěstí
     * @param slowSwing pomalá křivka máchnutí - odklon do strany
     * @param light 0 až 1 - ruka tmavne v jeskyni a v noci stejně jako svět
     */
    public void draw(int screenWidth, int screenHeight, float fovDegrees,
                     byte block, float fastSwing, float slowSwing, float light)
    {
        int floats = build(block, data);

        if(floats == 0)
        {
            return;
        }

        // ⚠️ Hloubka se maže, ne jen vypíná test: ruka musí být vepředu,
        // ale sama se sebou se hloubkově porovnávat má - jinak by zadní stěny
        // kostky přebily přední.
        glClear(GL_DEPTH_BUFFER_BIT);

        matrix(mvp, block, screenWidth, screenHeight, fovDegrees, fastSwing, slowSwing);

        shader.bind();
        shader.setMatrix4("uMvp", mvp);
        shader.setFloat("uLight", light);

        // Sampler je prostě jednotka 0 - "uAtlas" se jmenuje podle bloku,
        // pro holou ruku na ní leží skin. UV ruky míří do skinu.
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, isBareHand(block) ? skin.id() : atlas.id());
        shader.setInt("uAtlas", 0);

        // Alfa jen u vody, jako ve světě: blok z labu s vygumovaným pixelem
        // má v ruce černou skvrnu stejně jako položený, a holá ruka taky -
        // postava ve třetí osobě se kreslí bez míchání.
        shader.setFloat("uKeepAlpha", !isBareHand(block) && World.isTranslucent(block) ? 1f : 0f);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        upload.clear();
        upload.put(data, 0, floats);
        upload.flip();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, upload);
        glDrawArrays(GL_TRIANGLES, 0, floats / FLOATS_PER_VERTEX);
        GlStats.countDraw();
        glBindVertexArray(0);

        glDisable(GL_BLEND);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    // ------------------------------------------------------------------
    // stavba na CPU - bez GL
    // ------------------------------------------------------------------

    /** Prázdný slot = holá ruka ze skinu, cokoliv jiného = blok z atlasu. */
    static boolean isBareHand(byte block)
    {
        return block == World.AIR;
    }

    /**
     * Vrcholy toho, co je v ruce, do out (aspoň MAX_FLOATS). Vrací počet
     * zapsaných floatů.
     */
    static int build(byte block, float[] out)
    {
        return isBareHand(block) ? buildArm(out) : buildBlock(block, out);
    }

    /** Matice pro to, co je v ruce - blok a holá ruka se drží každý jinak. */
    static Matrix4f matrix(Matrix4f dest, byte block, int screenWidth, int screenHeight,
                           float fovDegrees, float fastSwing, float slowSwing)
    {
        return isBareHand(block)
                ? armMatrix(dest, screenWidth, screenHeight, fovDegrees, fastSwing, slowSwing)
                : blockMatrix(dest, screenWidth, screenHeight, fovDegrees, fastSwing, slowSwing);
    }

    /**
     * Blízká ořezová rovina je stejná jako u světa, daleká stačí malá - ruka
     * je kousek od oka a nic za ní se v tomhle průchodu nekreslí.
     */
    private static Matrix4f perspective(Matrix4f dest, int screenWidth, int screenHeight, float fovDegrees)
    {
        return dest.setPerspective((float) Math.toRadians(fovDegrees),
                (float) screenWidth / screenHeight, 0.05f, 10f);
    }

    /**
     * Umístění bloku a máchnutí.
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
     */
    static Matrix4f blockMatrix(Matrix4f dest, int screenWidth, int screenHeight, float fovDegrees,
                                float fastSwing, float slowSwing)
    {
        return perspective(dest, screenWidth, screenHeight, fovDegrees)
                .translate(0.56f, -0.52f, -0.72f)
                .rotateY((float) Math.toRadians(45f - 20f * slowSwing))
                .rotateZ((float) Math.toRadians(-20f * fastSwing))
                .rotateX((float) Math.toRadians(-80f * fastSwing))
                .scale(0.4f)
                // Model má počátek v rohu; tímhle se otáčí kolem svého středu.
                .translate(-0.5f, -0.5f, -0.5f);
    }

    /**
     * Umístění holé ruky a máchnutí. Klidová póza je póza z Minecraftu 1.8
     * (ItemRenderer.renderPlayerArm), jen rozepsaná na otočení, která jde
     * vysvětlit - jeho řetězec posunů a úhlů 120°/200°/-135° dá na obrazovce
     * prakticky totéž.
     *
     * Vrcholy jsou v pixelech modelu postavy (paže visí z ramene dolů po -Y,
     * postava kouká po +Z). Čteno od posledního řádku k prvnímu, tak jak se
     * transformuje vrchol:
     *
     *   1. ramenní kloub do počátku - otočení v bodech 4 a 5 jsou kolem ramene
     *   2. Ry(180°): postava kouká po +Z, pohled po -Z. Otočená zády ke
     *      kameře kouká tam co hráč a její pravá ruka je vpravo v obraze.
     *   3. pixel modelu = 1/16 jednotky pohledu, jako u Minecraftu
     *   4. klidová póza, tři otočení:
     *        Ry(55°)   kolem délky paže (ještě visí svisle) - vnějším bokem
     *                  ke kameře, jak ruku ukazuje Minecraft
     *        Rx(125°)  zdvih: 90° namíří paži dopředu do obrazu, dalších 35°
     *                  ji zvedne nad vodorovnou, pěst je výš než rameno
     *        Ry(-25°)  odklon doprava - pěst v pravé části obrazu
     *   5. Ry(65° * rychlá): při máchnutí se předloktí stočí dovnitř, pěst
     *      míří na zaměřovač a paže leží v obraze šikmo, jako v Minecraftu
     *   6. rameno na místo vpravo dole, pod okraj obrazu
     *   7. máchnutí jako OBLOUK KOLEM OKA (počátek pohledu), viz ⚠️ u
     *      blockMatrix. Otočení kolem oka sune ruku po kouli, takže po
     *      obrazovce jede obloukem a nemění velikost:
     *        Rx  rychlá křivka ji zvedne k zaměřovači, pomalá ji v druhé
     *            půlce stáhne POD klidovou polohu - zpátky jde jinudy, níž
     *        Ry  rychlá křivka ji přetáhne doleva ke středu obrazu
     *
     * Kolem samotného ramene by pěst k zaměřovači nedosáhla (je od něj jen
     * 10 px), paže by se jen postavila svisle - proto dva klouby.
     * Klid je táž matice s nulami, žádný zvláštní případ.
     */
    static Matrix4f armMatrix(Matrix4f dest, int screenWidth, int screenHeight, float fovDegrees,
                              float fastSwing, float slowSwing)
    {
        return perspective(dest, screenWidth, screenHeight, fovDegrees)
                .rotateY((float) Math.toRadians(SWING_ACROSS * fastSwing))
                .rotateX((float) Math.toRadians(SWING_LIFT * fastSwing - SWING_DROP * slowSwing))
                .translate(ARM_X, ARM_Y, ARM_Z)
                .rotateY((float) Math.toRadians(SWING_TURN * fastSwing))
                .rotateY((float) Math.toRadians(ARM_OUT))
                .rotateX((float) Math.toRadians(ARM_RAISE))
                .rotateY((float) Math.toRadians(ARM_ROLL))
                .scale(ARM_SCALE)
                .rotateY((float) Math.PI)
                .translate(-ARM.pivotX(), -ARM.pivotY(), -ARM.pivotZ());
    }

    /**
     * Holá ruka: šest stěn kvádru pravé ruky, rozbalení i UV sdílené
     * s modelem postavy (PlayerModelMesh.unfold). Odstíny stěn jako u bloku.
     */
    static int buildArm(float[] out)
    {
        ArmFaces faces = new ArmFaces(out);
        PlayerModelMesh.unfold(ARM, faces);
        return faces.floats;
    }

    /** Zapisuje stěny z unfold() ve formátu ruky; UV z pixelů skinu na 0 až 1. */
    private static final class ArmFaces implements PlayerModelMesh.FaceSink {

        private final float[] out;
        private int floats = 0;

        ArmFaces(float[] out)
        {
            this.out = out;
        }

        @Override
        public void face(float ax, float ay, float az, float au, float av,
                         float bx, float by, float bz, float bu, float bv,
                         float cx, float cy, float cz, float cu, float cv,
                         float dx, float dy, float dz, float du, float dv,
                         float nx, float ny, float nz)
        {
            // Stejné odstíny jako stěny bloku v ruce: podle osy stěny v modelu.
            float shade = ny > 0 ? SHADE_TOP
                    : ny < 0 ? SHADE_BOTTOM
                    : nx != 0 ? SHADE_SIDE_X : SHADE_SIDE_Z;
            float s = 1f / PlayerModelMesh.SKIN_SIZE;

            floats = vertex(out, floats, ax, ay, az, au * s, av * s, shade);
            floats = vertex(out, floats, bx, by, bz, bu * s, bv * s, shade);
            floats = vertex(out, floats, cx, cy, cz, cu * s, cv * s, shade);

            floats = vertex(out, floats, ax, ay, az, au * s, av * s, shade);
            floats = vertex(out, floats, cx, cy, cz, cu * s, cv * s, shade);
            floats = vertex(out, floats, dx, dy, dz, du * s, dv * s, shade);
        }
    }

    /** Všech šest stěn každého kvádru modelu - ruku vidíme z několika stran. */
    static int buildBlock(byte block, float[] out)
    {
        int at = 0;

        int top = BlockAtlas.tile(block, BlockAtlas.FACE_TOP);
        int bottom = BlockAtlas.tile(block, BlockAtlas.FACE_BOTTOM);
        int side = BlockAtlas.tile(block, BlockAtlas.FACE_SIDE);

        for(BlockModels.BlockBox box : BlockModels.of(block))
        {
            if(at + VERTICES_PER_BOX * FLOATS_PER_VERTEX > out.length)
            {
                break;
            }

            float x0 = box.minX(), x1 = box.maxX();
            float y0 = box.minY(), y1 = box.maxY();
            float z0 = box.minZ(), z1 = box.maxZ();

            at = face(out, at, top, SHADE_TOP,       x0, y1, z0,  x0, y1, z1,  x1, y1, z1,  x1, y1, z0);
            at = face(out, at, bottom, SHADE_BOTTOM, x0, y0, z0,  x1, y0, z0,  x1, y0, z1,  x0, y0, z1);
            at = face(out, at, side, SHADE_SIDE_X,   x1, y0, z0,  x1, y1, z0,  x1, y1, z1,  x1, y0, z1);
            at = face(out, at, side, SHADE_SIDE_X,   x0, y0, z0,  x0, y0, z1,  x0, y1, z1,  x0, y1, z0);
            at = face(out, at, side, SHADE_SIDE_Z,   x0, y0, z1,  x1, y0, z1,  x1, y1, z1,  x0, y1, z1);
            at = face(out, at, side, SHADE_SIDE_Z,   x0, y0, z0,  x0, y1, z0,  x1, y1, z0,  x1, y0, z0);
        }

        return at;
    }

    /** Celá dlaždice na stěnu; u tenkých modelů je textura oříznutá jinde než ve světě. */
    private static int face(float[] out, int at, int tile, float shade,
                            float ax, float ay, float az, float bx, float by, float bz,
                            float cx, float cy, float cz, float dx, float dy, float dz)
    {
        float u0 = BlockAtlas.u0(tile), u1 = BlockAtlas.u1(tile);
        float v0 = BlockAtlas.v0(tile), v1 = BlockAtlas.v1(tile);

        at = vertex(out, at, ax, ay, az, u0, v0, shade);
        at = vertex(out, at, bx, by, bz, u0, v1, shade);
        at = vertex(out, at, cx, cy, cz, u1, v1, shade);

        at = vertex(out, at, ax, ay, az, u0, v0, shade);
        at = vertex(out, at, cx, cy, cz, u1, v1, shade);
        at = vertex(out, at, dx, dy, dz, u1, v0, shade);
        return at;
    }

    private static int vertex(float[] out, int at, float x, float y, float z, float u, float v, float shade)
    {
        out[at++] = x;
        out[at++] = y;
        out[at++] = z;
        out[at++] = u;
        out[at++] = v;
        out[at++] = shade;
        return at;
    }

    public void delete()
    {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        shader.delete();
        // Atlas i skin si maže ten, kdo je vytvořil (Main).
    }
}
