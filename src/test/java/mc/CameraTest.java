package mc;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Overuje pohledy kamery: prvni osobu, tretí osobu zezadu a zepredu,
 * a hlavne zkraceni vzdalenosti, kdyz by kamera skoncila v terenu.
 *
 * Camera.follow() i clearDistance() pouzivaji jen World a Raycaster, takze
 * jdou otestovat bez GL. Arena je plosina vysoko nad terenem jako v PhysicsTest.
 */
public class CameraTest {

    static int failures = 0;

    static final int GROUND = 90;
    static final float STAND = GROUND + 1;
    static final float EYE_X = 8.5f, EYE_Y = STAND + Player.EYE_HEIGHT, EYE_Z = 8.5f;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    static boolean near(float a, float b, float tol) { return Math.abs(a - b) <= tol; }

    static String pos(Camera c) { return String.format("%.3f %.3f %.3f", c.x, c.y, c.z); }

    static Camera camera(Camera.View view, float yaw, float pitch) {
        Camera c = new Camera();
        c.view = view;
        c.yaw = yaw;
        c.pitch = pitch;
        return c;
    }

    public static void main(String[] args) {
        // ---------- poradi pohledu ----------
        check("F5: prvni osoba -> zezadu -> zepredu -> zpet",
                Camera.View.FIRST_PERSON.next() == Camera.View.THIRD_PERSON_BACK
                        && Camera.View.THIRD_PERSON_BACK.next() == Camera.View.THIRD_PERSON_FRONT
                        && Camera.View.THIRD_PERSON_FRONT.next() == Camera.View.FIRST_PERSON, "");

        World w = new World();
        w.loadRadius = 1;
        w.unloadRadius = 3;
        w.updateBlocking(8.5f, 8.5f);

        for (int x = -4; x <= 20; x++)
            for (int z = -4; z <= 20; z++)
                w.placeBlock(x, GROUND, z, World.STONE);

        // ---------- volny prostor ----------
        Camera first = camera(Camera.View.FIRST_PERSON, 0f, 0f);
        first.follow(w, EYE_X, EYE_Y, EYE_Z);
        check("v prvni osobe je kamera v ocich",
                first.x == EYE_X && first.y == EYE_Y && first.z == EYE_Z, pos(first));

        // Yaw 0 = pohled po +X.
        Camera back = camera(Camera.View.THIRD_PERSON_BACK, 0f, 0f);
        back.follow(w, EYE_X, EYE_Y, EYE_Z);
        check("zezadu stoji 4 bloky za hracem",
                near(back.x, EYE_X - 4f, 1e-4f) && near(back.y, EYE_Y, 1e-4f) && near(back.z, EYE_Z, 1e-4f),
                pos(back));
        check("a kouka stejnym smerem jako hrac", near(back.viewDirection()[0], 1f, 1e-5f), "");

        Camera front = camera(Camera.View.THIRD_PERSON_FRONT, 0f, 0f);
        front.follow(w, EYE_X, EYE_Y, EYE_Z);
        check("zepredu stoji 4 bloky pred hracem", near(front.x, EYE_X + 4f, 1e-4f), pos(front));
        check("a kouka zpatky na nej",
                near(front.viewDirection()[0], -1f, 1e-5f) && near(front.getLookDirection()[0], 1f, 1e-5f),
                "pohled hrace se tim nezmeni");

        // Matice pohledu musi vzit smer KAMERY: to, kam kouka, vyjde po
        // transformaci jako -Z (OpenGL kouka do zaporne osy Z).
        Vector3f toFace = front.viewMatrix(new Matrix4f())
                .transformDirection(new Vector3f(-1f, 0f, 0f));
        check("matice pohledu zepredu miri na oblicej", near(toFace.z, -1f, 1e-4f),
                String.format("%.3f %.3f %.3f", toFace.x, toFace.y, toFace.z));

        // Sklon pohledu: zezadu nad hracem, kdyz kouka dolu.
        Camera down = camera(Camera.View.THIRD_PERSON_BACK, 0f, -45f);
        down.follow(w, EYE_X, EYE_Y, EYE_Z);
        float d45 = 4f * (float) Math.sqrt(0.5);
        check("pri pohledu dolu je kamera za hracem a nad nim",
                near(down.x, EYE_X - d45, 1e-3f) && near(down.y, EYE_Y + d45, 1e-3f), pos(down));

        // ---------- zkraceni o zed ----------
        // Zed za hracem: blok x = 6, tedy stena v rovine x = 7, 1,5 bloku za ocima.
        for (int z = -4; z <= 20; z++)
            for (int y = GROUND + 1; y <= GROUND + 4; y++)
                w.placeBlock(6, y, z, World.STONE);

        back.follow(w, EYE_X, EYE_Y, EYE_Z);
        check("zed za hracem kameru zastavi pred sebou",
                back.x > 7f && !w.isSolid((int) Math.floor(back.x), (int) Math.floor(back.y), (int) Math.floor(back.z)),
                pos(back));
        check("a necha mezi kamerou a stenou polomer kamery",
                near(back.x, 7f + Camera.CAMERA_RADIUS, 1e-3f), pos(back));

        // Pohled nahoru: kamera by zezadu zajela pod podlahu.
        Camera under = camera(Camera.View.THIRD_PERSON_BACK, 0f, 60f);
        under.follow(w, EYE_X, EYE_Y, EYE_Z);
        float underDistance = (float) Math.sqrt(Math.pow(under.x - EYE_X, 2) + Math.pow(under.y - EYE_Y, 2));
        check("pri pohledu nahoru kamera nezajede do podlahy",
                under.y >= STAND + Camera.CAMERA_RADIUS - 1e-3f && underDistance < 4f,
                pos(under) + String.format(", vzdalenost %.2f", underDistance));

        // Zepredu o zed pred hracem: blok x = 11, stena v rovine x = 11.
        for (int z = -4; z <= 20; z++)
            for (int y = GROUND + 1; y <= GROUND + 4; y++)
                w.placeBlock(11, y, z, World.STONE);

        front.follow(w, EYE_X, EYE_Y, EYE_Z);
        check("zepredu se kamera zastavi o zed pred hracem",
                near(front.x, 11f - Camera.CAMERA_RADIUS, 1e-3f), pos(front));

        // ---------- presna vzdalenost ----------
        // Jeden blok v ceste: vsech osm paprsku do nej vstoupi stenou z = 11,
        // nejkratsi je ten posunuty o polomer blize k ni.
        w.placeBlock(8, GROUND + 3, 11, World.STONE);
        float exact = Camera.clearDistance(w, 8.5f, GROUND + 3.5f, 8.5f, 0f, 0f, 1f, 4f);
        check("vzdalenost se meri k rovine zasazene steny", near(exact, 2.5f - Camera.CAMERA_RADIUS, 1e-4f),
                String.format("%.4f", exact));

        float open = Camera.clearDistance(w, 8.5f, GROUND + 3.5f, 8.5f, 0f, 1f, 0f, 4f);
        check("volnym prostorem projde cela vzdalenost", open == 4f, "" + open);

        // ---------- oci v bloku ----------
        Camera buried = camera(Camera.View.THIRD_PERSON_BACK, 0f, 0f);
        buried.follow(w, 8.5f, GROUND + 0.5f, 8.5f);
        check("s ocima v bloku (noclip) zustane kamera v ocich",
                near(buried.x, 8.5f, 1e-5f) && near(buried.y, GROUND + 0.5f, 1e-5f), pos(buried));

        w.shutdown();

        mouse();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ---------- mys: citlivost, obracena osa, orez pitch ----------
    static void mouse() {
        Camera c = camera(Camera.View.FIRST_PERSON, 0f, 0f);
        c.mouseSensitivity = 0.1f;

        c.processMouse(10, 0);
        check("mys doprava otoci yaw o dx * citlivost", near(c.yaw, 1f, 1e-5f), "" + c.yaw);

        c.processMouse(0, 20);
        check("dy posune pitch o dy * citlivost", near(c.pitch, 2f, 1e-5f), "" + c.pitch);

        c.invertMouseY = true;
        c.processMouse(0, 20);
        check("obracena osa Y: tentyz pohyb vrati pitch zpet", near(c.pitch, 0f, 1e-5f), "" + c.pitch);
        c.invertMouseY = false;

        // Oreze se na +-MAX_PITCH: presne na 90 by smer pohledu byl rovnobezny
        // s vektorem "nahoru" a setLookAt by vratil NaN matici (cerny obraz).
        c.processMouse(0, 1e6);
        check("pitch se orizne na MAX_PITCH", c.pitch == Camera.MAX_PITCH, "" + c.pitch);
        check("MAX_PITCH je pod 90", Camera.MAX_PITCH < 90f, "" + Camera.MAX_PITCH);
        check("pohledova matice na horni mezi je konecna", finite(c.viewMatrix(new Matrix4f())), "");

        c.processMouse(0, -1e6);
        check("a dole na -MAX_PITCH", c.pitch == -Camera.MAX_PITCH, "" + c.pitch);
        check("pohledova matice na dolni mezi je konecna", finite(c.viewMatrix(new Matrix4f())), "");

        c.invertMouseY = true;
        c.processMouse(0, -1e6);
        check("orez plati i s obracenou osou", c.pitch == Camera.MAX_PITCH, "" + c.pitch);

        // Yaw se neorezava - otoceni dokola je v poradku.
        float before = c.yaw;
        c.processMouse(3600 / c.mouseSensitivity, 0);
        check("yaw se neorezava (deset otacek)", near(c.yaw - before, 3600f, 0.01f), "" + (c.yaw - before));
    }

    static boolean finite(Matrix4f m) {
        float[] v = m.get(new float[16]);
        for (float f : v) if (!Float.isFinite(f)) return false;
        return true;
    }
}
