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
 * ⚠️ Co tu NENÍ: PixelMode (Blocks a Skin) je vnitřní třída labu a bez GL
 * nevznikne. Jeho klávesy (Ctrl+Z, F3, hex) testuje jen ruční zkouška.
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

        try {
            Keybinds.activate(Keybinds.defaults());
            convention();
            keybindWaiting();
            tunerSteps();
        } finally {
            Keybinds.activate(before);
            BiomeTuning.activate(tuningBefore);
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
        keys.onEnter();
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
        keys.onEnter();
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
        keys.onEnter();
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
        keys.onEnter();
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

    static Biome otherThan(Biome biome) {
        for (Biome b : Biome.values()) if (b != biome) return b;
        return biome;
    }
}
