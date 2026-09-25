package mc;

/**
 * Jedna pec: tři sloty (surovina, palivo, výsledek) a průběh tavení.
 *
 * ---------------------------------------------------------------------------
 * Pravidla Minecraftu, přepočtená z ticků na vteřiny:
 *
 *   - kus se taví COOK_SECONDS (10 s)
 *   - palivo se zapálí, jen když JE CO TAVIT (surovina má recept a výsledek
 *     se vejde do výstupu) - pec nepálí palivo naprázdno
 *   - hořící palivo dohoří, i když tavit přestane být co; tavení bez ohně
 *     stojí a postup pomalu klesá (dvakrát rychleji, než rostl)
 *   - výsledek se přidá k výstupu, jen když je to tatáž věc a vejde se
 *
 * ⚠️ Delší dt se zpracuje po krocích (nejvýš STEP), jinak by frame po
 * zaseknutí přeskočil hranici paliva a pec by "tavila na dluh".
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL.
 */
public final class FurnaceState {

    public static final int INPUT = 0, FUEL = 1, OUTPUT = 2;

    /** Jak dlouho se taví jeden kus. */
    public static final float COOK_SECONDS = 10f;

    /** Nejdelší krok simulace - kratší než nejkratší palivo (klacek 5 s). */
    static final float STEP = 0.5f;

    /** Sloty - Container, aby šly rovnou do obrazovky pece jako každý jiný. */
    public final Container slots = new Container(3);

    float burnLeft = 0f;
    float burnTotal = 0f;
    float cook = 0f;

    public boolean isBurning()
    {
        return burnLeft > 0f;
    }

    /** Postup tavení 0 až 1 - šipka na obrazovce. */
    public float progress()
    {
        return Math.min(1f, cook / COOK_SECONDS);
    }

    /** Kolik ohně zbývá 0 až 1 - plamínek na obrazovce. */
    public float flame()
    {
        return burnTotal > 0f ? Math.max(0f, burnLeft / burnTotal) : 0f;
    }

    /** Dá se teď tavit: surovina má recept a výsledek se vejde do výstupu? */
    boolean canSmelt()
    {
        ItemStack result = Smelting.resultOf(slots.get(INPUT));

        if(result.isEmpty())
        {
            return false;
        }

        ItemStack out = slots.get(OUTPUT);
        return out.isEmpty() || (out.sameItem(result) && out.count() + result.count() <= out.maxCount());
    }

    /** Posune pec o dt vteřin. */
    public void update(float dt)
    {
        while(dt > 0f)
        {
            float step = Math.min(dt, STEP);
            tick(step);
            dt -= step;
        }
    }

    private void tick(float dt)
    {
        boolean smeltable = canSmelt();

        // Zapálit další kus paliva - jen když je co tavit.
        if(burnLeft <= 0f && smeltable)
        {
            float seconds = Fuel.seconds(slots.get(FUEL).id());

            if(seconds > 0f)
            {
                slots.removeOne(FUEL);
                burnLeft = burnTotal = seconds;
            }
        }

        if(burnLeft > 0f)
        {
            burnLeft = Math.max(0f, burnLeft - dt);

            if(smeltable)
            {
                cook += dt;

                if(cook >= COOK_SECONDS)
                {
                    cook -= COOK_SECONDS;
                    finishOne();
                }
            }
            else
            {
                cook = 0f;
            }
        }
        else
        {
            cook = Math.max(0f, cook - 2f * dt);
        }
    }

    private void finishOne()
    {
        ItemStack result = Smelting.resultOf(slots.get(INPUT));
        ItemStack out = slots.get(OUTPUT);

        slots.removeOne(INPUT);
        slots.set(OUTPUT, out.isEmpty() ? result : out.plus(result.count()));

        // Došla surovina, nebo je výstup plný: postup se nezačíná "na dluh".
        if(!canSmelt())
        {
            cook = 0f;
        }
    }
}
