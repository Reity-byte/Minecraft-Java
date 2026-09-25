package mc;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Nekonečný svět složený ze sloupců chunků. Od kroku A3 jsou to čistá data -
 * žádné GL volání tady není, kreslení řeší WorldRenderer.
 *
 * Nekonečný v X a Z, omezený ve výšce (WORLD_HEIGHT). Omezená výška je záměr,
 * ne kompromis - dělá to i Minecraft a zjednodušuje generování, osvětlení
 * i ukládání. Sloupce se generují na požádání kolem hráče a za hranicí se zahazují.
 */
public class World {

    /** Adresa jedné sekce 16x16x16 v mřížce chunků. */
    public record SectionPos(int cx, int cy, int cz) {}

    // Pojmenovaná ID bloků, ať se v kódu nemotají magická čísla.
    // Přidat nový typ = nová konstanta zde + dlaždice v BlockAtlas a Textures.
    // ⚠️ Vestavěné bloky mají id jen 0 až 63. Od BlockRegistry.FIRST_ID (64)
    // výš jsou bloky z texture labu (textures/blocks.json) - jejich vlastnosti
    // nejsou tady v kódu, ale v BlockDef, a ptají se na ně metody níž.
    public static final byte AIR    = 0;
    public static final byte GRASS  = 1;
    public static final byte STONE  = 2;
    public static final byte DIRT   = 3;
    public static final byte SAND   = 4;
    public static final byte PLANKS = 5;
    public static final byte COAL_ORE = 6;
    public static final byte IRON_ORE = 7;
    public static final byte WATER    = 8;

    // Craftitelné bloky. V terénu se negenerují, vznikají jen v crafting mřížce.
    public static final byte CRAFTING_TABLE = 9;
    public static final byte STONE_BRICKS   = 10;

    // Stromy. Kmen je zdroj prken, takže teprve s nimi je crafting kompletní.
    public static final byte LOG    = 11;
    public static final byte LEAVES = 12;

    // Nekrychlové bloky - tvar jim dává BlockModels.
    public static final byte TORCH = 13;
    public static final byte FENCE = 14;

    /**
     * Bloky biomů. Přibyly jen ty, bez kterých by se dva biomy nedaly od sebe
     * poznat - sníh pro tundru a dvě další dřeva s listím pro březový les
     * a tajgu. Prales si vystačí s dubovým kmenem a vlastním tmavým listím.
     *
     * ⚠️ MUSÍ TO BÝT VESTAVĚNÉ BLOKY, ne bloky z texture labu. Generátor na
     * nich závisí, a blocks.json je nepovinný soubor - na čisté instalaci
     * nebo po jeho smazání by se generování rozbilo.
     */
    public static final byte SNOW = 15;

    public static final byte BIRCH_LOG    = 16;
    public static final byte BIRCH_LEAVES = 17;

    public static final byte SPRUCE_LOG    = 18;
    public static final byte SPRUCE_LEAVES = 19;

    public static final byte JUNGLE_LEAVES = 20;

    /**
     * Největší obsazené id vestavěného bloku.
     *
     * ⚠️ Zvýšit při přidání konstanty výš. Je to jediné místo, kde je napsané,
     * které z id 0-63 opravdu existují - samotné hardness() ani isOpaque()
     * to nepoznají, mají default větev. Ptá se na to creative přehled
     * (CreativeInventory), aby v něm nebyly prázdné položky.
     */
    public static final byte LAST_BUILT_IN = JUNGLE_LEAVES;

    /** Výška světa v blocích. 128 = 8 sekcí po 16. */
    public static final int WORLD_HEIGHT = 128;

    /**
     * Seed, se kterým vznikly všechny světy před zavedením seedů.
     *
     * ⚠️ Nesahat: starý uložený svět má pod stavbami terén z téhohle čísla,
     * takže migrovaný svět ho musí dostat, aby vypadal stejně.
     */
    public static final long DEFAULT_SEED = 12345L;

    /**
     * Výchozí výška terénu. Z ní se odvozuje hladina (SEA_LEVEL) a pás písku
     * (TerrainGenerator.SAND_LEVEL = SEA_LEVEL + 1). Výšky biomů má dnes
     * každý biom vlastní (Biome, BiomeTuning), tohle je jen společná kotva.
     */
    static final int GROUND_HEIGHT = 64;

    /**
     * Hladina vody. Všechno pod ní, co není terén, se zaplaví.
     *
     * O jedna níž než SAND_LEVEL v TerrainGenerator schválně: pás písku tak vyčnívá kousek nad
     * hladinu a kolem jezer vznikne pláž místo trávy rovnou u vody.
     */
    public static final int SEA_LEVEL = GROUND_HEIGHT - 9;


    /**
     * Načtené sloupce, klíč = zabalené souřadnice chunku (cx, cz).
     * ⚠️ Sahá na ně JEN hlavní vlákno. Worker sem nikdy nezapisuje.
     */
    private final LongMap<ChunkColumn> columns = new LongMap<>(512);

    // ------------------------------------------------------------------
    // asynchronní generování
    //
    // Generování sloupce stojí ~1,3 ms (většinu sežere 3D šum jeskyní).
    // Synchronně to znamenalo, že přechod přes hranici chunku vygeneruje
    // najednou celý nový prstenec - až 17 sloupců, tedy ~22 ms v jednom
    // framu a viditelné cuknutí.
    //
    // Dělba práce je záměrně přísná: worker DOSTÁVÁ souřadnice a VRACÍ hotové
    // sloupce, ale na mapu columns nesahá. Nepotřebuje tak žádný zámek a
    // nemůže vzniknout stav, kdy hlavní vlákno čte sloupec, který se zrovna
    // plní. generateColumn() je čistá funkce souřadnic (TerrainGenerator je
    // neměnný - seed i tabulky šumu jsou final), takže je bezpečné volat ji
    // odkudkoliv.
    // ------------------------------------------------------------------

    /**
     * Kolik sloupců smí být rozpracovaných najednou. Omezuje to, jak moc
     * může být fronta zastaralá, když se hráč rozejde jinam.
     */
    private static final int MAX_IN_FLIGHT = 32;

    /**
     * ⚠️ Kolik hotových sloupců se smí převzít za jeden frame.
     *
     * Převzetí není zadarmo: sloupec se musí nasvítit, což stojí zhruba jako
     * jeho vygenerování. Bez tohohle stropu se při rychlém pohybu převzalo
     * deset sloupců naráz a frame vyskočil na 12 ms - tedy přesně ten zádrhel,
     * kvůli kterému se generování stěhovalo na worker vlákno.
     */
    private static final int MAX_ADOPTED_PER_FRAME = 3;

    /**
     * V tomhle okruhu kolem hráče se sloupec dogeneruje SYNCHRONNĚ, když ještě
     * není. Je to pojistka proti propadnutí skrz nehotovou zem - hráč je široký
     * 0,6 bloku, takže se dotkne nejvýš sousedního sloupce. Za běžné chůze se
     * neuplatní (prstenec, který přibyde, je 8 chunků daleko); vystřelí jen při
     * spawnu, kdy ještě není načteno nic.
     */
    private static final int CRITICAL_RADIUS = 1;

    /** Fronta požadavků pro worker vlákno. */
    private final BlockingQueue<Long> requests = new LinkedBlockingQueue<>();

    /** Hotové sloupce od workera. Vybírá je hlavní vlákno v update(). */
    private final Queue<ChunkColumn> finished = new ConcurrentLinkedQueue<>();

    /** Co je zadané workerovi. Jen hlavní vlákno. */
    private final Set<Long> inFlight = new HashSet<>();

    /** Kolik sloupců v dosahu ještě chybí. Čte loading screen. */
    private int missingColumns = 0;

    /**
     * Bloky, které hráč změnil proti vygenerovanému terénu.
     * Klíč sloupce -> (index uvnitř sloupce -> nový blok).
     *
     * ---------------------------------------------------------------------
     * Svět = generátor + tahle mapa. Generátor je čistá funkce souřadnic,
     * takže se terén dá kdykoliv dopočítat znovu; ukládat je proto potřeba
     * jen tohle, ne celý svět. Uložený svět má díky tomu kilobajty místo
     * megabajtů.
     *
     * Má to i druhý, důležitější účinek: změny přežijí zahození sloupce.
     * Dokud mapa neexistovala, stačilo odejít za unloadRadius a vrátit se -
     * sloupec se vygeneroval znovu z šumu a všechno postavené bylo pryč.
     * ---------------------------------------------------------------------
     */
    private final Map<Long, Map<Integer, Byte>> changes = new HashMap<>();

    private volatile boolean running = true;
    private final Thread worker;

    /**
     * Šíření světla. Běží na hlavním vlákně - sahá na sousední sloupce, které
     * worker nesmí vidět, a mění data, ze kterých se staví meshe.
     */
    private final LightEngine light = new LightEngine(this);

    /** Kolik času za frame se smí strávit šířením světla. */
    public static final long LIGHT_BUDGET = 2_000_000L;   // 2 ms

    /**
     * Rozpočet světla při loadingu - obrazovka nic jiného nedělá, stejně jako
     * u meshů (WorldRenderer.BUILD_BUDGET_LOADING). Loading čeká, až světlo
     * doběhne (pendingLight() == 0), jinak by hra začala s nedosvíceným
     * okolím a dosvícení by pak přestavovalo sekce mimo rozpočet.
     */
    public static final long LIGHT_BUDGET_LOADING = 12_000_000L;   // 12 ms

    /** Kolik času na frame smí dostat šíření světla. Main ho přepíná jako rozpočet meshů. */
    public long lightBudget = LIGHT_BUDGET;

    /**
     * Generátor terénu tohohle světa.
     *
     * ⚠️ Neměnný, a proto z něj smí číst i worker vlákno bez zámku. Drží
     * seed, takže dva světy se stejným seedem jsou blok po bloku stejné
     * a s jiným seedem úplně jiné.
     */
    private final TerrainGenerator generator;

    /** Svět s výchozím seedem - terén jako před zavedením seedů. */
    public World()
    {
        this(DEFAULT_SEED);
    }

    public World(long seed)
    {
        this(seed, BiomeTuning.active());
    }

    /**
     * Svět s daným tuningem generátoru místo aktivního - pro náhledy v labu,
     * které musí stát na terénu, jaký znají (viz TreePreview.createWorld).
     */
    public World(long seed, BiomeTuning tuning)
    {
        generator = new TerrainGenerator(seed, tuning);

        worker = new Thread(this::generateLoop, "world-gen");
        // Daemon, aby nedržel JVM naživu, kdyby se zapomnělo na shutdown().
        worker.setDaemon(true);
    }

    /**
     * Spustí worker, až když je poprvé co generovat.
     *
     * ⚠️ LÍNĚ, NE V KONSTRUKTORU. Úvodní zástupný svět v Main a malé světy
     * náhledů v labu (BlockPreview, TreePreview) nikdy nic nepožádají, a jejich
     * vlákno se přesto celou dobu desetkrát za sekundu probouzelo. Po
     * shutdown() se už nespustí.
     */
    private void ensureWorker()
    {
        if(running && worker.getState() == Thread.State.NEW)
        {
            worker.start();
        }
    }

    /** Seed, ze kterého se počítá terén. Ukládá ho WorldSaves do world.json. */
    public long seed()
    {
        return generator.seed();
    }

    /**
     * Generátor terénu. Přes něj se ptá na výšky, spawn a další věci, které
     * nepotřebují načtený sloupec.
     */
    public TerrainGenerator generator()
    {
        return generator;
    }

    /**
     * Zastaví worker vlákno. Volat při zahození světa - jinak by po vytvoření
     * nového světa běžela dvě generující vlákna vedle sebe.
     */
    public void shutdown()
    {
        running = false;
        worker.interrupt();
    }

    /** Běží worker? Kvůli testu, že shutdown() opravdu zabírá. */
    public boolean isWorkerAlive()
    {
        return worker.isAlive();
    }

    private void generateLoop()
    {
        while(running)
        {
            Long key;

            try
            {
                // Poll s timeoutem, ne take(): vlákno se tak samo probudí
                // a všimne si, že running už neplatí.
                key = requests.poll(100, TimeUnit.MILLISECONDS);
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }

            if(key != null)
            {
                finished.add(generateSafely(chunkX(key), chunkZ(key)));
            }
        }
    }

    /**
     * generateColumn() pro worker vlákno: výjimka nesmí vlákno zabít.
     *
     * ⚠️ Dřív tu try/catch nebyl. Výjimka z generátoru ukončila worker
     * vlákno, klíč zůstal navždy "in flight", pendingColumns() nikdy
     * neklesl na nulu a loading screen visel bez jediné hlášky. Teď se
     * chyba vypíše a místo sloupce přijde prázdný (vzduch) - v terénu je
     * díra, ale hra běží dál a o příčině se ví. Dnešní generátor na žádném
     * známém vstupu nepadá (viz BiomeTuningTest, meze tuningu); je to
     * pojistka pro příští změnu generátoru.
     */
    ChunkColumn generateSafely(int cx, int cz)
    {
        try
        {
            return generateColumn(cx, cz);
        }
        catch(RuntimeException | StackOverflowError e)
        {
            System.err.println("Generovani sloupce " + cx + ", " + cz + " selhalo: " + e
                    + " - misto nej je prazdny sloupec");
            return new ChunkColumn(cx, cz);
        }
    }

    /**
     * Sekce, jejichž mesh je potřeba přestavět. Plní se při změně bloku,
     * vyprazdňuje je WorldRenderer.
     */
    private final Set<SectionPos> dirtySections = new HashSet<>();

    /** V jakém okruhu chunků kolem hráče se generuje. */
    public int loadRadius = 8;

    /**
     * Za jakou vzdáleností se sloupce zahazují. Vždycky musí být větší než
     * loadRadius - jinak by se sloupec na hranici pořád dokola generoval
     * a hned mazal, kdykoliv hráč přešlápne přes hranu chunku (hystereze).
     */
    public int unloadRadius = 10;

    /**
     * Dohled v blocích. Musí zůstat pod (loadRadius - 1) * 16, protože sekce
     * se nemešuje, dokud nejsou načtení všichni čtyři sousedé - jinak by na
     * okraji byly vidět neexistující stěny.
     */
    public float renderDistance = 96f;

    // ------------------------------------------------------------------
    // převod souřadnic
    // ------------------------------------------------------------------

    /**
     * Světová souřadnice -> souřadnice chunku.
     *
     * Aritmetický posun >> zaokrouhluje dolů i pro záporná čísla, na rozdíl
     * od dělení, které zaokrouhluje k nule: -1 / 16 = 0, ale -1 >> 4 = -1.
     * U nekonečného světa se do záporných souřadnic dostaneš hned, takže
     * použít tady dělení je tichá chyba, která rozbije chunk na hranici nuly.
     */
    private static int toChunk(int world)
    {
        return world >> Chunk.BITS;
    }

    /**
     * Světová souřadnice -> lokální uvnitř chunku (0-15).
     * & 15 dá správný nezáporný zbytek i pro záporná čísla, na rozdíl od % 16.
     */
    private static int toLocal(int world)
    {
        return world & Chunk.MASK;
    }

    /** Dvě 32bitová čísla zabalená do jednoho longu - klíč do mapy bez alokace objektu. */
    public static long key(int cx, int cz)
    {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static int chunkX(long key) { return (int) (key >> 32); }
    private static int chunkZ(long key) { return (int) key; }

    /**
     * Adresa bloku uvnitř sloupce. y je celá výška světa (0-127), takže se
     * nevejde do stejného schématu jako Chunk.index() - potřebuje 7 bitů.
     */
    public static int columnIndex(int lx, int y, int lz)
    {
        return (y << 8) | (lz << 4) | lx;
    }

    public static int indexY(int index) { return index >>> 8; }
    public static int indexZ(int index) { return (index >> 4) & Chunk.MASK; }
    public static int indexX(int index) { return index & Chunk.MASK; }

    // ------------------------------------------------------------------
    // načítání a generování
    // ------------------------------------------------------------------

    /**
     * ⚠️ ZAVOLAT KAŽDÝ FRAME. Tahle metoda práci jen ZADÁ a převezme, co je
     * hotové - nečeká na generování a jedním voláním se svět nevygeneruje.
     * Kdo ji zavolá jednou a čeká, že je hotovo, uvázne: hotové sloupce
     * vyzvedává právě ona a bez dalšího volání je nemá kdo vybrat z fronty.
     *
     * Kdo potřebuje svět hotový hned, chce updateBlocking().
     */
    public void update(float camX, float camZ)
    {
        update(camX, camZ, false);
    }

    /**
     * Jako update(), ale vrátí se až když jsou všechny sloupce v dosahu hotové.
     *
     * Chybějící sloupce si dogeneruje rovnou na volajícím vlákně, takže je to
     * ten samý generátor, jen bez čekání na frontu. Používají to testy, které
     * potřebují číst svět hned po zavolání.
     */
    public void updateBlocking(float camX, float camZ)
    {
        update(camX, camZ, true);
    }

    private void update(float camX, float camZ, boolean blocking)
    {
        int centerCx = toChunk((int) Math.floor(camX));
        int centerCz = toChunk((int) Math.floor(camZ));

        collectFinished(centerCx, centerCz, blocking);
        ensureCritical(centerCx, centerCz);
        requestMissing(centerCx, centerCz, blocking);
        unloadFar(centerCx, centerCz);

        // Blokující varianta (testy, loading) musí dosvítit celý svět,
        // jinak by se četlo nedopočítané světlo.
        light.process(blocking ? Long.MAX_VALUE / 2 : lightBudget);
    }

    /** Převezme, co worker mezitím vyrobil. */
    private void collectFinished(int centerCx, int centerCz, boolean blocking)
    {
        ChunkColumn column;
        int adopted = 0;

        while((blocking || adopted < MAX_ADOPTED_PER_FRAME) && (column = finished.poll()) != null)
        {
            long k = key(column.cx, column.cz);
            inFlight.remove(k);

            // Hráč se mezitím mohl rozejít jinam - pak je sloupec k ničemu
            // a zahodí se. Ukládat ho a hned mazat by jen nafouklo mapu.
            // Strop počítá jen PŘEVZATÉ sloupce: zahození nic nestojí, a dřív
            // se po teleportu kvůli zahozeným zdržovalo převzetí těch nových.
            if(withinRadius(column.cx, column.cz, centerCx, centerCz, loadRadius)
                    && !columns.containsKey(k))
            {
                insert(k, column);
                adopted++;
            }
        }
    }

    /**
     * Nejbližší okolí hráče musí existovat OKAMŽITĚ - fyzika se na ně ptá
     * hned v tomhle framu a nesmí propadnout skrz nehotovou zem.
     */
    private void ensureCritical(int centerCx, int centerCz)
    {
        for(int cx = centerCx - CRITICAL_RADIUS; cx <= centerCx + CRITICAL_RADIUS; cx++)
        {
            for(int cz = centerCz - CRITICAL_RADIUS; cz <= centerCz + CRITICAL_RADIUS; cz++)
            {
                long k = key(cx, cz);

                if(!columns.containsKey(k))
                {
                    // Hlavní vlákno ve hře: výjimka generátoru by shodila hru.
                    insert(k, generateSafely(cx, cz));

                    // Kdyby to zrovna měl rozpracované worker, jeho výsledek
                    // pak collectFinished zahodí - klíč už v mapě bude.
                    inFlight.remove(k);
                }
            }
        }
    }

    /**
     * Zadá workerovi chybějící sloupce, od nejbližšího.
     *
     * Pořadí je podstatné: bez něj se svět dosypává v náhodných ostrovech,
     * protože pořadí mapy sloupců je libovolné. Stejný důvod, proč řadí frontu
     * i WorldRenderer.
     */
    /**
     * ⚠️ Bez jediné alokace.
     *
     * Dřív se tady každý frame stavěl List&lt;Long&gt; a řadil komparátorem, což
     * znamenalo stovky zabalených Longů za frame. Na papíře drobnost, jenže
     * odpad ze všech takových míst dohromady rozhoupe GC - a jeho pauzy jsou
     * pak větší než celý rozpočet na generování i světlo. Tady se sbírá do
     * znovupoužitého pole a místo řazení se vybere N nejbližších.
     */
    private long[] missingBuffer = new long[512];
    private int[] missingDistance = new int[512];

    private void requestMissing(int centerCx, int centerCz, boolean blocking)
    {
        int span = 2 * loadRadius + 1;

        if(missingBuffer.length < span * span)
        {
            missingBuffer = new long[span * span];
            missingDistance = new int[span * span];
        }

        int count = 0;

        for(int cx = centerCx - loadRadius; cx <= centerCx + loadRadius; cx++)
        {
            for(int cz = centerCz - loadRadius; cz <= centerCz + loadRadius; cz++)
            {
                long k = key(cx, cz);

                if(!columns.containsKey(k))
                {
                    int dx = cx - centerCx;
                    int dz = cz - centerCz;

                    missingBuffer[count] = k;
                    missingDistance[count] = dx * dx + dz * dz;
                    count++;
                }
            }
        }

        missingColumns = count;

        if(blocking)
        {
            // Bez fronty a bez čekání: rovnou tady, na volajícím vlákně.
            for(int i = 0; i < count; i++)
            {
                long k = missingBuffer[i];
                insert(k, generateColumn(chunkX(k), chunkZ(k)));
                inFlight.remove(k);
            }

            missingColumns = 0;
            return;
        }

        // Vybírají se jen ty nejbližší, kolik se jich vejde do fronty - řadit
        // celé pole by bylo zbytečné, zbytek se stejně zahodí.
        int wanted = MAX_IN_FLIGHT - inFlight.size();

        for(int picked = 0; picked < wanted && picked < count; picked++)
        {
            int best = picked;

            for(int i = picked + 1; i < count; i++)
            {
                if(missingDistance[i] < missingDistance[best])
                {
                    best = i;
                }
            }

            swap(picked, best);

            long k = missingBuffer[picked];

            if(inFlight.add(k))
            {
                ensureWorker();
                requests.add(k);
            }
        }
    }

    private void swap(int a, int b)
    {
        long key = missingBuffer[a];
        missingBuffer[a] = missingBuffer[b];
        missingBuffer[b] = key;

        int distance = missingDistance[a];
        missingDistance[a] = missingDistance[b];
        missingDistance[b] = distance;
    }

    private void unloadFar(int centerCx, int centerCz)
    {
        columns.removeIf((key, column) ->
                !withinRadius(column.cx, column.cz, centerCx, centerCz, unloadRadius));
    }

    /**
     * Zařadí čerstvě vygenerovaný sloupec mezi načtené.
     *
     * ⚠️ Změny hráče se dopisují AŽ TADY, na hlavním vlákně - ne v
     * generateColumn(). Ta běží na workeru a mapa changes se za běhu mění
     * (hráč pořád něco boří), takže by ji worker četl souběžně se zápisem.
     */
    private void insert(long key, ChunkColumn column)
    {
        Map<Integer, Byte> columnChanges = changes.get(key);

        // Vygenerovaný terén žádnou pochodeň neobsahuje - může ji do sloupce
        // dostat jen uložená změna. Bez téhle informace by nasvěcování muselo
        // projít celou výšku každého sloupce pro nic za nic.
        boolean hasLightSources = false;

        if(columnChanges != null)
        {
            for(Map.Entry<Integer, Byte> change : columnChanges.entrySet())
            {
                int index = change.getKey();
                column.set(indexX(index), indexY(index), indexZ(index), change.getValue());

                if(LightEngine.emission(change.getValue()) > 0)
                {
                    hasLightSources = true;
                }
            }
        }

        columns.put(key, column);

        // ⚠️ Až po vložení do mapy: nasvěcování se ptá i na sousední sloupce
        // a musí přitom vidět i ten právě vkládaný.
        light.seedColumn(column, hasLightSources);
    }

    /**
     * Změny hráče proti generátoru - jediné, co se ukládá na disk.
     * Volající do mapy nezapisuje, jen z ní čte.
     */
    public Map<Long, Map<Integer, Byte>> changes()
    {
        return changes;
    }

    /**
     * Nasadí načtené změny. Volat na čerstvém světě PŘED prvním update() -
     * sloupce si je pak vezmou samy, jak se budou generovat.
     */
    public void restoreChanges(Map<Long, Map<Integer, Byte>> saved)
    {
        changes.clear();

        for(Map.Entry<Long, Map<Integer, Byte>> column : saved.entrySet())
        {
            changes.put(column.getKey(), new HashMap<>(column.getValue()));
        }
    }

    /** Kolik bloků hráč změnil. Pro ladicí výpis a testy. */
    public int changedBlockCount()
    {
        int total = 0;
        for(Map<Integer, Byte> column : changes.values())
        {
            total += column.size();
        }
        return total;
    }

    private static boolean withinRadius(int cx, int cz, int centerCx, int centerCz, int radius)
    {
        return Math.abs(cx - centerCx) <= radius && Math.abs(cz - centerCz) <= radius;
    }

    /**
     * Kolik sloupců v dosahu ještě chybí. Loading screen to potřebuje k tomu,
     * aby nevyhlásil hotovo dřív, než je svět vygenerovaný - fronta meshů je
     * na začátku prázdná právě proto, že ještě není z čeho stavět.
     */
    public int pendingColumns()
    {
        return missingColumns;
    }

    /**
     * Vygeneruje sloupec. Volá to i worker vlákno, a smí to jen proto, že
     * generátor je neměnný a nesahá na nic ze World - žádný zámek tu není
     * a ani být nemá.
     */
    private ChunkColumn generateColumn(int cx, int cz)
    {
        return generator.generateColumn(cx, cz);
    }

    // ------------------------------------------------------------------
    // přístup pro renderer
    // ------------------------------------------------------------------

    public Collection<ChunkColumn> loadedColumns()
    {
        return columns.values();
    }

    public ChunkColumn column(int cx, int cz)
    {
        return columns.get(key(cx, cz));
    }

    public boolean hasColumn(int cx, int cz)
    {
        return columns.containsKey(key(cx, cz));
    }

    public boolean hasColumn(long key)
    {
        return columns.containsKey(key);
    }

    public int loadedColumnCount()
    {
        return columns.size();
    }

    public Set<SectionPos> dirtySections()
    {
        return dirtySections;
    }

    // ------------------------------------------------------------------
    // přístup k blokům (světové souřadnice)
    // ------------------------------------------------------------------

    public byte getBlock(int x, int y, int z)
    {
        // Pod světem se tváříme, že je pevno. Ušetří to spodní stěny nejnižší
        // vrstvy, které stejně nikdy neuvidíš.
        if(y < 0)
        {
            return STONE;
        }
        if(y >= WORLD_HEIGHT)
        {
            return AIR;
        }

        ChunkColumn column = columns.get(key(toChunk(x), toChunk(z)));
        if(column == null)
        {
            // Nenačtený sloupec bereme jako vzduch. Renderer se s tím nepotká,
            // protože sekci nemešuje, dokud nejsou načtení všichni sousedé.
            return AIR;
        }

        return column.get(toLocal(x), y, toLocal(z));
    }

    /**
     * ⚠️ TŘI RŮZNÉ OTÁZKY, KTERÉ SE NESMÍ SLÉVAT DO JEDNÉ.
     *
     * Dokud byly všechny bloky plné krychle, stačil jediný test "není vzduch".
     * S vodou a nekrychlovými modely se to rozpadlo na tři nezávislé vlastnosti
     * a každá z nich řídí něco jiného:
     *
     *   isOpaque       zakrývá sousedy (mesher) a později i zastaví světlo
     *   blocksMovement zastaví hráče (kolize)
     *   isTargetable   dá se zaměřit a rozbít (paprsek)
     *
     * Voda: nic z toho. Pochodeň: jde rozbít, ale projde se skrz a nezakrývá.
     * Plot: zastaví hráče (celý blok), ale nezakrývá - je to sloupek
     * s příčkami k sousedům (BlockModels.of s sousedy).
     */
    /**
     * Kreslí se blok průhledným průchodem, kde platí alfa textury? Jen voda.
     *
     * ⚠️ Všechno ostatní jde neprůhledným průchodem a alfa se tam zahodí:
     * pixel s alfou 0 má ve světě svou barvu (guma z labu = černá). Ikona,
     * ruka i náhled kůže se tímhle řídí taky, aby ukazovaly totéž co svět.
     */
    public static boolean isTranslucent(byte blockId)
    {
        return blockId == WATER;
    }

    public static boolean isOpaque(byte blockId)
    {
        // Blok z labu je vždycky plná krychle, takže rozhodují jen jeho data.
        // Neznámé id (blocks.json zmizel) se chová jako dřív: plná kostka.
        if(blockId >= BlockRegistry.FIRST_ID)
        {
            BlockDef custom = BlockRegistry.lookup(blockId);
            return custom == null || custom.opaque();
        }

        return blockId != AIR && blockId != WATER && BlockModels.isFullCube(blockId);
    }

    /** Zastaví blok hráče? */
    public static boolean blocksMovement(byte blockId)
    {
        if(blockId >= BlockRegistry.FIRST_ID)
        {
            BlockDef custom = BlockRegistry.lookup(blockId);
            return custom == null || custom.solid();
        }

        return blockId != AIR && blockId != WATER && blockId != TORCH;
    }

    /**
     * Jak dlouho trvá blok rozbít, v sekundách.
     *
     * Poměry jsou převzaté z Minecraftu: hlína a písek se odhrábnou skoro
     * hned, kámen chvíli trvá a rudy nejdéle. Nástroje zatím neexistují,
     * takže je to holý čas bez násobičů.
     */
    public static float hardness(byte blockId)
    {
        // Blok z labu nese tvrdost v datech, ve stejných sekundách jako tady.
        BlockDef custom = BlockRegistry.lookup(blockId);

        if(custom != null)
        {
            return custom.hardness();
        }

        return switch(blockId)
        {
            case TORCH -> 0.05f;
            case LEAVES, BIRCH_LEAVES, SPRUCE_LEAVES, JUNGLE_LEAVES -> 0.2f;
            // Sníh je 0,5 jako hlína, ne 0,2 jako v Minecraftu: tvrdost tady
            // určuje i materiál zvuku (Sound.Material.byHardness) a sníh, který
            // by šustil jako listí, by zněl špatně.
            case GRASS, DIRT, SAND, SNOW -> 0.5f;
            case PLANKS, LOG, BIRCH_LOG, SPRUCE_LOG, FENCE, CRAFTING_TABLE -> 0.8f;
            case STONE, STONE_BRICKS -> 1.8f;
            case COAL_ORE -> 2.5f;
            case IRON_ORE -> 3.0f;
            default -> 0.5f;
        };
    }

    /**
     * Dá se blok zaměřit paprskem a rozbít? Blok z labu vždycky - i když
     * není pevný ani neprůhledný, musí jít vytěžit, jinak by ve světě
     * zůstal navždy.
     */
    public static boolean isTargetable(byte blockId)
    {
        return blockId != AIR && blockId != WATER;
    }

    public boolean isTargetable(int x, int y, int z)
    {
        return isTargetable(getBlock(x, y, z));
    }

    // ------------------------------------------------------------------
    // světlo
    // ------------------------------------------------------------------

    /**
     * Sluneční světlo 0-15. Mimo načtený svět vrací plnou hodnotu: kdyby
     * vracelo nulu, na okraji dohledu by se objevil černý pruh dřív, než se
     * tam sloupec stihne vygenerovat.
     */
    public int skyLightAt(int x, int y, int z)
    {
        ChunkColumn column = columns.get(key(toChunk(x), toChunk(z)));
        return column == null ? LightEngine.MAX_LIGHT
                : column.skyLight(toLocal(x), y, toLocal(z));
    }

    public int blockLightAt(int x, int y, int z)
    {
        ChunkColumn column = columns.get(key(toChunk(x), toChunk(z)));
        return column == null ? 0 : column.blockLight(toLocal(x), y, toLocal(z));
    }

    /**
     * Blok i obě světla jedním dotazem: blok &lt;&lt; 16 | sluneční &lt;&lt; 4 | blokové.
     *
     * ⚠️ Existuje kvůli plynulému osvětlení. To se ptá na čtyři buňky kolem
     * každého rohu stěny, tedy 24krát na blok - a každý zvlášť položený dotaz
     * je jedno vyhledání v mapě sloupců. Sloučením tří dotazů do jednoho spadla
     * stavba meshe na třetinu.
     */
    public int cellAt(int x, int y, int z)
    {
        ChunkColumn column = columns.get(key(toChunk(x), toChunk(z)));

        if(column == null)
        {
            // Mimo načtený svět: vzduch s plným sluncem, ať na okraji dohledu
            // není černý pruh. Stejná úmluva jako u skyLightAt().
            return (AIR << 16) | (LightEngine.MAX_LIGHT << 4);
        }

        int lx = toLocal(x);
        int lz = toLocal(z);

        return ((column.get(lx, y, lz) & 0xFF) << 16)
                | (column.skyLight(lx, y, lz) << 4)
                | column.blockLight(lx, y, lz);
    }

    public static byte cellBlock(int cell) { return (byte) ((cell >> 16) & 0xFF); }
    public static int cellSky(int cell)    { return (cell >> 4) & 0xF; }
    public static int cellBlockLight(int cell) { return cell & 0xF; }

    /**
     * Je sloupec na téhle pozici načtený?
     *
     * ⚠️ Musí to jít zjistit, protože skyLightAt() u nenačteného sloupce LŽE -
     * vrací plnou hodnotu, aby na okraji dohledu nebyl černý pruh. Pro mesher
     * je to správně, pro šíření světla je to zdroj světla, který neexistuje.
     */
    public boolean isColumnLoaded(int x, int z)
    {
        return columns.containsKey(key(toChunk(x), toChunk(z)));
    }

    /**
     * ⚠️ Zápis světla sekce NEZNAČÍ jako špinavé.
     *
     * Značit je u každé buňky se ukázalo jako nejdražší věc v celém osvětlení:
     * jedno položení pochodně změní tisíce buněk a každá by alokovala záznam
     * do množiny. Sekce k přestavbě si proto sbírá LightEngine, zdeduplikuje
     * je a nahlásí je najednou přes markLightedSection().
     */
    public void setSkyLightAt(int x, int y, int z, int value)
    {
        ChunkColumn column = columns.get(key(toChunk(x), toChunk(z)));

        if(column != null && y >= 0 && y < WORLD_HEIGHT)
        {
            column.setSkyLight(toLocal(x), y, toLocal(z), value);
        }
    }

    public void setBlockLightAt(int x, int y, int z, int value)
    {
        ChunkColumn column = columns.get(key(toChunk(x), toChunk(z)));

        if(column != null && y >= 0 && y < WORLD_HEIGHT)
        {
            column.setBlockLight(toLocal(x), y, toLocal(z), value);
        }
    }

    /** Sekce, ve které se změnilo světlo - mesh se musí přestavět. */
    public void markLightedSection(int cx, int cy, int cz)
    {
        if(cy >= 0 && cy < ChunkColumn.SECTIONS)
        {
            markDirty(cx, cy, cz);
        }
    }

    /** Kolik buněk čeká na dopočítání světla. Čte loading screen. */
    public int pendingLight()
    {
        return light.pending();
    }

    public boolean isWater(int x, int y, int z)
    {
        return getBlock(x, y, z) == WATER;
    }

    public boolean isSolid(int x, int y, int z)
    {
        return blocksMovement(getBlock(x, y, z));
    }

    /** Rozbije blok. Vrací true, když se to povedlo - volající pak ví, že má co sebrat. */
    public boolean breakBlock(int x, int y, int z)
    {
        return setBlock(x, y, z, AIR);
    }

    /**
     * Položí blok. Vrací true, když se to povedlo.
     * Pokládat jde jen do vzduchu - obsazená buňka se nepřepíše.
     */
    public boolean placeBlock(int x, int y, int z, byte blockId)
    {
        // Do vody se položit dá - blok ji vytlačí. Jinak by se jezero nedalo
        // zasypat a paprsek by kvůli průhlednosti mířil někam za něj.
        byte existing = getBlock(x, y, z);

        if(existing != AIR && existing != WATER)
        {
            return false;
        }

        return setBlock(x, y, z, blockId);
    }

    private boolean setBlock(int x, int y, int z, byte blockId)
    {
        if(y < 0 || y >= WORLD_HEIGHT)
        {
            return false;
        }

        ChunkColumn column = columns.get(key(toChunk(x), toChunk(z)));
        if(column == null)
        {
            return false;
        }

        int lx = toLocal(x);
        int lz = toLocal(z);

        byte previous = column.get(lx, y, lz);

        // Zápis stejného bloku nic nemění. Bez tohohle "rozbití" vzduchu
        // zapsalo změnu do uložených dat, pustilo sluneční paprsek (který
        // kvůli zápisu světla alokoval prázdnou sekci) a přestavělo meshe.
        if(previous == blockId)
        {
            return false;
        }

        column.set(lx, y, lz, blockId);
        light.blockChanged(x, y, z, previous, blockId);

        // Zaznamenat PROTI generátoru, ne stav sloupce - tahle mapa je všechno,
        // co se ukládá, a zároveň to, co drží změny naživu přes unload sloupce.
        changes.computeIfAbsent(key(toChunk(x), toChunk(z)), k -> new HashMap<>())
                .put(columnIndex(lx, y, lz), blockId);

        markDirtyAround(x, y, z);
        return true;
    }

    /**
     * Označí k přestavbě každou sekci, jejíž mesh na tenhle blok může koukat.
     *
     * ⚠️ NE JEN 6 STĚNOVÝCH SOUSEDŮ, ALE CELÉ OKOLÍ 3x3x3. Dřív tu platilo
     * "face culling kouká jen na 6 sousedů", jenže plynulé osvětlení a AO
     * berou pro každý roh stěny i buňky do strany a do rohu - stěna bloku
     * tedy závisí na všech 26 sousedech. Blok na hraně nebo rohu sekce
     * mění mesh i DIAGONÁLNÍ sekce a ta zůstávala se starým stínem (po
     * položení chyběl AO, po rozbití zůstal "stín duch"), dokud ji neoznačilo
     * něco jiného. Uvnitř sekce je to pořád jedna sekce; na rohu nejvýš 8.
     */
    private void markDirtyAround(int x, int y, int z)
    {
        int cx0 = toChunk(x - 1), cx1 = toChunk(x + 1);
        int cz0 = toChunk(z - 1), cz1 = toChunk(z + 1);
        int cy0 = Math.max(0, (y - 1) >> Chunk.BITS);
        int cy1 = Math.min(ChunkColumn.SECTIONS - 1, (y + 1) >> Chunk.BITS);

        for(int cx = cx0; cx <= cx1; cx++)
        {
            for(int cz = cz0; cz <= cz1; cz++)
            {
                for(int cy = cy0; cy <= cy1; cy++)
                {
                    markDirty(cx, cy, cz);
                }
            }
        }
    }

    private void markDirty(int cx, int cy, int cz)
    {
        dirtySections.add(new SectionPos(cx, cy, cz));
    }

}
