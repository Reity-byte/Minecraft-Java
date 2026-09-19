package mc;

/**
 * Classic 2D Simplex noise (Ken Perlin's improved/simplex algorithm).
 * This is a well-known, standard implementation — nothing here is
 * project-specific, so treat it as a library, not something you need
 * to understand line by line.
 *
 * What you need to know to USE it:
 *   double value = SimplexNoise.noise(x, z);       // 2D - vysky terenu
 *   double value = SimplexNoise.noise(x, y, z);    // 3D - jeskyne
 *   -> returns a value roughly in the range [-1, 1]
 *   -> nearby inputs give similar outputs (that's the whole point)
 *   -> same input always gives the same output (deterministic, no randomness per call)
 */
public class SimplexNoise {

    private static final int[][] grad3 = {
            {1,1,0},{-1,1,0},{1,-1,0},{-1,-1,0},
            {1,0,1},{-1,0,1},{1,0,-1},{-1,0,-1},
            {0,1,1},{0,-1,1},{0,1,-1},{0,-1,-1}
    };

    private static final int[] p = new int[256];
    private static final int[] perm = new int[512];

    static {
        // fixed seed so terrain is reproducible between runs;
        // change the seed value if you want a different-looking world
        java.util.Random rand = new java.util.Random(12345);
        java.util.List<Integer> list = new java.util.ArrayList<>();
        for (int i = 0; i < 256; i++) list.add(i);
        java.util.Collections.shuffle(list, rand);
        for (int i = 0; i < 256; i++) p[i] = list.get(i);
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    private static double dot(int[] g, double x, double y) {
        return g[0] * x + g[1] * y;
    }

    private static double dot(int[] g, double x, double y, double z) {
        return g[0] * x + g[1] * y + g[2] * z;
    }

    /** 2D simplex noise. Returns a value approximately in [-1, 1]. */
    public static double noise(double xin, double yin) {
        double n0, n1, n2;

        double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
        double s = (xin + yin) * F2;
        int i = (int) Math.floor(xin + s);
        int j = (int) Math.floor(yin + s);

        double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
        double t = (i + j) * G2;
        double X0 = i - t;
        double Y0 = j - t;
        double x0 = xin - X0;
        double y0 = yin - Y0;

        int i1, j1;
        if (x0 > y0) { i1 = 1; j1 = 0; } else { i1 = 0; j1 = 1; }

        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2;
        double y2 = y0 - 1.0 + 2.0 * G2;

        int ii = i & 255;
        int jj = j & 255;
        int gi0 = perm[ii + perm[jj]] % 12;
        int gi1 = perm[ii + i1 + perm[jj + j1]] % 12;
        int gi2 = perm[ii + 1 + perm[jj + 1]] % 12;

        double t0 = 0.5 - x0 * x0 - y0 * y0;
        if (t0 < 0) n0 = 0.0;
        else { t0 *= t0; n0 = t0 * t0 * dot(grad3[gi0], x0, y0); }

        double t1 = 0.5 - x1 * x1 - y1 * y1;
        if (t1 < 0) n1 = 0.0;
        else { t1 *= t1; n1 = t1 * t1 * dot(grad3[gi1], x1, y1); }

        double t2 = 0.5 - x2 * x2 - y2 * y2;
        if (t2 < 0) n2 = 0.0;
        else { t2 *= t2; n2 = t2 * t2 * dot(grad3[gi2], x2, y2); }

        return 70.0 * (n0 + n1 + n2);
    }

    /**
     * 3D simplex noise. Returns a value approximately in [-1, 1].
     *
     * Stejny standardni algoritmus jako 2D varianta, jen o rozmer vys: simplex
     * je tetraedr, takze se scitaji ctyri rohy misto tri, a vyber druheho
     * a tretiho rohu zavisi na poradi x0, y0, z0.
     *
     * Pouziva to generovani jeskyn - ty potrebuji sum, ktery se meni i s vyskou.
     * S 2D sumem by vsechny jeskyne byly svisle protazene skrz cely sloupec.
     */
    public static double noise(double xin, double yin, double zin) {
        double n0, n1, n2, n3;

        final double F3 = 1.0 / 3.0;
        double s = (xin + yin + zin) * F3;
        int i = (int) Math.floor(xin + s);
        int j = (int) Math.floor(yin + s);
        int k = (int) Math.floor(zin + s);

        final double G3 = 1.0 / 6.0;
        double t = (i + j + k) * G3;
        double x0 = xin - (i - t);
        double y0 = yin - (j - t);
        double z0 = zin - (k - t);

        // Ktere dva rohy tetraedru lezi mezi pocatecnim a protilehlym
        int i1, j1, k1, i2, j2, k2;
        if (x0 >= y0) {
            if (y0 >= z0)      { i1=1; j1=0; k1=0; i2=1; j2=1; k2=0; }
            else if (x0 >= z0) { i1=1; j1=0; k1=0; i2=1; j2=0; k2=1; }
            else               { i1=0; j1=0; k1=1; i2=1; j2=0; k2=1; }
        } else {
            if (y0 < z0)       { i1=0; j1=0; k1=1; i2=0; j2=1; k2=1; }
            else if (x0 < z0)  { i1=0; j1=1; k1=0; i2=0; j2=1; k2=1; }
            else               { i1=0; j1=1; k1=0; i2=1; j2=1; k2=0; }
        }

        double x1 = x0 - i1 + G3,       y1 = y0 - j1 + G3,       z1 = z0 - k1 + G3;
        double x2 = x0 - i2 + 2.0 * G3, y2 = y0 - j2 + 2.0 * G3, z2 = z0 - k2 + 2.0 * G3;
        double x3 = x0 - 1.0 + 3.0*G3,  y3 = y0 - 1.0 + 3.0*G3,  z3 = z0 - 1.0 + 3.0*G3;

        int ii = i & 255, jj = j & 255, kk = k & 255;
        int gi0 = perm[ii      + perm[jj      + perm[kk]]]      % 12;
        int gi1 = perm[ii + i1 + perm[jj + j1 + perm[kk + k1]]] % 12;
        int gi2 = perm[ii + i2 + perm[jj + j2 + perm[kk + k2]]] % 12;
        int gi3 = perm[ii + 1  + perm[jj + 1  + perm[kk + 1]]]  % 12;

        double t0 = 0.6 - x0*x0 - y0*y0 - z0*z0;
        if (t0 < 0) n0 = 0.0; else { t0 *= t0; n0 = t0 * t0 * dot(grad3[gi0], x0, y0, z0); }

        double t1 = 0.6 - x1*x1 - y1*y1 - z1*z1;
        if (t1 < 0) n1 = 0.0; else { t1 *= t1; n1 = t1 * t1 * dot(grad3[gi1], x1, y1, z1); }

        double t2 = 0.6 - x2*x2 - y2*y2 - z2*z2;
        if (t2 < 0) n2 = 0.0; else { t2 *= t2; n2 = t2 * t2 * dot(grad3[gi2], x2, y2, z2); }

        double t3 = 0.6 - x3*x3 - y3*y3 - z3*z3;
        if (t3 < 0) n3 = 0.0; else { t3 *= t3; n3 = t3 * t3 * dot(grad3[gi3], x3, y3, z3); }

        return 32.0 * (n0 + n1 + n2 + n3);
    }
}