package mc;

/**
 * Rozdělení id věcí, které se dají držet v inventáři: bloky a předměty.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ JEDNA ŘADA ČÍSEL PRO OBOJÍ, ROZDĚLENÁ NAPEVNO:
 *
 *     0 až 127     bloky - vestavěné (World) a z labu (BlockRegistry, 64+).
 *                  Id hromádky bloku JE id bloku ve světě, bez převodu.
 *   128 až 255     rezerva, kdyby bloky jednou přerostly byte
 *   256 až 1023    předměty (klacek, uhlí, nástroje) - nejdou položit
 *
 * Hromádka (ItemStack) proto drží int, ne byte. Blok se pozná podle id
 * menšího než FIRST_ITEM - žádný příznak navíc, který by se mohl rozejít
 * s číslem. Uložené světy a recepty nesou tatáž čísla.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL.
 */
public final class Items {

    /** Nejvyšší id bloku. Bloky jsou byte, záporné id by rozbilo porovnání. */
    public static final int LAST_BLOCK = BlockRegistry.LAST_ID;

    /** První id předmětu. Mezi bloky a předměty je rezerva do 255. */
    public static final int FIRST_ITEM = 256;

    /** Nejvyšší id čehokoli v inventáři. Ukládá se jako short, takže s rezervou. */
    public static final int LAST_ID = 1023;

    private Items() {}

    /** Je id blok (dá se položit)? Vzduch taky - je to "žádný blok". */
    public static boolean isBlock(int id)
    {
        return id >= 0 && id <= LAST_BLOCK;
    }

    /** Je id předmět (nedá se položit)? */
    public static boolean isItem(int id)
    {
        return id >= FIRST_ITEM && id <= LAST_ID;
    }

    /** Leží id v některém z rozsahů? (Nic neříká o tom, jestli taková věc existuje.) */
    public static boolean inRange(int id)
    {
        return isBlock(id) || isItem(id);
    }

    /**
     * Existuje taková věc? Vzduch ne. Pro blok: vestavěný, nebo z labu,
     * který aktivní BlockRegistry zná. Předměty zatím žádné nejsou.
     */
    public static boolean exists(int id)
    {
        if(id == World.AIR || !inRange(id))
        {
            return false;
        }

        if(isBlock(id))
        {
            return id >= BlockRegistry.FIRST_ID
                    ? BlockRegistry.lookup((byte) id) != null
                    : id <= World.LAST_BUILT_IN;
        }

        return false;
    }
}
