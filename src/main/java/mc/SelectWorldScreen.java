package mc;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.GL_CLAMP_TO_EDGE;

/**
 * Obrazovka "Select World": seznam uložených světů s náhledem.
 *
 * ---------------------------------------------------------------------------
 * Světy jsou seřazené od naposledy hraného (WorldSaves.list), u každého
 * náhledový obrázek z poslední hry, jméno a datum. Klik vybírá, dvojklik
 * rovnou hraje; Delete se ptá na potvrzení, protože smazání světa je
 * nevratné a je to jediná nevratná věc v celém menu.
 *
 * Náhledy jsou textury a nahrávají se při otevření obrazovky (refresh()),
 * ne při každém kreslení; delete() je zase uvolní. Chybějící ikona (svět,
 * ze kterého se ještě neodcházelo do menu) se nakreslí jako prázdné pole.
 * ---------------------------------------------------------------------------
 */
public final class SelectWorldScreen {

    public enum Action { NONE, PLAY, CREATE, CANCEL }

    public static final int WIDTH = 320, HEIGHT = 212;

    static final int ROWS = 4;
    static final ScreenLayout.Rect LIST = new ScreenLayout.Rect(5, 26, 310, 128);
    static final int ROW_PITCH = 32, ROW_HEIGHT = 30;
    static final int ICON_W = 54, ICON_H = 30;

    static final ScreenLayout.Rect PLAY = new ScreenLayout.Rect(5, 160, 153, 20);
    static final ScreenLayout.Rect CREATE = new ScreenLayout.Rect(162, 160, 153, 20);
    static final ScreenLayout.Rect DELETE = new ScreenLayout.Rect(5, 184, 153, 20);
    static final ScreenLayout.Rect CANCEL = new ScreenLayout.Rect(162, 184, 153, 20);

    // potvrzení smazání
    static final ScreenLayout.Rect CONFIRM_PANEL = new ScreenLayout.Rect(30, 60, 260, 86);
    static final ScreenLayout.Rect CONFIRM_DELETE = new ScreenLayout.Rect(40, 118, 110, 20);
    static final ScreenLayout.Rect CONFIRM_CANCEL = new ScreenLayout.Rect(170, 118, 110, 20);

    /** Dvojklik: dva kliky na tentýž řádek do půl sekundy. */
    private static final double DOUBLE_CLICK_SECONDS = 0.5;

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Widgets widgets;
    private final ImageRenderer images;
    private final Path root;

    private List<WorldSaves.WorldInfo> worlds = new ArrayList<>();
    private final Map<String, Texture> icons = new HashMap<>();

    private int selected = -1;
    private int scroll = 0;
    private boolean confirming = false;

    private int lastClickedRow = -1;
    private double lastClickTime = -1;

    public SelectWorldScreen(Widgets widgets, ImageRenderer images, Path root)
    {
        this.widgets = widgets;
        this.images = images;
        this.root = root;
    }

    /** Znovu načte seznam i náhledy - po otevření, po smazání a po hraní. */
    public void refresh()
    {
        String keep = selected >= 0 && selected < worlds.size() ? worlds.get(selected).folder() : null;

        worlds = WorldSaves.list(root);
        selected = -1;

        for(int i = 0; i < worlds.size(); i++)
        {
            if(worlds.get(i).folder().equals(keep))
            {
                selected = i;
            }
        }

        if(selected < 0 && !worlds.isEmpty())
        {
            selected = 0;   // naposledy hraný je první
        }

        loadIcons();
        clampScroll();
    }

    public List<WorldSaves.WorldInfo> worlds()
    {
        return worlds;
    }

    public WorldSaves.WorldInfo selected()
    {
        return selected >= 0 && selected < worlds.size() ? worlds.get(selected) : null;
    }

    private void loadIcons()
    {
        for(Texture texture : icons.values())
        {
            texture.delete();
        }

        icons.clear();

        for(WorldSaves.WorldInfo world : worlds)
        {
            Thumbnails.Image image = Thumbnails.load(world.iconFile(), 512, 512);

            if(image != null)
            {
                icons.put(world.folder(),
                        Texture.fromArgb(image.argb(), image.width(), image.height(), GL_CLAMP_TO_EDGE));
            }
        }
    }

    // ------------------------------------------------------------------
    // vstup
    // ------------------------------------------------------------------

    static ScreenLayout layout(int screenWidth, int screenHeight)
    {
        return new ScreenLayout(WIDTH, HEIGHT, screenWidth, screenHeight);
    }

    /** Obdélník i-tého viditelného řádku (0 až ROWS-1). */
    static ScreenLayout.Rect rowRect(int visibleIndex)
    {
        return new ScreenLayout.Rect(LIST.x(), LIST.y() + visibleIndex * ROW_PITCH, LIST.w(), ROW_HEIGHT);
    }

    /**
     * Trefil poslední klik tlačítko, které obrazovka obsloužila sama (bez
     * akce pro Main)? Main podle toho zahraje zvuk kliknutí - dřív zněla
     * jen tlačítka, jejichž akci vracela obrazovka, a stejně vypadající
     * přepínače vedle nich mlčely.
     */
    private boolean buttonClicked = false;

    public boolean takeClicked()
    {
        boolean was = buttonClicked;
        buttonClicked = false;
        return was;
    }

    /** Index světa pod myší, nebo -1. */
    int worldAt(ScreenLayout l, double mouseX, double mouseY)
    {
        for(int i = 0; i < ROWS; i++)
        {
            int index = scroll + i;

            if(index < worlds.size() && l.hit(rowRect(i), mouseX, mouseY))
            {
                return index;
            }
        }

        return -1;
    }

    public Action press(double mouseX, double mouseY, int screenWidth, int screenHeight, double now)
    {
        ScreenLayout l = layout(screenWidth, screenHeight);

        if(confirming)
        {
            if(l.hit(CONFIRM_DELETE, mouseX, mouseY))
            {
                deleteSelected();
                buttonClicked = true;
            }
            else if(l.hit(CONFIRM_CANCEL, mouseX, mouseY))
            {
                confirming = false;
                buttonClicked = true;
            }

            return Action.NONE;
        }

        int clicked = worldAt(l, mouseX, mouseY);

        if(clicked >= 0)
        {
            boolean again = clicked == lastClickedRow && now - lastClickTime <= DOUBLE_CLICK_SECONDS;
            selected = clicked;
            lastClickedRow = clicked;
            lastClickTime = now;

            return again ? Action.PLAY : Action.NONE;
        }

        if(l.hit(PLAY, mouseX, mouseY) && selected() != null)
        {
            return Action.PLAY;
        }
        if(l.hit(CREATE, mouseX, mouseY))
        {
            return Action.CREATE;
        }
        if(l.hit(DELETE, mouseX, mouseY) && selected() != null)
        {
            confirming = true;
            buttonClicked = true;
            return Action.NONE;
        }
        if(l.hit(CANCEL, mouseX, mouseY))
        {
            return Action.CANCEL;
        }

        return Action.NONE;
    }

    /** Kolečko posouvá seznam; kladné nahoru, jako všude. */
    public void scroll(double amount)
    {
        if(!confirming)
        {
            scroll -= (int) Math.signum(amount);
            clampScroll();
        }
    }

    public Action key(int key)
    {
        if(confirming)
        {
            if(key == GLFW_KEY_ESCAPE)
            {
                confirming = false;
            }
            return Action.NONE;
        }

        return switch(key)
        {
            case GLFW_KEY_ESCAPE -> Action.CANCEL;
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> selected() != null ? Action.PLAY : Action.NONE;
            case GLFW_KEY_UP -> { move(-1); yield Action.NONE; }
            case GLFW_KEY_DOWN -> { move(1); yield Action.NONE; }
            default -> Action.NONE;
        };
    }

    private void move(int by)
    {
        if(worlds.isEmpty())
        {
            return;
        }

        selected = Math.max(0, Math.min(worlds.size() - 1, selected + by));

        // Vybraný řádek musí zůstat vidět.
        scroll = Math.max(selected - ROWS + 1, Math.min(scroll, selected));
        clampScroll();
    }

    private void clampScroll()
    {
        scroll = Math.max(0, Math.min(Math.max(0, worlds.size() - ROWS), scroll));
    }

    private void deleteSelected()
    {
        WorldSaves.WorldInfo world = selected();
        confirming = false;

        if(world == null)
        {
            return;
        }

        if(!WorldSaves.delete(root, world))
        {
            System.err.println("Svet " + world.folder() + " se nepovedlo smazat");
        }

        selected = -1;
        refresh();
    }

    public boolean isConfirming()
    {
        return confirming;
    }

    // ------------------------------------------------------------------
    // kreslení
    // ------------------------------------------------------------------

    public void render(int screenWidth, int screenHeight, double mouseX, double mouseY)
    {
        ScreenLayout l = layout(screenWidth, screenHeight);
        int hovered = confirming ? -1 : worldAt(l, mouseX, mouseY);
        boolean hasWorld = selected() != null;

        // --- podklady ---
        widgets.shapes.begin(screenWidth, screenHeight);

        for(int i = 0; i < ROWS && scroll + i < worlds.size(); i++)
        {
            widgets.sunken(l, screenHeight, rowRect(i));
        }

        widgets.button(l, screenHeight, PLAY, !confirming && l.hit(PLAY, mouseX, mouseY), hasWorld);
        widgets.button(l, screenHeight, CREATE, !confirming && l.hit(CREATE, mouseX, mouseY), true);
        widgets.button(l, screenHeight, DELETE, !confirming && l.hit(DELETE, mouseX, mouseY), hasWorld);
        widgets.button(l, screenHeight, CANCEL, !confirming && l.hit(CANCEL, mouseX, mouseY), true);

        widgets.shapes.end();

        // --- náhledy světů (textura, tedy vlastní shader mezi průchody) ---
        for(int i = 0; i < ROWS && scroll + i < worlds.size(); i++)
        {
            WorldSaves.WorldInfo world = worlds.get(scroll + i);
            Texture icon = icons.get(world.folder());

            if(icon != null)
            {
                ScreenLayout.Rect r = iconRect(i);
                // v0 = 1, v1 = 0: pole náhledu je v pořadí obrázku (první řádek
                // nahoře), kdežto GL má v = 0 u prvního nahraného řádku.
                images.draw(icon, screenWidth, screenHeight,
                        l.screenX(r), l.screenBottom(r, screenHeight),
                        r.w() * l.scale(), r.h() * l.scale(), 0f, 1f, 1f, 0f);
            }
        }

        // --- rámečky výběru a najetí ---
        widgets.shapes.begin(screenWidth, screenHeight);

        for(int i = 0; i < ROWS && scroll + i < worlds.size(); i++)
        {
            int index = scroll + i;

            if(index == selected)
            {
                widgets.outline(l, screenHeight, rowRect(i), l.scale(), Palette.SELECTOR);
            }
            else if(index == hovered)
            {
                widgets.outline(l, screenHeight, rowRect(i), 1, Palette.TEXT_MUTED);
            }
        }

        if(confirming)
        {
            widgets.dim(screenWidth, screenHeight);
            widgets.panel(l, screenHeight, CONFIRM_PANEL);
            widgets.button(l, screenHeight, CONFIRM_DELETE, l.hit(CONFIRM_DELETE, mouseX, mouseY), true);
            widgets.button(l, screenHeight, CONFIRM_CANCEL, l.hit(CONFIRM_CANCEL, mouseX, mouseY), true);
        }

        widgets.shapes.end();

        drawTexts(l, screenWidth, screenHeight, hasWorld);
    }

    static ScreenLayout.Rect iconRect(int visibleIndex)
    {
        ScreenLayout.Rect row = rowRect(visibleIndex);
        return new ScreenLayout.Rect(row.x(), row.y(), ICON_W, ICON_H);
    }

    private void drawTexts(ScreenLayout l, int screenWidth, int screenHeight, boolean hasWorld)
    {
        TextRenderer text = widgets.text;

        text.begin(screenWidth, screenHeight, l.scale() * 2);
        text.drawCenteredShadowed("Select World", l.textLeft(WIDTH / 2f), l.textTop(4),
                Palette.TEXT, Palette.TEXT_SHADOW);
        text.end();

        text.begin(screenWidth, screenHeight, l.scale());

        // ⚠️ Při potvrzení mazání se texty seznamu NEKRESLÍ. Text jde na
        // obrazovku až po všech tvarech, takže by jinak prosvítal skrz panel
        // dialogu - ztmavení pod ním zakryje jen tvary.
        if(confirming)
        {
            drawConfirmTexts(l);
            text.end();
            return;
        }

        if(worlds.isEmpty())
        {
            widgets.centered(l, LIST, "No worlds yet - create one");
        }

        for(int i = 0; i < ROWS && scroll + i < worlds.size(); i++)
        {
            WorldSaves.WorldInfo world = worlds.get(scroll + i);
            ScreenLayout.Rect row = rowRect(i);
            int textX = row.x() + ICON_W + 5;
            int textWidth = row.w() - ICON_W - 8;

            widgets.label(l, textX, row.y() + 3, widgets.fit(world.name(), textWidth, l.scale()));
            widgets.muted(l, textX, row.y() + 15, widgets.fit(
                    WHEN.format(Instant.ofEpochMilli(world.lastPlayed())) + "   seed " + world.seed(),
                    textWidth, l.scale()));
        }

        if(worlds.size() > ROWS)
        {
            // Nad seznamem vlevo, vedle nadpisu - pod seznamem by se kryl
            // s posledním řádkem i s tlačítky.
            widgets.muted(l, LIST.x(), LIST.y() - 11,
                    (scroll + 1) + "-" + Math.min(worlds.size(), scroll + ROWS) + " of " + worlds.size()
                            + "   (scroll)");
        }

        widgets.centered(l, PLAY, "Play Selected World", hasWorld);
        widgets.centered(l, CREATE, "Create New World");
        widgets.centered(l, DELETE, "Delete", hasWorld);
        widgets.centered(l, CANCEL, "Cancel");

        text.end();
    }

    private void drawConfirmTexts(ScreenLayout l)
    {
        WorldSaves.WorldInfo world = selected();
        String name = world == null ? "" : world.name();

        widgets.centered(l, new ScreenLayout.Rect(CONFIRM_PANEL.x(), CONFIRM_PANEL.y() + 12,
                CONFIRM_PANEL.w(), 12), widgets.fit("Delete \"" + name + "\"?", CONFIRM_PANEL.w() - 10, l.scale()));
        widgets.centered(l, new ScreenLayout.Rect(CONFIRM_PANEL.x(), CONFIRM_PANEL.y() + 32,
                CONFIRM_PANEL.w(), 12), "This world will be gone forever.");
        widgets.centered(l, CONFIRM_DELETE, "Delete");
        widgets.centered(l, CONFIRM_CANCEL, "Cancel");
    }

    /** Uvolní náhledové textury. Main to dělá při každém odchodu ze seznamu (setState). */
    public void delete()
    {
        for(Texture texture : icons.values())
        {
            texture.delete();
        }

        icons.clear();
    }
}
