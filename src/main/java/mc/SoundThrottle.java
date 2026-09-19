package mc;

import java.util.Arrays;

/**
 * Hlídá, aby se zvuk jednoho druhu neopakoval rychleji než jeho cooldown.
 *
 * Typický problém je rychlé kopání: pochodeň se rozbije za 0,05 s, listí za
 * 0,2 s, a přejíždění myší přes řadu takových bloků by bez omezení
 * vyrobilo "kulomet" stejných zvuků. Co přijde během cooldownu, se zahodí -
 * neodkládá se, protože zvuk zahraný opožděně by k ničemu nepatřil.
 *
 * Čas se předává zvenku (sekundy), takže jde otestovat bez hodin.
 * Nesahá na OpenAL.
 */
public final class SoundThrottle {

    private final double[] lastPlayed = new double[Sound.Kind.values().length];

    public SoundThrottle()
    {
        Arrays.fill(lastPlayed, Double.NEGATIVE_INFINITY);
    }

    /**
     * Smí zvuk tohoto druhu zaznít teď? Když ano, zapamatuje si čas -
     * volá se tedy až těsně před přehráním, ne "na zkoušku".
     */
    public boolean allow(Sound.Kind kind, double now)
    {
        if(now - lastPlayed[kind.ordinal()] < kind.cooldown)
        {
            return false;
        }

        lastPlayed[kind.ordinal()] = now;
        return true;
    }
}
