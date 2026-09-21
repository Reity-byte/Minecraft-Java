package mc;

/**
 * Tvar jednoho stromu: které bloky a kam.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ VYTAŽENO Z `TerrainGenerator`, ABY HO MOHL POUŽÍT I NÁHLED V LABU.
 * Náhled stromu v Ore/Biome Toneru musí ukázat TEN STROM, který ve světě
 * opravdu vyroste - ne podobný. Tohle je proto jediné místo, kde je napsané,
 * jak strom vypadá; generátor i náhled si ho oba razítkují odsud, jen
 * s jiným `Sink`. Kdyby měl náhled vlastní kopii, rozešly by se ty dva
 * tvary při první změně a lab by lhal - tatáž úvaha, proč se Recipe Lab
 * ptá `Recipes.match()` a proč náhled bloku staví `ChunkMesh.build()`.
 *
 * ⚠️ KMEN AŽ PO LISTÍ. Listí zapisuje jen do vzduchu (neprožere terén ani
 * sousední strom), kmen přebíjí - takže se musí razítkovat jako druhý, jinak
 * by mu listí, které mu vyšlo do cesty, zůstalo v kmeni.
 *
 * ⚠️ POSLEDNÍ VRSTVA KORUNY LEŽÍ O BLOK NAD VRCHOLEM KMENE, odtud posun
 * `layers - 2` u první vrstvy. Bez toho by kmen končil holým špalkem.
 * ---------------------------------------------------------------------------
 *
 * Čistá funkce - žádný stav, žádné GL, žádný šum. Náhodnost (jak vysoký kmen
 * a jak velká koruna) rozhoduje volající, protože jen on ví, z čeho ji brát:
 * generátor z hashe pozice, náhled z tlačítka Reroll.
 */
public final class TreeShape {

    private TreeShape() {}

    /** Kam se strom razítkuje. Generátor do sloupce, náhled do malého světa. */
    public interface Sink {

        /** Listí - zapíše se JEN tam, kde je vzduch. */
        void leaves(int x, int y, int z, byte block);

        /** Kmen - přebije, co na tom místě je. */
        void log(int x, int y, int z, byte block);
    }

    /**
     * Vyrazítkuje strom druhu `type` s kmenem `trunk` bloků vysokým, jehož
     * pata stojí na `ground`.
     *
     * `crownDelta` posouvá poloměr KAŽDÉ vrstvy koruny: 0 je tvar, jaký má
     * druh v datech, +1 o blok širší koruna, -1 užší.
     *
     * ⚠️ VRSTVA S POLOMĚREM 0 ZŮSTÁVÁ NULOVÁ. Smrk má špičku jako jeden blok
     * a kdyby ji delta zvětšila, přestal by být kužel a stal by se z něj
     * další dub s useknutým vrškem - silueta druhu se rozsahem velikosti
     * měnit nemá, jen jeho velikost.
     */
    public static void stamp(Biome.TreeType type, int trunk, int crownDelta,
                             int treeX, int ground, int treeZ, Sink sink)
    {
        if(type == null || type == Biome.TreeType.NONE || trunk < 1)
        {
            return;
        }

        int top = ground + trunk - 1;
        int layers = type.layerRadius.length;

        for(int layer = 0; layer < layers; layer++)
        {
            int y = top - (layers - 2) + layer;
            int base = type.layerRadius[layer];
            int radius = base == 0 ? 0 : Math.max(0, base + crownDelta);
            boolean trimCorners = type.layerTrim[layer];

            for(int dx = -radius; dx <= radius; dx++)
            {
                for(int dz = -radius; dz <= radius; dz++)
                {
                    if(trimCorners && Math.abs(dx) == radius && Math.abs(dz) == radius)
                    {
                        continue;
                    }

                    sink.leaves(treeX + dx, y, treeZ + dz, type.leaves);
                }
            }
        }

        // Kmen až nakonec, aby přebil listí, které mu vyšlo do cesty.
        for(int y = ground; y <= top; y++)
        {
            sink.log(treeX, y, treeZ, type.log);
        }
    }

    /**
     * Největší poloměr, jaký koruna s touhle deltou dostane - tedy jak
     * daleko od kmene strom sahá. Vrstvy s nulovým poloměrem se nepočítají,
     * protože je delta nezvětšuje (viz `stamp`).
     */
    public static int reach(Biome.TreeType type, int crownDelta)
    {
        int max = 0;

        for(int base : type.layerRadius)
        {
            if(base != 0)
            {
                max = Math.max(max, Math.max(0, base + crownDelta));
            }
        }

        return max;
    }

    /**
     * Kolik bloků nad zemí strom s touhle výškou kmene zabere - kmen a nad
     * jeho vrcholem ještě poslední vrstva koruny.
     */
    public static int totalHeight(int trunk)
    {
        return trunk + 2;
    }
}
