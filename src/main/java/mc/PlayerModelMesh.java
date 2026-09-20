package mc;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.Arrays;

import static org.lwjgl.opengl.GL33.*;

/**
 * Model postavy ve třetí osobě: hlava, trup, dvě ruce a dvě nohy z kvádrů
 * a v pravé ruce držený blok.
 *
 * ---------------------------------------------------------------------------
 * Rozměry, klouby i UV jsou přesně z Minecraftu, v jeho PIXELECH (postava je
 * 32 px vysoká) - a UV míří do šablony skinu 64x64 z Minecraftu. Skutečný
 * skin z PNG se proto dá podstrčit místo vygenerovaného beze změny jediného
 * čísla tady; viz Textures.playerSkin().
 *
 * Model se transformuje NA CPU a staví znovu každý frame, stejně jako
 * předměty na zemi: je to 216 vrcholů, takže víc stojí draw call než výpočet.
 * Vertex formát je světový (pozice, uv, sluneční a blokové světlo), takže se
 * kreslí světovým shaderem - postava má stejné světlo i mlhu jako terén.
 * Pozice jsou relativní ke kameře, odečtené v double (viz Shaders).
 *
 * Ve VBO leží dvě sady za sebou: nejdřív postava (textura skinu), pak držený
 * blok (atlas bloků). Kreslí se dvěma glDrawArrays se stejným VAO.
 * ---------------------------------------------------------------------------
 *
 * build() nesahá na GL a jde otestovat headless; upload() a draw*() ano.
 */
public class PlayerModelMesh {

    /** pozice (3) + uv (2) + sluneční (1) + blokové světlo (1), jako ChunkMesh */
    static final int FLOATS_PER_VERTEX = 7;
    static final int VERTICES_PER_BOX = 36;

    /** Jeden pixel modelu v blocích: 32 px postavy = výška hitboxu. */
    public static final float PX = Player.HEIGHT / 32f;

    /** Šablona skinu Minecraftu je 64x64 pixelů. */
    public static final int SKIN_SIZE = 64;

    /**
     * Jeden díl těla: kvádr v pixelech modelu (y od chodidel nahoru, postava
     * kouká po +Z, její pravice je -X), kloub, kolem kterého se otáčí,
     * a levý horní roh jeho rozbalení ve skinu.
     */
    record Part(float x0, float y0, float z0, float x1, float y1, float z1,
                float pivotX, float pivotY, float pivotZ, int skinU, int skinV) {}

    // Pořadí dílů určuje i pořadí vrcholů - PART_* jsou indexy pro testy.
    static final int PART_HEAD = 0, PART_BODY = 1, PART_RIGHT_ARM = 2,
            PART_LEFT_ARM = 3, PART_RIGHT_LEG = 4, PART_LEFT_LEG = 5;

    static final Part[] PARTS = {
            new Part(-4, 24, -4,   4, 32, 4,    0, 24, 0,   0, 0),    // hlava
            new Part(-4, 12, -2,   4, 24, 2,    0, 24, 0,  16, 16),   // trup
            new Part(-8, 12, -2,  -4, 24, 2,   -5, 22, 0,  40, 16),   // pravá ruka
            new Part( 4, 12, -2,   8, 24, 2,    5, 22, 0,  32, 48),   // levá ruka
            new Part(-4,  0, -2,   0, 12, 2,   -2, 12, 0,   0, 16),   // pravá noha
            new Part( 0,  0, -2,   4, 12, 2,    2, 12, 0,  16, 48)    // levá noha
    };

    /**
     * Držený blok: kostka 6 px (3/8 bloku, jako v Minecraftu) těsně před
     * pěstí pravé ruky. Souřadnice jsou vůči RAMENNÍMU KLOUBU, takže blok
     * jde s rukou, když se rozmáchne. Otočený o 45°, ať jsou vidět dvě stěny.
     */
    static final float ITEM_SIZE = 6f;
    static final float ITEM_X = -1f, ITEM_Y = -10.5f, ITEM_Z = 2.5f;

    // Stejné odstíny stěn jako ChunkMesh - viz shade().
    private static final float SHADE_TOP    = 1.00f;
    private static final float SHADE_SIDE_Z = 0.80f;
    private static final float SHADE_SIDE_X = 0.60f;
    private static final float SHADE_BOTTOM = 0.50f;

    private float[] data = new float[VERTICES_PER_BOX * 12 * FLOATS_PER_VERTEX];
    private int floats = 0;
    private int skinVertices = 0;
    private int itemVertices = 0;

    // Stav právě stavěného kvádru.
    private final Matrix4f model = new Matrix4f();
    private final Matrix4f part = new Matrix4f();
    private final Vector3f scratch = new Vector3f();
    private float sky, block;

    /** Uložený odkaz, ať emitPart() nevyrábí každý frame nový objekt. */
    private final FaceSink skinFace = this::face;

    private int vao = 0;
    private int vbo = 0;
    private FloatBuffer upload;

    // ------------------------------------------------------------------
    // stavba na CPU
    // ------------------------------------------------------------------

    /**
     * Postaví postavu v dané póze.
     *
     * @param feetX/Y/Z  chodidla (Player.x, y, z)
     * @param yawDegrees kam se postava dívá - stejná úmluva jako Camera.yaw
     * @param heldBlock  blok v pravé ruce, World.AIR když nic
     * @param sky, block světlo 0 až 1 - jedno pro celou postavu, jako u ruky
     *                   v první osobě
     * @param camX/Y/Z   kamera; vrcholy vyjdou relativně k ní
     */
    public void build(PlayerPose pose, float feetX, float feetY, float feetZ, float yawDegrees,
                      byte heldBlock, float sky, float block, float camX, float camY, float camZ)
    {
        floats = 0;
        this.sky = sky;
        this.block = block;

        // Model kouká po +Z, hráč po (cos yaw, sin yaw). Otočení o 90° - yaw
        // kolem svislé osy převede jedno na druhé (viz rotateY v JOML:
        // x' = x cos + z sin, z' = -x sin + z cos).
        model.identity()
                .translate((float) (feetX - (double) camX),
                        (float) (feetY - (double) camY),
                        (float) (feetZ - (double) camZ))
                .rotateY((float) Math.toRadians(90f - yawDegrees))
                .scale(PX);

        emitPart(PARTS[PART_HEAD], pose.headPitch(), 0f, 0f);
        emitPart(PARTS[PART_BODY], 0f, 0f, 0f);
        emitPart(PARTS[PART_RIGHT_ARM], pose.rightArmX(), pose.rightArmY(), pose.rightArmZ());
        emitPart(PARTS[PART_LEFT_ARM], pose.leftArmX(), 0f, pose.leftArmZ());
        emitPart(PARTS[PART_RIGHT_LEG], pose.rightLegX(), 0f, 0f);
        emitPart(PARTS[PART_LEFT_LEG], pose.leftLegX(), 0f, 0f);

        skinVertices = floats / FLOATS_PER_VERTEX;

        if(heldBlock != World.AIR)
        {
            emitHeldBlock(pose, heldBlock);
        }

        itemVertices = floats / FLOATS_PER_VERTEX - skinVertices;
    }

    /** Matice dílu: otočení kolem kloubu v pořadí Z, Y, X, jako v Minecraftu. */
    private Matrix4f partMatrix(Part p, float rx, float ry, float rz)
    {
        return part.set(model)
                .translate(p.pivotX(), p.pivotY(), p.pivotZ())
                .rotateZ(rz)
                .rotateY(ry)
                .rotateX(rx)
                .translate(-p.pivotX(), -p.pivotY(), -p.pivotZ());
    }

    private void emitPart(Part p, float rx, float ry, float rz)
    {
        partMatrix(p, rx, ry, rz);
        unfold(p, skinFace);
    }

    /**
     * Příjemce stěn z unfold(): čtyři rohy (poloha v pixelech modelu, u, v
     * v pixelech skinu) proti směru hodinových ručiček zvenku a normála stěny
     * v souřadnicích dílu.
     */
    interface FaceSink {
        void face(float ax, float ay, float az, float au, float av,
                  float bx, float by, float bz, float bu, float bv,
                  float cx, float cy, float cz, float cu, float cv,
                  float dx, float dy, float dz, float du, float dv,
                  float nx, float ny, float nz);
    }

    /**
     * Rozbalí kvádr dílu na šest stěn - JEDINÉ místo, kde se počítají UV do
     * skinu. Sdílí ho postava i holá ruka v první osobě (HeldItemRenderer),
     * takže ruka vypadá v obou pohledech stejně a skin se mění na jednom místě.
     *
     * Pořadí stěn: vršek, spodek, +X, -X, předek, záda.
     */
    static void unfold(Part p, FaceSink sink)
    {
        float x0 = p.x0(), x1 = p.x1();
        float y0 = p.y0(), y1 = p.y1();
        float z0 = p.z0(), z1 = p.z1();

        float w = x1 - x0, h = y1 - y0, d = z1 - z0;
        float u = p.skinU(), v = p.skinV();

        // ⚠️ UV jsou rozbalení kvádru z Minecraftu (ModelBox) v pixelech skinu:
        //
        //          [ vršek ][ spodek ]
        //   [ pravá ][ před ][ levá  ][ zadní ]
        //
        // "Pravá" je pravá strana POSTAVY (-X), tedy vlevo, když se na ni
        // díváš zepředu. Každý roh stěny dostane (u, v) podle toho, na kterém
        // okraji kvádru leží; v roste směrem DOLŮ po obrázku.
        float front = z1, back = z0;

        // +Y vršek: předek u v + d, zadek u v
        sink.face(x0, y1, z0, u + d, v,       x0, y1, z1, u + d, v + d,
                  x1, y1, z1, u + d + w, v + d, x1, y1, z0, u + d + w, v,       0, 1, 0);
        // -Y spodek
        sink.face(x0, y0, z0, u + d + w, v,   x1, y0, z0, u + d + 2 * w, v,
                  x1, y0, z1, u + d + 2 * w, v + d, x0, y0, z1, u + d + w, v + d, 0, -1, 0);
        // +X levá strana postavy: předek u u + d + w
        sink.face(x1, y0, back, u + 2 * d + w, v + d + h,  x1, y1, back, u + 2 * d + w, v + d,
                  x1, y1, front, u + d + w, v + d,         x1, y0, front, u + d + w, v + d + h, 1, 0, 0);
        // -X pravá strana postavy: předek u u + d
        sink.face(x0, y0, back, u, v + d + h,              x0, y0, front, u + d, v + d + h,
                  x0, y1, front, u + d, v + d,             x0, y1, back, u, v + d,             -1, 0, 0);
        // +Z předek (obličej): -X u u + d
        sink.face(x0, y0, z1, u + d, v + d + h,            x1, y0, z1, u + d + w, v + d + h,
                  x1, y1, z1, u + d + w, v + d,            x0, y1, z1, u + d, v + d,            0, 0, 1);
        // -Z záda: zrcadlově, -X u u + 2d + 2w
        sink.face(x0, y0, z0, u + 2 * d + 2 * w, v + d + h, x0, y1, z0, u + 2 * d + 2 * w, v + d,
                  x1, y1, z0, u + 2 * d + w, v + d,        x1, y0, z0, u + 2 * d + w, v + d + h, 0, 0, -1);
    }

    /**
     * Stěna jako dva trojúhelníky (0,1,2) + (0,2,3). Rohy jsou proti směru
     * hodinových ručiček zvenku, stejně jako v ChunkMesh - a matice jsou jen
     * otočení, posuny a kladné zvětšení, takže pořadí nepřevrátí.
     * u, v jsou v pixelech skinu.
     */
    private void face(float ax, float ay, float az, float au, float av,
                      float bx, float by, float bz, float bu, float bv,
                      float cx, float cy, float cz, float cu, float cv,
                      float dx, float dy, float dz, float du, float dv,
                      float nx, float ny, float nz)
    {
        float shade = shade(nx, ny, nz);
        float s = 1f / SKIN_SIZE;

        vertex(ax, ay, az, au * s, av * s, shade);
        vertex(bx, by, bz, bu * s, bv * s, shade);
        vertex(cx, cy, cz, cu * s, cv * s, shade);

        vertex(ax, ay, az, au * s, av * s, shade);
        vertex(cx, cy, cz, cu * s, cv * s, shade);
        vertex(dx, dy, dz, du * s, dv * s, shade);
    }

    /**
     * Ztmavení stěny podle toho, kam míří VE SVĚTĚ, ne v modelu.
     *
     * Terén má pevná ztmavení podle os (vršek 1, boky X 0,6, boky Z 0,8,
     * spodek 0,5). Postava se ale otáčí, takže kdyby se odstín vázal ke stěně
     * modelu, pravý bok by byl tmavý i ve chvíli, kdy míří tam, kam ve světě
     * svítí nejvíc. Normála se proto otočí stejnou maticí jako vrcholy
     * a odstíny os se smíchají podle druhých mocnin jejích složek - ty dávají
     * součet 1, takže stěna mířící přesně podél osy dostane přesně odstín té
     * osy a šikmá stěna plynulý mezistupeň.
     */
    private float shade(float nx, float ny, float nz)
    {
        part.transformDirection(scratch.set(nx, ny, nz)).normalize();

        float x2 = scratch.x * scratch.x;
        float y2 = scratch.y * scratch.y;
        float z2 = scratch.z * scratch.z;

        return x2 * SHADE_SIDE_X + z2 * SHADE_SIDE_Z
                + y2 * (scratch.y > 0 ? SHADE_TOP : SHADE_BOTTOM);
    }

    /** Roh v souřadnicích dílu → poloha vůči kameře přes matici dílu. */
    private void vertex(float x, float y, float z, float u, float v, float shade)
    {
        if(floats + FLOATS_PER_VERTEX > data.length)
        {
            data = Arrays.copyOf(data, data.length * 2);
        }

        part.transformPosition(scratch.set(x, y, z));

        data[floats++] = scratch.x;
        data[floats++] = scratch.y;
        data[floats++] = scratch.z;
        data[floats++] = u;
        data[floats++] = v;
        data[floats++] = sky * shade;
        data[floats++] = block * shade;
    }

    /**
     * Držený blok: tvar z BlockModels a textura z atlasu, jako položka na zemi.
     * Matice navazuje na matici pravé ruky, takže blok jde s ní.
     */
    private void emitHeldBlock(PlayerPose pose, byte heldBlock)
    {
        Part arm = PARTS[PART_RIGHT_ARM];

        int top = BlockAtlas.tile(heldBlock, BlockAtlas.FACE_TOP);
        int bottom = BlockAtlas.tile(heldBlock, BlockAtlas.FACE_BOTTOM);
        int side = BlockAtlas.tile(heldBlock, BlockAtlas.FACE_SIDE);

        for(BlockModels.BlockBox box : BlockModels.of(heldBlock))
        {
            partMatrix(arm, pose.rightArmX(), pose.rightArmY(), pose.rightArmZ())
                    .translate(arm.pivotX() + ITEM_X, arm.pivotY() + ITEM_Y, arm.pivotZ() + ITEM_Z)
                    .rotateY((float) Math.toRadians(45))
                    .scale(ITEM_SIZE)
                    // Model bloku má počátek v rohu; střed kostky na místo pěsti.
                    .translate(-0.5f, -0.5f, -0.5f);

            float x0 = box.minX(), x1 = box.maxX();
            float y0 = box.minY(), y1 = box.maxY();
            float z0 = box.minZ(), z1 = box.maxZ();

            // Rohy a UV v pořadí ChunkMesh.emitBox(), aby textura ležela
            // stejně jako na bloku ve světě.
            blockFace(top, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0,
                    UV_A, x0, x1, z0, z1, 0, 1, 0);
            blockFace(bottom, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1,
                    UV_B, x0, x1, z0, z1, 0, -1, 0);
            blockFace(side, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1,
                    UV_A, z0, z1, y0, y1, 1, 0, 0);
            blockFace(side, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0,
                    UV_B, z0, z1, y0, y1, -1, 0, 0);
            blockFace(side, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1,
                    UV_B, x0, x1, y0, y1, 0, 0, 1);
            blockFace(side, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0,
                    UV_A, x0, x1, y0, y1, 0, 0, -1);
        }
    }

    /** Stejné dva vzorce rohů dlaždice jako v ChunkMesh - viz jeho UV_A a UV_B. */
    private static final float[] UV_A = {0, 0,  0, 1,  1, 1,  1, 0};
    private static final float[] UV_B = {0, 0,  1, 0,  1, 1,  0, 1};

    private void blockFace(int tile,
                           float ax, float ay, float az, float bx, float by, float bz,
                           float cx, float cy, float cz, float dx, float dy, float dz,
                           float[] pattern, float sMin, float sMax, float tMin, float tMax,
                           float nx, float ny, float nz)
    {
        float shade = shade(nx, ny, nz);

        float u0 = BlockAtlas.u0(tile), u1 = BlockAtlas.u1(tile);
        float v0 = BlockAtlas.v0(tile), v1 = BlockAtlas.v1(tile);

        float au = lerp(u0, u1, pattern[0] == 0 ? sMin : sMax), av = lerp(v0, v1, pattern[1] == 0 ? tMin : tMax);
        float bu = lerp(u0, u1, pattern[2] == 0 ? sMin : sMax), bv = lerp(v0, v1, pattern[3] == 0 ? tMin : tMax);
        float cu = lerp(u0, u1, pattern[4] == 0 ? sMin : sMax), cv = lerp(v0, v1, pattern[5] == 0 ? tMin : tMax);
        float du = lerp(u0, u1, pattern[6] == 0 ? sMin : sMax), dv = lerp(v0, v1, pattern[7] == 0 ? tMin : tMax);

        vertex(ax, ay, az, au, av, shade);
        vertex(bx, by, bz, bu, bv, shade);
        vertex(cx, cy, cz, cu, cv, shade);

        vertex(ax, ay, az, au, av, shade);
        vertex(cx, cy, cz, cu, cv, shade);
        vertex(dx, dy, dz, du, dv, shade);
    }

    private static float lerp(float a, float b, float t)
    {
        return a + (b - a) * t;
    }

    public int skinVertexCount() { return skinVertices; }
    public int itemVertexCount() { return itemVertices; }

    public boolean isEmpty()
    {
        return floats == 0;
    }

    /** Surová data vrcholů pro testy; platných je (skin + item) * FLOATS_PER_VERTEX. */
    float[] vertices()
    {
        return data;
    }

    // ------------------------------------------------------------------
    // nahrání a kreslení
    // ------------------------------------------------------------------

    /** Musí se volat na vlákně s aktivním GL kontextem. */
    public void upload()
    {
        if(floats == 0)
        {
            return;
        }

        if(vao == 0)
        {
            vao = glGenVertexArrays();
            vbo = glGenBuffers();

            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);

            int stride = FLOATS_PER_VERTEX * Float.BYTES;

            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);                 // pozice
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3L * Float.BYTES);   // uv
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 5L * Float.BYTES);   // sluneční
            glEnableVertexAttribArray(2);
            glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 6L * Float.BYTES);   // blokové
            glEnableVertexAttribArray(3);
        }

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        if(upload == null || upload.capacity() < data.length)
        {
            upload = BufferUtils.createFloatBuffer(data.length);
            glBufferData(GL_ARRAY_BUFFER, (long) data.length * Float.BYTES, GL_STREAM_DRAW);
        }

        upload.clear();
        upload.put(data, 0, floats);
        upload.flip();

        glBufferSubData(GL_ARRAY_BUFFER, 0, upload);
    }

    /** Postava - volat s navázanou texturou skinu. */
    public void drawSkin()
    {
        if(skinVertices == 0)
        {
            return;
        }

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, skinVertices);
        GlStats.countDraw();
        glBindVertexArray(0);
    }

    /** Držený blok - volat s navázaným atlasem bloků. */
    public void drawItem()
    {
        if(itemVertices == 0)
        {
            return;
        }

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, skinVertices, itemVertices);
        GlStats.countDraw();
        glBindVertexArray(0);
    }

    public void delete()
    {
        if(vao != 0)
        {
            glDeleteVertexArrays(vao);
            glDeleteBuffers(vbo);
            vao = 0;
            vbo = 0;
        }

        upload = null;
        floats = 0;
        skinVertices = 0;
        itemVertices = 0;
    }
}
