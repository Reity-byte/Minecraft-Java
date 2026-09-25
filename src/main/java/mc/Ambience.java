package mc;

import java.util.Random;

/**
 * Zvuky prostředí: vítr venku, hučení v jeskyni, šplouchání u vody a občas
 * kapka v podzemí.
 *
 * ---------------------------------------------------------------------------
 * Tři smyčky hrají pořád (SoundEngine), tahle třída jim jen každý frame
 * nastaví hlasitost podle toho, kde jsou uši (kamera):
 *
 *   vítr   pod širým nebem (sluneční světlo v buňce hlavy - ne denní doba,
 *          takže fouká i v noci) a víc ve výšce; v budově a v jeskyni ne
 *   jeskyně  tma od oblohy A pod hladinou moře - tmavý dům na povrchu
 *          nehučí, ani noc venku (sluneční světlo buňky se v noci nemění)
 *   voda   podle vzdálenosti k nejbližší vodě do 8 bloků; pod vodou naplno
 *          a ostatní ztichnou
 *
 * ⚠️ Hlasitosti se k cíli DOTAHUJÍ (asi za sekundu), neskáčou. Vstup do
 * jeskyně nebo vynoření jinak zní jako vypínač.
 *
 * Nejbližší voda se hledá jen dvakrát za sekundu - je to 2601 buněk kolem
 * hlavy a voda neuteče.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na OpenAL - hraje přes SoundSink, takže jde otestovat s nahrávačem.
 */
public final class Ambience {

    /** Nejvyšší hlasitost smyček - prostředí je kulisa, ne efekt. */
    static final float WIND_MAX = 0.35f;
    static final float CAVE_MAX = 0.55f;
    static final float WATER_MAX = 0.45f;
    static final float UNDERWATER = 0.6f;

    /** Časová konstanta dotahování hlasitosti (s). */
    static final float FADE_SECONDS = 0.8f;

    /** Kde se hledá voda: ±8 bloků do stran, ±4 nahoru a dolů. */
    static final int WATER_RADIUS = 8;
    static final int WATER_HEIGHT = 4;
    static final float SCAN_INTERVAL = 0.5f;

    /** Vítr je od hladiny moře slabší (35 %) a naplno o 40 bloků výš. */
    static final float WIND_LOW = 0.35f;
    static final float WIND_FULL_ABOVE_SEA = 40f;

    /** Hučení naplno 10 bloků pod hladinou moře. */
    static final float CAVE_FULL_BELOW_SEA = 10f;

    /** Kapky: když je hučení aspoň z poloviny, jednou za 4 až 14 s, do 6 bloků od hlavy. */
    static final float DRIP_MIN = 4f, DRIP_MAX = 14f, DRIP_RANGE = 6f;

    private final Random random;

    private float wind = 0f, cave = 0f, water = 0f;
    private float waterDistance = Float.POSITIVE_INFINITY;
    private float scanIn = 0f;
    private float dripIn = Float.NaN;

    public Ambience(Random random)
    {
        this.random = random;
    }

    public float wind()  { return wind; }
    public float cave()  { return cave; }
    public float water() { return water; }

    /** Frame ve hře: cíle podle okolí, dotáhnout, nastavit smyčky, občas kapka. */
    public void update(World world, float x, float y, float z, float dt, SoundSink sink)
    {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);

        boolean underwater = world.isWater(bx, by, bz);
        int sky = by >= World.WORLD_HEIGHT ? LightEngine.MAX_LIGHT : world.skyLightAt(bx, by, bz);

        scanIn -= dt;

        if(scanIn <= 0f)
        {
            waterDistance = nearestWater(world, bx, by, bz);
            scanIn = SCAN_INTERVAL;
        }

        approach(windTarget(sky, y, underwater), caveTarget(sky, y, underwater),
                waterTarget(waterDistance, underwater), dt);
        emit(sink);
        drip(x, y, z, dt, sink);
    }

    /** Frame mimo hru (pauza, menu): všechno doznívá do ticha. */
    public void silence(float dt, SoundSink sink)
    {
        approach(0f, 0f, 0f, dt);
        emit(sink);

        // Po návratu hledat vodu hned - hráč mohl mezitím načíst jiný svět.
        scanIn = 0f;
    }

    private void approach(float windTarget, float caveTarget, float waterTarget, float dt)
    {
        if(dt <= 0f)
        {
            return;
        }

        float k = 1f - (float) Math.exp(-dt / FADE_SECONDS);
        wind += (windTarget - wind) * k;
        cave += (caveTarget - cave) * k;
        water += (waterTarget - water) * k;
    }

    private void emit(SoundSink sink)
    {
        sink.loop(Sound.AMBIENT_WIND, wind);
        sink.loop(Sound.AMBIENT_CAVE, cave);
        sink.loop(Sound.AMBIENT_WATER, water);
    }

    /**
     * Kapka v jeskyni, poziční - z náhodného místa kolem hlavy, takže
     * s útlumem a směrem. Odpočet běží, jen když jeskyně hučí aspoň z půlky.
     */
    private void drip(float x, float y, float z, float dt, SoundSink sink)
    {
        if(cave < CAVE_MAX * 0.5f)
        {
            dripIn = Float.NaN;
            return;
        }

        // První kapka ne hned po vstupu, ale po náhodné pauze.
        if(Float.isNaN(dripIn))
        {
            dripIn = nextDripDelay();
        }

        dripIn -= dt;

        if(dripIn <= 0f)
        {
            sink.playAt(Sound.CAVE_DRIP,
                    x + (random.nextFloat() * 2f - 1f) * DRIP_RANGE,
                    y + random.nextFloat() * 3f,
                    z + (random.nextFloat() * 2f - 1f) * DRIP_RANGE);
            dripIn = nextDripDelay();
        }
    }

    private float nextDripDelay()
    {
        return DRIP_MIN + random.nextFloat() * (DRIP_MAX - DRIP_MIN);
    }

    // ------------------------------------------------------------------
    // čisté funkce
    // ------------------------------------------------------------------

    static float clamp01(float v)
    {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Vítr: otevřenost nebi (na druhou - pod stromem už skoro nefouká) krát výška. */
    static float windTarget(int skyLight, float y, boolean underwater)
    {
        if(underwater)
        {
            return 0f;
        }

        float open = skyLight / (float) LightEngine.MAX_LIGHT;
        float height = WIND_LOW + (1f - WIND_LOW) * clamp01((y - World.SEA_LEVEL) / WIND_FULL_ABOVE_SEA);
        return WIND_MAX * open * open * height;
    }

    /** Jeskyně: tma od oblohy krát hloubka pod hladinou moře. */
    static float caveTarget(int skyLight, float y, boolean underwater)
    {
        if(underwater)
        {
            return 0f;
        }

        float dark = 1f - skyLight / (float) LightEngine.MAX_LIGHT;
        float depth = clamp01((World.SEA_LEVEL - y) / CAVE_FULL_BELOW_SEA);
        return CAVE_MAX * dark * depth;
    }

    /** Voda: lineárně od plné hlasitosti u vody k nule na WATER_RADIUS blocích. */
    static float waterTarget(float distance, boolean underwater)
    {
        if(underwater)
        {
            return UNDERWATER;
        }

        return WATER_MAX * clamp01(1f - distance / WATER_RADIUS);
    }

    /** Vzdálenost k nejbližší vodě kolem buňky (středy buněk), nebo nekonečno. */
    static float nearestWater(World world, int bx, int by, int bz)
    {
        float best = Float.POSITIVE_INFINITY;

        for(int dy = -WATER_HEIGHT; dy <= WATER_HEIGHT; dy++)
        {
            int y = by + dy;

            if(y < 0 || y >= World.WORLD_HEIGHT)
            {
                continue;
            }

            for(int dx = -WATER_RADIUS; dx <= WATER_RADIUS; dx++)
            {
                for(int dz = -WATER_RADIUS; dz <= WATER_RADIUS; dz++)
                {
                    float d2 = dx * dx + dy * dy + dz * dz;

                    if(d2 < best * best && world.isWater(bx + dx, y, bz + dz))
                    {
                        best = (float) Math.sqrt(d2);
                    }
                }
            }
        }

        return best;
    }
}
