package mc;

import java.util.Random;

/**
 * Zvuky prostredi (Ambience): cile hlasitosti podle okoli, dotahovani,
 * hledani vody, kapky v jeskyni a ztlumeni mimo hru.
 */
public class AmbienceTest {

    static int failures = 0;
    static final float DT = 1f / 60f;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        targets();
        world();
        drips();

        for (World w : CreativeTest.opened) w.shutdown();
        CreativeTest.opened.clear();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    static void targets() {
        int sea = World.SEA_LEVEL;

        check("pod sirym nebem fouka, ve vysce vic",
                Ambience.windTarget(15, sea, false) > 0.05f
                        && Ambience.windTarget(15, sea + 60, false) == Ambience.WIND_MAX
                        && Ambience.windTarget(15, sea + 60, false) > Ambience.windTarget(15, sea, false), "");
        check("v budove / pod zemi (bez oblohy) nefouka", Ambience.windTarget(0, sea + 20, false) == 0f, "");
        check("pod stromem (svetlo 10) fouka o dost min nez venku",
                Ambience.windTarget(10, sea, false) < 0.5f * Ambience.windTarget(15, sea, false), "");

        check("hluboko ve tme jeskyne huci naplno", Ambience.caveTarget(0, sea - 30, false) == Ambience.CAVE_MAX, "");
        check("tmavy dum na povrchu nehuci", Ambience.caveTarget(0, sea + 5, false) == 0f, "");
        check("pod otevrenou oblohou (dno rokliny) nehuci", Ambience.caveTarget(15, sea - 30, false) == 0f, "");

        check("u vody naplno, dal slabne, za dosahem ticho",
                Ambience.waterTarget(0f, false) == Ambience.WATER_MAX
                        && Ambience.waterTarget(4f, false) < Ambience.WATER_MAX
                        && Ambience.waterTarget(4f, false) > 0f
                        && Ambience.waterTarget(Float.POSITIVE_INFINITY, false) == 0f, "");
        check("pod vodou: voda hraje, vitr a jeskyne ne",
                Ambience.waterTarget(Float.POSITIVE_INFINITY, true) == Ambience.UNDERWATER
                        && Ambience.windTarget(15, sea + 60, true) == 0f
                        && Ambience.caveTarget(0, sea - 30, true) == 0f, "");
    }

    static void world() {
        final int Y = 100;
        World w = CreativeTest.arena(Y);

        check("zadna voda v dosahu = nekonecno",
                Ambience.nearestWater(w, 8, Y + 2, 8) == Float.POSITIVE_INFINITY, "");
        w.placeBlock(11, Y + 2, 12, World.WATER);
        float d = Ambience.nearestWater(w, 8, Y + 2, 8);
        check("nejbliz voda na 3,4,0 = 5 bloku", Math.abs(d - 5f) < 1e-4f, "" + d);

        SoundTest.Recorder rec = new SoundTest.Recorder();
        Ambience a = new Ambience(new Random(1));
        a.update(w, 8.5f, Y + 2.5f, 8.5f, DT, rec);
        float first = rec.loops.getOrDefault(Sound.AMBIENT_WIND, -1f);
        check("hlasitost se dotahuje, neskoci", first > 0f && first < 0.1f * Ambience.WIND_MAX, "" + first);

        for (int i = 0; i < 600; i++) a.update(w, 8.5f, Y + 2.5f, 8.5f, DT, rec);
        float wind = rec.loops.get(Sound.AMBIENT_WIND);
        float water = rec.loops.get(Sound.AMBIENT_WATER);
        check("venku vysoko nad mori: fouka", Math.abs(wind - Ambience.windTarget(15, Y + 2.5f, false)) < 0.01f,
                "" + wind);
        check("voda 5 bloku od hlavy je slyset", Math.abs(water - Ambience.waterTarget(5f, false)) < 0.01f,
                "" + water);
        check("jeskyne mlci a zadna kapka", rec.loops.get(Sound.AMBIENT_CAVE) < 0.001f && rec.played.isEmpty(), "");

        for (int i = 0; i < 600; i++) a.silence(DT, rec);
        check("v pauze vsechno dozni do ticha",
                rec.loops.get(Sound.AMBIENT_WIND) < 0.001f && rec.loops.get(Sound.AMBIENT_WATER) < 0.001f, "");

        check("tichy SoundSink smycky ignoruje (vychozi loop)", callSilent(w), "");
    }

    static boolean callSilent(World w) {
        new Ambience(new Random(2)).update(w, 8.5f, 102.5f, 8.5f, DT, SoundSink.SILENT);
        return true;
    }

    static void drips() {
        // Hluboko v kameni: svetlo od oblohy 0, pod hladinou moře.
        World w = CreativeTest.arena(100);
        int y = World.SEA_LEVEL - 30;
        w.breakBlock(8, y, 8);

        SoundTest.Recorder rec = new SoundTest.Recorder();
        Ambience a = new Ambience(new Random(7));
        int frames = 60 * 60;   // minuta
        for (int i = 0; i < frames; i++) a.update(w, 8.5f, y + 0.5f, 8.5f, DT, rec);

        check("v jeskyni huci", rec.loops.get(Sound.AMBIENT_CAVE) > 0.9f * Ambience.CAVE_MAX,
                "" + rec.loops.get(Sound.AMBIENT_CAVE));

        long drips = rec.played.stream().filter(p -> p.sound() == Sound.CAVE_DRIP).count();
        check("za minutu 4 az 15 kapek (jednou za 4-14 s)", drips >= 4 && drips <= 15, "" + drips);

        boolean positional = rec.played.stream().allMatch(p -> p.positional()
                && Math.abs(p.x() - 8.5f) <= Ambience.DRIP_RANGE && Math.abs(p.z() - 8.5f) <= Ambience.DRIP_RANGE);
        check("kapky jsou pozicni a kolem hlavy", positional, "");
    }
}
