package mc;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Mapa s klíčem `long` bez zabalování: otevřené adresování, lineární
 * zkoušení, promíchaný hash. Pro sloupce světa (`World.key(cx, cz)`).
 *
 * ---------------------------------------------------------------------------
 * ⚠️ PROČ NE HashMap&lt;Long, …&gt;. `Long.hashCode()` klíče (cx &lt;&lt; 32 | cz)
 * vychází jako `cx ^ cz`, takže všechny sloupce na téže "diagonále XOR" měly
 * stejný hash: z 289 načtených sloupců jen 32 různých hashů, koše se měnily
 * na stromy a hledání v nich alokovalo. A to na nejteplejší cestě hry -
 * mesher volá `World.cellAt` zhruba 24x na blok, BFS světla několikrát na
 * uzel. K tomu každý dotaz zabalil `long` do `Long`, takže `update()` v klidu
 * alokoval ~20 KB na frame (289 × containsKey), i když nic nechybělo.
 * Tady se klíč promíchá (Fibonacciho hash) a do pole se nic nebalí.
 * ---------------------------------------------------------------------------
 *
 * Hodnota null neexistuje - `get()` vrací null pro "není". Jen pro jedno
 * vlákno (World je jen na hlavním, viz `World.columns`). Iterace přes
 * `values()` se nesmí prokládat změnami mapy; mazat za chodu jde jen přes
 * `removeIf`.
 */
final class LongMap<V> {

    /** Čtvrtinu pole necháváme volnou, jinak se lineární zkoušení prodlouží. */
    private static final float LOAD = 0.75f;

    private long[] keys;
    private Object[] values;
    private int mask;
    private int size;

    /** Znovupoužité pole klíčů pro removeIf - mazání za chodu bez alokace. */
    private long[] doomed = new long[16];

    LongMap()
    {
        this(16);
    }

    LongMap(int expected)
    {
        int capacity = Integer.highestOneBit(Math.max(4, (int) (expected / LOAD) + 1) - 1) << 1;
        keys = new long[capacity];
        values = new Object[capacity];
        mask = capacity - 1;
    }

    private int slot(long key)
    {
        // Fibonacciho hash: horní bity součinu jsou dobře promíchané ze všech
        // bitů klíče, takže cx i cz se v indexu projeví oba.
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }

    @SuppressWarnings("unchecked")
    V get(long key)
    {
        for(int i = slot(key); ; i = (i + 1) & mask)
        {
            Object value = values[i];

            if(value == null)
            {
                return null;
            }

            if(keys[i] == key)
            {
                return (V) value;
            }
        }
    }

    boolean containsKey(long key)
    {
        return get(key) != null;
    }

    /** Vloží nebo přepíše. Vrací předchozí hodnotu, nebo null. */
    @SuppressWarnings("unchecked")
    V put(long key, V value)
    {
        if(value == null)
        {
            throw new IllegalArgumentException("LongMap nedrzi null");
        }

        int i = slot(key);

        for(; values[i] != null; i = (i + 1) & mask)
        {
            if(keys[i] == key)
            {
                V previous = (V) values[i];
                values[i] = value;
                return previous;
            }
        }

        keys[i] = key;
        values[i] = value;
        size++;

        if(size > keys.length * LOAD)
        {
            grow();
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    V remove(long key)
    {
        for(int i = slot(key); values[i] != null; i = (i + 1) & mask)
        {
            if(keys[i] == key)
            {
                V previous = (V) values[i];
                deleteAt(i);
                return previous;
            }
        }

        return null;
    }

    /**
     * Smaže slot a posune za ním ležící položky zpátky, aby řetěz zkoušení
     * zůstal souvislý (bez "náhrobků", které by hledání prodlužovaly).
     */
    private void deleteAt(int hole)
    {
        values[hole] = null;
        size--;

        for(int i = (hole + 1) & mask; values[i] != null; i = (i + 1) & mask)
        {
            int home = slot(keys[i]);

            // Položka smí do díry, jen když díra leží na její cestě
            // od domovského slotu (cyklicky) - jinak by ji hledání minulo.
            boolean movable = hole <= i
                    ? home <= hole || home > i
                    : home <= hole && home > i;

            if(movable)
            {
                keys[hole] = keys[i];
                values[hole] = values[i];
                values[i] = null;
                hole = i;
            }
        }
    }

    private void grow()
    {
        long[] oldKeys = keys;
        Object[] oldValues = values;

        keys = new long[oldKeys.length * 2];
        values = new Object[oldValues.length * 2];
        mask = keys.length - 1;

        for(int j = 0; j < oldKeys.length; j++)
        {
            if(oldValues[j] != null)
            {
                int i = slot(oldKeys[j]);
                while(values[i] != null)
                {
                    i = (i + 1) & mask;
                }
                keys[i] = oldKeys[j];
                values[i] = oldValues[j];
            }
        }
    }

    int size()
    {
        return size;
    }

    boolean isEmpty()
    {
        return size == 0;
    }

    void clear()
    {
        Arrays.fill(values, null);
        size = 0;
    }

    interface EntryPredicate<V> {
        boolean test(long key, V value);
    }

    /**
     * Smaže každou položku, pro kterou `doomed` vrátí true. Predikát se na
     * každou položku zavolá právě jednou, takže smí uklízet (mazat VBO).
     */
    @SuppressWarnings("unchecked")
    void removeIf(EntryPredicate<V> predicate)
    {
        // Nejdřív sebrat, pak mazat: posun při mazání by jinak mohl přesunout
        // ještě neprojitou položku do už projitého slotu a ta by se přeskočila.
        int count = 0;

        for(int i = 0; i < keys.length; i++)
        {
            if(values[i] != null && predicate.test(keys[i], (V) values[i]))
            {
                if(count == doomed.length)
                {
                    doomed = Arrays.copyOf(doomed, count * 2);
                }
                doomed[count++] = keys[i];
            }
        }

        for(int j = 0; j < count; j++)
        {
            remove(doomed[j]);
        }
    }

    /** Živý pohled na hodnoty. Mapu během procházení neměnit. */
    java.util.Collection<V> values()
    {
        return valueView;
    }

    private final java.util.Collection<V> valueView = new AbstractCollection<>()
    {
        @Override
        public Iterator<V> iterator()
        {
            return new Iterator<>()
            {
                private int next = advance(0);

                private int advance(int from)
                {
                    while(from < values.length && values[from] == null)
                    {
                        from++;
                    }
                    return from;
                }

                @Override
                public boolean hasNext()
                {
                    return next < values.length;
                }

                @Override
                @SuppressWarnings("unchecked")
                public V next()
                {
                    if(next >= values.length)
                    {
                        throw new NoSuchElementException();
                    }
                    V value = (V) values[next];
                    next = advance(next + 1);
                    return value;
                }
            };
        }

        @Override
        public int size()
        {
            return size;
        }
    };
}
