package mc;

/**
 * Postup rozbíjení bloku.
 *
 * ---------------------------------------------------------------------------
 * Kopání není okamžité: každý blok má svou tvrdost a drží se, dokud se
 * nenaplní postup. Odtud i praskliny, které se na bloku objevují.
 *
 * ⚠️ Postup se váže na KONKRÉTNÍ BLOK, ne na stisknuté tlačítko. Jakmile
 * paprsek ukáže jinam - nebo se blok pod kurzorem změní - musí se začít
 * znovu. Bez toho by se dalo "nabít" kopání na měkké hlíně a jedním
 * přesunutím myši rozbít kámen.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, takže jde celé otestovat headless.
 */
public class Mining {

    /** Kolik stádií prasklin se rozlišuje. Stejně jako v Minecraftu. */
    public static final int STAGES = 10;

    private boolean active = false;
    private int x, y, z;
    private byte block = World.AIR;

    /** 0 až 1; při jedničce blok praskne. */
    private float progress = 0f;

    /**
     * Prodleva mezi dvěma bloky v creative, v sekundách - 5 ticků jako ve
     * vanille.
     *
     * ⚠️ BEZ NÍ ROZHODOVALO FPS. Okamžité kopání vracelo "praskl" v každém
     * framu s drženým tlačítkem, takže další frame paprsek trefil blok ZA
     * rozbitým a padl taky. Obyčejný klik (tlačítko dole 80-120 ms) tak
     * vykopal tunel: 6 bloků při 60 FPS, 8 (strop dosahu) při 240.
     */
    public static final float CREATIVE_DELAY = 0.25f;

    /**
     * Kolik ještě zbývá do dalšího rozbití v creative. Běží jen s drženým
     * tlačítkem; puštění ho vynuluje, takže každý nový klik rozbije hned.
     */
    private float cooldown = 0f;

    /**
     * Posune kopání o jeden frame. Vrací true právě ve framu, kdy blok praskl.
     *
     * hit smí být null (kurzor nemíří na nic) a held false (tlačítko není
     * stisknuté) - obojí kopání zruší.
     *
     * Bez módu se kope survival pravidly - tedy přesně jako dřív.
     */
    public boolean update(World world, float dt, boolean held, Raycaster.RaycastHit hit)
    {
        return update(world, dt, held, hit, GameMode.SURVIVAL);
    }

    /**
     * Totéž, ale s herním módem. V creative je kopání OKAMŽITÉ: postup skočí
     * rovnou na jedničku, takže blok praskne ve framu, kdy se na něj začne
     * mířit s drženým tlačítkem.
     *
     * ⚠️ Zkracuje se jenom čekání. Test na World.isTargetable() zůstává nad
     * touhle větví, takže vzduch ani voda se nerozbijí ani v creative -
     * creative pravidla neobchází, jen v nich nečeká.
     */
    public boolean update(World world, float dt, boolean held, Raycaster.RaycastHit hit,
                          GameMode mode)
    {
        return update(world, dt, held, hit, mode, World.AIR);
    }

    /**
     * Totéž s věcí v ruce (id z Items). Nástroj na materiál bloku kope
     * toolSpeed-krát rychleji (Items.miningSpeed); cokoliv jiného jako ruka.
     *
     * ⚠️ Rychlost se bere KAŽDÝ FRAME, ne při začátku kopání: přepnutí
     * slotu uprostřed kopání platí hned, postup se nenuluje - jako
     * v Minecraftu.
     */
    public boolean update(World world, float dt, boolean held, Raycaster.RaycastHit hit,
                          GameMode mode, int heldId)
    {
        if(!held)
        {
            cancel();
            return false;
        }

        // Prodleva běží, i když zrovna nic nezaměřuji - jinak by šlo
        // pohybem myši přes oblohu prodlevu "přeskočit".
        cooldown = Math.max(0f, cooldown - dt);

        if(hit == null)
        {
            stop();
            return false;
        }

        byte target = world.getBlock(hit.x(), hit.y(), hit.z());

        if(!World.isTargetable(target))
        {
            stop();
            return false;
        }

        // Jiný blok než minule - začíná se od nuly.
        if(!active || hit.x() != x || hit.y() != y || hit.z() != z || target != block)
        {
            active = true;
            x = hit.x();
            y = hit.y();
            z = hit.z();
            block = target;
            progress = 0f;
        }

        // Creative: žádné čekání. stop() je tu ze stejného důvodu jako
        // na konci survival větve - postup se vynuluje, ale x/y/z zůstanou,
        // takže na ně harvest() ve stejném framu ještě dosáhne.
        if(mode.instantMining())
        {
            // Po rozbití se s drženým tlačítkem čeká CREATIVE_DELAY, než
            // padne další blok - viz tam.
            // Tolerance kvůli odčítání floatů: 0,25 - 15 × (1/60) nevyjde
            // přesně nula a blok by padl o frame později.
            if(cooldown > 1e-4f)
            {
                return false;
            }

            stop();
            cooldown = CREATIVE_DELAY;
            return true;
        }

        float hardness = World.hardness(block);

        if(hardness <= 0f)
        {
            progress = 1f;
        }
        else
        {
            progress += dt * Items.miningSpeed(heldId, block) / hardness;
        }

        if(progress < 1f)
        {
            return false;
        }

        stop();
        return true;
    }

    /**
     * Rozbije blok, na kterém kopání právě skončilo, a vytěžený kus dá
     * do inventáře. Co se nevejde, vypadne na zem na místě rozbitého bloku -
     * dřív to tiše propadlo.
     *
     * Volat ve framu, kdy update() vrátil true: x(), y(), z() pak pořád
     * ukazují na dokopaný blok (stop() nuluje postup, ne pozici).
     * Vrací true, když se blok opravdu rozbil.
     *
     * Rozbití zazní v prostoru, ze středu bloku, zvukem jeho materiálu.
     *
     * Bez módu se těží survival pravidly - tedy přesně jako dřív.
     */
    public boolean harvest(World world, Container inventory, DroppedItems drops, SoundSink sounds)
    {
        return harvest(world, inventory, drops, sounds, GameMode.SURVIVAL);
    }

    /**
     * Totéž, ale s herním módem. V creative vytěžený blok MIZÍ - nejde
     * do inventáře ani nevypadne na zem, stejně jako ve vanilla Minecraftu.
     *
     * Rozbití a zvuk jsou pro oba módy tytéž; liší se jen to, co se stane
     * s vytěženým kusem. Inventář ani seznam položek se v creative nedotkne,
     * takže se jich nemá jak dotknout ani omylem.
     */
    public boolean harvest(World world, Container inventory, DroppedItems drops, SoundSink sounds,
                           GameMode mode)
    {
        byte mined = world.getBlock(x, y, z);

        // Voda ani vzduch se vytěžit nedají - a breakBlock by jinak zapsal
        // zbytečnou změnu do mapy, která se ukládá.
        if(!World.isTargetable(mined) || !world.breakBlock(x, y, z))
        {
            return false;
        }

        sounds.playAt(Sound.breakOf(mined), x + 0.5f, y + 0.5f, z + 0.5f);

        if(!mode.keepsMinedBlock())
        {
            return true;
        }

        // ⚠️ Vytěžený blok VYPADNE NA ZEM, jako v Minecraftu - do inventáře
        // se dostane až sebráním (DroppedItems.update, se zvukem PICKUP).
        // Dřív šel rovnou do inventáře a na zem jen to, co se nevešlo.
        // Inventář se tu proto nepoužívá; parametr zůstává kvůli volajícím.
        drops.dropFromBlock(x, y, z, ItemStack.of(dropOf(mined), 1));
        return true;
    }

    /**
     * Co z vytěženého bloku vypadne. Skoro vždycky blok sám; uhelná ruda
     * dá uhlí (předmět), jako v Minecraftu. Železná ruda padá dál jako ruda -
     * pec, která by z ní udělala železo, zatím není.
     */
    public static int dropOf(byte mined)
    {
        // Natočená pec padá jako pec (jeden předmět ve všech natočeních).
        return mined == World.COAL_ORE ? ItemRegistry.COAL : World.canonical(mined);
    }

    /**
     * Zruší kopání úplně - i s prodlevou creative, takže další klik rozbije
     * hned. Volá se při puštění tlačítka a když Main mění obrazovku.
     */
    public void cancel()
    {
        stop();
        cooldown = 0f;
    }

    /**
     * Zastaví postup na tomhle bloku, prodleva creative běží dál. Uvnitř
     * update(): mířit s drženým tlačítkem na oblohu nesmí prodlevu vynulovat.
     */
    private void stop()
    {
        active = false;
        progress = 0f;
        block = World.AIR;
    }

    public boolean isActive()
    {
        return active;
    }

    public float progress()
    {
        return progress;
    }

    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }

    /**
     * Které stádium prasklin se má kreslit, nebo -1 když žádné.
     *
     * Nula znamená "sotva naťuknuto", takže se ukáže hned po prvním framu
     * kopání - jinak by první třetina vteřiny vypadala, že se nic neděje.
     */
    public int stage()
    {
        if(!active || progress <= 0f)
        {
            return -1;
        }

        return Math.min(STAGES - 1, (int) (progress * STAGES));
    }
}
