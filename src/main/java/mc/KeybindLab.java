package mc;

import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

/**
 * Keybind Lab: přebindování kláves do `keybinds.json`.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ MÓD NEDRŽÍ KLÁVESY, DRŽÍ JEN ROZEPSANOU ZMĚNU. Pravda je v `Keybinds`
 * a lab s ní pracuje jako Recipe Lab s `RecipeBook`: postaví si nový neměnný
 * exemplář a Save ho zapíše A ZÁROVEŇ aktivuje. Pořadí je "zapsat, pak
 * aktivovat" - kdyby se aktivovalo dřív, hrálo by se s klávesami, které na
 * disku nejsou, a po restartu by zmizely.
 *
 * ⚠️ KOLIZE SE HLÁSÍ V UI, NE NA KONZOLI. Kolidující tlačítko zčervená
 * a pod seznamem stojí věta "E = Inventory + Toggle VSync". Barva sama by
 * nestačila: na tmavém pozadí se ztratí a barvoslepému uživateli neřekne nic.
 * A dokud kolize je, Save odmítne uložit a řekne proč - uložit nastavení,
 * ve kterém dvě klávesy nefungují, by byla past, na kterou by se přišlo až
 * ve hře.
 *
 * ⚠️ PŘI ČEKÁNÍ NA KLÁVESU SI MÓD BERE VŠECHNY. Jinak by se F6 (nebo jiná
 * klávesa labu) místo přiřazení chytla jako "zavři lab" a přiřadit ji by
 * nešlo vůbec. Esc čekání ZRUŠÍ místo aby se přiřadil - je to jediná
 * klávesa, kterou hra potřebuje na zavření obrazovek, takže ji uživatel
 * skoro jistě mačká proto, aby z toho režimu utekl. Přiřadit Esc přesto jde:
 * přes ruční úpravu souboru.
 * ---------------------------------------------------------------------------
 *
 * Logika bez GL je v `Keybinds` a `TextureLabLayout`; tady je kreslení,
 * vstup a rozepsané nastavení.
 */
public final class KeybindLab implements LabMode {

    private final Renderer2D shapes;
    private final TextRenderer text;
    private final TextureLab lab;

    /** Rozepsané nastavení. Ve hře platí až po Save. */
    private Keybinds draft = Keybinds.active();

    /** Akce, které se zrovna přiřazuje nová klávesa, nebo null. */
    private Keybinds.Action arming = null;

    // ------------------------------------------------------------------
    // hlášky
    // ------------------------------------------------------------------

    /** Poslední hláška - aby šla logika módu otestovat bez labu (a bez GL). */
    private String lastMessage = "";

    /** Hláška do stavového řádku labu. Bez labu (headless test) si ji jen zapamatuje. */
    private void say(String message)
    {
        lastMessage = message;

        if(lab != null)
        {
            lab.say(message);
        }
    }

    String lastMessage()
    {
        return lastMessage;
    }

    public KeybindLab(TextureLab lab, Renderer2D shapes, TextRenderer text)
    {
        this.lab = lab;
        this.shapes = shapes;
        this.text = text;
    }

    @Override
    public String title()
    {
        return "Keys";
    }

    @Override
    public String hint()
    {
        return "Click a key to rebind it, press the new key, then Save";
    }

    /**
     * Ikona: klávesa jako zapuštěný čtvereček s "pecičkou". Obdélníky
     * z `Renderer2D`, takže nevyprázdní dávku bočního panelu (viz "Výkon labu").
     */
    @Override
    public void drawIcon(Renderer2D shapes, float left, float bottom, float size)
    {
        float unit = size / 9f;
        float[] cap = {0.82f, 0.82f, 0.82f, 1f};
        float[] shadow = {0.30f, 0.30f, 0.30f, 1f};
        float[] letter = {0.20f, 0.20f, 0.22f, 1f};

        shapes.fillRect(left + unit, bottom + unit, 7 * unit, 7 * unit, shadow);
        shapes.fillRect(left + 1.6f * unit, bottom + 2.2f * unit, 5.8f * unit, 5.8f * unit, cap);

        // Znak na klávese: svislá čárka a patka, ať je poznat, že je to písmeno.
        shapes.fillRect(left + 4f * unit, bottom + 3.4f * unit, unit, 3.4f * unit, letter);
        shapes.fillRect(left + 3.2f * unit, bottom + 3.4f * unit, 2.6f * unit, unit, letter);
    }

    // ⚠️ onEnter() návrh NEPŘEPISUJE. Dřív tu bylo `draft = Keybinds.active()`
    // a pět přebindovaných kláves zmizelo, stačilo odskočit do Blocks a zpět
    // - bez jediného slova. Návrh vzniká s labem (pole výš) a žije, dokud je
    // lab otevřený; zavření s neuloženými klávesami hlídá hub (unsaved()).

    @Override
    public void onLeave()
    {
        arming = null;
    }

    /** Liší se návrh od kláves, které ve hře platí? */
    @Override
    public boolean unsaved()
    {
        return !draft.sameKeys(Keybinds.active());
    }

    /**
     * Začne upravovat tyhle klávesy - pro testy, které potřebují čistý
     * začátek (lab sám začíná od aktivních při svém vzniku).
     */
    void edit(Keybinds keys)
    {
        draft = keys;
        arming = null;
    }

    // ------------------------------------------------------------------
    // rozepsané nastavení
    // ------------------------------------------------------------------

    /** Rozepsané klávesy. Balíčkově viditelné kvůli testu. */
    Keybinds draft()
    {
        return draft;
    }

    Keybinds.Action arming()
    {
        return arming;
    }

    /** Začne čekat na novou klávesu pro tuhle akci. */
    void arm(Keybinds.Action action)
    {
        arming = action;
        say("Press a key for " + action.label() + "  (Esc cancels)");
    }

    /**
     * Přiřadí klávesu rozepsané akci.
     *
     * Kolize se tady NEBRÁNÍ - přiřadit jde cokoliv a teprve Save řekne ne.
     * Bránit by znamenalo, že prohodit dvě klávesy mezi sebou nejde vůbec:
     * mezistav takové výměny je vždycky kolize.
     */
    void assign(int key)
    {
        if(arming == null)
        {
            return;
        }

        Keybinds.Action action = arming;
        arming = null;

        if(!Keybinds.isUsableKey(key))
        {
            say("That key has no code GLFW knows - not assigned");
            return;
        }

        draft = draft.with(action, key);

        if(draft.conflicted(action))
        {
            say(action.label() + " = " + Keybinds.keyName(key)
                    + " - clashes with another action, fix it before saving");
        }
        else
        {
            say(action.label() + " = " + Keybinds.keyName(key));
        }
    }

    /** Proč nejde uložit, nebo null, když jde. */
    String problem()
    {
        List<String> conflicts = draft.conflicts();

        if(!conflicts.isEmpty())
        {
            return "Two actions share a key: " + conflicts.get(0)
                    + (conflicts.size() > 1 ? " (+" + (conflicts.size() - 1) + " more)" : "");
        }

        return null;
    }

    /**
     * Uloží klávesy do souboru A ZÁROVEŇ je aktivuje.
     *
     * ⚠️ Pořadí je "zapsat, pak aktivovat" - viz poznámka u třídy.
     */
    void save()
    {
        String problem = problem();

        if(problem != null)
        {
            say(problem);
            return;
        }

        if(!draft.save(Keybinds.FILE))
        {
            say(SafeFiles.writeFailed(Keybinds.FILE));
            return;
        }

        Keybinds.activate(draft);
        say("Saved - the new keys work right now, no restart");
    }

    void reset()
    {
        draft = Keybinds.defaults();
        arming = null;
        say("Back to the built-in keys - Save to keep it");
    }

    // ------------------------------------------------------------------
    // vstup
    // ------------------------------------------------------------------

    @Override
    public boolean press(TextureLabLayout layout, double mouseX, double mouseY,
                         int screenWidth, int screenHeight, boolean left)
    {
        if(!left)
        {
            return false;
        }

        Keybinds.Action[] actions = Keybinds.Action.values();
        int index = layout.keyButtonAt(mouseX, mouseY, actions.length);

        if(index >= 0)
        {
            arm(actions[index]);
            return false;
        }

        if(layout.hit(TextureLabLayout.KEYBIND_SAVE, mouseX, mouseY))
        {
            save();
            return false;
        }

        if(layout.hit(TextureLabLayout.KEYBIND_RESET, mouseX, mouseY))
        {
            reset();
            return false;
        }

        if(layout.hit(TextureLabLayout.KEYBIND_CLOSE, mouseX, mouseY))
        {
            return true;
        }

        return false;
    }

    /**
     * ⚠️ PŘI ČEKÁNÍ NA KLÁVESU SI MÓD BERE ÚPLNĚ VŠECHNO (vrací true).
     * Kdyby nechal propadnout klávesu labu, nešla by přiřadit - hub by ji
     * vzal jako "zavři lab". Viz poznámka u třídy.
     */
    @Override
    public boolean key(int key, int mods)
    {
        if(arming == null)
        {
            return false;
        }

        if(key == GLFW_KEY_ESCAPE)
        {
            say("Rebinding " + arming.label() + " cancelled");
            arming = null;
            return true;
        }

        assign(key);
        return true;
    }

    // ------------------------------------------------------------------
    // kreslení
    // ------------------------------------------------------------------

    @Override
    public void drawShapes(TextureLabLayout layout, int screenWidth, int screenHeight,
                           double mouseX, double mouseY)
    {
        int scale = layout.scale();
        shapes.begin(screenWidth, screenHeight);

        Keybinds.Action[] actions = Keybinds.Action.values();

        for(int i = 0; i < actions.length; i++)
        {
            TextureLabLayout.Rect r = TextureLabLayout.keyButton(i);
            boolean hovered = layout.hit(r, mouseX, mouseY);

            float[] fill, highlight, shadow;

            if(actions[i] == arming)
            {
                // Zelené = "mačkej". Musí to být vidět na první pohled, jinak
                // uživatel neví, že hra čeká zrovna na něj.
                fill = Palette.ARMED_FILL;
                highlight = Palette.ARMED_HIGHLIGHT;
                shadow = Palette.ARMED_SHADOW;
            }
            else if(draft.conflicted(actions[i]))
            {
                fill = Palette.WARNING_FILL;
                highlight = Palette.WARNING_HIGHLIGHT;
                shadow = Palette.WARNING_SHADOW;
            }
            else if(hovered)
            {
                fill = Palette.BUTTON_HOVER_FILL;
                highlight = Palette.BUTTON_HOVER_HIGHLIGHT;
                shadow = Palette.BUTTON_HOVER_SHADOW;
            }
            else
            {
                fill = Palette.BUTTON_FILL;
                highlight = Palette.BUTTON_HIGHLIGHT;
                shadow = Palette.BUTTON_SHADOW;
            }

            shapes.bevelRect(layout.screenX(r), layout.screenBottom(r, screenHeight),
                    r.w() * scale, r.h() * scale, scale,
                    Palette.BUTTON_OUTLINE, fill, highlight, shadow);
        }

        lab.button(layout, screenHeight, TextureLabLayout.KEYBIND_SAVE, mouseX, mouseY);
        lab.button(layout, screenHeight, TextureLabLayout.KEYBIND_RESET, mouseX, mouseY);
        lab.button(layout, screenHeight, TextureLabLayout.KEYBIND_CLOSE, mouseX, mouseY);

        shapes.end();
    }

    @Override
    public void drawText(TextureLabLayout layout, int screenWidth, int screenHeight,
                         double mouseX, double mouseY)
    {
        int scale = layout.scale();
        text.begin(screenWidth, screenHeight, scale);

        // "built-in"/"custom" popisuje klávesy, které PLATÍ, ne návrh - viz
        // LabMode.unsaved(). Rozepsané změny říká "(unsaved)".
        lab.label(layout, 8, TextureLabLayout.TITLE_Y,
                "Lab   keys: " + SafeFiles.shown(Keybinds.FILE) + "   "
                        + (Keybinds.active().isDefault() ? "built-in" : "custom")
                        + (unsaved() ? "   (unsaved)" : ""));

        Keybinds.Action[] actions = Keybinds.Action.values();

        for(int i = 0; i < actions.length; i++)
        {
            boolean clash = draft.conflicted(actions[i]);

            text.drawShadowed(actions[i].label(),
                    layout.textLeft(TextureLabLayout.keyLabelX(i)),
                    layout.textTop(TextureLabLayout.keyLabelY(i)),
                    clash ? Palette.TEXT_WARNING : Palette.TEXT, Palette.TEXT_SHADOW);

            String shown = actions[i] == arming ? "press a key..."
                    : Keybinds.keyName(draft.key(actions[i]));

            lab.centered(layout, TextureLabLayout.keyButton(i), shown);
        }

        // ⚠️ Kolize se říkají SLOVY, ne jen barvou tlačítka - viz poznámka
        // u třídy. Vejdou se dva řádky; víc kolizí najednou se neudělá,
        // protože každá další je jen další akce na téže klávese.
        List<String> conflicts = draft.conflicts();

        if(conflicts.isEmpty())
        {
            text.drawShadowed("No clashes - every action has its own key",
                    layout.textLeft(8), layout.textTop(TextureLabLayout.KEY_CONFLICT_Y),
                    Palette.TEXT_MUTED, Palette.TEXT_SHADOW);
        }
        else
        {
            // fit(): s nejdelšími názvy akcí vychází řádek těsně přes šířku.
            text.drawShadowed(lab.fit("CLASH  " + conflicts.get(0) + "  - neither one fires",
                            TextureLabLayout.CONTENT_WIDTH - 16, scale),
                    layout.textLeft(8), layout.textTop(TextureLabLayout.KEY_CONFLICT_Y),
                    Palette.TEXT_WARNING, Palette.TEXT_SHADOW);

            String more = conflicts.size() > 1
                    ? "CLASH  " + conflicts.get(1)
                        + (conflicts.size() > 2 ? "   (+" + (conflicts.size() - 2) + " more)" : "")
                    : "Fix it before saving - Save refuses while anything clashes";

            text.drawShadowed(more,
                    layout.textLeft(8), layout.textTop(TextureLabLayout.KEY_CONFLICT_Y2),
                    Palette.TEXT_WARNING, Palette.TEXT_SHADOW);
        }

        lab.centered(layout, TextureLabLayout.KEYBIND_SAVE, "Save keys");
        lab.centered(layout, TextureLabLayout.KEYBIND_RESET, "Defaults");
        lab.centered(layout, TextureLabLayout.KEYBIND_CLOSE, "Close  (Esc)");

        text.end();
    }

    /** Nápověda dole podle toho, na čem je myš. */
    @Override
    public String help(TextureLabLayout layout, double mouseX, double mouseY)
    {
        if(arming != null)
        {
            return "Press the key you want for " + arming.label() + " - Esc cancels";
        }

        Keybinds.Action[] actions = Keybinds.Action.values();
        int index = layout.keyButtonAt(mouseX, mouseY, actions.length);

        if(index >= 0)
        {
            return actions[index].label() + " is " + Keybinds.keyName(draft.key(actions[index]))
                    + " - click to rebind";
        }

        if(layout.hit(TextureLabLayout.KEYBIND_SAVE, mouseX, mouseY))
        {
            return "Writes " + SafeFiles.shown(Keybinds.FILE)
                    + " and the keys work right away, no restart";
        }

        if(layout.hit(TextureLabLayout.KEYBIND_RESET, mouseX, mouseY))
        {
            return "Back to the keys the game had before keybinds.json existed";
        }

        return "Escape always closes screens, whatever Pause is bound to";
    }
}
