package mc;

import org.lwjgl.BufferUtils;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Rasterizuje ASCII znaky do jedné textury a pamatuje si, kde který leží.
 *
 * Fonty se kreslí přes AWT (BufferedImage + Graphics2D), ne přes stb_truetype.
 * Důvod: AWT je přímo v JDK, takže nepotřebuje ani novou závislost, ani
 * přibalený .ttf soubor. Rasterizuje se jednou při startu; za běhu už se
 * jen čte z textury.
 *
 * Atlas je jednokanálový (GL_RED) - drží pouze krytí glyfu. Barvu dodává
 * shader uniformem, takže jeden atlas obslouží text libovolné barvy.
 *
 * ---------------------------------------------------------------------------
 * ANTIALIASING JE VYPNUTÝ A FILTROVÁNÍ JE GL_NEAREST. Obojí schválně:
 * font se rasterizuje malý (viz Main) a na obrazovku se kreslí zvětšený CELÝM
 * číslem, takže z každého texelu vyjde ostrý čtvereček - pixelové písmo.
 *
 * S antialiasingem by měl glyf poloprůhledné okraje, které by se zvětšením
 * roztáhly do rozmazaného lemu; s GL_LINEAR by se texely navíc průměrovaly.
 * Kterákoliv z těch dvou věcí zapnutá = hladké písmo, a je jedno, jak ostré
 * je zbytek rozhraní.
 * ---------------------------------------------------------------------------
 */
public class FontAtlas {

    private static final char FIRST_CHAR = 32;   // mezera
    private static final char LAST_CHAR  = 126;  // ~
    private static final int  GLYPH_COUNT = LAST_CHAR - FIRST_CHAR + 1;

    /** Odsazení mezi glyfy v atlasu, aby se při filtrování nepřetahovaly sousedi. */
    private static final int PADDING = 2;

    /**
     * Rozestup mezi znaky při sazbě, v pixelech atlasu (tedy v GUI pixelech).
     *
     * ⚠️ Bez něj se znaky dotýkají. Advance z FontMetrics je u malých velikostí
     * bez antialiasingu často přesně tak široký jako samotný tvar písmene -
     * 'M' v devítce zabírá celých svých 7 px - takže po něm následující 'i'
     * začne hned vedle a obojí splyne v jeden blok ("Minecraft" se čte jako
     * "Nnecraft"). Je to vlastnost metrik fontu, ne našeho kreslení: nativní
     * AWT drawString celého řetězce vypadá stejně.
     *
     * Jeden pixel stačí a je to i míra, kterou používá Minecraft. Dva už text
     * zbytečně roztrhají.
     */
    private static final int LETTER_SPACING = 1;

    private final int textureId;

    // Pozice a rozměr každého glyfu v atlasu, v pixelech.
    private final int[] glyphX = new int[GLYPH_COUNT];
    private final int[] glyphY = new int[GLYPH_COUNT];
    private final int[] glyphWidth = new int[GLYPH_COUNT];
    private final int[] glyphAdvance = new int[GLYPH_COUNT];

    private final int atlasWidth;
    private final int atlasHeight;
    private final int lineHeight;
    private final int ascent;

    public FontAtlas(String fontName, int fontSize)
    {
        this(fontName, fontSize, Font.PLAIN);
    }

    /** style je konstanta z java.awt.Font: PLAIN, BOLD, ITALIC. */
    public FontAtlas(String fontName, int fontSize, int style)
    {
        Font font = new Font(fontName, style, fontSize);

        // Metriky se musí zjistit dřív, než se ví, jak velký atlas bude potřeba,
        // takže se nejdřív měří na zahazovacím jednopixelovém obrázku.
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D probeGraphics = probe.createGraphics();
        probeGraphics.setFont(font);
        FontMetrics metrics = probeGraphics.getFontMetrics();

        this.ascent = metrics.getAscent();
        this.lineHeight = metrics.getHeight();

        int cellHeight = lineHeight + PADDING;

        // Rozvržení do řádků: glyfy se skládají vedle sebe a na dané šířce se zalomí.
        int targetWidth = 512;
        int penX = PADDING;
        int penY = PADDING;
        int usedHeight = cellHeight + PADDING;

        for(int i = 0; i < GLYPH_COUNT; i++)
        {
            char c = (char) (FIRST_CHAR + i);
            int advance = metrics.charWidth(c);
            int width = Math.max(1, advance);

            if(penX + width + PADDING > targetWidth)
            {
                penX = PADDING;
                penY += cellHeight;
                usedHeight = penY + cellHeight + PADDING;
            }

            glyphX[i] = penX;
            glyphY[i] = penY;
            glyphWidth[i] = width;
            glyphAdvance[i] = advance;

            penX += width + PADDING;
        }

        probeGraphics.dispose();

        this.atlasWidth = targetWidth;
        this.atlasHeight = nextPowerOfTwo(usedHeight);

        // Skutečné vykreslení glyfů: bílé na průhledném pozadí.
        // Zajímá nás jen alfa kanál, barva se dodá až v shaderu.
        BufferedImage atlas = new BufferedImage(atlasWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setFont(font);
        g.setColor(Color.WHITE);

        for(int i = 0; i < GLYPH_COUNT; i++)
        {
            char c = (char) (FIRST_CHAR + i);
            // drawString kreslí od ÚČaří, ne od horního okraje - proto + ascent
            g.drawString(String.valueOf(c), glyphX[i], glyphY[i] + ascent);
        }

        g.dispose();

        this.textureId = uploadTexture(atlas);
    }

    private static int uploadTexture(BufferedImage image)
    {
        int width = image.getWidth();
        int height = image.getHeight();

        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height);

        // Do textury jde jen alfa kanál (jeden bajt na pixel).
        // OpenGL má počátek textury vlevo DOLE, BufferedImage vlevo NAHOŘE,
        // ale řádky nepřevracíme - místo toho se obrací V souřadnice při kreslení,
        // takže tady stačí přepsat pixely v pořadí, v jakém jsou.
        for(int y = 0; y < height; y++)
        {
            for(int x = 0; x < width; x++)
            {
                int argb = image.getRGB(x, y);
                pixels.put((byte) ((argb >> 24) & 0xFF));
            }
        }

        pixels.flip();

        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);

        // Řádek atlasu nemusí mít délku dělitelnou 4, což je výchozí zarovnání.
        // Bez tohohle by se textura zešikmila.
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RED, width, height, 0, GL_RED, GL_UNSIGNED_BYTE, pixels);

        // NEAREST, ne LINEAR: text se zvětšuje celým číslem a každý texel
        // má zůstat ostrý čtvereček. Viz poznámka v hlavičce třídy.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        // CLAMP_TO_EDGE, ať se okrajové glyfy nezacyklí z druhé strany atlasu
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glBindTexture(GL_TEXTURE_2D, 0);
        return id;
    }

    private static int nextPowerOfTwo(int value)
    {
        int result = 1;
        while(result < value)
        {
            result <<= 1;
        }
        return result;
    }

    private static int indexOf(char c)
    {
        if(c < FIRST_CHAR || c > LAST_CHAR)
        {
            return '?' - FIRST_CHAR;   // neznámý znak se nakreslí jako otazník
        }
        return c - FIRST_CHAR;
    }

    // ------------------------------------------------------------------

    public int textureId()  { return textureId; }
    public int lineHeight() { return lineHeight; }
    public int ascent()     { return ascent; }

    /** Posun pera na další znak - šířka glyfu plus rozestup. */
    public int advance(char c)     { return glyphAdvance[indexOf(c)] + LETTER_SPACING; }

    /** Šířka samotného tvaru písmene, tedy kolik zabere kreslený obdélník. */
    public int glyphWidth(char c)  { return glyphWidth[indexOf(c)]; }

    /** Šířka celého řetězce v pixelech - pro zarovnání na střed a na pravou hranu. */
    public int textWidth(String text)
    {
        if(text.isEmpty())
        {
            return 0;
        }

        int width = 0;
        for(int i = 0; i < text.length(); i++)
        {
            width += advance(text.charAt(i));
        }

        // Za posledním znakem už rozestup není, jinak by text seděl o půl
        // pixelu vlevo od skutečného středu.
        return width - LETTER_SPACING;
    }

    public float u0(char c) { return glyphX[indexOf(c)] / (float) atlasWidth; }
    public float u1(char c) { return (glyphX[indexOf(c)] + glyphWidth[indexOf(c)]) / (float) atlasWidth; }
    public float v0(char c) { return glyphY[indexOf(c)] / (float) atlasHeight; }
    public float v1(char c) { return (glyphY[indexOf(c)] + lineHeight) / (float) atlasHeight; }

    public void delete()
    {
        glDeleteTextures(textureId);
    }
}
