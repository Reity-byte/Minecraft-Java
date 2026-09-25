package mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Všechny pece ve světě: stav podle polohy bloku.
 *
 * ---------------------------------------------------------------------------
 * Svět nese jen bajt na buňku, takže co v peci leží a jak dlouho taví,
 * drží tahle mapa vedle něj ("block entity" v Minecraftu). Vzniká položením
 * pece (create), mizí rozbitím (remove - obsah vrátí, ať vypadne na zem).
 *
 * Taví se VŠECHNY pece, dokud se hraje - i ty daleko od hráče v nenačtených
 * sloupcích (rozhodnutí uživatele: tavení se dokončí, i když odejdeš).
 * Svět zavřený = pece stojí; ukládají se s ním (WorldStorage, MCW6).
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL.
 */
public final class Furnaces {

    /** Uložený stav jedné pece (WorldStorage). */
    public record Saved(int x, int y, int z, ItemStack[] slots, float burnLeft, float burnTotal, float cook) {}

    private final Map<Long, FurnaceState> byPos = new HashMap<>();

    /** Klíč polohy: x a z po 26 bitech, y po 12 - svět má 128 bloků, souřadnice ±33 milionů. */
    static long key(int x, int y, int z)
    {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Pec na poloze, nebo null. */
    public FurnaceState at(int x, int y, int z)
    {
        return byPos.get(key(x, y, z));
    }

    /** Pec na poloze; když žádná není, založí prázdnou (položená pec, nebo starý svět bez stavu). */
    public FurnaceState create(int x, int y, int z)
    {
        return byPos.computeIfAbsent(key(x, y, z), k -> new FurnaceState());
    }

    /**
     * Pec byla rozbitá: stav zmizí a vrátí se, co v ní leželo (neprázdné
     * hromádky), ať to volající vyhodí na zem.
     */
    public List<ItemStack> remove(int x, int y, int z)
    {
        FurnaceState state = byPos.remove(key(x, y, z));
        List<ItemStack> contents = new ArrayList<>();

        if(state != null)
        {
            for(int i = 0; i < state.slots.size(); i++)
            {
                if(!state.slots.get(i).isEmpty())
                {
                    contents.add(state.slots.get(i));
                }
            }
        }

        return contents;
    }

    public int size()
    {
        return byPos.size();
    }

    public void clear()
    {
        byPos.clear();
    }

    /** Posune všechny pece o dt. */
    public void update(float dt)
    {
        for(FurnaceState state : byPos.values())
        {
            state.update(dt);
        }
    }

    // ------------------------------------------------------------------
    // ukládání
    // ------------------------------------------------------------------

    public List<Saved> snapshot()
    {
        List<Saved> out = new ArrayList<>();

        for(Map.Entry<Long, FurnaceState> e : byPos.entrySet())
        {
            long k = e.getKey();
            // Zpětně ze znaménkových polí: x a z jsou 26bitová čísla se znaménkem.
            int x = (int) (k >> 38) << 6 >> 6;
            int z = (int) ((k >> 12) & 0x3FFFFFF) << 6 >> 6;
            int y = (int) (k & 0xFFF);

            FurnaceState s = e.getValue();
            ItemStack[] slots = new ItemStack[s.slots.size()];

            for(int i = 0; i < slots.length; i++)
            {
                slots[i] = s.slots.get(i);
            }

            out.add(new Saved(x, y, z, slots, s.burnLeft, s.burnTotal, s.cook));
        }

        return out;
    }

    /** Stav ze souboru místo dosavadního. Nesmyslná čísla (NaN, záporná) se srovnají na 0. */
    public void restore(List<Saved> saved)
    {
        byPos.clear();

        for(Saved s : saved)
        {
            FurnaceState state = create(s.x(), s.y(), s.z());

            for(int i = 0; i < Math.min(s.slots().length, state.slots.size()); i++)
            {
                state.slots.set(i, s.slots()[i] == null ? ItemStack.EMPTY : s.slots()[i]);
            }

            state.burnLeft = sane(s.burnLeft());
            state.burnTotal = Math.max(state.burnLeft, sane(s.burnTotal()));
            state.cook = Math.min(FurnaceState.COOK_SECONDS, sane(s.cook()));
        }
    }

    private static float sane(float v)
    {
        return Float.isFinite(v) && v > 0f ? v : 0f;
    }
}
