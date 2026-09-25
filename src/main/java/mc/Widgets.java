package mc;

/**
 * Kreslení ovládacích prvků obrazovek nastavení a světů.
 *
 * ---------------------------------------------------------------------------
 * Žádný nový UI systém: jsou to tytéž kousky, ze kterých je poskládané menu
 * a texture lab - tlačítko s bevelem jako Menu, zapuštěné pole jako slot
 * inventáře a posuvník jako HSV posuvníky labu (zapuštěná dráha a značka).
 * Tady jsou jen na jednom místě, aby je obrazovky nekopírovaly.
 *
 * Kreslí se ve dvou průchodech, jako v labu: nejdřív tvary (mezi
 * shapes.begin/end), pak texty (mezi text.begin/end). Tvary a text mají
 * každý svůj shader, takže prokládat je po jednom prvku by znamenalo
 * přepínat program u každého tlačítka.
 * ---------------------------------------------------------------------------
 */
final class Widgets {

    private static final float[] TRACK_FILL = {0.30f, 0.45f, 0.25f, 1f};
    private static final float[] DISABLED_FILL = {0.35f, 0.35f, 0.35f, 1f};
    private static final float[] DISABLED_EDGE = {0.28f, 0.28f, 0.28f, 1f};

    final Renderer2D shapes;
    final TextRenderer text;

    Widgets(Renderer2D shapes, TextRenderer text)
    {
        this.shapes = shapes;
        this.text = text;
    }

    // ------------------------------------------------------------------
    // tvary
    // ------------------------------------------------------------------

    /** Ztmavení scény za obrazovkou - stejný přechod jako pod menu pauzy. */
    void dim(int screenWidth, int screenHeight)
    {
        shapes.fillRectGradient(0, 0, screenWidth, screenHeight, Palette.DIM_BOTTOM, Palette.DIM_TOP);
    }

    void fill(ScreenLayout l, int screenHeight, ScreenLayout.Rect r, float[] color)
    {
        int s = l.scale();
        shapes.fillRect(l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s, r.h() * s, color);
    }

    /** Vystouplý panel jako kontejner inventáře. */
    void panel(ScreenLayout l, int screenHeight, ScreenLayout.Rect r)
    {
        int s = l.scale();
        shapes.bevelRect(l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s, r.h() * s, s,
                Palette.PANEL_OUTLINE, Palette.CONTAINER_FILL, Palette.CONTAINER_HIGHLIGHT, Palette.CONTAINER_SHADOW);
    }

    /** Zapuštěný rámeček kolem obdélníku, jako slot v inventáři. */
    void sunken(ScreenLayout l, int screenHeight, ScreenLayout.Rect r)
    {
        int s = l.scale();
        shapes.bevelRect(l.screenX(r) - s, l.screenBottom(r, screenHeight) - s,
                (r.w() + 2) * s, (r.h() + 2) * s, s,
                Palette.SLOT_OUTLINE, Palette.SLOT_FILL, Palette.SLOT_SHADOW, Palette.SLOT_HIGHLIGHT);
    }

    /** Rámeček daný tloušťkou v pixelech obrazovky. */
    void outline(ScreenLayout l, int screenHeight, ScreenLayout.Rect r, int thickness, float[] color)
    {
        int s = l.scale();
        shapes.border(l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s, r.h() * s, thickness, color);
    }

    /** Tlačítko s bevelem jako v Menu; neaktivní je ploché a šedé. */
    void button(ScreenLayout l, int screenHeight, ScreenLayout.Rect r, boolean hovered, boolean enabled)
    {
        int s = l.scale();

        if(!enabled)
        {
            shapes.bevelRect(l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s, r.h() * s, s,
                    Palette.BUTTON_OUTLINE, DISABLED_FILL, DISABLED_EDGE, DISABLED_EDGE);
            return;
        }

        shapes.bevelRect(l.screenX(r), l.screenBottom(r, screenHeight), r.w() * s, r.h() * s, s,
                Palette.BUTTON_OUTLINE,
                hovered ? Palette.BUTTON_HOVER_FILL : Palette.BUTTON_FILL,
                hovered ? Palette.BUTTON_HOVER_HIGHLIGHT : Palette.BUTTON_HIGHLIGHT,
                hovered ? Palette.BUTTON_HOVER_SHADOW : Palette.BUTTON_SHADOW);
    }

    /**
     * Posuvník jako HSV posuvníky v labu: zapuštěná dráha, vyplněná po
     * aktuální hodnotu, a značka s černým lemem. t je 0 až 1.
     */
    void slider(ScreenLayout l, int screenHeight, ScreenLayout.Rect track, float t, boolean hovered)
    {
        int s = l.scale();
        sunken(l, screenHeight, track);

        float x = l.screenX(track), y = l.screenBottom(track, screenHeight);
        float filled = Math.max(0f, Math.min(1f, t)) * track.w() * s;

        shapes.fillRect(x, y, filled, track.h() * s, TRACK_FILL);

        float marker = x + filled;
        shapes.fillRect(marker - s, y - s, 2 * s, (track.h() + 2) * s, Palette.PANEL_OUTLINE);
        shapes.fillRect(marker - s / 2f, y, s, track.h() * s, hovered ? Palette.TEXT : Palette.SELECTOR);
    }

    /** Textové pole: zapuštěné, s rámečkem, když je v něm fokus. */
    void textField(ScreenLayout l, int screenHeight, ScreenLayout.Rect r, boolean focused)
    {
        sunken(l, screenHeight, r);

        if(focused)
        {
            outline(l, screenHeight, r, l.scale(), Palette.SELECTOR);
        }
    }

    // ------------------------------------------------------------------
    // texty
    // ------------------------------------------------------------------

    void label(ScreenLayout l, float guiX, float guiY, String line)
    {
        text.drawShadowed(line, l.textLeft(guiX), l.textTop(guiY), Palette.TEXT, Palette.TEXT_SHADOW);
    }

    void muted(ScreenLayout l, float guiX, float guiY, String line)
    {
        text.draw(line, l.textLeft(guiX), l.textTop(guiY), Palette.TEXT_MUTED);
    }

    /** Text vycentrovaný v obdélníku, svisle zarovnaný na celý GUI pixel. */
    void centered(ScreenLayout l, ScreenLayout.Rect r, String line, boolean enabled)
    {
        float centerX = l.textLeft(r.x() + r.w() / 2f);
        float top = l.textTop(r.y()) + (r.h() * l.scale() - text.lineHeight()) / 2f;

        if(enabled)
        {
            text.drawCenteredShadowed(line, centerX, Gui.snap(top, l.scale()), Palette.TEXT, Palette.TEXT_SHADOW);
        }
        else
        {
            text.drawCentered(line, centerX, Gui.snap(top, l.scale()), Palette.TEXT_MUTED);
        }
    }

    void centered(ScreenLayout l, ScreenLayout.Rect r, String line)
    {
        centered(l, r, line, true);
    }

    /** Zkrátí text tak, aby se vešel do šířky v GUI pixelech (viz TextRenderer.fit). */
    String fit(String line, int guiWidth, int scale)
    {
        return text.fit(line, guiWidth);
    }
}
