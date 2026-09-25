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
 * ⚠️ MÍCHÁNÍ SEEDU DO HAŠŮ JE U VÝCHOZÍHO SEEDU NEVIDITELNÉ. Zavedení seedů
 * GENERATOR_VERSION nezvyšovalo, takže muselo se seedem World.DEFAULT_SEED
 * vyjít bit po bitu totéž co předtím: seedMix je pro něj nula a XOR nulou nic
 * nezmění. Zůstává to tak, i když biomy terén posunuly - ta změna se ohlásila
 * řádně zvýšením GENERATOR_VERSION na 5 a SeedTest má součty přeměřené.
 * Bez toho pravidla by dva různé seedy daly tentýž vzor stromů a žil.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL.
 */
public final class TerrainGenerator {

    // --- parametry generování terénu ---
    private static final double TERRAIN_FREQUENCY = 0.007;
    private static final int    GROUND_HEIGHT     = 64;

    /** Kolik oktáv se sečte ve fbm(). Víc = víc detailu, ale i víc volání noise. */
    private static final int TERRAIN_OCTAVES = 4;

    /** Pod touhle výškou je povrch písčitý místo travnatého - dělá to údolím pláže. */
    private static final int SAND_LEVEL = GROUND_HEIGHT - 8;

    /** Kolik vrstev hlíny je pod trávou, než začne kámen. */
    private static final int SOIL_DEPTH = 4;

    /**
     * ⚠️ Dno výšky terénu. Strop je instanční (`maxTerrainHeight`), protože
     * se počítá z NEJVYŠŠÍHO STROMU a ten je od tuneru tunable - viz tam.
     */
    private static final int MIN_TERRAIN_HEIGHT = 4;

    // --- biomy ---

    /**
     * Frekvence biomové mapy: 0,0018 znamená rys šumu asi 550 bloků, tedy
     * biomy velké stovky bloků. Nižší frekvence = větší biomy, ale taky delší
     * chůze, než hráč nějaký druhý najde; vyšší = mozaika, ve které se biom
     * nepozná. Změřeno v BiomeTest (průměrná délka jednoho biomu podél přímky).
     */
    private static final double BIOME_FREQUENCY = 0.0018;

    /**
     * ⚠️ POSUNY TŘÍ BIOMOVÝCH VRSTEV. Všechny tři vrstvy jedou na TÉŽE
     * permutační tabulce (jeden SimplexNoise na svět), takže bez posunu by
     * teplota, vlhkost i reliéf byly jedna a ta samá funkce - dostal by se
     * z toho jen jeden pruhovaný svět, ne matice biomů. Posuny jsou velké
     * a nekulaté, ze stejného důvodu jako u jeskyní. BiomeTest měří korelaci
     * těch tří vrstev a trvá na tom, že je blízko nule.
     */
    private static final double TEMPERATURE_OFFSET_X = 1731.7;
    private static final double TEMPERATURE_OFFSET_Z = -908.3;
    private static final double HUMIDITY_OFFSET_X = -4127.9;
    private static final double HUMIDITY_OFFSET_Z = 3312.1;
    private static final double RELIEF_OFFSET_X = 8815.3;
    private static final double RELIEF_OFFSET_Z = -6204.7;

    /**
     * Nad touhle výškou je v horách povrch zasněžený.
     *
     * Není to nový blok ani nový mechanismus - je to týž sníh jako v tundře
     * a jediná podmínka. Bez něj jsou hory jen zelené jehly a z dálky se
     * nepoznají od kopců.
     *
     * ⚠️ JE TO TÁŽ ČÁRA JAKO HRANICE LESA, a schválně: kdyby byly dvě, vznikl
     * by mezi nimi pás dubů se zeleným listím stojících ve sněhu. Bere se
     * proto přímo z dat biomu, ne jako vlastní konstanta.
     */
    private static final int MOUNTAIN_SNOW_LINE = Biome.MOUNTAINS.treeLine();

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
    static final int COAL_RARITY = 30;
    static final int IRON_RARITY = 60;

    /**
     * Železo v horách. Trojnásobek množství = třetina vzácnosti, protože
     * rarity je "jedna buňka z tolika nese žílu" - hustota žil je 1/rarity.
     *
     * Proč právě 3x: dvojnásobek by v měření zapadl do rozptylu mezi
     * jednotlivými oblastmi (železo je vzácné, takže i na tisících bloků
     * kamene se počty houpou), pětinásobek by v horách udělal ze železa
     * běžnou rudu a přestalo by být za co lezt. CaveTest ten poměr MĚŘÍ
     * a vypisuje, takže se po každé změně dá přečíst.
     *
     * ⚠️ UŽ SE TÍM NEPOČÍTÁ, POČÍTÁ SE TO Z TUNERU. Zůstává tu jako
     * kontrolní číslo: výchozí `ironDensity` hor je 3, takže
     * `BiomeTuning.rarity(IRON_RARITY, 3.0)` musí vyjít přesně na tuhle
     * dvacítku. CaveTest to porovnává, aby se výchozí tuning nemohl tiše
     * rozejít s tím, co hra dělala před tunerem.
     */
    static final int IRON_RARITY_MOUNTAINS = IRON_RARITY / 3;

    // Odlišují hashe jednotlivých rud, jinak by ležely na stejných místech.
    private static final int COAL_SALT = 0x51ED;
    private static final int IRON_SALT = 0x2F19;

    // --- stromy ---

    /** Hrana buňky, ve které smí stát nejvýš jeden strom: 1 << 3 = 8 bloků. */
    private static final int TREE_CELL_BITS = 3;
    private static final int TREE_CELL_MASK = (1 << TREE_CELL_BITS) - 1;

    private static final int TREE_SALT = 0x7A31;

    /**
     * ⚠️ Jak daleko od sloupce se ještě musí hledat kmeny.
     *
     * Koruna je široká 2·poloměr + 1 bloku, takže strom stojící až o poloměr
     * ZA hranicí chunku do něj pořád zasahuje listím. Kdyby se procházel jen
     * vlastní sloupec, byly by na každé hranici chunku useknuté koruny.
     *
     * ⚠️ POČÍTÁ SE Z DAT, ne napsané ručně. Prales má korunu o poloměru 3
     * (dub jen 2) a kdyby tady zůstala dvojka, ořezaly by se pralesní koruny
     * přesně na švech chunků - a vypadalo by to jako "no tak takhle ten strom
     * vyrostl". Nový druh stromu s ještě širší korunou tím dosah zvětší sám.
     */
    private final int treeReach;

    /** Generátor výchozího seedu - dokud si hra seed nepamatuje, jede na něm. */
    static final TerrainGenerator DEFAULT = new TerrainGenerator(World.DEFAULT_SEED);

    private final long seed;
    private final SimplexNoise noise;

    /**
     * ⚠️ TUNING SE BERE JEDNOU, PŘI VZNIKU GENERÁTORU, A PAK UŽ SE NEMĚNÍ.
     * Generátor musí zůstat neměnný (viz komentář u třídy) - kdyby se ptal
     * `BiomeTuning.active()` za běhu, mohl by uprostřed hry změnit parametry
     * a sousední sloupce by na sebe přestaly navazovat. Změna tuningu se
     * proto projeví u příštího světa, ne v rozehraném.
     */
    private final BiomeTuning tuning;

    /** Vzácnost rud po biomech, spočítaná dopředu z násobků v tuningu. */
    private final int[] ironRarity;
    private final int[] coalRarity;

    /** Hustota stromů po biomech - ať `treeTypeAt()` nesahá do záznamu. */
    private final int[] treeDensity;

    /**
     * Strop výšky terénu. POČÍTÁ SE Z NEJVYŠŠÍHO STROMU, který v tomhle
     * tuningu může vyrůst: nad terénem musí zbýt místo i na tu nejvyšší
     * korunu, jinak by se strom tiše neumístil. S výchozím tuningem vyjde
     * na 114, tedy přesně na to, co tu bylo jako konstanta před tunerem.
     */
    private final int maxTerrainHeight;

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
        this(seed, BiomeTuning.active());
    }

    /**
     * Generátor s výslovně daným tuningem. Používají to testy a náhled
     * stromu v labu, aby nezávisely na tom, co je zrovna aktivní.
     */
    public TerrainGenerator(long seed, BiomeTuning tuning)
    {
        this.seed = seed;
        this.noise = new SimplexNoise(seed);
        this.seedMix = (int) fmix64(seed ^ World.DEFAULT_SEED);
        this.tuning = tuning == null ? BiomeTuning.defaults() : tuning;

        Biome[] biomes = Biome.values();
        this.ironRarity  = new int[biomes.length];
        this.coalRarity  = new int[biomes.length];
        this.treeDensity = new int[biomes.length];

        for(Biome biome : biomes)
        {
            BiomeTuning.Tune tune = this.tuning.tune(biome);
            ironRarity[biome.ordinal()]  = BiomeTuning.rarity(IRON_RARITY, tune.ironDensity());
            coalRarity[biome.ordinal()]  = BiomeTuning.rarity(COAL_RARITY, tune.coalDensity());
            treeDensity[biome.ordinal()] = tune.treeDensity();
        }

        this.treeReach = this.tuning.maxCrownRadius();
        this.maxTerrainHeight = World.WORLD_HEIGHT - this.tuning.maxTreeHeight() - 1;
    }

    /** Tuning, se kterým tenhle generátor vznikl. */
    public BiomeTuning tuning()
    {
        return tuning;
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

        // --- 1. výšky a biomy, i o sloupeček za okrajem chunku ---
        //
        // ⚠️ JESKYNĚ POTŘEBUJÍ VÝŠKU SOUSEDŮ. Ochrana "jeskyně nemají vchody
        // a vodu vidí jen přes půdu" byla dřív jen svislá: kope se jen
        // v kameni, takže nad jeskyní jsou vždycky vrstvy půdy. Vodorovně ale
        // jeskynní vzduch sloupce A sousedí s buňkou sloupce B ve stejné
        // výšce - a když je B o 5 a víc bloků níž, je tam voda nebo otevřený
        // vzduch. S výchozími čísly je největší krok mezi sousedy 3 (změřeno
        // na 3000x3000 sloupcích), s tunerem až 11: stěny vody v chodbách
        // a vchody na útesech. Kope se proto jen pod nejnižším ze čtyř
        // sousedů (caveCeiling níž) - a k tomu je potřeba znát výšku i za
        // hranou chunku. Je to 68 výšek navíc k 256, žádný další 3D šum.
        int span = Chunk.SIZE + 2;
        int[] heights = new int[span * span];
        Biome[] biomes = new Biome[Chunk.SIZE * Chunk.SIZE];

        for(int lx = -1; lx <= Chunk.SIZE; lx++)
        {
            for(int lz = -1; lz <= Chunk.SIZE; lz++)
            {
                // Noise se krmí SVĚTOVÝMI souřadnicemi, ne lokálními.
                // Díky tomu na sebe terén přes hranice chunků navazuje spojitě
                // a sloupec se dá vygenerovat kdykoliv nezávisle na sousedech.
                int wx = (cx << Chunk.BITS) + lx;
                int wz = (cz << Chunk.BITS) + lz;

                // ⚠️ Biom a výška se počítají JEDNOU na sloupeček a pak se
                // předávají dál. Šum pro biom stojí tři vzorky; kdyby se na
                // biom ptal každý blok (třeba rudy kvůli železu v horách),
                // bylo by to tisíckrát za sloupeček místo jednou.
                double temperature = temperatureAt(wx, wz);
                double humidity    = humidityAt(wx, wz);
                double relief      = reliefAt(wx, wz);

                heights[(lx + 1) * span + lz + 1] = heightFrom(wx, wz, temperature, humidity, relief);

                if(lx >= 0 && lx < Chunk.SIZE && lz >= 0 && lz < Chunk.SIZE)
                {
                    biomes[lx * Chunk.SIZE + lz] = Biome.classify(temperature, humidity, relief);
                }
            }
        }

        // --- 2. bloky ---
        for(int lx = 0; lx < Chunk.SIZE; lx++)
        {
            for(int lz = 0; lz < Chunk.SIZE; lz++)
            {
                int wx = (cx << Chunk.BITS) + lx;
                int wz = (cz << Chunk.BITS) + lz;

                Biome biome = biomes[lx * Chunk.SIZE + lz];
                int at = (lx + 1) * span + lz + 1;
                int height = heights[at];
                int ceiling = caveCeiling(heights[at - span], heights[at + span],
                        heights[at - 1], heights[at + 1]);

                byte surface    = surfaceBlock(biome, height);
                byte subsurface = subsurfaceBlock(biome, height);

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
                    // nemají vchody a musí se k nim dokopat. Totéž do stran:
                    // nad `ceiling` by jeskyně vyšla vedle vody nebo vzduchu
                    // nižšího souseda.
                    if(block == World.STONE)
                    {
                        if(y < ceiling && isCave(wx, y, wz))
                        {
                            continue;   // nic se nenastaví, zůstane vzduch
                        }

                        block = oreAt(wx, y, wz, biome);
                    }

                    column.set(lx, y, lz, block);
                }

                // Voda se nalévá AŽ NAD terén, takže se jeskyně nemůžou zaplavit.
                // Drží to díky tomu, že se kope jen v kameni (nad jeskyní jsou
                // vrstvy půdy) a jen pod nejnižším sousedem (vedle jeskyně je
                // v sousedním sloupci pevný blok, ne voda).
                for(int y = height; y < World.SEA_LEVEL; y++)
                {
                    column.set(lx, y, lz, World.WATER);
                }
            }
        }

        stampTrees(column, cx, cz);

        return column;
    }

    /**
     * Nejvyšší y (bez něj), kde smí být jeskyně vzhledem ke čtyřem sousedům:
     * buňka sousedního sloupce ve stejné výšce musí být pevná, tedy pod jeho
     * povrchem (sousední buňka v y je pevná, když y < výška souseda).
     *
     * Diagonály se neřeší: jeskyně je dírou pro hráče ani pro vodu jen přes
     * stěnu, ne přes hranu. S výchozím tuningem je krok mezi sousedy nejvýš
     * 3, takže tahle mez nikdy neklesne pod vlastní strop kamene (výška - 4)
     * a výchozí terén se nezměnil ani o blok - SeedTest prochází beze změny.
     */
    static int caveCeiling(int west, int east, int north, int south)
    {
        return Math.min(Math.min(west, east), Math.min(north, south));
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
     * i na místa, kam se hráč teprve chystá. Používá to hledání spawnu
     * i razítkování stromů ze sousedních sloupců.
     *
     * Stojí SEDM vzorků šumu: čtyři oktávy fbm a tři biomové vrstvy. Před
     * biomy to byly čtyři. Naměřeno v BiomeTest, kolik to udělá na sloupec.
     */
    public int terrainHeight(int worldX, int worldZ)
    {
        return heightFrom(worldX, worldZ,
                temperatureAt(worldX, worldZ),
                humidityAt(worldX, worldZ),
                reliefAt(worldX, worldZ));
    }

    /**
     * Výška, když už jsou hodnoty klimatu spočítané. Existuje proto, aby
     * generateColumn() nemusel tři biomové vzorky dělat dvakrát.
     */
    private int heightFrom(int worldX, int worldZ,
                           double temperature, double humidity, double relief)
    {
        double height = Biome.surfaceHeight(tuning, temperature, humidity, relief,
                fbm(worldX, worldZ));

        return Math.max(MIN_TERRAIN_HEIGHT, Math.min(maxTerrainHeight, (int) height));
    }

    // ------------------------------------------------------------------
    // biomová mapa
    // ------------------------------------------------------------------

    /**
     * Který biom leží na dané pozici.
     *
     * Čistá funkce souřadnic a seedu, přesně jako terrainHeight(), hasTree()
     * a rudné žíly - a ze stejného důvodu: sloupec se musí dát vygenerovat
     * nezávisle na sousedech a na kterémkoliv vlákně. Stejný seed proto dává
     * stejné rozložení biomů při každém načtení světa.
     */
    public Biome biomeAt(int worldX, int worldZ)
    {
        return Biome.classify(
                temperatureAt(worldX, worldZ),
                humidityAt(worldX, worldZ),
                reliefAt(worldX, worldZ));
    }

    /** Teplota: nízkofrekvenční vrstva šumu, zhruba <-1; 1>. */
    public double temperatureAt(int worldX, int worldZ)
    {
        return noise.sample(worldX * BIOME_FREQUENCY + TEMPERATURE_OFFSET_X,
                            worldZ * BIOME_FREQUENCY + TEMPERATURE_OFFSET_Z);
    }

    /** Vlhkost: druhá vrstva, jiný posun. */
    public double humidityAt(int worldX, int worldZ)
    {
        return noise.sample(worldX * BIOME_FREQUENCY + HUMIDITY_OFFSET_X,
                            worldZ * BIOME_FREQUENCY + HUMIDITY_OFFSET_Z);
    }

    /** Reliéf: třetí vrstva - rozhoduje o kopcích a horách bez ohledu na klima. */
    public double reliefAt(int worldX, int worldZ)
    {
        return noise.sample(worldX * BIOME_FREQUENCY + RELIEF_OFFSET_X,
                            worldZ * BIOME_FREQUENCY + RELIEF_OFFSET_Z);
    }

    // ------------------------------------------------------------------
    // povrchové vrstvy
    // ------------------------------------------------------------------

    /**
     * Blok na samém povrchu.
     *
     * ⚠️ PÍSEK U VODY PŘEBÍJÍ BIOM. Pravidlo "pod SAND_LEVEL je písek" tu bylo
     * před biomy a dělá pláže kolem jezer; kdyby ho biom přebil, sahala by
     * v tajze tráva až do vody a pás písku nad hladinou (viz SEA_LEVEL
     * o jedna níž) by zmizel. Poušť je z písku tak jako tak.
     */
    private static byte surfaceBlock(Biome biome, int height)
    {
        if(height < SAND_LEVEL)
        {
            return World.SAND;
        }

        if(biome == Biome.MOUNTAINS && height > MOUNTAIN_SNOW_LINE)
        {
            return World.SNOW;
        }

        return biome.surface();
    }

    private static byte subsurfaceBlock(Biome biome, int height)
    {
        return height < SAND_LEVEL ? World.SAND : biome.subsurface();
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

        for(int tx = baseX - treeReach; tx < baseX + Chunk.SIZE + treeReach; tx++)
        {
            for(int tz = baseZ - treeReach; tz < baseZ + Chunk.SIZE + treeReach; tz++)
            {
                Biome.TreeType type = treeTypeAt(tx, tz);

                if(type != null)
                {
                    placeTree(column, baseX, baseZ, tx, tz, type);
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
        return treeTypeAt(worldX, worldZ) != null;
    }

    /**
     * Jaký strom na téhle pozici stojí, nebo null, když žádný.
     *
     * ⚠️ POŘADÍ TESTŮ JE VÝKONOVÉ ROZHODNUTÍ. Nejdřív se ptá, jestli je pozice
     * vůbec tím jedním místem ve své buňce 8×8 (dva hashe, zamítne 63 pozic
     * ze 64), a teprve pak se sahá na biom (tři vzorky šumu) a na výšku terénu
     * (další sedm). Před biomy se první ptalo na hustotu; kdyby to tak zůstalo,
     * volal by se biomový šum na každou pozici v okolí sloupce, tedy stokrát
     * místo pětkrát.
     */
    Biome.TreeType treeTypeAt(int worldX, int worldZ)
    {
        int cellX = worldX >> TREE_CELL_BITS;
        int cellZ = worldZ >> TREE_CELL_BITS;

        int cell = hash(cellX, 0, cellZ, TREE_SALT);

        int offsetX = (cell >> 8) & TREE_CELL_MASK;
        int offsetZ = (cell >> 12) & TREE_CELL_MASK;

        if(worldX != (cellX << TREE_CELL_BITS) + offsetX
                || worldZ != (cellZ << TREE_CELL_BITS) + offsetZ)
        {
            return null;
        }

        Biome biome = biomeAt(worldX, worldZ);
        Biome.TreeType type = biome.treeType();

        if(type == Biome.TreeType.NONE)
        {
            return null;
        }

        // Hustota z JINÝCH BITŮ hashe než pozice v buňce - jinak by v hustém
        // biomu byly stromy jen v jednom koutu každé buňky.
        if(((cell >> 16) & 15) >= treeDensity[biome.ordinal()])
        {
            return null;
        }

        // Jen na trávě: pod SAND_LEVEL je povrch písčitý a pod SEA_LEVEL navíc
        // pod vodou. Strom v jezeře ani na pláži nechceme.
        int height = terrainHeight(worldX, worldZ);

        if(height < SAND_LEVEL)
        {
            return null;
        }

        // Hranice lesa: v horách nad ní už nic neroste.
        if(height > biome.treeLine())
        {
            return null;
        }

        // A nad korunou musí zbýt místo ve světě. Nejvyšší možný kmen
        // tohohle biomu, ne druhu - rozsah je tunable.
        int tallest = TreeShape.totalHeight(tuning.tune(biome).trunkMax());

        return height + tallest < World.WORLD_HEIGHT ? type : null;
    }

    /**
     * Jak vysoký kmen na téhle pozici vyroste.
     *
     * ⚠️ ROZSAH JE Z TUNERU, NE Z DRUHU. Dřív to byl `type.trunkMin` plus
     * zbytek po `type.trunkVariants`, takže měly všechny stromy jednoho
     * druhu tentýž rozsah ve všech biomech - smrk v tajze i v tundře.
     * Teď si rozsah nese biom, takže smí mít tajga vzrostlé smrky a tundra
     * zakrslé, aniž by přibyl druh stromu.
     *
     * ⚠️ PŘI JEDNOPRVKOVÉM ROZSAHU SE HASH VŮBEC NEVOLÁ. Není to úspora
     * (jeden hash je pár nanosekund) - je to záruka: dokud se min rovná max,
     * je výsledek přesně to jediné číslo, takže se dá na výchozím tuningu
     * porovnat terén bit po bitu s tím, co hra dělala před tunerem.
     *
     * Hash je nezáporný (viz hash()), takže zbytek po dělení je taky
     * nezáporný a kmen nikdy nevyjde kratší než trunkMin.
     */
    int trunkHeight(int worldX, int worldZ, BiomeTuning.Tune tune)
    {
        int variants = tune.trunkVariants();

        if(variants <= 1)
        {
            return tune.trunkMin();
        }

        return tune.trunkMin() + (hash(worldX, 1, worldZ, TREE_SALT) % variants);
    }

    /**
     * O kolik se posune poloměr každé vrstvy koruny proti tvaru druhu.
     *
     * ⚠️ NÁHODNÝ POLOMĚR JE NOVÝ, VÝŠKA KMENE UŽ NÁHODNÁ BYLA. Tohle je ta
     * změna, kvůli které přestanou stromy jednoho druhu vypadat jako kopie:
     * dva duby vedle sebe se dosud lišily jen výškou kmene, protože koruna
     * byla pevné pole poloměrů v datech druhu.
     *
     * ⚠️ JINÁ SOUŘADNICE HASHE NEŽ KMEN (y = 2 proti y = 1). Se stejnou by
     * byl vysoký kmen vždycky spřažený s širokou korunou a les by vypadal
     * jako řada zvětšenin jednoho stromu. A protože se y = 2 dosud nikde
     * nepoužívalo, nemůže tenhle hash změnit ani jedno z dosavadních čísel.
     */
    int crownDelta(int worldX, int worldZ, Biome.TreeType type, BiomeTuning.Tune tune)
    {
        int variants = tune.crownVariants();

        int radius = variants <= 1
                ? tune.crownMin()
                : tune.crownMin() + (hash(worldX, 2, worldZ, TREE_SALT) % variants);

        return radius - type.maxRadius();
    }

    /**
     * Vyrazítkuje jeden strom; zapíše jen ty bloky, které padnou do tohohle sloupce.
     *
     * ⚠️ TVAR JE V `TreeShape`, NE TADY. Tentýž kód razítkuje strom do světa
     * i do náhledu v Ore/Biome Toneru, takže lab nemůže ukázat strom, který
     * ve světě nevyroste - stejné pravidlo jako u náhledu bloku (staví ho
     * `ChunkMesh.build()`) a u náhledu receptu (počítá ho `Recipes.match()`).
     *
     * Biom se tu zjišťuje znovu (tři vzorky šumu), protože rozsah velikosti
     * je jeho. Stojí to jen u skutečných stromů, tedy jednotky případů na
     * sloupec - `treeTypeAt()` zamítne 63 pozic ze 64 dřív, než se sem dojde.
     */
    private void placeTree(ChunkColumn column, int baseX, int baseZ,
                           int treeX, int treeZ, Biome.TreeType type)
    {
        BiomeTuning.Tune tune = tuning.tune(biomeAt(treeX, treeZ));

        int ground = terrainHeight(treeX, treeZ);
        int trunk = trunkHeight(treeX, treeZ, tune);
        int delta = crownDelta(treeX, treeZ, type, tune);

        TreeShape.stamp(type, trunk, delta, treeX, ground, treeZ, new TreeShape.Sink() {

            @Override
            public void leaves(int x, int y, int z, byte block)
            {
                setIfAir(column, baseX, baseZ, x, y, z, block);
            }

            @Override
            public void log(int x, int y, int z, byte block)
            {
                set(column, baseX, baseZ, x, y, z, block);
            }
        });
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

    /**
     * Jaký kámen tu leží - obyčejný, nebo rudný. Pohodlná varianta, která si
     * biom dohledá sama; generateColumn() používá tu s biomem, aby se šum
     * pro biom nepočítal na každý blok znovu.
     */
    byte oreAt(int worldX, int worldY, int worldZ)
    {
        return oreAt(worldX, worldY, worldZ, biomeAt(worldX, worldZ));
    }

    /**
     * Jaký kámen tu leží, když už je biom známý.
     *
     * ⚠️ Biom mění JEN vzácnost železa, nic jiného. Hloubkové pásmo zůstává
     * stejné (y 3-42), takže žíla navíc v horách leží stejně hluboko jako
     * všude jinde - je jí víc, ne výš. Uhlí se nemění vůbec.
     */
    byte oreAt(int worldX, int worldY, int worldZ, Biome biome)
    {
        // Železo se testuje první: je vzácnější, takže by ho uhlí v překryvu
        // hloubek jinak skoro celé přebilo.
        // ⚠️ Vzácnost je spočítaná dopředu v konstruktoru, ne tady dělením.
        // oreAt() běží na KAŽDÉM bloku kamene, takže by se dělení počítalo
        // statisíckrát na sloupec. Nula znamená "tahle ruda v tom biomu
        // není" a žíla se ani nezkusí.
        int iron = ironRarity[biome.ordinal()];
        int coal = coalRarity[biome.ordinal()];

        if(iron > 0 && worldY >= IRON_MIN_Y && worldY <= IRON_MAX_Y
                && vein(worldX, worldY, worldZ, IRON_SALT, iron))
        {
            return World.IRON_ORE;
        }

        if(coal > 0 && worldY >= COAL_MIN_Y && worldY <= COAL_MAX_Y
                && vein(worldX, worldY, worldZ, COAL_SALT, coal))
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
