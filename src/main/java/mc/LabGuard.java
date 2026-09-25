package mc;

import java.util.ArrayList;
import java.util.List;

/**
 * Pojistka labu proti ztrátě rozepsané práce: úkon, který by něco zahodil,
 * napoprvé NEPROBĚHNE a jen se řekne proč; když se tentýž úkon zopakuje do
 * pár sekund, proběhne.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ PROČ "ZOPAKUJ", A NE DIALOG. Lab nemá žádné modální okno a zavírá se
 * z několika míst (Esc, klávesa labu, tlačítko Close v každém módu).
 * Opakování funguje pro všechna stejně a navazuje na to, co lab uměl už
 * dřív - Esc v rozepsaném bloku blok zruší, teprve další Esc zavře lab.
 *
 * Potvrzení platí jen pro TENTÝŽ úkon: varování před zavřením nepustí
 * přepnutí módu a naopak, jinak by se první klik jinam tvářil jako souhlas.
 * Úkon, u kterého není co ztratit, potvrzení zruší - z "varoval jsem před
 * minutou" se nesmí stát souhlas s něčím, před čím varování nepadlo.
 *
 * Bez GL, takže jde celé projít testem (LabModesTest).
 * ---------------------------------------------------------------------------
 */
final class LabGuard {

    /** Jak dlouho platí varování. Stejně dlouho visí hláška ve stavovém řádku. */
    static final float CONFIRM_SECONDS = 5f;

    /** Úkon, před kterým se naposledy varovalo, nebo null. */
    private String armed = null;
    private float left = 0f;

    /**
     * Smí úkon proběhnout?
     *
     * @param action jméno úkonu ("close", "mode 3") - potvrzuje se jen stejný
     * @param loses  co by úkon zahodil, nebo null, když nic
     * @return true = proveď; false = varování - volající řekne proč
     */
    boolean allow(String action, String loses)
    {
        if(loses == null)
        {
            armed = null;
            return true;
        }

        if(action.equals(armed) && left > 0f)
        {
            armed = null;
            return true;
        }

        armed = action;
        left = CONFIRM_SECONDS;
        return false;
    }

    void update(float dt)
    {
        if(left > 0f)
        {
            left = Math.max(0f, left - dt);

            if(left == 0f)
            {
                armed = null;
            }
        }
    }

    /**
     * Které módy mají neuloženou práci, jako "Keys, Biomes", nebo null.
     * Pořadí je pořadí v bočním panelu.
     */
    static String unsavedModes(List<LabMode> modes)
    {
        List<String> names = new ArrayList<>();

        for(LabMode mode : modes)
        {
            if(mode.unsaved())
            {
                names.add(mode.title());
            }
        }

        return names.isEmpty() ? null : String.join(", ", names);
    }
}
