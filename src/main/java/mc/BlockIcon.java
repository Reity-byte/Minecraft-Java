package mc;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.Arrays;

import static org.lwjgl.opengl.GL33.*;

/**
 * Izometrická ikona bloku - do hotbaru i do slotů inventáře. Předmět
 * (Items.isItem) je plochý obrázek z atlasu předmětů, jako v Minecraftu.
 *
 * ---------------------------------------------------------------------------
 * Kreslí se podle MODELU bloku, ne jako pevná krychle. Pochodeň je v ikoně
 * tenká tyčka a plot sloupek, stejně jako ve světě; dokud se kreslila krychle,
 * vypadal plot v hotbaru jako kostka prken a nešel od nich rozeznat.
 *
 * Textura se bere z blokového atlasu a UV z rozsahu kvádru - přesně jako
 * v ChunkMesh, takže ikona a blok ve světě vypadají stejně.
 *
 * Vlastní shader a VAO: vertex je pozice(2) + uv(2) + odstín(1) + "alfa
 * platí"(1) + zdroj(1), což se do formátu Renderer2D (pozice + barva) nevejde.
 * Zdroj 0 = atlas bloků (jednotka 0), 1 = atlas předmětů (jednotka 1) -
 * bloky i předměty tak zůstávají v JEDNÉ dávce a kurzor nakreslený
 * naposled je pořád nahoře.
 *
 * ⚠️ PRŮHLEDNÝ PIXEL JE V IKONĚ TÍMŽ, ČÍM VE SVĚTĚ. Svět kreslí bloky
 * neprůhledným průchodem, takže pixel s alfou 0 má svou barvu (guma =
 * černá) - jen voda jde průhledným. Ikona dřív alfu míchala všem, takže
 * blok z labu měl v inventáři díru a ve světě černou skvrnu. Teď alfu
 * bere jen blok, který je průhledný i ve světě (World.isTranslucent).
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
    static final float SHADE_TOP   = 1.00f;
    static final float SHADE_LEFT  = 0.80f;   // stěna +Z
    static final float SHADE_RIGHT = 0.60f;   // stěna +X

    static final int FLOATS_PER_VERTEX = 7;
    static final int VERTICES_PER_QUAD = 6;

    /** Tři viditelné stěny na kvádr. */
    static final int FLOATS_PER_BOX = 3 * VERTICES_PER_QUAD * FLOATS_PER_VERTEX;

    /** Počáteční místo: zhruba plný creative inventář krychlí. Roste podle potřeby. */
    private static final int INITIAL_FLOATS = 96 * FLOATS_PER_BOX;

    private final ShaderProgram shader =
            new ShaderProgram(Shaders.UI_BLOCK_VERTEX, Shaders.UI_BLOCK_FRAGMENT);

    private final Texture atlas;

    /** Atlas předmětů, nebo null (lab bez předmětů) - pak se předmět ukáže jako neznámý. */
    private final Texture items;

    private final int vao;
    private final int vbo;

    private float[] scratch = new float[INITIAL_FLOATS];
    private FloatBuffer upload = BufferUtils.createFloatBuffer(INITIAL_FLOATS);

    private int floats = 0;

    public BlockIcon(Texture atlas)
    {
        this(atlas, null);
    }

    public BlockIcon(Texture atlas, Texture items)
    {
        this.atlas = atlas;
        this.items = items;

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
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 5L * Float.BYTES);
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(4, 1, GL_FLOAT, false, stride, 6L * Float.BYTES);
        glEnableVertexAttribArray(4);

        glBindVertexArray(0);
    }

    public void begin(int screenWidth, int screenHeight)
    {
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.bind();
        shader.setVector2("uScreenSize", screenWidth, screenHeight);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, items != null ? items.id() : 0);
        shader.setInt("uItems", 1);

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
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    /**
     * Nakreslí věc do čtverce o straně size: blok jako izometrický tvar,
     * předmět jako plochý obrázek. Jen složí vrcholy do dávky; na grafiku
     * jdou až v end().
     */
    public void draw(float x, float y, float size, int id)
    {
        int needed = floatsFor(id);

        if(floats + needed > scratch.length)
        {
            scratch = Arrays.copyOf(scratch, Math.max(scratch.length * 2, floats + needed));
        }

        floats = build(id, x, y, size, items != null, scratch, floats);
    }

    // ------------------------------------------------------------------
    // geometrie - čistá funkce, bez GL (testuje BlockIconTest)
    // ------------------------------------------------------------------
    //
    // ⚠️ PROČ STATICKY. GL je už v konstruktoru (shader, VAO), takže dokud
    // stavba vrcholů byla metoda instance, nešlo pořadí rohů, UV ani odstíny
    // ověřit bez okna. U ruky, postavy i položek na zemi je stavba čistá
    // funkce a testovaná - a chyba "culling potichu schová celou oblohu"
    // už jednou nastala (kvůli ní je SkyTest).

    /**
     * Plochý obrázek dlaždice atlasu předmětů - náhled v labu, kde dlaždice
     * ještě žádnému předmětu nepatří. Bez atlasu předmětů nic.
     */
    public void drawItemTile(float x, float y, float size, int tile)
    {
        if(items == null)
        {
            return;
        }

        int needed = VERTICES_PER_QUAD * FLOATS_PER_VERTEX;

        if(floats + needed > scratch.length)
        {
            scratch = Arrays.copyOf(scratch, Math.max(scratch.length * 2, floats + needed));
        }

        floats = flat(scratch, floats, x, y, size, tile, 1f);
    }

    /** Kolik floatů zabere ikona tohohle bloku. */
    static int floatsFor(byte block)
    {
        return BlockModels.of(block).length * FLOATS_PER_BOX;
    }

    /** Kolik floatů zabere ikona věci - předmět je jeden čtverec. */
    static int floatsFor(int id)
    {
        return Items.isItem(id) ? VERTICES_PER_QUAD * FLOATS_PER_VERTEX : floatsFor((byte) id);
    }

    /**
     * Vrcholy ikony věci: předmět jako plochý obrázek, blok izometricky.
     * hasItemAtlas = false (lab bez atlasu předmětů) i neznámý předmět
     * nakreslí šachovnici "neznámý blok" z atlasu bloků.
     */
    static int build(int id, float x, float y, float size, boolean hasItemAtlas, float[] out, int offset)
    {
        if(!Items.isItem(id))
        {
            return build((byte) id, x, y, size, out, offset);
        }

        ItemDef def = Items.item(id);

        if(def == null || !hasItemAtlas)
        {
            return flat(out, offset, x, y, size, BlockAtlas.TILE_UNKNOWN, 0f);
        }

        return flat(out, offset, x, y, size, def.tile(), 1f);
    }

    /**
     * Plochý obrázek dlaždice přes celý čtverec. Alfa platí vždycky -
     * předmět je obrys na průhledném pozadí. Obrazovka má (0,0) vlevo dole
     * a atlas řádek 0 dole, takže spodní hrana = v0.
     */
    private static int flat(float[] out, int at, float x, float y, float size, int tile, float source)
    {
        float u0 = BlockAtlas.u0(tile), u1 = BlockAtlas.u1(tile);
        float v0 = BlockAtlas.v0(tile), v1 = BlockAtlas.v1(tile);
        float x1 = x + size, y1 = y + size;

        at = vertex(out, at, x, y, u0, v0, 1f, 1f, source);
        at = vertex(out, at, x1, y, u1, v0, 1f, 1f, source);
        at = vertex(out, at, x1, y1, u1, v1, 1f, 1f, source);

        at = vertex(out, at, x, y, u0, v0, 1f, 1f, source);
        at = vertex(out, at, x1, y1, u1, v1, 1f, 1f, source);
        at = vertex(out, at, x, y1, u0, v1, 1f, 1f, source);
        return at;
    }

    /**
     * Vrcholy ikony do out od offset; vrací offset za posledním zapsaným.
     *
     * Vidět jsou vždycky jen tři stěny každého kvádru: horní (+Y), levá (+Z)
     * a pravá (+X). Zbylé tři jsou odvrácené, takže se nekreslí vůbec - žádný
     * depth test tu není a kreslit je by znamenalo, že by mohly přebít ty přední.
     *
     * Vrchol je pozice(2) + uv(2) + odstín(1) + "alfa platí"(1), viz
     * `World.isTranslucent()`.
     */
    static int build(byte block, float x, float y, float size, float[] out, int offset)
    {
        Iso iso = new Iso(x + size / 2f, y + size / 2f, size / 2f, size / 4f);
        float alpha = World.isTranslucent(block) ? 1f : 0f;

        int top = BlockAtlas.tile(block, BlockAtlas.FACE_TOP);
        // Levá stěna ikony je +Z (jih), pravá +X (východ) - pec (FURNACE) tak
        // ukáže čelo vlevo, jako v Minecraftu.
        int south = BlockAtlas.tile(block, BlockAtlas.FACE_SOUTH);
        int east = BlockAtlas.tile(block, BlockAtlas.FACE_EAST);
        int at = offset;

        for(BlockModels.BlockBox box : BlockModels.of(block))
        {
            // Horní stěna (+Y): u podle x, v podle z.
            at = face(out, at, iso, top, SHADE_TOP, alpha,
                    box.minX(), box.maxY(), box.minZ(),  box.minX(), box.minZ(),
                    box.minX(), box.maxY(), box.maxZ(),  box.minX(), box.maxZ(),
                    box.maxX(), box.maxY(), box.maxZ(),  box.maxX(), box.maxZ(),
                    box.maxX(), box.maxY(), box.minZ(),  box.maxX(), box.minZ());

            // Levá stěna (+Z): u podle x, v podle y.
            at = face(out, at, iso, south, SHADE_LEFT, alpha,
                    box.maxX(), box.maxY(), box.maxZ(),  box.maxX(), box.maxY(),
                    box.minX(), box.maxY(), box.maxZ(),  box.minX(), box.maxY(),
                    box.minX(), box.minY(), box.maxZ(),  box.minX(), box.minY(),
                    box.maxX(), box.minY(), box.maxZ(),  box.maxX(), box.minY());

            // Pravá stěna (+X): u podle z, v podle y.
            at = face(out, at, iso, east, SHADE_RIGHT, alpha,
                    box.maxX(), box.maxY(), box.maxZ(),  box.maxZ(), box.maxY(),
                    box.maxX(), box.minY(), box.maxZ(),  box.maxZ(), box.minY(),
                    box.maxX(), box.minY(), box.minZ(),  box.minZ(), box.minY(),
                    box.maxX(), box.maxY(), box.minZ(),  box.minZ(), box.maxY());
        }

        return at;
    }

    /**
     * Izometrická projekce bodu z bloku (0-1) do pixelů obrazovky.
     *
     * Poměr 2:1 - posun o blok do strany je půl šířky ikony vodorovně
     * a čtvrtina svisle, posun nahoru je polovina výšky.
     */
    private record Iso(float centerX, float centerY, float halfWidth, float quarter) {

        float x(float bx, float bz)
        {
            return centerX + (bx - bz) * halfWidth;
        }

        float y(float bx, float by, float bz)
        {
            return centerY + (2f - bx - bz) * quarter + (by - 1f) * 2f * quarter;
        }
    }

    /** Jedna stěna: čtyři rohy, každý se svou pozicí v bloku a svým (u, v) v dlaždici. */
    private static int face(float[] out, int at, Iso iso, int tile, float shade, float alpha,
                            float ax, float ay, float az, float au, float av,
                            float bx, float by, float bz, float bu, float bv,
                            float cx, float cy, float cz, float cu, float cv,
                            float dx, float dy, float dz, float du, float dv)
    {
        float u0 = BlockAtlas.u0(tile), u1 = BlockAtlas.u1(tile);
        float v0 = BlockAtlas.v0(tile), v1 = BlockAtlas.v1(tile);

        at = vertex(out, at, iso.x(ax, az), iso.y(ax, ay, az), lerp(u0, u1, au), lerp(v0, v1, av), shade, alpha);
        at = vertex(out, at, iso.x(bx, bz), iso.y(bx, by, bz), lerp(u0, u1, bu), lerp(v0, v1, bv), shade, alpha);
        at = vertex(out, at, iso.x(cx, cz), iso.y(cx, cy, cz), lerp(u0, u1, cu), lerp(v0, v1, cv), shade, alpha);

        at = vertex(out, at, iso.x(ax, az), iso.y(ax, ay, az), lerp(u0, u1, au), lerp(v0, v1, av), shade, alpha);
        at = vertex(out, at, iso.x(cx, cz), iso.y(cx, cy, cz), lerp(u0, u1, cu), lerp(v0, v1, cv), shade, alpha);
        at = vertex(out, at, iso.x(dx, dz), iso.y(dx, dy, dz), lerp(u0, u1, du), lerp(v0, v1, dv), shade, alpha);
        return at;
    }

    private static float lerp(float a, float b, float t)
    {
        return a + (b - a) * t;
    }

    /** Vrchol z atlasu bloků (zdroj 0). */
    private static int vertex(float[] out, int at, float x, float y, float u, float v,
                              float shade, float alpha)
    {
        return vertex(out, at, x, y, u, v, shade, alpha, 0f);
    }

    private static int vertex(float[] out, int at, float x, float y, float u, float v,
                              float shade, float alpha, float source)
    {
        out[at++] = x;
        out[at++] = y;
        out[at++] = u;
        out[at++] = v;
        out[at++] = shade;
        out[at++] = alpha;
        out[at++] = source;
        return at;
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
