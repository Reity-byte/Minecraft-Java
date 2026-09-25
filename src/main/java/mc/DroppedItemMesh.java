package mc;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/**
 * Předměty na zemi jako trojúhelníky pro SVĚTOVÝ shader.
 *
 * ---------------------------------------------------------------------------
 * Vertex formát je stejný jako v ChunkMesh - pozice, uv, sluneční a blokové
 * světlo - takže se položky kreslí týmž programem jako terén: se stejným
 * denním světlem, mlhou i mlhou pod vodou, bez vlastního shaderu. Tvar se
 * bere z BlockModels, takže pochodeň leží na zemi jako tyčka.
 *
 * ⚠️ Pozice jsou RELATIVNÍ KE KAMEŘE, odečtené na CPU v double. Je to táž
 * úmluva jako uChunkOffset u sekcí (viz Shaders), jen se posun zapeče rovnou
 * do vrcholů: položky se každý frame hýbou a točí, takže se stejně celé
 * přenahrávají. uChunkOffset je pak nula.
 *
 * Staví se znovu každý frame. Kostka je 36 vrcholů, takže i sto položek
 * je necelých 100 KB.
 *
 * ⚠️ BLOKY A PŘEDMĚTY JSOU DVĚ INSTANCE. Předmět se kreslí z atlasu
 * předmětů (jiná textura), takže nemůže být v jednom draw callu s bloky.
 * Instance bez pixelů předmětů staví jen bloky, instance s nimi jen
 * předměty - každá jako 3D model z ItemModel, dvakrát větší než kostka
 * (plochý obrázek by byl v měřítku kostky nečitelný).
 * ---------------------------------------------------------------------------
 *
 * build() nesahá na GL a jde otestovat headless; upload() a draw() ano.
 */
public class DroppedItemMesh {

    /** pozice (3) + uv (2) + sluneční (1) + blokové světlo (1), jako ChunkMesh */
    static final int FLOATS_PER_VERTEX = 7;
    private static final int FLOATS_PER_QUAD = 6 * FLOATS_PER_VERTEX;

    // Stejné odstíny stěn jako ChunkMesh, aby položka seděla s blokem ve světě.
    // Otáčí se s modelem - je to zapečené "světlo", ne skutečný směr slunce.
    private static final float SHADE_TOP    = 1.00f;
    private static final float SHADE_SIDE_Z = 0.80f;
    private static final float SHADE_SIDE_X = 0.60f;
    private static final float SHADE_BOTTOM = 0.50f;

    /** Stejné dva vzorce rohů dlaždice jako v ChunkMesh - viz jeho UV_A a UV_B. */
    private static final float[] UV_A = {0, 0,  0, 1,  1, 1,  1, 0};
    private static final float[] UV_B = {0, 0,  1, 0,  1, 1,  0, 1};

    /**
     * Animace z Minecraftu: otočka jedna radiána za sekundu a houpání
     * 0 až 0,2 bloku nad zemí s periodou ~3 s. Položka se tak odliší od
     * bloku i na první pohled.
     */
    private static final float SPIN = 1f;
    private static final float BOB_BASE = 0.1f;
    private static final float BOB_AMPLITUDE = 0.1f;
    private static final float BOB_SPEED = 2f;

    /** Předmět na zemi je obrázek o straně půl bloku - dvojnásobek kostky. */
    static final float ITEM_SIZE = DroppedItem.SIZE * 2f;

    private float[] data = new float[FLOATS_PER_QUAD * 6 * 16];

    /** null = instance pro bloky; jinak pro předměty (viz třída). */
    private final int[] itemPixels;
    private final float[] itemModel;

    /** Velikost právě stavěné položky - DroppedItem.SIZE, u předmětu ITEM_SIZE. */
    private float size = DroppedItem.SIZE;

    /** Instance pro bloky. */
    public DroppedItemMesh()
    {
        this(null);
    }

    /** Instance pro předměty z atlasu s těmito pixely (sdílené pole, ne kopie). */
    public DroppedItemMesh(int[] itemPixels)
    {
        this.itemPixels = itemPixels;
        this.itemModel = itemPixels == null ? null : new float[ItemModel.MAX_FLOATS];
    }
    private int floats = 0;

    // Stav právě stavěné položky - nastaví se jednou na položku, viz emitItem().
    private float baseX, baseY, baseZ;
    private float cos, sin;
    private float sky, block;

    private int vao = 0;
    private int vbo = 0;
    private FloatBuffer upload;

    // ------------------------------------------------------------------
    // stavba na CPU
    // ------------------------------------------------------------------

    /**
     * Postaví trojúhelníky všech položek blíž než maxDistance od kamery.
     * Vzdálenější se přeskočí - čtvrtinová kostka je tam pixel nebo dva.
     */
    public void build(List<DroppedItem> items, World world,
                      float camX, float camY, float camZ, float maxDistance)
    {
        floats = 0;

        float maxSquared = maxDistance * maxDistance;

        for(DroppedItem item : items)
        {
            // V double: odečítají se dvě velká, skoro stejná čísla.
            float dx = (float) (item.x - (double) camX);
            float dy = (float) (item.y - (double) camY);
            float dz = (float) (item.z - (double) camZ);

            if(dx * dx + dy * dy + dz * dz > maxSquared)
            {
                continue;
            }

            // Každá instance jen svůj druh - bloky, nebo předměty.
            if(Items.isItem(item.stack().id()) != (itemPixels != null))
            {
                continue;
            }

            emitItem(item, world, dx, dy, dz);
        }
    }

    private void emitItem(DroppedItem item, World world, float dx, float dy, float dz)
    {
        float angle = item.age() * SPIN + item.phase();
        cos = (float) Math.cos(angle);
        sin = (float) Math.sin(angle);

        baseX = dx;
        baseY = dy + BOB_BASE + BOB_AMPLITUDE * (float) Math.sin(item.age() * BOB_SPEED + item.phase());
        baseZ = dz;

        // Světlo buňky, ve které položka leží - ploché pro celý model, stejně
        // jako stěna uvnitř bloku v ChunkMesh. Plynulé osvětlení po rozích
        // by u kostky, která se točí, nemělo k čemu se vztáhnout.
        int cell = world.cellAt((int) Math.floor(item.x),
                (int) Math.floor(item.y + DroppedItem.SIZE / 2f),
                (int) Math.floor(item.z));

        sky = World.cellSky(cell) / (float) LightEngine.MAX_LIGHT;
        block = World.cellBlockLight(cell) / (float) LightEngine.MAX_LIGHT;

        if(itemPixels != null)
        {
            emitItemModel(item.stack().id());
            return;
        }

        size = DroppedItem.SIZE;
        byte id = item.stack().block();

        int top = BlockAtlas.tile(id, BlockAtlas.FACE_TOP);
        int bottom = BlockAtlas.tile(id, BlockAtlas.FACE_BOTTOM);
        int side = BlockAtlas.tile(id, BlockAtlas.FACE_SIDE);

        for(BlockModels.BlockBox box : BlockModels.of(id))
        {
            emitBox(box, top, bottom, side);
        }
    }

    /** Předmět jako 3D model z pixelů; neznámý předmět se nekreslí. */
    private void emitItemModel(int id)
    {
        ItemDef def = Items.item(id);

        if(def == null)
        {
            return;
        }

        size = ITEM_SIZE;
        int end = ItemModel.build(ItemTextures.tilePixels(itemPixels, def.tile()), def.tile(), itemModel, 0);

        if(floats + end / ItemModel.FLOATS_PER_VERTEX * FLOATS_PER_VERTEX > data.length)
        {
            data = Arrays.copyOf(data, Math.max(data.length * 2,
                    floats + end / ItemModel.FLOATS_PER_VERTEX * FLOATS_PER_VERTEX));
        }

        for(int i = 0; i < end; i += ItemModel.FLOATS_PER_VERTEX)
        {
            vertex(itemModel[i], itemModel[i + 1], itemModel[i + 2],
                    itemModel[i + 3], itemModel[i + 4], itemModel[i + 5]);
        }
    }

    /**
     * Všech šest stěn kvádru - položka se točí, takže je vidět ze všech stran.
     * Rohy i UV jsou přesně v pořadí ChunkMesh.emitBox(), aby textura ležela
     * stejně jako na bloku ve světě a stěny zůstaly otočené ven (culling).
     */
    private void emitBox(BlockModels.BlockBox box, int top, int bottom, int side)
    {
        float x0 = box.minX(), x1 = box.maxX();
        float y0 = box.minY(), y1 = box.maxY();
        float z0 = box.minZ(), z1 = box.maxZ();

        quad(x0, y1, z0,  x0, y1, z1,  x1, y1, z1,  x1, y1, z0,
                top, UV_A, x0, x1, z0, z1, SHADE_TOP);
        quad(x0, y0, z0,  x1, y0, z0,  x1, y0, z1,  x0, y0, z1,
                bottom, UV_B, x0, x1, z0, z1, SHADE_BOTTOM);
        quad(x1, y0, z0,  x1, y1, z0,  x1, y1, z1,  x1, y0, z1,
                side, UV_A, z0, z1, y0, y1, SHADE_SIDE_X);
        quad(x0, y0, z0,  x0, y0, z1,  x0, y1, z1,  x0, y1, z0,
                side, UV_B, z0, z1, y0, y1, SHADE_SIDE_X);
        quad(x0, y0, z1,  x1, y0, z1,  x1, y1, z1,  x0, y1, z1,
                side, UV_B, x0, x1, y0, y1, SHADE_SIDE_Z);
        quad(x0, y0, z0,  x0, y1, z0,  x1, y1, z0,  x1, y0, z0,
                side, UV_A, x0, x1, y0, y1, SHADE_SIDE_Z);
    }

    /** Čtyřúhelník jako dva trojúhelníky (0,1,2) + (0,2,3), proti směru hodinových ručiček. */
    private void quad(float ax, float ay, float az, float bx, float by, float bz,
                      float cx, float cy, float cz, float dx, float dy, float dz,
                      int tile, float[] pattern, float sMin, float sMax, float tMin, float tMax,
                      float shade)
    {
        if(floats + FLOATS_PER_QUAD > data.length)
        {
            data = Arrays.copyOf(data, data.length * 2);
        }

        float u0 = BlockAtlas.u0(tile), u1 = BlockAtlas.u1(tile);
        float v0 = BlockAtlas.v0(tile), v1 = BlockAtlas.v1(tile);

        // UV z rozsahu kvádru, ne z celé dlaždice - tenká pochodeň si vezme
        // jen svůj pruh textury, stejně jako ve světě.
        float au = lerp(u0, u1, pattern[0] == 0 ? sMin : sMax);
        float av = lerp(v0, v1, pattern[1] == 0 ? tMin : tMax);
        float bu = lerp(u0, u1, pattern[2] == 0 ? sMin : sMax);
        float bv = lerp(v0, v1, pattern[3] == 0 ? tMin : tMax);
        float cu = lerp(u0, u1, pattern[4] == 0 ? sMin : sMax);
        float cv = lerp(v0, v1, pattern[5] == 0 ? tMin : tMax);
        float du = lerp(u0, u1, pattern[6] == 0 ? sMin : sMax);
        float dv = lerp(v0, v1, pattern[7] == 0 ? tMin : tMax);

        vertex(ax, ay, az, au, av, shade);
        vertex(bx, by, bz, bu, bv, shade);
        vertex(cx, cy, cz, cu, cv, shade);

        vertex(ax, ay, az, au, av, shade);
        vertex(cx, cy, cz, cu, cv, shade);
        vertex(dx, dy, dz, du, dv, shade);
    }

    /**
     * Roh modelu (0-1 v bloku) na místo ve světě: střed kostky do počátku,
     * zmenšit na velikost položky, otočit kolem svislé osy a posunout na
     * místo položky vůči kameře.
     *
     * Otočení je obyčejná rotace v rovině x-z (determinant +1), takže
     * nepřevrací pořadí vrcholů a backface culling dál zahazuje správné stěny.
     */
    private void vertex(float mx, float my, float mz, float u, float v, float shade)
    {
        float lx = (mx - 0.5f) * size;
        float lz = (mz - 0.5f) * size;

        data[floats++] = baseX + lx * cos - lz * sin;
        data[floats++] = baseY + my * size;
        data[floats++] = baseZ + lx * sin + lz * cos;
        data[floats++] = u;
        data[floats++] = v;
        data[floats++] = sky * shade;
        data[floats++] = block * shade;
    }

    private static float lerp(float a, float b, float t)
    {
        return a + (b - a) * t;
    }

    public int vertexCount()
    {
        return floats / FLOATS_PER_VERTEX;
    }

    public boolean isEmpty()
    {
        return floats == 0;
    }

    /** Surová data vrcholů pro testy; platných je vertexCount() * FLOATS_PER_VERTEX. */
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

        // Buffer na grafice roste spolu s polem na CPU; zmenšovat ho nemá smysl.
        // Mezi tím se jen přepisuje, takže se každý frame nic nealokuje.
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

    public void draw()
    {
        if(floats == 0)
        {
            return;
        }

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount());
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
    }
}
