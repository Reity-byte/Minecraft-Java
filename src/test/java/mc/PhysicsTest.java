package mc;


/** Testy fyziky a kolizi hrace. Player ani World nesahaji na GL. */
public class PhysicsTest {

    static int failures = 0;
    static final float DT = 1f / 60f;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    static boolean near(float a, float b, float tol) { return Math.abs(a - b) <= tol; }

    /** Postavi plnou plosinu z kamene ve vysce y pres zadany obdelnik. */
    static void platform(World w, int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                w.placeBlock(x, y, z, World.STONE);
    }

    static void wall(World w, int x0, int z0, int x1, int z1, int y0, int y1) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int y = y0; y <= y1; y++)
                    w.placeBlock(x, y, z, World.STONE);
    }

    /** Necha hrace stat/padat n tiku bez vstupu. */
    static void settle(World w, Player p, int ticks) {
        p.inputForward = 0; p.inputStrafe = 0; p.inputJump = false;
        for (int i = 0; i < ticks; i++) p.update(w, DT, 0f);
    }

    public static void main(String[] args) {
        World w = new World();
        w.updateBlocking(8.5f, 8.5f);

        final int GROUND = 90;          // vysoko nad terenem -> ciste prostredi
        final float STAND = GROUND + 1; // nohy na hornim povrchu plosiny
        platform(w, -4, -4, 20, 20, GROUND);

        Player p = new Player();

        // ---------- 1) spawn na povrch ----------
        Player sp = new Player();
        sp.spawn(w, 8.5f, 8.5f);
        check("spawn postavi hrace na nejvyssi pevny blok", near(sp.y, STAND, 0.001f),
                "y=" + sp.y + " cekano " + STAND);
        check("spawn hlasi onGround", sp.onGround, "");

        // ---------- 2) gravitace a dosednuti ----------
        p.x = 8.5f; p.z = 8.5f; p.y = STAND + 12; p.onGround = false; p.vy = 0;
        settle(w, p, 300);
        check("hrac spadne a zastavi se na plosine", near(p.y, STAND, 0.01f), "y=" + p.y);
        check("po dosednuti je onGround", p.onGround, "");
        check("po dosednuti je svisla rychlost nulova", near(p.vy, 0f, 0.001f), "vy=" + p.vy);

        // ---------- 3) stani je stabilni (nepropada se ani necuka) ----------
        float restY = p.y;
        settle(w, p, 600);
        check("po 600 ticich stani se pozice nezmenila", near(p.y, restY, 0.0001f),
                restY + " -> " + p.y);

        // ---------- 4) vyska skoku ----------
        p.inputJump = true;
        p.update(w, DT, 0f);
        p.inputJump = false;
        float peak = p.y;
        for (int i = 0; i < 200; i++) {
            p.update(w, DT, 0f);
            peak = Math.max(peak, p.y);
            if (p.onGround && i > 5) break;
        }
        float jumpHeight = peak - restY;
        check("skok vyskoci pres 1 blok (na schod)", jumpHeight > 1.05f, "vyska=" + jumpHeight);
        check("skok nevyskoci na 2 bloky", jumpHeight < 2.0f, "vyska=" + jumpHeight);
        settle(w, p, 200);
        check("po skoku zase pristane", near(p.y, STAND, 0.01f), "y=" + p.y);

        // ---------- 5) chuze do zdi ----------
        // zed na x=14, hrac jde z x=8.5 smerem +X (yaw 0)
        wall(w, 14, -4, 14, 20, GROUND + 1, GROUND + 3);
        p.x = 8.5f; p.z = 8.5f; p.y = STAND; p.vy = 0; p.onGround = true;
        p.inputForward = 1;
        for (int i = 0; i < 400; i++) p.update(w, DT, 0f);
        check("chuze do zdi hrace zastavi pred ni", p.x < 14f, "x=" + p.x);
        check("hrac se zastavi tesne u zdi (ne metr pred)", p.x > 13.6f, "x=" + p.x);
        check("hitbox neprotina zed", p.x + 0.3f <= 14f + 0.001f, "prava hrana=" + (p.x + 0.3f));

        // ---------- 6) roh: diagonalni naraz nesmi propustit ----------
        // pridame jeste zed na z=14 -> vznikne roh v (14,14)
        wall(w, -4, 14, 20, 14, GROUND + 1, GROUND + 3);
        p.x = 12.5f; p.z = 12.5f; p.y = STAND; p.vy = 0; p.onGround = true;
        p.inputForward = 1; p.inputStrafe = 1;   // sikmo do rohu
        for (int i = 0; i < 400; i++) p.update(w, DT, 0f);
        check("diagonalni naraz do rohu neprojde v X", p.x < 14f, "x=" + p.x);
        check("diagonalni naraz do rohu neprojde v Z", p.z < 14f, "z=" + p.z);

        // ---------- 7) skok na jednoblokovy schod ----------
        World w2 = new World();
        w2.updateBlocking(8.5f, 8.5f);
        platform(w2, -4, -4, 20, 20, GROUND);
        platform(w2, 12, -4, 20, 20, GROUND + 1);   // schod o 1 blok vys
        Player q = new Player();
        q.x = 8.5f; q.z = 8.5f; q.y = STAND; q.onGround = true;
        q.inputForward = 1;
        boolean climbed = false;
        for (int i = 0; i < 500; i++) {
            q.inputJump = q.onGround;   // skace kdykoliv se dotkne zeme
            q.update(w2, DT, 0f);
            // hlidame OKAMZIK vylezeni; kdyz necham bezet dal, dojde na konec
            // testovaci plosiny a spadne z ni - a merilo by se uz jen to
            if (q.onGround && q.x > 12.3f && q.y > STAND + 0.5f) { climbed = true; break; }
        }
        check("hrac vyskoci na jednoblokovy schod", climbed, "x=" + q.x + " y=" + q.y);

        // ---------- 8) dvoublokovou zed neprekona ----------
        World w3 = new World();
        w3.updateBlocking(8.5f, 8.5f);
        platform(w3, -4, -4, 20, 20, GROUND);
        wall(w3, 12, -4, 13, 20, GROUND + 1, GROUND + 2);   // 2 bloky vysoka
        Player r = new Player();
        r.x = 8.5f; r.z = 8.5f; r.y = STAND; r.onGround = true;
        r.inputForward = 1;
        for (int i = 0; i < 500; i++) {
            r.inputJump = r.onGround;
            r.update(w3, DT, 0f);
        }
        check("dvoublokovou zed neprekona", r.x < 12f, "x=" + r.x);

        // ---------- 9) tunelovani pri velke rychlosti a velkem dt ----------
        World w4 = new World();
        w4.updateBlocking(8.5f, 8.5f);
        platform(w4, -4, -4, 20, 20, GROUND);   // JEDNA vrstva bloku
        Player t = new Player();
        t.x = 8.5f; t.z = 8.5f; t.y = STAND + 40; t.vy = -50; t.onGround = false;
        // dt = 5 s: Player si ho musi sam oriznout, jinak by proletel skrz
        for (int i = 0; i < 60; i++) t.update(w4, 5.0f, 0f);
        check("velke dt neprotuneluje skrz jednu vrstvu bloku", t.y >= GROUND,
                "y=" + t.y);
        check("po padu s velkym dt stoji na plosine", near(t.y, STAND, 0.01f), "y=" + t.y);

        // ---------- 10) let ignoruje gravitaci ----------
        Player f = new Player();
        f.x = 8.5f; f.z = 8.5f; f.y = STAND + 20; f.flying = true;
        float flyStart = f.y;
        settle(w, f, 300);
        check("v letu hrac nepada", near(f.y, flyStart, 0.001f), "y=" + f.y);
        f.flying = false;
        settle(w, f, 400);
        check("po vypnuti letu zase spadne", near(f.y, STAND, 0.01f), "y=" + f.y);

        // ---------- 11) noclip projde skrz ----------
        Player n = new Player();
        n.x = 8.5f; n.z = 8.5f; n.y = STAND; n.noclip = true; n.flying = true;
        n.inputDescend = true;
        for (int i = 0; i < 60; i++) n.update(w, DT, 0f);
        check("noclip propadne skrz plosinu", n.y < GROUND, "y=" + n.y);

        // ---------- 12) intersectsBlock pro pokladani ----------
        Player b = new Player();
        b.x = 8.5f; b.y = STAND; b.z = 8.5f;
        check("blok v nohou protina hitbox", b.intersectsBlock(8, (int) STAND, 8), "");
        check("blok v hlave protina hitbox", b.intersectsBlock(8, (int) STAND + 1, 8), "");
        check("blok pod nohama NEprotina hitbox", !b.intersectsBlock(8, (int) STAND - 1, 8), "");
        check("blok nad hlavou NEprotina hitbox", !b.intersectsBlock(8, (int) STAND + 2, 8), "");
        check("sousedni blok vedle NEprotina (sirka 0.6)", !b.intersectsBlock(9, (int) STAND, 8), "");

        // ---------- 13) diagonalni chuze neni rychlejsi ----------
        Player d1 = new Player();
        d1.x = 8.5f; d1.z = 8.5f; d1.y = STAND + 30; d1.flying = true;
        d1.inputForward = 1;
        for (int i = 0; i < 60; i++) d1.update(w, DT, 0f);
        float straight = d1.x - 8.5f;

        Player d2 = new Player();
        d2.x = 8.5f; d2.z = 8.5f; d2.y = STAND + 30; d2.flying = true;
        d2.inputForward = 1; d2.inputStrafe = 1;
        for (int i = 0; i < 60; i++) d2.update(w, DT, 0f);
        float diagonal = (float) Math.hypot(d2.x - 8.5f, d2.z - 8.5f);
        check("sikma chuze neni rychlejsi nez rovne", near(straight, diagonal, 0.01f),
                "rovne=" + straight + " sikmo=" + diagonal);

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
