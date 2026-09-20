package mc;

/**
 * Generátor terénu jednoho světa: výšky, jeskyně, rudy a stromy.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ NEMĚNNÁ TŘÍDA, A JE TO PODMÍNKA, NE OZDOBA. generateColumn() volá worker
 * vlákno bez jediného zámku a smí to jen proto, že je to čistá funkce
 * souřadnic - všechna pole jsou final a sdílený stav tu žádný není. Dokud
 * generátor neumí nic jiného než číst ze svých tabulek, nemůže vzniknout
 * stav, kdy hlavní vlákno čte rozdělaná data. Oddělení od World to drží
 * konstrukčně: generátor na mapu sloupců ani na změny hráče nevidí.
 *
 * ⚠️ SE SEEDEM World.DEFAULT_SEED MUSÍ VYJÍT BIT PO BITU TENTÝŽ TERÉN JAKO
 * PŘED ZAVEDENÍM SEEDŮ. Staré uložené světy mají pod stavbami terén z toho
 * seedu a GENERATOR_VERSION se kvůli tomu nesmí zvyšovat. Proto je míchání
 * seedu do hashů udělané tak, aby u výchozího seedu nebylo vidět:
 * seedMix je pro něj nula a XOR nulou nic nezmění. SeedTest to hlídá
 * kontrolním součtem změřeným na kódu před refaktorem.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL.
 */
public final class TerrainGenerator {

    // --- parametry generování terénu ---
    private static final double TERRAIN_FREQUENCY = 0.007;
    private static final int    TERRAIN_AMPLITUDE = 20;
    private static final int    GROUND_HEIGHT     = 64;

    /** Kolik oktáv se sečte ve fbm(). Víc = víc detailu, ale i víc volání noise. */
    private static final int TERRAIN_OCTAVES = 4;

    /** Pod touhle výškou je povrch písčitý místo travnatého - dělá to údolím pláže. */
    private static final int SAND_LEVEL = GROUND_HEIGHT - 8;

    /** Kolik vrstev hlíny je pod trávou, než začne kámen. */
    private static final int SOIL_DEPTH = 4;

    // --- jeskyně ---

    /**
     * ⚠️ Frekvence řídí ŠÍŘKU chodeb, ne jejich množství.
     *
     * Šířka chodby je zhruba práh děleno gradientem šumu, a gradient škáluje
     * s frekvencí - nižší frekvence tedy chodby rozšíří, aniž by vykopala víc
     * horniny. Zvýšení prahu naopak zvětší i objem. Když je potřeba "větší
     * jeskyně, ne víc jeskyní", sahá se sem, ne na práh.
     */
    private static final double CAVE_FREQUENCY = 0.018;

    /**
     * Jak tlustý pás kolem nulové hladiny šumu se vykope.
     *
     * Mění se s hloubkou: mělko úzké chodby, hluboko síně. Je to zadarmo
     * (žádné další volání šumu) a dává to sestupu do hloubky smysl.
     */
    private static final double CAVE_THRESHOLD_SHALLOW = 0.12;
    private static final double CAVE_THRESHOLD_DEEP    = 0.20;

    /** Nad touhle výškou už se práh dál nemění. */
    private static final int CAVE_SHALLOW_Y = 50;

    /**
     * Pod touhle výškou se nekope. Svět má dno na y = 0 a kdyby se prokopalo
     * až tam, dala by se jeskyní propadnout ven ze světa.
     */
    private static final int CAVE_MIN_Y = 3;

    /**
     * Posun druhého šumového pole. Musí být velký a nekulatý, jinak by obě
     * pole byla korelovaná a průnik by nedal chodby, ale jen tenčí desky.
     */
    private static final double CAVE_OFFSET_X = 311.7;
    private static final double CAVE_OFFSET_Y = -197.3;
    private static final double CAVE_OFFSET_Z = 47.9;

    // --- rudy ---

    /** Hrana žilné buňky je 1 << ORE_BITS bloků, tedy 4. */
    private static final int ORE_BITS = 2;

    private static final int COAL_MIN_Y = 5,  COAL_MAX_Y = 72;
    private static final int IRON_MIN_Y = 3,  IRON_MAX_Y = 42;

    /**
     * Jedna buňka z tolika nese žílu. Vyšší číslo = vzácnější ruda.
     * Hodnoty jsou doladěné podle měření v CaveTest: při 12 a 24 vycházelo
     * 37 uhlí na 1000 kamene, což byly celé stěny uhlí místo občasného nálezu.
     */
    private static final int COAL_RARITY = 30;
    private static final int IRON_RARITY = 60;

    // Odlišují hashe jednotlivých rud, jinak by ležely na stejných místech.
    private static final int COAL_SALT = 0x51ED;
    private static final int IRON_SALT = 0x2F19;

    // --- stromy ---

    /** Hrana buňky, ve které smí stát nejvýš jeden strom: 1 << 3 = 8 bloků. */
    private static final int TREE_CELL_BITS = 3;
    private static final int TREE_CELL_MASK = (1 << TREE_CELL_BITS) - 1;

    /** V kolika buňkách z pěti strom vyroste. Změřeno v TreeTest. */
    private static final int TREE_DENSITY = 3;

    private static final int TREE_SALT = 0x7A31;

    private static final int TRUNK_MIN = 4;
    private static final int TRUNK_VARIANTS = 3;   // 4 až 6 bloků

    /**
     * ⚠️ Jak daleko od sloupce se ještě musí hledat kmeny.
     *
     * Koruna je široká 5 bloků, takže strom stojící až dva bloky ZA hranicí
     * chunku do něj pořád zasahuje listím. Kdyby se procházel jen vlastní
     * sloupec, byly by na každé hranici chunku useknuté koruny.
     */
    private static final int TREE_REACH = 2;

    /** Generátor výchozího seedu - dokud si hra seed nepamatuje, jede na něm. */
    static final TerrainGenerator DEFAULT = new TerrainGenerator(World.DEFAULT_SEED);

    private final long seed;
    private final SimplexNoise noise;

    /**
     * Příměs seedu do všech hashů (stromy, rudy).
     *
     * ⚠️ Pro World.DEFAULT_SEED je to NULA, takže se hash chová přesně jako
     * dřív. Mixer je bijekce s fmix64(0) == 0, takže nulu nedá žádný jiný seed
     * a dva různé seedy se nepotkají na jednom čísle jen tak.
     */
    private final int seedMix;

    public TerrainGenerator(long seed)
    {
        this.seed = seed;
        this.noise = new SimplexNoise(seed);
        this.seedMix = (int) fmix64(seed ^ World.DEFAULT_SEED);
    }

    public long seed()
    {
        return seed;
    }

    // ------------------------------------------------------------------
    // sloupec
    // ------------------------------------------------------------------

    /**
     * Vygeneruje celý sloupec chunků. Čistá funkce souřadnic - dva sloupce
     * se stejným seedem vyjdou stejně, ať se počítají kdykoliv a na kterémkoliv
     * vlákně. Na tom stojí asynchronní generování i navazování stromů a žil
     * přes hranice chunků.
     */
    public ChunkColumn generateColumn(int cx, int cz)
    {
        ChunkColumn column = new ChunkColumn(cx, cz);

        for(int lx = 0; lx < Chunk.SIZE; lx++)
        {
            for(int lz = 0; lz < Chunk.SIZE; lz++)
            {
                // Noise se krmí SVĚTOVÝMI souřadnicemi, ne lokálními.
                // Díky tomu na sebe terén přes hranice chunků navazuje spojitě
                // a sloupec se dá vygenerovat kdykoliv nezávisle na sousedech.
                int wx = (cx << Chunk.BITS) + lx;
                int wz = (cz << Chunk.BITS) + lz;

                int height = (int) (GROUND_HEIGHT + fbm(wx, wz) * TERRAIN_AMPLITUDE);

                // V nížinách je povrch písčitý - levný způsob, jak dostat
                // do světa nějakou barevnou různorodost bez biomů.
                boolean sandy = height < SAND_LEVEL;
                byte surface    = sandy ? World.SAND : World.GRASS;
                byte subsurface = sandy ? World.SAND : World.DIRT;

                for(int y = 0; y < height; y++)
                {
                    byte block;
                    if(y == height - 1)
                    {
                        block = surface;
                    }
                    else if(y >= height - SOIL_DEPTH)
                    {
                        block = subsurface;
                    }
                    else
                    {
                        block = World.STONE;
                    }

                    // Jeskyně a rudy se týkají JEN kamene. Povrchové vrstvy
                    // zůstávají netknuté, takže na povrchu nemůže vzniknout díra
                    // ani viset tráva ve vzduchu - za cenu toho, že jeskyně
                    // nemají vchody a musí se k nim dokopat.
                    if(block == World.STONE)
                    {
                        if(isCave(wx, y, wz))
                        {
                            continue;   // nic se nenastaví, zůstane vzduch
                        }

                        block = oreAt(wx, y, wz);
                    }

                    column.set(lx, y, lz, block);
                }

                // Voda se nalévá AŽ NAD terén, takže se jeskyně nemůžou zaplavit.
                // Drží to i díky tomu, že se kope jen v kameni: mezi dnem jezera
                // a nejvyšším možným stropem jeskyně jsou vždycky vrstvy půdy.
                for(int y = height; y < World.SEA_LEVEL; y++)
                {
                    column.set(lx, y, lz, World.WATER);
                }
            }
        }

        stampTrees(column, cx, cz);

        return column;
    }

    // ------------------------------------------------------------------
    // povrch
    // ------------------------------------------------------------------

    /**
     * Fractal Brownian Motion - sečte několik oktáv šumu přes sebe.
     *
     * Jedna oktáva dá jen hladké kopce jedné velikosti. Každá další oktáva
     * má DVOJNÁSOBNOU frekvenci (jemnější tvary) a POLOVIČNÍ amplitudu
     * (menší vliv), takže přidává detail, aniž by převálcovala základní tvar.
     * Právě tenhle poměr 2x / 0,5x dělá terén, který vypadá přirozeně
     * v malém i velkém měřítku.
     *
     * Součet se na konci vydělí sumou amplitud, aby výsledek zůstal
     * zhruba v rozsahu <-1, 1> nezávisle na počtu oktáv.
     */
    private double fbm(int worldX, int worldZ)
    {
        double sum = 0;
        double amplitude = 1;
        double frequency = TERRAIN_FREQUENCY;
        double totalAmplitude = 0;

        for(int octave = 0; octave < TERRAIN_OCTAVES; octave++)
        {
            sum += noise.sample(worldX * frequency, worldZ * frequency) * amplitude;
            totalAmplitude += amplitude;

            frequency *= 2.0;
            amplitude *= 0.5;
        }

        return sum / totalAmplitude;
    }

    /**
     * Výška terénu na dané pozici, tedy počet bloků odspodu.
     *
     * Čistá funkce šumu - nepotřebuje vygenerovaný sloupec, takže se dá volat
     * i na místa, kam se hráč teprve chystá. Používá to hledání spawnu.
     */
    public int terrainHeight(int worldX, int worldZ)
    {
        return (int) (GROUND_HEIGHT + fbm(worldX, worldZ) * TERRAIN_AMPLITUDE);
    }

    /**
     * Najde nejbližší suchou pozici pro spawn.
     *
     * ⚠️ Bez tohohle se hráč u počátku souřadnic objeví po pás ve vodě - terén
     * tam vychází níž než hladina. Prohledává se ve čtvercích od středu ven,
     * takže se vrátí opravdu nejbližší souš, ne první nalezená v řádku.
     *
     * Když se v celém okruhu nic nenajde, vrátí se původní bod: spawnout se
     * ve vodě je pořád lepší než neexistující svět.
     */
    public int[] findLandSpawn(int preferredX, int preferredZ, int maxRadius)
    {
        if(terrainHeight(preferredX, preferredZ) >= World.SEA_LEVEL)
        {
            return new int[]{preferredX, preferredZ};
        }

        for(int radius = 1; radius <= maxRadius; radius++)
        {
            for(int dx = -radius; dx <= radius; dx++)
            {
                for(int dz = -radius; dz <= radius; dz++)
                {
                    // Jen obvod čtverce - vnitřek už prošly menší poloměry.
                    if(Math.abs(dx) != radius && Math.abs(dz) != radius)
                    {
                        continue;
                    }

                    int x = preferredX + dx;
                    int z = preferredZ + dz;

                    if(terrainHeight(x, z) >= World.SEA_LEVEL)
                    {
                        return new int[]{x, z};
                    }
                }
            }
        }

        return new int[]{preferredX, preferredZ};
    }

    // ------------------------------------------------------------------
    // stromy
    // ------------------------------------------------------------------

    /**
     * Vyrazítkuje do sloupce všechny stromy, které do něj zasahují - včetně těch,
     * jejichž kmen stojí až za hranicí chunku. Viz TREE_REACH.
     */
    private void stampTrees(ChunkColumn column, int cx, int cz)
    {
        int baseX = cx << Chunk.BITS;
        int baseZ = cz << Chunk.BITS;

        for(int tx = baseX - TREE_REACH; tx < baseX + Chunk.SIZE + TREE_REACH; tx++)
        {
            for(int tz = baseZ - TREE_REACH; tz < baseZ + Chunk.SIZE + TREE_REACH; tz++)
            {
                if(hasTree(tx, tz))
                {
                    placeTree(column, baseX, baseZ, tx, tz);
                }
            }
        }
    }

    /**
     * Stojí na téhle pozici kmen?
     *
     * Svět je rozdělený na buňky 8x8 a v každé smí být nejvýš jeden strom, na
     * pozici určené hashem. Zaručí to rozestupy - kdyby se házelo mincí pro
     * každý blok zvlášť, stromy by rostly ve shlucích jeden na druhém.
     *
     * Celý test závisí jen na světové pozici (a na seedu), takže strom vyjde
     * stejně, ať se na něj ptá kterýkoliv ze sousedních sloupců.
     */
    boolean hasTree(int worldX, int worldZ)
    {
        int cellX = worldX >> TREE_CELL_BITS;
        int cellZ = worldZ >> TREE_CELL_BITS;

        int cell = hash(cellX, 0, cellZ, TREE_SALT);

        if(cell % 5 >= TREE_DENSITY)
        {
            return false;
        }

        int offsetX = (cell >> 8) & TREE_CELL_MASK;
        int offsetZ = (cell >> 12) & TREE_CELL_MASK;

        if(worldX != (cellX << TREE_CELL_BITS) + offsetX
                || worldZ != (cellZ << TREE_CELL_BITS) + offsetZ)
        {
            return false;
        }

        // Jen na trávě: pod SAND_LEVEL je povrch písčitý a pod SEA_LEVEL navíc
        // pod vodou. Strom v jezeře ani na pláži nechceme.
        int height = terrainHeight(worldX, worldZ);

        return height >= SAND_LEVEL
                && height + TRUNK_MIN + TRUNK_VARIANTS + 2 < World.WORLD_HEIGHT;
    }

    private int trunkHeight(int worldX, int worldZ)
    {
        return TRUNK_MIN + (hash(worldX, 1, worldZ, TREE_SALT) % TRUNK_VARIANTS);
    }

    /**
     * Vyrazítkuje jeden strom; zapíše jen ty bloky, které padnou do tohohle sloupce.
     *
     * Tvar je klasický dub: kmen a kolem jeho vrcholu koruna ze čtyř vrstev -
     * dvě široké 5x5 s useknutými rohy, nad nimi 3x3 a špička.
     */
    private void placeTree(ChunkColumn column, int baseX, int baseZ,
                           int treeX, int treeZ)
    {
        int ground = terrainHeight(treeX, treeZ);
        int trunk = trunkHeight(treeX, treeZ);
        int top = ground + trunk - 1;

        // Koruna: pro každou vrstvu její poloměr a jestli se ořezávají rohy.
        for(int layer = 0; layer < 4; layer++)
        {
            int y = top - 2 + layer;
            int radius = layer < 2 ? 2 : 1;
            boolean trimCorners = layer < 2 || layer == 3;

            for(int dx = -radius; dx <= radius; dx++)
            {
                for(int dz = -radius; dz <= radius; dz++)
                {
                    if(trimCorners && Math.abs(dx) == radius && Math.abs(dz) == radius)
                    {
                        continue;
                    }

                    setIfAir(column, baseX, baseZ, treeX + dx, y, treeZ + dz, World.LEAVES);
                }
            }
        }

        // Kmen až nakonec, aby přebil listí, které mu vyšlo do cesty.
        for(int y = ground; y <= top; y++)
        {
            set(column, baseX, baseZ, treeX, y, treeZ, World.LOG);
        }
    }

    private static void setIfAir(ChunkColumn column, int baseX, int baseZ,
                                 int worldX, int y, int worldZ, byte block)
    {
        int lx = worldX - baseX;
        int lz = worldZ - baseZ;

        if(lx < 0 || lx >= Chunk.SIZE || lz < 0 || lz >= Chunk.SIZE
                || y < 0 || y >= World.WORLD_HEIGHT)
        {
            return;   // patří sousednímu sloupci, ten si to vyrazítkuje sám
        }

        if(column.get(lx, y, lz) == World.AIR)
        {
            column.set(lx, y, lz, block);
        }
    }

    private static void set(ChunkColumn column, int baseX, int baseZ,
                            int worldX, int y, int worldZ, byte block)
    {
        int lx = worldX - baseX;
        int lz = worldZ - baseZ;

        if(lx >= 0 && lx < Chunk.SIZE && lz >= 0 && lz < Chunk.SIZE
                && y >= 0 && y < World.WORLD_HEIGHT)
        {
            column.set(lx, y, lz, block);
        }
    }

    // ------------------------------------------------------------------
    // podzemí
    // ------------------------------------------------------------------

    /**
     * Je na téhle pozici jeskyně?
     *
     * Použijí se DVĚ nezávislá 3D šumová pole a kope se tam, kde je obě mají
     * blízko nuly. Proč ne jedno: množina |šum| < práh je okolí nulové plochy,
     * tedy zvlněná DESKA - jedno pole by udělalo rozlehlé pukliny přes celý
     * svět. Průnik dvou takových desek je křivka, a ta zesílená na pár bloků
     * dá přesně to, co se od jeskyně čeká: propletené chodby.
     *
     * První pole se vyhodnocuje samo a při neúspěchu se hned končí. Podmínku
     * splní jen pár procent bloků, takže druhé volání šumu odpadne u naprosté
     * většiny z nich - a generování sloupce se tím zlevní skoro na polovinu.
     */
    boolean isCave(int worldX, int worldY, int worldZ)
    {
        if(worldY < CAVE_MIN_Y)
        {
            return false;
        }

        double threshold = caveThreshold(worldY);

        double x = worldX * CAVE_FREQUENCY;
        double y = worldY * CAVE_FREQUENCY;
        double z = worldZ * CAVE_FREQUENCY;

        if(Math.abs(noise.sample(x, y, z)) >= threshold)
        {
            return false;
        }

        return Math.abs(noise.sample(
                x + CAVE_OFFSET_X, y + CAVE_OFFSET_Y, z + CAVE_OFFSET_Z)) < threshold;
    }

    /** Lineární přechod mezi úzkými chodbami nahoře a síněmi dole. */
    private static double caveThreshold(int worldY)
    {
        if(worldY >= CAVE_SHALLOW_Y)
        {
            return CAVE_THRESHOLD_SHALLOW;
        }

        double t = (worldY - CAVE_MIN_Y) / (double) (CAVE_SHALLOW_Y - CAVE_MIN_Y);
        return CAVE_THRESHOLD_DEEP + (CAVE_THRESHOLD_SHALLOW - CAVE_THRESHOLD_DEEP) * t;
    }

    /** Jaký kámen tu leží - obyčejný, nebo rudný. */
    byte oreAt(int worldX, int worldY, int worldZ)
    {
        // Železo se testuje první: je vzácnější, takže by ho uhlí v překryvu
        // hloubek jinak skoro celé přebilo.
        if(worldY >= IRON_MIN_Y && worldY <= IRON_MAX_Y
                && vein(worldX, worldY, worldZ, IRON_SALT, IRON_RARITY))
        {
            return World.IRON_ORE;
        }

        if(worldY >= COAL_MIN_Y && worldY <= COAL_MAX_Y
                && vein(worldX, worldY, worldZ, COAL_SALT, COAL_RARITY))
        {
            return World.COAL_ORE;
        }

        return World.STONE;
    }

    /**
     * Leží tenhle blok v žíle?
     *
     * Svět je rozdělený na buňky 4x4x4 a hash rozhodne, které z nich žílu nesou.
     * Uvnitř buňky se vyplní zhruba koule kolem jejího středu, aby žíla nebyla
     * konfety, a okraj se hashem roztřepí, aby nebyla koule.
     *
     * Celý test závisí jen na světové pozici, takže žíly na hranicích chunků
     * navazují samy od sebe - není potřeba nic dogenerovávat do sousedů.
     */
    private boolean vein(int worldX, int worldY, int worldZ, int salt, int rarity)
    {
        // ⚠️ >> a ne / : dělení zaokrouhluje k nule, takže buňka kolem nuly
        // by byla dvakrát široká - stejná past jako u převodu na chunky.
        int cellX = worldX >> ORE_BITS;
        int cellY = worldY >> ORE_BITS;
        int cellZ = worldZ >> ORE_BITS;

        int cell = hash(cellX, cellY, cellZ, salt);

        if(cell % rarity != 0)
        {
            return false;
        }

        // Střed a poloměr se berou z téhož hashe, jen z jiných bitů - dvě žíly
        // vedle sebe tak nejsou stejně velké.
        int centerX = (cellX << ORE_BITS) + 1 + ((cell >> 8) & 1);
        int centerY = (cellY << ORE_BITS) + 1 + ((cell >> 9) & 1);
        int centerZ = (cellZ << ORE_BITS) + 1 + ((cell >> 10) & 1);

        int dx = worldX - centerX;
        int dy = worldY - centerY;
        int dz = worldZ - centerZ;

        if(dx * dx + dy * dy + dz * dz > 2 + ((cell >> 12) & 3))
        {
            return false;
        }

        return (hash(worldX, worldY, worldZ, salt) & 7) != 0;
    }

    // ------------------------------------------------------------------
    // hash a seed
    // ------------------------------------------------------------------

    /**
     * Rozhoz bitů z pozice, soli a seedu. Vrací nezáporné číslo, aby se dalo
     * bez překvapení použít % - záporný zbytek by u testu vzácnosti tiše
     * vyřadil půlku světa.
     *
     * ⚠️ Seed se přimíchává XORem PŘED mícháním bitů, ne přičtením. Přičtení
     * by bylo k ničemu: lineární část hashe je v x invertovatelná, takže
     * jakákoliv přičtená konstanta odpovídá jen posunu světa v ose x - dva
     * seedy by daly týž vzor stromů a žil, jen jinde.
     */
    private int hash(int x, int y, int z, int salt)
    {
        int h = (x * 374761393 + y * 668265263 + z * 1274126177 + salt * 972897169) ^ seedMix;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) >>> 1;
    }

    /** Murmur3 finalizer. Bijekce, a fmix64(0) == 0 - na tom stojí výchozí seed. */
    private static long fmix64(long k)
    {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
