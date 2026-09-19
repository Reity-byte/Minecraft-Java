package mc;

import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL33.*;

/**
 * Živý 3D náhled bloku v texture labu.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ NÁHLED JDE CELOU CESTOU JAKO HRA. Nejde o podobnou kostku: blok stojí
 * ve SKUTEČNÉM (malém) světě, světlo mu spočítá skutečný LightEngine, sekci
 * postaví ChunkMesh.build(), nahraje se jako každá jiná sekce a kreslí ji
 * světový shader (WORLD_VERTEX/FRAGMENT) s týmž atlasem. Ztmavení stěn
 * (SHADE_*), UV vzorce, půltexelové zúžení, modely (pochodeň, plot),
 * průhlednost vody i vlastní záře pochodně jsou tak přesně jako ve hře,
 * protože je počítá tentýž kód. TextureLabTest to hlídá: mesh náhledu je
 * bajt po bajtu tentýž, jaký hra postaví pro blok volně ve vzduchu.
 *
 * ⚠️ Prázdný svět (bez načtených sloupců) by NESTAČIL. Na nenačtené místo sice
 * World odpovídá "vzduch s plným sluncem", ale plný blok ve hře zastíní buňku
 * pod sebou - má sluneční světlo 14, ne 15 - a jeho spodní stěna vyjde o 1,7 %
 * tmavší. Prázdný svět tenhle vlastní stín neumí; změřil to právě test.
 * Malý svět stojí při otevření labu pár desítek milisekund (viz
 * ARCHITECTURE.md) a delete() ho zastaví jako každý jiný svět.
 *
 * ⚠️ OTÁČÍ SE KAMERA, NE BLOK. Ztmavení stěn je ve hře vázané na osy SVĚTA
 * (vršek 1, boky X 0,6, boky Z 0,8) - kdyby se točil blok, točily by se
 * s ním i odstíny a náhled by ukazoval něco, co ve hře nikdy neuvidíš.
 * Takhle vypadá přesně jako blok, kolem kterého hráč obchází.
 *
 * Mesh se staví jen při změně bloku. Malování mění pixely atlasu, ne UV,
 * takže stačí, že se atlas přenahraje - kostka se změní v tom samém framu.
 * ---------------------------------------------------------------------------
 */
public class BlockPreview {

    /**
     * Kde blok v náhledovém světě stojí: vysoko nad terénem (ten končí pod 80,
     * stromy o kus výš), volně ve vzduchu, uprostřed sekce 7 - na lokální
     * pozici (8, 8, 8), takže sousedy čte mesher z pole sekce.
     */
    static final int X = 8, Y = 120, Z = 8;
    static final int BASE_X = (X >> Chunk.BITS) << Chunk.BITS;
    static final int BASE_Y = (Y >> Chunk.BITS) << Chunk.BITS;
    static final int BASE_Z = (Z >> Chunk.BITS) << Chunk.BITS;

    private static final float DISTANCE = 2.4f;
    private static final float ELEVATION = (float) Math.toRadians(28);
    private static final float SPIN = 0.6f;   // rad/s
    private static final float FOV = 40f;

    private final ShaderProgram shader = new ShaderProgram(Shaders.WORLD_VERTEX, Shaders.WORLD_FRAGMENT);

    /** Malý skutečný svět, ve kterém blok stojí - viz komentář třídy. */
    private final World world = createWorld();

    private final ChunkMesh mesh = new ChunkMesh();
    private byte block = World.AIR;
    private float angle = 0.8f;

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();

    /**
     * Náhledový svět: nejmenší okolí, jaké World umí (3x3 sloupce), vygenerované
     * a nasvícené hned - updateBlocking() stejně jako v testech.
     */
    static World createWorld()
    {
        World world = new World();
        world.loadRadius = 1;
        world.unloadRadius = 3;
        world.updateBlocking(X + 0.5f, Z + 0.5f);
        return world;
    }

    /**
     * Postaví blok do náhledového světa (ten předchozí rozbije), nechá světlo
     * dopočítat a jeho sekci převede na mesh - stejně, jako hra staví sekci
     * po položení bloku. Bez GL; volá ho i test.
     */
    static void build(ChunkMesh mesh, World world, byte block)
    {
        world.breakBlock(X, Y, Z);

        if(block != World.AIR)
        {
            world.placeBlock(X, Y, Z, block);
        }

        world.updateBlocking(X + 0.5f, Z + 0.5f);

        Chunk section = world.column(X >> Chunk.BITS, Z >> Chunk.BITS).section(Y >> Chunk.BITS);
        mesh.build(world, section, BASE_X, BASE_Y, BASE_Z);
    }

    /** Přepne náhled na jiný blok. World.AIR = nic (dlaždici nepoužívá žádný blok). */
    public void show(byte newBlock)
    {
        show(newBlock, false);
    }

    /**
     * rebuild = postavit znovu, i když jde o tentýž blok. Rozepsaný blok
     * z labu má pořád stejné id, ale mění se mu dlaždice i neprůhlednost -
     * a to jsou UV a světlo v meshi, ne jen pixely atlasu.
     */
    public void show(byte newBlock, boolean rebuild)
    {
        if(newBlock == block && !rebuild)
        {
            return;
        }

        block = newBlock;
        mesh.delete();

        if(block != World.AIR)
        {
            build(mesh, world, block);
            mesh.upload();
        }
    }

    public byte block()
    {
        return block;
    }

    public void update(float dt)
    {
        angle += SPIN * Math.min(dt, 0.1f);
    }

    /**
     * Nakreslí náhled do obdélníku obrazovky (x, y = levý dolní roh).
     * Vlastní viewport a scissor; hloubka se maže jen uvnitř.
     */
    public void draw(Texture atlas, int x, int y, int width, int height,
                     int screenWidth, int screenHeight)
    {
        if(block == World.AIR || mesh.isEmpty() || width <= 0 || height <= 0)
        {
            return;
        }

        float cx = X + 0.5f;
        float cy = Y + 0.5f;
        float cz = Z + 0.5f;

        float horizontal = (float) Math.cos(ELEVATION) * DISTANCE;
        float ex = cx + (float) Math.cos(angle) * horizontal;
        float ey = cy + (float) Math.sin(ELEVATION) * DISTANCE;
        float ez = cz + (float) Math.sin(angle) * horizontal;

        // Stejná úmluva jako svět: oko v počátku, matice pohledu je jen
        // rotace a sekce se k němu posune uniformem uChunkOffset.
        view.setLookAt(0, 0, 0, cx - ex, cy - ey, cz - ez, 0, 1, 0);
        projection.setPerspective((float) Math.toRadians(FOV), width / (float) height, 0.05f, 50f);
        projection.mul(view, viewProjection);

        glViewport(x, y, width, height);
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, y, width, height);
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);

        shader.bind();
        shader.setMatrix4("uViewProjection", viewProjection);
        shader.setVector3("uChunkOffset", BASE_X - ex, BASE_Y - ey, BASE_Z - ez);

        // Poledne bez mlhy - blok tak, jak ho hráč vidí za jasného dne zblízka.
        shader.setFloat("uDaylight", 1f);
        shader.setFloat("uAmbient", DayCycle.AMBIENT);
        shader.setVector3("uFogColor", WorldRenderer.SKY_R, WorldRenderer.SKY_G, WorldRenderer.SKY_B);
        shader.setFloat("uFogStart", 1000f);
        shader.setFloat("uFogEnd", 2000f);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlas.id());
        shader.setInt("uAtlas", 0);

        mesh.drawOpaque();

        // Voda: druhý průchod s mícháním a bez zápisu hloubky, jako ve světě.
        if(mesh.hasTransparent())
        {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);
            mesh.drawTransparent();
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
        mesh.delete();
        shader.delete();
        world.shutdown();
    }
}
