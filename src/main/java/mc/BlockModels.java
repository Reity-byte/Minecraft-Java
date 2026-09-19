package mc;

/**
 * Tvary bloků. Blok už nemusí být krychle.
 *
 * ---------------------------------------------------------------------------
 * Model je SEZNAM KVÁDRŮ v souřadnicích 0-1 uvnitř bloku. Pokrývá to pochodeň,
 * plot, schody, desky i tlakové desky - všechno, co jde poskládat z hranolů.
 *
 * Dvě pravidla, na kterých to celé stojí:
 *
 * 1) Stěna kvádru se zahazuje JEN když leží přesně na hranici bloku. Stěna
 *    uvnitř bloku (třeba bok tenké pochodně) musí být vidět vždycky, i když
 *    je vedle plný kámen - jinak by pochodeň u zdi zmizela.
 *
 * 2) Blok s nekrychlovým modelem NENÍ neprůhledný (World.isOpaque). Kdyby byl,
 *    zahodil by stěny sousedů a za pochodní by byla díra do prázdna.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, takže jde celé otestovat headless.
 *
 * Co tenhle systém NEUMÍ: šikmé plochy. Květiny a sazenice jsou v Minecraftu
 * dva zkřížené obdélníky, ne hranoly - na ně bude potřeba model ze čtyřúhelníků.
 */
public final class BlockModels {

    /**
     * Kvádr v souřadnicích bloku, 0 až 1.
     * Pro čitelnost se rozměry píšou ve šestnáctinách, jako v Minecraftu.
     */
    public record BlockBox(float minX, float minY, float minZ,
                           float maxX, float maxY, float maxZ) {

        /** Zápis ve šestnáctinách bloku - 0 až 16. */
        public static BlockBox px(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
        {
            return new BlockBox(minX / 16f, minY / 16f, minZ / 16f,
                    maxX / 16f, maxY / 16f, maxZ / 16f);
        }
    }

    private static final BlockBox[] CUBE = {
            new BlockBox(0f, 0f, 0f, 1f, 1f, 1f)
    };

    /** Tenká tyčka uprostřed bloku, sahající do dvou třetin výšky. */
    private static final BlockBox[] TORCH = {
            BlockBox.px(7, 0, 7, 9, 10, 9)
    };

    /**
     * Zatím jen sloupek. Příčky by se musely napojovat podle sousedů, což je
     * model závislý na okolí - další krok, ne tenhle.
     */
    private static final BlockBox[] FENCE = {
            BlockBox.px(6, 0, 6, 10, 16, 10)
    };

    private BlockModels() {}

    public static BlockBox[] of(byte blockId)
    {
        return switch(blockId)
        {
            case World.TORCH -> TORCH;
            case World.FENCE -> FENCE;
            default -> CUBE;
        };
    }

    /** Je blok obyčejná plná krychle? Rozhoduje o tom, jestli smí zakrývat sousedy. */
    public static boolean isFullCube(byte blockId)
    {
        return of(blockId) == CUBE;
    }
}
