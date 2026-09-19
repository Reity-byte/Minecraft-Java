package mc;

import org.joml.Matrix4f;

/**
 * Simple free-fly camera: position + yaw/pitch, no gravity/collision yet.
 * That's your next step once this base feels comfortable.
 */
public class Camera {

    /**
     * Odkud se kouká. F5 je cyklicky přepíná v tomhle pořadí, jako v Minecraftu.
     *
     * ⚠️ yaw a pitch jsou pořád POHLED HRÁČE - řídí pohyb, míření i hlavu
     * modelu. Pohled zepředu jen otočí to, kam se dívá KAMERA (viewDirection),
     * ne to, kam se dívá hráč.
     */
    public enum View {
        FIRST_PERSON, THIRD_PERSON_BACK, THIRD_PERSON_FRONT;

        public View next()
        {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Jak daleko od očí stojí kamera ve třetí osobě. Minecraft: 4 bloky. */
    public static final float THIRD_PERSON_DISTANCE = 4f;

    /**
     * Poloměr "hlavy" kamery. Blízká ořezová rovina leží 0,05 před kamerou,
     * takže kamera přitisknutá těsně ke stěně by do ní nahlédla. Minecraft
     * proto nevrhá jeden paprsek, ale osm posunutých o ±0,1 v každé ose.
     */
    static final float CAMERA_RADIUS = 0.1f;

    public float x = 8, y = 80, z = 8; // start above the terrain (ground is around y=64)
    public float yaw = -90f;   // facing -Z initially
    public float pitch = 0f;

    public View view = View.FIRST_PERSON;

    /** Stupně otočení na pixel pohybu myši při citlivosti 100 %. */
    public static final float DEFAULT_SENSITIVITY = 0.12f;

    /** Nastavuje se z Options (citlivost a obrácená osa Y). */
    public float mouseSensitivity = DEFAULT_SENSITIVITY;
    public boolean invertMouseY = false;

    public void processMouse(double dx, double dy) {
        yaw += (float) (dx * mouseSensitivity);
        pitch += (float) ((invertMouseY ? -dy : dy) * mouseSensitivity);

        if (pitch > 89f) pitch = 89f;
        if (pitch < -89f) pitch = -89f;
    }

    public void setPosition(float x, float y, float z)
    {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public float[] getForwardMoveTarget(float amount)
    {
        float[] f = forwardVector();
        float newX = x + f[0] * amount;
        float newZ = z + f[2] * amount;
        return new float[]{newX, y, newZ};
    }

    public float[]getRightMoveTarget(float amount)
    {
        float[] r = rightVector();
        float newX = x + r[0] * amount;
        float newZ = z + r[2] * amount;

        return new float[]{newX, y, newZ};
    }

    public float[]getUpMoveTarget(float amount)
    {
        float newY = y + amount;
        return new float[]{x, newY, z};
    }

    private float[] forwardVector() {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        float fx = (float) (Math.cos(yawRad) * Math.cos(pitchRad));
        float fy = (float) (Math.sin(pitchRad));
        float fz = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        return new float[]{fx, fy, fz};
    }

    /** Kam se dívá HRÁČ. Míří se tímhle, ne směrem kamery. */
    public float[] getLookDirection() {
        return forwardVector();
    }

    /** Kam se dívá KAMERA. Zepředu je to proti pohledu hráče - na jeho obličej. */
    public float[] viewDirection()
    {
        float[] f = forwardVector();
        return view == View.THIRD_PERSON_FRONT ? new float[]{-f[0], -f[1], -f[2]} : f;
    }

    // ------------------------------------------------------------------
    // třetí osoba
    // ------------------------------------------------------------------

    /**
     * Postaví kameru podle pohledu: v první osobě do očí, ve třetí o
     * THIRD_PERSON_DISTANCE za hráče (nebo před něj) po přímce pohledu.
     *
     * ⚠️ Vzdálenost se zkrátí, když by kamera skončila v terénu - jinak by
     * u zdi koukala zevnitř bloku. Poloha se proto počítá znovu každý frame
     * a v uzavřené chodbě kamera sama přijede blíž k hráči.
     */
    public void follow(World world, float eyeX, float eyeY, float eyeZ)
    {
        if(view == View.FIRST_PERSON)
        {
            setPosition(eyeX, eyeY, eyeZ);
            return;
        }

        // Zezadu couvá proti pohledu, zepředu jde po pohledu dopředu.
        float[] f = forwardVector();
        float sign = view == View.THIRD_PERSON_BACK ? -1f : 1f;
        float dx = f[0] * sign, dy = f[1] * sign, dz = f[2] * sign;

        float distance = clearDistance(world, eyeX, eyeY, eyeZ, dx, dy, dz, THIRD_PERSON_DISTANCE);
        setPosition(eyeX + dx * distance, eyeY + dy * distance, eyeZ + dz * distance);
    }

    /**
     * Jak daleko od (x, y, z) ve směru d může kamera stát, nejvýš wanted.
     *
     * Vrhá se osm paprsků z rohů krychličky ±CAMERA_RADIUS kolem startu,
     * všechny rovnoběžně s d, a bere se nejkratší zásah. Paprsky jsou ten
     * samý Raycaster, kterým se míří na bloky - takže kamera se zarazí
     * o cokoliv, co jde zaměřit (pochodeň i plot beráno jako plná buňka),
     * a vodou projede.
     */
    public static float clearDistance(World world, float x, float y, float z,
                                      float dx, float dy, float dz, float wanted)
    {
        float distance = wanted;

        for(int corner = 0; corner < 8; corner++)
        {
            float sx = x + ((corner & 1) == 0 ? -CAMERA_RADIUS : CAMERA_RADIUS);
            float sy = y + ((corner & 2) == 0 ? -CAMERA_RADIUS : CAMERA_RADIUS);
            float sz = z + ((corner & 4) == 0 ? -CAMERA_RADIUS : CAMERA_RADIUS);

            Raycaster.RaycastHit hit = Raycaster.cast(world, sx, sy, sz, dx, dy, dz, wanted);

            if(hit != null)
            {
                distance = Math.min(distance, hitDistance(hit, sx, sy, sz, dx, dy, dz));
            }
        }

        return Math.max(0f, distance);
    }

    /**
     * Vzdálenost podél paprsku, ve které vstoupil do zasaženého bloku.
     *
     * Raycaster vrací blok a normálu stěny, kterou se do něj vstoupilo - to
     * stačí: stěna s normálou +X leží v rovině x = blok + 1, s normálou -X
     * v rovině x = blok, a paprsek ji protne v t = (rovina - start) / směr.
     * Nulová normála znamená, že paprsek začal uvnitř bloku.
     */
    static float hitDistance(Raycaster.RaycastHit hit, float sx, float sy, float sz,
                             float dx, float dy, float dz)
    {
        if(hit.nx() != 0)
        {
            return ((hit.nx() > 0 ? hit.x() + 1 : hit.x()) - sx) / dx;
        }
        if(hit.ny() != 0)
        {
            return ((hit.ny() > 0 ? hit.y() + 1 : hit.y()) - sy) / dy;
        }
        if(hit.nz() != 0)
        {
            return ((hit.nz() > 0 ? hit.z() + 1 : hit.z()) - sz) / dz;
        }

        return 0f;
    }

    private float[] rightVector() {
        float[] f = forwardVector();
        // right = forward x worldUp
        float ux = 0, uy = 1, uz = 0;
        float rx = f[1] * uz - f[2] * uy;
        float ry = f[2] * ux - f[0] * uz;
        float rz = f[0] * uy - f[1] * ux;
        float len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        return new float[]{rx / len, ry / len, rz / len};
    }

    public void moveForward(float amount) {
        float[] f = getForwardMoveTarget(amount);
        setPosition(f[0], f[1], f[2]);
    }

    public void moveRight(float amount) {
        float[] r = getRightMoveTarget(amount);
        setPosition(r[0], r[1], r[2]);
    }

    public void moveUp(float amount) {
        float[] u = getUpMoveTarget(amount);
        setPosition(u[0], u[1], u[2]);
    }

    /**
     * View matice pro shader. Nahradila původní applyView(), která tlačila
     * matici rovnou do fixed-function GL - to v core profilu neexistuje.
     *
     * Všimni si, že oko je v POČÁTKU, ne na pozici kamery. Renderer posílá
     * geometrii relativně ke kameře (viz komentář v Shaders), takže tahle
     * matice obsahuje jen rotaci, žádný posun. Kdyby tu byla i translace,
     * započítala by se dvakrát.
     */
    public Matrix4f viewMatrix(Matrix4f dest)
    {
        float[] f = viewDirection();
        return dest.setLookAt(0, 0, 0,
                f[0], f[1], f[2],
                0, 1, 0);
    }
}
