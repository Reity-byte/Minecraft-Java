package mc;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL33.*;

/**
 * Veškeré kreslení světa. World je od kroku A3 čistě data - nemá v sobě
 * jediné GL volání.
 *
 * Drží cache meshů (jeden na sekci), staví je líně při prvním zobrazení
 * a přestavuje, když World ohlásí změnu bloku.
 */
public class WorldRenderer {

    public static final float SKY_R = 0.53f;
    public static final float SKY_G = 0.81f;
    public static final float SKY_B = 0.92f;

    /**
     * Barva a dosah mlhy pod vodou. Krátký dohled je to hlavní, co dělá
     * "jsem potopený" - bez něj je pod hladinou vidět stejně daleko jako nad ní.
     */
    public static final float WATER_FOG_R = 0.10f;
    public static final float WATER_FOG_G = 0.26f;
    public static final float WATER_FOG_B = 0.48f;

    private static final float WATER_FOG_START = 1f;
    private static final float WATER_FOG_END   = 22f;

    /**
     * Kolik času za frame se smí strávit stavbou nových meshů.
     *
     * Rozpočet je časový, ne počtem sekcí: počet by se na pomalém stroji
     * do framu nevešel a na rychlém by se nevyužil. Kontroluje se PŘED každou
     * stavbou, takže se rozpočet může přetáhnout nejvýš o jednu sekci
     * (~0,5 ms) - to je přijatelné a je to cena za to, že se nemusí měřit
     * dopředu, jak dlouho konkrétní sekce potrvá.
     */
    public static final long BUILD_BUDGET_PLAYING = 4_000_000L;  // 4 ms
    /** Při loadingu není co zdržovat - vyšší rozpočet zkrátí čekání. */
    public static final long BUILD_BUDGET_LOADING = 12_000_000L; // 12 ms

    private long buildBudgetNanos = BUILD_BUDGET_PLAYING;

    /** Sekce čekající na první postavení meshe. Fronta se plní během kreslení. */
    private record PendingBuild(ChunkColumn column, int sectionIndex, float distanceSquared) {}

    private final ShaderProgram shader = new ShaderProgram(Shaders.WORLD_VERTEX, Shaders.WORLD_FRAGMENT);

    /** Obrys bloku má vlastní program - jeho vrcholy nesou jen pozici. Viz Shaders. */
    private final ShaderProgram outlineShader =
            new ShaderProgram(Shaders.OUTLINE_VERTEX, Shaders.OUTLINE_FRAGMENT);

    /**
     * Atlas textur bloků. Vlastní ho Main a sdílí ho s BlockIcon - ikony
     * v hotbaru a v inventáři berou dlaždice ze stejné textury jako svět.
     */
    private final Texture atlas;

    /** Skin postavy ve třetí osobě. Vlastní ho Main, stejně jako atlas. */
    private final Texture skin;

    /** Meshe po sloupcích, stejný klíč jako používá World. Pole má jednu položku na sekci. */
    private final Map<Long, ChunkMesh[]> columnMeshes = new HashMap<>();

    // Matice se drží jako pole, ne aby se každý frame alokovaly nové.
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();

    private int outlineVao = 0;
    private int outlineVbo = 0;

    // Praskliny: krychle s UV, kreslená přes rozbíjený blok.
    private int crackVao = 0;
    private int crackVbo = 0;
    private final float[] crackData = new float[6 * 6 * 5];   // 6 stěn, 6 vrcholů, pozice+uv
    private final java.nio.FloatBuffer crackUpload =
            org.lwjgl.BufferUtils.createFloatBuffer(crackData.length);

    /** Vlastní program: textura z atlasu, ale bez světla a mlhy. */
    private final ShaderProgram crackShader =
            new ShaderProgram(Shaders.CRACK_VERTEX, Shaders.CRACK_FRAGMENT);

    /**
     * Předměty na zemi. Kreslí se světovým shaderem, takže mají stejné
     * světlo i mlhu jako terén - viz DroppedItemMesh.
     */
    private final DroppedItemMesh itemMesh = new DroppedItemMesh();

    /** Dál než tohle se položky nekreslí - čtvrtinová kostka je tam pixel nebo dva. */
    private static final float ITEM_DRAW_DISTANCE = 64f;

    private final List<PendingBuild> pending = new ArrayList<>();

    /** Jedna sekce připravená k druhému, průhlednému průchodu. */
    private record TransparentSection(ChunkMesh mesh, int baseX, int baseY, int baseZ,
                                      float distanceSquared) {}

    /**
     * Sekce s vodou, posbírané během neprůhledného průchodu. Sbírá se to takhle
     * schválně: průhledné stěny se musí kreslit až po VŠECH neprůhledných, jinak
     * by voda přebila terén, který je za ní a ještě se nenakreslil.
     */
    private final List<TransparentSection> transparentSections = new ArrayList<>();

    // statistiky pro debug titulek
    private int drawnSections = 0;
    private int drawnFaces = 0;
    private int meshesBuiltThisFrame = 0;
    private int pendingCount = 0;

    public WorldRenderer(Texture atlas, Texture skin)
    {
        this.atlas = atlas;
        this.skin = skin;
        createOutlineMesh();
    }

    // ------------------------------------------------------------------
    // hlavní render
    // ------------------------------------------------------------------

    /**
     * @param droppedItems předměty na zemi
     * @param body         postava hráče už postavená v póze, nebo null
     *                     (v první osobě se vlastní tělo nekreslí)
     */
    public void render(World world, Camera camera, int width, int height, float fovDegrees,
                       boolean underwater, DayCycle day, List<DroppedItem> droppedItems,
                       PlayerModelMesh body)
    {
        meshesBuiltThisFrame = 0;
        drawnSections = 0;
        drawnFaces = 0;

        processDirtySections(world);
        evictUnloadedColumns(world);

        projection.setPerspective(
                (float) Math.toRadians(fovDegrees),
                (float) width / height,
                0.05f,
                Math.max(1000f, world.renderDistance * 2f));

        camera.viewMatrix(view);
        projection.mul(view, viewProjection);

        shader.bind();
        shader.setMatrix4("uViewProjection", viewProjection);
        // Slunce slábne jedním uniformem; uložené světlo zůstává, jak je.
        shader.setFloat("uDaylight", day.daylight());
        shader.setFloat("uAmbient", DayCycle.AMBIENT);

        float[] sky = day.skyColor();

        if(underwater)
        {
            shader.setVector3("uFogColor", WATER_FOG_R, WATER_FOG_G, WATER_FOG_B);
            shader.setFloat("uFogStart", WATER_FOG_START);
            shader.setFloat("uFogEnd", WATER_FOG_END);
        }
        else
        {
            // Mlha má barvu oblohy, jinak by na obzoru byl světlý pruh
            // i uprostřed noci.
            shader.setVector3("uFogColor", sky[0], sky[1], sky[2]);
            shader.setFloat("uFogStart", world.renderDistance * 0.55f);
            shader.setFloat("uFogEnd", world.renderDistance);
        }

        // Atlas je pro všechny sekce stejný, takže se váže jednou za frame.
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlas.id());
        shader.setInt("uAtlas", 0);

        float renderDistanceSquared = world.renderDistance * world.renderDistance;

        for(ChunkColumn column : world.loadedColumns())
        {
            int baseX = column.cx << Chunk.BITS;
            int baseZ = column.cz << Chunk.BITS;

            ChunkMesh[] meshes = null;

            for(int s = 0; s < ChunkColumn.SECTIONS; s++)
            {
                Chunk section = column.section(s);
                if(section == null || section.isEmpty())
                {
                    continue;
                }

                int baseY = s << Chunk.BITS;

                float distanceSquared = sectionDistanceSquared(baseX, baseY, baseZ, camera);
                if(distanceSquared >= renderDistanceSquared)
                {
                    continue;
                }

                if(meshes == null)
                {
                    meshes = columnMeshes.computeIfAbsent(
                            World.key(column.cx, column.cz),
                            k -> new ChunkMesh[ChunkColumn.SECTIONS]);
                }

                ChunkMesh mesh = meshes[s];
                if(mesh == null)
                {
                    // Sekci nemešujeme, dokud nejsou načtení všichni čtyři sousední
                    // sloupce. Kdyby se stavěla dřív, brala by nenačteného souseda
                    // jako vzduch a vygenerovala by na hranici stěnu, která tam
                    // nepatří - a ta by v cache zůstala i po doplnění souseda.
                    // Tohle je čistší než hlídat invalidaci při každém dogenerování.
                    if(neighboursLoaded(world, column.cx, column.cz))
                    {
                        // Nestaví se hned, jen se zařadí do fronty a postaví se až
                        // po vykreslení, v rámci časového rozpočtu. Tenhle frame se
                        // sekce ještě nekreslí - objeví se o pár framů později.
                        pending.add(new PendingBuild(column, s, distanceSquared));
                    }
                    continue;
                }

                if(mesh.isEmpty())
                {
                    continue;
                }

                // Offset chunku vůči kameře. Odečtení se dělá tady na CPU v double,
                // aby do shaderu šla jen malá čísla - viz komentář v Shaders.
                shader.setVector3("uChunkOffset",
                        (float) (baseX - (double) camera.x),
                        (float) (baseY - (double) camera.y),
                        (float) (baseZ - (double) camera.z));

                mesh.drawOpaque();

                if(mesh.hasTransparent())
                {
                    transparentSections.add(new TransparentSection(
                            mesh, baseX, baseY, baseZ, distanceSquared));
                }

                drawnSections++;
                drawnFaces += mesh.faceCount();
            }
        }

        // ⚠️ Položky jsou neprůhledné, takže patří PŘED vodu. Voda nezapisuje
        // hloubku, takže položka nakreslená po ní by přes hladinu prosvítala
        // bez modrého nádechu, jako by ležela nad vodou.
        drawDroppedItems(droppedItems, world, camera);
        drawPlayer(body);

        drawTransparent(camera);

        buildPending(world);
    }

    /**
     * Předměty na zemi. Shader, matice, světlo, mlha i atlas jsou už nastavené
     * z render() - položky mají vrcholy relativní ke kameře, takže se změní
     * jen posun, a to na nulu.
     */
    private void drawDroppedItems(List<DroppedItem> items, World world, Camera camera)
    {
        if(items.isEmpty())
        {
            return;
        }

        itemMesh.build(items, world, camera.x, camera.y, camera.z,
                Math.min(ITEM_DRAW_DISTANCE, world.renderDistance));

        if(itemMesh.isEmpty())
        {
            return;
        }

        shader.setVector3("uChunkOffset", 0f, 0f, 0f);

        itemMesh.upload();
        itemMesh.draw();
    }

    /**
     * Postava ve třetí osobě. Stejný světový shader jako položky na zemi -
     * vrcholy jsou relativní ke kameře, takže posun je nula - jen se pro tělo
     * na chvíli podstrčí textura skinu místo atlasu. Držený blok jde pak zase
     * z atlasu, a ten musí zůstat navázaný i pro vodu po nás.
     */
    private void drawPlayer(PlayerModelMesh body)
    {
        if(body == null || body.isEmpty())
        {
            return;
        }

        shader.setVector3("uChunkOffset", 0f, 0f, 0f);
        body.upload();

        glBindTexture(GL_TEXTURE_2D, skin.id());
        body.drawSkin();

        glBindTexture(GL_TEXTURE_2D, atlas.id());
        body.drawItem();
    }

    /**
     * Druhý průchod: voda.
     *
     * Tři věci, bez kterých to nevypadá správně:
     *
     * 1) Kreslí se OD NEJVZDÁLENĚJŠÍ. Průhlednost se míchá s tím, co je už
     *    v bufferu, takže na pořadí záleží - blízká voda nakreslená první by
     *    se nesmíchala s tou vzdálenější za ní.
     *
     * 2) Zápis do depth bufferu je VYPNUTÝ (glDepthMask). Test hloubky zůstává,
     *    takže voda zmizí za terénem, ale sama si nezacloní vodu za sebou.
     *
     * 3) Až po celém neprůhledném průchodu. Voda kreslená průběžně by míchala
     *    s barvou oblohy místo s terénem, který se nakreslí až po ní.
     */
    private void drawTransparent(Camera camera)
    {
        if(transparentSections.isEmpty())
        {
            return;
        }

        transparentSections.sort(
                Comparator.comparingDouble(TransparentSection::distanceSquared).reversed());

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);

        for(TransparentSection section : transparentSections)
        {
            shader.setVector3("uChunkOffset",
                    (float) (section.baseX() - (double) camera.x),
                    (float) (section.baseY() - (double) camera.y),
                    (float) (section.baseZ() - (double) camera.z));

            section.mesh().drawTransparent();
        }

        glDepthMask(true);
        glDisable(GL_BLEND);

        transparentSections.clear();
    }

    /**
     * Postaví meshe z fronty, nejbližší nejdřív, dokud nedojde časový rozpočet.
     *
     * Nejbližší nejdřív je důležité: svět se pak dosypává v kruhu kolem hráče
     * místo v náhodných ostrovech (pořadí HashMapy je libovolné). Zbytek fronty
     * se zahodí a příští frame se nasbírá znovu z aktuální pozice kamery -
     * díky tomu se fronta sama přeuspořádá, když se hráč pohne, a nemůže
     * zůstat viset odkaz na mezitím uvolněný sloupec.
     */
    private void buildPending(World world)
    {
        pendingCount = pending.size();

        if(pending.isEmpty())
        {
            return;
        }

        pending.sort(Comparator.comparingDouble(PendingBuild::distanceSquared));

        long deadline = System.nanoTime() + buildBudgetNanos;

        for(PendingBuild build : pending)
        {
            if(System.nanoTime() >= deadline)
            {
                break;
            }

            ChunkColumn column = build.column();
            Chunk section = column.section(build.sectionIndex());

            if(section == null || section.isEmpty())
            {
                continue;
            }

            ChunkMesh[] meshes = columnMeshes.get(World.key(column.cx, column.cz));
            if(meshes == null || meshes[build.sectionIndex()] != null)
            {
                continue;
            }

            ChunkMesh mesh = new ChunkMesh();
            mesh.build(world, section,
                    column.cx << Chunk.BITS,
                    build.sectionIndex() << Chunk.BITS,
                    column.cz << Chunk.BITS);
            mesh.upload();

            meshes[build.sectionIndex()] = mesh;
            meshesBuiltThisFrame++;
        }

        pending.clear();
    }

    private static boolean neighboursLoaded(World world, int cx, int cz)
    {
        return world.hasColumn(cx - 1, cz)
                && world.hasColumn(cx + 1, cz)
                && world.hasColumn(cx, cz - 1)
                && world.hasColumn(cx, cz + 1);
    }

    /** Přestaví meshe sekcí, které World označil jako změněné. */
    private void processDirtySections(World world)
    {
        Set<World.SectionPos> dirty = world.dirtySections();
        if(dirty.isEmpty())
        {
            return;
        }

        for(World.SectionPos pos : dirty)
        {
            ChunkMesh[] meshes = columnMeshes.get(World.key(pos.cx(), pos.cz()));
            if(meshes == null)
            {
                continue; // sloupec se ještě nekreslil, mesh se postaví sám při prvním zobrazení
            }

            ChunkMesh mesh = meshes[pos.cy()];
            if(mesh == null)
            {
                continue;
            }

            ChunkColumn column = world.column(pos.cx(), pos.cz());
            Chunk section = column == null ? null : column.section(pos.cy());

            if(section == null)
            {
                mesh.delete();
                meshes[pos.cy()] = null;
                continue;
            }

            mesh.build(world, section, pos.cx() << Chunk.BITS, pos.cy() << Chunk.BITS, pos.cz() << Chunk.BITS);
            mesh.upload();
        }

        dirty.clear();
    }

    /** Uvolní VBO sloupců, které World mezitím zahodil - jinak by paměť grafiky rostla donekonečna. */
    private void evictUnloadedColumns(World world)
    {
        Iterator<Map.Entry<Long, ChunkMesh[]>> it = columnMeshes.entrySet().iterator();

        while(it.hasNext())
        {
            Map.Entry<Long, ChunkMesh[]> entry = it.next();

            if(world.hasColumn(entry.getKey()))
            {
                continue;
            }

            for(ChunkMesh mesh : entry.getValue())
            {
                if(mesh != null)
                {
                    mesh.delete();
                }
            }

            it.remove();
        }
    }

    /**
     * Vzdálenost kamery od kvádru sekce: v každé ose se vezme, o kolik kamera
     * leží mimo interval <base, base+16>. Uvnitř intervalu je příspěvek nula.
     * Pracuje se s druhými mocninami, aby se nemuselo odmocňovat.
     */
    private static float sectionDistanceSquared(int baseX, int baseY, int baseZ, Camera camera)
    {
        float dx = axisDistance(camera.x, baseX);
        float dy = axisDistance(camera.y, baseY);
        float dz = axisDistance(camera.z, baseZ);

        return dx * dx + dy * dy + dz * dz;
    }

    private static float axisDistance(float cam, int base)
    {
        if(cam < base)
        {
            return base - cam;
        }

        float max = base + Chunk.SIZE;
        if(cam > max)
        {
            return cam - max;
        }

        return 0f;
    }

    // ------------------------------------------------------------------
    // obrys zaměřeného bloku
    // ------------------------------------------------------------------

    /**
     * Obrys je pořád ta samá jednotková krychle, mění se jen její pozice.
     * Postaví se proto jednou do VBO a při kreslení se posouvá uniformem -
     * žádné přenahrávání dat každý frame.
     */
    private void createOutlineMesh()
    {
        float pad = 0.002f;
        float a = -pad;
        float b = 1f + pad;

        float[][] corners = {
                {a, a, a}, {b, a, a}, {b, a, b}, {a, a, b},   // spodní čtverec
                {a, b, a}, {b, b, a}, {b, b, b}, {a, b, b}    // horní čtverec
        };

        int[] edges = {
                0, 1, 1, 2, 2, 3, 3, 0,   // spodní čtverec
                4, 5, 5, 6, 6, 7, 7, 4,   // horní čtverec
                0, 4, 1, 5, 2, 6, 3, 7    // svislé hrany
        };

        // Jen pozice - barvu dodá uniform v OUTLINE_FRAGMENT.
        float[] data = new float[edges.length * 3];
        int i = 0;

        for(int corner : edges)
        {
            data[i++] = corners[corner][0];
            data[i++] = corners[corner][1];
            data[i++] = corners[corner][2];
        }

        outlineVao = glGenVertexArrays();
        outlineVbo = glGenBuffers();

        glBindVertexArray(outlineVao);
        glBindBuffer(GL_ARRAY_BUFFER, outlineVbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);

        glBindVertexArray(0);
    }

    /**
     * Matice pohledu a projekce bez posunu kamery - kamera sedí v počátku.
     * Potřebuje ji obloha, která se kreslí PŘED světem, tedy dřív, než si ji
     * spočítá render().
     */
    public Matrix4f viewProjection(Camera camera, int width, int height, float fovDegrees)
    {
        projection.setPerspective(
                (float) Math.toRadians(fovDegrees),
                (float) width / height,
                0.05f,
                1000f);

        camera.viewMatrix(view);
        return projection.mul(view, viewProjection);
    }

    /**
     * Praskliny na rozbíjeném bloku.
     *
     * ⚠️ Krychle je o kousek NAFOUKLÁ. Kdyby ležela přesně na bloku, hloubkový
     * test by mezi ní a jeho stěnou nedokázal rozhodnout a obraz by se rozblikal
     * (z-fighting). Nafouknutí je menší než jeden pixel textury, takže není
     * poznat, ale hloubkovému testu stačí.
     *
     * Volat až po render(), protože využívá jeho matice.
     */
    public void drawCracks(int x, int y, int z, int stage, Camera camera)
    {
        if(stage < 0)
        {
            return;
        }

        if(crackVao == 0)
        {
            crackVao = glGenVertexArrays();
            crackVbo = glGenBuffers();

            glBindVertexArray(crackVao);
            glBindBuffer(GL_ARRAY_BUFFER, crackVbo);
            glBufferData(GL_ARRAY_BUFFER, (long) crackData.length * Float.BYTES, GL_DYNAMIC_DRAW);

            int stride = 5 * Float.BYTES;
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3L * Float.BYTES);
            glEnableVertexAttribArray(1);

            glBindVertexArray(0);
        }

        int tile = BlockAtlas.TILE_CRACK_FIRST
                + Math.min(stage, BlockAtlas.CRACK_STAGES - 1);

        buildCrackCube(tile);

        crackShader.bind();
        crackShader.setMatrix4("uViewProjection", viewProjection);
        crackShader.setVector3("uChunkOffset",
                (float) (x - (double) camera.x),
                (float) (y - (double) camera.y),
                (float) (z - (double) camera.z));

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlas.id());
        crackShader.setInt("uAtlas", 0);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);

        crackUpload.clear();
        crackUpload.put(crackData);
        crackUpload.flip();

        glBindVertexArray(crackVao);
        glBindBuffer(GL_ARRAY_BUFFER, crackVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, crackUpload);
        glDrawArrays(GL_TRIANGLES, 0, 36);
        glBindVertexArray(0);

        glDepthMask(true);
        glDisable(GL_BLEND);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /** Šest stěn nafouknuté krychle, každá s celou dlaždicí prasklin. */
    private void buildCrackCube(int tile)
    {
        float pad = 0.002f;
        float a = -pad, b = 1f + pad;

        float u0 = BlockAtlas.u0(tile), u1 = BlockAtlas.u1(tile);
        float v0 = BlockAtlas.v0(tile), v1 = BlockAtlas.v1(tile);

        // Pořadí rohů pro každou stěnu, proti směru hodinových ručiček zvenku.
        float[][] faces = {
                {a, b, a,  a, b, b,  b, b, b,  b, b, a},   // +Y
                {a, a, a,  b, a, a,  b, a, b,  a, a, b},   // -Y
                {b, a, a,  b, b, a,  b, b, b,  b, a, b},   // +X
                {a, a, a,  a, a, b,  a, b, b,  a, b, a},   // -X
                {a, a, b,  b, a, b,  b, b, b,  a, b, b},   // +Z
                {a, a, a,  a, b, a,  b, b, a,  b, a, a}    // -Z
        };

        float[][] uvs = {
                {u0, v0,  u0, v1,  u1, v1,  u1, v0},
                {u0, v0,  u1, v0,  u1, v1,  u0, v1},
                {u0, v0,  u0, v1,  u1, v1,  u1, v0},
                {u0, v0,  u1, v0,  u1, v1,  u0, v1},
                {u0, v0,  u1, v0,  u1, v1,  u0, v1},
                {u0, v0,  u0, v1,  u1, v1,  u1, v0}
        };

        int i = 0;
        int[] order = {0, 1, 2, 0, 2, 3};

        for(int face = 0; face < 6; face++)
        {
            for(int o : order)
            {
                crackData[i++] = faces[face][o * 3];
                crackData[i++] = faces[face][o * 3 + 1];
                crackData[i++] = faces[face][o * 3 + 2];
                crackData[i++] = uvs[face][o * 2];
                crackData[i++] = uvs[face][o * 2 + 1];
            }
        }
    }

    /** Volat až po render(), protože využívá jeho matice. */
    public void drawBlockOutline(int x, int y, int z, Camera camera)
    {
        outlineShader.bind();
        outlineShader.setMatrix4("uViewProjection", viewProjection);
        outlineShader.setVector4("uColor", 0f, 0f, 0f, 1f);
        outlineShader.setVector3("uChunkOffset",
                (float) (x - (double) camera.x),
                (float) (y - (double) camera.y),
                (float) (z - (double) camera.z));

        // V core profilu není šířka čáry > 1 zaručená; většina desktopových
        // ovladačů ji ale respektuje. Když bude obrys tenký, je to tohle.
        glLineWidth(3f);

        glBindVertexArray(outlineVao);
        glDrawArrays(GL_LINES, 0, 24);
        glBindVertexArray(0);

        glLineWidth(1f);
    }

    // ------------------------------------------------------------------

    public int drawnSections() { return drawnSections; }
    public int drawnFaces()    { return drawnFaces; }
    public int meshesBuilt()   { return meshesBuiltThisFrame; }

    /** Kolik sekcí čekalo tenhle frame na postavení meshe - 0 znamená "dosypáno". */
    public int pendingBuilds() { return pendingCount; }

    public void setBuildBudget(long nanos) { buildBudgetNanos = nanos; }

    /**
     * Zahodí všechny meshe. Volá se při vytváření nového světa - jinak by
     * v cache zůstaly buffery od starého a paměť grafiky by rostla.
     */
    public void reset()
    {
        for(ChunkMesh[] meshes : columnMeshes.values())
        {
            for(ChunkMesh mesh : meshes)
            {
                if(mesh != null)
                {
                    mesh.delete();
                }
            }
        }

        columnMeshes.clear();
        pending.clear();
        pendingCount = 0;
    }

    public void delete()
    {
        for(ChunkMesh[] meshes : columnMeshes.values())
        {
            for(ChunkMesh mesh : meshes)
            {
                if(mesh != null)
                {
                    mesh.delete();
                }
            }
        }

        columnMeshes.clear();

        glDeleteVertexArrays(outlineVao);
        glDeleteBuffers(outlineVbo);

        if(crackVao != 0)
        {
            glDeleteVertexArrays(crackVao);
            glDeleteBuffers(crackVbo);
        }

        itemMesh.delete();
        crackShader.delete();
        outlineShader.delete();
        shader.delete();
    }
}
