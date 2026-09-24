package mc;

/**
 * Šíření světla.
 *
 * ---------------------------------------------------------------------------
 * DVA NEZÁVISLÉ KANÁLY, oba 0-15:
 *
 *   sluneční  padá shora, v noci se ztlumí (násobičem v shaderu, ne v datech -
 *             přepočítávat celý svět při každém západu slunce nepřipadá v úvahu)
 *   blokové   vzniká u pochodní a v noci svítí dál
 *
 * Šíří se prohledáváním do šířky: ze zdroje ven, každý krok o jedna slabší.
 *
 * ⚠️ ODEBRÁNÍ SVĚTLA JE TĚŽŠÍ NEŽ PŘIDÁNÍ a je to klasický zdroj chyb.
 * Když zmizí pochodeň, nestačí jí nastavit nulu - musí se projít celá oblast,
 * kterou osvětlovala, vynulovat ji, a přitom si zapamatovat každý okraj, kde
 * narazí na světlo, které tam patří odjinud. Ty okraje se pak použijí jako
 * nové zdroje a oblast se dosvítí zpátky. Bez druhé fáze zůstane po zhasnuté
 * pochodni tmavá díra i tam, kam dosvítí slunce.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, takže jde celé otestovat headless.
 */
public class LightEngine {

    public static final int MAX_LIGHT = 15;

    /** Jak silně svítí pochodeň. O jedna míň než maximum, jako v Minecraftu. */
    public static final int TORCH_LIGHT = 14;

    /** Rozlišení dvou kanálů. Kód šíření je pro oba stejný. */
    private static final int SKY = 0;
    private static final int BLOCK = 1;

    private final World world;

    // Fronty: co rozsvítit a co zhasnout, zvlášť pro každý kanál.
    private final LongQueue[] spread = {new LongQueue(), new LongQueue()};
    private final LongQueue[] darken = {new LongQueue(), new LongQueue()};

    /** U zhasínání se kromě pozice nese i to, jak silné světlo tam bylo. */
    private final LongQueue[] darkenLevel = {new LongQueue(), new LongQueue()};

    /**
     * Sekce, kterým se změnilo světlo a potřebují přestavět mesh.
     *
     * ⚠️ Sbírají se tady a hlásí se najednou. Značit sekci u každé změněné
     * buňky bylo nejdražší místo celého osvětlení - jedno položení pochodně
     * změní tisíce buněk, ale dotkne se nejvýš pár desítek sekcí.
     */
    private final LongSet touched = new LongSet();

    public LightEngine(World world)
    {
        this.world = world;
    }

    // ------------------------------------------------------------------
    // souřadnice zabalené do longu
    //
    // x a z se do světa vejdou na 26 bitů se znaménkem (±33 milionů bloků),
    // y na 8. Fronta je pak pole longů bez jediné alokace na uzel - BFS jich
    // po položení pochodně projde tisíce.
    // ------------------------------------------------------------------

    private static long pack(int x, int y, int z)
    {
        return ((long) (x & 0x3FFFFFF) << 34) | ((long) (z & 0x3FFFFFF) << 8) | (y & 0xFF);
    }

    private static int unpackX(long v) { return signExtend26((int) (v >>> 34)); }
    private static int unpackZ(long v) { return signExtend26((int) (v >>> 8)); }
    private static int unpackY(long v) { return (int) (v & 0xFF); }

    private static int signExtend26(int value)
    {
        return (value & 0x3FFFFFF) << 6 >> 6;
    }

    // ------------------------------------------------------------------
    // založení sloupce
    // ------------------------------------------------------------------

    /**
     * Nasvítí čerstvě vygenerovaný sloupec.
     *
     * Svislý průchod: shora dolů je plné sluneční světlo, dokud nenarazí na
     * neprůhledný blok; od něj níž je tma, kterou pak dosvítí prohledávání
     * do šířky - odtud stíny pod převisy a světlo ve vchodech do jeskyní.
     */
    /**
     * ⚠️ Zapisuje se PŘÍMO DO SLOUPCE, ne přes World.setSkyLightAt.
     *
     * Ta cesta totiž u každého zápisu označí okolní sekce jako špinavé, aby
     * se přestavěl mesh - jenže při zakládání sloupce žádný mesh ještě
     * neexistuje. Tisíce zbytečných záznamů v HashSetu stály víc než celé
     * generování terénu a přechod přes hranici chunku kvůli tomu trhal.
     *
     * @param hasLightSources jestli sloupec vůbec může obsahovat pochodeň.
     *        Vygenerovaný terén žádnou nemá, takže se pak přeskočí procházení
     *        celé výšky sloupce.
     */
    public void seedColumn(ChunkColumn column, boolean hasLightSources)
    {
        int baseX = column.cx << Chunk.BITS;
        int baseZ = column.cz << Chunk.BITS;

        // Výška povrchu se počítá jednou a schová se; hledat ji podruhé
        // znamená projít celý sloupec znovu.
        int[] top = new int[Chunk.SIZE * Chunk.SIZE];
        int highest = 0;

        for(int lx = 0; lx < Chunk.SIZE; lx++)
        {
            for(int lz = 0; lz < Chunk.SIZE; lz++)
            {
                int t = topOpaque(column, lx, lz);
                top[lz * Chunk.SIZE + lx] = t;
                highest = Math.max(highest, t);
            }
        }

        // Sekce nad nejvyšším blokem sloupce jsou celý vzduch - výchozí hodnota
        // 15 je ušetří od alokace pole světla.
        int sunlitFrom = Math.min(ChunkColumn.SECTIONS, (highest >> Chunk.BITS) + 1);
        column.setFullySunlitAbove(sunlitFrom);

        int sunlitBelow = sunlitFrom << Chunk.BITS;

        for(int lx = 0; lx < Chunk.SIZE; lx++)
        {
            for(int lz = 0; lz < Chunk.SIZE; lz++)
            {
                int t = top[lz * Chunk.SIZE + lx];

                for(int y = t + 1; y < sunlitBelow; y++)
                {
                    column.setSkyLight(lx, y, lz, MAX_LIGHT);
                }

                // ⚠️ Do fronty nestačí jen nejnižší osvětlená buňka.
                //
                // Vedlejší sloupeček může mít terén výš a jeho stín sahá nad
                // úroveň zdejšího povrchu; světlo do něj musí přijít ZE STRANY
                // z buněk v odpovídající výšce. Když se zařadilo jen čelo
                // u země, zůstal na švu chunků pruh o jednu tmavší - okem
                // k nerozeznání od stínu, našel to až nezávislý přepočet.
                //
                // Zařadit ale všechny osvětlené buňky je zbytečně drahé
                // (tisíce uzlů na sloupec). Stačí ty, které mají kam svítit:
                // po nejvyššího ze čtyř sousedů. Na okraji chunku se sousední
                // terén nezná, takže se tam zařadí celý rozsah.
                int reach = neighbourTop(top, lx, lz) + 1;

                for(int y = t + 1; y <= Math.min(reach, sunlitBelow - 1); y++)
                {
                    spread[SKY].add(pack(baseX + lx, y, baseZ + lz));
                }
            }
        }

        if(hasLightSources)
        {
            for(int lx = 0; lx < Chunk.SIZE; lx++)
            {
                for(int lz = 0; lz < Chunk.SIZE; lz++)
                {
                    for(int y = 0; y < World.WORLD_HEIGHT; y++)
                    {
                        byte block = column.get(lx, y, lz);

                        if(emission(block) > 0)
                        {
                            column.setBlockLight(lx, y, lz, emission(block));
                            spread[BLOCK].add(pack(baseX + lx, y, baseZ + lz));
                        }
                    }
                }
            }
        }

        // Sousední sloupce mohly být nasvícené dřív a jejich světlo teď má kam
        // přetéct. Jejich okraje se proto přidají do fronty znovu.
        reseedBorders(column, baseX, baseZ);
    }

    /**
     * Vrátí okraje sousedních sloupců do fronty.
     *
     * ⚠️ Bez tohohle je na hranici chunku vidět šev: sloupec nasvícený dřív
     * si "myslel", že za jeho hranicí je tma, a nic ho už nedonutí přepočítat.
     */
    private void reseedBorders(ChunkColumn column, int baseX, int baseZ)
    {
        // ⚠️ KAŽDÉ y, ne po čtyřech. Vynechané výšky znamenaly, že přes hranici
        // chunku neteklo světlo do všech vrstev a na švu byl pruh o jednu
        // tmavší - okem k nerozeznání od stínu, našel to až nezávislý přepočet.
        //
        // Sousední sloupce se proto vytáhnou JEDNOU. Ptát se na ně přes
        // World.skyLightAt by znamenalo tři vyhledání v mapě na každou z osmi
        // tisíc buněk okraje, a fronta světla by pak byla trvale plná.
        border(world.column(column.cx - 1, column.cz), baseX - 1, baseZ, 0, 1);
        border(world.column(column.cx + 1, column.cz), baseX + Chunk.SIZE, baseZ, 0, 1);
        border(world.column(column.cx, column.cz - 1), baseX, baseZ - 1, 1, 0);
        border(world.column(column.cx, column.cz + 1), baseX, baseZ + Chunk.SIZE, 1, 0);
    }

    /**
     * Zařadí osvětlené buňky jedné hrany sousedního sloupce.
     * stepX/stepZ říká, kterým směrem se po hraně jde.
     */
    private void border(ChunkColumn neighbour, int startX, int startZ, int stepX, int stepZ)
    {
        if(neighbour == null)
        {
            return;   // ⚠️ z nenačteného sloupce se nesmí šířit nic
        }

        for(int i = 0; i < Chunk.SIZE; i++)
        {
            int x = startX + stepX * i;
            int z = startZ + stepZ * i;

            int lx = x & Chunk.MASK;
            int lz = z & Chunk.MASK;

            for(int y = 0; y < World.WORLD_HEIGHT; y++)
            {
                if(neighbour.skyLight(lx, y, lz) > 0)
                {
                    spread[SKY].add(pack(x, y, z));
                }

                if(neighbour.blockLight(lx, y, lz) > 0)
                {
                    spread[BLOCK].add(pack(x, y, z));
                }
            }
        }
    }

    private void enqueueIfLit(int x, int y, int z)
    {
        // ⚠️ Z nenačteného sloupce se nesmí šířit nic.
        if(!world.isColumnLoaded(x, z))
        {
            return;
        }

        if(world.skyLightAt(x, y, z) > 0)
        {
            spread[SKY].add(pack(x, y, z));
        }

        if(world.blockLightAt(x, y, z) > 0)
        {
            spread[BLOCK].add(pack(x, y, z));
        }
    }

    /**
     * Nejvyšší terén ze čtyř sousedních sloupečků uvnitř téhle sekce.
     * Na okraji chunku se soused nezná, takže se vrátí strop světa - tam se
     * do fronty zařadí celý osvětlený rozsah a nic se nevynechá.
     */
    private static int neighbourTop(int[] top, int lx, int lz)
    {
        if(lx == 0 || lz == 0 || lx == Chunk.MASK || lz == Chunk.MASK)
        {
            return World.WORLD_HEIGHT;
        }

        int best = top[lz * Chunk.SIZE + lx];

        best = Math.max(best, top[lz * Chunk.SIZE + lx + 1]);
        best = Math.max(best, top[lz * Chunk.SIZE + lx - 1]);
        best = Math.max(best, top[(lz + 1) * Chunk.SIZE + lx]);
        best = Math.max(best, top[(lz - 1) * Chunk.SIZE + lx]);

        return best;
    }

    /** Nejvyšší neprůhledný blok ve sloupci, nebo -1. */
    private static int topOpaque(ChunkColumn column, int lx, int lz)
    {
        for(int y = World.WORLD_HEIGHT - 1; y >= 0; y--)
        {
            if(World.isOpaque(column.get(lx, y, lz)))
            {
                return y;
            }
        }

        return -1;
    }

    /** Kolik světla blok vydává. */
    public static int emission(byte block)
    {
        return block == World.TORCH ? TORCH_LIGHT : 0;
    }

    // ------------------------------------------------------------------
    // změna bloku
    // ------------------------------------------------------------------

    /**
     * Blok se změnil - přepočítej okolí.
     *
     * Čtyři případy naráz: zdroj světla zmizel, zdroj přibyl, díra se ucpala
     * (světlo se musí odebrat) a díra vznikla (světlo má kam téct).
     */
    public void blockChanged(int x, int y, int z, byte oldBlock, byte newBlock)
    {
        int oldEmission = emission(oldBlock);

        if(oldEmission > 0)
        {
            queueDarken(BLOCK, x, y, z, oldEmission);
            world.setBlockLightAt(x, y, z, 0);
        }

        // Nový neprůhledný blok pohltí světlo, které v jeho buňce bylo.
        if(World.isOpaque(newBlock))
        {
            int sky = world.skyLightAt(x, y, z);
            if(sky > 0)
            {
                queueDarken(SKY, x, y, z, sky);
                world.setSkyLightAt(x, y, z, 0);
            }

            int block = world.blockLightAt(x, y, z);
            if(block > 0)
            {
                queueDarken(BLOCK, x, y, z, block);
                world.setBlockLightAt(x, y, z, 0);
            }
        }

        int newEmission = emission(newBlock);

        if(newEmission > 0)
        {
            world.setBlockLightAt(x, y, z, newEmission);
            spread[BLOCK].add(pack(x, y, z));
        }

        // Díra po odstraněném bloku: světlo ze sousedů má kam téct.
        if(!World.isOpaque(newBlock))
        {
            for(int face = 0; face < 6; face++)
            {
                enqueueIfLit(x + FACE_X[face], y + FACE_Y[face], z + FACE_Z[face]);
            }

            // A shora může padnout sluneční paprsek celým nově vzniklým otvorem.
            resendSunlightColumn(x, y, z);
        }
    }

    /**
     * Po vykopání díry do stropu musí sluneční světlo spadnout dolů celým
     * sloupcem, ne se jen rozlít o jedna slabší z okolí.
     */
    private void resendSunlightColumn(int x, int y, int z)
    {
        if(world.skyLightAt(x, y + 1, z) != MAX_LIGHT)
        {
            return;
        }

        for(int scan = y; scan >= 0; scan--)
        {
            if(World.isOpaque(world.getBlock(x, scan, z)))
            {
                break;
            }

            // ⚠️ touch(): setSkyLightAt sekci záměrně neznačí a další BFS
            // v úzké šachtě už nic nezmění - bez tohohle zůstaly stěny šachty
            // pod první sekcí v meshi tmavé, i když data světla byla správně.
            world.setSkyLightAt(x, scan, z, MAX_LIGHT);
            touch(x, scan, z);
            spread[SKY].add(pack(x, scan, z));
        }
    }

    private void queueDarken(int channel, int x, int y, int z, int level)
    {
        darken[channel].add(pack(x, y, z));
        darkenLevel[channel].add(level);
    }

    // ------------------------------------------------------------------
    // zpracování front
    // ------------------------------------------------------------------

    private static final int[] FACE_X = {1, -1, 0, 0, 0, 0};
    private static final int[] FACE_Y = {0, 0, 1, -1, 0, 0};
    private static final int[] FACE_Z = {0, 0, 0, 0, 1, -1};

    /** Kolik práce zbývá. Loading screen podle toho pozná, že se ještě svítí. */
    public int pending()
    {
        return spread[SKY].size() + spread[BLOCK].size()
                + darken[SKY].size() + darken[BLOCK].size();
    }

    /**
     * Zpracuje fronty, dokud nedojde časový rozpočet.
     *
     * Rozpočet je časový ze stejného důvodu jako u stavby meshů: počet uzlů
     * by se na pomalém stroji do framu nevešel a na rychlém by se nevyužil.
     * Zhasínání jde první - jinak by se rozsvěcovalo do oblasti, která se
     * vzápětí vynuluje.
     */
    public void process(long budgetNanos)
    {
        long deadline = System.nanoTime() + budgetNanos;

        while(System.nanoTime() < deadline)
        {
            if(!darken[BLOCK].isEmpty())      { stepDarken(BLOCK); }
            else if(!darken[SKY].isEmpty())   { stepDarken(SKY); }
            else if(!spread[BLOCK].isEmpty()) { stepSpread(BLOCK); }
            else if(!spread[SKY].isEmpty())   { stepSpread(SKY); }
            else                              { break; }
        }

        flushTouched();
    }

    private void stepSpread(int channel)
    {
        long node = spread[channel].poll();

        int x = unpackX(node), y = unpackY(node), z = unpackZ(node);

        // ⚠️ TOHLE JE TA PAST. skyLightAt u nenačteného sloupce vrací 15, takže
        // uzel, jehož sloupec se mezitím zahodil (nebo ještě nedorazil), by se
        // tvářil jako plné slunce a rozlil 14 do okolí - i do jeskyně dvacet
        // bloků pod zemí. Projevilo se to jen ve hře: v blokujícím režimu se
        // fronta zpracuje až po načtení všeho, takže testy o tom nevěděly.
        if(!world.isColumnLoaded(x, z))
        {
            return;
        }

        int level = get(channel, x, y, z);

        if(level <= 1)
        {
            return;
        }

        for(int face = 0; face < 6; face++)
        {
            int nx = x + FACE_X[face], ny = y + FACE_Y[face], nz = z + FACE_Z[face];

            if(ny < 0 || ny >= World.WORLD_HEIGHT
                    || !world.isColumnLoaded(nx, nz)
                    || World.isOpaque(world.getBlock(nx, ny, nz)))
            {
                continue;
            }

            // Sluneční světlo padá dolů beze ztráty - jinak by se pod každou
            // dírou ve stropu zužoval kužel a jáma by byla tmavá už po pár blocích.
            int target = channel == SKY && face == 3 && level == MAX_LIGHT
                    ? MAX_LIGHT
                    : level - 1;

            if(get(channel, nx, ny, nz) >= target)
            {
                continue;
            }

            set(channel, nx, ny, nz, target);
            spread[channel].add(pack(nx, ny, nz));
        }
    }

    private void stepDarken(int channel)
    {
        long node = darken[channel].poll();
        int was = (int) darkenLevel[channel].poll();

        int x = unpackX(node), y = unpackY(node), z = unpackZ(node);

        if(!world.isColumnLoaded(x, z))
        {
            return;
        }

        for(int face = 0; face < 6; face++)
        {
            int nx = x + FACE_X[face], ny = y + FACE_Y[face], nz = z + FACE_Z[face];

            if(ny < 0 || ny >= World.WORLD_HEIGHT || !world.isColumnLoaded(nx, nz))
            {
                continue;
            }

            int neighbour = get(channel, nx, ny, nz);

            if(neighbour == 0)
            {
                continue;
            }

            // Slabší soused svítil od nás - zhasne a šíří tmu dál.
            // Stejně silný nebo silnější patří někomu jinému a stane se
            // z něj zdroj, ze kterého se oblast dosvítí zpátky.
            boolean litByUs = neighbour < was
                    || (channel == SKY && face == 3 && was == MAX_LIGHT && neighbour == MAX_LIGHT);

            if(litByUs)
            {
                set(channel, nx, ny, nz, 0);
                queueDarken(channel, nx, ny, nz, neighbour);
            }
            else
            {
                spread[channel].add(pack(nx, ny, nz));
            }
        }
    }

    private int get(int channel, int x, int y, int z)
    {
        return channel == SKY ? world.skyLightAt(x, y, z) : world.blockLightAt(x, y, z);
    }

    private void set(int channel, int x, int y, int z, int value)
    {
        if(channel == SKY)
        {
            world.setSkyLightAt(x, y, z, value);
        }
        else
        {
            world.setBlockLightAt(x, y, z, value);
        }

        touch(x, y, z);
    }

    /**
     * Poznamená každou sekci, jejíž mesh si světlo z téhle buňky bere.
     *
     * ⚠️ OKOLÍ 3x3x3, ne 6 stěnových sousedů - plynulé osvětlení čte světlo
     * i z buněk do strany a do rohu stěny, takže buňka na hraně nebo rohu
     * sekce mění i diagonální sekce (viz World.markDirtyAround). Uvnitř
     * sekce vyjde jedna sekce, takže BFS tím skoro nic nestojí.
     */
    private void touch(int x, int y, int z)
    {
        int cx0 = (x - 1) >> Chunk.BITS, cx1 = (x + 1) >> Chunk.BITS;
        int cy0 = (y - 1) >> Chunk.BITS, cy1 = (y + 1) >> Chunk.BITS;
        int cz0 = (z - 1) >> Chunk.BITS, cz1 = (z + 1) >> Chunk.BITS;

        for(int cx = cx0; cx <= cx1; cx++)
        {
            for(int cz = cz0; cz <= cz1; cz++)
            {
                for(int cy = cy0; cy <= cy1; cy++)
                {
                    touched.add(sectionKey(cx, cy, cz));
                }
            }
        }
    }

    private static long sectionKey(int cx, int cy, int cz)
    {
        return ((long) (cx & 0xFFFFF) << 44) | ((long) (cz & 0xFFFFF) << 24) | (cy & 0xFF);
    }

    private static int keyX(long k) { return (int) (k >>> 44) << 12 >> 12; }
    private static int keyZ(long k) { return (int) (k >>> 24) << 12 >> 12; }
    private static int keyY(long k) { return (int) (k & 0xFF); }

    /** Nahlásí světu všechny sekce, kterým se změnilo světlo, a zapomene je. */
    private void flushTouched()
    {
        if(touched.isEmpty())
        {
            return;
        }

        touched.forEach(key -> world.markLightedSection(keyX(key), keyY(key), keyZ(key)));
        touched.clear();
    }

    /**
     * Množina longů bez boxování, otevřené adresování.
     *
     * ⚠️ HashSet&lt;Long&gt; tady byl vyloženě špatně: BFS volá touch() pro každou
     * změněnou buňku, tedy desetitisíckrát za frame, a každé volání by zabalilo
     * Long. Vzniklý odpad rozhoupal GC natolik, že pauzy byly větší než celý
     * rozpočet na šíření světla.
     */
    private static final class LongSet {

        private static final long EMPTY = Long.MIN_VALUE;

        private long[] keys = new long[512];
        private int size = 0;

        LongSet()
        {
            java.util.Arrays.fill(keys, EMPTY);
        }

        void add(long value)
        {
            if((size + 1) * 2 > keys.length)
            {
                grow();
            }

            int mask = keys.length - 1;
            int i = (int) (mix(value) & mask);

            while(keys[i] != EMPTY)
            {
                if(keys[i] == value)
                {
                    return;
                }

                i = (i + 1) & mask;
            }

            keys[i] = value;
            size++;
        }

        boolean isEmpty()
        {
            return size == 0;
        }

        void forEach(java.util.function.LongConsumer action)
        {
            for(long key : keys)
            {
                if(key != EMPTY)
                {
                    action.accept(key);
                }
            }
        }

        void clear()
        {
            java.util.Arrays.fill(keys, EMPTY);
            size = 0;
        }

        private void grow()
        {
            long[] old = keys;
            keys = new long[old.length * 2];
            java.util.Arrays.fill(keys, EMPTY);
            size = 0;

            for(long key : old)
            {
                if(key != EMPTY)
                {
                    add(key);
                }
            }
        }

        /** Rozhoz bitů, ať sousední klíče sekcí nepadnou do jednoho shluku. */
        private static long mix(long value)
        {
            long h = value * 0x9E3779B97F4A7C15L;
            return (h ^ (h >>> 32)) & Long.MAX_VALUE;
        }
    }

    /** Fronta longů bez boxování - BFS jich projde tisíce na jedno položení pochodně. */
    private static final class LongQueue {

        private long[] data = new long[1024];
        private int head = 0;
        private int tail = 0;

        void add(long value)
        {
            if(tail == data.length)
            {
                compact();
            }

            data[tail++] = value;
        }

        long poll()
        {
            return data[head++];
        }

        boolean isEmpty()
        {
            return head == tail;
        }

        int size()
        {
            return tail - head;
        }

        /** Posune obsah na začátek, a když se pořád nevejde, pole zvětší. */
        private void compact()
        {
            int used = tail - head;

            if(used > data.length / 2)
            {
                long[] bigger = new long[data.length * 2];
                System.arraycopy(data, head, bigger, 0, used);
                data = bigger;
            }
            else
            {
                System.arraycopy(data, head, data, 0, used);
            }

            head = 0;
            tail = used;
        }
    }
}
