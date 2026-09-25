package mc;

import java.util.Arrays;

/**
 * Mřížka slotů s hromádkami. Nic víc.
 *
 * ---------------------------------------------------------------------------
 * TOHLE JE TA ZNOVUPOUŽITELNÁ ČÁST. Container neví nic o kreslení, o myši ani
 * o tom, k čemu slouží - je to jen pole hromádek s pravidly slévání.
 *
 * Inventář hráče, crafting mřížka, výsledkový slot i budoucí truhla nebo
 * enchantovací stůl jsou různě velké instance TÉHOŽ. Obrazovka
 * (ContainerScreen) pak jen říká, které kontejnery se kde kreslí; přesuny,
 * dělení hromádek a shift-klik se díky tomu píšou jednou.
 * ---------------------------------------------------------------------------
 */
public class Container {

    private final ItemStack[] slots;

    public Container(int size)
    {
        slots = new ItemStack[size];
        Arrays.fill(slots, ItemStack.EMPTY);
    }

    public int size()
    {
        return slots.length;
    }

    public ItemStack get(int index)
    {
        return index < 0 || index >= slots.length ? ItemStack.EMPTY : slots[index];
    }

    public void set(int index, ItemStack stack)
    {
        if(index >= 0 && index < slots.length)
        {
            slots[index] = stack == null ? ItemStack.EMPTY : stack;
        }
    }

    public void clear()
    {
        Arrays.fill(slots, ItemStack.EMPTY);
    }

    public boolean isEmpty()
    {
        for(ItemStack stack : slots)
        {
            if(!stack.isEmpty())
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Přidá hromádku do kontejneru a vrátí, co se nevešlo.
     *
     * ⚠️ Nejdřív se dolévají ROZDĚLANÉ hromádky téhož bloku a teprve pak se
     * zabírá prázdný slot. Opačné pořadí by po vytěžení pár bloků rozsypalo
     * inventář do jednotlivých slotů s jedním kusem.
     */
    public ItemStack add(ItemStack stack)
    {
        return insert(stack, 0, slots.length);
    }

    /**
     * Totéž co add(), jen do slotů from až to-1. Shift-klik tím přesouvá
     * jen do druhé části inventáře (hotbar / batoh) a pravidla slévání
     * zůstávají napsaná jednou.
     */
    public ItemStack insert(ItemStack stack, int from, int to)
    {
        if(stack.isEmpty())
        {
            return ItemStack.EMPTY;
        }

        from = Math.max(0, from);
        to = Math.min(slots.length, to);

        int remaining = stack.count();

        for(int i = from; i < to && remaining > 0; i++)
        {
            ItemStack slot = slots[i];

            if(!slot.sameItem(stack))
            {
                continue;
            }

            // max(0, ...): hromádka přes MAX_COUNT (třeba z ručně upraveného
            // souboru) má místo záporné a "přidání" by z ní kusy ubralo.
            int moved = Math.min(Math.max(0, slot.space()), remaining);
            slots[i] = slot.plus(moved);
            remaining -= moved;
        }

        for(int i = from; i < to && remaining > 0; i++)
        {
            if(!slots[i].isEmpty())
            {
                continue;
            }

            int moved = Math.min(ItemStack.MAX_COUNT, remaining);
            slots[i] = stack.withCount(moved);
            remaining -= moved;
        }

        return stack.withCount(remaining);
    }

    /**
     * Kolik kusů téhle hromádky se do kontejneru vejde - stejnými pravidly
     * jako add(), jen bez přidání. Když je to aspoň stack.count(), add()
     * vrátí prázdný zbytek. Pro místa, kde se musí přidat všechno, nebo nic.
     */
    public int room(ItemStack stack)
    {
        if(stack.isEmpty())
        {
            return 0;
        }

        int room = 0;

        for(ItemStack slot : slots)
        {
            if(slot.isEmpty())
            {
                room += ItemStack.MAX_COUNT;
            }
            else if(slot.sameItem(stack))
            {
                room += Math.max(0, slot.space());
            }
        }

        return room;
    }

    /** Ubere jeden kus ze slotu. Používá pokládání bloku z hotbaru. */
    public boolean removeOne(int index)
    {
        ItemStack slot = get(index);

        if(slot.isEmpty())
        {
            return false;
        }

        set(index, slot.plus(-1));
        return true;
    }

    /**
     * Vezme ze slotu nejvýš count kusů a vrátí je jako hromádku; ve slotu
     * zůstane zbytek. Používá vyhazování z ruky (jeden kus i celá hromádka).
     */
    public ItemStack take(int index, int count)
    {
        ItemStack slot = get(index);
        int taken = Math.min(count, slot.count());

        if(slot.isEmpty() || taken <= 0)
        {
            return ItemStack.EMPTY;
        }

        set(index, slot.plus(-taken));
        return slot.withCount(taken);
    }

    /** Kolik kusů dané věci (blok i předmět, podle id) je v kontejneru celkem. Pro testy. */
    public int countOf(int id)
    {
        int total = 0;

        for(ItemStack stack : slots)
        {
            if(!stack.isEmpty() && stack.id() == id)
            {
                total += stack.count();
            }
        }

        return total;
    }
}
