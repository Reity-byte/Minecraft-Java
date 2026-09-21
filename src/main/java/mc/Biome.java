package mc;

/**
 * Biom: klimatická oblast, která rozhoduje o výšce terénu, povrchovém bloku
 * a druhu i hustotě stromů.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ TŘI NEZÁVISLÉ OSY, NE DVĚ. Minecraft vybírá biom z teploty a vlhkosti;
 * tady je k nim ještě RELIÉF. Kopce a hory totiž nejsou klima, ale tvar
 * krajiny - kdyby se vecpaly do matice teplota × vlhkost, znamenalo by to,
 * že hory jsou vždycky studené a suché (nebo jakýkoliv jiný jeden kout té
 * matice). Reliéf je proto samostatná vrstva šumu a rozhoduje PRVNÍ: kde je
 * vysoko, je hora nebo kopec bez ohledu na klima, a teprve ve zbytku světa
 * se uplatní teplota s vlhkostí.
 *
 * ⚠️ VÝŠKA TERÉNU SE NEPOČÍTÁ Z VYBRANÉHO BIOMU, ALE Z VÁŽENÉ SMĚSI VŠECH.
 * Kdyby si každý sloupec vzal parametry svého biomu, byla by na hranici hor
 * a pouště svislá zeď dvaceti bloků. Místo toho se z hodnot šumu spočítá osm
 * vah, které dávají v součtu přesně jednu (partition of unity), a výsledná
 * základní výška i amplituda jsou jejich váženým průměrem - viz
 * surfaceHeight(). Přechod je tím plynulý zadarmo: NESTOJÍ to ani jedno
 * volání šumu navíc, protože váhy se počítají z týchž tří čísel, ze kterých
 * se biom vybírá.
 *
 * ⚠️ BIOM SÁM (a s ním povrchový blok i druh stromu) se přepíná OSTŘE na
 * prahu. To je záměr, ne nedodělek: blok je buď sníh, nebo tráva, nic mezi
 * tím neexistuje. Plynulá je jen výška. Přesně tak to vypadá i v Minecraftu -
 * na okraji pouště přechází písek do trávy jednou hranou, ale kopec pod ní
 * pokračuje spojitě.
 * ---------------------------------------------------------------------------
 *
 * Čistá data a aritmetika - žádný šum, žádné GL, žádný stav. Šum do toho
 * dodává TerrainGenerator; tahle třída jen říká, co ze tří čísel vyplývá.
 */
public enum Biome {

    // Parametry: základní výška, amplituda, povrch, podpovrch, druh stromu,
    // hustota stromů (kolik buněk ze 16 nese strom), horní hranice lesa.
    //
    // Základní výška a amplituda se čtou takhle: terén se houpe v rozsahu
    // base ± amplituda (fbm dává asi <-0,85; +0,8>, takže reálný rozsah je
    // o něco menší). PLAINS má schválně 64/20, tedy přesně to, co měl svět
    // před biomy - pláň je pořád "ten původní terén".

    /** Původní terén: mírně zvlněné pláně s duby. */
    PLAINS(64, 20, World.GRASS, World.DIRT, TreeType.OAK, 5, World.WORLD_HEIGHT),

    /** Poušť: plochá, o kousek nad hladinou, písek do hloubky, bez stromů. */
    DESERT(67, 9, World.SAND, World.SAND, TreeType.NONE, 0, World.WORLD_HEIGHT),

    /** Prales: mírně zvlněný, ale strom v každé buňce a vysoké široké koruny. */
    JUNGLE(64, 18, World.GRASS, World.DIRT, TreeType.JUNGLE, 16, World.WORLD_HEIGHT),

    /** Březový les: mírně zvlněný, střední hustota, břízy. */
    BIRCH_FOREST(64, 18, World.GRASS, World.DIRT, TreeType.BIRCH, 10, World.WORLD_HEIGHT),

    /** Jehličnatý les (tajga): mírně zvlněný, střední hustota, smrky. */
    TAIGA(64, 16, World.GRASS, World.DIRT, TreeType.SPRUCE, 10, World.WORLD_HEIGHT),

    /** Tundra / ledová pláň: plochá, zasněžená, řídké smrky. */
    TUNDRA(65, 11, World.SNOW, World.DIRT, TreeType.SPRUCE, 2, World.WORLD_HEIGHT),

    /** Kopce: středně zvlněný terén, řídké duby. */
    HILLS(73, 31, World.GRASS, World.DIRT, TreeType.OAK, 3, World.WORLD_HEIGHT),

    /**
     * Hory: vysoký a členitý terén, stromy jen pod hranicí lesa.
     *
     * ⚠️ "Členitost" nepotřebuje další oktávu šumu. fbm() sčítá oktávy
     * s klesající amplitudou a celý součet se násobí amplitudou biomu -
     * s 42 místo 20 se tedy zvětší i ty JEMNÉ oktávy, takže profil není
     * jen vyšší, ale i strmější. Vyjde to zadarmo.
     *
     * Hranice lesa 88 je zároveň sněžná čára (TerrainGenerator si ji odsud
     * bere): nad ní je vrchol zasněžený a nic na něm neroste. Dvě různá čísla
     * by znamenala pás dubů se zeleným listím stojících ve sněhu. Změřeno:
     * přesahuje ji zhruba 30 % hor, takže zasněžené vrcholy jsou vidět,
     * ale nejsou z každé hory.
     */
    MOUNTAINS(84, 42, World.GRASS, World.DIRT, TreeType.OAK, 3, 88);

    // ------------------------------------------------------------------
    // druhy stromů
    // ------------------------------------------------------------------

    /**
     * Druh stromu: dřevo, listí, výška kmene a tvar koruny.
     *
     * Koruna je popsaná POLEM PRO KAŽDOU VRSTVU (poloměr + jestli se
     * ořezávají rohy), ne kódem - jinak by každý biom znamenal vlastní
     * kopii placeTree(). Poslední vrstva leží o jeden blok NAD vrcholem
     * kmene, takže se dá kmen "zavřít" špičkou.
     */
    public enum TreeType {

        /** Poušť: nic. */
        NONE(World.AIR, World.AIR, 0, 1, new int[0], new boolean[0]),

        /** Dub: přesně ten strom, který hra měla před biomy. */
        OAK(World.LOG, World.LEAVES, 4, 3,
                new int[]{2, 2, 1, 1},
                new boolean[]{true, true, false, true}),

        /** Bříza: stejný tvar jako dub, ale vyšší kmen a světlé dřevo. */
        BIRCH(World.BIRCH_LOG, World.BIRCH_LEAVES, 5, 3,
                new int[]{2, 2, 1, 1},
                new boolean[]{true, true, false, true}),

        /**
         * Smrk: kužel. Poloměr klesá odspodu nahoru a končí jedním blokem
         * špičky, takže silueta je špičatá a ne kulatá jako dub.
         */
        SPRUCE(World.SPRUCE_LOG, World.SPRUCE_LEAVES, 6, 4,
                new int[]{2, 2, 1, 1, 0},
                new boolean[]{true, true, false, false, false}),

        /** Prales: vysoký kmen a koruna o poloměru 3, tedy 7 bloků široká. */
        JUNGLE(World.LOG, World.JUNGLE_LEAVES, 7, 5,
                new int[]{3, 3, 2, 1},
                new boolean[]{true, true, true, true});

        public final byte log;
        public final byte leaves;
        public final int trunkMin;
        public final int trunkVariants;

        /** Poloměr koruny po vrstvách, odspodu. Délka = počet vrstev. */
        public final int[] layerRadius;

        /** Ořezat u té vrstvy rohy? Bez toho je koruna hranatá krabice. */
        public final boolean[] layerTrim;

        TreeType(byte log, byte leaves, int trunkMin, int trunkVariants,
                 int[] layerRadius, boolean[] layerTrim)
        {
            this.log = log;
            this.leaves = leaves;
            this.trunkMin = trunkMin;
            this.trunkVariants = trunkVariants;
            this.layerRadius = layerRadius;
            this.layerTrim = layerTrim;
        }

        /** Nejvyšší možný kmen tohohle druhu. */
        public int trunkMax()
        {
            return trunkMin + trunkVariants - 1;
        }

        /** Největší poloměr koruny - z něj vychází TREE_REACH v generátoru. */
        public int maxRadius()
        {
            int max = 0;
            for(int radius : layerRadius)
            {
                max = Math.max(max, radius);
            }
            return max;
        }

        /**
         * Kolik bloků nad zemí strom celkem potřebuje: kmen a nad jeho
         * vrcholem ještě poslední vrstva koruny.
         */
        public int totalHeight()
        {
            return trunkMax() + 2;
        }
    }

    // ------------------------------------------------------------------
    // prahy výběru
    //
    // Hodnoty jsou doladěné podle MĚŘENÍ podílů biomů (BiomeTest je vypisuje),
    // ne odhadem: simplex šum se nechová jako rovnoměrné rozdělení - hodnoty
    // se hromadí kolem nuly, takže práh 0,5 by udělal biom, do kterého se
    // skoro nedá dojít.
    // ------------------------------------------------------------------

    /** Pod touhle teplotou je studeno (tundra, tajga). */
    public static final double T_COLD = -0.26;

    /** Nad touhle teplotou je teplo (poušť, prales). */
    public static final double T_WARM = 0.26;

    /** Nad touhle vlhkostí je vlhko (les místo pláně, prales místo pouště). */
    public static final double H_WET = 0.0;

    /** Nad tímhle reliéfem jsou kopce. */
    public static final double R_HILLS = 0.30;

    /** Nad tímhle reliéfem jsou hory. */
    public static final double R_MOUNTAINS = 0.60;

    /**
     * Polovina šířky přechodového pásu, v jednotkách šumu.
     *
     * ⚠️ Pásy se NESMÍ překrývat, jinak by váhy vyšly negativní a součet
     * by nebyl jedna: T_WARM - BAND >= T_COLD + BAND a
     * R_MOUNTAINS - BAND >= R_HILLS + BAND. BiomeTest to hlídá.
     *
     * ⚠️ A nesmí být ani příliš ŠIROKÉ: mezi dvěma sousedními pásy musí zbýt
     * kus, kde má biom váhu přesně 1, jinak by kopce nikdy neměly svou vlastní
     * výšku a byly by pořád jen směsí nížiny a hor. BiomeTest to kontroluje
     * dotazem "v jádru biomu má ten biom váhu 1".
     *
     * Kolik je to v blocích: gradient šumu je řádově 2,5 na jednotku vstupu,
     * vstup je x × BIOME_FREQUENCY, takže při frekvenci 0,0018 vyjde přechod
     * zhruba na 2·BAND / (2,5·0,0018) ≈ 45 bloků. To je ta vzdálenost, na
     * které se hora "svaří" s pláněmi.
     */
    public static final double BAND = 0.10;

    // ------------------------------------------------------------------
    // data instance
    // ------------------------------------------------------------------

    private final int baseHeight;
    private final int amplitude;
    private final byte surface;
    private final byte subsurface;
    private final TreeType treeType;
    private final int treeDensity;
    private final int treeLine;

    Biome(int baseHeight, int amplitude, byte surface, byte subsurface,
          TreeType treeType, int treeDensity, int treeLine)
    {
        this.baseHeight = baseHeight;
        this.amplitude = amplitude;
        this.surface = surface;
        this.subsurface = subsurface;
        this.treeType = treeType;
        this.treeDensity = treeDensity;
        this.treeLine = treeLine;
    }

    public int baseHeight()  { return baseHeight; }
    public int amplitude()   { return amplitude; }
    public byte surface()    { return surface; }
    public byte subsurface() { return subsurface; }
    public TreeType treeType() { return treeType; }

    /** Kolik buněk ze DENSITY_SCALE nese strom. Nula = bez stromů. */
    public int treeDensity() { return treeDensity; }

    /**
     * Nad touhle výškou už strom nevyroste (hranice lesa v horách) a povrch
     * je zasněžený. Mimo hory je to strop světa, tedy "nikdy".
     */
    public int treeLine() { return treeLine; }

    /** Jmenovatel hustoty stromů. Buňka nese strom, když hash % 16 < treeDensity. */
    public static final int DENSITY_SCALE = 16;

    // ------------------------------------------------------------------
    // výběr biomu
    // ------------------------------------------------------------------

    /**
     * Který biom leží na daném klimatu. Ostré prahy, žádné prolnutí - biom
     * je jeden konkrétní, viz komentář u třídy.
     *
     * Reliéf rozhoduje první: hora je hora, i když je zrovna v tropech.
     */
    public static Biome classify(double temperature, double humidity, double relief)
    {
        if(relief > R_MOUNTAINS)
        {
            return MOUNTAINS;
        }

        if(relief > R_HILLS)
        {
            return HILLS;
        }

        boolean wet = humidity > H_WET;

        if(temperature < T_COLD)
        {
            return wet ? TAIGA : TUNDRA;
        }

        if(temperature > T_WARM)
        {
            return wet ? JUNGLE : DESERT;
        }

        return wet ? BIRCH_FOREST : PLAINS;
    }

    // ------------------------------------------------------------------
    // plynulý přechod výšky
    // ------------------------------------------------------------------

    /**
     * Výška terénu z klimatu a hodnoty fbm, s plynulým prolnutím mezi biomy.
     *
     * Osm vah se skládá ze šesti hladkých členů (teplo/studeno/mírno,
     * vlhko/suchu, hory/kopce/nížina) a v součtu dávají přesně jednu, takže
     * výsledek je vážený průměr parametrů biomů. Zápis je záměrně VYTKNUTÝ
     * (žádné pole vah), aby na horké cestě generátoru nic nealokoval -
     * terrainHeight() se volá na každý blok povrchu.
     *
     * BiomeTest ověřuje, že tenhle vytknutý zápis dává bit po bitu totéž
     * jako naivní suma přes weights(), takže se ty dvě verze nemůžou rozejít.
     */
    public static double surfaceHeight(double temperature, double humidity,
                                       double relief, double fbm)
    {
        return surfaceHeight(BiomeTuning.defaults(), temperature, humidity, relief, fbm);
    }

    /**
     * Totéž se základními výškami a amplitudami z tuneru místo z enumu.
     *
     * ⚠️ VÁHY SE NEMĚNÍ, MĚNÍ SE JEN PARAMETRY, KTERÉ VÁŽÍ. Prahy, BAND
     * i smoothstep zůstávají v kódu, takže "součet vah je přesně jedna"
     * platí dál bez ohledu na to, co si uživatel natuní - a s ním i plynulý
     * přechod. Kdyby tuner sahal i na prahy, mohl by si pásma překrýt a váhy
     * by vyšly záporné (viz poznámka u BAND).
     *
     * S `BiomeTuning.defaults()` vyjde bit po bitu totéž, co vycházelo před
     * tunerem: jsou to tytéž int hodnoty z enumu, jen načtené přes tuning.
     */
    public static double surfaceHeight(BiomeTuning tuning, double temperature, double humidity,
                                       double relief, double fbm)
    {
        double warm = warmWeight(temperature);
        double cold = coldWeight(temperature);
        double temperate = 1.0 - warm - cold;

        double wet = wetWeight(humidity);
        double dry = 1.0 - wet;

        double mountains = mountainWeight(relief);
        double hills = hillWeight(relief);
        double low = 1.0 - mountains - hills;

        int mountainsBase = tuning.tune(MOUNTAINS).baseHeight();
        int hillsBase     = tuning.tune(HILLS).baseHeight();
        int tundraBase    = tuning.tune(TUNDRA).baseHeight();
        int taigaBase     = tuning.tune(TAIGA).baseHeight();
        int plainsBase    = tuning.tune(PLAINS).baseHeight();
        int birchBase     = tuning.tune(BIRCH_FOREST).baseHeight();
        int desertBase    = tuning.tune(DESERT).baseHeight();
        int jungleBase    = tuning.tune(JUNGLE).baseHeight();

        double base = mountains * mountainsBase
                + hills * hillsBase
                + low * (cold * (dry * tundraBase + wet * taigaBase)
                       + temperate * (dry * plainsBase + wet * birchBase)
                       + warm * (dry * desertBase + wet * jungleBase));

        double amplitude = mountains * tuning.tune(MOUNTAINS).amplitude()
                + hills * tuning.tune(HILLS).amplitude()
                + low * (cold * (dry * tuning.tune(TUNDRA).amplitude() + wet * tuning.tune(TAIGA).amplitude())
                       + temperate * (dry * tuning.tune(PLAINS).amplitude() + wet * tuning.tune(BIRCH_FOREST).amplitude())
                       + warm * (dry * tuning.tune(DESERT).amplitude() + wet * tuning.tune(JUNGLE).amplitude()));

        return base + fbm * amplitude;
    }

    /**
     * Váhy všech biomů, v pořadí values(). Alokuje, takže je to jen pro testy
     * a ladění - horká cesta jde přes surfaceHeight().
     */
    public static double[] weights(double temperature, double humidity, double relief)
    {
        double warm = warmWeight(temperature);
        double cold = coldWeight(temperature);
        double temperate = 1.0 - warm - cold;

        double wet = wetWeight(humidity);
        double dry = 1.0 - wet;

        double mountains = mountainWeight(relief);
        double hills = hillWeight(relief);
        double low = 1.0 - mountains - hills;

        double[] w = new double[values().length];

        w[PLAINS.ordinal()]       = low * temperate * dry;
        w[DESERT.ordinal()]       = low * warm * dry;
        w[JUNGLE.ordinal()]       = low * warm * wet;
        w[BIRCH_FOREST.ordinal()] = low * temperate * wet;
        w[TAIGA.ordinal()]        = low * cold * wet;
        w[TUNDRA.ordinal()]       = low * cold * dry;
        w[HILLS.ordinal()]        = hills;
        w[MOUNTAINS.ordinal()]    = mountains;

        return w;
    }

    static double warmWeight(double temperature)
    {
        return smoothstep(T_WARM - BAND, T_WARM + BAND, temperature);
    }

    static double coldWeight(double temperature)
    {
        return 1.0 - smoothstep(T_COLD - BAND, T_COLD + BAND, temperature);
    }

    static double wetWeight(double humidity)
    {
        return smoothstep(H_WET - BAND, H_WET + BAND, humidity);
    }

    static double mountainWeight(double relief)
    {
        return smoothstep(R_MOUNTAINS - BAND, R_MOUNTAINS + BAND, relief);
    }

    static double hillWeight(double relief)
    {
        return smoothstep(R_HILLS - BAND, R_HILLS + BAND, relief)
                - mountainWeight(relief);
    }

    /**
     * Hladký přechod 0 → 1 mezi edge0 a edge1 (Hermitův polynom 3t² − 2t³).
     *
     * ⚠️ Lineární rampa by nestačila: má na obou koncích ZLOM v derivaci,
     * takže by na začátku i konci přechodového pásu byla v terénu vidět
     * hrana. Smoothstep má derivaci na obou koncích nulovou, takže se hora
     * do pláně vlije bez švu.
     */
    static double smoothstep(double edge0, double edge1, double value)
    {
        double t = (value - edge0) / (edge1 - edge0);

        if(t <= 0.0)
        {
            return 0.0;
        }

        if(t >= 1.0)
        {
            return 1.0;
        }

        return t * t * (3.0 - 2.0 * t);
    }
}
