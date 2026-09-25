package mc;

/**
 * 2D vrstvy přes vyrenderovaný svět: zaměřovač, hotbar, ladicí výpis
 * a loading screen.
 *
 * Sám nic nekreslí přímo - používá sdílený Renderer2D a TextRenderer, aby
 * se shadery a buffery neduplikovaly s menu.
 *
 * ---------------------------------------------------------------------------
 * VŠECHNY ROZMĚRY JSOU V GUI PIXELECH, ne v pixelech obrazovky. Na obrazovku
 * se násobí měřítkem z Gui.scale(). Rozvržení hotbaru je převzaté
 * z Minecraftu: slot 20x20, mezi sloty dělicí čára, kolem celku 1px rámeček,
 * takže pro 9 slotů (HOTBAR_SIZE) vyjde pruh 9*20 + 2 = 182 GUI pixelů široký.
 * ---------------------------------------------------------------------------
 */
public class Hud {

    // --- zaměřovač (GUI px) ---
    private static final float CROSSHAIR_ARM   = 4f;
    private static final float CROSSHAIR_GAP   = 1f;
    private static final float CROSSHAIR_THICK = 1f;

    // --- hotbar (GUI px) ---
    private static final float SLOT_SIZE     = 20f;
    private static final float HOTBAR_BORDER = 1f;
    private static final float HOTBAR_MARGIN = 4f;

    /** Ikona bloku uvnitř slotu; zbytek je odsazení. */
    private static final float ICON_SIZE = 16f;

    /** O kolik přesahuje rámeček vybraného slotu jeho čtvereček. */
    private static final float SELECTOR_OVERHANG = 1f;

    // --- ladicí výpis (GUI px) ---
    private static final float DEBUG_MARGIN  = 3f;
    private static final float DEBUG_PADDING = 2f;

    // --- loading (GUI px) ---
    private static final float BAR_WIDTH   = 182f;
    private static final float BAR_HEIGHT  = 10f;
    private static final float BAR_BORDER  = 1f;
    private static final float TITLE_GAP   = 20f;
    private static final float PERCENT_GAP = 10f;

    private final Renderer2D shapes;
    private final TextRenderer text;
    private final BlockIcon icons;

    public Hud(Renderer2D shapes, TextRenderer text, BlockIcon icons)
    {
        this.shapes = shapes;
        this.text = text;
        this.icons = icons;
    }

    /** Volat až po vykreslení světa a obrysu bloku. */
    public void draw(int screenWidth, int screenHeight,
                     Inventory inventory, int selectedSlot, String[] debugLines)
    {
        int scale = Gui.scale(screenWidth, screenHeight);
        boolean hasDebug = debugLines != null && debugLines.length > 0;

        shapes.begin(screenWidth, screenHeight);

        drawHotbar(screenWidth, scale, selectedSlot);
        drawCrosshair(screenWidth, screenHeight, scale);

        if(hasDebug)
        {
            drawDebugPanel(screenHeight, scale, debugLines);
        }

        shapes.end();

        drawHotbarIcons(screenWidth, screenHeight, scale, inventory);
        drawHotbarCounts(screenWidth, screenHeight, scale, inventory);

        if(hasDebug)
        {
            text.begin(screenWidth, screenHeight, scale);

            float x = (DEBUG_MARGIN + DEBUG_PADDING) * scale;
            float y = (DEBUG_MARGIN + DEBUG_PADDING) * scale;

            for(String line : debugLines)
            {
                text.draw(line, x, y, Palette.TEXT);
                y += text.lineHeight();
            }

            text.end();
        }
    }

    /** Řada slotů dole uprostřed; vybraný má světlý rámeček navíc. */
    private void drawHotbar(int screenWidth, int scale, int selectedSlot)
    {
        int count = Inventory.HOTBAR_SIZE;

        float barWidth = (count * SLOT_SIZE + 2 * HOTBAR_BORDER) * scale;
        float barHeight = (SLOT_SIZE + 2 * HOTBAR_BORDER) * scale;

        float barX = Gui.snap((screenWidth - barWidth) / 2f, scale);
        float barY = HOTBAR_MARGIN * scale;

        shapes.bevelRect(barX, barY, barWidth, barHeight, HOTBAR_BORDER * scale,
                Palette.PANEL_OUTLINE, Palette.PANEL_FILL,
                Palette.PANEL_HIGHLIGHT, Palette.PANEL_SHADOW);

        float slotY = barY + HOTBAR_BORDER * scale;
        float slotSize = SLOT_SIZE * scale;

        for(int i = 0; i < count; i++)
        {
            float slotX = barX + HOTBAR_BORDER * scale + i * slotSize;

            // Dělicí čára mezi sloty. Před prvním slotem ne - tam už je rámeček.
            if(i > 0)
            {
                shapes.fillRect(slotX, slotY, scale, slotSize, Palette.SLOT_SEPARATOR);
            }
        }

        // Ikony se kreslí až po tvarech, vlastním průchodem - mají jiný
        // shader (texturovaný) než Renderer2D. Viz drawHotbarIcons().

        if(selectedSlot >= 0 && selectedSlot < count)
        {
            float selX = barX + HOTBAR_BORDER * scale + selectedSlot * slotSize;
            float over = SELECTOR_OVERHANG * scale;

            shapes.border(selX - over, slotY - over,
                    slotSize + 2 * over, slotSize + 2 * over,
                    scale, Palette.SELECTOR);
        }
    }


    /**
     * Čtyři ramena s mezerou uprostřed. Mezera tam není jen kvůli vzhledu:
     * v invertujícím míchání by se překrývající ramena vyinvertovala dvakrát
     * a průsečík by zmizel.
     */
    private void drawCrosshair(int screenWidth, int screenHeight, int scale)
    {
        float cx = Gui.snap(screenWidth / 2f, scale);
        float cy = Gui.snap(screenHeight / 2f, scale);

        float arm = CROSSHAIR_ARM * scale;
        float gap = CROSSHAIR_GAP * scale;
        float thick = CROSSHAIR_THICK * scale;
        float half = thick / 2f;

        shapes.beginInvertBlend();

        shapes.fillRect(cx - gap - arm, cy - half, arm, thick, 1f, 1f, 1f, 1f);
        shapes.fillRect(cx + gap, cy - half, arm, thick, 1f, 1f, 1f, 1f);
        shapes.fillRect(cx - half, cy - gap - arm, thick, arm, 1f, 1f, 1f, 1f);
        shapes.fillRect(cx - half, cy + gap, thick, arm, 1f, 1f, 1f, 1f);

        shapes.endInvertBlend();
    }

    /** Kostky bloků v hotbaru. Vlastní průchod kvůli texturovanému shaderu. */
    private void drawHotbarIcons(int screenWidth, int screenHeight, int scale,
                                 Inventory inventory)
    {
        float barWidth = (Inventory.HOTBAR_SIZE * SLOT_SIZE + 2 * HOTBAR_BORDER) * scale;
        float barX = Gui.snap((screenWidth - barWidth) / 2f, scale);
        float slotY = HOTBAR_MARGIN * scale + HOTBAR_BORDER * scale;
        float inset = (SLOT_SIZE - ICON_SIZE) / 2f * scale;

        icons.begin(screenWidth, screenHeight);

        for(int i = 0; i < Inventory.HOTBAR_SIZE; i++)
        {
            ItemStack stack = inventory.hotbar(i);

            if(stack.isEmpty())
            {
                continue;
            }

            float slotX = barX + HOTBAR_BORDER * scale + i * SLOT_SIZE * scale;
            icons.draw(slotX + inset, slotY + inset, ICON_SIZE * scale, stack.id());
        }

        icons.end();

        // Pruhy výdrže opotřebených nástrojů - až po ikonách, ať leží nad nimi.
        shapes.begin(screenWidth, screenHeight);

        for(int i = 0; i < Inventory.HOTBAR_SIZE; i++)
        {
            float slotX = barX + HOTBAR_BORDER * scale + i * SLOT_SIZE * scale;
            Durability.draw(shapes, slotX + inset, slotY + inset, ICON_SIZE * scale, inventory.hotbar(i));
        }

        shapes.end();
    }

    /** Počty kusů v hotbaru, vpravo dole ve slotu jako v Minecraftu. */
    private void drawHotbarCounts(int screenWidth, int screenHeight, int scale,
                                  Inventory inventory)
    {
        float barWidth = (Inventory.HOTBAR_SIZE * SLOT_SIZE + 2 * HOTBAR_BORDER) * scale;
        float barX = Gui.snap((screenWidth - barWidth) / 2f, scale);
        float slotY = HOTBAR_MARGIN * scale + HOTBAR_BORDER * scale;

        text.begin(screenWidth, screenHeight, scale);

        for(int i = 0; i < Inventory.HOTBAR_SIZE; i++)
        {
            ItemStack stack = inventory.hotbar(i);

            if(stack.isEmpty() || stack.count() <= 1)
            {
                continue;
            }

            String label = Integer.toString(stack.count());
            float slotX = barX + HOTBAR_BORDER * scale + i * SLOT_SIZE * scale;

            text.drawShadowed(label,
                    slotX + (SLOT_SIZE - 1) * scale - text.widthOf(label),
                    screenHeight - (slotY + text.lineHeight()) - scale,
                    Palette.TEXT, Palette.TEXT_SHADOW);
        }

        text.end();
    }

    /** Podklad pod ladicí výpis, aby byl čitelný i nad světlým terénem. */
    private void drawDebugPanel(int screenHeight, int scale, String[] lines)
    {
        int widest = 0;
        for(String line : lines)
        {
            widest = Math.max(widest, text.font().textWidth(line));
        }

        float width = widest * scale + 2 * DEBUG_PADDING * scale;
        float height = lines.length * text.font().lineHeight() * scale + 2 * DEBUG_PADDING * scale;

        float margin = DEBUG_MARGIN * scale;

        // Text se sází shora dolů, tvary mají počátek dole - proto se y překlápí.
        shapes.fillRect(margin, screenHeight - margin - height, width, height,
                Palette.PANEL_FILL);
    }

    /**
     * Modrý filtr přes celou obrazovku pod vodou. Volat po světě, před HUD -
     * zaměřovač ani hotbar se tónovat nemají.
     */
    public void drawUnderwaterTint(int screenWidth, int screenHeight)
    {
        shapes.begin(screenWidth, screenHeight);
        shapes.fillRect(0, 0, screenWidth, screenHeight, Palette.UNDERWATER_TINT);
        shapes.end();
    }

    /** Loading screen s ukazatelem postupu. progress je 0..1. */
    public void drawLoading(int screenWidth, int screenHeight, String title, float progress)
    {
        int scale = Gui.scale(screenWidth, screenHeight);

        float barWidth = BAR_WIDTH * scale;
        float barHeight = BAR_HEIGHT * scale;
        float barX = Gui.snap((screenWidth - barWidth) / 2f, scale);
        float barY = Gui.snap(screenHeight / 2f - barHeight / 2f, scale);

        float inner = BAR_BORDER * scale;
        float filledWidth = Math.round((barWidth - 2 * inner) * clamp(progress) / scale) * scale;

        shapes.begin(screenWidth, screenHeight);

        shapes.fillRect(0, 0, screenWidth, screenHeight, Palette.LOADING_BACKDROP);

        shapes.bevelRect(barX, barY, barWidth, barHeight, inner,
                Palette.PANEL_OUTLINE, Palette.BAR_TRACK,
                Palette.PANEL_SHADOW, Palette.PANEL_HIGHLIGHT);

        if(filledWidth > 0f)
        {
            shapes.fillRect(barX + inner, barY + inner,
                    filledWidth, barHeight - 2 * inner, Palette.BAR_FILL);
        }

        shapes.end();

        // Nadpis je stejný font, jen kreslený dvojnásobným měřítkem.
        text.begin(screenWidth, screenHeight, scale * 2);
        text.drawCenteredShadowed(title, screenWidth / 2f,
                screenHeight - (barY + barHeight) - TITLE_GAP * scale,
                Palette.TEXT, Palette.TEXT_SHADOW);
        text.end();

        text.begin(screenWidth, screenHeight, scale);
        text.drawCenteredShadowed(Math.round(clamp(progress) * 100) + "%",
                screenWidth / 2f, screenHeight - barY + PERCENT_GAP * scale,
                Palette.TEXT_MUTED, Palette.TEXT_SHADOW);
        text.end();
    }

    private static float clamp(float value)
    {
        if(value < 0f) return 0f;
        if(value > 1f) return 1f;
        return value;
    }
}
