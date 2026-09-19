package mc;

/**
 * Overuje geometrii oblohy.
 *
 * ⚠️ Hlavni kontrola je ORIENTACE STEN. Oblohu vidime zevnitr skorapky, takze
 * kazda stena musi mit normalu OBRACENOU K POCATKU. Kdyz je otocena ven,
 * backface culling ji zahodi - a obloha je neviditelna, aniz by cokoliv
 * zahlasilo chybu. Presne tohle se stalo a pouhym okem to vypada jako
 * "obloha se nekresli".
 */
public class SkyTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        float[] g = SkyRenderer.buildGeometry();

        check("geometrie neni prazdna", g.length > 0, g.length + " floatu");
        check("delka odpovida trojuhelnikum", g.length % 9 == 0, "" + g.length);

        int triangles = g.length / 9;
        int facingAway = 0, tooClose = 0;
        float minRadius = Float.MAX_VALUE, maxRadius = 0;

        for (int t = 0; t < triangles; t++) {
            int i = t * 9;
            float ax = g[i],   ay = g[i+1], az = g[i+2];
            float bx = g[i+3], by = g[i+4], bz = g[i+5];
            float cx = g[i+6], cy = g[i+7], cz = g[i+8];

            // normala = (b-a) x (c-a)
            float ux = bx-ax, uy = by-ay, uz = bz-az;
            float vx = cx-ax, vy = cy-ay, vz = cz-az;
            float nx = uy*vz - uz*vy, ny = uz*vx - ux*vz, nz = ux*vy - uy*vx;

            // Musi mirit K pocatku, tedy proti poloze steny.
            if (nx*ax + ny*ay + nz*az >= 0) facingAway++;

            float r = (float) Math.sqrt(ax*ax + ay*ay + az*az);
            minRadius = Math.min(minRadius, r);
            maxRadius = Math.max(maxRadius, r);
            if (r < 50) tooClose++;
        }

        System.out.printf("%nObloha: %d trojuhelniku, polomer %.0f az %.0f%n",
                triangles, minRadius, maxRadius);

        check("kazda stena je otocena k pozorovateli (jinak ji culling zahodi)",
                facingAway == 0, facingAway + " odvracenych");
        check("nic neni bliz nez 50 bloku (obloha ma byt daleko)", tooClose == 0,
                tooClose + " prilis blizko");

        // Slunce a mesic musi byt naproti sobe.
        float sunY = g[1], moonY = g[1 + 6 * 3];
        check("slunce a mesic jsou na opacnych stranach", sunY * moonY < 0,
                String.format("%.0f vs %.0f", sunY, moonY));

        // Hvezdy rozhazene po cele kouli, ne shluk na jednom miste.
        int above = 0, below = 0;
        for (int t = 2 * 2; t < triangles; t++) {   // za sluncem a mesicem
            if (g[t * 9 + 1] > 0) above++; else below++;
        }
        check("hvezdy jsou nad i pod obzorem", above > 0 && below > 0,
                above + " nad, " + below + " pod");

        // Deterministicke: dve volani daji tutez oblohu.
        float[] again = SkyRenderer.buildGeometry();
        boolean same = true;
        for (int i = 0; i < g.length; i++) if (g[i] != again[i]) same = false;
        check("obloha vyjde dvakrat stejne", same, "");

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
