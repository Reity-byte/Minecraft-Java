package mc;

/**
 * Kdy zazní krok.
 *
 * ---------------------------------------------------------------------------
 * Krok se počítá z UJITÉ VZDÁLENOSTI, ne z času: každých STRIDE bloků jeden.
 * Interval mezi kroky je tak přesně STRIDE / rychlost (viz interval()) a sám
 * se přizpůsobí zrychlení i zpomalení uprostřed kroku - časovač by musel
 * řešit, co s rozběhnutým krokem, když se změní rychlost.
 *
 * Vzdálenost se sčítá i ve vzduchu, ale krok zazní jen na zemi. Po skoku
 * dopředu proto zazní hned při dopadu - stejně jako v Minecraftu, odkud je
 * i délka kroku (distanceWalkedModified roste o 0,6 na blok, krok každou
 * jedničku, tedy každých 1/0,6 bloku).
 * ---------------------------------------------------------------------------
 *
 * Nesahá na OpenAL.
 */
public final class Footsteps {

    /** Kolik bloků ujde hráč mezi dvěma kroky. */
    public static final float STRIDE = 1f / 0.6f;

    private float travelled = 0f;

    /**
     * Posune počítání o frame. distance je skutečný vodorovný posun hráče.
     * Vrací true právě ve framu, kdy má zaznít krok.
     */
    public boolean update(float distance, boolean onGround)
    {
        travelled += distance;

        if(travelled < STRIDE || !onGround)
        {
            return false;
        }

        // Zbytek, ne nula: kroky tak drží rytmus přesně podle vzdálenosti.
        // Po dlouhém letu se nasbíraná vzdálenost zahodí najednou - dopad
        // je jeden krok, ne salva.
        travelled %= STRIDE;
        return true;
    }

    /** Za jak dlouho zazní další krok při dané vodorovné rychlosti (s). */
    public static float interval(float speed)
    {
        return speed > 0f ? STRIDE / speed : Float.POSITIVE_INFINITY;
    }
}
