package mc;

/**
 * Pruh výdrže pod ikonou opotřebeného nástroje, jako v Minecraftu.
 *
 * ---------------------------------------------------------------------------
 * Ukazuje se, JEN když je předmět opotřebený - nový nástroj pruh nemá.
 * Barva jde po odstínu od zelené (nový) přes žlutou k červené (skoro
 * prasklý); pruh je 13/16 šířky ikony, dva pixely ikony vysoký, u spodní
 * hrany: černý podklad a na něm barevná část podle zbývající výdrže.
 * ---------------------------------------------------------------------------
 *
 * Výpočty jsou čisté funkce; draw() jen skládá obdélníky do dávky
 * Renderer2D (volá se mezi begin a end).
 */
public final class Durability {

    private static final float[] BACK = {0f, 0f, 0f, 1f};

    private Durability() {}

    /**
     * Zbývající výdrž 0 až 1, nebo -1, když se pruh neukazuje (předmět bez
     * výdrže, nebo ještě nepoužitý).
     */
    public static float remaining(ItemStack stack)
    {
        int durability = Items.durability(stack.id());

        if(stack.isEmpty() || durability <= 0 || stack.damage() <= 0)
        {
            return -1f;
        }

        return Math.max(0f, 1f - stack.damage() / (float) durability);
    }

    /** Barva pruhu: odstín 0 (červená) až 120° (zelená) podle zbývající výdrže. */
    public static float[] color(float remaining)
    {
        int argb = AtlasEditor.hsv(120f * Math.max(0f, Math.min(1f, remaining)), 1f, 1f, 0xFF);
        return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, 1f};
    }

    /**
     * Pruh pod ikonou; iconX/iconY je levý DOLNÍ roh ikony (jako BlockIcon),
     * size její strana. Pro předmět bez pruhu nic.
     */
    public static void draw(Renderer2D shapes, float iconX, float iconY, float size, ItemStack stack)
    {
        float left = remaining(stack);

        if(left < 0f)
        {
            return;
        }

        float unit = size / 16f;
        float x = iconX + 2 * unit, y = iconY + unit;
        float width = 13 * unit;

        shapes.fillRect(x, y, width, 2 * unit, BACK);
        shapes.fillRect(x, y + unit, Math.max(unit, width * left), unit, color(left));
    }
}
