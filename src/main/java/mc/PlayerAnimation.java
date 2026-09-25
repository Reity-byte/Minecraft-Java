package mc;

/**
 * Animace postavy: chůze, klid a máchnutí rukou ve třetí osobě.
 *
 * ---------------------------------------------------------------------------
 * Vzorce jsou z Minecraftu (ModelBiped), přepočtené z ticků (20 za sekundu)
 * na sekundy. Stav jsou jen tři čísla:
 *
 *   fáze kroku   kde v cyklu chůze nohy jsou (radiány, roste pořád)
 *   rozmach      jak moc se končetiny rozmáchnou, 0 až 1 podle rychlosti
 *   čas          pro pohupování rukou v klidu
 *
 * ⚠️ Fáze roste ROZMACHEM, ne ujitou vzdáleností. Při chůzi to vyjde skoro
 * nastejno (rozmach je úměrný rychlosti), ale rozmach je shora omezený - takže
 * sprint ani let rychlostí 12 b/s nohy neroztočí do zběsilého cupitání.
 * Minecraft to dělá stejně.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL. Výpočet úhlu končetiny jsou statické čisté funkce, takže jde
 * otestovat bez okna i bez světa.
 */
public class PlayerAnimation {

    /** Fáze kroku za sekundu při plném rozmachu: 0,6662 rad za tick. */
    static final float PHASE_RATE = 0.6662f * 20f;

    /** Při jaké vodorovné rychlosti je rozmach plný: 0,25 bloku za tick. */
    static final float FULL_SWING_SPEED = 5f;

    /** Největší výkyv nohy a ruky při plném rozmachu (radiány). */
    static final float LEG_SWING = 1.4f;
    static final float ARM_SWING = 1.0f;

    /**
     * Kolik z rozdílu mezi rozmachem a cílem zbude po sekundě. Minecraft
     * dotahuje o 0,4 rozdílu za tick, tedy zbývá 0,6 za tick a 0,6^20 za
     * sekundu. Mocnina, ne násobek - musí to vyjít stejně při každém FPS.
     */
    static final float SWING_SMOOTHING = (float) Math.pow(0.6, 20);

    /**
     * Pohupování rukou v klidu: odklon od těla 0 až 0,1 rad a lehký kmit
     * dopředu a dozadu ±0,05 rad, každé s jinou periodou, aby se to neopakovalo
     * jako metronom. Běží i za chůze, jen tam zanikne ve velkém výkyvu.
     */
    static final float IDLE_SWAY = 0.05f;
    static final float IDLE_SWAY_RATE = 0.09f * 20f;
    static final float IDLE_DRIFT_RATE = 0.067f * 20f;

    /** Ruka s něčím v ruce je předsunutá o π/10 a za chůze máchá jen napůl. */
    static final float HOLD_ARM = (float) (-Math.PI / 10);

    /**
     * Máchnutí: stejné dvě křivky jako ruka v první osobě (HandSwing).
     * Rychlá zvedá ruku dopředu (80°, jako v HeldItemRenderer), pomalá ji
     * v druhé půlce stočí přes tělo (20°) - ruka tak opíše oblouk, ne kmit.
     */
    static final float SWING_RAISE = (float) Math.toRadians(80);
    static final float SWING_SWEEP = (float) Math.toRadians(20);

    private float phase = 0f;
    private float amount = 0f;
    private float time = 0f;

    // ------------------------------------------------------------------
    // stav
    // ------------------------------------------------------------------

    /**
     * Posune animaci o frame. distanceMoved je SKUTEČNÝ vodorovný posun hráče
     * za tenhle frame - chůze do zdi proto nohama nemáchá, i když se drží W.
     */
    public void update(float dt, float distanceMoved)
    {
        if(dt <= 0f)
        {
            return;
        }

        // ⚠️ OBĚ ČÍSLA SE BALÍ, nerostou donekonečna. Float ztrácí přesnost:
        // od time = 16384 (asi 4,5 h v jednom běhu) při 1000 FPS šlo
        // pohupování 1,95x rychleji a od 65 536 stálo úplně, protože dt pod
        // rozlišením se k času přestalo přičítat. Fáze nohou jde jen do cos(),
        // takže modulo 2 pí je beze švu. Čas pohánějí dvě nesoudělné
        // frekvence; balí se po celém počtu period pohupování, takže šev je
        // jen v nepatrném posunu driftu jednou za ~17 minut.
        time = (time + dt) % TIME_WRAP;

        float target = swingAmountFor(distanceMoved / dt);
        amount += (target - amount) * (1f - (float) Math.pow(SWING_SMOOTHING, dt));

        phase = (phase + amount * PHASE_RATE * dt) % TWO_PI;
    }

    private static final float TWO_PI = (float) (2 * Math.PI);

    /** 293 period pohupování (~1023 s): dost malé na přesnost floatu, beze švu v pohupování. */
    static final float TIME_WRAP = (float) (293 * 2 * Math.PI / IDLE_SWAY_RATE);

    public float phase()  { return phase; }
    public float amount() { return amount; }
    public float time()   { return time; }

    /**
     * Póza pro tenhle okamžik.
     *
     * @param pitchDegrees sklon pohledu z kamery; hlava ho kopíruje
     * @param swingFast    HandSwing.fast() - 0 mimo máchnutí
     * @param swingSlow    HandSwing.slow()
     * @param holdingItem  drží pravá ruka něco?
     */
    public PlayerPose pose(float pitchDegrees, float swingFast, float swingSlow, boolean holdingItem)
    {
        return pose(phase, amount, time, pitchDegrees, swingFast, swingSlow, holdingItem);
    }

    // ------------------------------------------------------------------
    // čisté funkce
    // ------------------------------------------------------------------

    /** Cílový rozmach pro danou vodorovnou rychlost: úměrně, nejvýš 1. */
    public static float swingAmountFor(float speed)
    {
        return Math.max(0f, Math.min(1f, speed / FULL_SWING_SPEED));
    }

    /**
     * Úhel pravé nohy. Levá je v opačné fázi, tedy s opačným znaménkem -
     * když jde jedna dopředu, druhá jde dozadu.
     */
    public static float legAngle(float phase, float amount)
    {
        return (float) Math.cos(phase) * LEG_SWING * amount;
    }

    /**
     * Úhel pravé ruky. Ruka jde PROTI noze na téže straně (pravá ruka
     * dopředu s levou nohou), přesně jako při skutečné chůzi.
     */
    public static float armAngle(float phase, float amount)
    {
        return -(float) Math.cos(phase) * ARM_SWING * amount;
    }

    /** Odklon rukou od těla v klidu, 0 až 2·IDLE_SWAY. */
    public static float idleSway(float time)
    {
        return (float) Math.cos(time * IDLE_SWAY_RATE) * IDLE_SWAY + IDLE_SWAY;
    }

    /** Kmit rukou dopředu a dozadu v klidu, ±IDLE_SWAY. */
    public static float idleDrift(float time)
    {
        return (float) Math.sin(time * IDLE_DRIFT_RATE) * IDLE_SWAY;
    }

    public static PlayerPose pose(float phase, float amount, float time, float pitchDegrees,
                                  float swingFast, float swingSlow, boolean holdingItem)
    {
        float leg = legAngle(phase, amount);
        float arm = armAngle(phase, amount);
        float sway = idleSway(time);
        float drift = idleDrift(time);

        float rightArmX = arm + drift;
        float leftArmX = -arm - drift;

        if(holdingItem)
        {
            rightArmX = rightArmX * 0.5f + HOLD_ARM;
        }

        // Máchnutí se přičítá AŽ NAKONEC, přes chůzi i držení - kope se
        // i za chůze a ruka se má rozmáchnout z místa, kde zrovna je.
        rightArmX -= swingFast * SWING_RAISE;
        float rightArmY = swingSlow * SWING_SWEEP;

        // Hlava kopíruje sklon pohledu. Kladný pitch kamery je nahoru, kladné
        // natočení kolem X v modelu sklání dolů - proto mínus.
        return new PlayerPose((float) -Math.toRadians(pitchDegrees),
                rightArmX, rightArmY, -sway,
                leftArmX, sway,
                leg, -leg);
    }
}
