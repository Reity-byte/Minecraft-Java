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
     * Samotný sloupek. Tak vypadá plot bez sousedů, a tak se kreslí v ruce,
     * v inventáři a na zemi - tam žádní sousedé nejsou.
     */
    private static final BlockBox[] FENCE = {
            BlockBox.px(6, 0, 6, 10, 16, 10)
    };

    /** Bity směrů, kam vede příčka: +X, -X, +Z, -Z. */
    static final int EAST = 1, WEST = 2, SOUTH = 4, NORTH = 8;

    /**
     * Plot ve světě: sloupek a ke každému napojenému sousedovi dvě příčky
     * (2 px silné, ve výšce 6-9 a 12-15 px, jako v Minecraftu) od sloupku
     * k hraně bloku. Všech 16 kombinací je spočítaných dopředu -
     * mesher je jen vybere podle masky, nic nealokuje.
     *
     * Konec příčky leží na hranici bloku: u plného souseda se zahodí
     * (pravidlo 1), u sousedního plotu se potká s jeho příčkou.
     */
    private static final BlockBox[][] FENCE_CONNECTED = new BlockBox[16][];

    static
    {
        for(int mask = 0; mask < 16; mask++)
        {
            java.util.List<BlockBox> boxes = new java.util.ArrayList<>(java.util.List.of(FENCE));

            for(int bar : new int[]{6, 12})
            {
                if((mask & EAST) != 0)  boxes.add(BlockBox.px(10, bar, 7, 16, bar + 3, 9));
                if((mask & WEST) != 0)  boxes.add(BlockBox.px(0, bar, 7, 6, bar + 3, 9));
                if((mask & SOUTH) != 0) boxes.add(BlockBox.px(7, bar, 10, 9, bar + 3, 16));
                if((mask & NORTH) != 0) boxes.add(BlockBox.px(7, bar, 0, 9, bar + 3, 6));
            }

            FENCE_CONNECTED[mask] = boxes.toArray(new BlockBox[0]);
        }
    }

    /**
     * Nejvíc kvádrů, které má kterýkoli model. Podle toho má pevné buffery
     * HeldItemRenderer - model s víc kvádry by jinak tiše přišel o ty navíc.
     */
    static final int MAX_BOXES = Math.max(CUBE.length,
            Math.max(TORCH.length, FENCE_CONNECTED[15].length));

    private BlockModels() {}

    /**
     * Model bloku ve světě, kde záleží na sousedech (zatím jen plot).
     * Sousedé jsou bloky v +X, -X, +Z a -Z. Pro ostatní bloky je to of().
     */
    public static BlockBox[] of(byte blockId, byte east, byte west, byte south, byte north)
    {
        if(blockId != World.FENCE)
        {
            return of(blockId);
        }

        int mask = (fenceConnects(east) ? EAST : 0) | (fenceConnects(west) ? WEST : 0)
                | (fenceConnects(south) ? SOUTH : 0) | (fenceConnects(north) ? NORTH : 0);
        return FENCE_CONNECTED[mask];
    }

    /**
     * Napojí se plot na tenhle blok? Na jiný plot a na plný neprůhledný blok
     * (zeď), jako v Minecraftu. Na listí, vodu, sklo z labu ani pochodeň ne.
     */
    static boolean fenceConnects(byte neighbor)
    {
        return switch(neighbor)
        {
            case World.FENCE -> true;
            // Listí je tu plná krychle (isOpaque), ale v Minecraftu je
            // průsvitné a plot se na něj nenapojuje - živý plot by trčel příčkami.
            case World.LEAVES, World.BIRCH_LEAVES, World.SPRUCE_LEAVES, World.JUNGLE_LEAVES -> false;
            default -> World.isOpaque(neighbor);
        };
    }

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
