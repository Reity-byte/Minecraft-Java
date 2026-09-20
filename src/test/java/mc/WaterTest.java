package mc;

/**
 * Overuje vodu: zaplaveni terenu, ze se nedostane do jeskyn, pravidla
 * viditelnosti sten a plavani.
 *
 * Kresleni vody (druhy pruchod, razeni podle hloubky, vypnuty zapis do depth
 * bufferu) sahá na GL a overi ho az spusteni hry. Testovatelne je vsechno
 * ostatni - a to je vetsina toho, co se muze pokazit.
 */
public class WaterTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    static final int RADIUS = 3;
    static final float DT = 1f / 60f;

    public static void main(String[] args) {
        World w = new World();
        TerrainGenerator gen = w.generator();
        w.loadRadius = RADIUS;
        w.unloadRadius = RADIUS + 2;
        w.updateBlocking(8f, 8f);

        int lo = -RADIUS * Chunk.SIZE, hi = (RADIUS + 1) * Chunk.SIZE - 1;

        // ---------- 1) hladina ----------
        long water = 0, wateredColumns = 0, columns = 0;
        int highestWater = -1, lowestWater = World.WORLD_HEIGHT;
        int waterInTerrain = 0;

        for (int x = lo; x <= hi; x++) {
            for (int z = lo; z <= hi; z++) {
                columns++;
                boolean any = false;
                int height = gen.terrainHeight(x, z);

                for (int y = 0; y < World.WORLD_HEIGHT; y++) {
                    if (w.getBlock(x, y, z) != World.WATER) continue;

                    water++;
                    any = true;
                    highestWater = Math.max(highestWater, y);
                    lowestWater = Math.min(lowestWater, y);

                    // Voda se leje az NAD teren - uvnitr nej nema co delat.
                    if (y < height) waterInTerrain++;
                }

                if (any) wateredColumns++;
            }
        }

        System.out.printf("%nVoda: %d bloku, %d z %d sloupcu ma vodu (%.1f %%), vyska %d-%d%n",
                water, wateredColumns, columns, 100.0 * wateredColumns / columns,
                lowestWater, highestWater);

        check("voda vubec vznika", water > 0, "" + water);
        check("jezera nejsou vsude", wateredColumns < columns, wateredColumns + " z " + columns);
        check("jezera nejsou zadna", wateredColumns > 0, "");
        check("zadna voda nad hladinou", highestWater < World.SEA_LEVEL,
                highestWater + " >= " + World.SEA_LEVEL);
        check("voda neni zapustena v terenu", waterInTerrain == 0, waterInTerrain + " bloku");

        // ---------- 2) jeskyne zustavaji suche ----------
        // Kope se jen v kameni a voda se leje jen nad teren, takze mezi dnem
        // jezera a nejvyssim moznym stropem jeskyne je vzdycky vrstva pudy.
        int waterUnderground = 0;
        for (int x = lo; x <= hi; x++)
            for (int z = lo; z <= hi; z++) {
                int height = gen.terrainHeight(x, z);
                for (int y = 0; y < height; y++)
                    if (w.getBlock(x, y, z) == World.WATER) waterUnderground++;
            }
        check("v podzemi neni ani kapka", waterUnderground == 0, waterUnderground + " bloku");

        // ---------- 3) voda neni pevna ani neprusvitna ----------
        check("voda neni neprusvitna", !World.isOpaque(World.WATER), "");
        check("vzduch neni neprusvitny", !World.isOpaque(World.AIR), "");
        check("kamen je neprusvitny", World.isOpaque(World.STONE), "");

        int wx = -1, wy = -1, wz = -1;
        outer:
        for (int x = lo; x <= hi; x++)
            for (int z = lo; z <= hi; z++)
                for (int y = 0; y < World.SEA_LEVEL; y++)
                    if (w.getBlock(x, y, z) == World.WATER) { wx = x; wy = y; wz = z; break outer; }

        check("nasli jsme vodu k testovani", wx != -1, wx + "," + wy + "," + wz);
        check("voda neni pevna (hrac ji propada)", !w.isSolid(wx, wy, wz), "");
        check("isWater ji pozna", w.isWater(wx, wy, wz), "");

        // ---------- 4) pravidla viditelnosti sten ----------
        // Kamenna kostka obklopena vodou, vysoko nad terenem, at do toho nemluvi
        // nic dalsiho. Rucne spocitano: kamen ma 6 sten (voda ho nezakryva),
        // kazdy ze 6 vodnich bloku ma 5 sten ke vzduchu a zadnou ke kameni.
        final int TEST_SECTION = 6;
        final int baseY = TEST_SECTION << Chunk.BITS;
        final int cx = 8, cy = baseY + 4, cz = 8;

        w.placeBlock(cx, cy, cz, World.STONE);
        w.placeBlock(cx + 1, cy, cz, World.WATER);
        w.placeBlock(cx - 1, cy, cz, World.WATER);
        w.placeBlock(cx, cy + 1, cz, World.WATER);
        w.placeBlock(cx, cy - 1, cz, World.WATER);
        w.placeBlock(cx, cy, cz + 1, World.WATER);
        w.placeBlock(cx, cy, cz - 1, World.WATER);

        ChunkMesh mesh = new ChunkMesh();
        mesh.build(w, w.column(0, 0).section(TEST_SECTION), 0, baseY, 0);

        check("kamen ve vode + 6 vodnich bloku = 36 sten", mesh.faceCount() == 36,
                "" + mesh.faceCount());
        check("sekce s vodou ma pruhlednou cast", mesh.hasTransparent(), "");

        // Dve vody vedle sebe mezi sebou zadnou stenu nedelaji: pridanim
        // sedmeho vodniho bloku k jednomu ze sesti ubude jedna stena (ta,
        // kterou puvodni blok mel ke vzduchu) a pribudou peti nove.
        int before = mesh.faceCount();
        w.placeBlock(cx + 2, cy, cz, World.WATER);
        mesh.build(w, w.column(0, 0).section(TEST_SECTION), 0, baseY, 0);
        check("voda vedle vody nedela stenu mezi sebou",
                mesh.faceCount() == before + 4, before + " -> " + mesh.faceCount());

        // ---------- 5) pokladani do vody ----------
        check("do vody se da polozit blok", w.placeBlock(cx + 1, cy, cz, World.PLANKS), "");
        check("polozeny blok vodu vytlacil", w.getBlock(cx + 1, cy, cz) == World.PLANKS, "");
        check("do pevneho bloku se polozit neda", !w.placeBlock(cx, cy, cz, World.PLANKS), "");

        // ---------- 6) spawn je na sousi ----------
        int[] spawn = gen.findLandSpawn(8, 8, 64);
        check("spawn je nad hladinou",
                gen.terrainHeight(spawn[0], spawn[1]) >= World.SEA_LEVEL,
                "vyska " + gen.terrainHeight(spawn[0], spawn[1])
                        + " na " + spawn[0] + "," + spawn[1]);
        System.out.printf("Spawn posunut z 8,8 (vyska %d) na %d,%d (vyska %d)%n",
                gen.terrainHeight(8, 8), spawn[0], spawn[1],
                gen.terrainHeight(spawn[0], spawn[1]));

        // ---------- 7) plavani ----------
        // Nadrz vysoko nad terenem: podlaha a nad ni 8 vrstev vody.
        World tank = new World();
        tank.loadRadius = 1;
        tank.unloadRadius = 3;
        tank.updateBlocking(8f, 8f);

        final int FLOOR = 100;
        for (int x = 4; x <= 12; x++)
            for (int z = 4; z <= 12; z++) {
                tank.placeBlock(x, FLOOR, z, World.STONE);
                for (int y = FLOOR + 1; y <= FLOOR + 8; y++) tank.placeBlock(x, y, z, World.WATER);
            }

        Player swimmer = new Player();
        swimmer.x = 8.5f; swimmer.z = 8.5f; swimmer.y = FLOOR + 6;
        swimmer.vy = 0;

        swimmer.update(tank, DT, 0f);
        check("hrac ve vode to pozna", swimmer.inWater, "");

        // Klesani: bez drzeneho skoku se propada, ale pomalu.
        float startY = swimmer.y;
        for (int i = 0; i < 60; i++) swimmer.update(tank, DT, 0f);
        float sinkDistance = startY - swimmer.y;

        System.out.printf("Za 1 s ve vode klesne o %.2f bloku (na suchu by to bylo pres 10)%n",
                sinkDistance);
        check("ve vode se klesa, ne pada", sinkDistance > 0.2f && sinkDistance < 4f,
                String.format("%.2f bloku", sinkDistance));

        // Plavani vzhuru
        float beforeSwim = swimmer.y;
        swimmer.inputJump = true;
        for (int i = 0; i < 30; i++) swimmer.update(tank, DT, 0f);
        swimmer.inputJump = false;
        check("drzeny skok plave vzhuru", swimmer.y > beforeSwim,
                String.format("%.2f -> %.2f", beforeSwim, swimmer.y));

        // Vodorovne zpomaleni
        Player dry = new Player();
        dry.x = 8.5f; dry.z = 8.5f; dry.y = FLOOR + 20;   // ve vzduchu nad nadrzi
        dry.inputForward = 1;
        dry.update(tank, DT, 0f);
        float drySpeed = (float) Math.sqrt(dry.vx * dry.vx + dry.vz * dry.vz);

        swimmer.inputForward = 1;
        swimmer.update(tank, DT, 0f);
        float wetSpeed = (float) Math.sqrt(swimmer.vx * swimmer.vx + swimmer.vz * swimmer.vz);

        System.out.printf("Vodorovna rychlost: sucho %.2f, voda %.2f b/s%n", drySpeed, wetSpeed);
        check("ve vode je pohyb pomalejsi", wetSpeed < drySpeed * 0.75f,
                String.format("%.2f vs %.2f", wetSpeed, drySpeed));
        check("ve vode se pohyb uplne nezastavi", wetSpeed > 0.5f,
                String.format("%.2f", wetSpeed));

        // ---------- 8) na hladine se plave, ne hopsa ----------
        // Puvodni verze nastavovala svislou rychlost napevno, dokud se hitbox
        // dotykal vody. Vztlak hrace vystrcil celeho nad hladinu, tam prepnul
        // na plnou gravitaci, hrac spadl zpatky - a znovu, jako slime block.
        // Se vztlakem podle PODILU PONORENI existuje rovnovazna hloubka.
        Player floater = new Player();
        floater.x = 8.5f; floater.z = 8.5f;
        floater.y = FLOOR + 4;
        floater.inputJump = true;

        // nechat se ustalit
        for (int i = 0; i < 400; i++) floater.update(tank, DT, 0f);

        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minSub = 1f, maxSub = 0f;
        for (int i = 0; i < 240; i++) {
            floater.update(tank, DT, 0f);
            minY = Math.min(minY, floater.y);
            maxY = Math.max(maxY, floater.y);
            minSub = Math.min(minSub, floater.submerged);
            maxSub = Math.max(maxSub, floater.submerged);
        }
        float bob = maxY - minY;

        float waterTop = FLOOR + 9;   // voda konci na FLOOR+8, hladina je nad ni
        System.out.printf("Na hladine se drzeným skokem houpe o %.3f bloku, ponoreni %.0f-%.0f %%%n",
                bob, minSub * 100, maxSub * 100);

        check("na hladine se nehopsa", bob < 0.25f, String.format("rozkmit %.3f bloku", bob));
        check("hrac se ustali castecne ponoreny, ne nad vodou ani pod ni",
                maxSub < 0.95f && minSub > 0.2f,
                String.format("%.0f-%.0f %%", minSub * 100, maxSub * 100));
        check("hlava zustane nad hladinou", floater.y + Player.EYE_HEIGHT > waterTop - 1.05f,
                String.format("oci %.2f, hladina %.2f", floater.y + Player.EYE_HEIGHT, waterTop - 1f));

        // ---------- 9) setrvacnost: po klesani se pohyb hned neotoci ----------
        Player diver = new Player();
        diver.x = 8.5f; diver.z = 8.5f; diver.y = FLOOR + 5;
        for (int i = 0; i < 60; i++) diver.update(tank, DT, 0f);   // chvili klesa
        float sinking = diver.vy;
        check("pred otocenim klesa", sinking < -0.3f, String.format("%.2f", sinking));

        diver.inputJump = true;
        diver.update(tank, DT, 0f);
        check("jeden frame drzeneho skoku pohyb neotoci (setrvacnost)", diver.vy < 0f,
                String.format("vy %.2f -> %.2f", sinking, diver.vy));

        for (int i = 0; i < 40; i++) diver.update(tank, DT, 0f);
        check("po chvilce uz stoupa", diver.vy > 0f, String.format("%.2f", diver.vy));

        // Noclip vodu ignoruje - jinak by se v ni nedalo proletet.
        swimmer.noclip = true;
        swimmer.update(tank, DT, 0f);
        check("noclip vodu ignoruje", !swimmer.inWater, "");

        w.shutdown();
        tank.shutdown();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
