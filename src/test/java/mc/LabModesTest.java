package mc;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Logika módů labu bez GL: konvence LabMode.key(), čekání na klávesu
 * v Keybind Labu a posouvání čísel v Ore/Biome Toneru.
 *
 * ---------------------------------------------------------------------------
 * PROČ TENHLE TEST JE. Konvence "key() vrací true = spotřeboval jsem, o
 * zavření rozhoduje hub" se už jednou rozbila: PixelMode a RecipeLab ji
 * četly opačně, takže Delete v módu Recipes vymazal mřížku A ZAVŘEL LAB
 * (a Esc ho nezavíral). Oprava tehdy regresní test nedostala - módy
 * potřebovaly živý lab (kvůli hláškám, a ten je GL). Teď si hlášku umí
 * mód zapamatovat sám (lastMessage) a hub má rozhodnutí jako statickou
 * funkci TextureLab.closesLab(), takže jde projít se skutečnými módy.
 *
 * Dál tu je sjednocení labů: výchozí metody LabMode (mód přidaný jedním
 * řádkem), rozepsaná práce přežije přepnutí módu, unsaved() proti tomu,
 * co platí, pojistka zavření (LabGuard) a vnořené fáze měření.
 *
 * ⚠️ Co tu NENÍ: PixelMode (Blocks a Skin) je vnitřní třída labu a bez GL
 * nevznikne. Jeho klávesy (Ctrl+Z, hex) a přepínání měření hubem (F3)
 * testuje jen ruční zkouška.
 * ---------------------------------------------------------------------------
 */
public class LabModesTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        Keybinds before = Keybinds.active();
        BiomeTuning tuningBefore = BiomeTuning.active();
        RecipeBook recipesBefore = RecipeBook.active();

        try {
            Keybinds.activate(Keybinds.defaults());
            convention();
            keybindWaiting();
            tunerSteps();
            modeDefaults();
            draftsSurvive();
            guard();
            profilerNesting();
        } finally {
            Keybinds.activate(before);
            BiomeTuning.activate(tuningBefore);
            RecipeBook.activate(recipesBefore);
        }

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ==================================================================
    // konvence key(): true = spotřeboval, zavírá jen hub
    // ==================================================================

    static void convention() {
        System.out.println("\n-- konvence LabMode.key(): zavira hub, ne mod --");

        int labKey = Keybinds.defaults().key(Keybinds.Action.LAB);

        RecipeLab recipes = new RecipeLab(null, null, null);
        recipes.onEnter();
        KeybindLab keys = new KeybindLab(null, null, null);
        keys.edit(Keybinds.active());
        BiomeTunerLab biomes = new BiomeTunerLab(null, null, null);
        biomes.edit(BiomeTuning.defaults());

        for (LabMode mode : new LabMode[]{recipes, keys, biomes}) {
            String name = mode.title();
            check(name + ": Esc zavre lab", TextureLab.closesLab(mode, GLFW_KEY_ESCAPE, 0), "");
            check(name + ": klavesa labu zavre lab", TextureLab.closesLab(mode, labKey, 0), "");
            check(name + ": jina klavesa lab nezavre", !TextureLab.closesLab(mode, GLFW_KEY_J, 0), "");
        }

        // Presne ta chyba z drivejska: Delete v Recipes mrizku vymaze a lab NEZAVRE.
        recipes.grid().set(4, ItemStack.of(World.STONE, 1));
        check("Recipes: Delete lab nezavre (je spotrebovany)",
                !TextureLab.closesLab(recipes, GLFW_KEY_DELETE, 0), "");
        check("Recipes: Delete mrizku vymaze", recipes.grid().isEmpty(), "");
        check("Recipes: a rekne to", recipes.lastMessage().equals("Grid cleared"), recipes.lastMessage());
        check("Recipes: Backspace taky", !TextureLab.closesLab(recipes, GLFW_KEY_BACKSPACE, 0), "");

        // F11 si mod nevezme a nezavira - Main ji pak pouzije k prepnuti cele obrazovky.
        check("Recipes: F11 je UNUSED (Main prepne celou obrazovku)",
                TextureLab.route(recipes, GLFW_KEY_F11, 0) == TextureLab.KeyResult.UNUSED, "");
        keys.arm(Keybinds.Action.FULLSCREEN);
        check("Keys pri cekani: F11 je CONSUMED (jde ji priradit, fullscreen se neprepne)",
                TextureLab.route(keys, GLFW_KEY_F11, 0) == TextureLab.KeyResult.CONSUMED, "");
        keys.edit(Keybinds.active());

        // Klavesa labu zavira i po prebindovani - hub se pta Keybinds, ne GLFW konstanty.
        Keybinds moved = Keybinds.defaults().with(Keybinds.Action.LAB, GLFW_KEY_F7);
        Keybinds.activate(moved);
        check("prebindovana klavesa labu (F7) lab zavre", TextureLab.closesLab(recipes, GLFW_KEY_F7, 0), "");
        check("a puvodni F6 uz ne", !TextureLab.closesLab(recipes, GLFW_KEY_F6, 0), "");
        Keybinds.activate(Keybinds.defaults());
    }

    // ==================================================================
    // Keybind Lab: při čekání na klávesu si mód bere všechno
    // ==================================================================

    static void keybindWaiting() {
        System.out.println("\n-- Keybind Lab: cekani na klavesu --");

        KeybindLab keys = new KeybindLab(null, null, null);
        keys.edit(Keybinds.active());
        int labKey = Keybinds.defaults().key(Keybinds.Action.LAB);

        check("bez cekani si mod klavesy nebere", !keys.key(GLFW_KEY_J, 0), "");

        // Klavesa labu se pri cekani PRIRADI, lab nezavre.
        keys.arm(Keybinds.Action.JUMP);
        check("pri cekani se klavesa labu spotrebuje (lab se nezavre)",
                !TextureLab.closesLab(keys, labKey, 0), "");
        check("a priradi se akci", keys.draft().key(Keybinds.Action.JUMP) == labKey,
                Keybinds.keyName(keys.draft().key(Keybinds.Action.JUMP)));
        check("po prirazeni se uz neceka", keys.arming() == null, "");
        check("prirazeni kolize projde, jen se nahlasi (Save ji odmitne)",
                keys.lastMessage().contains("clashes"), keys.lastMessage());
        check("Save kolizi odmitne", keys.problem() != null, "");

        // Esc pri cekani ceka zrusi, lab nezavre a nic neprepise.
        keys.edit(Keybinds.active());
        int jumpBefore = keys.draft().key(Keybinds.Action.JUMP);
        keys.arm(Keybinds.Action.JUMP);
        check("Esc pri cekani lab nezavre", !TextureLab.closesLab(keys, GLFW_KEY_ESCAPE, 0), "");
        check("a jen ceka zrusi", keys.arming() == null
                && keys.draft().key(Keybinds.Action.JUMP) == jumpBefore, "");
        check("a rekne to", keys.lastMessage().contains("cancelled"), keys.lastMessage());

        // Neznama klavesa (GLFW_KEY_UNKNOWN = -1 = NONE) se odmitne, jinak by akce tise zmizela.
        keys.arm(Keybinds.Action.JUMP);
        check("neznama klavesa se spotrebuje", keys.key(GLFW_KEY_UNKNOWN, 0), "");
        check("ale neprirazuje", keys.draft().key(Keybinds.Action.JUMP) == jumpBefore, "");

        // Prohozeni dvou klaves: mezistav je kolize, konec uz ne.
        keys.edit(Keybinds.active());
        int w = keys.draft().key(Keybinds.Action.FORWARD);
        int s = keys.draft().key(Keybinds.Action.BACK);
        keys.arm(Keybinds.Action.FORWARD);
        keys.key(s, 0);
        check("mezistav prohozeni je kolize", keys.problem() != null, "");
        keys.arm(Keybinds.Action.BACK);
        keys.key(w, 0);
        check("po prohozeni kolize neni", keys.problem() == null, "" + keys.problem());
    }

    // ==================================================================
    // Ore/Biome Tuner: posouvání čísel
    // ==================================================================

    static void tunerSteps() {
        System.out.println("\n-- Ore/Biome Tuner: posouvani cisel --");

        BiomeTunerLab lab = new BiomeTunerLab(null, null, null);
        lab.edit(BiomeTuning.defaults());
        Biome biome = lab.selected();
        BiomeTuning.Tune start = lab.draft().tune(biome);

        // Min nad max vytahne max s sebou (rozsah se nerozbije).
        for (int i = 0; i <= start.trunkMax() - start.trunkMin(); i++) {
            lab.step(BiomeTunerLab.Row.TRUNK_MIN, +1);
        }
        BiomeTuning.Tune t = lab.draft().tune(biome);
        check("trunk min nad max vytahne max s sebou",
                t.trunkMin() == start.trunkMax() + 1 && t.trunkMax() == t.trunkMin(),
                t.trunkMin() + "-" + t.trunkMax());

        // Max pod min stahne min s sebou.
        for (int i = 0; i < 40; i++) lab.step(BiomeTunerLab.Row.CROWN_MAX, -1);
        t = lab.draft().tune(biome);
        check("crown max pod min stahne min, a oboje skonci na mezi",
                t.crownMax() == BiomeTuning.MIN_CROWN && t.crownMin() == BiomeTuning.MIN_CROWN,
                t.crownMin() + "-" + t.crownMax());

        // Meze: sto kroku dolu a nahoru se zastavi na mezich, ne za nimi.
        for (int i = 0; i < 200; i++) lab.step(BiomeTunerLab.Row.BASE, -1);
        check("zakladni vyska se zastavi na MIN_BASE",
                lab.draft().tune(biome).baseHeight() == BiomeTuning.MIN_BASE,
                "" + lab.draft().tune(biome).baseHeight());
        for (int i = 0; i < 200; i++) lab.step(BiomeTunerLab.Row.BASE, +1);
        check("a nahore na MAX_BASE",
                lab.draft().tune(biome).baseHeight() == BiomeTuning.MAX_BASE,
                "" + lab.draft().tune(biome).baseHeight());

        // Ruda po ctvrtinach.
        double iron = lab.draft().tune(biome).ironDensity();
        lab.step(BiomeTunerLab.Row.IRON, +1);
        check("ruda se posune o 0,25",
                Math.abs(lab.draft().tune(biome).ironDensity() - (iron + 0.25)) < 1e-9,
                iron + " -> " + lab.draft().tune(biome).ironDensity());

        check("jiny biom se tim nezmenil",
                lab.draft().tune(otherThan(biome)).equals(BiomeTuning.defaults().tune(otherThan(biome))), "");
    }

    // ==================================================================
    // LabMode: výchozí odpovědi = "nic", aby šel mód přidat jedním řádkem
    // ==================================================================

    static void modeDefaults() {
        System.out.println("\n-- LabMode: vychozi odpovedi modu, ktery nic navic neumi --");

        // Nejmenší možný mód - přesně to, co by vzniklo "třídou a jedním řádkem".
        LabMode bare = new LabMode() {
            public String title() { return "Bare"; }
            public String hint() { return "Bare: nothing here"; }
            public void drawIcon(Renderer2D s, float l, float b, float size) {}
            public void drawShapes(TextureLabLayout l, int w, int h, double x, double y) {}
            public void drawText(TextureLabLayout l, int w, int h, double x, double y) {}
        };

        // Dřív hub nápovědu, kolečko i úklid rozhodoval podle identity módu
        // a šestý mód by ukazoval nápovědu Biomes.
        check("napoveda bez vlastni verze = hint()", bare.help(null, 0, 0).equals(bare.hint()),
                bare.help(null, 0, 0));
        check("pretazeny soubor neumi (hub rekne kde to jde)",
                !bare.fileDropped(java.nio.file.Path.of("x.png")), "");
        check("nema neulozenou praci", !bare.unsaved(), "");
        check("odchod nic nezahodi", bare.leaveWarning() == null, "");
        bare.scroll(1);
        bare.delete();
        check("kolecko a uklid projdou bez chyby", true, "");
        check("zadny mod = nic neulozeneho", LabGuard.unsavedModes(java.util.List.of(bare)) == null, "");
    }

    // ==================================================================
    // rozepsaná práce přežije přepnutí módu a unsaved() ji pozná
    // ==================================================================

    static void draftsSurvive() {
        System.out.println("\n-- rozepsana prace: prepnuti modu, (unsaved) --");

        // --- Keys ---
        Keybinds.activate(Keybinds.defaults());
        KeybindLab keys = new KeybindLab(null, null, null);
        check("Keys: cerstvy lab nema nic neulozeneho", !keys.unsaved(), "");

        keys.arm(Keybinds.Action.JUMP);
        keys.key(GLFW_KEY_J, 0);
        check("Keys: prebindovani = neulozene", keys.unsaved(), "");

        // Přesně scénář z auditu (LAB-5): Keys -> Blocks -> Keys.
        keys.onLeave();
        keys.onEnter();
        check("Keys: odskok do jineho modu navrh NEZAHODI",
                keys.draft().key(Keybinds.Action.JUMP) == GLFW_KEY_J,
                Keybinds.keyName(keys.draft().key(Keybinds.Action.JUMP)));
        check("Keys: a porad je neulozeny", keys.unsaved(), "");

        // Save = zapsat a aktivovat; tady jen aktivace (test nesmí psát do projektu).
        Keybinds.activate(keys.draft());
        check("Keys: po aktivaci uz neulozeny neni", !keys.unsaved(), "");

        // Titulek dřív říkal "built-in" podle NÁVRHU: po Defaults ukázal
        // built-in, i když ve hře platily vlastní klávesy. Teď je to rozdíl proti aktivním.
        keys.reset();
        check("Keys: Defaults pri vlastnich aktivnich klavesach = neulozene", keys.unsaved(), "");
        Keybinds.activate(Keybinds.defaults());

        // --- Biomes ---
        BiomeTuning.activate(BiomeTuning.defaults());
        BiomeTunerLab biomes = new BiomeTunerLab(null, null, null);
        biomes.edit(BiomeTuning.active());
        check("Biomes: bez zmeny nic neulozeneho", !biomes.unsaved(), "");
        biomes.step(BiomeTunerLab.Row.BASE, +1);
        check("Biomes: krok = neulozene", biomes.unsaved(), "");
        biomes.onLeave();
        check("Biomes: odchod z modu navrh drzi", biomes.unsaved(), "");
        BiomeTuning.activate(biomes.draft());
        check("Biomes: po aktivaci uz neulozeny neni", !biomes.unsaved(), "");
        BiomeTuning.activate(BiomeTuning.defaults());

        // --- Recipes ---
        RecipeBook.activate(RecipeBook.empty());
        RecipeLab recipes = new RecipeLab(null, null, null);
        recipes.onEnter();
        check("Recipes: prazdna mrizka nic neulozeneho", !recipes.unsaved(), "");
        recipes.grid().set(0, ItemStack.of(World.STONE, 1));
        recipes.grid().set(4, ItemStack.of(World.DIRT, 1));
        check("Recipes: recept v mrizce = neulozeny", recipes.unsaved(), "");
        recipes.onLeave();
        recipes.onEnter();
        check("Recipes: prepnuti mrizku necha", !recipes.grid().isEmpty(), "");
        RecipeBook.activate(RecipeBook.active().with(recipes.draft()));
        check("Recipes: vzor uz v knize je = nic neulozeneho", !recipes.unsaved(), "");

        // Hub: seznam módů s neuloženou prací, v pořadí panelu.
        keys.edit(Keybinds.defaults().with(Keybinds.Action.JUMP, GLFW_KEY_J));
        biomes.edit(BiomeTuning.defaults());
        biomes.step(BiomeTunerLab.Row.BASE, -1);
        String unsaved = LabGuard.unsavedModes(java.util.List.of(recipes, keys, biomes));
        check("hub vyjmenuje mody s neulozenou praci v poradi panelu",
                (keys.title() + ", " + biomes.title()).equals(unsaved), "" + unsaved);

        RecipeBook.activate(RecipeBook.empty());
    }

    // ==================================================================
    // LabGuard: napoprvé varuje, podruhé pustí
    // ==================================================================

    static void guard() {
        System.out.println("\n-- LabGuard: zavreni a prepnuti s neulozenou praci --");

        LabGuard g = new LabGuard();
        check("nic neulozeneho -> zavre hned", g.allow("close", null), "");
        check("neulozene -> napoprve ne", !g.allow("close", "Keys"), "");
        check("hned podruhe ano", g.allow("close", "Keys"), "");
        check("a potreti zase varuje (potvrzeni se spotrebovalo)", !g.allow("close", "Keys"), "");

        g = new LabGuard();
        g.allow("close", "Keys");
        check("varovani pred zavrenim nepusti prepnuti modu", !g.allow("mode 2", "block"), "");
        check("a prepnuti pak potvrdi jen tentyz mod", !g.allow("mode 3", "block"), "");
        check("tentyz mod podruhe ano", g.allow("mode 3", "block"), "");

        g = new LabGuard();
        g.allow("close", "Keys");
        g.update(LabGuard.CONFIRM_SECONDS + 0.1f);
        check("po vyprseni se znovu varuje", !g.allow("close", "Keys"), "");

        g = new LabGuard();
        g.allow("close", "Keys");
        g.allow("close", null);
        check("mezitim ulozeno (nic k zahozeni) -> dalsi zmena se varuje znovu",
                !g.allow("close", "Keys"), "");
    }

    // ==================================================================
    // LabProfiler: vnořené fáze se nepočítají dvakrát
    // ==================================================================

    static void profilerNesting() {
        System.out.println("\n-- LabProfiler: vnorene faze --");

        // Hub měří obsah módu jako SHAPES a Blocks/Skin uvnitř jemněji.
        // Vnořená fáze musí vnější pozastavit, jinak by se čas započítal dvakrát.
        LabProfiler p = new LabProfiler();
        p.toggle();

        for (int frame = 0; frame < LabProfiler.WINDOW; frame++) {
            p.beginFrame();
            p.start(LabProfiler.SHAPES);
            p.start(LabProfiler.UPLOAD);
            busy(200_000);                 // 0,2 ms jen ve vnořené fázi
            p.stop(LabProfiler.UPLOAD);
            p.stop(LabProfiler.SHAPES);
            p.endFrame();
        }

        double shapes = p.millis(LabProfiler.SHAPES);
        double upload = p.millis(LabProfiler.UPLOAD);
        check("vnorena faze ma svuj cas", upload >= 0.19, String.format("upload %.3f ms", upload));
        check("vnejsi faze ho nezapocita znovu", shapes < upload / 2,
                String.format("shapes %.3f ms, upload %.3f ms", shapes, upload));

        // Nespárovaný stop (jiná fáze, než která běží) nic nerozbije.
        p.beginFrame();
        p.start(LabProfiler.TEXT);
        p.stop(LabProfiler.IMAGES);
        p.stop(LabProfiler.TEXT);
        p.endFrame();
        check("nesparovany stop se ignoruje", true, "");
    }

    /** Aktivní čekání - sleep má na Windows krok ~1 ms a test by trval zbytečně dlouho. */
    static void busy(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    static Biome otherThan(Biome biome) {
        for (Biome b : Biome.values()) if (b != biome) return b;
        return biome;
    }
}
