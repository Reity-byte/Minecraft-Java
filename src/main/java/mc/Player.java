package mc;

/**
 * Hráč jako entita s rozměry, rychlostí a gravitací.
 *
 * Do teď byla "hráčem" přímo kamera: bod bez rozměru, který se testoval proti
 * jediné buňce. Kvůli tomu se dalo projít rohem a stát napůl v bloku. Teď má
 * hráč pořádný AABB hitbox a kolize se řeší po osách.
 *
 * POZOR na konvenci: y je SPODEK hitboxu (nohy), ne oči. Kamera sedí
 * o EYE_HEIGHT výš - viz Main.
 */
public class Player {

    // Rozměry zhruba podle Minecraftu. Šířka pod 1 blok je důležitá:
    // hráč se pak vejde do jednoblokové mezery.
    public static final float WIDTH      = 0.6f;
    public static final float HEIGHT     = 1.8f;
    public static final float EYE_HEIGHT = 1.62f;

    private static final float HALF_WIDTH = WIDTH / 2f;

    private static final float WALK_SPEED        = 4.3f;
    private static final float SPRINT_MULTIPLIER = 1.3f;
    private static final float SNEAK_MULTIPLIER  = 0.3f;
    private static final float FLY_SPEED         = 12f;
    private static final float FLY_SPRINT        = 3f;

    private static final float GRAVITY  = 28f;
    /**
     * v² / 2g = 8.4² / 56 = 1.26 bloku. Schválně o kus přes 1 - hráč musí
     * vyskočit na jednoblokový schod, ale ne na dvoublokový.
     */
    private static final float JUMP_VELOCITY     = 8.4f;
    private static final float TERMINAL_VELOCITY = 50f;

    // ------------------------------------------------------------------
    // voda
    //
    // ⚠️ Všechno se škáluje PODÍLEM PONOŘENÍ hitboxu, ne binárním "je ve vodě".
    //
    // Binární verze hopsala jako trampolína: hitbox se počítal jako ve vodě,
    // dokud se jí dotýkal aspoň špičkou, takže vztlak hráče vystrčil celého
    // nad hladinu, tam přepnul na plnou gravitaci, hráč spadl zpátky - a znovu.
    //
    // S podílem se to ustálí samo: čím výš hráč vyplave, tím menší je vztlak
    // a tím větší gravitace, takže existuje rovnovážná hloubka a kolem ní
    // se to jen utlumeně houpe. Zároveň z toho vypadne setrvačnost, kterou
    // má voda v Minecraftu - po klesání chvíli trvá, než se pohyb otočí.
    // ------------------------------------------------------------------

    /** Jakou částí se uplatní gravitace při úplném ponoření. */
    private static final float WATER_GRAVITY_SCALE = 0.30f;

    /**
     * Zrychlení vzhůru při plavání, při úplném ponoření. Musí být větší než
     * gravitace, jinak se s drženým skokem nedá vyplavat.
     */
    private static final float WATER_SWIM_ACCEL = 28f;

    /**
     * Útlum svislé rychlosti, podíl zbylý za sekundu při úplném ponoření.
     * Právě tenhle člen dělá pohyb ve vodě "hustý" a drží terminální rychlosti
     * nízko: klesání se ustálí kolem 1,9 b/s, plavání vzhůru kolem 4,4 b/s.
     *
     * ⚠️ Nedávat ho moc silný. První verze měla 0,004 a vyplavání z hloubky
     * trvalo tak dlouho, že to působilo, jako by se vyplavat nedalo vůbec.
     */
    private static final float WATER_VERTICAL_DRAG = 0.012f;

    /** Násobič vodorovné rychlosti při úplném ponoření. */
    private static final float WATER_DRAG = 0.5f;

    /**
     * Strop na délku jednoho kroku fyziky. Bez něj by jedno zaseknutí
     * (GC, alt-tab, breakpoint) poslalo dt na půl vteřiny a hráč by se
     * v jediném kroku přesunul o metry - rovnou skrz zeď.
     */
    private static final float MAX_TIME_STEP = 0.05f;

    /**
     * Nejdelší posun, který se testuje najednou. Test kolize kontroluje jen
     * CÍLOVOU polohu, ne cestu k ní - kdyby se posunulo o víc než tloušťku
     * stěny, prolétlo by se skrz. Proto se pohyb dělí na dílčí kroky.
     */
    private static final float MAX_SUBSTEP = 0.4f;

    /** Odsazení při dosednutí na stěnu, ať se hitbox nedotýká přesně na hraně. */
    private static final float EPSILON = 1e-3f;

    public float x, y, z;      // y = spodek hitboxu
    public float vx, vy, vz;

    public boolean onGround = false;

    /**
     * Jaká část výšky hitboxu je pod hladinou, 0 až 1.
     * Přepočítává se na začátku každého update().
     */
    public float submerged = 0f;

    /** Dotýká se hráč vody vůbec? Jen zkratka pro submerged > 0. */
    public boolean inWater = false;
    public boolean flying = false;
    public boolean noclip = false;

    // Vstup nastavuje Main před voláním update().
    public float inputForward;   // -1 dozadu .. +1 dopředu
    public float inputStrafe;    // -1 doleva .. +1 doprava
    public boolean inputJump;
    public boolean inputDescend;
    public boolean inputSprint;
    public boolean inputSneak;

    // ------------------------------------------------------------------

    /** Postaví hráče na povrch terénu v daném sloupci. */
    public void spawn(World world, float spawnX, float spawnZ)
    {
        x = spawnX;
        z = spawnZ;
        y = World.WORLD_HEIGHT;

        int bx = (int) Math.floor(spawnX);
        int bz = (int) Math.floor(spawnZ);

        for(int scanY = World.WORLD_HEIGHT - 1; scanY >= 0; scanY--)
        {
            if(world.isSolid(bx, scanY, bz))
            {
                y = scanY + 1;
                break;
            }
        }

        vx = vy = vz = 0;
        onGround = true;
    }

    public float eyeY()
    {
        return y + EYE_HEIGHT;
    }

    // ------------------------------------------------------------------

    public void update(World world, float dt, float yawDegrees)
    {
        dt = Math.min(dt, MAX_TIME_STEP);

        // Musí se zjistit PŘED pohybem: zpomalení i vztlak se počítají z toho,
        // kde hráč je teď, ne kam se chystá.
        submerged = noclip ? 0f : submergedFraction(world);
        inWater = submerged > 0f;

        applyMovementInput(yawDegrees);

        if(flying)
        {
            // Ve volném letu se svislá rychlost bere přímo ze vstupu,
            // gravitace se neuplatňuje.
            float speed = FLY_SPEED * (inputSprint ? FLY_SPRINT : 1f);
            vy = 0;
            if(inputJump)    vy += speed;
            if(inputDescend) vy -= speed;
        }
        else if(inputJump && onGround)
        {
            // Odrazit se ode dna jde i ve vodě - jinak by se z mělčiny
            // u břehu nedalo vyskočit na souš.
            vy = JUMP_VELOCITY;
            onGround = false;
        }
        else if(inWater)
        {
            // Zrychlení, ne nastavení rychlosti. Nastavovat ji je právě to,
            // co dělalo z plavání skákání po hladině.
            vy -= GRAVITY * (1f - submerged * (1f - WATER_GRAVITY_SCALE)) * dt;

            if(inputJump)
            {
                vy += WATER_SWIM_ACCEL * submerged * dt;
            }

            // Útlum je mocnina, ne násobek: musí vyjít stejně bez ohledu na to,
            // jak dlouhý byl frame, jinak by voda byla hustší při nízkém FPS.
            vy *= (float) Math.pow(lerp(1f, WATER_VERTICAL_DRAG, submerged), dt);
        }
        else
        {
            vy -= GRAVITY * dt;

            if(vy < -TERMINAL_VELOCITY)
            {
                vy = -TERMINAL_VELOCITY;
            }
        }

        moveWithCollision(world, vx * dt, vy * dt, vz * dt);
    }

    private void applyMovementInput(float yawDegrees)
    {
        float speed;
        if(flying)
        {
            speed = FLY_SPEED * (inputSprint ? FLY_SPRINT : 1f);
        }
        else if(inputSneak)
        {
            speed = WALK_SPEED * SNEAK_MULTIPLIER;
        }
        else if(inputSprint)
        {
            speed = WALK_SPEED * SPRINT_MULTIPLIER;
        }
        else
        {
            speed = WALK_SPEED;
        }

        // Stejná konvence jako Camera.forwardVector, jen bez pitch -
        // chodí se po rovině, i když se kouká nahoru.
        float yawRad = (float) Math.toRadians(yawDegrees);
        float forwardX = (float) Math.cos(yawRad);
        float forwardZ = (float) Math.sin(yawRad);
        float rightX = -forwardZ;
        float rightZ = forwardX;

        float dx = forwardX * inputForward + rightX * inputStrafe;
        float dz = forwardZ * inputForward + rightZ * inputStrafe;

        // Normalizace, aby chůze šikmo (W+D) nebyla 1,41x rychlejší než rovně.
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if(length > 1e-4f)
        {
            dx /= length;
            dz /= length;
        }

        if(inWater && !flying)
        {
            speed *= lerp(1f, WATER_DRAG, submerged);
        }

        vx = dx * speed;
        vz = dz * speed;
    }

    // ------------------------------------------------------------------
    // kolize
    // ------------------------------------------------------------------

    private void moveWithCollision(World world, float dx, float dy, float dz)
    {
        if(noclip)
        {
            x += dx;
            y += dy;
            z += dz;
            onGround = false;
            return;
        }

        float longest = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        int steps = Math.max(1, (int) Math.ceil(longest / MAX_SUBSTEP));

        float stepX = dx / steps;
        float stepY = dy / steps;
        float stepZ = dz / steps;

        onGround = false;

        for(int i = 0; i < steps; i++)
        {
            // Osy se řeší JEDNA PO DRUHÉ, a pokaždé se testuje celý hitbox.
            // Kdyby se posunuly všechny naráz a pak se testovalo, nešlo by
            // poznat, o kterou stěnu se zarazit - a hráč by se zasekával
            // v rozích místo aby po nich klouzal.
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

        if(dx > 0)
        {
            // Pravá hrana hitboxu je uvnitř nějakého bloku - přisuneme ji
            // těsně k jeho levé stěně.
            x = (float) Math.floor(target + HALF_WIDTH) - HALF_WIDTH - EPSILON;
        }
        else
        {
            x = (float) Math.floor(target - HALF_WIDTH) + 1 + HALF_WIDTH + EPSILON;
        }

        // Pojistka: kdyby přisunutí vyšlo do jiného bloku (hráč vklíněný
        // mezi dvěma stěnami), radši se nehneme vůbec, než abychom skončili
        // uvnitř geometrie.
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
            // Hlava narazila do stropu.
            y = (float) Math.floor(target + HEIGHT) - HEIGHT - EPSILON;
        }
        else
        {
            // Nohy dosedly na blok - postavíme je na jeho horní hranu.
            // floor(target) + 1 (a ne ceil) je správně i když target vyjde
            // přesně na celé číslo: nohy jsou pak uvnitř toho bloku.
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

        if(dz > 0)
        {
            z = (float) Math.floor(target + HALF_WIDTH) - HALF_WIDTH - EPSILON;
        }
        else
        {
            z = (float) Math.floor(target - HALF_WIDTH) + 1 + HALF_WIDTH + EPSILON;
        }

        if(collides(world, x, y, z))
        {
            z = previous;
        }

        vz = 0;
    }

    private static float lerp(float a, float b, float t)
    {
        return a + (b - a) * t;
    }

    /**
     * Jaká část výšky hitboxu je pod hladinou, 0 až 1.
     *
     * Měří se ve svislém sloupci pod středem hráče - ten je široký 0,6 bloku,
     * takže skoro vždycky leží v jedné buňce a rozšiřovat test do stran by
     * jen stálo čas. Sčítá se skutečný PŘEKRYV výšky, ne počet buněk: hráč
     * stojící na hladině má být z poloviny ponořený, ne buď celý, nebo vůbec.
     */
    private float submergedFraction(World world)
    {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);

        float top = y + HEIGHT;
        int minY = (int) Math.floor(y);
        int maxY = (int) Math.ceil(top) - 1;

        float submergedHeight = 0f;

        for(int by = minY; by <= maxY; by++)
        {
            if(!world.isWater(bx, by, bz))
            {
                continue;
            }

            float overlap = Math.min(top, by + 1) - Math.max(y, by);

            if(overlap > 0f)
            {
                submergedHeight += overlap;
            }
        }

        return Math.min(1f, submergedHeight / HEIGHT);
    }

    /**
     * Protíná hitbox v dané poloze nějaký pevný blok?
     *
     * Buňka <i, i+1) se překrývá s intervalem <a, b) právě pro i od floor(a)
     * do ceil(b)-1. Použít místo toho floor(b) je klasická chyba: když hráč
     * stojí přesně na hraně bloku, testovala by se i buňka, které se jen
     * dotýká, a hráč by se zasekl sám o sebe.
     */
    private boolean collides(World world, float px, float py, float pz)
    {
        int minX = (int) Math.floor(px - HALF_WIDTH);
        int maxX = (int) Math.ceil(px + HALF_WIDTH) - 1;
        int minY = (int) Math.floor(py);
        int maxY = (int) Math.ceil(py + HEIGHT) - 1;
        int minZ = (int) Math.floor(pz - HALF_WIDTH);
        int maxZ = (int) Math.ceil(pz + HALF_WIDTH) - 1;

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

    /**
     * Překrývá se buňka bloku s hitboxem hráče?
     * Používá se při pokládání - do sebe si blok položit nemůžeš.
     */
    public boolean intersectsBlock(int bx, int by, int bz)
    {
        return bx + 1 > x - HALF_WIDTH && bx < x + HALF_WIDTH
                && by + 1 > y          && by < y + HEIGHT
                && bz + 1 > z - HALF_WIDTH && bz < z + HALF_WIDTH;
    }
}
