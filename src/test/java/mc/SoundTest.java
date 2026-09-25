package mc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Overuje zvukovou logiku, ktera nepotrebuje OpenAL: vyber zvuku podle
 * materialu, obmenu vysky, cooldown, kroky, syntezu, cteni WAV a vymenu
 * placeholderu za soubor.
 *
 * Samotne prehravani (SoundEngine) se tu nespousti - chtelo by zvukove
 * zarizeni. To se overuje rucne ve hre.
 */
public class SoundTest {

    static int failures = 0;
    static final float DT = 1f / 60f;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    static boolean near(float a, float b, float tol) { return Math.abs(a - b) <= tol; }

    /** Zvukovy vystup, ktery si jen zapisuje, co mel zahrat. Pouziva ho i MiningTest. */
    static final class Recorder implements SoundSink {
        record Played(Sound sound, boolean positional, float x, float y, float z) {}

        final List<Played> played = new ArrayList<>();

        @Override public void play(Sound sound) {
            if (sound != null) played.add(new Played(sound, false, 0, 0, 0));
        }

        @Override public void playAt(Sound sound, float x, float y, float z) {
            if (sound != null) played.add(new Played(sound, true, x, y, z));
        }

        /** Posledni hlasitost kazde smycky prostredi. */
        final java.util.Map<Sound, Float> loops = new java.util.EnumMap<>(Sound.class);

        @Override public void loop(Sound sound, float gain) {
            if (sound != null) loops.put(sound, gain);
        }

        Played last() { return played.isEmpty() ? null : played.get(played.size() - 1); }
    }

    public static void main(String[] args) throws IOException {
        materials();
        pitchAndThrottle();
        footsteps();
        playerSteps();
        synthesis();
        wav();
        library();
        variants();
        loops();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ==================================================================

    /** Vsechny bloky, ktere jde zamerit a rozbit - ty musi znit. */
    static byte[] targetableBlocks() {
        byte[] all = new byte[64];
        int n = 0;
        for (int id = 0; id <= World.LAST_BUILT_IN; id++)
            if (World.isTargetable((byte) id)) all[n++] = (byte) id;
        return Arrays.copyOf(all, n);
    }

    static void materials() {
        byte[] blocks = targetableBlocks();

        boolean allSound = true;
        for (byte b : blocks) allSound &= Sound.Material.of(b) != null;
        check("kazdy blok, ktery jde rozbit, ma material zvuku", allSound, blocks.length + " bloku");
        check("vzduch a voda neznou",
                Sound.Material.of(World.AIR) == null && Sound.breakOf(World.WATER) == null
                        && Sound.stepOf(World.AIR) == null, "");

        // Stejne rozdeleni jako tvrdost: co ma stejnou tvrdost, zni stejne.
        boolean sameGroups = true;
        String broken = "";
        for (byte a : blocks)
            for (byte b : blocks)
                if (World.hardness(a) == World.hardness(b) && Sound.Material.of(a) != Sound.Material.of(b)) {
                    sameGroups = false;
                    broken = a + " vs " + b;
                }
        check("bloky se stejnou tvrdosti maji stejny material zvuku", sameGroups, broken);

        check("rudy zni jako kamen, pochoden jako drevo, listi jako rostlina",
                Sound.Material.of(World.IRON_ORE) == Sound.Material.STONE
                        && Sound.Material.of(World.COAL_ORE) == Sound.Material.STONE
                        && Sound.Material.of(World.TORCH) == Sound.Material.WOOD
                        && Sound.Material.of(World.LEAVES) == Sound.Material.PLANT, "");

        check("druh zvuku sedi s udalosti",
                Sound.breakOf(World.STONE) == Sound.BREAK_STONE
                        && Sound.placeOf(World.PLANKS) == Sound.PLACE_WOOD
                        && Sound.stepOf(World.GRASS) == Sound.STEP_EARTH
                        && Sound.stepOf(World.LEAVES) == Sound.STEP_PLANT, "");

        boolean uniqueFiles = true;
        for (Sound a : Sound.values())
            for (Sound b : Sound.values())
                if (a != b && a.fileName().equals(b.fileName())) uniqueFiles = false;
        check("kazdy zvuk ma vlastni jmeno souboru", uniqueFiles && Sound.BREAK_STONE.fileName().equals("break_stone"),
                Sound.BREAK_STONE.fileName());
    }

    // ==================================================================

    static void pitchAndThrottle() {
        // ---------- obmena vysky ----------
        Random random = new Random(42);
        float v = Sound.Kind.BREAK.pitchVariation;
        float min = 9f, max = -9f, sum = 0f;
        int n = 10000;
        for (int i = 0; i < n; i++) {
            float p = Sound.Kind.BREAK.pitch(random);
            min = Math.min(min, p);
            max = Math.max(max, p);
            sum += p;
        }
        check("vyska tonu zustava v 1 +- obmena", min >= 1f - v && max <= 1f + v,
                String.format("%.3f az %.3f", min, max));
        check("a opravdu se meni po celem rozsahu", min < 1f - 0.9f * v && max > 1f + 0.9f * v, "");
        check("v prumeru zni puvodni vyskou", near(sum / n, 1f, 0.005f), String.format("%.4f", sum / n));
        check("kliknuti se meni mene nez rozbiti",
                Sound.Kind.CLICK.pitchVariation < Sound.Kind.BREAK.pitchVariation, "");

        // ---------- cooldown ----------
        SoundThrottle throttle = new SoundThrottle();
        check("prvni zvuk projde", throttle.allow(Sound.Kind.BREAK, 10.0), "");
        check("dalsi behem cooldownu ne", !throttle.allow(Sound.Kind.BREAK, 10.05), "");
        check("jiny druh ma vlastni cooldown", throttle.allow(Sound.Kind.PLACE, 10.05), "");
        check("po cooldownu zase projde", throttle.allow(Sound.Kind.BREAK, 10.0 + Sound.Kind.BREAK.cooldown), "");

        // "Kulomet": pochoden se rozbije za 0,05 s, prejizdi se pres jednu za
        // druhou kazdy frame. Za sekundu smi zaznit nejvys 1 / cooldown rozbiti.
        SoundThrottle spam = new SoundThrottle();
        int heard = 0;
        for (int frame = 0; frame < 60; frame++)
            if (spam.allow(Sound.Kind.BREAK, frame * DT)) heard++;
        int limit = (int) Math.ceil(1f / Sound.Kind.BREAK.cooldown);
        System.out.printf("%nRozbiti kazdy frame po dobu 1 s: zaznelo %d z 60%n", heard);
        check("rozbijeni kazdy frame nezni jako kulomet", heard <= limit && heard >= limit - 2,
                heard + " za sekundu, strop " + limit);

        // Odmitnuty pokus cooldown neprodluzuje - jinak by pri drzeni
        // tlacitka nezaznelo uz nikdy nic.
        SoundThrottle held = new SoundThrottle();
        held.allow(Sound.Kind.STEP, 0.0);
        held.allow(Sound.Kind.STEP, 0.1);
        check("odmitnuty pokus cooldown neprodluzuje", held.allow(Sound.Kind.STEP, Sound.Kind.STEP.cooldown), "");
    }

    // ==================================================================

    static void footsteps() {
        float walk = 4.3f, sprint = 4.3f * 1.3f;

        check("interval kroku je delka kroku / rychlost",
                near(Footsteps.interval(walk), Footsteps.STRIDE / walk, 1e-6f),
                String.format("%.3f s", Footsteps.interval(walk)));
        check("rychleji = castejsi kroky", Footsteps.interval(sprint) < Footsteps.interval(walk), "");
        check("ve stoje zadne kroky", Footsteps.interval(0f) == Float.POSITIVE_INFINITY, "");

        // Chuze 10 s: intervaly mezi kroky musi sedet s interval().
        Footsteps f = new Footsteps();
        List<Integer> stepFrames = new ArrayList<>();
        for (int frame = 0; frame < 600; frame++)
            if (f.update(walk * DT, true)) stepFrames.add(frame);

        float measured = (stepFrames.get(stepFrames.size() - 1) - stepFrames.get(0))
                / (float) (stepFrames.size() - 1) * DT;
        System.out.printf("%nChuze 4,3 b/s: %d kroku za 10 s, krok kazdych %.3f s%n",
                stepFrames.size(), measured);
        check("chuze 10 s = 10 * rychlost / delka kroku",
                Math.abs(stepFrames.size() - 10f * walk / Footsteps.STRIDE) <= 1f, "" + stepFrames.size());
        check("namereny interval odpovida vypoctenemu",
                near(measured, Footsteps.interval(walk), DT), String.format("%.3f s", measured));

        // Ve vzduchu se nesaha, ale vzdalenost se pocita - po dlouhem letu
        // zazni pri dopadu JEDEN krok, ne salva.
        Footsteps air = new Footsteps();
        int inAir = 0;
        for (int frame = 0; frame < 300; frame++)
            if (air.update(12f * DT, false)) inAir++;
        check("ve vzduchu zadny krok", inAir == 0, "" + inAir);

        int landing = 0;
        for (int frame = 0; frame < 3; frame++)
            if (air.update(0f, true)) landing++;
        check("po dlouhem letu zazni pri dopadu jeden krok", landing == 1, "" + landing);

        Footsteps still = new Footsteps();
        int standing = 0;
        for (int frame = 0; frame < 600; frame++)
            if (still.update(0f, true)) standing++;
        check("ve stoje zadny krok", standing == 0, "" + standing);
    }

    /** Skutecny Player na plosine: kroky jen pri chuzi po zemi. */
    static void playerSteps() {
        World w = new World();
        w.loadRadius = 1;
        w.unloadRadius = 3;
        w.updateBlocking(8.5f, 8.5f);

        final int GROUND = 90;
        for (int x = -4; x <= 20; x++)
            for (int z = -4; z <= 20; z++)
                w.placeBlock(x, GROUND, z, x < 8 ? World.PLANKS : World.STONE);

        Player p = new Player();
        p.x = 8.5f; p.z = 8.5f; p.y = GROUND + 1; p.onGround = true;

        int standing = 0;
        for (int i = 0; i < 120; i++) { p.update(w, DT, 0f); if (p.stepped) standing++; }
        check("hrac ve stoje nesaha", standing == 0, "" + standing);

        p.inputForward = 1;   // yaw 0 = po +X, na kamen
        int steps = 0;
        byte block = World.AIR;
        for (int i = 0; i < 120; i++) {
            p.update(w, DT, 0f);
            if (p.stepped) { steps++; block = p.stepBlock; }
        }
        float expected = 2f * 4.3f / Footsteps.STRIDE;
        check("hrac na zemi saha podle ujite vzdalenosti", Math.abs(steps - expected) <= 1f,
                steps + " kroku za 2 s, cekano ~" + String.format("%.1f", expected));
        check("krok vi, po jakem bloku se slo", block == World.STONE, "" + block);

        p.inputForward = 0;
        p.x = 7.5f; p.z = 8.5f; p.y = GROUND + 1;
        check("pod hracem na prknech jsou prkna", p.blockUnderFeet(w) == World.PLANKS, "");

        // Hrac na hrane: stred je nad vzduchem, drzi ho kraj hitboxu.
        p.x = 21.1f; p.z = 8.5f;   // plosina konci bunkou 20, stred je nad vzduchem
        check("na hrane se saha po bloku, ktery hrace drzi",
                w.getBlock(21, GROUND, 8) == World.AIR && p.blockUnderFeet(w) == World.STONE,
                "" + p.blockUnderFeet(w));

        // Let nesaha, i kdyz se hrac hybe tesne nad zemi.
        Player flyer = new Player();
        flyer.x = 8.5f; flyer.z = 2.5f; flyer.y = GROUND + 1.5f; flyer.flying = true;
        flyer.inputForward = 1;
        int flown = 0;
        for (int i = 0; i < 60; i++) { flyer.update(w, DT, 0f); if (flyer.stepped) flown++; }
        check("v letu zadne kroky", flown == 0, "" + flown);

        w.shutdown();
    }

    // ==================================================================

    static void synthesis() {
        boolean audible = true, cleanEdges = true, deterministic = true;
        String detail = "";

        for (Sound s : Sound.values()) {
            short[] a = SoundSynth.synthesize(s).samples();
            short[] b = SoundSynth.synthesize(s).samples();

            int peak = 0;
            for (short x : a) peak = Math.max(peak, Math.abs(x));

            audible &= a.length > 0 && peak > Short.MAX_VALUE / 3;
            // Smycka zacina a konci uprostred signalu - hlida ji loops() nize.
            if (!s.loops()) cleanEdges &= Math.abs(a[0]) < 500 && Math.abs(a[a.length - 1]) < 500;
            deterministic &= Arrays.equals(a, b);
            if (peak <= Short.MAX_VALUE / 3) detail = s + " spicka " + peak;
        }

        check("kazdy placeholder je slysitelny", audible, detail);
        check("zacina i konci u nuly (bez lupnuti)", cleanEdges, "");
        check("vyjde pokazde stejne - sum je z hashe, ne z nahody", deterministic, "");

        check("materialy zni ruzne",
                !Arrays.equals(SoundSynth.synthesize(Sound.BREAK_STONE).samples(),
                        SoundSynth.synthesize(Sound.BREAK_EARTH).samples())
                        && !Arrays.equals(SoundSynth.synthesize(Sound.BREAK_WOOD).samples(),
                        SoundSynth.synthesize(Sound.BREAK_PLANT).samples()), "");

        float step = SoundSynth.synthesize(Sound.STEP_STONE).seconds();
        float place = SoundSynth.synthesize(Sound.PLACE_STONE).seconds();
        float brk = SoundSynth.synthesize(Sound.BREAK_STONE).seconds();
        check("krok je kratsi nez polozeni a to kratsi nez rozbiti", step < place && place < brk,
                String.format("%.2f / %.2f / %.2f s", step, place, brk));
    }

    static void wav() {
        // ---------- tam a zpet ----------
        Wav.Pcm original = SoundSynth.synthesize(Sound.PLACE_WOOD);
        byte[] file = Wav.encode(original);
        Wav.Pcm back = Wav.decode(file);
        check("zapis a cteni WAV vrati tytez vzorky",
                back != null && back.sampleRate() == original.sampleRate()
                        && Arrays.equals(back.samples(), original.samples()), "");
        check("hlavicka je RIFF/WAVE", new String(file, 0, 4).equals("RIFF") && new String(file, 8, 4).equals("WAVE"), "");

        // ---------- 8 bitu stereo ----------
        // Levy kanal 255 (skoro +1), pravy 1 (skoro -1) -> smichane mono kolem nuly;
        // oba 192 -> +0,5.
        byte[] stereo8 = wavFile(1, 2, 8000, 8, new byte[]{(byte) 255, 1, (byte) 192, (byte) 192}, false);
        Wav.Pcm mono = Wav.decode(stereo8);
        check("8bitove stereo se precte a smicha do mona",
                mono != null && mono.samples().length == 2
                        && Math.abs(mono.samples()[0]) <= 256 && mono.samples()[1] == 64 << 8,
                mono == null ? "null" : Arrays.toString(mono.samples()));

        // ---------- cizi blok pred daty ----------
        byte[] withList = wavFile(1, 1, 8000, 16, new byte[]{0x10, 0x00, (byte) 0xF0, (byte) 0xFF}, true);
        Wav.Pcm listed = Wav.decode(withList);
        check("cizi blok (LIST) mezi hlavickou a daty nevadi",
                listed != null && listed.samples().length == 2 && listed.samples()[0] == 16 && listed.samples()[1] == -16,
                listed == null ? "null" : Arrays.toString(listed.samples()));

        // ---------- co neumi ----------
        check("24 bitu se odmitne", Wav.decode(wavFile(1, 1, 8000, 24, new byte[6], false)) == null, "");
        check("komprese (ne PCM) se odmitne", Wav.decode(wavFile(2, 1, 8000, 16, new byte[4], false)) == null, "");

        // SND-4: WAVE_FORMAT_EXTENSIBLE rozhoduje podle subformatu.
        Wav.Pcm extPcm = Wav.decode(extensible(1, new byte[]{0x10, 0x00, (byte) 0xF0, (byte) 0xFF}));
        check("EXTENSIBLE se subformatem PCM se prehraje",
                extPcm != null && extPcm.samples().length == 2 && extPcm.samples()[0] == 0x10,
                extPcm == null ? "null" : "" + extPcm.samples().length);
        check("EXTENSIBLE s A-law (6) se odmitne, misto sumu hraje placeholder",
                Wav.decode(extensible(6, new byte[4])) == null, "");
        check("EXTENSIBLE bez subformatu (kratky fmt) se odmitne",
                Wav.decode(wavFile(0xFFFE, 1, 8000, 16, new byte[4], false)) == null, "");
        check("nesmysl se odmitne", Wav.decode("tohle neni wav".getBytes()) == null && Wav.decode(null) == null, "");

        byte[] cut = Arrays.copyOf(file, 44 + 100);
        Wav.Pcm partial = Wav.decode(cut);
        check("useknuty soubor vrati, co v nem je",
                partial != null && partial.samples().length == 50, partial == null ? "null" : "" + partial.samples().length);
    }

    /** WAV v obalce WAVE_FORMAT_EXTENSIBLE (fmt o 40 bajtech), mono 16 bitu, dany subformat. */
    static byte[] extensible(int subFormat, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(12 + 8 + 40 + 8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(4 + 8 + 40 + 8 + data.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(40).putShort((short) 0xFFFE).putShort((short) 1)
                .putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                .putShort((short) 22).putShort((short) 16).putInt(4)          // cbSize, validBits, maska kanalu
                .putShort((short) subFormat)                                    // GUID: prvni 2 bajty = kod
                .put(new byte[]{0, 0, 0, 0, 0x10, 0, (byte) 0x80, 0, 0, (byte) 0xAA, 0, 0x38, (byte) 0x9B, 0x71});
        b.put("data".getBytes()).putInt(data.length).put(data);
        return b.array();
    }

    /** Rucne slozeny WAV: format, kanaly, frekvence, bity, data, volitelne blok LIST navic. */
    static byte[] wavFile(int format, int channels, int rate, int bits, byte[] data, boolean listChunk) {
        int extra = listChunk ? 8 + 4 : 0;
        ByteBuffer b = ByteBuffer.allocate(44 + extra + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + extra + data.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) format).putShort((short) channels)
                .putInt(rate).putInt(rate * channels * bits / 8)
                .putShort((short) (channels * bits / 8)).putShort((short) bits);
        if (listChunk) b.put("LIST".getBytes()).putInt(4).put("INFO".getBytes());
        b.put("data".getBytes()).putInt(data.length).put(data);
        return b.array();
    }

    // ==================================================================

    static void loops() {
        List<Sound> loops = new ArrayList<>();
        for (Sound s : Sound.values()) if (s.loops()) loops.add(s);
        check("tri smycky prostredi: vitr, jeskyne, voda",
                loops.equals(List.of(Sound.AMBIENT_WIND, Sound.AMBIENT_CAVE, Sound.AMBIENT_WATER)), loops.toString());

        // Bez svu: skok z posledniho vzorku na prvni neni vetsi nez nejvetsi
        // skok mezi sousednimi vzorky uvnitr smycky. Lupnuti by bylo mnohem vic.
        boolean seamless = true, length = true, different = true;
        String detail = "";
        for (Sound s : loops) {
            short[] a = SoundSynth.synthesize(s).samples();
            int inside = 0;
            for (int i = 1; i < a.length; i++) inside = Math.max(inside, Math.abs(a[i] - a[i - 1]));
            int wrap = Math.abs(a[0] - a[a.length - 1]);
            if (wrap > inside) { seamless = false; detail = s + ": sev " + wrap + " > " + inside; }
            length &= Math.abs(SoundSynth.synthesize(s).seconds() - SoundSynth.LOOP_SECONDS) < 0.01f;
            different &= SoundSynth.variants(s) == 1;
        }
        check("smycky navazuji beze svu (konec -> zacatek)", seamless, detail);
        check("smycky maji delku LOOP_SECONDS a jednu variantu", length && different, "");
        check("smycky se neprehravaji jako jednorazove zvuky, kapka ano",
                !Sound.CAVE_DRIP.loops() && Sound.AMBIENT_WIND.kind.pitchVariation == 0f, "");
    }

    static void variants() throws IOException {
        // ---------- syntetizovane ----------
        boolean distinct = true, audible = true, sameLength = true;
        for (Sound s : Sound.values()) {
            int n = SoundSynth.variants(s);
            for (int v = 0; v < n; v++) {
                short[] a = SoundSynth.synthesize(s, v).samples();
                int peak = 0;
                for (short x : a) peak = Math.max(peak, Math.abs(x));
                audible &= peak > Short.MAX_VALUE / 3;
                sameLength &= a.length == SoundSynth.synthesize(s).samples().length;
                for (int w = 0; w < v; w++) distinct &= !Arrays.equals(a, SoundSynth.synthesize(s, w).samples());
            }
        }
        check("zvuky bloku maji 4 varianty, kliknuti a sebrani jednu",
                SoundSynth.variants(Sound.STEP_STONE) == 4 && SoundSynth.variants(Sound.CLICK) == 1
                        && SoundSynth.variants(Sound.PICKUP) == 1, "");
        check("varianty zni ruzne", distinct, "");
        check("kazda varianta je slysitelna a stejne dlouha", audible && sameLength, "");
        check("varianta 0 je puvodni zvuk",
                Arrays.equals(SoundSynth.synthesize(Sound.BREAK_WOOD, 0).samples(),
                        SoundSynth.synthesize(Sound.BREAK_WOOD).samples()), "");

        // ---------- ze souboru ----------
        Path dir = Files.createTempDirectory("mc-variants");
        try {
            check("bez souboru jsou varianty syntetizovane",
                    SoundLibrary.variants(Sound.STEP_WOOD, dir).size() == 4, "");

            Files.write(dir.resolve("step_wood_2.wav"), wavFile(1, 1, 8000, 16, new byte[20], false));
            Files.write(dir.resolve("step_wood_5.wav"), wavFile(1, 1, 8000, 16, new byte[40], false));
            Files.write(dir.resolve("step_wood_3.wav"), "poskozeny".getBytes());
            List<Wav.Pcm> found = SoundLibrary.variants(Sound.STEP_WOOD, dir);
            check("ocislovane soubory (i s mezerou, bez zakladniho) nahradi vsechny placeholdery",
                    found.size() == 2 && found.get(0).samples().length == 10 && found.get(1).samples().length == 20,
                    "" + found.size());

            Files.write(dir.resolve("step_wood.wav"), wavFile(1, 1, 8000, 16, new byte[60], false));
            found = SoundLibrary.variants(Sound.STEP_WOOD, dir);
            check("zakladni soubor je prvni, vadny se preskoci",
                    found.size() == 3 && found.get(0).samples().length == 30
                            && SoundLibrary.load(Sound.STEP_WOOD, dir).samples().length == 30, "" + found.size());

            Files.write(dir.resolve("step_wood_9.wav"), wavFile(1, 1, 8000, 16, new byte[20], false));
            check("vic nez MAX_VARIANTS cisel se necte",
                    SoundLibrary.variants(Sound.STEP_WOOD, dir).size() == 3, "");
        } finally {
            try (var files = Files.list(dir)) {
                for (Path p : files.toList()) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        }
    }

    static void library() throws IOException {
        Path dir = Files.createTempDirectory("mc-sounds");

        try {
            Wav.Pcm synth = SoundLibrary.load(Sound.BREAK_STONE, dir);
            check("bez souboru hraje syntetizovany placeholder",
                    Arrays.equals(synth.samples(), SoundSynth.synthesize(Sound.BREAK_STONE).samples()), "");

            // Skutecna "nahravka": 1000 vzorku ticha se 44,1 kHz, ve stereu.
            byte[] recording = wavFile(1, 2, 44100, 16, new byte[4000], false);
            Files.write(dir.resolve("break_stone.wav"), recording);

            Wav.Pcm replaced = SoundLibrary.load(Sound.BREAK_STONE, dir);
            check("soubor sounds/<jmeno>.wav placeholder nahradi",
                    replaced.sampleRate() == 44100 && replaced.samples().length == 1000, "");
            check("a ostatni zvuky zustanou syntetizovane",
                    Arrays.equals(SoundLibrary.load(Sound.BREAK_EARTH, dir).samples(),
                            SoundSynth.synthesize(Sound.BREAK_EARTH).samples()), "");

            Files.write(dir.resolve("place_wood.wav"), "poskozeny soubor".getBytes());
            check("poskozeny soubor hru nepolozi - hraje placeholder",
                    Arrays.equals(SoundLibrary.load(Sound.PLACE_WOOD, dir).samples(),
                            SoundSynth.synthesize(Sound.PLACE_WOOD).samples()), "");

            // BUG: delka bloku kolem Integer.MAX_VALUE pretekla v int, kontrola
            // prosla a decode hodil IndexOutOfBoundsException - SoundLibrary ji
            // nechytal a SoundEngine.open() pak vypnul VSECHNY zvuky.
            for (String tag : new String[]{"LIST", "fmt ", "data"}) {
                ByteBuffer hostile = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
                hostile.put("RIFF".getBytes()).putInt(32).put("WAVE".getBytes());
                hostile.put(tag.getBytes()).putInt(0x7FFFFFF0).put(new byte[20]);

                boolean threw = false;
                Wav.Pcm decoded = null;
                try {
                    decoded = Wav.decode(hostile.array());
                } catch (RuntimeException e) {
                    threw = true;
                }
                check("blok " + tag.trim() + " s obri delkou: decode nehodi vyjimku", !threw, "");
                check("blok " + tag.trim() + " s obri delkou: je to nepodporovany soubor (null)",
                        !threw && (decoded == null || tag.equals("data")), "");

                Files.write(dir.resolve("step_earth.wav"), hostile.array());
                check("blok " + tag.trim() + " s obri delkou: hraje placeholder, zbytek zvuku zije",
                        SoundLibrary.load(Sound.STEP_EARTH, dir) != null, "");
            }

            // Obri soubor (omylem pojmenovana dlouha nahravka) se ani nezkusi precist.
            Path huge = dir.resolve("break_wood.wav");
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(huge.toFile(), "rw")) {
                raf.setLength(SoundLibrary.MAX_FILE_BYTES + 1);   // ridky soubor, na disku skoro nic
            }
            check("soubor nad strop velikosti hraje placeholder (driv OutOfMemoryError)",
                    Arrays.equals(SoundLibrary.load(Sound.BREAK_WOOD, dir).samples(),
                            SoundSynth.synthesize(Sound.BREAK_WOOD).samples()), "");
        } finally {
            try (var files = Files.list(dir)) {
                for (Path p : files.toList()) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        }
    }
}
