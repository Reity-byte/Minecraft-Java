package mc;

/**
 * Hromádka jedné věci v jednom slotu - bloku, nebo předmětu (viz Items).
 *
 * Je NEMĚNNÁ. Přesouvání v inventáři je pak jen výměna odkazů a nemůže se stát,
 * že se stejný objekt omylem ocitne ve dvou slotech a změna v jednom se projeví
 * i ve druhém - což je nejčastější chyba u drag &amp; drop v inventáři.
 *
 * Prázdný slot drží {@link #EMPTY}, ne null: odpadne tím kontrola na null
 * v každém kreslení i v každém přesunu.
 *
 * @param id    id věci: blok pod Items.FIRST_ITEM, předmět od něj výš
 * @param count kolik kusů
 */
public record ItemStack(int id, int count) {

    /** Kolik se vejde do jednoho slotu. Stejně jako v Minecraftu. */
    public static final int MAX_COUNT = 64;

    public static final ItemStack EMPTY = new ItemStack(World.AIR, 0);

    /** Hromádka; nula kusů, vzduch i id mimo rozsahy Items dají EMPTY. */
    public static ItemStack of(int id, int count)
    {
        return count <= 0 || id == World.AIR || !Items.inRange(id) ? EMPTY : new ItemStack(id, count);
    }

    public boolean isEmpty()
    {
        return count <= 0 || id == World.AIR;
    }

    /**
     * Blok, který se z hromádky položí - nebo World.AIR, když je to předmět
     * (klacek položit nejde) nebo prázdno.
     *
     * ⚠️ JEN NA POKLÁDÁNÍ A KRESLENÍ BLOKU. Kdo se ptá "je to táž věc",
     * "co to je" nebo věc ukládá, bere id() - block() dá u všech předmětů
     * stejný vzduch.
     */
    public byte block()
    {
        return Items.isBlock(id) ? (byte) id : World.AIR;
    }

    /** Je to blok (dá se položit)? Prázdná hromádka není nic. */
    public boolean isBlock()
    {
        return !isEmpty() && Items.isBlock(id);
    }

    /**
     * Kolik kusů téhle věci se vejde do slotu (Items.maxStack): 64,
     * u nástroje z labu třeba 1. MAX_COUNT je horní mez pro všechno.
     */
    public int maxCount()
    {
        return Items.maxStack(id);
    }

    /** Kolik se do téhle hromádky ještě vejde. Prázdný slot unese MAX_COUNT čehokoliv. */
    public int space()
    {
        return isEmpty() ? MAX_COUNT : maxCount() - count;
    }

    /**
     * Je to týž druh věci, takže jde hromádky slít? Prázdná není nic.
     *
     * ⚠️ JEDINÉ MÍSTO, KDE TO PRAVIDLO JE. Dřív bylo `a.block() == b.block()`
     * rozepsané na pěti místech v inventáři a kontejneru.
     */
    public boolean sameItem(ItemStack other)
    {
        return !isEmpty() && !other.isEmpty() && id == other.id;
    }

    public ItemStack withCount(int newCount)
    {
        return of(id, newCount);
    }

    public ItemStack plus(int amount)
    {
        return of(id, count + amount);
    }

    @Override
    public String toString()
    {
        return isEmpty() ? "prazdno" : count + "x " + (Items.isBlock(id) ? "blok " : "predmet ") + id;
    }
}
