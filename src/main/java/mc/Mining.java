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
     * Posune kopání o jeden frame. Vrací true právě ve framu, kdy blok praskl.
     *
     * hit smí být null (kurzor nemíří na nic) a held false (tlačítko není
     * stisknuté) - obojí kopání zruší.
     */
    public boolean update(World world, float dt, boolean held, Raycaster.RaycastHit hit)
    {
        if(!held || hit == null)
        {
            cancel();
            return false;
        }

        byte target = world.getBlock(hit.x(), hit.y(), hit.z());

        if(!World.isTargetable(target))
        {
            cancel();
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

        float hardness = World.hardness(block);

        if(hardness <= 0f)
        {
            progress = 1f;
        }
        else
        {
            progress += dt / hardness;
        }

        if(progress < 1f)
        {
            return false;
        }

        cancel();
        return true;
    }

    /**
     * Rozbije blok, na kterém kopání právě skončilo, a vytěžený kus dá
     * do inventáře. Co se nevejde, vypadne na zem na místě rozbitého bloku -
     * dřív to tiše propadlo.
     *
     * Volat ve framu, kdy update() vrátil true: x(), y(), z() pak pořád
     * ukazují na dokopaný blok (cancel() nuluje postup, ne pozici).
     * Vrací true, když se blok opravdu rozbil.
     */
    public boolean harvest(World world, Container inventory, DroppedItems drops)
    {
        byte mined = world.getBlock(x, y, z);

        // Voda ani vzduch se vytěžit nedají - a breakBlock by jinak zapsal
        // zbytečnou změnu do mapy, která se ukládá.
        if(!World.isTargetable(mined) || !world.breakBlock(x, y, z))
        {
            return false;
        }

        ItemStack rest = inventory.add(ItemStack.of(mined, 1));
        drops.dropFromBlock(x, y, z, rest);
        return true;
    }

    public void cancel()
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
