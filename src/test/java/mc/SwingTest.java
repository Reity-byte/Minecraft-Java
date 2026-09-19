package mc;

/**
 * Overuje machnuti rukou.
 *
 * ⚠️ Machnuti se NEPRERUSUJE. Kopani drzi tlacitko a spousti ho kazdy frame;
 * kdyby se pokazde restartovalo, ruka by se roztrasla na miste misto aby
 * se rozmachla.
 */
public class SwingTest {

    static int failures = 0;
    static final float DT = 1f / 60f;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        HandSwing s = new HandSwing();

        check("v klidu ruka nemacha", !s.isSwinging() && s.progress() == 0f && s.fast() == 0f && s.slow() == 0f, "");

        s.trigger();
        check("spusteni rozmachne ruku", s.isSwinging(), "");

        // Obe krivky musi vrcholit JINDE - v tom je cely minecrafti svih.
        // Kdyby vrcholily spolecne, je z toho houpnuti sem a tam.
        float peakFast = 0, peakSlow = 0;
        float atFastPeak = 0, atSlowPeak = 0;
        int frames = 0;
        boolean inRange = true;

        while (s.isSwinging() && frames < 1000) {
            s.update(DT);
            frames++;

            if (s.fast() > peakFast) { peakFast = s.fast(); atFastPeak = s.progress(); }
            if (s.slow() > peakSlow) { peakSlow = s.slow(); atSlowPeak = s.progress(); }

            if (s.fast() < 0f || s.fast() > 1.001f) inRange = false;
            if (s.slow() < 0f || s.slow() > 1.001f) inRange = false;
        }

        System.out.printf("%nMachnuti: %d framu (%.2f s)%n", frames, frames * DT);
        System.out.printf("  rychla krivka vrchol %.2f v %.0f %% prubehu%n",
                peakFast, atFastPeak * 100);
        System.out.printf("  pomala krivka vrchol %.2f v %.0f %% prubehu%n",
                peakSlow, atSlowPeak * 100);

        check("machnuti dobehne", !s.isSwinging(), "");
        check("obe krivky zustanou v rozsahu 0 az 1", inRange, "");
        check("obe krivky nekde vrcholi", peakFast > 0.9f && peakSlow > 0.9f,
                String.format("%.2f / %.2f", peakFast, peakSlow));
        check("rychla krivka vrcholi driv nez pomala", atFastPeak < atSlowPeak - 0.15f,
                String.format("%.2f vs %.2f", atFastPeak, atSlowPeak));
        check("rychla krivka je rozjeta uz v prvni tretine", atFastPeak < 0.4f,
                String.format("%.2f", atFastPeak));
        check("machnuti trva zhruba ctvrt vteriny",
                frames * DT > 0.15f && frames * DT < 0.4f, String.format("%.2f s", frames * DT));
        check("po dobehnuti je ruka zase v klidu",
                s.fast() == 0f && s.slow() == 0f && s.progress() == 0f, "");

        // ⚠️ Opakovane spousteni behem machnuti ho nesmi restartovat.
        HandSwing held = new HandSwing();
        held.trigger();
        for (int i = 0; i < 5; i++) { held.update(DT); held.trigger(); }
        float afterFive = held.progress();

        HandSwing once = new HandSwing();
        once.trigger();
        for (int i = 0; i < 5; i++) once.update(DT);

        check("drzene tlacitko machnuti nerestartuje",
                Math.abs(afterFive - once.progress()) < 1e-6f,
                String.format("%.4f vs %.4f", afterFive, once.progress()));

        // Po dobehnuti uz zase spustit jde.
        while (held.isSwinging()) held.update(DT);
        held.trigger();
        check("po dobehnuti jde machnout znovu", held.isSwinging(), "");

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
