package mc;

/**
 * Setrvačnost ruky v první osobě: při otočení myší se ruka o kousek opozdí
 * a pak dožene pohled.
 *
 * ---------------------------------------------------------------------------
 * Z Minecraftu (ItemRenderer, renderArmPitch/renderArmYaw): ruka má vlastní
 * yaw a pitch, které se k pohledu dotahují o polovinu rozdílu za tick. Ruka
 * se pak natočí kolem oka o LAG_FACTOR rozdílu - otočení doprava ji na
 * chvilku nechá vlevo, pohled nahoru ji stáhne dolů.
 *
 * ⚠️ Yaw je balený 0-360 (Camera.wrapYaw), takže rozdíl se počítá přes šev
 * nejkratší cestou - otočka přes 359 -> 0 nesmí ruku protočit dokola.
 *
 * Velký skok (načtení světa, teleport) ruka nedotahuje, rovnou skočí -
 * jinak by se po načtení hodnou chvíli natáčela ze starého směru.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL.
 */
public final class HandSway {

    /** Kolik z rozdílu zbude po sekundě: Minecraft 0,5 za tick, 20 ticků. */
    static final float SMOOTHING = (float) Math.pow(0.5, 20);

    /** Jakou část zpoždění se ruka natočí. Minecraft má 0,1; tady víc, ať je to vidět. */
    static final float LAG_FACTOR = 0.2f;

    /** Nejvíc stupňů natočení ruky - rychlé švihnutí myší ji neodhodí z obrazu. */
    static final float MAX_TILT = 8f;

    /** Rozdíl, nad kterým ruka nedotahuje, ale skočí (načtení světa). */
    static final float SNAP = 90f;

    private float yaw = Float.NaN;
    private float pitch = 0f;

    /** Posune ruku k pohledu o frame. */
    public void update(float dt, float viewYaw, float viewPitch)
    {
        if(Float.isNaN(yaw) || Math.abs(yawDelta(viewYaw, yaw)) > SNAP || Math.abs(viewPitch - pitch) > SNAP)
        {
            yaw = viewYaw;
            pitch = viewPitch;
            return;
        }

        if(dt <= 0f)
        {
            return;
        }

        float k = 1f - (float) Math.pow(SMOOTHING, dt);
        yaw = Camera.wrapYaw(yaw + yawDelta(viewYaw, yaw) * k);
        pitch += (viewPitch - pitch) * k;
    }

    /** Natočení ruky kolem svislé osy (stupně); kladné = doleva, když se hráč točí doprava. */
    public float tiltYaw(float viewYaw)
    {
        return Float.isNaN(yaw) ? 0f : clampTilt(yawDelta(viewYaw, yaw) * LAG_FACTOR);
    }

    /** Natočení ruky kolem vodorovné osy (stupně); záporné = dolů, když se hráč dívá nahoru. */
    public float tiltPitch(float viewPitch)
    {
        return Float.isNaN(yaw) ? 0f : clampTilt(-(viewPitch - pitch) * LAG_FACTOR);
    }

    /** Rozdíl a - b nejkratší cestou přes šev 360, v intervalu -180 až 180. */
    static float yawDelta(float a, float b)
    {
        float d = Camera.wrapYaw(a - b);
        return d > 180f ? d - 360f : d;
    }

    private static float clampTilt(float degrees)
    {
        return Math.max(-MAX_TILT, Math.min(MAX_TILT, degrees));
    }
}
