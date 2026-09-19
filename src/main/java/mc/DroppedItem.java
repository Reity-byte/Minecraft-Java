package mc;

/**
 * Jedna hromádka ležící ve světě - samostatný objekt, ne blok.
 *
 * ---------------------------------------------------------------------------
 * Fyzika je schválně jednoduchá: gravitace, útlum a kolize krabičky 0,25³
 * proti TÝMŽ blokům, o které se zarazí hráč (World.isSolid). Kolize se řeší
 * po osách a pohyb se dělí na kroky ze stejných důvodů jako u hráče - viz
 * Player. Položka se neodráží, nekutálí a s ostatními se nestrká.
 *
 * y je SPODEK krabičky, stejně jako u hráče.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, takže jde celé otestovat headless.
 */
public class DroppedItem {

    /** Hrana krabičky. Stejně jako v Minecraftu - čtvrtina bloku. */
    public static final float SIZE = 0.25f;
    private static final float HALF = SIZE / 2f;

    /**
     * Konstanty jsou z Minecraftu, přepočtené z ticků (20 za sekundu) na
     * sekundy: gravitace 0,04 bloku za tick² = 16 b/s².
     */
    private static final float GRAVITY = 16f;

    /**
     * Kolik rychlosti zbude po jedné sekundě. Minecraft násobí 0,98 za tick
     * ve vzduchu (0,98^20 = 0,67) a 0,98 · 0,6 na zemi (0,588^20 ≈ 2,4e-5,
     * tedy položka se po dopadu skoro hned zastaví).
     *
     * Terminální rychlost se nehlídá zvlášť - vypadne z útlumu sama:
     * g / -ln(0,67) = 16 / 0,4 = 40 b/s, stejně jako v Minecraftu.
     *
     * Útlum je mocnina pow(drag, dt), ne násobek: musí vyjít stejně bez ohledu
     * na délku framu. Stejné pravidlo jako u hráče ve vodě.
     */
    private static final float AIR_DRAG = 0.67f;
    private static final float GROUND_DRAG = 2.4e-5f;

    /**
     * Ve vodě položka pomalu klesá ke dnu (~1,3 b/s). Nevyplave - to je
     * vědomé zjednodušení, viz ARCHITECTURE.md.
     */
    private static final float WATER_GRAVITY_SCALE = 0.25f;
    private static final float WATER_DRAG = 0.05f;

    /** Stejné pojistky jako u hráče: strop na dt a na délku jednoho kroku. */
    private static final float MAX_TIME_STEP = 0.05f;
    private static final float MAX_SUBSTEP = 0.4f;
    private static final float EPSILON = 1e-3f;

    public float x, y, z;       // y = spodek krabičky
    public float vx, vy, vz;
    public boolean onGround = false;

    /** Co leží na zemi. Při částečném sebrání se zmenší. */
    private ItemStack stack;

    /** Jak dlouho položka existuje, v sekundách. Řídí zánik i animaci. */
    private float age = 0f;

    /** Za jak dlouho půjde sebrat. Vyhozená položka nesmí skončit hned zpátky v ruce. */
    private float pickupDelay;

    /** Posun animace, aby se položky vedle sebe netočily jako jedna. */
    private final float phase;

    public DroppedItem(ItemStack stack, float x, float y, float z,
                       float vx, float vy, float vz, float pickupDelay, float phase)
    {
        this.stack = stack;
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.pickupDelay = pickupDelay;
        this.phase = phase;
    }

    public ItemStack stack() { return stack; }
    public float age()       { return age; }
    public float phase()     { return phase; }

    void setStack(ItemStack stack)
    {
        this.stack = stack;
    }

    public boolean canBePickedUp()
    {
        return pickupDelay <= 0f;
    }

    // ------------------------------------------------------------------

    public void update(World world, float dt)
    {
        dt = Math.min(dt, MAX_TIME_STEP);

        age += dt;
        pickupDelay = Math.max(0f, pickupDelay - dt);

        // Hráč může položit blok přímo na ležící položku. Dopad by ji vracel
        // na horní hranu buňky POD blokem, takže by v něm zůstala uvězněná -
        // proto se vytlačí o buňku výš, dokud nebude volná (blok za frame).
        if(collides(world, x, y, z))
        {
            y = (float) Math.floor(y) + 1 + EPSILON;
            vy = 0;
            return;
        }

        boolean inWater = world.isWater(
                (int) Math.floor(x), (int) Math.floor(y + HALF), (int) Math.floor(z));

        if(inWater)
        {
            vy -= GRAVITY * WATER_GRAVITY_SCALE * dt;

            float drag = (float) Math.pow(WATER_DRAG, dt);
            vx *= drag;
            vy *= drag;
            vz *= drag;
        }
        else
        {
            vy -= GRAVITY * dt;

            float air = (float) Math.pow(AIR_DRAG, dt);
            float horizontal = onGround ? (float) Math.pow(GROUND_DRAG, dt) : air;

            vx *= horizontal;
            vy *= air;
            vz *= horizontal;
        }

        move(world, vx * dt, vy * dt, vz * dt);
    }

    // ------------------------------------------------------------------
    // kolize - stejná pravidla jako Player, jen menší krabička
    // ------------------------------------------------------------------

    private void move(World world, float dx, float dy, float dz)
    {
        // Test kolize kontroluje jen CÍLOVOU polohu, takže se pohyb dělí
        // na kroky kratší, než je tloušťka nejtenčí stěny.
        float longest = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        int steps = Math.max(1, (int) Math.ceil(longest / MAX_SUBSTEP));

        float stepX = dx / steps;
        float stepY = dy / steps;
        float stepZ = dz / steps;

        onGround = false;

        for(int i = 0; i < steps; i++)
        {
            // Osy jedna po druhé - jinak nejde poznat, o kterou stěnu se zarazit.
            moveX(world, stepX);
            moveY(world, stepY);
            moveZ(world, stepZ);
        }
    }

    private void moveX(World world, float dx)
    {
        if(dx == 0) return;

        float target = x + dx;
        if(!collides(world, target, y, z))
        {
            x = target;
            return;
        }

        float previous = x;

        // Přisunout hranu krabičky těsně ke stěně bloku, do kterého vjela.
        x = dx > 0
                ? (float) Math.floor(target + HALF) - HALF - EPSILON
                : (float) Math.floor(target - HALF) + 1 + HALF + EPSILON;

        // Vklíněná mezi dvěma stěnami: radši zůstat, než skončit v bloku.
        if(collides(world, x, y, z))
        {
            x = previous;
        }

        vx = 0;
    }

    private void moveY(World world, float dy)
    {
        if(dy == 0) return;

        float target = y + dy;
        if(!collides(world, x, target, z))
        {
            y = target;
            return;
        }

        float previous = y;

        if(dy > 0)
        {
            y = (float) Math.floor(target + SIZE) - SIZE - EPSILON;
        }
        else
        {
            // Dopad na horní hranu bloku, do kterého by krabička vjela.
            y = (float) Math.floor(target) + 1 + EPSILON;
            onGround = true;
        }

        if(collides(world, x, y, z))
        {
            y = previous;
        }

        vy = 0;
    }

    private void moveZ(World world, float dz)
    {
        if(dz == 0) return;

        float target = z + dz;
        if(!collides(world, x, y, target))
        {
            z = target;
            return;
        }

        float previous = z;

        z = dz > 0
                ? (float) Math.floor(target + HALF) - HALF - EPSILON
                : (float) Math.floor(target - HALF) + 1 + HALF + EPSILON;

        if(collides(world, x, y, z))
        {
            z = previous;
        }

        vz = 0;
    }

    /**
     * Protíná krabička v dané poloze pevný blok? Buňky od floor(a) do
     * ceil(b)-1, ne floor(b) - jinak by se položka ležící přesně na hraně
     * zasekla o blok, kterého se jen dotýká. Viz Player.collides().
     */
    private static boolean collides(World world, float px, float py, float pz)
    {
        int minX = (int) Math.floor(px - HALF);
        int maxX = (int) Math.ceil(px + HALF) - 1;
        int minY = (int) Math.floor(py);
        int maxY = (int) Math.ceil(py + SIZE) - 1;
        int minZ = (int) Math.floor(pz - HALF);
        int maxZ = (int) Math.ceil(pz + HALF) - 1;

        for(int bx = minX; bx <= maxX; bx++)
        {
            for(int by = minY; by <= maxY; by++)
            {
                for(int bz = minZ; bz <= maxZ; bz++)
                {
                    if(world.isSolid(bx, by, bz))
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
