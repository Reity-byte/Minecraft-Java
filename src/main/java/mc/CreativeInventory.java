package mc;

import java.util.ArrayList;
import java.util.List;

/**
 * Obsah creative přehledu: jeden kus od každého bloku, který jde položit.
 *
 * ---------------------------------------------------------------------------
 * Seznam se NESKLÁDÁ RUČNĚ, počítá se. Vestavěné bloky jsou id 1 až
 * World.LAST_BUILT_IN (nula je vzduch, tedy "nic", ne blok), bloky z labu
 * si řekne aktivní BlockRegistry. Nový blok - v kódu i v labu - se tím
 * v přehledu objeví sám, bez druhého místa na údržbu.
 *
 * ⚠️ JE TO JEN OBYČEJNÝ Container. Nekonečnost není jeho vlastnost - ten
 * o sobě nic neví. Že se z něj bere kopie a nikdy se z něj neubere, je
 * pravidlo OBRAZOVKY (ContainerScreen.creativeInventory a příznak infinite
 * u mřížky). Díky tomu se nemuselo sahat na Container, na kterém stojí
 * survival inventář i crafting.
 *
 * Počet kusů ve slotu je 1: přesně to, co je vidět, se taky vezme. Víc by
 * nemělo smysl - v creative se pokládáním nic neubírá, takže se jeden kus
 * nikdy nespotřebuje.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, takže jde celé otestovat headless (viz CreativeTest).
 */
public final class CreativeInventory {

    /** Kolik kusů je ve slotu přehledu. Viz komentář u třídy. */
    public static final int STACK = 1;

    private CreativeInventory() {}

    /**
     * Dá se vestavěný blok položit, tedy patří do přehledu?
     *
     * Vzduch ne - to je prázdná buňka, ne blok. Všechno ostatní z rozsahu
     * vestavěných id ano, včetně vody: World.placeBlock() na druh bloku
     * nekouká a v creative je voda přesně ten blok, ke kterému se hráč
     * jinak nedostane (vytěžit ji nejde, viz World.isTargetable).
     */
    public static boolean isPlaceable(byte block)
    {
        return block > World.AIR && block <= World.LAST_BUILT_IN;
    }

    /**
     * Id všech bloků do přehledu: nejdřív vestavěné podle id, pak bloky
     * z labu podle id. Pořadí je stabilní, takže blok neskáče po mřížce
     * mezi otevřeními.
     *
     * Bez blocks.json (prázdný registr) vyjdou jen vestavěné bloky.
     */
    public static List<Byte> blocks(BlockRegistry registry)
    {
        List<Byte> ids = new ArrayList<>();

        for(byte id = 1; id <= World.LAST_BUILT_IN; id++)
        {
            if(isPlaceable(id))
            {
                ids.add(id);
            }
        }

        for(BlockDef def : registry.blocks())
        {
            ids.add(def.id());
        }

        return ids;
    }

    /**
     * Id všeho do přehledu: bloky (viz blocks()), za nimi předměty -
     * vestavěné, pak z labu, každé podle id.
     */
    public static List<Integer> ids(BlockRegistry blocks, ItemRegistry items)
    {
        List<Integer> ids = new ArrayList<>();

        for(byte block : blocks(blocks))
        {
            ids.add((int) block);
        }

        // null = jen bloky. ItemRegistry.empty() by nestačil - vestavěné
        // předměty (klacek, uhlí) jsou v každém registru.
        if(items != null)
        {
            for(ItemDef def : items.items())
            {
                ids.add(def.id());
            }
        }

        return ids;
    }

    /** Přehled jen s bloky - jako dřív (a pro testy, které předměty nezajímají). */
    public static Container container(BlockRegistry registry)
    {
        return container(registry, null);
    }

    /**
     * Přehled jako kontejner - přesně tolik slotů, kolik je věcí, každý
     * s jedním kusem. Volá se při každém otevření obrazovky, takže blok
     * nebo předmět právě založený v labu je v něm hned.
     */
    public static Container container(BlockRegistry blocks, ItemRegistry items)
    {
        List<Integer> ids = ids(blocks, items);
        Container source = new Container(ids.size());

        for(int i = 0; i < ids.size(); i++)
        {
            source.set(i, ItemStack.of(ids.get(i), STACK));
        }

        return source;
    }
}
