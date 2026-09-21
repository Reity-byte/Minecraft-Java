package mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Overuje stav, ktery NEPATRI World, ale prezije vymenu sveta v temze behu hry.
 *
 * ---------------------------------------------------------------------------
 * PROC TENHLE TEST VUBEC JE. Main.freshWorld() vymenoval World, SoundEngine,
 * WorldRenderer a polozky na zemi - tedy vsechno, co je samo o sobe objekt.
 * Inventar, crafting mrizky, vybrany slot a denni doba ale objekt sveta nejsou:
 * jsou to pole Main, ktera zustavaji tataz instance po celou dobu behu hry.
 * Na ty se zapomnelo, takze:
 *
 *   BUG 1: nove zalozeny svet ukazal v inventari veci ze sveta, ve kterem
 *          hrac byl pred chvili.
 *   BUG 2: novy svet pokracoval v denni dobe tam, kde skoncil ten predchozi -
 *          hrac odesel v noci a novy svet zacal taky v noci.
 *
 * Obe chyby maji tutez pricinu, takze je hlida jeden test.
 *
 * ⚠️ CO TENHLE TEST NEPOKRYVA. freshWorld() sam zavolat nejde - otevira
 * OpenAL a saha na WorldRenderer, ktery bez GL kontextu neexistuje. Testuje
 * se proto resetPlayerState(), tedy presne ta metoda, kterou freshWorld()
 * vola hned po vymene World. Kdyby nekdo to jedno volani z freshWorld()
 * smazal, test to nechytne - je to jeden radek a jeho smazani je videt.
 * ---------------------------------------------------------------------------
 *
 * Nesaha na GL ani na OpenAL.
 */
public class MainStateTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        freshWorldResetsInventory();
        freshWorldResetsDayCycle();
        dayCycleValues();
        timeSurvivesSaveAndLoad();
        loadedWorldKeepsItsOwnState();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    /**
     * Main bez GL. Konstruktor jen zaklada obycejne objekty (Camera, Player,
     * World, Inventory, Container, DayCycle); vsechno GL-ove se vyrabi az
     * v init(), ktery se tady nevola.
     *
     * ⚠️ World v nem rozjede generujici vlakno, takze se musi zastavit -
     * jinak by testy nechaly za sebou beziciho workera.
     */
    static Main headlessMain() {
        return new Main();
    }

    static void shutdown(Main main) {
        main.world.shutdown();
    }

    // ==================================================================
    // BUG 1: inventar, crafting mrizky a vybrany slot
    // ==================================================================

    static void freshWorldResetsInventory() {
        System.out.println("\n-- Bug 1: novy svet zacina s prazdnym inventarem --");

        Main main = headlessMain();

        // --- svet A: hrac si neco nahraje a neco rozdela v craftingu ---
        main.inventory.set(0, ItemStack.of(World.STONE, 40));
        main.inventory.set(5, ItemStack.of(World.IRON_ORE, 12));
        main.inventory.set(20, ItemStack.of(World.PLANKS, 64));
        main.selectedSlot = 7;
        main.craftingSmall.set(0, ItemStack.of(World.PLANKS, 3));
        main.craftingSmall.set(3, ItemStack.of(World.PLANKS, 1));
        main.craftingLarge.set(4, ItemStack.of(World.STONE_BRICKS, 9));
        main.craftingResult.set(0, ItemStack.of(World.CRAFTING_TABLE, 1));

        check("test neni degenerovany: ve svete A je opravdu neco v inventari",
                !main.inventory.get(0).isEmpty() && !main.craftingSmall.get(0).isEmpty(), "");

        // --- prechod na svet B (novy, bez ulozeneho souboru) ---
        main.resetPlayerState();

        boolean emptyInventory = true;
        for (int i = 0; i < Inventory.SIZE; i++) {
            if (!main.inventory.get(i).isEmpty()) emptyInventory = false;
        }
        check("novy svet ma uplne prazdny inventar", emptyInventory,
                main.inventory.get(0).toString());

        check("vybrany slot je zpatky na nule", main.selectedSlot == 0, "" + main.selectedSlot);

        boolean emptyCrafting = true;
        for (int i = 0; i < 4; i++) if (!main.craftingSmall.get(i).isEmpty()) emptyCrafting = false;
        for (int i = 0; i < 9; i++) if (!main.craftingLarge.get(i).isEmpty()) emptyCrafting = false;
        if (!main.craftingResult.get(0).isEmpty()) emptyCrafting = false;

        // Crafting mrizka je TRVALY kontejner - co v ni zustane, je tam i pri
        // dalsim otevreni. Presne proto se musi pri vymene sveta vyprazdnit.
        check("obe crafting mrizky i vysledkovy slot jsou prazdne", emptyCrafting, "");

        // A jeste jednou tam a zpet, at je videt, ze to neni jednorazovka.
        main.inventory.set(3, ItemStack.of(World.SNOW, 5));
        main.selectedSlot = 2;
        main.resetPlayerState();
        check("druhy prechod resetuje stejne",
                main.inventory.get(3).isEmpty() && main.selectedSlot == 0, "");

        shutdown(main);
    }

    // ==================================================================
    // BUG 2: denni doba
    // ==================================================================

    static void freshWorldResetsDayCycle() {
        System.out.println("\n-- Bug 2: novy svet zacina rano, ne tam, kde skoncil predchozi --");

        Main main = headlessMain();

        check("cerstva hra zacina na START_TIME",
                main.day.time() == DayCycle.START_TIME, "" + main.day.time());

        // --- svet A: hrac v nem stravi pul cyklu a odejde v noci ---
        main.day.skip(0.55f);
        check("test neni degenerovany: ve svete A je opravdu noc",
                main.day.isNight(), String.format("%.1f h", main.day.hours()));

        float nightTime = main.day.time();

        // --- prechod na svet B ---
        main.resetPlayerState();

        check("novy svet zacina na START_TIME, ne v case sveta A",
                main.day.time() == DayCycle.START_TIME,
                String.format("%.1f vs %.1f", main.day.time(), nightTime));
        check("a je v nem den, ne noc", !main.day.isNight(),
                String.format("%.1f h", main.day.hours()));
        check("START_TIME je dopoledne (mezi 8. a 11. hodinou)",
                main.day.hours() > 8f && main.day.hours() < 11f,
                String.format("%.1f h", main.day.hours()));

        shutdown(main);
    }

    /**
     * setTime() musi prezit i nesmysl z poskozeneho souboru.
     *
     * ⚠️ NaN by se pres modulo protahlo dal a daylight() by vracela NaN -
     * obloha i cely svet by zcernaly a nic by nereklo proc.
     */
    static void dayCycleValues() {
        System.out.println("\n-- DayCycle.setTime: meze --");

        DayCycle day = new DayCycle();

        day.setTime(123.5f);
        check("rozumny cas se vezme, jak je", day.time() == 123.5f, "" + day.time());

        day.setTime(0f);
        check("nula je platny cas (pulnoc cyklu)", day.time() == 0f, "" + day.time());

        for (float bad : new float[]{-1f, -0.001f, DayCycle.DAY_LENGTH, DayCycle.DAY_LENGTH + 1,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1e30f}) {
            day.setTime(bad);
            if (day.time() != DayCycle.START_TIME) {
                check("nesmyslny cas " + bad + " spadne na START_TIME", false, "" + day.time());
                return;
            }
        }
        check("zaporny, prilis velky, NaN i nekonecno spadnou na START_TIME", true, "");

        // A vysledek musi byt porad pouzitelne cislo, ne NaN.
        boolean finite = true;
        for (float t : new float[]{0f, 100f, 300f, 599.9f}) {
            day.setTime(t);
            if (!Float.isFinite(day.daylight()) || !Float.isFinite(day.hours())
                    || !Float.isFinite(day.skyAngle()) || !Float.isFinite(day.nightFactor())) {
                finite = false;
            }
        }
        check("daylight, hours, skyAngle i nightFactor jsou vzdycky konecne", finite, "");

        day.reset();
        check("reset() vrati START_TIME", day.time() == DayCycle.START_TIME, "" + day.time());
    }

    // ==================================================================
    // persistence casu
    // ==================================================================

    /**
     * Cas se uklada SE SVETEM (format MCW3), takze nacteny svet pokracuje tam,
     * kde hrac skoncil - odejit v noci a vratit se do noci.
     *
     * Volba mezi "ukladat cas" a "kazdy svet zacina rano": ukladat, protoze
     * format uz jednou presne takhle rostl (MCW1 bez inventare -> MCW2 s nim),
     * takze to neni prestavba, ale ctyri radky ve stejnem vzoru. Starsi soubor
     * cas nema a dostane START_TIME, tedy presne to, co delal dosud.
     */
    static void timeSurvivesSaveAndLoad() throws IOException {
        System.out.println("\n-- cas se uklada se svetem --");

        Path dir = Files.createTempDirectory("mc-daytime");
        Path file = dir.resolve("world.dat");

        World w = new World();
        ItemStack[] inventory = new ItemStack[Inventory.SIZE];
        inventory[0] = ItemStack.of(World.STONE, 3);

        float evening = DayCycle.DAY_LENGTH * 0.47f;

        check("ulozeni projde", WorldStorage.save(file, new WorldStorage.Save(
                1, 2, 3, 0, 0, false, 0, w.changes(), inventory, evening)), "");

        WorldStorage.Save loaded = WorldStorage.load(file);
        check("svet se nacte", loaded != null, "");

        if (loaded != null) {
            check("cas prezil ulozeni a nacteni presne", loaded.dayTime() == evening,
                    loaded.dayTime() + " vs " + evening);
        }

        // ⚠️ Soubor ze starsi verze (MCW2, bez casu) se musi porad nacist
        // a dostat START_TIME - jinak by zvyseni verze formatu znamenalo,
        // ze kazdy rozehrany svet zacina v case 0, tedy o pulnoci cyklu.
        Path old = dir.resolve("old.dat");
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(old)))) {
            out.writeInt(0x4D435732);                       // MCW2
            out.writeInt(WorldStorage.GENERATOR_VERSION);
            out.writeFloat(1); out.writeFloat(2); out.writeFloat(3);
            out.writeFloat(0); out.writeFloat(0);
            out.writeBoolean(false);
            out.writeInt(0);
            out.writeInt(0);                                // zadne zmeny bloku
            out.writeInt(Inventory.SIZE);
            for (int i = 0; i < Inventory.SIZE; i++) {
                out.writeByte(i == 0 ? World.SAND : World.AIR);
                out.writeInt(i == 0 ? 9 : 0);
            }
        }

        WorldStorage.Save oldLoaded = WorldStorage.load(old);
        check("soubor z verze MCW2 (bez casu) se porad nacte", oldLoaded != null, "");
        if (oldLoaded != null) {
            check("a dostane START_TIME, tedy to, co delal dosud",
                    oldLoaded.dayTime() == DayCycle.START_TIME, "" + oldLoaded.dayTime());
            check("jeho inventar dorazi beze zmeny",
                    oldLoaded.inventory()[0].block() == World.SAND
                            && oldLoaded.inventory()[0].count() == 9, "");
        }

        w.shutdown();

        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList())
                Files.deleteIfExists(p);
        }
    }

    /**
     * Cela cesta "svet A -> ulozit -> svet B -> nacist A zpatky" na stavu Main.
     *
     * ⚠️ resetPlayerState() se vola VZDYCKY, i u nacteneho sveta, a restore()
     * az po nem. Kdyby se reset delal jen u noveho sveta, byl by rozdil mezi
     * "novy svet" a "nacteny svet, jehoz soubor danou polozku jeste nema" -
     * ta polozka by se u druheho pripadu zdedila po predchozim svete.
     */
    static void loadedWorldKeepsItsOwnState() {
        System.out.println("\n-- nacteny svet si nese svuj vlastni stav --");

        Main main = headlessMain();
        main.world.loadRadius = 1;
        main.world.unloadRadius = 3;

        // --- svet A: veci v inventari, vecer ---
        main.inventory.set(2, ItemStack.of(World.COAL_ORE, 17));
        main.selectedSlot = 4;
        main.day.setTime(DayCycle.DAY_LENGTH * 0.60f);

        ItemStack[] snapshot = new ItemStack[Inventory.SIZE];
        for (int i = 0; i < Inventory.SIZE; i++) snapshot[i] = main.inventory.get(i);

        WorldStorage.Save saveA = new WorldStorage.Save(
                10, 70, 20, 1f, -0.5f, false, main.selectedSlot,
                main.world.changes(), snapshot, main.day.time());

        // --- svet B: novy ---
        main.resetPlayerState();
        check("svet B nema po A ani inventar, ani cas",
                main.inventory.get(2).isEmpty()
                        && main.day.time() == DayCycle.START_TIME
                        && main.selectedSlot == 0, "");

        // --- zpatky do sveta A: reset a hned po nem restore ---
        main.resetPlayerState();
        main.restore(saveA);

        check("nacteny svet vrati svuj inventar",
                main.inventory.get(2).block() == World.COAL_ORE
                        && main.inventory.get(2).count() == 17,
                main.inventory.get(2).toString());
        check("nacteny svet vrati svuj vybrany slot", main.selectedSlot == 4,
                "" + main.selectedSlot);
        check("nacteny svet vrati svuj cas, ne START_TIME",
                main.day.time() == DayCycle.DAY_LENGTH * 0.60f,
                String.format("%.1f", main.day.time()));
        check("a ve svete A je porad noc", main.day.isNight(),
                String.format("%.1f h", main.day.hours()));

        // Crafting mrizka se uklada jen pres reset - v ulozenem souboru neni,
        // takze nacteny svet ji ma prazdnou. To je dnesni chovani, jen ted
        // po nacteni ciziho sveta nezustanou suroviny z toho predchoziho.
        boolean craftingEmpty = true;
        for (int i = 0; i < 4; i++) if (!main.craftingSmall.get(i).isEmpty()) craftingEmpty = false;
        check("crafting mrizka je i u nacteneho sveta prazdna", craftingEmpty, "");

        shutdown(main);
    }
}
