package mc;

/**
 * Denní doba, síla slunce a barva oblohy.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ Noc se dělá ZTLUMENÍM SLUNEČNÍHO KANÁLU V SHADERU, ne přepočtem světla
 * ve světě. Uložené sluneční světlo je "kolik sem dosáhne obloha" a je pořád
 * stejné; jak silná obloha je, řekne až jeden uniform. Kdyby se přepočítávalo,
 * znamenal by každý západ slunce přestavbu všech meshů v dohledu.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, takže jde celé otestovat headless.
 */
public class DayCycle {

    /** Délka celého cyklu v sekundách. Polovina minecraftovského dne. */
    public static final float DAY_LENGTH = 600f;

    /**
     * Kolik z cyklu zabere den, noc a přechody. Součet musí dát 1.
     * Krátké přechody schválně - dlouhé svítání je hezké jednou, pak zdržuje.
     */
    private static final float DAY_PART   = 0.46f;
    private static final float DUSK_PART  = 0.05f;
    private static final float NIGHT_PART = 0.44f;

    /**
     * Jak silné je slunce v noci. Ne nula: úplná tma na povrchu znamená,
     * že hráč v noci nevidí vůbec nic a hra se zastaví.
     */
    private static final float NIGHT_SUN = 0.16f;

    /**
     * Nejnižší osvětlení, pod které se nejde dostat ani v neosvětlené jeskyni.
     * Jeskyně má být tmavá, ale ne černá plocha bez tvaru.
     */
    public static final float AMBIENT = 0.05f;

    // Barvy oblohy. Denní je ta původní, noční tmavě modrá.
    private static final float[] SKY_DAY   = {0.53f, 0.81f, 0.92f};
    private static final float[] SKY_NIGHT = {0.02f, 0.03f, 0.08f};

    /** Uplynulý čas v rámci cyklu, v sekundách. */
    private float time = DAY_LENGTH * 0.15f;   // start dopoledne, ne za svítání

    private final float[] skyColor = new float[3];

    public void advance(float dt)
    {
        time = (time + dt) % DAY_LENGTH;
    }

    /** Posune čas o zlomek cyklu. Pro ladění - jinak se na noc čeká minuty. */
    public void skip(float fraction)
    {
        time = (time + DAY_LENGTH * fraction) % DAY_LENGTH;
    }

    /** 0 o půlnoci, 1 v poledne. Tímhle se násobí sluneční kanál v shaderu. */
    public float daylight()
    {
        float phase = time / DAY_LENGTH;

        if(phase < DAY_PART)
        {
            return 1f;
        }

        if(phase < DAY_PART + DUSK_PART)
        {
            // soumrak
            return lerp(1f, NIGHT_SUN, (phase - DAY_PART) / DUSK_PART);
        }

        if(phase < DAY_PART + DUSK_PART + NIGHT_PART)
        {
            return NIGHT_SUN;
        }

        // svítání
        float t = (phase - DAY_PART - DUSK_PART - NIGHT_PART)
                / (1f - DAY_PART - DUSK_PART - NIGHT_PART);
        return lerp(NIGHT_SUN, 1f, t);
    }

    /**
     * Barva oblohy. Míchá se stejným činitelem jako síla slunce, takže obloha
     * a osvětlení terénu tmavnou zároveň - jinak by v noci svítilo modré nebe
     * nad černou krajinou.
     */
    public float[] skyColor()
    {
        float t = 1f - nightFactor();

        for(int i = 0; i < 3; i++)
        {
            skyColor[i] = lerp(SKY_NIGHT[i], SKY_DAY[i], t);
        }

        return skyColor;
    }

    /** 0 ve dne, 1 uprostřed noci. Tímhle se řídí viditelnost hvězd. */
    public float nightFactor()
    {
        float t = (daylight() - NIGHT_SUN) / (1f - NIGHT_SUN);
        return 1f - Math.max(0f, Math.min(1f, t));
    }

    /**
     * Otočení oblohy v radiánech. 0 znamená slunce v nadhlavníku.
     *
     * Odečítá se poledne, které leží uprostřed denní části cyklu - jinak by
     * slunce vycházelo v jinou dobu, než se rozsvěcí obloha.
     */
    public float skyAngle()
    {
        float phase = time / DAY_LENGTH;
        return (float) ((phase - DAY_PART / 2f) * Math.PI * 2);
    }

    /** Denní doba jako 0-24 hodin, pro ladicí výpis. */
    public float hours()
    {
        // Poledne uprostřed denní části, aby čísla odpovídala tomu, co je vidět.
        return (time / DAY_LENGTH * 24f + 6f) % 24f;
    }

    public boolean isNight()
    {
        return daylight() < (1f + NIGHT_SUN) / 2f;
    }

    private static float lerp(float a, float b, float t)
    {
        return a + (b - a) * Math.max(0f, Math.min(1f, t));
    }
}
