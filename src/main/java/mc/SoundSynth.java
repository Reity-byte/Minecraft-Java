package mc;

/**
 * Procedurálně syntetizované placeholder zvuky.
 *
 * ---------------------------------------------------------------------------
 * Stejná filozofie jako Textures.blockAtlasPixels(): žádné soubory, jen pár
 * řádků kódu, které zvuk spočítají. Výsledek je obyčejný soubor WAV v paměti,
 * takže se dá kdykoliv nahradit skutečnou nahrávkou - viz SoundLibrary.
 *
 * Každý zvuk je druh x materiál:
 *
 *   druh      délka a doznívání - krok je krátké ťuknutí, rozbití delší
 *             praskot, položení něco mezi
 *   materiál  z čeho se zvuk skládá - hlína je tlumený šum se zrnitostí,
 *             kámen ostřejší šum s nízkým cvaknutím, dřevo tlumený tón
 *             s klepnutím na začátku, listí vysoký šum s pomalým náběhem
 *
 * Šum je z HASHE pozice vzorku, ne z generátoru náhodných čísel - zvuk vyjde
 * pokaždé stejný, stejně jako textury.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na OpenAL.
 */
public final class SoundSynth {

    /** Na krátké efekty stačí. Poloviční data proti 44,1 kHz. */
    public static final int SAMPLE_RATE = 22050;

    /** Náběh a doběh obálky. Bez nich by zvuk začínal a končil lupnutím. */
    private static final float ATTACK = 0.002f;
    private static final float FADE_OUT = 0.004f;

    private SoundSynth() {}

    /** Zvuk jako kompletní soubor WAV. */
    public static byte[] wav(Sound sound)
    {
        return Wav.encode(synthesize(sound));
    }

    public static Wav.Pcm synthesize(Sound sound)
    {
        // Délka, doznívání (časová konstanta exponenciály) a špička podle druhu.
        return switch(sound.kind)
        {
            case STEP  -> render(0.09f, 0.022f, attackOf(sound), 0.55f, sound.ordinal(), timbreOf(sound));
            case BREAK -> render(0.24f, 0.060f, attackOf(sound), 0.90f, sound.ordinal(), timbreOf(sound));
            case PLACE -> render(0.13f, 0.030f, attackOf(sound), 0.80f, sound.ordinal(), timbreOf(sound));
            case CLICK -> render(0.05f, 0.012f, ATTACK, 0.6f, sound.ordinal(), SoundSynth::click);
            case PICKUP -> render(0.10f, 0.030f, ATTACK, 0.7f, sound.ordinal(), SoundSynth::pop);
        };
    }

    /** Listí se rozšustí pomalu, ostatní začínají úderem. */
    private static float attackOf(Sound sound)
    {
        return sound.material == Sound.Material.PLANT ? 0.015f : ATTACK;
    }

    // ------------------------------------------------------------------
    // barvy materiálů
    // ------------------------------------------------------------------

    /** Surový signál materiálu ve vzorku i (před obálkou). Stav filtrů nese Filters. */
    private interface Timbre {
        float sample(int i, float t, int seed, Filters f);
    }

    /** Stav jednopólových filtrů jednoho zvuku. */
    private static final class Filters {
        float low1, low2, high;
    }

    private static Timbre timbreOf(Sound sound)
    {
        return switch(sound.material)
        {
            case EARTH -> SoundSynth::earth;
            case STONE -> SoundSynth::stone;
            case WOOD  -> SoundSynth::wood;
            case PLANT -> SoundSynth::plant;
        };
    }

    /**
     * Hlína: šum přes dolní propust ~800 Hz, s hlasitostí rozsekanou na
     * zrnka po ~3 ms. Zrnitost dělá "křupnutí" místo syčení.
     */
    private static float earth(int i, float t, int seed, Filters f)
    {
        float grain = 0.45f + 0.55f * unit(hash(i / 64, seed + 1));
        f.low1 = lowPass(f.low1, noise(i, seed) * grain, 800f);
        f.low2 = lowPass(f.low2, f.low1, 1600f);
        return f.low2;
    }

    /** Kámen: ostřejší šum (~3,5 kHz) a pod ním rychle dozvívající cvaknutí 140 Hz. */
    private static float stone(int i, float t, int seed, Filters f)
    {
        f.low1 = lowPass(f.low1, noise(i, seed), 3500f);
        float knock = (float) (Math.sin(2 * Math.PI * 140f * t) * Math.exp(-t / 0.015f));
        return f.low1 + 0.6f * knock;
    }

    /** Dřevo: tlumený tón 200 Hz s alikvótou a krátké klepnutí šumem na začátku. */
    private static float wood(int i, float t, int seed, Filters f)
    {
        float tone = (float) (Math.sin(2 * Math.PI * 200f * t) + 0.5 * Math.sin(2 * Math.PI * 410f * t));
        float tap = t < 0.008f ? noise(i, seed) : 0f;
        f.low1 = lowPass(f.low1, 0.6f * tone + tap, 2000f);
        return f.low1;
    }

    /** Listí: jen vysoké frekvence šumu - horní propust jako šum minus jeho dolní propust. */
    private static float plant(int i, float t, int seed, Filters f)
    {
        float n = noise(i, seed);
        f.low1 = lowPass(f.low1, n, 1500f);
        return n - f.low1;
    }

    /**
     * Sebrání položky: krátký tón, jehož výška STOUPÁ (600 -> 1400 Hz za
     * desetinu vteřiny). Stoupání je to, co z pípnutí dělá "pop" - a odliší ho
     * od kliknutí v menu, které má výšku pevnou.
     */
    private static float pop(int i, float t, int seed, Filters f)
    {
        // Fáze = integrál frekvence 600 + 8000 t, tedy 600 t + 4000 t².
        double phase = 2 * Math.PI * (600.0 * t + 4000.0 * t * t);
        return (float) Math.sin(phase);
    }

    /** Kliknutí v UI: krátké pípnutí 1,4 kHz s alikvótou. */
    private static float click(int i, float t, int seed, Filters f)
    {
        return (float) (Math.sin(2 * Math.PI * 1400f * t) + 0.3 * Math.sin(2 * Math.PI * 2800f * t));
    }

    // ------------------------------------------------------------------
    // skládání
    // ------------------------------------------------------------------

    /**
     * Spočítá zvuk: signál barvy x obálka (lineární náběh, exponenciální
     * doznívání, lineární doběh do nuly) a znormuje ho na danou špičku.
     */
    private static Wav.Pcm render(float seconds, float decay, float attack, float peak,
                                  int seed, Timbre timbre)
    {
        int count = Math.round(seconds * SAMPLE_RATE);
        float[] signal = new float[count];
        Filters filters = new Filters();

        float loudest = 0f;

        for(int i = 0; i < count; i++)
        {
            float t = i / (float) SAMPLE_RATE;
            float left = seconds - t;

            float envelope = (float) Math.exp(-t / decay)
                    * Math.min(1f, t / attack)
                    * Math.min(1f, left / FADE_OUT);

            signal[i] = timbre.sample(i, t, seed, filters) * envelope;
            loudest = Math.max(loudest, Math.abs(signal[i]));
        }

        // Normování na stejnou špičku - jinak by materiály vyšly různě hlasité
        // jen podle toho, kolik energie filtr zrovna propustí.
        float scale = loudest > 0f ? peak / loudest : 0f;
        short[] samples = new short[count];

        for(int i = 0; i < count; i++)
        {
            samples[i] = (short) Math.round(signal[i] * scale * Short.MAX_VALUE);
        }

        return new Wav.Pcm(samples, SAMPLE_RATE);
    }

    /** Jednopólová dolní propust s mezní frekvencí cutoff. */
    private static float lowPass(float previous, float input, float cutoff)
    {
        float k = (float) (1 - Math.exp(-2 * Math.PI * cutoff / SAMPLE_RATE));
        return previous + k * (input - previous);
    }

    /** Šum -1 až 1, čistá funkce indexu vzorku a semínka. */
    private static float noise(int i, int seed)
    {
        return unit(hash(i, seed)) * 2f - 1f;
    }

    private static float unit(int hash)
    {
        return (hash & 0xFFFF) / 65535f;
    }

    /** Stejný rozhoz bitů jako v Textures. */
    private static int hash(int x, int y)
    {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) >>> 1;
    }
}
