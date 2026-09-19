package mc;

import java.util.Arrays;

import static org.lwjgl.opengl.GL33.*;

/**
 * Mesh jedné sekce 16x16x16: převede bloky na seznam trojúhelníků a nahraje ho
 * do VBO na grafice.
 *
 * Tohle je jádro celého kroku A3. Dřív se face culling počítal a stěny posílaly
 * do GL znovu KAŽDÝ FRAME. Teď se spočítá jednou, výsledek zůstane ležet
 * v paměti grafiky a každý frame se pošle jediný draw call.
 *
 * Souřadnice vrcholů jsou lokální (0-16), ne světové - důvod viz komentář
 * v Shaders (přesnost floatu daleko od počátku).
 */
public class ChunkMesh {

    /**
     * pozice (3) + uv (2) + sluneční světlo (1) + blokové světlo (1)
     *
     * ⚠️ Ztmavení stěny (SHADE_*) se do vrcholu neposílá zvlášť - je už
     * ZAPEČENÉ v obou světlech. Ušetří to jeden float na vrchol a vyjde to
     * nastejno, protože se ztmavení uplatní na obě složky stejně.
     *
     * Kanály musí zůstat oddělené: sluneční se v noci ztlumí násobičem
     * v shaderu, blokové svítí dál. Sečíst je na CPU by znamenalo přepočítat
     * celý svět při každém západu slunce.
     */
    private static final int FLOATS_PER_VERTEX = 7;
    /** 2 trojúhelníky po 3 vrcholech */
    private static final int VERTICES_PER_FACE = 6;
    private static final int FLOATS_PER_FACE = VERTICES_PER_FACE * FLOATS_PER_VERTEX;

    // Fake směrové osvětlení. Jde do vrcholu jako samostatný násobič a fragment
    // shader jím vynásobí barvu z textury. Stejné hodnoty používá Hud na
    // izometrické kostky v hotbaru, aby ikona seděla s tím, jak blok vypadá.
    private static final float SHADE_TOP    = 1.00f;
    private static final float SHADE_SIDE_Z = 0.80f;
    private static final float SHADE_SIDE_X = 0.60f;
    private static final float SHADE_BOTTOM = 0.50f;

    /**
     * Přiřazení rohů stěny k rohům dlaždice, v jednotkovém čtverci (s, t).
     *
     * Šest stěn se dělí jen o DVA vzorce, protože se liší jen tím, kterým
     * směrem obcházejí obvod - a to je dané pořadím vrcholů zvoleným kvůli
     * backface cullingu. U bočních stěn platí t = výška, takže textura stojí
     * správně; u vodorovných je t světová osa Z, resp. X.
     */
    private static final float[] UV_A = {0, 0,  0, 1,  1, 1,  1, 0};   // top, +X, -Z
    private static final float[] UV_B = {0, 0,  1, 0,  1, 1,  0, 1};   // bottom, -X, +Z

    // ------------------------------------------------------------------
    // Stěny se sbírají do DVOU polí: neprůhledné a průhledné (voda).
    //
    // Do jednoho VBO se pak nahrají za sebou, neprůhledné první, takže se
    // kreslí dvěma glDrawArrays se stejným VAO - jen s jiným offsetem.
    // Oddělené být musí: průhledné stěny se kreslí až po VŠECH neprůhledných
    // ze všech sekcí a odzadu dopředu, jinak by voda přebila to, co je za ní.
    // ------------------------------------------------------------------

    private float[] opaque = new float[FLOATS_PER_FACE * 128];
    private int opaqueFloats = 0;

    private float[] transparent = new float[FLOATS_PER_FACE * 16];
    private int transparentFloats = 0;

    private int opaqueVertices = 0;
    private int transparentVertices = 0;

    /** Kam právě tečou stěny. Nastavuje se jednou na blok, viz build(). */
    private boolean emitTransparent = false;

    private int vao = 0;
    private int vbo = 0;

    // ------------------------------------------------------------------
    // stavba na CPU
    // ------------------------------------------------------------------

    /**
     * Projde sekci a vygeneruje stěny, jejichž soused je vzduch.
     * Sousedi se čtou ze světa ve světových souřadnicích, takže to funguje
     * i přes hranici chunku - blok na lx=15 se korektně podívá do vedlejšího.
     */
    public void build(World world, Chunk chunk, int baseX, int baseY, int baseZ)
    {
        opaqueFloats = 0;
        transparentFloats = 0;

        // Pořadí y-z-x odpovídá rozložení Chunk.index(), takže se pole čte sekvenčně.
        for(int ly = 0; ly < Chunk.SIZE; ly++)
        {
            // Jestli je blok "vnitřní", nezávisí na všech třech osách stejně -
            // části podmínky se dají vytáhnout z vnitřních smyček ven.
            boolean interiorY = ly > 0 && ly < Chunk.MASK;

            for(int lz = 0; lz < Chunk.SIZE; lz++)
            {
                boolean interiorYZ = interiorY && lz > 0 && lz < Chunk.MASK;

                for(int lx = 0; lx < Chunk.SIZE; lx++)
                {
                    byte id = chunk.get(lx, ly, lz);
                    if(id == World.AIR)
                    {
                        continue;
                    }

                    // Voda jde do průhledného bufferu a řídí se jiným pravidlem
                    // viditelnosti - viz visible().
                    emitTransparent = id == World.WATER;

                    int wx = baseX + lx;
                    int wy = baseY + ly;
                    int wz = baseZ + lz;

                    float x = lx, y = ly, z = lz;

                    // Blok uvnitř sekce (ne na jejím povrchu) má všech 6 sousedů
                    // ve stejném poli - čtou se přímo, bez cesty přes World,
                    // tedy bez vyhledání sloupce v HashMap. Vnitřek je 14^3 ze
                    // 16^3, tedy 67 % bloků, takže tímhle odpadne většina lookupů.
                    // Bloky na povrchu sekce musí dál přes World, protože jejich
                    // sousedi leží v jiném chunku.
                    byte up, down, right, left, front, back;

                    if(interiorYZ && lx > 0 && lx < Chunk.MASK)
                    {
                        up    = chunk.get(lx, ly + 1, lz);
                        down  = chunk.get(lx, ly - 1, lz);
                        right = chunk.get(lx + 1, ly, lz);
                        left  = chunk.get(lx - 1, ly, lz);
                        front = chunk.get(lx, ly, lz + 1);
                        back  = chunk.get(lx, ly, lz - 1);
                    }
                    else
                    {
                        up    = world.getBlock(wx, wy + 1, wz);
                        down  = world.getBlock(wx, wy - 1, wz);
                        right = world.getBlock(wx + 1, wy, wz);
                        left  = world.getBlock(wx - 1, wy, wz);
                        front = world.getBlock(wx, wy, wz + 1);
                        back  = world.getBlock(wx, wy, wz - 1);
                    }

                    for(BlockModels.BlockBox box : BlockModels.of(id))
                    {
                        emitBox(world, id, box, x, y, z, wx, wy, wz,
                                up, down, right, left, front, back);
                    }
                }
            }
        }

        opaqueVertices = opaqueFloats / FLOATS_PER_VERTEX;
        transparentVertices = transparentFloats / FLOATS_PER_VERTEX;
    }

    /**
     * Vygeneruje stěny jednoho kvádru modelu.
     *
     * ⚠️ Stěna se zahazuje JEN když leží přesně na hranici bloku. Stěna uvnitř
     * bloku - třeba bok tenké pochodně - musí být vidět vždycky, i když je vedle
     * plný kámen; jinak by pochodeň u zdi zmizela.
     *
     * UV se berou z rozsahu kvádru, ne z celé dlaždice: tenká pochodeň si
     * vezme jen ten pruh textury, který jí odpovídá. U plné krychle vyjde
     * 0 až 1, tedy přesně to co dřív.
     */
    private void emitBox(World world, byte id, BlockModels.BlockBox box,
                         float x, float y, float z, int wx, int wy, int wz,
                         byte up, byte down, byte right, byte left, byte front, byte back)
    {
        float x0 = x + box.minX(), x1 = x + box.maxX();
        float y0 = y + box.minY(), y1 = y + box.maxY();
        float z0 = z + box.minZ(), z1 = z + box.maxZ();

        int top = BlockAtlas.tile(id, BlockAtlas.FACE_TOP);
        int bottom = BlockAtlas.tile(id, BlockAtlas.FACE_BOTTOM);
        int side = BlockAtlas.tile(id, BlockAtlas.FACE_SIDE);

        if(box.maxY() < 1f || visible(up))
        {
            faceLight(world, wx, wy, wz, box.maxY() >= 1f, 0, 1, 0, SHADE_TOP,
                    -1, 0, 0,  0, 0, -1);
            addQuad(x0, y1, z0,  x0, y1, z1,  x1, y1, z1,  x1, y1, z0,
                    top, UV_A, box.minX(), box.maxX(), box.minZ(), box.maxZ());
        }
        if(box.minY() > 0f || visible(down))
        {
            faceLight(world, wx, wy, wz, box.minY() <= 0f, 0, -1, 0, SHADE_BOTTOM,
                    -1, 0, 0,  0, 0, -1);
            addQuad(x0, y0, z0,  x1, y0, z0,  x1, y0, z1,  x0, y0, z1,
                    bottom, UV_B, box.minX(), box.maxX(), box.minZ(), box.maxZ());
        }
        if(box.maxX() < 1f || visible(right))
        {
            faceLight(world, wx, wy, wz, box.maxX() >= 1f, 1, 0, 0, SHADE_SIDE_X,
                    0, -1, 0,  0, 0, -1);
            addQuad(x1, y0, z0,  x1, y1, z0,  x1, y1, z1,  x1, y0, z1,
                    side, UV_A, box.minZ(), box.maxZ(), box.minY(), box.maxY());
        }
        if(box.minX() > 0f || visible(left))
        {
            faceLight(world, wx, wy, wz, box.minX() <= 0f, -1, 0, 0, SHADE_SIDE_X,
                    0, -1, 0,  0, 0, -1);
            addQuad(x0, y0, z0,  x0, y0, z1,  x0, y1, z1,  x0, y1, z0,
                    side, UV_B, box.minZ(), box.maxZ(), box.minY(), box.maxY());
        }
        if(box.maxZ() < 1f || visible(front))
        {
            faceLight(world, wx, wy, wz, box.maxZ() >= 1f, 0, 0, 1, SHADE_SIDE_Z,
                    -1, 0, 0,  0, -1, 0);
            addQuad(x0, y0, z1,  x1, y0, z1,  x1, y1, z1,  x0, y1, z1,
                    side, UV_B, box.minX(), box.maxX(), box.minY(), box.maxY());
        }
        if(box.minZ() > 0f || visible(back))
        {
            faceLight(world, wx, wy, wz, box.minZ() <= 0f, 0, 0, -1, SHADE_SIDE_Z,
                    -1, 0, 0,  0, -1, 0);
            addQuad(x0, y0, z0,  x0, y1, z0,  x1, y1, z0,  x1, y0, z0,
                    side, UV_A, box.minX(), box.maxX(), box.minY(), box.maxY());
        }
    }

    // ------------------------------------------------------------------
    // plynulé osvětlení (smooth lighting)
    //
    // ⚠️ Světlo se počítá DO KAŽDÉHO ROHU stěny zvlášť, ze čtyř buněk, které
    // se toho rohu dotýkají: buňka před stěnou, dvě po stranách a jedna
    // do rohu. Grafická karta pak mezi rohy interpoluje, takže přechod světla
    // přes stěnu je plynulý místo skoku na hranici bloku.
    //
    // Ze stejných čtyř buněk vypadne i ambient occlusion: čím víc jich je
    // zazděných, tím tmavší roh. Odtud stíny v koutech a pod převisy - bez
    // nich vypadá i plynule osvětlená scéna ploše.
    // ------------------------------------------------------------------

    /** Ztmavení rohu podle počtu zazděných sousedů: 0 = kout, 3 = volno. */
    private static final float[] AMBIENT_OCCLUSION = {0.52f, 0.70f, 0.86f, 1.00f};

    private final float[] cornerSky = new float[4];
    private final float[] cornerBlock = new float[4];

    /**
     * Spočítá světlo do čtyř rohů stěny.
     *
     * flush říká, jestli stěna leží na hranici bloku. Když neleží (bok tenké
     * pochodně), soused neexistuje a celá stěna dostane ploché světlo vlastní
     * buňky - jinak by se počítalo se zazděným blokem a pochodeň by zčernala.
     *
     * a a b jsou dvě osy v rovině stěny; rohy se berou v pořadí
     * (-a,-b), (-a,+b), (+a,+b), (+a,-b) nebo jeho variantě - musí sedět
     * s pořadím vrcholů, které se pak pošle do addQuad.
     */
    private void faceLight(World world, int wx, int wy, int wz, boolean flush,
                           int nx, int ny, int nz, float shade,
                           int ax, int ay, int az, int bx, int by, int bz)
    {
        if(!flush)
        {
            float sky = shade * world.skyLightAt(wx, wy, wz) / (float) LightEngine.MAX_LIGHT;
            float block = shade * world.blockLightAt(wx, wy, wz) / (float) LightEngine.MAX_LIGHT;

            for(int i = 0; i < 4; i++)
            {
                cornerSky[i] = sky;
                cornerBlock[i] = block;
            }

            return;
        }

        int fx = wx + nx, fy = wy + ny, fz = wz + nz;

        // Pořadí rohů odpovídá pořadí vrcholů v addQuad.
        corner(world, fx, fy, fz, -ax, -ay, -az, -bx, -by, -bz, shade, 0);
        corner(world, fx, fy, fz, -ax, -ay, -az,  bx,  by,  bz, shade, 1);
        corner(world, fx, fy, fz,  ax,  ay,  az,  bx,  by,  bz, shade, 2);
        corner(world, fx, fy, fz,  ax,  ay,  az, -bx, -by, -bz, shade, 3);
    }

    /**
     * Jeden roh: průměr světla ze čtyř buněk kolem něj a ztmavení podle toho,
     * kolik z nich je zazděných.
     *
     * Zazděné buňky se do průměru nepočítají - měly by nulu a roh u zdi by
     * kvůli nim byl tmavší, než jaké světlo tam ve skutečnosti je. Na to,
     * že je roh u zdi, stačí ambient occlusion.
     */
    private void corner(World world, int fx, int fy, int fz,
                        int ax, int ay, int az, int bx, int by, int bz,
                        float shade, int index)
    {
        // Jeden dotaz na buňku místo tří - viz World.cellAt().
        int front = world.cellAt(fx, fy, fz);
        int cellA = world.cellAt(fx + ax, fy + ay, fz + az);
        int cellB = world.cellAt(fx + bx, fy + by, fz + bz);
        int cellD = world.cellAt(fx + ax + bx, fy + ay + by, fz + az + bz);

        boolean sideA = World.isOpaque(World.cellBlock(cellA));
        boolean sideB = World.isOpaque(World.cellBlock(cellB));
        boolean diagonal = World.isOpaque(World.cellBlock(cellD));

        // Dvě zazděné strany zakryjí roh úplně - co je za nimi, už nehraje roli.
        int open = sideA && sideB ? 0
                : 3 - (sideA ? 1 : 0) - (sideB ? 1 : 0) - (diagonal ? 1 : 0);

        int skySum = World.cellSky(front);
        int blockSum = World.cellBlockLight(front);
        int count = 1;

        if(!sideA)
        {
            skySum += World.cellSky(cellA);
            blockSum += World.cellBlockLight(cellA);
            count++;
        }

        if(!sideB)
        {
            skySum += World.cellSky(cellB);
            blockSum += World.cellBlockLight(cellB);
            count++;
        }

        if(!diagonal && !(sideA && sideB))
        {
            skySum += World.cellSky(cellD);
            blockSum += World.cellBlockLight(cellD);
            count++;
        }

        float factor = shade * AMBIENT_OCCLUSION[open] / (count * (float) LightEngine.MAX_LIGHT);

        cornerSky[index] = skySum * factor;
        cornerBlock[index] = blockSum * factor;
    }

    /**
     * Má se stěna k tomuhle sousedovi vůbec kreslit?
     *
     * Neprůhledný blok: kreslí se ke všemu, co nezakrývá - tedy k vzduchu
     * i k VODĚ. Bez toho by dno jezera zmizelo, protože voda by se počítala
     * jako plný soused.
     *
     * Voda: kreslí se jen ke vzduchu. Ne k sousední vodě (jinak by uvnitř
     * jezera byla mřížka stěn) a ne k pevnému bloku, kde by ležela přesně
     * na jeho stěně a blikala by s ní (z-fighting).
     */
    private boolean visible(byte neighbour)
    {
        return emitTransparent
                ? neighbour == World.AIR
                : !World.isOpaque(neighbour);
    }

    /**
     * Čtyřúhelník se rozpadne na dva trojúhelníky - core profil GL_QUADS neumí.
     * Pořadí (0,1,2) + (0,2,3) zachovává orientaci proti směru hodinových
     * ručiček, na které stojí backface culling.
     */
    private void addQuad(float x0, float y0, float z0,
                         float x1, float y1, float z1,
                         float x2, float y2, float z2,
                         float x3, float y3, float z3,
                         int tile, float[] uvPattern,
                         float sMin, float sMax, float tMin, float tMax)
    {
        ensureCapacity();

        float u0 = BlockAtlas.u0(tile), u1 = BlockAtlas.u1(tile);
        float v0 = BlockAtlas.v0(tile), v1 = BlockAtlas.v1(tile);

        // Vzorec drží jen 0 nebo 1 - tedy "začátek" nebo "konec" kvádru na dané
        // ose. Skutečné UV se pak roztáhne na rozsah dlaždice.
        float au = u(u0, u1, sMin, sMax, uvPattern[0]), av = u(v0, v1, tMin, tMax, uvPattern[1]);
        float bu = u(u0, u1, sMin, sMax, uvPattern[2]), bv = u(v0, v1, tMin, tMax, uvPattern[3]);
        float cu = u(u0, u1, sMin, sMax, uvPattern[4]), cv = u(v0, v1, tMin, tMax, uvPattern[5]);
        float du = u(u0, u1, sMin, sMax, uvPattern[6]), dv = u(v0, v1, tMin, tMax, uvPattern[7]);

        // ⚠️ ÚHLOPŘÍČKA SE PODLE POTŘEBY PŘETOČÍ.
        //
        // Čtyřúhelník se kreslí jako dva trojúhelníky a světlo se interpoluje
        // uvnitř každého zvlášť. Když jsou protilehlé rohy různě tmavé, je přes
        // špatně vedenou úhlopříčku vidět ostrý šev. Rozdělení se proto volí
        // tak, aby úhlopříčka spojovala PODOBNĚ osvětlené rohy.
        if(cornerSky[0] + cornerSky[2] + cornerBlock[0] + cornerBlock[2]
                > cornerSky[1] + cornerSky[3] + cornerBlock[1] + cornerBlock[3])
        {
            vertex(x0, y0, z0, au, av, 0);
            vertex(x1, y1, z1, bu, bv, 1);
            vertex(x2, y2, z2, cu, cv, 2);

            vertex(x0, y0, z0, au, av, 0);
            vertex(x2, y2, z2, cu, cv, 2);
            vertex(x3, y3, z3, du, dv, 3);
        }
        else
        {
            vertex(x1, y1, z1, bu, bv, 1);
            vertex(x2, y2, z2, cu, cv, 2);
            vertex(x3, y3, z3, du, dv, 3);

            vertex(x1, y1, z1, bu, bv, 1);
            vertex(x3, y3, z3, du, dv, 3);
            vertex(x0, y0, z0, au, av, 0);
        }
    }

    /** Z rohu vzorce (0 nebo 1) udělá UV uvnitř dlaždice podle rozsahu kvádru. */
    private static float u(float tileLow, float tileHigh, float boxMin, float boxMax, float corner)
    {
        float t = corner == 0f ? boxMin : boxMax;
        return tileLow + t * (tileHigh - tileLow);
    }

    private void vertex(float x, float y, float z, float u, float v, int corner)
    {
        if(emitTransparent)
        {
            transparent[transparentFloats++] = x;
            transparent[transparentFloats++] = y;
            transparent[transparentFloats++] = z;
            transparent[transparentFloats++] = u;
            transparent[transparentFloats++] = v;
            transparent[transparentFloats++] = cornerSky[corner];
            transparent[transparentFloats++] = cornerBlock[corner];
        }
        else
        {
            opaque[opaqueFloats++] = x;
            opaque[opaqueFloats++] = y;
            opaque[opaqueFloats++] = z;
            opaque[opaqueFloats++] = u;
            opaque[opaqueFloats++] = v;
            opaque[opaqueFloats++] = cornerSky[corner];
            opaque[opaqueFloats++] = cornerBlock[corner];
        }
    }

    private void ensureCapacity()
    {
        if(emitTransparent)
        {
            if(transparentFloats + FLOATS_PER_FACE > transparent.length)
            {
                transparent = Arrays.copyOf(transparent,
                        Math.max(transparent.length * 2, transparentFloats + FLOATS_PER_FACE));
            }
        }
        else if(opaqueFloats + FLOATS_PER_FACE > opaque.length)
        {
            opaque = Arrays.copyOf(opaque,
                    Math.max(opaque.length * 2, opaqueFloats + FLOATS_PER_FACE));
        }
    }

    // ------------------------------------------------------------------
    // nahrání na grafiku
    // ------------------------------------------------------------------

    /** Musí se volat na vlákně s aktivním GL kontextem. */
    public void upload()
    {
        if(opaqueVertices == 0 && transparentVertices == 0)
        {
            return;
        }

        if(vao == 0)
        {
            vao = glGenVertexArrays();
            vbo = glGenBuffers();
        }

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        // Obě sady do jednoho bufferu, neprůhledné první. Kreslení pak jen
        // posune offset, takže dva průchody nestojí druhý VAO ani druhý VBO.
        float[] combined = new float[opaqueFloats + transparentFloats];
        System.arraycopy(opaque, 0, combined, 0, opaqueFloats);
        System.arraycopy(transparent, 0, combined, opaqueFloats, transparentFloats);

        glBufferData(GL_ARRAY_BUFFER, combined, GL_STATIC_DRAW);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;

        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);                 // pozice
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3L * Float.BYTES);   // uv
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 5L * Float.BYTES);   // sluneční
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 6L * Float.BYTES);   // blokové
        glEnableVertexAttribArray(3);

        glBindVertexArray(0);
    }

    public void drawOpaque()
    {
        if(opaqueVertices == 0)
        {
            return;
        }

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, opaqueVertices);
    }

    public void drawTransparent()
    {
        if(transparentVertices == 0)
        {
            return;
        }

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, opaqueVertices, transparentVertices);
    }

    public boolean hasTransparent()
    {
        return transparentVertices > 0;
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

        opaqueVertices = 0;
        transparentVertices = 0;
        opaqueFloats = 0;
        transparentFloats = 0;
    }

    public boolean isEmpty()
    {
        return opaqueVertices == 0 && transparentVertices == 0;
    }

    public int faceCount()
    {
        return (opaqueVertices + transparentVertices) / VERTICES_PER_FACE;
    }

    /** Pro testy: postavené vrcholy obou sad, jak by šly na grafiku. */
    float[] opaqueData()      { return Arrays.copyOf(opaque, opaqueFloats); }
    float[] transparentData() { return Arrays.copyOf(transparent, transparentFloats); }
}
