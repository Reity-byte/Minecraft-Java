package mc;

import java.util.Locale;

/**
 * Ore / Biome Tuner: čísla generátoru po biomech do `biome_tuning.json`.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ TUNER MĚNÍ JEN ČÍSLA, NIKDY BLOKY. Nejde tu vybrat, co se má pokládat -
 * dub zůstane dub a sníh sníh. Důvod je tentýž, proč musí být bloky
 * generátoru vestavěné a ne z `blocks.json`: generátor musí fungovat i bez
 * nepovinného souboru. Viz `BiomeTuning`.
 *
 * ⚠️ NÁHLED JE JEDEN STROM, NE KUS KRAJINY. Náhled celého terénu by znamenal
 * generovat a mešovat stovky sloupců při každém kliknutí na [+] - a přitom
 * by se na malém obrázku stejně nepoznalo, že se amplituda změnila o dva.
 * Jeden strom je naopak přesně to, co jde na rozsazích vidět, a staví se
 * tímtéž kódem, jakým ho staví generátor (`TreeShape` → `ChunkMesh`), takže
 * to, co je vidět, ve světě opravdu vyroste.
 *
 * ⚠️ ZMĚNA ČÍSLA PŘESTAVÍ NÁHLED, ALE NEPŘELOSUJE HO. Kdyby se s každým
 * kliknutím losovalo znovu, nešlo by poznat, co udělala úprava a co kostka.
 * Nové losování je na vlastním tlačítku (Reroll) - viz `TreePreview`.
 *
 * ⚠️ TUNING SE PROJEVÍ AŽ U PŘÍŠTÍHO SVĚTA, ne v rozehraném. Generátor je
 * neměnný a bere si tuning při svém vzniku; kdyby ho četl za běhu, přestaly
 * by na sebe sousední sloupce navazovat uprostřed mapy. Říká to i text pod
 * tlačítky, aby to nebylo překvapení.
 * ---------------------------------------------------------------------------
 *
 * Logika bez GL je v `BiomeTuning` a `TextureLabLayout`; tady je kreslení,
 * vstup a rozepsaný tuning.
 */
public final class BiomeTunerLab implements LabMode {

    /** Řádky čísel, v pořadí, v jakém se kreslí. */
    enum Row {
        BASE       ("Base height",  1),
        AMPLITUDE  ("Amplitude",    1),
        TREE_DENSITY("Trees / 16",  1),
        TRUNK_MIN  ("Trunk min",    1),
        TRUNK_MAX  ("Trunk max",    1),
        CROWN_MIN  ("Crown min",    1),
        CROWN_MAX  ("Crown max",    1),
        IRON       ("Iron x",       1),
        COAL       ("Coal x",       1);

        final String label;
        final int step;

        Row(String label, int step)
        {
            this.label = label;
            this.step = step;
        }
    }

    /** O kolik se hýbe násobek rudy na jedno kliknutí. */
    private static final double ORE_STEP = 0.25;

    private final Renderer2D shapes;
    private final TextRenderer text;
    private final TextureLab lab;

    /** Rozepsaný tuning. V generátoru platí až po Save. */
    private BiomeTuning draft = BiomeTuning.active();

    private Biome selected = Biome.PLAINS;

    /**
     * Náhled se staví líně, až když se do módu poprvé vstoupí: postavit
     * malý svět stojí pár desítek milisekund a bylo by škoda je zaplatit
     * při startu hry za mód, do kterého uživatel nemusí vůbec vlézt.
     */
    private TreePreview preview = null;

    /**
     * Generátor, kterým se náhled ptá na výšku kmene a poloměr koruny.
     * Staví se znovu při každé změně tuningu - je to jen šumová tabulka
     * a pár polí, takže je to levnější než držet dva zdroje pravdy.
     */
    private TerrainGenerator generator = new TerrainGenerator(World.DEFAULT_SEED, draft);

    public BiomeTunerLab(TextureLab lab, Renderer2D shapes, TextRenderer text)
    {
        this.lab = lab;
        this.shapes = shapes;
        this.text = text;
    }

    @Override
    public String title()
    {
        return "Biomes";
    }

    @Override
    public String hint()
    {
        return "Tune terrain, trees and ore per biome - numbers only, never blocks";
    }

    /**
     * Ikona: kopec s jehličnanem. Obdélníky a čtyřúhelníky z `Renderer2D`,
     * takže nevyprázdní dávku bočního panelu (viz "Výkon labu").
     */
    @Override
    public void drawIcon(Renderer2D shapes, float left, float bottom, float size)
    {
        float unit = size / 9f;
        float[] ground = {0.36f, 0.55f, 0.23f, 1f};
        float[] rock = {0.55f, 0.55f, 0.58f, 1f};
        float[] leaves = {0.16f, 0.42f, 0.20f, 1f};
        float[] trunk = {0.42f, 0.30f, 0.18f, 1f};

        // hora vzadu
        shapes.fillQuad(left + 4.6f * unit, bottom + 7.4f * unit,
                left + 2.2f * unit, bottom + 3f * unit,
                left + 7.6f * unit, bottom + 3f * unit,
                left + 7.6f * unit, bottom + 3f * unit, rock);

        // země
        shapes.fillRect(left + unit, bottom + 1.6f * unit, 7 * unit, 1.6f * unit, ground);

        // strom vpředu
        shapes.fillRect(left + 2.6f * unit, bottom + 2.6f * unit, 0.9f * unit, 2.2f * unit, trunk);
        shapes.fillQuad(left + 3.05f * unit, bottom + 6.6f * unit,
                left + 1.4f * unit, bottom + 4.2f * unit,
                left + 4.7f * unit, bottom + 4.2f * unit,
                left + 4.7f * unit, bottom + 4.2f * unit, leaves);
    }

    @Override
    public void onEnter()
    {
        draft = BiomeTuning.active();
        generator = new TerrainGenerator(World.DEFAULT_SEED, draft);

        if(preview == null)
        {
            preview = new TreePreview();
        }

        refreshPreview();
    }

    @Override
    public void update(float dt)
    {
        if(preview != null)
        {
            preview.update(dt);
        }
    }

    // ------------------------------------------------------------------
    // rozepsaný tuning
    // ------------------------------------------------------------------

    BiomeTuning draft()
    {
        return draft;
    }

    Biome selected()
    {
        return selected;
    }

    void select(Biome biome)
    {
        if(biome == selected)
        {
            return;
        }

        selected = biome;
        refreshPreview();
        lab.say(name(biome) + ": " + describeTrees(draft.tune(biome), biome));
    }

    /**
     * Posune jedno číslo vybraného biomu.
     *
     * ⚠️ MIN A MAX SE TLAČÍ NAVZÁJEM. Zvednout min nad max je v rozsahu
     * nesmysl, ale ZAKÁZAT to by znamenalo, že se rozsah nedá posunout
     * nahoru jinak než "nejdřív max, pak min" - a na to uživatel nepřijde.
     * Místo toho si min vytáhne max s sebou (a naopak), takže rozsah jede
     * nahoru celý a nikdy se nerozbije.
     */
    void step(Row row, int direction)
    {
        BiomeTuning.Tune t = draft.tune(selected);

        int base = t.baseHeight(), amp = t.amplitude(), density = t.treeDensity();
        int trunkMin = t.trunkMin(), trunkMax = t.trunkMax();
        int crownMin = t.crownMin(), crownMax = t.crownMax();
        double iron = t.ironDensity(), coal = t.coalDensity();

        switch(row)
        {
            case BASE        -> base += direction * row.step;
            case AMPLITUDE   -> amp += direction * row.step;
            case TREE_DENSITY-> density += direction * row.step;

            case TRUNK_MIN -> {
                trunkMin += direction * row.step;
                trunkMax = Math.max(trunkMax, trunkMin);
            }
            case TRUNK_MAX -> {
                trunkMax += direction * row.step;
                trunkMin = Math.min(trunkMin, trunkMax);
            }
            case CROWN_MIN -> {
                crownMin += direction * row.step;
                crownMax = Math.max(crownMax, crownMin);
            }
            case CROWN_MAX -> {
                crownMax += direction * row.step;
                crownMin = Math.min(crownMin, crownMax);
            }

            case IRON -> iron = round2(iron + direction * ORE_STEP);
            case COAL -> coal = round2(coal + direction * ORE_STEP);
        }

        draft = draft.with(selected, new BiomeTuning.Tune(base, amp, density,
                trunkMin, trunkMax, crownMin, crownMax, iron, coal));

        // Generátor se musí postavit znovu: dosah koruny i strop terénu
        // si z tuningu počítá při svém vzniku.
        generator = new TerrainGenerator(World.DEFAULT_SEED, draft);
        refreshPreview();
    }

    /** Zaokrouhlení na dvě desetinná místa - v souboru je násobek s %.2f. */
    private static double round2(double value)
    {
        return Math.round(value * 100.0) / 100.0;
    }

    private void refreshPreview()
    {
        if(preview != null)
        {
            preview.show(selected, draft, generator);
        }
    }

    void reroll()
    {
        if(preview == null)
        {
            return;
        }

        preview.reroll();
        refreshPreview();
        lab.say("Another random tree from the same range: "
                + describeShown());
    }

    /**
     * Uloží tuning do souboru A ZÁROVEŇ ho aktivuje.
     *
     * ⚠️ Pořadí je "zapsat, pak aktivovat", jako u receptů a kláves.
     * Aktivace se ale na rozdíl od nich neprojeví v rozehraném světě -
     * generátor už svůj tuning má. Hláška to říká rovnou, aby se uživatel
     * nedíval z okna a nečekal jiné hory.
     */
    void save()
    {
        if(!draft.save(BiomeTuning.FILE))
        {
            lab.say("Could not write " + BiomeTuning.FILE.toString().replace('\\', '/'));
            return;
        }

        BiomeTuning.activate(draft);
        lab.say("Saved - applies to the next world you create or load, not this one");
    }

    void reset()
    {
        draft = BiomeTuning.defaults();
        generator = new TerrainGenerator(World.DEFAULT_SEED, draft);
        refreshPreview();
        lab.say("Back to the built-in numbers - Save to keep it");
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

        Biome[] biomes = Biome.values();
        int tab = layout.biomeTabAt(mouseX, mouseY, biomes.length);

        if(tab >= 0)
        {
            select(biomes[tab]);
            return false;
        }

        Row[] rows = Row.values();

        int less = layout.tuneLessAt(mouseX, mouseY, rows.length);

        if(less >= 0)
        {
            step(rows[less], -1);
            return false;
        }

        int more = layout.tuneMoreAt(mouseX, mouseY, rows.length);

        if(more >= 0)
        {
            step(rows[more], +1);
            return false;
        }

        if(layout.hit(TextureLabLayout.TUNE_REROLL, mouseX, mouseY))
        {
            reroll();
            return false;
        }

        if(layout.hit(TextureLabLayout.TUNE_SAVE, mouseX, mouseY))
        {
            save();
            return false;
        }

        if(layout.hit(TextureLabLayout.TUNE_RESET, mouseX, mouseY))
        {
            reset();
            return false;
        }

        if(layout.hit(TextureLabLayout.TUNE_CLOSE, mouseX, mouseY))
        {
            return true;
        }

        return false;
    }

    /** Kolečko přepíná biom - je to jediný seznam, kterým se tu roluje. */
    void scroll(double yoffset)
    {
        Biome[] biomes = Biome.values();
        int index = selected.ordinal() - (int) Math.signum(yoffset);

        select(biomes[Math.max(0, Math.min(biomes.length - 1, index))]);
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

        Biome[] biomes = Biome.values();

        for(int i = 0; i < biomes.length; i++)
        {
            TextureLabLayout.Rect r = TextureLabLayout.biomeTab(i);
            lab.button(layout, screenHeight, r, mouseX, mouseY);

            if(biomes[i] == selected)
            {
                lab.outline(layout, screenHeight, r, scale, Palette.SELECTOR);
            }
        }

        for(int row = 0; row < Row.values().length; row++)
        {
            lab.button(layout, screenHeight, TextureLabLayout.tuneLess(row), mouseX, mouseY);
            lab.button(layout, screenHeight, TextureLabLayout.tuneMore(row), mouseX, mouseY);
            lab.sunken(layout, screenHeight, TextureLabLayout.tuneValue(row));
        }

        lab.sunken(layout, screenHeight, TextureLabLayout.TREE_PREVIEW);

        lab.button(layout, screenHeight, TextureLabLayout.TUNE_SAVE, mouseX, mouseY);
        lab.button(layout, screenHeight, TextureLabLayout.TUNE_RESET, mouseX, mouseY);
        lab.button(layout, screenHeight, TextureLabLayout.TUNE_REROLL, mouseX, mouseY);
        lab.button(layout, screenHeight, TextureLabLayout.TUNE_CLOSE, mouseX, mouseY);

        shapes.end();

        // Náhled má vlastní shader a viewport, takže jde AŽ PO dávce tvarů -
        // jinak by se dávka musela uprostřed vyprázdnit.
        if(preview != null)
        {
            TextureLabLayout.Rect r = TextureLabLayout.TREE_PREVIEW;

            preview.draw(lab.atlasTexture(),
                    (int) layout.screenX(r), (int) layout.screenBottom(r, screenHeight),
                    r.w() * scale, r.h() * scale, screenWidth, screenHeight);
        }
    }

    @Override
    public void drawText(TextureLabLayout layout, int screenWidth, int screenHeight,
                         double mouseX, double mouseY)
    {
        int scale = layout.scale();
        text.begin(screenWidth, screenHeight, scale);

        String file = BiomeTuning.FILE.toString().replace('\\', '/');
        lab.label(layout, 8, TextureLabLayout.TITLE_Y,
                "Lab   tuning: " + file + "   "
                        + (draft.isDefault() ? "built-in numbers" : "custom numbers"));

        Biome[] biomes = Biome.values();

        for(int i = 0; i < biomes.length; i++)
        {
            lab.centered(layout, TextureLabLayout.biomeTab(i), shortName(biomes[i]));
        }

        BiomeTuning.Tune t = draft.tune(selected);
        Row[] rows = Row.values();

        for(int row = 0; row < rows.length; row++)
        {
            boolean inert = isInert(rows[row], selected);

            text.drawShadowed(rows[row].label, layout.textLeft(8),
                    layout.textTop(TextureLabLayout.tuneLabelY(row)),
                    inert ? Palette.TEXT_MUTED : Palette.TEXT, Palette.TEXT_SHADOW);

            lab.centered(layout, TextureLabLayout.tuneLess(row), "-");
            lab.centered(layout, TextureLabLayout.tuneMore(row), "+");
            lab.centered(layout, TextureLabLayout.tuneValue(row), value(t, rows[row]));
        }

        lab.centered(layout, TextureLabLayout.TUNE_SAVE, "Save tuning");
        lab.centered(layout, TextureLabLayout.TUNE_RESET, "Defaults");
        lab.centered(layout, TextureLabLayout.TUNE_REROLL, "Reroll tree");
        lab.centered(layout, TextureLabLayout.TUNE_CLOSE, "Close  (Esc)");

        lab.label(layout, 8, TextureLabLayout.TUNE_INFO_Y,
                name(selected) + ": " + describeShown()
                        + "   -   saved tuning applies to the NEXT world, not this one");

        text.end();
    }

    /**
     * Je to číslo v tomhle biomu k ničemu? Poušť nemá stromy, takže jí
     * rozsah kmene nic neudělá - šedý popisek to řekne dřív, než uživatel
     * dvacetkrát klikne na [+] a bude se divit, že se náhled nehýbe.
     */
    private static boolean isInert(Row row, Biome biome)
    {
        boolean treeRow = row == Row.TRUNK_MIN || row == Row.TRUNK_MAX
                || row == Row.CROWN_MIN || row == Row.CROWN_MAX
                || row == Row.TREE_DENSITY;

        return treeRow && biome.treeType() == Biome.TreeType.NONE;
    }

    private static String value(BiomeTuning.Tune t, Row row)
    {
        return switch(row)
        {
            case BASE         -> String.valueOf(t.baseHeight());
            case AMPLITUDE    -> String.valueOf(t.amplitude());
            case TREE_DENSITY -> String.valueOf(t.treeDensity());
            case TRUNK_MIN    -> String.valueOf(t.trunkMin());
            case TRUNK_MAX    -> String.valueOf(t.trunkMax());
            case CROWN_MIN    -> String.valueOf(t.crownMin());
            case CROWN_MAX    -> String.valueOf(t.crownMax());
            case IRON         -> String.format(Locale.ROOT, "%.2f", t.ironDensity());
            case COAL         -> String.format(Locale.ROOT, "%.2f", t.coalDensity());
        };
    }

    /** Co je zrovna v náhledu - konkrétní strom, ne rozsah. */
    private String describeShown()
    {
        if(preview == null || preview.shownType() == Biome.TreeType.NONE)
        {
            return "no trees in this biome";
        }

        return "trunk " + preview.shownTrunk() + ", crown radius " + preview.shownRadius()
                + " (range " + describeTrees(draft.tune(selected), selected) + ")";
    }

    private static String describeTrees(BiomeTuning.Tune t, Biome biome)
    {
        if(biome.treeType() == Biome.TreeType.NONE || t.treeDensity() == 0)
        {
            return "no trees";
        }

        return "trunk " + t.trunkMin() + "-" + t.trunkMax()
                + ", crown " + t.crownMin() + "-" + t.crownMax();
    }

    /** Jméno biomu do titulku a hlášek. */
    static String name(Biome biome)
    {
        return switch(biome)
        {
            case PLAINS -> "Plains";
            case DESERT -> "Desert";
            case JUNGLE -> "Jungle";
            case BIRCH_FOREST -> "Birch forest";
            case TAIGA -> "Taiga";
            case TUNDRA -> "Tundra";
            case HILLS -> "Hills";
            case MOUNTAINS -> "Mountains";
        };
    }

    /** Jméno do záložky - 52 GUI pixelů unese zhruba osm znaků. */
    static String shortName(Biome biome)
    {
        return switch(biome)
        {
            case PLAINS -> "Plains";
            case DESERT -> "Desert";
            case JUNGLE -> "Jungle";
            case BIRCH_FOREST -> "Birch";
            case TAIGA -> "Taiga";
            case TUNDRA -> "Tundra";
            case HILLS -> "Hills";
            case MOUNTAINS -> "Mounts";
        };
    }

    /** Nápověda dole podle toho, na čem je myš. */
    String help(TextureLabLayout layout, double mouseX, double mouseY)
    {
        if(layout.biomeTabAt(mouseX, mouseY, Biome.values().length) >= 0)
        {
            return "Pick a biome - mouse wheel switches too";
        }

        Row[] rows = Row.values();
        int row = layout.tuneLessAt(mouseX, mouseY, rows.length);

        if(row < 0)
        {
            row = layout.tuneMoreAt(mouseX, mouseY, rows.length);
        }

        if(row >= 0)
        {
            return helpFor(rows[row]);
        }

        if(layout.hit(TextureLabLayout.TREE_PREVIEW, mouseX, mouseY))
        {
            return "One real tree from this biome, built by the generator itself";
        }

        if(layout.hit(TextureLabLayout.TUNE_REROLL, mouseX, mouseY))
        {
            return "Another random tree from the same range - the numbers stay put";
        }

        if(layout.hit(TextureLabLayout.TUNE_SAVE, mouseX, mouseY))
        {
            return "Writes " + BiomeTuning.FILE.toString().replace('\\', '/')
                    + " - new numbers apply to the next world, not this one";
        }

        return "Numbers only: the tuner never picks which blocks the generator places";
    }

    private static String helpFor(Row row)
    {
        return switch(row)
        {
            case BASE -> "Height the terrain sits around before the noise is added";
            case AMPLITUDE -> "How far the noise swings the terrain up and down";
            case TREE_DENSITY -> "Cells out of 16 that carry a tree - 0 is a biome with none";
            case TRUNK_MIN, TRUNK_MAX ->
                    "Trunk height range - min below max makes trees of that biome differ";
            case CROWN_MIN, CROWN_MAX ->
                    "Crown radius range - this is what stops every tree looking the same";
            case IRON -> "Iron vein density - 3 means three times as many veins as normal";
            case COAL -> "Coal vein density - 1 is what the world has everywhere today";
        };
    }

    public void delete()
    {
        if(preview != null)
        {
            preview.delete();
            preview = null;
        }
    }
}
