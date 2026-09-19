package mc;

/**
 * Inventář hráče: hotbar a batoh v jednom kontejneru.
 *
 * ⚠️ Sloty 0-8 jsou HOTBAR, 9-35 batoh. Je to jedno souvislé pole schválně -
 * shift-klik i ukládání pak nemusí řešit dvě různé věci a rozvržení
 * na obrazovce je jen otázka toho, kde se která část kreslí (viz ContainerScreen).
 */
public class Inventory extends Container {

    public static final int HOTBAR_SIZE = 9;
    public static final int BACKPACK_SIZE = 27;
    public static final int SIZE = HOTBAR_SIZE + BACKPACK_SIZE;

    public Inventory()
    {
        super(SIZE);
    }

    public ItemStack hotbar(int slot)
    {
        return get(slot);
    }

    /**
     * Shift-klik: přesune celou hromádku ze slotu do DRUHÉ části inventáře -
     * z hotbaru do batohu a z batohu do hotbaru.
     *
     * Slévá se stejně jako při sběru (nejdřív rozdělané hromádky, pak prázdné
     * sloty). Co se nevejde, zůstane ve výchozím slotu; plný cíl tedy znamená,
     * že se nic nestane.
     */
    public void quickMove(int slot)
    {
        ItemStack stack = get(slot);

        if(stack.isEmpty() || slot < 0 || slot >= SIZE)
        {
            return;
        }

        ItemStack rest = slot < HOTBAR_SIZE
                ? insert(stack, HOTBAR_SIZE, SIZE)
                : insert(stack, 0, HOTBAR_SIZE);

        set(slot, rest);
    }
}
