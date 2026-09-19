package mc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Všechny předměty ležící ve světě: vyhazování, fyzika, sběr a zánik.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ NEUKLÁDAJÍ SE - ani se světem, ani přes unload sloupce. Položka, pod
 * kterou se sloupec zahodil, zmizí. Viz ARCHITECTURE.md, "Předměty na zemi".
 *
 * Proč seznam zvlášť, a ne ve World: World jsou bloky a sloupce, tohle jsou
 * objekty s vlastní polohou. Svět se na položky nikdy neptá, jen položky se
 * ptají světa (kolize, voda, je sloupec načtený?). Main drží jeden seznam
 * a při založení nebo načtení světa ho vyprázdní.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, takže jde celé otestovat headless.
 */
public class DroppedItems {

    /** Vytěžený blok jde sebrat za půl vteřiny. Minecraft: 10 ticků. */
    public static final float PICKUP_DELAY_MINED = 0.5f;

    /**
     * Vyhozený předmět až za dvě vteřiny, jinak by hráči skončil hned zpátky
     * v ruce - objevuje se uvnitř jeho dosahu. Minecraft: 40 ticků.
     */
    public static final float PICKUP_DELAY_THROWN = 2f;

    /**
     * Po pěti minutách položka zmizí, jako v Minecraftu. Bez toho by jich při
     * těžbě s plným inventářem mohlo přibývat bez omezení.
     */
    public static final float LIFETIME = 300f;

    /**
     * Dosah sběru: hitbox hráče zvětšený o blok do stran a o půl bloku nahoru
     * i dolů, jako v Minecraftu. Porovnává se překryv krabiček, ne vzdálenost
     * středů - hráč je vysoký, takže by se jinak sbíralo jinak u nohou a jinak
     * u hlavy.
     */
    public static final float PICKUP_REACH_XZ = 1f;
    public static final float PICKUP_REACH_Y = 0.5f;

    /** Vyhození: 0,3 bloku za tick ve směru pohledu a 0,1 navíc nahoru. Minecraft. */
    private static final float THROW_SPEED = 6f;
    private static final float THROW_LIFT = 2f;

    /** Vyhozená položka se objeví kousek pod očima, ne v nich. */
    private static final float THROW_BELOW_EYES = 0.3f;

    /** Vytěžený blok malinko vyskočí, ať je vidět, že něco vypadlo. */
    private static final float MINED_POP = 3f;

    /**
     * Zlatý úhel: posun animace mezi po sobě vzniklými položkami. Kterékoliv
     * dvě se pak točí a houpou jinak, i když vzniknou na stejném místě.
     */
    private static final float PHASE_STEP = 2.39996f;

    private final List<DroppedItem> items = new ArrayList<>();
    private final List<DroppedItem> readOnly = Collections.unmodifiableList(items);

    private int spawned = 0;

    /** Položky ve světě. Jen ke čtení - přidává se přes drop/throw. */
    public List<DroppedItem> items()
    {
        return readOnly;
    }

    public int size()
    {
        return items.size();
    }

    /** Nový nebo načtený svět začíná bez položek - neukládají se. */
    public void clear()
    {
        items.clear();
    }

    // ------------------------------------------------------------------
    // vznik
    // ------------------------------------------------------------------

    /**
     * Vytěžený blok, který se nevešel do inventáře: vypadne ze středu buňky,
     * kde blok byl. Prázdná hromádka nevytvoří nic a vrátí null.
     */
    public DroppedItem dropFromBlock(int x, int y, int z, ItemStack stack)
    {
        return spawn(stack,
                x + 0.5f, y + 0.5f - DroppedItem.SIZE / 2f, z + 0.5f,
                0f, MINED_POP, 0f, PICKUP_DELAY_MINED);
    }

    /**
     * Vyhození z ruky: položka vyletí zpod očí hráče ve směru pohledu.
     * direction je jednotkový vektor z Camera.getLookDirection().
     */
    public DroppedItem throwFrom(Player player, float[] direction, ItemStack stack)
    {
        return spawn(stack,
                player.x, player.eyeY() - THROW_BELOW_EYES, player.z,
                direction[0] * THROW_SPEED,
                direction[1] * THROW_SPEED + THROW_LIFT,
                direction[2] * THROW_SPEED,
                PICKUP_DELAY_THROWN);
    }

    private DroppedItem spawn(ItemStack stack, float x, float y, float z,
                              float vx, float vy, float vz, float pickupDelay)
    {
        if(stack.isEmpty())
        {
            return null;
        }

        DroppedItem item = new DroppedItem(stack, x, y, z, vx, vy, vz,
                pickupDelay, spawned++ * PHASE_STEP);
        items.add(item);
        return item;
    }

    // ------------------------------------------------------------------
    // každý frame
    // ------------------------------------------------------------------

    /**
     * Fyzika, sběr a zánik. Volat jednou za frame, po pohybu hráče.
     *
     * Sbírá se do inventáře stejně jako při těžbě - nejdřív se dolijí
     * rozdělané hromádky, pak se zabere volný slot. Co se nevejde, zůstane
     * ležet - při plném inventáři celá položka, při skoro plném jen zbytek.
     */
    public void update(World world, Player player, Container inventory, float dt)
    {
        // Pozpátku, aby šlo mazat rovnou během průchodu.
        for(int i = items.size() - 1; i >= 0; i--)
        {
            DroppedItem item = items.get(i);

            // ⚠️ Sloupec pod položkou se zahodil: položka se neukládá, takže
            // zmizí. Nechat ji ležet nejde - nenačtený sloupec je pro kolize
            // vzduch a položka by propadala donekonečna.
            if(!world.isColumnLoaded((int) Math.floor(item.x), (int) Math.floor(item.z)))
            {
                items.remove(i);
                continue;
            }

            item.update(world, dt);

            if(item.age() >= LIFETIME)
            {
                items.remove(i);
                continue;
            }

            if(!item.canBePickedUp() || !reaches(player, item))
            {
                continue;
            }

            ItemStack rest = inventory.add(item.stack());

            if(rest.isEmpty())
            {
                items.remove(i);
            }
            else
            {
                item.setStack(rest);
            }
        }
    }

    /** Protíná se hitbox hráče zvětšený o dosah sběru s krabičkou položky? */
    static boolean reaches(Player player, DroppedItem item)
    {
        float reach = Player.WIDTH / 2f + PICKUP_REACH_XZ;
        float half = DroppedItem.SIZE / 2f;

        return item.x + half > player.x - reach && item.x - half < player.x + reach
                && item.z + half > player.z - reach && item.z - half < player.z + reach
                && item.y + DroppedItem.SIZE > player.y - PICKUP_REACH_Y
                && item.y < player.y + Player.HEIGHT + PICKUP_REACH_Y;
    }
}
