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

    /**
     * Kolik variant placeholderu zvuk má. Zvuky bloků jsou ze šumu, takže
     * jiné semínko dá opravdu jiný zvuk - 4 varianty, jako mívá Minecraft.
     * Kliknutí a sebrání jsou čisté tóny: semínko by je nezměnilo, a v UI
     * má kliknutí znít pořád stejně.
     */
    public static int variants(Sound sound)
    {
        return sound.material != null ? 4 : 1;   // smyčky, kliknutí, sebrání, kapka: 1
    }

    public static Wav.Pcm synthesize(Sound sound)
    {
        return synthesize(sound, 0);
    }

    /**
     * Varianta placeholderu. Liší se semínkem šumu a trochu doznívá
     * (±12 %); délka zůstává, ať druhy drží pořadí krok < položení < rozbití.
     * Varianta 0 je přesně původní zvuk.
     */
    public static Wav.Pcm synthesize(Sound sound, int variant)
    {
        int seed = sound.ordinal() + variant * VARIANT_SEED;
        float d = variant == 0 ? 1f : 1f + 0.12f * (float) Math.sin(variant * 2.3);

        // Délka, doznívání (časová konstanta exponenciály) a špička podle druhu.
        return switch(sound.kind)
        {
            case STEP  -> render(0.09f, 0.022f * d, attackOf(sound), 0.55f, seed, timbreOf(sound));
            case BREAK -> render(0.24f, 0.060f * d, attackOf(sound), 0.90f, seed, timbreOf(sound));
            case PLACE -> render(0.13f, 0.030f * d, attackOf(sound), 0.80f, seed, timbreOf(sound));
            case CLICK -> render(0.05f, 0.012f, ATTACK, 0.6f, seed, SoundSynth::click);
            case PICKUP -> render(0.10f, 0.030f, ATTACK, 0.7f, seed, SoundSynth::pop);
            case DRIP   -> render(0.12f, 0.035f, ATTACK, 0.6f, seed, SoundSynth::drip);
            case ITEM_BREAK -> render(0.22f, 0.050f, ATTACK, 0.85f, seed, SoundSynth::snap);
            case AMBIENT -> switch(sound)
            {
                case AMBIENT_WIND  -> renderLoop(0.5f, seed, SoundSynth::wind);
                case AMBIENT_CAVE  -> renderLoop(0.6f, seed, SoundSynth::cave);
                default            -> renderLoop(0.5f, seed, SoundSynth::water);
            };
        };
    }

    // ------------------------------------------------------------------
    // smyčky prostředí
    // ------------------------------------------------------------------

    /**
     * Délka smyčky. Všechny pomalé modulace mají CELÝ počet period na
     * smyčku, takže navazují; šum navazuje díky prolnutí (renderLoop).
     */
    static final float LOOP_SECONDS = 6f;

    /** Jak dlouhý kus konce se prolne do začátku. */
    private static final float LOOP_FADE = 0.5f;

    /** sin s k celými periodami za smyčku - navazuje přes konec smyčky. */
    private static float cycle(float t, int k, float offset)
    {
        return (float) Math.sin(2 * Math.PI * k * t / LOOP_SECONDS + offset);
    }

    /**
     * Smyčka bez obálky: spočítá o LOOP_FADE víc a ten přesah prolne do
     * začátku (sqrt váhy, ať šum v půlce prolnutí nezeslábne). Poslední
     * vzorek pak plynule navazuje na první - bez lupnutí při každém opakování.
     *
     * ⚠️ Jen varianta 0: smyčka hraje pořád tatáž, varianty by nebylo kdy
     * vystřídat (SoundSynth.variants vrací pro smyčky 1).
     */
    private static Wav.Pcm renderLoop(float peak, int seed, Timbre timbre)
    {
        int count = Math.round(LOOP_SECONDS * SAMPLE_RATE);
        int fade = Math.round(LOOP_FADE * SAMPLE_RATE);
        float[] raw = new float[count + fade];
        Filters filters = new Filters();

        for(int i = 0; i < raw.length; i++)
        {
            raw[i] = timbre.sample(i, i / (float) SAMPLE_RATE, seed, filters);
        }

        float[] signal = new float[count];
        float loudest = 0f;

        for(int i = 0; i < count; i++)
        {
            signal[i] = raw[i];

            if(i < fade)
            {
                float w = i / (float) fade;
                signal[i] = raw[count + i] * (float) Math.sqrt(1 - w) + raw[i] * (float) Math.sqrt(w);
            }

            loudest = Math.max(loudest, Math.abs(signal[i]));
        }

        float scale = loudest > 0f ? peak / loudest : 0f;
        short[] samples = new short[count];

        for(int i = 0; i < count; i++)
        {
            samples[i] = (short) Math.round(signal[i] * scale * Short.MAX_VALUE);
        }

        return new Wav.Pcm(samples, SAMPLE_RATE);
    }

    /** Síla poryvu 0 až 1 - tři pomalé vlny s celými periodami na smyčku. */
    private static float gust(float t)
    {
        return 0.5f + 0.25f * cycle(t, 1, 0f) + 0.15f * cycle(t, 2, 1.3f) + 0.1f * cycle(t, 5, 2.1f);
    }

    /**
     * Vítr: šum přes dolní propust, jejíž mez jede s poryvem (silnější vítr
     * = vyšší hučení), bez nejhlubších basů, hlasitost podle poryvu.
     */
    private static float wind(int i, float t, int seed, Filters f)
    {
        float g = gust(t);
        float cutoff = 200f + 500f * g;
        f.low1 = lowPass(f.low1, noise(i, seed), cutoff);
        f.low2 = lowPass(f.low2, f.low1, cutoff * 1.5f);
        f.high = lowPass(f.high, f.low2, 60f);
        return (f.low2 - f.high) * (0.3f + 0.7f * g);
    }

    /**
     * Jeskyně: hluboké hučení - šum pod 90 Hz a slabý tón 55 Hz (330 period
     * za smyčku, navazuje), který pomalu dýchá.
     */
    private static float cave(int i, float t, int seed, Filters f)
    {
        f.low1 = lowPass(f.low1, noise(i, seed), 90f);
        f.low2 = lowPass(f.low2, f.low1, 90f);
        float drone = (float) Math.sin(2 * Math.PI * 55f * t) * (0.7f + 0.3f * cycle(t, 1, 0f));
        return f.low2 * 6f + 0.15f * drone;
    }

    /**
     * Voda: pásmo šumu 400 až 1800 Hz (šplouchání), rozvlněné dvěma
     * rychlejšími vlnami - voda "bublá", nesyčí rovnoměrně.
     */
    private static float water(int i, float t, int seed, Filters f)
    {
        f.low1 = lowPass(f.low1, noise(i, seed), 1800f);
        f.high = lowPass(f.high, f.low1, 400f);
        float ripple = 0.55f + 0.25f * cycle(t, 13, 0f) + 0.2f * cycle(t, 29, 0.7f);
        return (f.low1 - f.high) * ripple;
    }

    /**
     * Prasknutí nástroje: ostré lupnutí šumu a pod ním tón, který rychle
     * padá (900 -> 300 Hz) - "křup a cink", jako když se zlomí dřevo s kovem.
     */
    private static float snap(int i, float t, int seed, Filters f)
    {
        f.low1 = lowPass(f.low1, noise(i, seed), 4000f);
        float crack = t < 0.03f ? f.low1 * 1.5f : f.low1 * 0.4f;
        double phase = 2 * Math.PI * (900.0 * t - 1360.0 * t * t);
        return crack + 0.5f * (float) Math.sin(phase);
    }

    /** Kapka: tón, jehož výška KLESÁ (1800 -> 1100 Hz) - obráceně než sebrání. */
    private static float drip(int i, float t, int seed, Filters f)
    {
        // Fáze = integrál frekvence 1800 - 6000 t.
        double phase = 2 * Math.PI * (1800.0 * t - 3000.0 * t * t);
        return (float) Math.sin(phase);
    }

    /** Odstup semínek variant - větší než počet zvuků, ať se nepotkají. */
    private static final int VARIANT_SEED = 7919;

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
