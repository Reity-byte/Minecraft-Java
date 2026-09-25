package mc;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/**
 * Živý 3D náhled jednoho stromu v Ore/Biome Toneru.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ JDE CELOU CESTOU JAKO HRA, přesně jako náhled bloku (`BlockPreview`).
 * Strom se razítkuje `TreeShape.stamp()` - tímtéž kódem, jakým ho razítkuje
 * generátor do světa - do SKUTEČNÉHO malého světa, světlo mu spočítá
 * `LightEngine`, sekce postaví `ChunkMesh.build()` a kreslí je světový
 * shader s týmž atlasem. Náhled proto nemůže ukázat strom, jaký ve světě
 * nevyroste; kdyby se kreslil "podobně", byl by to obrázek, ne náhled.
 *
 * ⚠️ VÝŠKA KMENE A POLOMĚR KORUNY SE BEROU Z GENERÁTORU, NE Z Random().
 * Náhled si drží souřadnice vzorku a ptá se `TerrainGenerator.trunkHeight()`
 * a `crownDelta()` - tedy těch samých funkcí, které rozhodují ve světě.
 * "Reroll" je posun na jiné souřadnice vzorku, takže je každý ukázaný strom
 * doopravdy někde ve světě a ne vymyšlený.
 *
 * ⚠️ ZMĚNA PARAMETRU NEPŘEHAZUJE NÁHODNÝ VZOREK, TO DĚLÁ AŽ REROLL.
 * Kdyby se při každém kliknutí na [+] losovalo znovu, nešlo by poznat, jestli
 * se strom změnil kvůli té úpravě, nebo kvůli kostce - a to je přesně ta
 * chvíle, kdy se uživatel dívá. Při pevném vzorku je jediná proměnná ta
 * úprava. Rozsah je ale k tomu, aby se stromy lišily, takže se musí dát
 * podívat i na jiné losování - od toho je Reroll. Náhled se jinak přegeneruje
 * po KAŽDÉ změně parametru, takže je vidět hned.
 *
 * ⚠️ OTÁČÍ SE KAMERA, NE STROM - ze stejného důvodu jako u náhledu bloku:
 * ztmavení stěn je vázané na osy SVĚTA, takže by se s otočeným stromem
 * otáčely i odstíny a náhled by ukazoval něco, co ve hře nikdy neuvidíš.
 * ---------------------------------------------------------------------------
 */
public class TreePreview {

    /**
     * Kde strom v náhledovém světě stojí.
     *
     * Vysoko nad terénem (u výchozího seedu je kolem (8,8) povrch na 53),
     * volně ve vzduchu, na vlastní plošince. Lokální x a z jsou 8, takže se
     * i koruna o největším povoleném poloměru 6 vejde do jediného sloupce
     * (2 až 14) a nemusí se mešovat sousedi.
     */
    static final int X = 8, Z = 8, GROUND = 64;

    /** Plošinka pod kmenem, aby strom nevisel ve vzduchu. */
    private static final int PAD_RADIUS = 3;

    private static final float ELEVATION = (float) Math.toRadians(18);
    private static final float SPIN = 0.5f;   // rad/s
    private static final float FOV = 45f;

    private final ShaderProgram shader = new ShaderProgram(Shaders.WORLD_VERTEX, Shaders.WORLD_FRAGMENT);

    /** Malý skutečný svět, ve kterém strom stojí - viz komentář třídy. */
    private final World world = createWorld();

    /**
     * Mesh na každou sekci sloupce. Strom je vysoký až 18 bloků a s plošinkou
     * zasahuje do dvou i tří sekcí; jedna by mu useknula korunu.
     */
    private final ChunkMesh[] meshes = new ChunkMesh[ChunkColumn.SECTIONS];

    /** Co jsme minule postavili - při přestavbě se to zase odstraní. */
    private final List<int[]> placed = new ArrayList<>();

    private float angle = 0.7f;

    /** Souřadnice vzorku: z nich generátor spočítá výšku kmene a poloměr koruny. */
    private int sampleX = X, sampleZ = Z;

    /** Co je teď postavené - pro popisek pod náhledem. */
    private Biome.TreeType shownType = Biome.TreeType.NONE;
    private int shownTrunk = 0;
    private int shownRadius = 0;

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();

    /**
     * Náhledový svět: nejmenší okolí, jaké World umí, vygenerované a nasvícené hned.
     *
     * ⚠️ S VÝCHOZÍM tuningem, ne s aktivním. Strom stojí na plošince v pevné
     * výšce GROUND a počítá s tím, že terén u (8, 8) je hluboko pod ní
     * (povrch ~53). S aktivním tuningem stačilo uložit pláně se základem
     * od ~76 a terén plošinku i kmen zasypal - náhled pak ukazoval kmen
     * trčící z kopce bez koruny. Strom samotný staví show() podle
     * rozepsaného tuningu, terén pod ním do náhledu nepatří.
     */
    static World createWorld()
    {
        World world = new World(World.DEFAULT_SEED, BiomeTuning.defaults());
        world.loadRadius = 1;
        world.unloadRadius = 3;
        world.updateBlocking(X + 0.5f, Z + 0.5f);
        return world;
    }

    /**
     * Postaví strom daného biomu podle daného tuningu.
     *
     * Bez GL až na `upload()`, takže si stavbu může ověřit i test - ten volá
     * `stamp()` přímo.
     */
    public void show(Biome biome, BiomeTuning tuning, TerrainGenerator generator)
    {
        stamp(world, placed, biome, tuning, generator, sampleX, sampleZ);

        BiomeTuning.Tune tune = tuning.tune(biome);
        shownType = biome.treeType();
        shownTrunk = generator.trunkHeight(sampleX, sampleZ, tune);
        shownRadius = TreeShape.reach(shownType,
                generator.crownDelta(sampleX, sampleZ, shownType, tune));

        rebuildMeshes();
    }

    /**
     * Vyrazítkuje strom do světa; nejdřív uklidí ten předchozí.
     *
     * ⚠️ UKLÍZÍ SE PŘESNĚ TY BLOKY, KTERÉ SE MINULE POLOŽILY, ne obdélník
     * kolem stromu. Vyčistit celý kvádr by znamenalo tisíce změn bloku
     * s přepočtem světla u každé; takhle je jich tolik, kolik má strom.
     *
     * Bez GL - používá to i test, aby mohl ověřit, že v náhledu stojí tentýž
     * strom, jaký generátor postaví ve světě.
     */
    static void stamp(World world, List<int[]> placed, Biome biome, BiomeTuning tuning,
                      TerrainGenerator generator, int sampleX, int sampleZ)
    {
        for(int[] block : placed)
        {
            world.breakBlock(block[0], block[1], block[2]);
        }

        placed.clear();

        // Plošinka: povrchový blok toho biomu, ať je vidět i to, na čem
        // strom stojí (sníh v tundře, písek v poušti).
        for(int dx = -PAD_RADIUS; dx <= PAD_RADIUS; dx++)
        {
            for(int dz = -PAD_RADIUS; dz <= PAD_RADIUS; dz++)
            {
                put(world, placed, X + dx, GROUND - 1, Z + dz, biome.surface());
            }
        }

        Biome.TreeType type = biome.treeType();

        if(type == Biome.TreeType.NONE)
        {
            return;   // poušť: plošinka bez stromu, což je pravda o tom biomu
        }

        BiomeTuning.Tune tune = tuning.tune(biome);
        int trunk = generator.trunkHeight(sampleX, sampleZ, tune);
        int delta = generator.crownDelta(sampleX, sampleZ, type, tune);

        TreeShape.stamp(type, trunk, delta, X, GROUND, Z, new TreeShape.Sink() {

            @Override
            public void leaves(int x, int y, int z, byte block)
            {
                if(world.getBlock(x, y, z) == World.AIR)
                {
                    put(world, placed, x, y, z, block);
                }
            }

            @Override
            public void log(int x, int y, int z, byte block)
            {
                put(world, placed, x, y, z, block);
            }
        });

        // Světlo a sekce k přestavbě dopočítat naráz, ne po každém bloku.
        world.updateBlocking(X + 0.5f, Z + 0.5f);
    }

    private static void put(World world, List<int[]> placed, int x, int y, int z, byte block)
    {
        world.breakBlock(x, y, z);

        if(world.placeBlock(x, y, z, block))
        {
            placed.add(new int[]{x, y, z});
        }
    }

    /**
     * Přestaví meshe sekcí, ve kterých strom stojí.
     *
     * ⚠️ MEŠUJE SE JEN PÁS KOLEM STROMU, ne celý sloupec. Pod plošinkou leží
     * normální terén náhledového světa (povrch je u výchozího seedu kolem 53)
     * a ten do náhledu nepatří - kreslil by se patnáct bloků pod stromem jako
     * kus krajiny, kvůli kterému by strom na malém obrázku zdrobněl. A navíc
     * se ty sekce nikdy nemění, takže by se přestavovaly při každém kliknutí
     * na [+] zadarmo.
     */
    private void rebuildMeshes()
    {
        ChunkColumn column = world.column(X >> Chunk.BITS, Z >> Chunk.BITS);

        // Od plošinky po vrchol nejvyššího možného stromu.
        int from = (GROUND - 1) >> Chunk.BITS;
        int to = Math.min(ChunkColumn.SECTIONS - 1,
                (GROUND + TreeShape.totalHeight(BiomeTuning.MAX_TRUNK)) >> Chunk.BITS);

        for(int section = 0; section < ChunkColumn.SECTIONS; section++)
        {
            if(section < from || section > to)
            {
                if(meshes[section] != null)
                {
                    meshes[section].delete();
                    meshes[section] = null;
                }

                continue;
            }

            Chunk chunk = column.section(section);

            if(meshes[section] != null)
            {
                meshes[section].delete();
                meshes[section] = null;
            }

            if(chunk == null)
            {
                continue;
            }

            ChunkMesh mesh = new ChunkMesh();
            mesh.build(world, chunk, (X >> Chunk.BITS) << Chunk.BITS,
                    section << Chunk.BITS, (Z >> Chunk.BITS) << Chunk.BITS);

            if(mesh.isEmpty())
            {
                mesh.delete();
                continue;
            }

            mesh.upload();
            meshes[section] = mesh;
        }
    }

    /** Jiné losování v rámci nastaveného rozsahu - viz poznámka u třídy. */
    public void reroll()
    {
        // Posun po velkých nekulatých krocích: sousední souřadnice by daly
        // podobný hash a "reroll" by někdy nic nezměnil.
        sampleX += 977;
        sampleZ += 1361;
    }

    public Biome.TreeType shownType() { return shownType; }
    public int shownTrunk()  { return shownTrunk; }
    public int shownRadius() { return shownRadius; }

    public void update(float dt)
    {
        angle += SPIN * Math.min(dt, 0.1f);
    }

    /**
     * Nakreslí náhled do obdélníku obrazovky (x, y = levý dolní roh).
     * Vlastní viewport a scissor; hloubka se maže jen uvnitř - stejně jako
     * u náhledu bloku.
     */
    public void draw(Texture atlas, int x, int y, int width, int height,
                     int screenWidth, int screenHeight)
    {
        if(width <= 0 || height <= 0)
        {
            return;
        }

        // Kamera míří doprostřed stromu, ne na jeho patu - jinak by u vysokého
        // kmene koruna vylezla z obrázku.
        float cx = X + 0.5f;
        float cy = GROUND + Math.max(4, shownTrunk) * 0.6f;
        float cz = Z + 0.5f;

        float distance = 9f + Math.max(0, shownTrunk - 4) * 0.9f + shownRadius * 1.1f;

        float horizontal = (float) Math.cos(ELEVATION) * distance;
        float ex = cx + (float) Math.cos(angle) * horizontal;
        float ey = cy + (float) Math.sin(ELEVATION) * distance;
        float ez = cz + (float) Math.sin(angle) * horizontal;

        view.setLookAt(0, 0, 0, cx - ex, cy - ey, cz - ez, 0, 1, 0);
        projection.setPerspective((float) Math.toRadians(FOV), width / (float) height, 0.05f, 200f);
        projection.mul(view, viewProjection);

        glViewport(x, y, width, height);
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, y, width, height);
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);

        shader.bind();
        shader.setMatrix4("uViewProjection", viewProjection);

        // Poledne bez mlhy - strom tak, jak ho hráč vidí za jasného dne.
        shader.setFloat("uDaylight", 1f);
        shader.setFloat("uAmbient", DayCycle.AMBIENT);
        shader.setVector3("uFogColor", WorldRenderer.SKY_R, WorldRenderer.SKY_G, WorldRenderer.SKY_B);
        shader.setFloat("uFogStart", 1000f);
        shader.setFloat("uFogEnd", 2000f);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlas.id());
        shader.setInt("uAtlas", 0);

        int baseX = (X >> Chunk.BITS) << Chunk.BITS;
        int baseZ = (Z >> Chunk.BITS) << Chunk.BITS;

        // Neprůhledný průchod bez míchání, jako ve světě (viz BlockPreview).
        glDisable(GL_BLEND);

        for(int section = 0; section < meshes.length; section++)
        {
            if(meshes[section] == null)
            {
                continue;
            }

            shader.setVector3("uChunkOffset", baseX - ex,
                    (section << Chunk.BITS) - ey, baseZ - ez);
            meshes[section].drawOpaque();
        }

        // Voda (jezero pod plošinkou) druhým průchodem, jako ve světě.
        for(int section = 0; section < meshes.length; section++)
        {
            if(meshes[section] == null || !meshes[section].hasTransparent())
            {
                continue;
            }

            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);
            shader.setVector3("uChunkOffset", baseX - ex,
                    (section << Chunk.BITS) - ey, baseZ - ez);
            meshes[section].drawTransparent();
            glDepthMask(true);
            glDisable(GL_BLEND);
        }

        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_SCISSOR_TEST);
        glViewport(0, 0, screenWidth, screenHeight);
    }

    public void delete()
    {
        for(int i = 0; i < meshes.length; i++)
        {
            if(meshes[i] != null)
            {
                meshes[i].delete();
                meshes[i] = null;
            }
        }

        shader.delete();
        world.shutdown();
    }
}
