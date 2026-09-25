package mc;

/**
 * Overuje postup rozbijeni bloku.
 *
 * ⚠️ Tezisko je na tom, ze se postup vaze na KONKRETNI BLOK, ne na stisknute
 * tlacitko. Kdyby se pocital jen z drzeni mysi, dalo by se kopani "nabit"
 * na mekke hline a jednim skubnutim mysi rozbit kamen.
 */
public class MiningTest {

    static int failures = 0;
    static final float DT = 1f / 60f;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    /** Paprsek na konkretni blok, jako by na nej hrac koukal. */
    static Raycaster.RaycastHit at(int x, int y, int z) {
        return new Raycaster.RaycastHit(x, y, z, 0, 1, 0);
    }

    /** Kolik framu trva rozbit blok na dane pozici. -1 kdyz se to nepovede. */
    static int framesToBreak(World w, Mining m, int x, int y, int z, int limit) {
        for (int i = 1; i <= limit; i++)
            if (m.update(w, DT, true, at(x, y, z))) return i;
        return -1;
    }

    public static void main(String[] args) {
        // ---------- tvrdosti ----------
        check("hlina je mekci nez kamen",
                World.hardness(World.DIRT) < World.hardness(World.STONE),
                World.hardness(World.DIRT) + " vs " + World.hardness(World.STONE));
        check("kamen je mekci nez zelezna ruda",
                World.hardness(World.STONE) < World.hardness(World.IRON_ORE), "");
        check("pochoden se rozbije skoro hned", World.hardness(World.TORCH) < 0.1f, "");
        check("zadna tvrdost neni nulova ani zaporna",
                World.hardness(World.STONE) > 0 && World.hardness(World.LEAVES) > 0, "");

        // ---------- arena ----------
        World w = new World();
        w.loadRadius = 1;
        w.unloadRadius = 3;
        w.updateBlocking(8f, 8f);

        final int FLOOR = 100;
        w.placeBlock(8, FLOOR, 8, World.DIRT);
        w.placeBlock(9, FLOOR, 8, World.STONE);
        w.placeBlock(10, FLOOR, 8, World.IRON_ORE);

        Mining m = new Mining();

        // ---------- doba kopani odpovida tvrdosti ----------
        int dirtFrames = framesToBreak(w, m, 8, FLOOR, 8, 2000);
        m.cancel();
        int stoneFrames = framesToBreak(w, m, 9, FLOOR, 8, 2000);
        m.cancel();
        int oreFrames = framesToBreak(w, m, 10, FLOOR, 8, 2000);
        m.cancel();

        System.out.printf("%nRozbiti: hlina %d framu (%.2f s), kamen %d (%.2f s), zelezo %d (%.2f s)%n",
                dirtFrames, dirtFrames * DT, stoneFrames, stoneFrames * DT,
                oreFrames, oreFrames * DT);

        check("hlina se rozbije", dirtFrames > 0, "" + dirtFrames);
        check("kamen trva dele nez hlina", stoneFrames > dirtFrames, "");
        check("zelezo trva dele nez kamen", oreFrames > stoneFrames, "");
        check("hlina zabere zhruba pul vteriny",
                Math.abs(dirtFrames * DT - World.hardness(World.DIRT)) < 0.05f,
                String.format("%.2f s", dirtFrames * DT));

        // ---------- ⚠️ prepnuti cile zacina od nuly ----------
        m.cancel();
        for (int i = 0; i < 25; i++) m.update(w, DT, true, at(8, FLOOR, 8));   // nakousnout hlinu
        float charged = m.progress();
        check("na hline se postup nacital", charged > 0.5f, String.format("%.2f", charged));

        m.update(w, DT, true, at(9, FLOOR, 8));   // skok na kamen
        check("prepnuti na jiny blok postup vynuluje", m.progress() < 0.05f,
                String.format("%.2f", m.progress()));
        check("kamen se tim nerozbil", w.isSolid(9, FLOOR, 8), "");

        // ---------- pusteni tlacitka zrusi postup ----------
        m.cancel();
        for (int i = 0; i < 25; i++) m.update(w, DT, true, at(9, FLOOR, 8));
        check("kamen se nakousl", m.progress() > 0.1f, String.format("%.2f", m.progress()));

        m.update(w, DT, false, at(9, FLOOR, 8));   // pustit
        check("pusteni tlacitka zrusi kopani", !m.isActive() && m.progress() == 0f, "");

        // ---------- kurzor mimo blok ----------
        m.cancel();
        for (int i = 0; i < 10; i++) m.update(w, DT, true, at(9, FLOOR, 8));
        m.update(w, DT, true, null);
        check("kurzor mimo blok kopani zrusi", !m.isActive(), "");

        // ---------- stadia prasklin ----------
        m.cancel();
        int previousStage = -1;
        boolean monotone = true;
        int distinct = 0;

        for (int i = 0; i < 2000; i++) {
            boolean broke = m.update(w, DT, true, at(10, FLOOR, 8));
            int stage = m.stage();

            if (broke) break;
            if (stage < previousStage) monotone = false;
            if (stage != previousStage) { distinct++; previousStage = stage; }
        }

        check("stadia prasklin jdou po sobe a nevraci se", monotone, "");
        check("projde se vsemi stadii", distinct >= Mining.STAGES,
                distinct + " z " + Mining.STAGES);
        check("stadium nikdy nepresahne posledni dlazdici",
                previousStage < Mining.STAGES, "" + previousStage);

        // ---------- necinne kopani nehlasi stadium ----------
        m.cancel();
        check("bez kopani se praskliny nekresli", m.stage() == -1, "" + m.stage());

        // ---------- blok, ktery se zamerit neda ----------
        m.cancel();
        w.placeBlock(11, FLOOR, 8, World.WATER);
        boolean brokeWater = false;
        for (int i = 0; i < 200; i++)
            if (m.update(w, DT, true, at(11, FLOOR, 8))) brokeWater = true;
        check("voda se rozbit neda", !brokeWater && !m.isActive(), "");

        // ---------- kam jde vytezeny blok: NA ZEM, pak sebrat ----------
        // Jako v Minecraftu: blok vypadne a do inventare ho dostane az sebrani
        // (se zvukem PICKUP). Driv sel rovnou do inventare.
        DroppedItems drops = new DroppedItems();

        Inventory roomy = new Inventory();
        w.placeBlock(12, FLOOR, 8, World.DIRT);
        m.cancel();
        framesToBreak(w, m, 12, FLOOR, 8, 2000);
        SoundTest.Recorder heard = new SoundTest.Recorder();
        boolean harvested = m.harvest(w, roomy, drops, heard);
        check("vytezeny blok NEjde rovnou do inventare", harvested && roomy.countOf(World.DIRT) == 0, "");
        check("rozbiti zazni zvukem materialu, v prostoru ze stredu bloku",
                heard.played.size() == 1 && heard.last().sound() == Sound.BREAK_EARTH
                        && heard.last().positional()
                        && heard.last().x() == 12.5f && heard.last().y() == FLOOR + 0.5f && heard.last().z() == 8.5f,
                heard.played.toString());
        check("blok je pryc", !w.isSolid(12, FLOOR, 8), "");

        DroppedItem dropped = drops.size() == 1 ? drops.items().get(0) : null;
        check("vytezeny blok vypadne na zem (i s mistem v inventari)",
                dropped != null && dropped.stack().block() == World.DIRT && dropped.stack().count() == 1,
                dropped == null ? drops.size() + " polozek" : dropped.stack().toString());
        check("polozka se objevi na miste rozbiteho bloku",
                dropped != null && Math.abs(dropped.x - 12.5f) < 1e-4f && Math.abs(dropped.z - 8.5f) < 1e-4f
                        && dropped.y >= FLOOR && dropped.y < FLOOR + 1,
                dropped == null ? "" : dropped.x + " " + dropped.y + " " + dropped.z);

        // Hrac stoji vedle: po zpozdeni sberu se polozka sebere a zazni PICKUP.
        Player near = new Player();
        near.x = 13.2f; near.y = FLOOR; near.z = 8.5f;
        heard.played.clear();
        for (int i = 0; i < 10; i++) drops.update(w, near, roomy, DT, heard);
        check("pred zpozdenim sberu (0,5 s) se nesebere", roomy.countOf(World.DIRT) == 0 && heard.played.isEmpty(), "");
        for (int i = 0; i < 60; i++) drops.update(w, near, roomy, DT, heard);
        check("sebrani da blok do inventare", roomy.countOf(World.DIRT) == 1 && drops.size() == 0,
                roomy.countOf(World.DIRT) + ", na zemi " + drops.size());
        check("a zazni PICKUP, prave jednou", heard.played.size() == 1 && heard.last().sound() == Sound.PICKUP
                && !heard.last().positional(), heard.played.toString());

        // Plny inventar: polozka zustane lezet a nic nezazni.
        Inventory full = InventoryTest.filled(World.STONE);
        w.placeBlock(13, FLOOR, 8, World.DIRT);
        m.cancel();
        framesToBreak(w, m, 13, FLOOR, 8, 2000);
        m.harvest(w, full, drops, SoundSink.SILENT);
        heard.played.clear();
        for (int i = 0; i < 70; i++) drops.update(w, near, full, DT, heard);
        check("pri plnem inventari zustane na zemi a nic nezazni",
                drops.size() == 1 && heard.played.isEmpty() && full.countOf(World.DIRT) == 0, "" + drops.size());

        // Kamen zni jako kamen - rozdeleni podle materialu, ne jeden zvuk na vsechno.
        w.placeBlock(15, FLOOR, 8, World.STONE);
        m.cancel();
        framesToBreak(w, m, 15, FLOOR, 8, 2000);
        m.harvest(w, roomy, drops, heard);
        check("rozbiti kamene zazni kamenem", heard.last().sound() == Sound.BREAK_STONE,
                heard.last().toString());

        // Bez dokopaneho bloku se nic nevytezi.
        int before = heard.played.size();
        int onGround = drops.size();
        check("harvest na vzduchu nic neudela ani nezazni",
                !m.harvest(w, roomy, drops, heard) && drops.size() == onGround
                        && heard.played.size() == before, "");

        w.shutdown();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
