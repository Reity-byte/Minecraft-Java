package mc;

/**
 * Overuje sireni svetla a cyklus dne a noci.
 *
 * Teziste je na ODEBRANI svetla. Rozsvitit je snadne; kdyz ale zmizi pochoden
 * nebo se ucpe dira ve stropu, musi se projit cela osvetlena oblast, vynulovat,
 * a pritom si zapamatovat kazdy okraj, kde narazi na svetlo patrici odjinud -
 * a z tech okraju se to pak dosvitit zpatky. Bez druhe faze zustane po zhasnute
 * pochodni tmava dira i tam, kam dosvitit slunce.
 */
public class LightTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    /** Vysoko nad terenem, at do testu nemluvi nic dalsiho. */
    static final int FLOOR = 100;

    static World arena() {
        World w = new World();
        w.loadRadius = 1;
        w.unloadRadius = 3;
        w.updateBlocking(8f, 8f);
        return w;
    }

    /** Zastresena mistnost: podlaha, strop a steny. Uvnitr ma byt tma. */
    static void room(World w, int x0, int z0, int x1, int z1, int floor, int height) {
        for (int x = x0 - 1; x <= x1 + 1; x++)
            for (int z = z0 - 1; z <= z1 + 1; z++)
                for (int y = floor; y <= floor + height; y++) {
                    boolean shell = x < x0 || x > x1 || z < z0 || z > z1
                            || y == floor || y == floor + height;
                    if (shell) w.placeBlock(x, y, z, World.STONE);
                }
    }

    /**
     * Spocita slunecni svetlo v oblasti ZNOVU a od nuly a porovna s tim, co
     * ma svet. Nezavisly vypocet - stejny princip jako naivni prepocet sten
     * v MeshTest.
     *
     * Bunky u okraje oblasti se vynechavaji: tam do svetla legitimne mluvi
     * to, co je za hranici a co referencni vypocet nevidi.
     */
    static int compareWithReference(World w, int lo, int hi) {
        int size = hi - lo + 1;
        int h = World.WORLD_HEIGHT;
        byte[] ref = new byte[size * h * size];

        // svisly pruchod: shora dolu plne svetlo, dokud neprijde neprusvitny blok
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++) {
                for (int y = h - 1; y >= 0; y--) {
                    if (World.isOpaque(w.getBlock(lo + x, y, lo + z))) break;
                    ref[(x * h + y) * size + z] = 15;
                }
            }

        // sireni do sirky, opakovane dokud se neco meni
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int x = 0; x < size; x++)
                for (int y = 0; y < h; y++)
                    for (int z = 0; z < size; z++) {
                        if (World.isOpaque(w.getBlock(lo + x, y, lo + z))) continue;
                        int best = ref[(x * h + y) * size + z];
                        int[][] d = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
                        for (int[] o : d) {
                            int nx = x + o[0], ny = y + o[1], nz = z + o[2];
                            if (nx < 0 || nz < 0 || nx >= size || nz >= size || ny < 0 || ny >= h) continue;
                            int n = ref[(nx * h + ny) * size + nz];
                            // slunce pada dolu beze ztraty
                            int carried = (o[1] == 1 && n == 15) ? 15 : n - 1;
                            best = Math.max(best, carried);
                        }
                        if (best > ref[(x * h + y) * size + z]) {
                            ref[(x * h + y) * size + z] = (byte) best;
                            changed = true;
                        }
                    }
        }

        // Porovnat jen dobre uvnitr - u okraje mluvi do svetla i to, co je za nim.
        int margin = 16;
        int mismatches = 0;

        int tooBright = 0, tooDark = 0, sampleY = -1, sx = 0, sz = 0, sHave = 0, sWant = 0;

        for (int x = margin; x < size - margin; x++)
            for (int z = margin; z < size - margin; z++)
                for (int y = 1; y < h - 1; y++) {
                    int have = w.skyLightAt(lo + x, y, lo + z);
                    int want = ref[(x * h + y) * size + z];
                    if (have == want) continue;
                    mismatches++;
                    if (have > want) tooBright++; else tooDark++;
                    if (sampleY < 0) { sampleY = y; sx = lo + x; sz = lo + z; sHave = have; sWant = want; }
                }

        if (mismatches > 0)
            System.out.printf("    svetlejsi nez ma byt: %d, tmavsi: %d; priklad %d,%d,%d ma %d misto %d%n",
                    tooBright, tooDark, sx, sampleY, sz, sHave, sWant);

        return mismatches;
    }

    public static void main(String[] args) {
        // ---------- 1) povrch je osvetleny, hloubka ne ----------
        World w = arena();

        int surface = -1;
        for (int y = World.WORLD_HEIGHT - 1; y >= 0; y--)
            if (w.isSolid(8, y, 8)) { surface = y; break; }

        check("nasli jsme povrch", surface > 0, "y=" + surface);
        check("nad terenem je plne slunce", w.skyLightAt(8, surface + 1, 8) == LightEngine.MAX_LIGHT,
                "" + w.skyLightAt(8, surface + 1, 8));
        check("hluboko pod zemi je tma", w.skyLightAt(8, 5, 8) == 0,
                "" + w.skyLightAt(8, 5, 8));
        check("vysoko nad terenem je plne slunce",
                w.skyLightAt(8, World.WORLD_HEIGHT - 1, 8) == LightEngine.MAX_LIGHT, "");

        // ---------- 2) zastresena mistnost je tmava ----------
        World r = arena();
        room(r, 4, 4, 12, 12, FLOOR, 5);
        r.updateBlocking(8f, 8f);

        int inside = r.skyLightAt(8, FLOOR + 2, 8);
        check("uvnitr zastresene mistnosti je tma", inside == 0, "" + inside);

        // ---------- 3) pochoden osvetli mistnost ----------
        r.placeBlock(8, FLOOR + 1, 8, World.TORCH);
        r.updateBlocking(8f, 8f);

        check("pochoden sviti na svem miste",
                r.blockLightAt(8, FLOOR + 1, 8) == LightEngine.TORCH_LIGHT,
                "" + r.blockLightAt(8, FLOOR + 1, 8));
        check("vedle pochodne je o jedna min",
                r.blockLightAt(9, FLOOR + 1, 8) == LightEngine.TORCH_LIGHT - 1,
                "" + r.blockLightAt(9, FLOOR + 1, 8));
        check("dva bloky od pochodne je o dva min",
                r.blockLightAt(10, FLOOR + 1, 8) == LightEngine.TORCH_LIGHT - 2,
                "" + r.blockLightAt(10, FLOOR + 1, 8));
        check("do rohu mistnosti dosviti slabeji, ale dosviti",
                r.blockLightAt(5, FLOOR + 1, 5) > 0 && r.blockLightAt(5, FLOOR + 1, 5) < 10,
                "" + r.blockLightAt(5, FLOOR + 1, 5));

        // ---------- 4) ⚠️ zhasnuti pochodne ----------
        r.breakBlock(8, FLOOR + 1, 8);
        r.updateBlocking(8f, 8f);

        int leftovers = 0;
        for (int x = 4; x <= 12; x++)
            for (int z = 4; z <= 12; z++)
                for (int y = FLOOR + 1; y < FLOOR + 5; y++)
                    if (r.blockLightAt(x, y, z) != 0) leftovers++;

        check("po zhasnuti pochodne nezbyla ani jedna svitici bunka", leftovers == 0,
                leftovers + " bunek");

        // ---------- 5) ⚠️ dve pochodne, jedna zhasne ----------
        // Tady se pozna, jestli druha faze odebirani funguje: oblast kolem
        // zhasnute pochodne se musi dosvitit z te druhe, ne zustat cerna.
        World two = arena();
        room(two, 4, 4, 12, 12, FLOOR, 5);
        two.placeBlock(6, FLOOR + 1, 8, World.TORCH);
        two.placeBlock(10, FLOOR + 1, 8, World.TORCH);
        two.updateBlocking(8f, 8f);

        int between = two.blockLightAt(8, FLOOR + 1, 8);
        check("mezi dvema pochodnemi je svetlo", between > 0, "" + between);

        two.breakBlock(6, FLOOR + 1, 8);
        two.updateBlocking(8f, 8f);

        check("po zhasnuti jedne sviti druha dal",
                two.blockLightAt(10, FLOOR + 1, 8) == LightEngine.TORCH_LIGHT,
                "" + two.blockLightAt(10, FLOOR + 1, 8));
        check("misto po zhasnute pochodni dosvitila druha",
                two.blockLightAt(6, FLOOR + 1, 8) > 0,
                "" + two.blockLightAt(6, FLOOR + 1, 8));
        check("svetlo od druhe klesa se vzdalenosti spravne",
                two.blockLightAt(8, FLOOR + 1, 8) == LightEngine.TORCH_LIGHT - 2,
                "" + two.blockLightAt(8, FLOOR + 1, 8));

        // ---------- 6) ⚠️ dira do stropu pusti slunce dovnitr ----------
        World hole = arena();
        room(hole, 4, 4, 12, 12, FLOOR, 5);
        hole.updateBlocking(8f, 8f);
        check("pred vykopanim je uvnitr tma", hole.skyLightAt(8, FLOOR + 2, 8) == 0, "");

        // vykopat sloupec od stropu mistnosti az nad teren
        for (int y = FLOOR + 5; y < World.WORLD_HEIGHT; y++) hole.breakBlock(8, y, 8);
        hole.updateBlocking(8f, 8f);

        check("dirou ve stropu spadne slunce az dolu",
                hole.skyLightAt(8, FLOOR + 4, 8) == LightEngine.MAX_LIGHT,
                "" + hole.skyLightAt(8, FLOOR + 4, 8));
        check("vedle diry je svetlo slabsi, ale nenulove",
                hole.skyLightAt(9, FLOOR + 4, 9) > 0 && hole.skyLightAt(9, FLOOR + 4, 9) < 15,
                "" + hole.skyLightAt(9, FLOOR + 4, 9));

        // ---------- 7) ⚠️ ucpani diry vrati tmu ----------
        hole.placeBlock(8, FLOOR + 5, 8, World.STONE);
        hole.updateBlocking(8f, 8f);

        int stillLit = 0;
        for (int x = 5; x <= 11; x++)
            for (int z = 5; z <= 11; z++)
                for (int y = FLOOR + 1; y < FLOOR + 5; y++)
                    if (hole.skyLightAt(x, y, z) != 0) stillLit++;

        check("po ucpani diry je uvnitr zase uplna tma", stillLit == 0, stillLit + " bunek");

        // ---------- 8) sluneni svetlo padá dolu beze ztraty ----------
        World shaft = arena();
        // svisla sachta v terenu
        int top = -1;
        for (int y = World.WORLD_HEIGHT - 1; y >= 0; y--)
            if (shaft.isSolid(8, y, 8)) { top = y; break; }
        for (int y = top; y > top - 10; y--) shaft.breakBlock(8, y, 8);
        shaft.updateBlocking(8f, 8f);

        check("na dne 10 bloku hluboke sachty je porad plne slunce",
                shaft.skyLightAt(8, top - 9, 8) == LightEngine.MAX_LIGHT,
                "" + shaft.skyLightAt(8, top - 9, 8));

        // ---------- 8b) ⚠️ blok polozeny do jeste neexistujici sekce ----------
        // Sekce nad terenem se nealokuji. Prvni blok, ktery do takove sekce
        // padne, ji vytvori - a ChunkColumn.set() ji zakladal holym new Chunk()
        // s vychozim slunecnim svetlem 0. LightEngine opravi jen okoli bloku,
        // takze zbytek sekce 16x16x16 zcernal: postavit sloup nad terenem
        // znamenalo zatemnit kus oblohy.
        //
        // Ostatni testy to nechytily, protoze mistnost na FLOOR ma byt uvnitr
        // stejne tmava - spatna vychozi hodnota tam vypadala jako spravny vysledek.
        World pillar = arena();
        int sec = FLOOR >> Chunk.BITS;
        check("sekce na FLOOR pred polozenim neexistuje",
                pillar.column(0, 0).section(sec) == null
                        && pillar.skyLightAt(5, FLOOR, 12) == LightEngine.MAX_LIGHT, "");

        pillar.placeBlock(4, FLOOR, 12, World.STONE);
        pillar.updateBlocking(8f, 8f);

        check("nad blokem v nove sekci je porad plne slunce",
                pillar.skyLightAt(4, FLOOR + 1, 12) == LightEngine.MAX_LIGHT,
                "" + pillar.skyLightAt(4, FLOOR + 1, 12));
        check("vedle bloku v nove sekci je porad plne slunce",
                pillar.skyLightAt(5, FLOOR, 12) == LightEngine.MAX_LIGHT,
                "" + pillar.skyLightAt(5, FLOOR, 12));
        check("pod blokem je stin o jedna slabsi",
                pillar.skyLightAt(4, FLOOR - 1, 12) == LightEngine.MAX_LIGHT - 1,
                "" + pillar.skyLightAt(4, FLOOR - 1, 12));

        // Cela sekce: plne slunce vsude krome bloku a jeho stinu pod nim.
        int wrongInSection = 0;
        for (int x = 0; x < Chunk.SIZE; x++)
            for (int z = 0; z < Chunk.SIZE; z++)
                for (int y = sec << Chunk.BITS; y < (sec + 1) << Chunk.BITS; y++) {
                    boolean underBlock = x == 4 && z == 12 && y <= FLOOR;
                    int want = !underBlock ? 15 : y == FLOOR ? 0 : 14;
                    if (pillar.skyLightAt(x, y, z) != want) wrongInSection++;
                }
        check("zbytek nove sekce nezcernal", wrongInSection == 0, wrongInSection + " bunek");

        int pillarMismatches = compareWithReference(pillar, -Chunk.SIZE, 2 * Chunk.SIZE - 1);
        check("po polozeni do nove sekce svetlo sedi s nezavislym prepoctem",
                pillarMismatches == 0, pillarMismatches + " bunek");
        pillar.shutdown();

        // ---------- 9) ⚠️ nezavisly prepocet proti asynchronnimu nacitani ----------
        // Svet se nacita po sloupcich a fronta svetla bezi kazdy frame - uzel
        // z jeste nenacteneho sloupce se tak muze zpracovat driv, nez sloupec
        // dorazi. Protoze skyLightAt u nenacteneho sloupce vraci 15 (aby na
        // okraji dohledu nebyl cerny pruh), rozlilo se z nej 14 do okoli -
        // i do jeskyne dvacet bloku pod zemi.
        //
        // V blokujicim rezimu se to nikdy neprojevilo, protoze fronta se
        // zpracuje az po nacteni vseho. Tenhle test proto nacita ASYNCHRONNE
        // a vysledek porovnava s vlastnim, nezavislym vypoctem.
        World async = new World();
        async.loadRadius = 3;
        async.unloadRadius = 5;

        for (int i = 0; i < 20000; i++) {
            async.update(8f, 8f);
            if (async.pendingColumns() == 0 && async.pendingLight() == 0) break;
            try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // Porovnava se siroka oblast: chyba se projevovala na sloupcich, ktere
        // se nacetly pozdeji, ne na tom prostrednim.
        int mismatches = compareWithReference(async, -2 * Chunk.SIZE, 3 * Chunk.SIZE - 1);
        check("asynchronne nactene svetlo sedi s nezavislym prepoctem",
                mismatches == 0, mismatches + " bunek");

        // ---------- 10) ⚠️ svetlo po chuzi tam a zpatky ----------
        // Sloupce se pri pohybu ZAHAZUJI. Uzel ve fronte, jehoz sloupec se
        // mezitim zahodil, cetl pres skyLightAt hodnotu 15 (protoze nenacteny
        // sloupec ji vraci) a rozlil 14 do okoli - i do jeskyne dvacet bloku
        // pod zemi. Presne tohle bylo videt ve hre: jeskyne osvetlena sluncem
        // bez jakekoliv diry nad sebou.
        //
        // Stani na miste to neodhali, protoze se nic nezahazuje - proto se
        // tady odejde a vrati.
        World roam = new World();
        roam.loadRadius = 3;
        roam.unloadRadius = 4;

        for (int i = 0; i < 20000; i++) {
            roam.update(8f, 8f);
            if (roam.pendingColumns() == 0 && roam.pendingLight() == 0) break;
            try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        for (float x = 8; x < 300; x += 8) roam.update(x, 8f);      // pryc
        for (float x = 300; x >= 8; x -= 8) roam.update(x, 8f);     // a zpatky

        for (int i = 0; i < 20000; i++) {
            roam.update(8f, 8f);
            if (roam.pendingColumns() == 0 && roam.pendingLight() == 0) break;
            try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        int afterRoaming = compareWithReference(roam, -2 * Chunk.SIZE, 3 * Chunk.SIZE - 1);
        check("po chuzi tam a zpatky svetlo porad sedi s prepoctem",
                afterRoaming == 0, afterRoaming + " bunek");

        roam.shutdown();
        async.shutdown();

        // ---------- 10) cyklus dne a noci ----------
        DayCycle day = new DayCycle();

        float maxSun = 0, minSun = 1;
        boolean sawNight = false, sawDay = false;

        for (int i = 0; i < 2000; i++) {
            day.advance(DayCycle.DAY_LENGTH / 2000f);
            float sun = day.daylight();
            maxSun = Math.max(maxSun, sun);
            minSun = Math.min(minSun, sun);
            if (day.isNight()) sawNight = true; else sawDay = true;
        }

        System.out.printf("%nSlunce behem cyklu: %.2f az %.2f%n", minSun, maxSun);
        check("v poledne sviti naplno", maxSun > 0.99f, String.format("%.2f", maxSun));
        check("v noci sviti slabe, ale ne nula", minSun > 0f && minSun < 0.3f,
                String.format("%.2f", minSun));
        check("cyklus ma den i noc", sawDay && sawNight, "");

        float[] noon = day.skyColor().clone();
        day.skip(0.5f);
        float[] midnight = day.skyColor().clone();
        check("obloha v noci ztmavne", midnight[2] < noon[2],
                String.format("%.2f -> %.2f", noon[2], midnight[2]));

        // ---------- 11) obloha ----------
        DayCycle sky = new DayCycle();

        // Slunce ma byt v nadhlavniku prave tehdy, kdyz sviti naplno.
        // Nejsvetlejsi okamzik: slunce ma byt blizko nadhlavniku (uhel ~0).
        float bestSun = -1, angleAtNoon = 0;

        for (int i = 0; i < 2000; i++) {
            sky.advance(DayCycle.DAY_LENGTH / 2000f);
            if (sky.daylight() > bestSun) { bestSun = sky.daylight(); angleAtNoon = sky.skyAngle(); }
        }

        // Uhel se pocita dokola, takze se porovnava jeho zbytek po 2pi.
        double noonWrapped = Math.abs(((angleAtNoon % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI));
        if (noonWrapped > Math.PI) noonWrapped = 2 * Math.PI - noonWrapped;

        check("v poledne je slunce blizko nadhlavniku", noonWrapped < 0.9,
                String.format("%.2f rad od zenitu", noonWrapped));

        // Pulnoc se neda hledat jako "nejtmavsi okamzik" - noc je plosina se
        // stejnou hodnotou, takze by vysel jeji ZACATEK. Otoceni oblohy se
        // proto overi primo: pul cyklu = pul otacky, tedy mesic tam, kde bylo
        // slunce.
        DayCycle turning = new DayCycle();
        float angleBefore = turning.skyAngle();
        turning.skip(0.5f);
        double turned = Math.abs(turning.skyAngle() - angleBefore);

        check("pul cyklu otoci oblohu o pul otacky",
                Math.abs(turned - Math.PI) < 0.01, String.format("%.3f rad", turned));

        // Hvezdy: ve dne neviditelne, v noci vyrazne.
        DayCycle starClock = new DayCycle();
        float minStars = 1, maxStars = 0;
        for (int i = 0; i < 2000; i++) {
            starClock.advance(DayCycle.DAY_LENGTH / 2000f);
            minStars = Math.min(minStars, starClock.nightFactor());
            maxStars = Math.max(maxStars, starClock.nightFactor());
        }
        System.out.printf("Viditelnost hvezd behem cyklu: %.2f az %.2f%n", minStars, maxStars);
        check("ve dne hvezdy nejsou videt", minStars < 0.02f, String.format("%.2f", minStars));
        check("v noci jsou hvezdy vyrazne", maxStars > 0.95f, String.format("%.2f", maxStars));

        // Cas se musi otacet dokola, ne utikat do nekonecna.
        DayCycle wrap = new DayCycle();
        float before = wrap.hours();
        wrap.advance(DayCycle.DAY_LENGTH);
        check("po jednom cyklu je stejny cas", Math.abs(wrap.hours() - before) < 0.1f,
                String.format("%.2f -> %.2f", before, wrap.hours()));

        w.shutdown();
        r.shutdown();
        two.shutdown();
        hole.shutdown();
        shaft.shutdown();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
