package mc;

public class Raycaster {

    /**
     * Zásah paprsku.
     *
     * (x, y, z)    = souřadnice zasaženého bloku
     * (nx, ny, nz) = normála zasažené stěny: jednotkový vektor, který míří
     *                z bloku VEN směrem k nám. Vždycky je nenulová jen v jedné
     *                ose, protože do buňky se dá vstoupit jen přes jednu stěnu.
     *                Např. když se dívám na blok shora, normála je (0, 1, 0).
     *
     * Normála je to, co odlišuje těžení od pokládání: těží se zasažený blok,
     * ale pokládá se do SOUSEDNÍ buňky před tou stěnou, tedy blok + normála.
     */
    public record RaycastHit(int x, int y, int z, int nx, int ny, int nz) {

        /** Souřadnice buňky před zasaženou stěnou - sem patří nově položený blok. */
        public int placeX() { return x + nx; }
        public int placeY() { return y + ny; }
        public int placeZ() { return z + nz; }
    }

    public static RaycastHit cast(World world, float startX, float startY, float startZ,
                                  float dirX, float dirY, float dirZ, float maxDistance) {

        int blockX = (int) Math.floor(startX);
        int blockY = (int) Math.floor(startY);
        int blockZ = (int) Math.floor(startZ);

        // Když už stojíme hlavou uvnitř bloku, vrátíme ho hned - jinak by ho
        // smyčka přeskočila (nejdřív krokuje, až pak testuje). Normála je nulová,
        // protože jsme do bloku nevstoupili přes žádnou stěnu; pokládání pak
        // míří na tu samou (obsazenou) buňku a World.placeBlock ho odmítne.
        // ⚠️ isTargetable, ne isSolid: pochodeň se dá rozbít, i když se skrz
        // ni prochází. Voda se naopak nezaměřuje, i když ji hráč "protíná".
        if(world.isTargetable(blockX, blockY, blockZ))
        {
            return new RaycastHit(blockX, blockY, blockZ, 0, 0, 0);
        }

        int stepX = dirX > 0 ? 1 : -1;
        int stepY = dirY > 0 ? 1 : -1;
        int stepZ = dirZ > 0 ? 1 : -1;

        // vzdálenost (podél paprsku) k překročení JEDNÉ celé hranice bloku v dané ose
        float tDeltaX = Math.abs(1f / dirX);
        float tDeltaY = Math.abs(1f / dirY);
        float tDeltaZ = Math.abs(1f / dirZ);

        // vzdálenost k NEJBLIŽŠÍ hranici bloku v dané ose (od aktuální pozice)
        float tMaxX = firstBoundary(startX, blockX, dirX);
        float tMaxY = firstBoundary(startY, blockY, dirY);
        float tMaxZ = firstBoundary(startZ, blockZ, dirZ);

        float traveled = 0f;

        // normála stěny, kterou jsme vstoupili do právě testované buňky
        int normalX = 0, normalY = 0, normalZ = 0;

        while (traveled < maxDistance) {
            if(tMaxX < tMaxY && tMaxX < tMaxZ)
            {
                blockX += stepX;
                traveled = tMaxX;
                tMaxX += tDeltaX;

                // Krok byl v ose X, takže jsme prošli stěnou kolmou na X.
                // Normála té stěny míří PROTI směru kroku (ven z bloku, k nám):
                // jdu-li doprava (stepX = +1), vstoupil jsem levou stěnou, jejíž
                // normála je -1. Ostatní osy vynulovat - stěna je kolmá jen na jednu.
                normalX = -stepX; normalY = 0; normalZ = 0;
            }
            else if(tMaxY < tMaxZ)
            {
                blockY += stepY;
                traveled = tMaxY;
                tMaxY += tDeltaY;

                normalX = 0; normalY = -stepY; normalZ = 0;
            }
            else
            {
                blockZ += stepZ;
                traveled = tMaxZ;
                tMaxZ += tDeltaZ;

                normalX = 0; normalY = 0; normalZ = -stepZ;
            }

            // Do buňky jsme vstoupili až ZA dosahem - ta už se nepočítá.
            // Dřív se testovala, takže paprsek trefil blok až o buňku dál,
            // než je maxDistance.
            if(traveled > maxDistance)
            {
                break;
            }

            if(world.isTargetable(blockX, blockY, blockZ))
            {
                return new RaycastHit(blockX, blockY, blockZ, normalX, normalY, normalZ);
            }
        }

        return null; // nic nenalezeno
    }

    /**
     * Vzdálenost podél paprsku k první hranici bloku v jedné ose.
     *
     * ⚠️ NULOVÁ SLOŽKA SMĚRU = NEKONEČNO, ne dělení nulou. Paprsek, který se
     * v ose nehýbe, její hranici nikdy nepřekročí. Dřív se dělilo i tady:
     * start přesně na celé souřadnici dal 0/0 = NaN a NaN v Y nebo Z
     * zablokoval výběr os (každé porovnání s NaN je false), takže se
     * krokovalo v Z, `traveled` vyšlo NaN a smyčka skončila bez zásahu -
     * hráč na z = 8,0 s pohledem po +X nezaměřil kámen tři bloky před sebou.
     * (Mimo start na celé souřadnici vyšlo x/0 = nekonečno náhodou správně.)
     */
    static float firstBoundary(float start, int block, float dir)
    {
        if(dir == 0f)   // platí i pro -0,0
        {
            return Float.POSITIVE_INFINITY;
        }

        return (dir > 0 ? (block + 1) - start : start - block) / Math.abs(dir);
    }
}
