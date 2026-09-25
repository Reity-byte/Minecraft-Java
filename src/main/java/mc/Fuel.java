package mc;

/**
 * Palivo do pece: kolik vteřin jeden kus hoří.
 *
 * Hodnoty Minecraftu (tavení kusu trvá 10 s): uhlí 80 s (8 kusů), kmen,
 * prkna, plot a stůl 15 s (1,5 kusu), klacek 5 s (půl kusu). Nic jiného
 * nehoří. Nesahá na GL.
 */
public final class Fuel {

    private Fuel() {}

    /** Kolik vteřin kus hoří; 0 = není palivo. */
    public static float seconds(int id)
    {
        if(id == ItemRegistry.COAL)  return 80f;
        if(id == ItemRegistry.STICK) return 5f;

        return switch(id)
        {
            case World.LOG, World.BIRCH_LOG, World.SPRUCE_LOG, World.PLANKS,
                 World.FENCE, World.CRAFTING_TABLE -> 15f;
            default -> 0f;
        };
    }

    public static boolean isFuel(int id)
    {
        return seconds(id) > 0f;
    }
}
