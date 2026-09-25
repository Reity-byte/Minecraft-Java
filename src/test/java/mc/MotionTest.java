package mc;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;

/**
 * Houpani pohledu pri chuzi (ViewBobbing), setrvacnost ruky (HandSway)
 * a jejich zapojeni: sila houpani v PlayerAnimation, kamera jen v prvni
 * osobe, ruka v HeldItemRenderer a prepinac v Options.
 */
public class MotionTest {

    static int failures = 0;
    static final float DT = 1f / 60f;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        bobbing();
        animationBob();
        camera();
        handSway();
        heldItem();
        options();
        fov();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    /** Kam houpani posune bod pred okem (0, 0, -1). */
    static Vector3f moved(float phase, float amount) {
        return ViewBobbing.apply(new Matrix4f(), phase, amount).transformPosition(new Vector3f(0, 0, -1));
    }

    static void bobbing() {
        check("bez rozmachu je houpani presne identita",
                ViewBobbing.apply(new Matrix4f(), 1.3f, 0f).equals(new Matrix4f()), "");

        // Do strany sin(faze): v 1/4 cyklu na jednu stranu, ve 3/4 na druhou.
        float right = moved((float) (Math.PI / 2), 1f).x;
        float left = moved((float) (3 * Math.PI / 2), 1f).x;
        check("do strany se houpe na obe strany", right > 0.02f && left < -0.02f, right + " / " + left);

        // Pokles -|cos|: nejniz pri fazi 0 a pi (nohy od sebe), ani jednou nahoru.
        float lowest = moved(0f, 1f).y;
        boolean neverUp = true;
        for (int i = 0; i <= 64; i++) neverUp &= moved((float) (i * Math.PI / 32), 1f).y <= 0.12f;
        check("pohled klesa, kdyz jsou nohy od sebe", lowest < -0.05f, "" + lowest);
        check("houpani je male - bod pred okem se posune o par centimetru", neverUp
                && moved(0f, 1f).distance(0, 0, -1) < 0.2f, "");

        check("polovicni rozmach = mensi houpani",
                moved(0f, 0.5f).distance(0, 0, -1) < moved(0f, 1f).distance(0, 0, -1), "");
    }

    static void animationBob() {
        PlayerAnimation a = new PlayerAnimation();
        for (int i = 0; i < 120; i++) a.update(DT, 4.3f * DT, true);
        float walking = a.bob();
        check("chuze po zemi rozhoupe pohled skoro naplno", walking > 0.8f && walking <= 1f, "" + walking);

        PlayerAnimation air = new PlayerAnimation();
        for (int i = 0; i < 120; i++) air.update(DT, 4.3f * DT, false);
        check("ve vzduchu (skok, let, voda) se pohled nehoupe, nohy ano",
                air.bob() == 0f && air.amount() > 0.8f, air.bob() + " / " + air.amount());

        for (int i = 0; i < 60; i++) a.update(DT, 0f, true);
        check("po zastaveni houpani dozni", a.bob() < 0.01f, "" + a.bob());

        PlayerAnimation old = new PlayerAnimation();
        for (int i = 0; i < 60; i++) old.update(DT, 4.3f * DT);
        check("update bez onGround pocita se zemi (stare volani)", old.bob() > 0.5f, "" + old.bob());
    }

    static void camera() {
        Camera c = new Camera();
        c.yaw = 30f;
        c.pitch = -10f;
        Matrix4f still = c.viewMatrix(new Matrix4f());
        Matrix4f plain = new Matrix4f().setLookAt(0, 0, 0,
                c.getLookDirection()[0], c.getLookDirection()[1], c.getLookDirection()[2], 0, 1, 0);
        check("bez houpani je pohled cisty lookAt jako driv", still.equals(plain, 1e-6f), "");

        c.bobPhase = 0.7f;
        c.bobAmount = 1f;
        check("v prvni osobe houpani pohnulo pohledem",
                !c.viewMatrix(new Matrix4f()).equals(still, 1e-4f), "");

        c.view = Camera.View.THIRD_PERSON_BACK;
        Matrix4f third = new Matrix4f().setLookAt(0, 0, 0,
                c.viewDirection()[0], c.viewDirection()[1], c.viewDirection()[2], 0, 1, 0);
        check("ve treti osobe se kamera nehoupe", c.viewMatrix(new Matrix4f()).equals(third, 1e-6f), "");
    }

    static void handSway() {
        HandSway s = new HandSway();
        s.update(DT, 100f, 0f);
        check("na zacatku ruka rovnou sedi s pohledem", s.tiltYaw(100f) == 0f && s.tiltPitch(0f) == 0f, "");

        // Otacka doprava 180 stupnu za sekundu.
        float yaw = 100f;
        for (int i = 0; i < 10; i++) {
            yaw += 3f;
            s.update(DT, yaw, 0f);
        }
        float lag = s.tiltYaw(yaw);
        check("otocka doprava nechava ruku vlevo (kladne natoceni)", lag > 0.3f, "" + lag);

        for (int i = 0; i < 60; i++) s.update(DT, yaw, 0f);
        check("po zastaveni ruka pohled dozene", Math.abs(s.tiltYaw(yaw)) < 0.01f, "" + s.tiltYaw(yaw));

        // Pres sev 359 -> 0 se nesmi protocit dokola.
        HandSway seam = new HandSway();
        seam.update(DT, 358f, 0f);
        seam.update(DT, Camera.wrapYaw(362f), 0f);
        float t = seam.tiltYaw(Camera.wrapYaw(362f));
        check("otocka pres 360 je male natoceni, ne pul otacky", t > 0f && t < 1f, "" + t);

        HandSway up = new HandSway();
        up.update(DT, 0f, 0f);
        up.update(DT, 0f, 10f);
        check("pohled nahoru stahne ruku dolu", up.tiltPitch(10f) < 0f, "" + up.tiltPitch(10f));

        HandSway fast = new HandSway();
        fast.update(DT, 0f, 0f);
        fast.update(DT, 80f, 0f);
        check("natoceni je omezene", fast.tiltYaw(80f) <= HandSway.MAX_TILT, "" + fast.tiltYaw(80f));

        fast.update(DT, 250f, 0f);
        check("velky skok (nacteni sveta) ruka preskoci", fast.tiltYaw(250f) == 0f, "" + fast.tiltYaw(250f));

        check("rozdil yaw nejkratsi cestou",
                HandSway.yawDelta(1f, 359f) == 2f && HandSway.yawDelta(359f, 1f) == -2f, "");
    }

    static void heldItem() {
        Matrix4f still = HeldItemRenderer.matrix(new Matrix4f(), World.STONE, 1280, 720, 70f, 0f, 0f);
        Matrix4f withStill = HeldItemRenderer.matrix(new Matrix4f(), World.STONE, 1280, 720, 70f, 0f, 0f,
                HeldItemRenderer.Motion.STILL);
        check("klidny pohyb = ruka presne jako driv", still.equals(withStill, 1e-6f), "");

        for (byte block : new byte[]{World.STONE, World.AIR}) {
            Vector3f rest = HeldItemRenderer.matrix(new Matrix4f(), block, 1280, 720, 70f, 0f, 0f)
                    .transformProject(new Vector3f(0.5f, 0.5f, 0.5f));
            Vector3f bobbed = HeldItemRenderer.matrix(new Matrix4f(), block, 1280, 720, 70f, 0f, 0f,
                    new HeldItemRenderer.Motion(0f, 1f, 0f, 0f)).transformProject(new Vector3f(0.5f, 0.5f, 0.5f));
            Vector3f turned = HeldItemRenderer.matrix(new Matrix4f(), block, 1280, 720, 70f, 0f, 0f,
                    new HeldItemRenderer.Motion(0f, 0f, 5f, 0f)).transformProject(new Vector3f(0.5f, 0.5f, 0.5f));
            String what = block == World.AIR ? "hola ruka" : "blok";
            check(what + ": pri chuzi klesne", bobbed.y < rest.y - 0.005f, rest.y + " -> " + bobbed.y);
            check(what + ": setrvacnost doprava ji posune doleva", turned.x < rest.x - 0.005f, rest.x + " -> " + turned.x);
        }
    }

    static void fov() {
        check("sprint roztahne o 15 %, let o 10 %, obojí naráz",
                FovEffect.target(true, 5.6f, false) == FovEffect.SPRINT_BOOST
                        && FovEffect.target(false, 11f, true) == FovEffect.FLYING_BOOST
                        && FovEffect.target(true, 30f, true) == FovEffect.SPRINT_BOOST * FovEffect.FLYING_BOOST, "");
        check("sprint do zdi (drzi klavesu, stoji) nic nedela", FovEffect.target(true, 0f, false) == 1f, "");
        check("obycejna chuze nic nedela", FovEffect.target(false, 4.3f, false) == 1f, "");

        FovEffect f = new FovEffect();
        f.update(DT, true, 5.6f, false, true);
        float first = f.multiplier();
        for (int i = 0; i < 60; i++) f.update(DT, true, 5.6f, false, true);
        check("rozjede se plynule a za sekundu je naplno",
                first > 1f && first < 1.05f && Math.abs(f.multiplier() - FovEffect.SPRINT_BOOST) < 1e-3f,
                first + " -> " + f.multiplier());

        FovEffect slow = new FovEffect();
        for (int i = 0; i < 30; i++) slow.update(1f / 30f, true, 5.6f, false, true);
        check("stejne pri 30 i 60 FPS", Math.abs(slow.multiplier() - f.multiplier()) < 1e-3f, "");

        for (int i = 0; i < 60; i++) f.update(DT, true, 5.6f, false, false);
        check("vypnute v Options: zpet na 1", Math.abs(f.multiplier() - 1f) < 1e-3f, "" + f.multiplier());

        f.update(DT, false, 11f, true, true);
        f.reset();
        check("reset (novy svet) je hned 1", f.multiplier() == 1f, "");

        Options o = Options.defaults();
        o.setFovEffects(false);
        check("FOV efekty: vychozi zapnuto, vypnuti se ulozi",
                Options.defaults().fovEffects() && !Options.fromJson(o.toJson(), new ArrayList<>()).fovEffects(), "");
        check("popisek FOV Effects",
                new OptionsScreen(o, null).caption(OptionsScreen.Item.FOV_EFFECTS).equals("FOV Effects: OFF"), "");
    }

    static void options() {
        Options o = Options.defaults();
        check("houpani je ve vychozim nastaveni zapnute", o.viewBobbing(), "");

        o.setViewBobbing(false);
        Options back = Options.fromJson(o.toJson(), new ArrayList<>());
        check("vypnute houpani se ulozi a nacte", !back.viewBobbing(), "");

        ArrayList<String> problems = new ArrayList<>();
        Options older = Options.fromJson("{\"format\": 1, \"fov\": 70}", problems);
        check("starsi soubor bez viewBobbing: zapnuto a mlcky", older.viewBobbing() && problems.isEmpty(),
                problems.toString());

        OptionsScreen screen = new OptionsScreen(o, null);
        check("popisek prepinace", screen.caption(OptionsScreen.Item.VIEW_BOBBING).equals("View Bobbing: OFF"),
                screen.caption(OptionsScreen.Item.VIEW_BOBBING));
    }
}
