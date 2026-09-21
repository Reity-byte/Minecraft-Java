package mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Overuje prebindovani klaves: Keybinds a keybinds.json.
 *
 * ---------------------------------------------------------------------------
 * Nejdulezitejsi kontroly jsou dve. Prvni: kolize (dve akce na jedne
 * klavese) NESMI spustit obe akce potichu - actionFor() musi vratit null
 * a effectiveKey() NONE, takze ta klavesa nedela nic. Druha: chybejici
 * nebo poskozeny soubor musi dat PRESNE ty klavesy, ktere mel Main
 * natvrdo, jinak by nepovinny soubor prestal byt nepovinny.
 *
 * ⚠️ Testy si na konci vrati puvodni aktivni klavesy. Keybinds.active() je
 * globalni stav jako RecipeBook.active() a AllTests bezi vsechno v jednom
 * JVM.
 * ---------------------------------------------------------------------------
 *
 * Nesaha na GL ani na GLFW za behu - GLFW_KEY_* jsou konstanty.
 */
public class KeybindTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        Keybinds before = Keybinds.active();

        try {
            defaults();
            keyNames();
            conflicts();
            roundTrip();
            brokenFile();
            liveWithoutRestart();
            labFlow();
        } finally {
            Keybinds.activate(before);
        }

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ==================================================================
    // 1) vychozi klavesy = to, co mel Main natvrdo
    // ==================================================================

    static void defaults() {
        System.out.println("\n-- vychozi klavesy --");

        Keybinds d = Keybinds.defaults();

        check("vychozi nastaveni je vychozi", d.isDefault(), "");

        // Tohle je ta kontrola, ktera hlida "chybejici soubor = dnesni hra".
        // Cisla jsou opsana z Main pred zavedenim Keybinds, ne z enumu -
        // jinak by test jen porovnal enum sam se sebou.
        check("W je dopredu", d.key(Keybinds.Action.FORWARD) == GLFW_KEY_W, "");
        check("S je dozadu", d.key(Keybinds.Action.BACK) == GLFW_KEY_S, "");
        check("A je doleva", d.key(Keybinds.Action.LEFT) == GLFW_KEY_A, "");
        check("D je doprava", d.key(Keybinds.Action.RIGHT) == GLFW_KEY_D, "");
        check("mezernik je skok", d.key(Keybinds.Action.JUMP) == GLFW_KEY_SPACE, "");
        check("levy Shift je sprint", d.key(Keybinds.Action.SPRINT) == GLFW_KEY_LEFT_SHIFT, "");
        check("levy Ctrl je plizeni", d.key(Keybinds.Action.SNEAK) == GLFW_KEY_LEFT_CONTROL, "");
        check("E je inventar", d.key(Keybinds.Action.INVENTORY) == GLFW_KEY_E, "");
        check("Q je vyhozeni", d.key(Keybinds.Action.DROP) == GLFW_KEY_Q, "");
        check("Esc je pauza", d.key(Keybinds.Action.PAUSE) == GLFW_KEY_ESCAPE, "");
        check("F3 je ladici vypis", d.key(Keybinds.Action.DEBUG) == GLFW_KEY_F3, "");
        check("F5 je pohled", d.key(Keybinds.Action.VIEW) == GLFW_KEY_F5, "");
        check("F6 je lab", d.key(Keybinds.Action.LAB) == GLFW_KEY_F6, "");
        check("F11 je cela obrazovka", d.key(Keybinds.Action.FULLSCREEN) == GLFW_KEY_F11, "");
        check("V je vsync", d.key(Keybinds.Action.VSYNC) == GLFW_KEY_V, "");
        check("T je posun casu", d.key(Keybinds.Action.SKIP_TIME) == GLFW_KEY_T, "");
        check("F je let", d.key(Keybinds.Action.FLY) == GLFW_KEY_F, "");
        check("C je noclip", d.key(Keybinds.Action.NOCLIP) == GLFW_KEY_C, "");

        boolean hotbar = true;
        for (int i = 0; i < 9; i++) {
            Keybinds.Action action = Keybinds.Action.values()[Keybinds.Action.HOTBAR_1.ordinal() + i];
            hotbar &= d.key(action) == GLFW_KEY_1 + i && action.hotbarSlot() == i;
        }
        check("klavesy 1-9 jsou sloty hotbaru 0-8", hotbar, "");

        // ⚠️ Vychozi nastaveni NESMI mit kolizi - hra by startovala
        // s nefunkcni klavesou. Proto je plizeni a klesani v letu JEDNA
        // akce a ne dve nad Ctrl.
        check("vychozi klavesy nekoliduji", !d.hasConflicts(),
                String.join("; ", d.conflicts()));

        check("kazda akce ma jine id", uniqueIds(), "");
        check("hotbarSlot() je -1 u vseho, co neni hotbar",
                Keybinds.Action.FORWARD.hotbarSlot() < 0
                        && Keybinds.Action.VSYNC.hotbarSlot() < 0, "");
    }

    static boolean uniqueIds() {
        Keybinds.Action[] actions = Keybinds.Action.values();

        for (int i = 0; i < actions.length; i++) {
            for (int j = i + 1; j < actions.length; j++) {
                if (actions[i].id().equals(actions[j].id())) {
                    return false;
                }
            }
            if (Keybinds.Action.byId(actions[i].id()) != actions[i]) {
                return false;
            }
        }

        return true;
    }

    // ==================================================================
    // 2) jmena klaves tam a zpatky
    // ==================================================================

    static void keyNames() {
        System.out.println("\n-- jmena klaves --");

        check("W se jmenuje W", Keybinds.keyName(GLFW_KEY_W).equals("W"), "");
        check("mezernik ma jmeno", Keybinds.keyName(GLFW_KEY_SPACE).equals("SPACE"), "");
        check("levy Ctrl ma jmeno", Keybinds.keyName(GLFW_KEY_LEFT_CONTROL).equals("LEFT CTRL"), "");
        check("NONE se jmenuje NONE", Keybinds.keyName(Keybinds.NONE).equals("NONE"), "");

        // ⚠️ Prevod musi byt UPLNY i pro klavesu, kterou tabulka nezna -
        // jinak by se pri ulozeni tiche ztratila.
        int exotic = GLFW_KEY_WORLD_1;
        check("neznamy kod se zapise jako #kod",
                Keybinds.keyName(exotic).equals("#" + exotic), Keybinds.keyName(exotic));
        check("a precte se zpatky", Keybinds.keyCode(Keybinds.keyName(exotic)) == exotic, "");

        boolean roundTrip = true;
        for (Keybinds.Action action : Keybinds.Action.values()) {
            int key = action.defaultKey();
            roundTrip &= Keybinds.keyCode(Keybinds.keyName(key)) == key;
        }
        check("kazda vychozi klavesa projde jmenem tam a zpatky", roundTrip, "");

        check("nesmysl neni klavesa", Keybinds.keyCode("tohle neni klavesa") == Keybinds.NONE, "");
        check("null neni klavesa", Keybinds.keyCode(null) == Keybinds.NONE, "");
        check("male pismeno je tataz klavesa", Keybinds.keyCode("w") == GLFW_KEY_W, "");

        // GLFW_KEY_UNKNOWN je -1, tedy nase NONE - takovy stisk se nesmi
        // priradit, jinak by akce tise zmizela.
        check("GLFW_KEY_UNKNOWN se prirazovat nesmi",
                !Keybinds.isUsableKey(GLFW_KEY_UNKNOWN), "");
        check("obycejna klavesa se priradit da", Keybinds.isUsableKey(GLFW_KEY_W), "");
    }

    // ==================================================================
    // 3) kolize
    // ==================================================================

    static void conflicts() {
        System.out.println("\n-- kolize --");

        Keybinds clash = Keybinds.defaults().with(Keybinds.Action.VSYNC, GLFW_KEY_E);

        check("kolize se pozna", clash.hasConflicts(), "");
        check("obe kolidujici akce o sobe vi",
                clash.conflicted(Keybinds.Action.VSYNC)
                        && clash.conflicted(Keybinds.Action.INVENTORY), "");
        check("nekolidujici akce se toho netyka",
                !clash.conflicted(Keybinds.Action.FORWARD), "");

        // ⚠️ TOHLE JE TA HLAVNI KONTROLA: kolidujici klavesa nesmi spustit
        // ani jednu akci. Kdyby vratila jednu z nich, rozhodovalo by o tom
        // poradi v enumu, ktere uzivatel nevidi; kdyby obe, otevrel by
        // jeden stisk inventar a zaroven vypnul vsync.
        check("kolidujici klavesa nespusti ZADNOU akci",
                clash.actionFor(GLFW_KEY_E) == null, "");
        check("a ani jedna akce nema pouzitelnou klavesu",
                clash.effectiveKey(Keybinds.Action.VSYNC) == Keybinds.NONE
                        && clash.effectiveKey(Keybinds.Action.INVENTORY) == Keybinds.NONE, "");
        check("ale nastavenou klavesu porad hlasi (aby sla v labu opravit)",
                clash.key(Keybinds.Action.VSYNC) == GLFW_KEY_E, "");

        List<String> lines = clash.conflicts();
        check("kolize se popise pro UI", lines.size() == 1 && lines.get(0).startsWith("E = "),
                lines.toString());
        check("popis jmenuje obe akce",
                lines.get(0).contains(Keybinds.Action.INVENTORY.label())
                        && lines.get(0).contains(Keybinds.Action.VSYNC.label()), lines.get(0));

        check("bez kolize je seznam prazdny", Keybinds.defaults().conflicts().isEmpty(), "");

        // Tri akce na jedne klavese je porad jedna kolize, ne dve.
        Keybinds three = clash.with(Keybinds.Action.SKIP_TIME, GLFW_KEY_E);
        check("tri akce na jedne klavese = jeden radek a nic nefunguje",
                three.conflicts().size() == 1 && three.actionFor(GLFW_KEY_E) == null, "");

        // NONE neni kolize, i kdyz ji ma vic akci - "nic" se nespousti.
        Keybinds unbound = Keybinds.defaults()
                .with(Keybinds.Action.FLY, Keybinds.NONE)
                .with(Keybinds.Action.NOCLIP, Keybinds.NONE);
        check("dve neprirazene akce nejsou kolize",
                !unbound.hasConflicts() && unbound.conflicts().isEmpty(), "");
        check("neprirazena akce nema pouzitelnou klavesu",
                unbound.effectiveKey(Keybinds.Action.FLY) == Keybinds.NONE, "");
        check("a NONE nespusti nic", unbound.actionFor(Keybinds.NONE) == null, "");

        check("with() nemeni puvodni nastaveni",
                Keybinds.defaults().key(Keybinds.Action.VSYNC) == GLFW_KEY_V, "");
    }

    // ==================================================================
    // 4) soubor tam a zpatky
    // ==================================================================

    static void roundTrip() throws IOException {
        System.out.println("\n-- keybinds.json tam a zpatky --");

        Path dir = Files.createTempDirectory("mc-keybinds");
        Path file = dir.resolve("keybinds.json");

        try {
            check("chybejici soubor da VYCHOZI klavesy MLCKY",
                    Keybinds.load(file).isDefault(), "");

            Keybinds custom = Keybinds.defaults()
                    .with(Keybinds.Action.FORWARD, GLFW_KEY_UP)
                    .with(Keybinds.Action.BACK, GLFW_KEY_DOWN)
                    .with(Keybinds.Action.LAB, GLFW_KEY_F8)
                    .with(Keybinds.Action.NOCLIP, Keybinds.NONE);

            check("ulozeni zapise soubor", custom.save(file) && Files.isRegularFile(file), "");

            Keybinds loaded = Keybinds.load(file);

            boolean same = true;
            for (Keybinds.Action action : Keybinds.Action.values()) {
                same &= loaded.key(action) == custom.key(action);
            }
            check("nactene klavesy jsou ty ulozene", same, "");
            check("i neprirazena akce prezije", loaded.key(Keybinds.Action.NOCLIP) == Keybinds.NONE, "");
            check("nactene nastaveni uz neni vychozi", !loaded.isDefault(), "");

            String first = Files.readString(file);
            custom.save(file);
            check("druhy zapis je bajt po bajtu stejny", first.equals(Files.readString(file)), "");

            check("soubor je citelny - je v nem jmeno klavesy, ne kod",
                    first.contains("\"lab\": \"F8\""), "");

            // Vychozi nastaveni se taky musi dat ulozit a nacist beze zmeny.
            Keybinds.defaults().save(file);
            check("ulozene vychozi nastaveni se nacte jako vychozi",
                    Keybinds.load(file).isDefault(), "");
        } finally {
            delete(dir);
        }
    }

    // ==================================================================
    // 5) poskozeny soubor
    // ==================================================================

    static void brokenFile() throws IOException {
        System.out.println("\n-- poskozeny soubor --");

        Path dir = Files.createTempDirectory("mc-keybinds-broken");
        Path file = dir.resolve("keybinds.json");

        try {
            String[] junk = {"tohle neni json", "", "[1,2,3]", "{}", "{\"format\": 1}",
                    "{\"keys\": 5}", "{\"format\": 1, \"keys\": [1,2]}"};

            boolean allDefault = true;
            for (String text : junk) {
                Files.writeString(file, text);
                allDefault &= Keybinds.load(file).isDefault();
            }
            check("kazdy druh poskozeneho souboru da VYCHOZI klavesy (= hra jako dnes)",
                    allDefault, "");

            // Jedna vadna polozka shodi jen sama sebe.
            Files.writeString(file, "{\"format\": 1, \"keys\": {"
                    + "\"forward\": \"UP\", \"back\": 42, \"neexistuje\": \"X\","
                    + "\"jump\": \"nesmysl\"}}");

            Keybinds partial = Keybinds.load(file);
            check("platna polozka se nacte", partial.key(Keybinds.Action.FORWARD) == GLFW_KEY_UP, "");
            check("klavesa, ktera neni text, nechá akci na vychozi",
                    partial.key(Keybinds.Action.BACK) == GLFW_KEY_S, "");
            check("neznama klavesa necha akci na vychozi",
                    partial.key(Keybinds.Action.JUMP) == GLFW_KEY_SPACE, "");
            check("neznama akce jen zmizi, ostatni se nactou",
                    partial.key(Keybinds.Action.LEFT) == GLFW_KEY_A, "");

            // Rucne napsana kolize soubor nezahodi, ale ta klavesa nedela nic.
            Files.writeString(file, "{\"format\": 1, \"keys\": {\"vsync\": \"E\"}}");
            Keybinds clash = Keybinds.load(file);
            check("kolize v rucne upravenem souboru se nacte", clash.hasConflicts(), "");
            check("ale ta klavesa porad nedela nic", clash.actionFor(GLFW_KEY_E) == null, "");

            // Novejsi format se nacte s varovanim.
            Files.writeString(file, "{\"format\": 99, \"keys\": {\"forward\": \"UP\"}}");
            check("novejsi format se precte, co zna",
                    Keybinds.load(file).key(Keybinds.Action.FORWARD) == GLFW_KEY_UP, "");

            // Zaloha .bak: soubor, ktery nesel cely nacist, se pred prepsanim zalohuje.
            Files.writeString(file, "{\"format\": 1, \"keys\": {\"forward\": 42}}");
            Keybinds.defaults().save(file);
            check("poskozeny soubor se pred prepsanim zalohoval do .bak",
                    Files.isRegularFile(dir.resolve("keybinds.json.bak")), "");
        } finally {
            delete(dir);
        }
    }

    // ==================================================================
    // 6) plati hned po Save, bez restartu
    // ==================================================================

    static void liveWithoutRestart() throws IOException {
        System.out.println("\n-- plati hned, bez restartu --");

        Path dir = Files.createTempDirectory("mc-keybinds-live");
        Path file = dir.resolve("keybinds.json");

        try {
            Keybinds.activate(Keybinds.defaults());

            check("pred zmenou otevira inventar E",
                    Keybinds.active().actionFor(GLFW_KEY_E) == Keybinds.Action.INVENTORY, "");
            check("a I nedela nic", Keybinds.active().actionFor(GLFW_KEY_I) == null, "");

            // ⚠️ Tohle je presne to, co dela Save v labu: zapsat, PAK aktivovat.
            Keybinds moved = Keybinds.defaults().with(Keybinds.Action.INVENTORY, GLFW_KEY_I);
            moved.save(file);
            Keybinds.activate(moved);

            check("hned po Save otevira inventar I",
                    Keybinds.active().actionFor(GLFW_KEY_I) == Keybinds.Action.INVENTORY, "");
            check("a E uz nedela nic", Keybinds.active().actionFor(GLFW_KEY_E) == null, "");
            check("zbytek klaves zustal", Keybinds.active().actionFor(GLFW_KEY_W)
                    == Keybinds.Action.FORWARD, "");

            // A to same, co by udelal restart: nacist soubor.
            check("po 'restartu' (nacteni souboru) plati totez",
                    Keybinds.load(file).actionFor(GLFW_KEY_I) == Keybinds.Action.INVENTORY, "");

            Keybinds.activate(null);
            check("activate(null) vrati vychozi klavesy", Keybinds.active().isDefault(), "");
        } finally {
            Keybinds.activate(Keybinds.defaults());
            delete(dir);
        }
    }

    // ==================================================================
    // 7) logika labu (bez GL)
    // ==================================================================

    static void labFlow() {
        System.out.println("\n-- logika Keybind Labu --");

        // KeybindLab sam potrebuje zivy lab kvuli hlaskam, takze se tu
        // overuje ta cast, ktera je ciste nad Keybinds - tedy presne to,
        // co lab dela s rozepsanym nastavenim.
        Keybinds draft = Keybinds.defaults();

        draft = draft.with(Keybinds.Action.INVENTORY, GLFW_KEY_I);
        check("prirazeni nove klavesy neudela kolizi", !draft.hasConflicts(), "");

        // Mezistav vymeny dvou klaves JE kolize - a prave proto se prirazeni
        // nesmi zakazovat, jen ulozeni.
        Keybinds swapping = Keybinds.defaults().with(Keybinds.Action.VSYNC, GLFW_KEY_E);
        check("mezistav vymeny je kolize, ale prirazeni prosla",
                swapping.hasConflicts() && swapping.key(Keybinds.Action.VSYNC) == GLFW_KEY_E, "");

        Keybinds finished = swapping.with(Keybinds.Action.INVENTORY, GLFW_KEY_V);
        check("po dokonceni vymeny uz kolize neni", !finished.hasConflicts(), "");
        check("a obe klavesy delaji to druhe",
                finished.actionFor(GLFW_KEY_E) == Keybinds.Action.VSYNC
                        && finished.actionFor(GLFW_KEY_V) == Keybinds.Action.INVENTORY, "");
    }

    static void delete(Path dir) throws IOException {
        try (var files = Files.walk(dir)) {
            for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
