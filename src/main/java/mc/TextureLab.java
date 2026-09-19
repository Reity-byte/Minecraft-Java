package mc;

import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.GL_REPEAT;

/**
 * Texture lab: vývojářská obrazovka na úpravy dlaždic atlasu bloků.
 *
 * ---------------------------------------------------------------------------
 * Vlevo přehled atlasu (klik vybere dlaždici), uprostřed dlaždice zvětšená
 * jako plátno (levé tlačítko maluje, pravé bere barvu), vpravo živý 3D náhled
 * bloku, který dlaždici používá, a pod tím paleta, HSV posuvníky, hex
 * a tlačítka Save / Revert / Close.
 *
 * ⚠️ Smysl celé obrazovky je živý náhled přes SKUTEČNÝ shader. Dlaždice
 * 16x16 vypadá na plátně jinak než na bloku: boky jsou ztmavené na 0,6 a 0,8,
 * spodek na 0,5, a vzor, který se na plátně jeví jako nenápadný šum, se na
 * šesti stěnách vedle sebe opakuje jako tapeta. Malovat naslepo a pak
 * restartovat hru, aby bylo vidět, jak to dopadlo, je přesně to, čemu lab
 * zabraňuje. Každá změna pixelu se hned nahraje do TÉŽE textury atlasu, ze
 * které kreslí svět, a kostka v náhledu (BlockPreview) se změní ve stejném
 * framu - viz AtlasEditor a Texture.update().
 *
 * Všechno bez GL (souřadnice, malování, barvy, soubory) je v AtlasEditor,
 * TextureLabLayout a AtlasImage; tady je jen kreslení a vstup.
 * ---------------------------------------------------------------------------
 */
public class TextureLab {

    /**
     * Pevný první řádek palety: průhledná (guma), odstíny šedi a pár sytých
     * barev. Druhý řádek jsou nejčastější barvy vybrané dlaždice - pixel-art
     * se maluje hlavně odstíny, které v dlaždici už jsou.
     */
    static final int[] BASIC_COLORS = {
            0x00000000, 0xFF000000, 0xFF3F3F3F, 0xFF7F7F7F, 0xFFBFBFBF, 0xFFFFFFFF,
            0xFFB02E26, 0xFFF9801D, 0xFFFED83D, 0xFF5E7C16, 0xFF3C44AA, 0xFF835432
    };

    private static final int HSV_SEGMENTS = 32;
    private static final float STATUS_SECONDS = 4f;

    private static final float[] GRID_LINE  = {0f, 0f, 0f, 0.22f};
    private static final float[] TILE_LINE  = {0f, 0f, 0f, 0.35f};
    private static final float[] HOVER      = {1f, 1f, 1f, 0.8f};
    private static final float[] SWATCH_BASE = {0.45f, 0.45f, 0.45f, 1f};
    private static final float[] GUM_CHECK   = {0.75f, 0.75f, 0.75f, 1f};
    private static final float[] SKY = {WorldRenderer.SKY_R, WorldRenderer.SKY_G, WorldRenderer.SKY_B, 1f};

    private final AtlasEditor editor;
    private final Texture atlas;
    private final Renderer2D shapes;
    private final TextRenderer text;

    // Vlastní GL prostředky labu - vznikají s ním a s ním se mažou.
    private final ImageRenderer images = new ImageRenderer();
    private final Texture checker;
    private final BlockPreview preview = new BlockPreview();

    /** Odkud atlas pochází: true = textures/atlas.png, false = procedurální. */
    private boolean fromFile;

    /** Kolikátý z bloků, které dlaždici používají, je v náhledu. */
    private int previewChoice = 0;

    /**
     * Aktuální barva jako HSV. Drží se zvlášť, ne jen jako přepočet z barvy:
     * u šedé by se jinak odstín ztratil a posuvník odstínu by po každém
     * pohybu sytosti skočil na červenou.
     */
    private float hue = 0f, saturation = 0f, value = 0f;

    private boolean painting = false;
    private TextureLabLayout.Rect draggedSlider = null;

    /** Rozepsaný hex, nebo null, když se nepíše. */
    private StringBuilder hexInput = null;

    private String status = "";
    private float statusLeft = 0f;

    private final int[] palette = new int[TextureLabLayout.SWATCH_COLUMNS * TextureLabLayout.SWATCH_ROWS];

    /** Kolik vzorků palety je platných - dlaždice může mít míň barev, než je míst. */
    private int paletteCount = 0;

    public TextureLab(int[] atlasPixels, Texture atlas, boolean fromFile,
                      Renderer2D shapes, TextRenderer text)
    {
        this.editor = new AtlasEditor(atlasPixels);
        this.atlas = atlas;
        this.fromFile = fromFile;
        this.shapes = shapes;
        this.text = text;

        checker = Texture.fromArgb(new int[]{0xFF9A9A9A, 0xFF6A6A6A, 0xFF6A6A6A, 0xFF9A9A9A},
                2, 2, GL_REPEAT);

        selectTile(0);
        setColor(editor.get(0, 0));
    }

    /** Pochází atlas teď ze souboru? Main to ukazuje v ladicím výpisu. */
    public boolean fromFile()
    {
        return fromFile;
    }

    // ------------------------------------------------------------------
    // stav
    // ------------------------------------------------------------------

    private void selectTile(int tile)
    {
        editor.select(tile);
        previewChoice = 0;
        refreshPreview();
    }

    private void refreshPreview()
    {
        List<Byte> blocks = AtlasEditor.blocksUsing(editor.tile());
        preview.show(blocks.isEmpty() ? World.AIR : blocks.get(previewChoice % blocks.size()));
    }

    private void setColor(int argb)
    {
        editor.setColor(argb);

        float[] hsv = AtlasEditor.toHsv(argb);

        // U šedé nemá odstín smysl a u černé ani sytost - necháme původní.
        if(hsv[1] > 0f && hsv[2] > 0f)
        {
            hue = hsv[0];
        }
        if(hsv[2] > 0f)
        {
            saturation = hsv[1];
        }
        value = hsv[2];
    }

    private void applyHsv()
    {
        // Z průhledné (gumy) přechod na HSV znamená "chci barvu" - plně krycí.
        int alpha = editor.color() >>> 24;
        editor.setColor(AtlasEditor.hsv(hue, saturation, value, alpha == 0 ? 0xFF : alpha));
    }

    private void say(String message)
    {
        status = message;
        statusLeft = STATUS_SECONDS;
    }

    private void save()
    {
        if(AtlasImage.save(editor.pixels(), Textures.ATLAS_FILE))
        {
            editor.markSaved();
            fromFile = true;
            say("Saved " + Textures.ATLAS_FILE.toString().replace('\\', '/'));
        }
        else
        {
            say("Save failed - see console");
        }
    }

    /** Zpátky k tomu, co je na disku: uložený PNG, jinak procedurální atlas. */
    private void revert()
    {
        Textures.AtlasPixels source = Textures.atlasPixels(Textures.ATLAS_FILE);
        editor.replaceAll(source.pixels());
        fromFile = source.fromFile();
        say(fromFile ? "Reverted to saved PNG" : "Reverted to procedural");
    }

    public void update(float dt)
    {
        preview.update(dt);
        statusLeft -= dt;
    }

    // ------------------------------------------------------------------
    // vstup
    // ------------------------------------------------------------------

    /** Zmáčknutí tlačítka myši. Vrací true, když se kliklo na Close. */
    public boolean press(double mouseX, double mouseY, int screenWidth, int screenHeight, boolean left)
    {
        TextureLabLayout layout = new TextureLabLayout(screenWidth, screenHeight);

        // Klik jinam ukončí psaní hexu - platný se použije, neplatný zahodí.
        if(hexInput != null && !layout.hit(TextureLabLayout.HEX, mouseX, mouseY))
        {
            commitHex();
        }

        int[] pixel = layout.canvasPixelAt(mouseX, mouseY);

        if(pixel != null)
        {
            if(left)
            {
                painting = true;
                editor.beginStroke(pixel[0], pixel[1]);
            }
            else
            {
                setColor(editor.pick(pixel[0], pixel[1]));
                say("Picked " + AtlasEditor.toHex(editor.color()));
            }
            return false;
        }

        if(!left)
        {
            return false;
        }

        int tile = layout.tileAt(mouseX, mouseY);

        if(tile >= 0)
        {
            selectTile(tile);
            return false;
        }

        int swatch = layout.swatchAt(mouseX, mouseY);

        if(swatch >= 0)
        {
            if(swatch < paletteCount)
            {
                setColor(palette[swatch]);
            }
            return false;
        }

        for(TextureLabLayout.Rect bar : new TextureLabLayout.Rect[]{
                TextureLabLayout.HUE, TextureLabLayout.SATURATION, TextureLabLayout.VALUE})
        {
            if(layout.hit(bar, mouseX, mouseY))
            {
                draggedSlider = bar;
                slide(layout, mouseX);
                return false;
            }
        }

        if(layout.hit(TextureLabLayout.HEX, mouseX, mouseY))
        {
            hexInput = new StringBuilder(AtlasEditor.toHex(editor.color()).substring(1));
        }
        else if(layout.hit(TextureLabLayout.PREVIEW, mouseX, mouseY))
        {
            // Dlaždici může používat víc bloků (hlína: hlína a spodek trávy).
            previewChoice++;
            refreshPreview();
        }
        else if(layout.hit(TextureLabLayout.SAVE, mouseX, mouseY))
        {
            save();
        }
        else if(layout.hit(TextureLabLayout.REVERT, mouseX, mouseY))
        {
            revert();
        }

        return layout.hit(TextureLabLayout.CLOSE, mouseX, mouseY);
    }

    /** Pohyb myši s drženým tlačítkem. */
    public void drag(double mouseX, double mouseY, int screenWidth, int screenHeight)
    {
        TextureLabLayout layout = new TextureLabLayout(screenWidth, screenHeight);

        if(painting)
        {
            // Mimo plátno se tah přitiskne k okraji, ať se čára u kraje neutrhne.
            float gx = layout.guiX(mouseX) - TextureLabLayout.CANVAS.x();
            float gy = layout.guiY(mouseY) - TextureLabLayout.CANVAS.y();

            int x = clamp((int) Math.floor(gx / TextureLabLayout.CANVAS_PIXEL));
            int y = AtlasEditor.TILE - 1 - clamp((int) Math.floor(gy / TextureLabLayout.CANVAS_PIXEL));

            editor.strokeTo(x, y);
        }
        else if(draggedSlider != null)
        {
            slide(layout, mouseX);
        }
    }

    public void release()
    {
        if(painting)
        {
            editor.endStroke();
            painting = false;
        }

        draggedSlider = null;
    }

    /**
     * Klávesa. Vrací true, když se má lab zavřít (Esc, F6).
     * Při psaní hexu patří klávesy poli, ne zkratkám.
     */
    public boolean key(int key, int mods)
    {
        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        if(hexInput != null)
        {
            if(key == GLFW_KEY_ESCAPE)
            {
                hexInput = null;
            }
            else if(key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER)
            {
                commitHex();
            }
            else if(key == GLFW_KEY_BACKSPACE && hexInput.length() > 0)
            {
                hexInput.setLength(hexInput.length() - 1);
            }
            else if(hexInput.length() < 8)
            {
                char digit = hexDigit(key);

                if(digit != 0)
                {
                    hexInput.append(digit);
                }
            }
            return false;
        }

        if(ctrl && key == GLFW_KEY_Z)
        {
            say(editor.undo() ? "Undo" : "Nothing to undo");
            refreshPreview();
            return false;
        }

        if(ctrl && key == GLFW_KEY_S)
        {
            save();
            return false;
        }

        return key == GLFW_KEY_ESCAPE || key == GLFW_KEY_F6;
    }

    private void commitHex()
    {
        Integer parsed = AtlasEditor.parseHex(hexInput.toString());
        hexInput = null;

        if(parsed != null)
        {
            setColor(parsed);
        }
        else
        {
            say("Hex needs 6 or 8 digits");
        }
    }

    private void slide(TextureLabLayout layout, double mouseX)
    {
        float t = layout.sliderValue(draggedSlider, mouseX);

        if(draggedSlider == TextureLabLayout.HUE)
        {
            hue = t * 360f;
        }
        else if(draggedSlider == TextureLabLayout.SATURATION)
        {
            saturation = t;
        }
        else
        {
            value = t;
        }

        applyHsv();
    }

    private static char hexDigit(int key)
    {
        if(key >= GLFW_KEY_0 && key <= GLFW_KEY_9) return (char) ('0' + key - GLFW_KEY_0);
        if(key >= GLFW_KEY_KP_0 && key <= GLFW_KEY_KP_9) return (char) ('0' + key - GLFW_KEY_KP_0);
        if(key >= GLFW_KEY_A && key <= GLFW_KEY_F) return (char) ('A' + key - GLFW_KEY_A);
        return 0;
    }

    private static int clamp(int pixel)
    {
        return Math.max(0, Math.min(AtlasEditor.TILE - 1, pixel));
    }

    // ------------------------------------------------------------------
    // kreslení
    // ------------------------------------------------------------------

    public void render(int screenWidth, int screenHeight, double mouseX, double mouseY)
    {
        // Jedno nahrání za frame, ať se maluje jakkoliv rychle.
        if(editor.takeDirty())
        {
            atlas.update(editor.pixels());
        }

        TextureLabLayout layout = new TextureLabLayout(screenWidth, screenHeight);
        int scale = layout.scale();

        System.arraycopy(BASIC_COLORS, 0, palette, 0, BASIC_COLORS.length);
        int[] tileColors = editor.tileColors(palette.length - BASIC_COLORS.length);
        System.arraycopy(tileColors, 0, palette, BASIC_COLORS.length, tileColors.length);
        paletteCount = BASIC_COLORS.length + tileColors.length;

        // --- podklady ---
        shapes.begin(screenWidth, screenHeight);

        shapes.bevelRect(layout.left(), screenHeight - layout.top() - TextureLabLayout.HEIGHT * scale,
                TextureLabLayout.WIDTH * scale, TextureLabLayout.HEIGHT * scale, scale,
                Palette.PANEL_OUTLINE, Palette.CONTAINER_FILL,
                Palette.CONTAINER_HIGHLIGHT, Palette.CONTAINER_SHADOW);

        sunken(layout, screenHeight, TextureLabLayout.ATLAS);
        sunken(layout, screenHeight, TextureLabLayout.CANVAS);
        sunken(layout, screenHeight, TextureLabLayout.PREVIEW);
        fill(layout, screenHeight, TextureLabLayout.PREVIEW, SKY);

        shapes.end();

        // --- atlas a plátno přímo z textury ---
        image(layout, screenWidth, screenHeight, checker, TextureLabLayout.ATLAS, 0f, 0f, 32f, 32f);
        image(layout, screenWidth, screenHeight, atlas, TextureLabLayout.ATLAS, 0f, 0f, 1f, 1f);

        // Výřez dlaždice PŘESNĚ na hranách, ne se zúžením z BlockAtlas: to by
        // krajní pixely ukázalo poloviční. Plátno je zarovnané na celé pixely,
        // takže středy fragmentů hranu nikdy netrefí.
        int tile = editor.tile();
        float u0 = AtlasEditor.tileX0(tile) / (float) AtlasEditor.SIZE;
        float v0 = AtlasEditor.tileY0(tile) / (float) AtlasEditor.SIZE;
        float span = AtlasEditor.TILE / (float) AtlasEditor.SIZE;

        image(layout, screenWidth, screenHeight, checker, TextureLabLayout.CANVAS, 0f, 0f, 8f, 8f);
        image(layout, screenWidth, screenHeight, atlas, TextureLabLayout.CANVAS, u0, v0, u0 + span, v0 + span);

        // --- mřížky, výběr, paleta, posuvníky, tlačítka ---
        shapes.begin(screenWidth, screenHeight);

        drawGrid(layout, screenHeight, TextureLabLayout.CANVAS, AtlasEditor.TILE, GRID_LINE);
        drawGrid(layout, screenHeight, TextureLabLayout.ATLAS, AtlasEditor.TILES_PER_ROW, TILE_LINE);

        outline(layout, screenHeight, TextureLabLayout.tileRect(tile), scale, Palette.SELECTOR);

        int hoveredTile = layout.tileAt(mouseX, mouseY);
        if(hoveredTile >= 0 && hoveredTile != tile)
        {
            outline(layout, screenHeight, TextureLabLayout.tileRect(hoveredTile), 1, HOVER);
        }

        int[] hoveredPixel = layout.canvasPixelAt(mouseX, mouseY);
        if(hoveredPixel != null)
        {
            outline(layout, screenHeight,
                    TextureLabLayout.canvasPixelRect(hoveredPixel[0], hoveredPixel[1]), 1, HOVER);
        }

        for(int i = 0; i < paletteCount; i++)
        {
            TextureLabLayout.Rect r = TextureLabLayout.swatchRect(i);
            swatch(layout, screenHeight, r, palette[i]);

            if(palette[i] == editor.color())
            {
                outline(layout, screenHeight, grow(r), scale, Palette.SELECTOR);
            }
        }

        swatch(layout, screenHeight, TextureLabLayout.CURRENT, editor.color());
        sunken(layout, screenHeight, TextureLabLayout.HEX);

        drawSlider(layout, screenHeight, TextureLabLayout.HUE, hue / 360f);
        drawSlider(layout, screenHeight, TextureLabLayout.SATURATION, saturation);
        drawSlider(layout, screenHeight, TextureLabLayout.VALUE, value);

        button(layout, screenHeight, TextureLabLayout.SAVE, mouseX, mouseY);
        button(layout, screenHeight, TextureLabLayout.REVERT, mouseX, mouseY);
        button(layout, screenHeight, TextureLabLayout.CLOSE, mouseX, mouseY);

        shapes.end();

        // --- živý náhled přes světový shader ---
        TextureLabLayout.Rect p = TextureLabLayout.PREVIEW;
        preview.draw(atlas, (int) layout.screenX(p), (int) layout.screenBottom(p, screenHeight),
                p.w() * scale, p.h() * scale, screenWidth, screenHeight);

        drawTexts(layout, screenWidth, screenHeight);
    }

    private void drawTexts(TextureLabLayout layout, int screenWidth, int screenHeight)
    {
        int scale = layout.scale();
        text.begin(screenWidth, screenHeight, scale);

        String source = fromFile ? Textures.ATLAS_FILE.toString().replace('\\', '/') : "procedural";
        label(layout, 8, TextureLabLayout.TITLE_Y,
                "Texture Lab   atlas: " + source + (editor.isUnsaved() ? "  (unsaved)" : ""));

        int tile = editor.tile();
        List<Byte> blocks = AtlasEditor.blocksUsing(tile);

        label(layout, 8, TextureLabLayout.INFO_Y, "Tile " + tile + "  ("
                + BlockAtlas.column(tile) + ", " + BlockAtlas.row(tile) + ")");

        if(blocks.isEmpty())
        {
            label(layout, 8, TextureLabLayout.INFO_Y + 12, "No block uses it");
        }
        else
        {
            label(layout, 8, TextureLabLayout.INFO_Y + 12, "Used by:");

            for(int i = 0; i < Math.min(blocks.size(), 4); i++)
            {
                label(layout, 12, TextureLabLayout.INFO_Y + 24 + i * 11, blockName(blocks.get(i)));
            }

            TextureLabLayout.Rect p = TextureLabLayout.PREVIEW;
            label(layout, p.x() + 3, p.y() + 3, blockName(preview.block())
                    + (blocks.size() > 1 ? "  (click: next)" : ""));
        }

        String hex = hexInput != null ? "#" + hexInput + "_" : AtlasEditor.toHex(editor.color());
        label(layout, TextureLabLayout.HEX.x() + 3, TextureLabLayout.HEX.y() + 2, hex);

        label(layout, TextureLabLayout.HUE.x() + TextureLabLayout.HUE.w() + 4, TextureLabLayout.HUE.y() - 2, "H");
        label(layout, TextureLabLayout.SATURATION.x() + TextureLabLayout.SATURATION.w() + 4,
                TextureLabLayout.SATURATION.y() - 2, "S");
        label(layout, TextureLabLayout.VALUE.x() + TextureLabLayout.VALUE.w() + 4,
                TextureLabLayout.VALUE.y() - 2, "V");

        centered(layout, TextureLabLayout.SAVE, "Save PNG  (Ctrl+S)");
        centered(layout, TextureLabLayout.REVERT, fromFile ? "Revert to saved" : "Revert to procedural");
        centered(layout, TextureLabLayout.CLOSE, "Close  (Esc / F6)");

        if(statusLeft > 0f)
        {
            label(layout, TextureLabLayout.SAVE.x(), TextureLabLayout.STATUS_Y, status);
        }

        text.draw("LMB paint   RMB pick color   Ctrl+Z undo   click hex to type",
                layout.textLeft(8), layout.textTop(TextureLabLayout.HELP_Y), Palette.TEXT_MUTED);

        text.end();
    }

    // ------------------------------------------------------------------
    // pomocné kreslení (všechno v GUI pixelech panelu)
    // ------------------------------------------------------------------

    private void fill(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r, float[] color)
    {
        int s = l.scale();
        shapes.fillRect(l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s, r.h() * s, color);
    }

    /** Zapuštěný rámeček kolem obdélníku, jako slot v inventáři. */
    private void sunken(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r)
    {
        int s = l.scale();
        shapes.bevelRect(l.screenX(r) - s, l.screenBottom(r, screenHeight) - s,
                (r.w() + 2) * s, (r.h() + 2) * s, s,
                Palette.SLOT_OUTLINE, Palette.SLOT_FILL, Palette.SLOT_SHADOW, Palette.SLOT_HIGHLIGHT);
    }

    /** Rámeček daný tloušťkou v pixelech obrazovky (1 = tenká čára i při velkém měřítku). */
    private void outline(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r,
                         int thickness, float[] color)
    {
        int s = l.scale();
        shapes.border(l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s, r.h() * s,
                thickness, color);
    }

    private static TextureLabLayout.Rect grow(TextureLabLayout.Rect r)
    {
        return new TextureLabLayout.Rect(r.x() - 1, r.y() - 1, r.w() + 2, r.h() + 2);
    }

    /** Čáry mezi buňkami - jeden pixel obrazovky, ať nezakrývají malované pixely. */
    private void drawGrid(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r,
                          int cells, float[] color)
    {
        int s = l.scale();
        float x = l.screenX(r), y = l.screenBottom(r, screenHeight);
        float width = r.w() * s, height = r.h() * s;

        for(int i = 1; i < cells; i++)
        {
            shapes.fillRect(x + i * width / cells, y, 1, height, color);
            shapes.fillRect(x, y + i * height / cells, width, 1, color);
        }
    }

    /** Vzorek barvy. Průhlednost je vidět proti šedé pod ním. */
    private void swatch(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r, int argb)
    {
        fill(l, screenHeight, r, SWATCH_BASE);

        if((argb >>> 24) == 0)
        {
            // Guma: úhlopříčný dílek, ať je odlišná od šedého vzorku.
            int s = l.scale();
            shapes.fillRect(l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s / 2f, r.h() * s / 2f,
                    GUM_CHECK);
            shapes.fillRect(l.screenX(r) + r.w() * s / 2f, l.screenBottom(r, screenHeight) + r.h() * s / 2f,
                    r.w() * s / 2f, r.h() * s / 2f, GUM_CHECK);
            return;
        }

        fill(l, screenHeight, r, argbToColor(argb));
    }

    /** Posuvník HSV: pruh s přechodem v dílcích a značka na aktuální hodnotě. */
    private void drawSlider(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect bar, float at)
    {
        int s = l.scale();
        float x = l.screenX(bar), y = l.screenBottom(bar, screenHeight);
        float segment = bar.w() * s / (float) HSV_SEGMENTS;

        for(int i = 0; i < HSV_SEGMENTS; i++)
        {
            float t = (i + 0.5f) / HSV_SEGMENTS;

            int argb;
            if(bar == TextureLabLayout.HUE)             argb = AtlasEditor.hsv(t * 360f, 1f, 1f, 0xFF);
            else if(bar == TextureLabLayout.SATURATION) argb = AtlasEditor.hsv(hue, t, Math.max(value, 0.2f), 0xFF);
            else                                        argb = AtlasEditor.hsv(hue, saturation, t, 0xFF);

            shapes.fillRect(x + i * segment, y, segment + 1, bar.h() * s, argbToColor(argb));
        }

        float marker = x + at * bar.w() * s;
        shapes.fillRect(marker - s, y - s, 2 * s, (bar.h() + 2) * s, Palette.PANEL_OUTLINE);
        shapes.fillRect(marker - s / 2f, y, s, bar.h() * s, Palette.SELECTOR);
    }

    private void button(TextureLabLayout l, int screenHeight, TextureLabLayout.Rect r,
                        double mouseX, double mouseY)
    {
        int s = l.scale();
        boolean hovered = l.hit(r, mouseX, mouseY);

        shapes.bevelRect(l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s, r.h() * s, s,
                Palette.BUTTON_OUTLINE,
                hovered ? Palette.BUTTON_HOVER_FILL : Palette.BUTTON_FILL,
                hovered ? Palette.BUTTON_HOVER_HIGHLIGHT : Palette.BUTTON_HIGHLIGHT,
                hovered ? Palette.BUTTON_HOVER_SHADOW : Palette.BUTTON_SHADOW);
    }

    private void image(TextureLabLayout l, int screenWidth, int screenHeight, Texture texture,
                       TextureLabLayout.Rect r, float u0, float v0, float u1, float v1)
    {
        int s = l.scale();
        images.draw(texture, screenWidth, screenHeight,
                l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s, r.h() * s,
                u0, v0, u1, v1);
    }

    private void label(TextureLabLayout l, float guiX, float guiY, String line)
    {
        text.drawShadowed(line, l.textLeft(guiX), l.textTop(guiY), Palette.TEXT, Palette.TEXT_SHADOW);
    }

    private void centered(TextureLabLayout l, TextureLabLayout.Rect r, String line)
    {
        float centerX = l.textLeft(r.x() + r.w() / 2f);
        float top = l.textTop(r.y()) + (r.h() * l.scale() - text.lineHeight()) / 2f;
        text.drawCenteredShadowed(line, centerX, Gui.snap(top, l.scale()), Palette.TEXT, Palette.TEXT_SHADOW);
    }

    private static float[] argbToColor(int argb)
    {
        return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f, (argb >>> 24) / 255f};
    }

    /** Jméno bloku do UI - anglicky, atlas fontu je jen ASCII. */
    static String blockName(byte block)
    {
        return switch(block)
        {
            case World.GRASS -> "Grass";
            case World.STONE -> "Stone";
            case World.DIRT -> "Dirt";
            case World.SAND -> "Sand";
            case World.PLANKS -> "Planks";
            case World.COAL_ORE -> "Coal ore";
            case World.IRON_ORE -> "Iron ore";
            case World.WATER -> "Water";
            case World.CRAFTING_TABLE -> "Crafting table";
            case World.STONE_BRICKS -> "Stone bricks";
            case World.LOG -> "Log";
            case World.LEAVES -> "Leaves";
            case World.TORCH -> "Torch";
            case World.FENCE -> "Fence";
            default -> "Block " + block;
        };
    }

    public void delete()
    {
        images.delete();
        checker.delete();
        preview.delete();
    }
}
