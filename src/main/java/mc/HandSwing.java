package mc;

/**
 * Máchnutí rukou.
 *
 * ⚠️ Máchnutí se nepřerušuje. Jednou spuštěné doběhne až do konce a teprve
 * pak může začít další - kdyby se restartovalo při každém framu kopání, ruka
 * by se roztřásla na místě místo aby se rozmáchla.
 *
 * Nesahá na GL, takže jde otestovat headless.
 */
public class HandSwing {

    /** Jak dlouho trvá jedno máchnutí. Zhruba jako v Minecraftu. */
    private static final float DURATION = 0.25f;

    private float progress = 0f;
    private boolean running = false;

    /** Spustí máchnutí, pokud zrovna žádné neběží. */
    public void trigger()
    {
        if(!running)
        {
            running = true;
            progress = 0f;
        }
    }

    public void update(float dt)
    {
        if(!running)
        {
            return;
        }

        progress += dt / DURATION;

        if(progress >= 1f)
        {
            running = false;
            progress = 0f;
        }
    }

    public boolean isSwinging()
    {
        return running;
    }

    /** Postup máchnutí 0 až 1. V klidu nula. */
    public float progress()
    {
        return running ? progress : 0f;
    }

    // ------------------------------------------------------------------
    // ⚠️ DVĚ RŮZNĚ ZAKŘIVENÉ KŘIVKY, ne jedna.
    //
    // Tohle dělá ten minecraftí švih. Kdyby se všechno hýbalo podle jedné
    // křivky, je z toho jen houpnutí sem a tam. Rychlá vystřelí hned na
    // začátku a pomalu se vrací - řídí hlavní zdvih ruky. Pomalá se rozjede
    // až v druhé půlce - řídí odklon do strany, takže se ruka nevrací
    // po stejné dráze, ale opíše oblouk.
    // ------------------------------------------------------------------

    /** Rychlá křivka: sin(sqrt(p) * pi). Vrchol brzy, dlouhý doběh. */
    public float fast()
    {
        return running ? (float) Math.sin(Math.sqrt(progress) * Math.PI) : 0f;
    }

    /** Pomalá křivka: sin(p^2 * pi). Dlouho skoro nic, pak rychlý vrchol. */
    public float slow()
    {
        return running ? (float) Math.sin(progress * progress * Math.PI) : 0f;
    }
}
