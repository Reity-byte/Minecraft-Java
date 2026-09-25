package mc;

/**
 * Rozšíření zorného pole při sprintu a letu, jako v Minecraftu.
 *
 * ---------------------------------------------------------------------------
 * Při sprintu se FOV zvětší o 15 %, v letu o 10 % (sprint v letu obojí
 * naráz). Obraz se tím na okrajích "roztáhne" a rychlost je víc cítit.
 * Násobek se k cíli dotahuje o polovinu rozdílu za tick (Minecraft), takže
 * rozjezd i zastavení jsou plynulé.
 *
 * ⚠️ Sprint se bere ze SKUTEČNÉHO pohybu, ne z klávesy: sprint do zdi nebo
 * s Ctrl na místě zorné pole neroztáhne.
 *
 * Mění se jen pohled na svět; ruka v první osobě zůstává na FOV z nastavení
 * (Minecraft to má stejně), jinak by se při sprintu zmenšovala.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL.
 */
public final class FovEffect {

    static final float SPRINT_BOOST = 1.15f;
    static final float FLYING_BOOST = 1.10f;

    /** Pod touhle vodorovnou rychlostí (bloky/s) se nesprintuje, jen drží klávesa. */
    static final float MIN_SPRINT_SPEED = 1f;

    /** Kolik z rozdílu zbude po sekundě: Minecraft 0,5 za tick, 20 ticků. */
    static final float SMOOTHING = (float) Math.pow(0.5, 20);

    private float multiplier = 1f;

    /** Cílový násobek FOV pro tenhle stav pohybu. */
    static float target(boolean sprintHeld, float horizontalSpeed, boolean flying)
    {
        boolean sprinting = sprintHeld && horizontalSpeed >= MIN_SPRINT_SPEED;
        return (flying ? FLYING_BOOST : 1f) * (sprinting ? SPRINT_BOOST : 1f);
    }

    /** Dotáhne násobek k cíli; enabled = false (Options) ho vrací k 1. */
    public void update(float dt, boolean sprintHeld, float horizontalSpeed, boolean flying, boolean enabled)
    {
        if(dt <= 0f)
        {
            return;
        }

        float goal = enabled ? target(sprintHeld, horizontalSpeed, flying) : 1f;
        multiplier += (goal - multiplier) * (1f - (float) Math.pow(SMOOTHING, dt));
    }

    public float multiplier()
    {
        return multiplier;
    }

    /** Okamžitě zpět na 1 - nový svět nezačne s roztaženým pohledem. */
    public void reset()
    {
        multiplier = 1f;
    }
}
