package mc;


/** Overuje DDA raycast: normaly ve vsech 6 smerech + pozici pro pokladani. */
public class RayTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    /** Strelime paprsek a overime, ze trefil ocekavany blok s ocekavanou normalou. */
    static void ray(World w, String name,
                    float sx, float sy, float sz, float dx, float dy, float dz,
                    int ex, int ey, int ez, int nx, int ny, int nz) {
        Raycaster.RaycastHit h = Raycaster.cast(w, sx, sy, sz, dx, dy, dz, 16f);
        boolean ok = h != null
                && h.x() == ex && h.y() == ey && h.z() == ez
                && h.nx() == nx && h.ny() == ny && h.nz() == nz;
        check(name, ok, String.valueOf(h));

        if (h != null) {
            // bunka pro pokladani musi byt sousedni a musi byt vzduch
            check(name + " -> place je vzduch",
                    !w.isSolid(h.placeX(), h.placeY(), h.placeZ()),
                    h.placeX() + "," + h.placeY() + "," + h.placeZ());
        }
    }

    public static void main(String[] args) {
        World w = new World();
        w.updateBlocking(8.5f, 8.5f);

        // Cistou arenu si postavime sami vysoko nad terenem: jeden osamocmeny
        // blok, na ktery se da strilet ze vsech sesti stran.
        final int BX = 8, BY = 100, BZ = 8;
        check("misto pro arenu je prazdne", !w.isSolid(BX, BY, BZ), "");
        check("blok se podarilo polozit", w.placeBlock(BX, BY, BZ, World.STONE), "");

        float cx = BX + 0.5f, cy = BY + 0.5f, cz = BZ + 0.5f;

        ray(w, "shora dolu  -> normala +Y", cx, cy + 4, cz, 0, -1, 0, BX, BY, BZ, 0, 1, 0);
        ray(w, "zezdola nahoru -> normala -Y", cx, cy - 4, cz, 0, 1, 0, BX, BY, BZ, 0, -1, 0);
        ray(w, "z -X doprava -> normala -X", cx - 4, cy, cz, 1, 0, 0, BX, BY, BZ, -1, 0, 0);
        ray(w, "z +X doleva  -> normala +X", cx + 4, cy, cz, -1, 0, 0, BX, BY, BZ, 1, 0, 0);
        ray(w, "z -Z dopredu -> normala -Z", cx, cy, cz - 4, 0, 0, 1, BX, BY, BZ, 0, 0, -1);
        ray(w, "z +Z dozadu  -> normala +Z", cx, cy, cz + 4, 0, 0, -1, BX, BY, BZ, 0, 0, 1);

        // ---------- normala ma vzdy prave jednu nenulovou slozku ----------
        Raycaster.RaycastHit h = Raycaster.cast(w, cx, cy + 4, cz, 0, -1, 0, 16f);
        int nonZero = (h.nx() != 0 ? 1 : 0) + (h.ny() != 0 ? 1 : 0) + (h.nz() != 0 ? 1 : 0);
        check("normala je jednotkova v jedne ose", nonZero == 1, "nenulovych: " + nonZero);

        // ---------- dosah ----------
        check("mimo dosah nic netrefi",
                Raycaster.cast(w, cx, cy + 20, cz, 0, -1, 0, 5f) == null, "");
        check("v dosahu trefi",
                Raycaster.cast(w, cx, cy + 3, cz, 0, -1, 0, 5f) != null, "");

        // ---------- start uvnitr bloku ----------
        Raycaster.RaycastHit inside = Raycaster.cast(w, cx, cy, cz, 1, 0, 0, 8f);
        check("start uvnitr bloku vrati ten blok",
                inside != null && inside.x() == BX && inside.y() == BY && inside.z() == BZ,
                String.valueOf(inside));
        check("start uvnitr bloku ma nulovou normalu -> pokladani se odmitne",
                inside != null && inside.nx() == 0 && inside.ny() == 0 && inside.nz() == 0
                        && !w.placeBlock(inside.placeX(), inside.placeY(), inside.placeZ(), World.STONE),
                "");

        // ---------- do prazdna ----------
        check("paprsek do nebe nic netrefi",
                Raycaster.cast(w, cx, cy + 4, cz, 0, 1, 0, 16f) == null, "");

        // ---------- sikmy paprsek na teren ----------
        // pozor: NE z (8.5,100,8.5) - tam stoji testovaci blok areny a paprsek
        // by zacinal uvnitr nej (a spravne by vratil nulovou normalu)
        Raycaster.RaycastHit diag = Raycaster.cast(w, 20.5f, 100f, 20.5f, 0.3f, -0.9f, 0.32f, 60f);
        check("sikmy paprsek dolu trefi teren", diag != null, String.valueOf(diag));
        if (diag != null) {
            check("sikmy paprsek: zasazeny blok je pevny",
                    w.isSolid(diag.x(), diag.y(), diag.z()), "");
            check("sikmy paprsek: place bunka je vzduch",
                    !w.isSolid(diag.placeX(), diag.placeY(), diag.placeZ()), "");
        }

        // ---------- zaporne souradnice ----------
        World w2 = new World();
        w2.updateBlocking(-100f, -100f);
        w2.placeBlock(-100, 100, -100, World.STONE);
        ray(w2, "zaporne souradnice: shora dolu",
                -99.5f, 104.5f, -99.5f, 0, -1, 0, -100, 100, -100, 0, 1, 0);

        // ---------- start na cele souradnici, nulova slozka smeru (WLD-7) ----------
        // Driv (start - blok) / |0| = 0/0 = NaN v ose Y nebo Z zablokovalo
        // vyber os a paprsek skoncil bez zasahu. Kamen 3 bloky v +X od oka.
        World w3 = new World();
        w3.updateBlocking(8.5f, 8.5f);
        w3.placeBlock(11, 100, 8, World.STONE);
        ray(w3, "start na cele Z (8,0), smer +X", 8.5f, 100.5f, 8.0f, 1, 0, 0, 11, 100, 8, -1, 0, 0);
        ray(w3, "start na cele Y (100,0), smer +X", 8.5f, 100.0f, 8.5f, 1, 0, 0, 11, 100, 8, -1, 0, 0);
        ray(w3, "start na cele Y i Z, smer +X", 8.5f, 100.0f, 8.0f, 1, 0, 0, 11, 100, 8, -1, 0, 0);
        ray(w3, "zaporna nula ve smeru (-0,0)", 8.5f, 100.5f, 8.0f, 1, -0f, -0f, 11, 100, 8, -1, 0, 0);
        check("hranice: nulovy smer = nekonecno, ne NaN",
                Raycaster.firstBoundary(8f, 8, 0f) == Float.POSITIVE_INFINITY
                        && Raycaster.firstBoundary(8f, 8, -0f) == Float.POSITIVE_INFINITY, "");
        check("nulovy vektor smeru nic netrefi a neuvizne",
                Raycaster.cast(w3, 8.5f, 100.5f, 8.5f, 0, 0, 0, 5f) == null, "");

        // ---------- dosah se neprekroci (WLD-11) ----------
        // Stena bloku 11 je 2,5 od oka. Driv se testovala i bunka, do ktere
        // se vstoupilo ZA dosahem, takze trefil i paprsek s dosahem 2.
        check("blok za dosahem se netrefi (stena 2,5, dosah 2)",
                Raycaster.cast(w3, 8.5f, 100.5f, 8.5f, 1, 0, 0, 2f) == null, "");
        check("na hranici dosahu se trefi (dosah 2,5)",
                Raycaster.cast(w3, 8.5f, 100.5f, 8.5f, 1, 0, 0, 2.5f) != null, "");

        // Zastavit workery - jinak by kazdy svet (~17 MB) zil v jedne JVM az do konce AllTests (WLD-13).
        w.shutdown();
        w2.shutdown();
        w3.shutdown();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
