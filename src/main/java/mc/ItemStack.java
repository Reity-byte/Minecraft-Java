package mc;

/**
 * Hromádka jednoho druhu bloku v jednom slotu.
 *
 * Je NEMĚNNÁ. Přesouvání v inventáři je pak jen výměna odkazů a nemůže se stát,
 * že se stejný objekt omylem ocitne ve dvou slotech a změna v jednom se projeví
 * i ve druhém - což je nejčastější chyba u drag &amp; drop v inventáři.
 *
 * Prázdný slot drží {@link #EMPTY}, ne null: odpadne tím kontrola na null
 * v každém kreslení i v každém přesunu.
 */
public record ItemStack(byte block, int count) {

    /** Kolik se vejde do jednoho slotu. Stejně jako v Minecraftu. */
    public static final int MAX_COUNT = 64;

    public static final ItemStack EMPTY = new ItemStack(World.AIR, 0);

    public static ItemStack of(byte block, int count)
    {
        return count <= 0 || block == World.AIR ? EMPTY : new ItemStack(block, count);
    }

    public boolean isEmpty()
    {
        return count <= 0 || block == World.AIR;
    }

    /** Kolik se do téhle hromádky ještě vejde. */
    public int space()
    {
        return isEmpty() ? MAX_COUNT : MAX_COUNT - count;
    }

    /** Jde tahle hromádka slít s tou druhou? Prázdná se slije s čímkoliv. */
    public boolean stacksWith(ItemStack other)
    {
        return isEmpty() || other.isEmpty() || block == other.block;
    }

    public ItemStack withCount(int newCount)
    {
        return of(block, newCount);
    }

    public ItemStack plus(int amount)
    {
        return of(block, count + amount);
    }

    @Override
    public String toString()
    {
        return isEmpty() ? "prazdno" : count + "x blok " + block;
    }
}
