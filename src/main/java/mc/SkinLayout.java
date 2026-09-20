package mc;

/**
 * Rozložení kůže postavy 64x64: která část obrázku patří které stěně
 * kterého dílu těla. Nesahá na GL.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ ČÍSLA SE NEOPISUJÍ, POČÍTAJÍ SE ZE STEJNÉHO VZORCE JAKO UV MODELU.
 * Kvádr dílu je ve skinu rozbalený takhle (šablona Minecraftu, ModelBox):
 *
 *          [ vršek ][ spodek ]
 *   [ pravá ][ před ][ levá  ][ zadní ]
 *
 * a přesně tenhle vzorec počítá PlayerModelMesh.unfold(), když skládá UV
 * vrcholů. Kdyby si tahle třída držela vlastní tabulku souřadnic, daly by
 * se obě rozejít a malovalo by se vedle - obličej by přistál na týlu.
 * Rozměry dílů se proto berou z PlayerModelMesh.PARTS a rozbalení je
 * dopočítané stejnými výrazy; PlayerModelTest to hlídá porovnáním
 * s UV, která unfold() skutečně vydá.
 *
 * ⚠️ ŘÁDEK 0 JE NAHOŘE, na rozdíl od atlasu bloků. Pole skinu jde do GL
 * v pořadí obrázku, protože UV modelu jsou rovnou souřadnice šablony dělené
 * 64 - viz Textures.playerSkin(). Plátno v labu má ale řádek 0 dole jako
 * všude jinde, takže se v index() překlápí.
 * ---------------------------------------------------------------------------
 */
public final class SkinLayout {

    private SkinLayout() {}

    public static final int SIZE = PlayerModelMesh.SKIN_SIZE;

    /** Stěny v pořadí, v jakém je vydává PlayerModelMesh.unfold(). */
    public static final int TOP = 0, BOTTOM = 1, LEFT = 2, RIGHT = 3, FRONT = 4, BACK = 5;
    public static final int FACES_PER_PART = 6;

    /** Kolik dílů má model - hlava, trup, dvě ruce, dvě nohy. */
    public static final int PARTS = PlayerModelMesh.PARTS.length;

    /** Kolik je dohromady malovatelných stěn. */
    public static final int FACE_COUNT = PARTS * FACES_PER_PART;

    static final String[] PART_NAMES = {"Head", "Body", "Right arm", "Left arm",
            "Right leg", "Left leg"};

    static final String[] FACE_NAMES = {"top", "bottom", "left", "right", "front", "back"};

    /** Obdélník ve skinu: u, v od LEVÉHO HORNÍHO rohu obrázku. */
    public record Rect(int u, int v, int width, int height) {

        public boolean contains(int x, int y)
        {
            return x >= u && y >= v && x < u + width && y < v + height;
        }
    }

    /** Plochý index stěny: díl krát šest plus stěna. */
    public static int face(int part, int side)
    {
        return part * FACES_PER_PART + side;
    }

    public static int partOf(int face)
    {
        return face / FACES_PER_PART;
    }

    public static int sideOf(int face)
    {
        return face % FACES_PER_PART;
    }

    /** "Head front", "Right arm side" - do rozhraní labu (anglicky, font je ASCII). */
    public static String name(int face)
    {
        return PART_NAMES[partOf(face)] + " " + FACE_NAMES[sideOf(face)];
    }

    /**
     * Kde ta stěna ve skinu leží. Vzorce jsou tytéž jako v unfold():
     * w, h, d jsou rozměry kvádru a u, v levý horní roh jeho rozbalení.
     */
    public static Rect rect(int face)
    {
        PlayerModelMesh.Part p = PlayerModelMesh.PARTS[partOf(face)];

        int w = (int) (p.x1() - p.x0());
        int h = (int) (p.y1() - p.y0());
        int d = (int) (p.z1() - p.z0());
        int u = p.skinU(), v = p.skinV();

        return switch(sideOf(face))
        {
            case TOP    -> new Rect(u + d, v, w, d);
            case BOTTOM -> new Rect(u + d + w, v, w, d);
            case LEFT   -> new Rect(u + d + w, v + d, d, h);
            case RIGHT  -> new Rect(u, v + d, d, h);
            case FRONT  -> new Rect(u + d, v + d, w, h);
            default     -> new Rect(u + 2 * d + w, v + d, w, h);
        };
    }

    public static int width(int face)
    {
        return rect(face).width();
    }

    public static int height(int face)
    {
        return rect(face).height();
    }

    /**
     * Index pixelu (x, y) stěny v poli skinu.
     *
     * ⚠️ y = 0 je DOLNÍ řádek plátna, ale ve skinu roste v dolů - proto
     * to překlopení. Plátno tak má stěnu tak, jak ji uvidíš na postavě,
     * a ne vzhůru nohama.
     */
    public static int index(int face, int x, int y)
    {
        Rect r = rect(face);
        return (r.v() + r.height() - 1 - y) * SIZE + r.u() + x;
    }

    /**
     * Stěna, do které patří pixel skinu (u, v od levého horního rohu),
     * nebo -1. Nepokrytá místa šablony (druhá vrstva - klobouk, bunda)
     * žádnou stěnu nemají; model je nekreslí, takže by malování naslepo
     * bylo přesně to, čemu lab zabraňuje.
     */
    public static int faceAt(int u, int v)
    {
        for(int face = 0; face < FACE_COUNT; face++)
        {
            if(rect(face).contains(u, v))
            {
                return face;
            }
        }

        return -1;
    }
}
