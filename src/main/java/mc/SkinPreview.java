package mc;

import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL33.*;

/**
 * Živý náhled postavy v texture labu - skutečný model, živě obarvovaný
 * rozmalovanou kůží.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ NÁHLED JDE CELOU CESTOU JAKO HRA, přesně ze stejného důvodu jako
 * u kostky bloku (BlockPreview). Postava se nekreslí "podobným panáčkem":
 * mesh staví tentýž PlayerModelMesh.build(), jaký kreslí postavu ve třetí
 * osobě, kreslí ho SVĚTOVÝ shader (WORLD_VERTEX/FRAGMENT) a textura je TÁŽ
 * textura skinu, do které lab maluje. Rozbalení kvádrů, ztmavení stěn podle
 * směru ve světě i UV jsou tedy počítané týmž kódem jako ve hře.
 *
 * Malovat kůži naslepo je ještě horší než malovat dlaždice: na plátně jsou
 * stěny vedle sebe jako rozstřižená krabice, kdežto na postavě se stýkají
 * v hranách - a rukáv, který na plátně navazuje, může být na modelu o pixel
 * vedle. Náhled je přesně to, co tohle ukáže.
 *
 * ⚠️ OTÁČÍ SE KAMERA, NE POSTAVA - stejně jako u kostky. Ztmavení stěn
 * (PlayerModelMesh.shade) je vázané na osy SVĚTA, takže kdyby se točila
 * postava, točily by se s ní i odstíny a náhled by ukazoval něco, co ve hře
 * nikdy neuvidíš. Postava tedy stojí čelem k +X a kamera kolem ní obíhá,
 * jako když hráč obchází druhého hráče.
 *
 * ⚠️ MESH SE STAVÍ JEDNOU. Póza je pevná (PlayerPose.REST - stojí rovně,
 * ruce podél těla) a kamera se hýbe jen uniformem uChunkOffset, takže není
 * co přestavovat: malování mění PIXELY textury, ne vrcholy ani UV. Tím je
 * tah štětcem stejně levný jako u kostky - nahraje se změněný obdélník
 * textury a model se překreslí beze změny.
 *
 * ⚠️ Vrcholy jsou stavěné s kamerou v POČÁTKU (camX/Y/Z = 0), ne relativně
 * k oku. Kdyby byly relativní k oku, musel by se mesh při každém otočení
 * kamery postavit znovu. Takhle leží postava v počátku a posun na oko
 * obstará uniform uChunkOffset - přesně jako sekce světa v BlockPreview.
 * ---------------------------------------------------------------------------
 */
public class SkinPreview {

    /** Kam se kamera dívá: doprostřed postavy (hitbox je 1,8 vysoký). */
    private static final float CENTER_Y = Player.HEIGHT / 2f;

    private static final float DISTANCE = 3.1f;
    private static final float ELEVATION = (float) Math.toRadians(8);
    private static final float SPIN = 0.6f;   // rad/s, jako kostka v BlockPreview
    private static final float FOV = 40f;

    /**
     * Kam postava kouká. PlayerModelMesh otáčí model o (90 - yaw), takže
     * při yaw = 0 stojí čelem k +X - a kamera na úhlu 0 ji vidí zepředu.
     */
    private static final float YAW = 0f;

    private final ShaderProgram shader = new ShaderProgram(Shaders.WORLD_VERTEX, Shaders.WORLD_FRAGMENT);
    private final PlayerModelMesh mesh = new PlayerModelMesh();

    /** Začíná mírně natočená, ať je vidět obličej i bok - jako kostka. */
    private float angle = 0.5f;

    private boolean built = false;

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();

    /**
     * Postaví model v klidové póze v počátku. Bez GL, takže to umí i test.
     * Plné sluneční světlo a žádné blokové - poledne, jako u kostky.
     */
    static void build(PlayerModelMesh mesh)
    {
        mesh.build(PlayerPose.REST, 0f, 0f, 0f, YAW, World.AIR, 1f, 0f, 0f, 0f, 0f);
    }

    public void update(float dt)
    {
        angle += SPIN * Math.min(dt, 0.1f);
    }

    /** Nakreslí náhled do obdélníku obrazovky (x, y = levý dolní roh). */
    public void draw(Texture skin, int x, int y, int width, int height,
                     int screenWidth, int screenHeight)
    {
        if(width <= 0 || height <= 0)
        {
            return;
        }

        if(!built)
        {
            build(mesh);
            mesh.upload();
            built = true;
        }

        if(mesh.isEmpty())
        {
            return;
        }

        float horizontal = (float) Math.cos(ELEVATION) * DISTANCE;
        float ex = (float) Math.cos(angle) * horizontal;
        float ey = CENTER_Y + (float) Math.sin(ELEVATION) * DISTANCE;
        float ez = (float) Math.sin(angle) * horizontal;

        // Stejná úmluva jako svět: oko v počátku, matice pohledu je jen
        // rotace a model se k němu posune uniformem uChunkOffset.
        view.setLookAt(0, 0, 0, -ex, CENTER_Y - ey, -ez, 0, 1, 0);
        projection.setPerspective((float) Math.toRadians(FOV), width / (float) height, 0.05f, 50f);
        projection.mul(view, viewProjection);

        glViewport(x, y, width, height);
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, y, width, height);
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);

        shader.bind();
        shader.setMatrix4("uViewProjection", viewProjection);
        shader.setVector3("uChunkOffset", -ex, -ey, -ez);

        shader.setFloat("uDaylight", 1f);
        shader.setFloat("uAmbient", DayCycle.AMBIENT);
        shader.setVector3("uFogColor", WorldRenderer.SKY_R, WorldRenderer.SKY_G, WorldRenderer.SKY_B);
        shader.setFloat("uFogStart", 1000f);
        shader.setFloat("uFogEnd", 2000f);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, skin.id());
        shader.setInt("uAtlas", 0);

        // Průhledné pixely skinu (nepokrytá místa šablony) se musí míchat,
        // jinak by z nich byly černé díry.
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        mesh.drawSkin();
        glDisable(GL_BLEND);

        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_SCISSOR_TEST);
        glViewport(0, 0, screenWidth, screenHeight);
    }

    public void delete()
    {
        mesh.delete();
        shader.delete();
    }
}
