package mc;

import java.nio.file.Path;

import static org.lwjgl.opengl.GL33.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL33.GL_REPEAT;

/**
 * Procedurálně generované textury.
 *
 * Nejsou to soubory v resources schválně: jsou to desítky řádků kódu a nemůže
 * je rozbít chybějící soubor v jaru. Až budou textury kreslené ručně, nahradí
 * se načtením PNG - Texture je na to připravená, generování dat je od ní
 * oddělené a BlockAtlas o způsobu vzniku pixelů vůbec neví.
 *
 * Odstíny se vybírají HASHEM ze souřadnic, ne generátorem náhodných čísel.
 * Dvě výhody: textura vyjde pokaždé stejná (žádné blikání mezi spuštěními)
 * a je bezešvá - hash nezná okraje, takže na hranici opakování nevznikne spára.
 */
public final class Textures {

    private static final int DIRT_TILE_SIZE = 16;

    // Čtyři tóny na dlaždici: míň vypadá plochá, víc jako šum.
    private static final int[] DIRT_TONES   = {0xFF8B6D4B, 0xFF866043, 0xFF79563A, 0xFF6E4E33};
    private static final int[] GRASS_TONES  = {0xFF5B8C3A, 0xFF6BA043, 0xFF548034, 0xFF74AC4B};
    private static final int[] STONE_TONES  = {0xFF8A8A8A, 0xFF7E7E7E, 0xFF949494, 0xFF737373};
    private static final int[] SAND_TONES   = {0xFFDBD3A0, 0xFFD6CE97, 0xFFE0D9AA, 0xFFCFC68C};
    private static final int[] PLANK_TONES  = {0xFFB08A55, 0xFFA8834F, 0xFF9C7847, 0xFFB79059};

    // Zrna rud. Leží v kamenném podkladu, takže musí být výrazně kontrastní.
    private static final int[] COAL_TONES = {0xFF2B2B2B, 0xFF1B1B1B, 0xFF383838, 0xFF121212};
    private static final int[] IRON_TONES = {0xFFC9A171, 0xFFB98F5E, 0xFFD6B084, 0xFFAA8050};

    /**
     * Voda je jediná dlaždice s alfou pod 255 - odtud si fragment shader bere
     * průhlednost. Neprůhledné dlaždice mají alfu 255, takže se pro ně nic nemění.
     */
    private static final int[] WATER_TONES = {0xC02F5FA8, 0xC02A56A0, 0xC0356AB4, 0xC0264E96};

    private static final int[] BRICK_TONES = {0xFF7E7E82, 0xFF767678, 0xFF87878B, 0xFF6E6E72};
    private static final int BRICK_SEAM = 0xFF54545A;

    private static final int TABLE_TOP_GRID = 0xFF4E3A22;

    private static final int[] BARK_TONES = {0xFF6B5335, 0xFF5E4930, 0xFF775C3B, 0xFF54402A};
    private static final int[] RING_TONES = {0xFFB79668, 0xFFA9885C, 0xFFC4A375, 0xFF9C7B52};
    private static final int[] LEAF_TONES = {0xFF3E7A2C, 0xFF356B26, 0xFF478A33, 0xFF2C5E20};

    private static final int[] TORCH_STICK = {0xFF8A6A3E, 0xFF7B5D35};
    private static final int[] TORCH_FLAME = {0xFFFFD65C, 0xFFFFB030, 0xFFFFF0A0};

    /** Praskliny jsou tmavé a poloprůhledné - kreslí se PŘES texturu bloku. */
    private static final int CRACK_DARK = 0xC0101010;
    private static final int CRACK_EDGE = 0x80303030;

    private static final int PLANK_SEAM = 0xFF6E5433;
    private static final int UNKNOWN_A  = 0xFFFF00FF;
    private static final int UNKNOWN_B  = 0xFF000000;

    /** Kolik horních řádků dlaždice grass_side zabírá tráva přetékající přes hranu. */
    private static final int GRASS_OVERHANG = 3;

    private Textures() {}

    /** Dlaždice hlíny pro pozadí hlavního menu. Opakuje se, proto GL_REPEAT. */
    public static Texture dirt()
    {
        int[] pixels = new int[DIRT_TILE_SIZE * DIRT_TILE_SIZE];

        for(int y = 0; y < DIRT_TILE_SIZE; y++)
        {
            for(int x = 0; x < DIRT_TILE_SIZE; x++)
            {
                pixels[y * DIRT_TILE_SIZE + x] = DIRT_TONES[hash(x, y) & 3];
            }
        }

        return Texture.fromArgb(pixels, DIRT_TILE_SIZE, DIRT_TILE_SIZE, GL_REPEAT);
    }

    /**
     * Kam texture lab ukládá upravený atlas a odkud se při startu hry načte.
     * Relativně k pracovnímu adresáři, stejně jako saves/ a sounds/.
     */
    public static final Path ATLAS_FILE = Path.of("textures", "atlas.png");

    /**
     * Odkud lab importuje hotový atlas, když se mu žádný soubor nepřetáhne
     * do okna. Okno souborů nabídnout nejde: AWT běží headless (viz Main)
     * a tinyfd by byl nový modul LWJGL, tedy další závislost.
     */
    public static final Path IMPORT_FILE = Path.of("textures", "import.png");

    /** Pixely atlasu a odkud přišly - ladicí výpis i lab to ukazují. */
    public record AtlasPixels(int[] pixels, boolean fromFile) {}

    /**
     * Pixely atlasu pro hru.
     *
     * ⚠️ TOHLE JE PŘEPÍNAČ mezi procedurální a nahranou texturou: když soubor
     * existuje a jde přečíst, použije se on; jinak procedurální generování
     * jako dřív. Smazání souboru tedy vrací hru k procedurálnímu atlasu.
     * BlockAtlas o tom neví - pro něj jsou to pořád jen pixely v mřížce.
     */
    public static AtlasPixels atlasPixels(Path file)
    {
        int[] fromFile = AtlasImage.load(file);

        return fromFile != null
                ? new AtlasPixels(fromFile, true)
                : new AtlasPixels(blockAtlasPixels(), false);
    }

    /**
     * Atlas textur bloků jako GL textura.
     *
     * ⚠️ Wrap je CLAMP_TO_EDGE, ne REPEAT. U atlasu by opakování znamenalo, že
     * UV mírně za okrajem sáhne na protilehlou stranu ATLASU - tedy do úplně
     * jiné dlaždice. CLAMP to zarazí na kraji; přesahu přes hranici dlaždice
     * brání navíc půltexelové zúžení v BlockAtlas.
     */
    public static Texture blockAtlas(int[] pixels)
    {
        return Texture.fromArgb(pixels,
                BlockAtlas.ATLAS_PIXELS, BlockAtlas.ATLAS_PIXELS, GL_CLAMP_TO_EDGE);
    }

    /**
     * Samotné pixely atlasu, bez GL. Oddělené schválně: takhle jde obsah atlasu
     * ověřit headless (viz AtlasTest) - jestli dlaždice sedí ve správných
     * buňkách a mají barvy, které mít mají.
     */
    static int[] blockAtlasPixels()
    {
        int size = BlockAtlas.ATLAS_PIXELS;
        int[] pixels = new int[size * size];

        for(int tile = 0; tile < BlockAtlas.TILE_COUNT; tile++)
        {
            int originX = BlockAtlas.column(tile) * BlockAtlas.TILE_PIXELS;
            int originY = BlockAtlas.row(tile) * BlockAtlas.TILE_PIXELS;

            for(int y = 0; y < BlockAtlas.TILE_PIXELS; y++)
            {
                for(int x = 0; x < BlockAtlas.TILE_PIXELS; x++)
                {
                    pixels[(originY + y) * size + originX + x] = texel(tile, x, y);
                }
            }
        }

        return pixels;
    }

    /**
     * Jeden pixel dlaždice. y = 0 je DOLNÍ řádek, protože v tomhle pořadí
     * čte data Texture a v tomhle pořadí počítá řádky BlockAtlas.
     */
    private static int texel(int tile, int x, int y)
    {
        return switch(tile)
        {
            case BlockAtlas.TILE_GRASS_TOP -> GRASS_TONES[hash(x, y) & 3];
            case BlockAtlas.TILE_DIRT      -> DIRT_TONES[hash(x, y) & 3];
            case BlockAtlas.TILE_STONE     -> stone(x, y);
            case BlockAtlas.TILE_SAND      -> SAND_TONES[hash(x, y) & 3];
            case BlockAtlas.TILE_PLANKS    -> planks(x, y);
            case BlockAtlas.TILE_GRASS_SIDE -> grassSide(x, y);
            case BlockAtlas.TILE_WATER      -> WATER_TONES[hash(x >> 1, y >> 1) & 3];
            case BlockAtlas.TILE_BRICKS     -> bricks(x, y);
            case BlockAtlas.TILE_LOG_SIDE   -> bark(x, y);
            case BlockAtlas.TILE_LOG_TOP    -> logRings(x, y);
            case BlockAtlas.TILE_LEAVES     -> leaves(x, y);
            case BlockAtlas.TILE_TORCH      -> torch(x, y);
            case BlockAtlas.TILE_TABLE_SIDE -> planks(x, y);
            case BlockAtlas.TILE_TABLE_TOP  -> tableTop(x, y);
            case BlockAtlas.TILE_COAL_ORE   -> ore(x, y, COAL_TONES, 17);
            case BlockAtlas.TILE_IRON_ORE   -> ore(x, y, IRON_TONES, 91);
            default -> tile >= BlockAtlas.TILE_CRACK_FIRST
                    && tile < BlockAtlas.TILE_CRACK_FIRST + BlockAtlas.CRACK_STAGES
                    ? crack(x, y, tile - BlockAtlas.TILE_CRACK_FIRST)
                    : ((x >> 3) ^ (y >> 3)) == 0 ? UNKNOWN_A : UNKNOWN_B;   // šachovnice
        };
    }

    /**
     * Bok travnatého bloku: hlína, přes kterou nahoře přetéká tráva.
     * Hrana není rovná - o poslední řádek se dělí podle hashe, aby přechod
     * vypadal utrženě a ne jako nakreslená linka.
     */
    private static int grassSide(int x, int y)
    {
        int top = BlockAtlas.TILE_PIXELS - 1;

        if(y > top - GRASS_OVERHANG)
        {
            return GRASS_TONES[hash(x, y) & 3];
        }

        if(y == top - GRASS_OVERHANG && (hash(x, 0) & 1) == 0)
        {
            return GRASS_TONES[hash(x, y) & 3];
        }

        return DIRT_TONES[hash(x, y) & 3];
    }

    /**
     * Ruda: kamenný podklad, do kterého jsou zapuštěné shluky zrna.
     *
     * Shluky se rozhodují na hrubší mřížce 2x2, ne po pixelech - jednotlivé
     * rozházené pixely by ve hře splynuly s vlastním zrnem kamene a ruda by
     * v šeru jeskyně nebyla poznat.
     */
    private static int ore(int x, int y, int[] tones, int salt)
    {
        if(hash(x >> 1, (y >> 1) + salt) % 5 == 0)
        {
            return tones[hash(x, y) & 3];
        }

        return stone(x, y);
    }

    /** Kámen: základní zrno plus řídké tmavší shluky, ať není úplně stejnoměrný. */
    private static int stone(int x, int y)
    {
        int tone = hash(x, y) & 3;

        // Hrubší mřížka 2x2 tu a tam ztmaví celý shluk pixelů najednou.
        if((hash(x >> 1, y >> 1) & 7) == 0)
        {
            tone = 3;
        }

        return STONE_TONES[tone];
    }

    /**
     * Prkna: vodorovné pásy po čtyřech pixelech, oddělené tmavou spárou,
     * a v každém pásu jedna svislá spára posunutá proti sousedním.
     */
    private static int planks(int x, int y)
    {
        int band = y >> 2;

        if((y & 3) == 0)
        {
            return PLANK_SEAM;
        }

        // Posun svislé spáry po pásech, ať prkna nejsou zarovnaná pod sebou.
        if(x == (band * 7 + 3) % BlockAtlas.TILE_PIXELS)
        {
            return PLANK_SEAM;
        }

        // Zrno se táhne vodorovně: podél x se tón mění pomalu, podél y rychle.
        return PLANK_TONES[hash(x >> 1, y) & 3];
    }

    /** Zdivo: řádky po čtyřech pixelech, svislé spáry ob řádek posunuté. */
    private static int bricks(int x, int y)
    {
        int row = y >> 2;

        if((y & 3) == 0)
        {
            return BRICK_SEAM;
        }

        // Posun o půl cihly ob řádek - jinak by spáry tvořily svislé sloupce.
        if(((x + (row % 2) * 4) & 7) == 0)
        {
            return BRICK_SEAM;
        }

        return BRICK_TONES[hash(x, y) & 3];
    }

    /**
     * Kůra: svislé žilkování. Tón se mění rychle podél x a pomalu podél y,
     * takže vzniknou svislé pruhy - přesně naopak než u prken.
     */
    private static int bark(int x, int y)
    {
        return BARK_TONES[hash(x, y >> 2) & 3];
    }

    /**
     * Řez kmenem: letokruhy. Pásmo se vybírá podle vzdálenosti od středu,
     * takže vyjdou soustředné prstence; hash je pak jen rozčeří.
     */
    private static int logRings(int x, int y)
    {
        int size = BlockAtlas.TILE_PIXELS;

        float dx = x - (size - 1) / 2f;
        float dy = y - (size - 1) / 2f;
        int radius = (int) Math.sqrt(dx * dx + dy * dy);

        // Okraj je kůra, aby řez nevypadal, že plave ve vzduchu.
        if(radius >= size / 2 - 1)
        {
            return BARK_TONES[hash(x, y) & 3];
        }

        return RING_TONES[(radius + (hash(x, y) & 1)) % RING_TONES.length];
    }

    /**
     * Listí: hrubší zrno než u trávy, ať je poznat i z dálky.
     *
     * Listí je NEPRŮHLEDNÉ - průhledné by znamenalo řešit, kdy se kreslí stěna
     * mezi dvěma listy, a průhledný průchod by ztrojnásobil počet stěn u každé
     * koruny. Minecraft na nízké nastavení grafiky dělá totéž.
     */
    private static int leaves(int x, int y)
    {
        return LEAF_TONES[hash(x >> 1, y >> 1) & 3];
    }

    /**
     * Pochodeň: tyčka a nahoře plamínek.
     *
     * Kreslí se přes celou dlaždici, i když model zabírá jen dva pixely na
     * šířku - mesher si z dlaždice vezme jen ten pruh, který kvádru odpovídá
     * (viz ChunkMesh.emitBox). Textura tak zůstane obyčejná čtvercová dlaždice.
     */
    private static int torch(int x, int y)
    {
        int size = BlockAtlas.TILE_PIXELS;

        // Plamínek na horních třech řádcích, tyčka pod ním.
        if(y >= size - 4)
        {
            return TORCH_FLAME[hash(x, y) % TORCH_FLAME.length];
        }

        return TORCH_STICK[hash(x, y) & 1];
    }

    /**
     * Praskliny, stádium 0 až 9.
     *
     * ⚠️ Vzor musí RŮST: co je prasklé ve stádiu 3, musí být prasklé i ve 4.
     * Kdyby se každé stádium losovalo zvlášť, praskliny by při kopání
     * poskakovaly. Proto se porovnává jeden a týž hash s rostoucím prahem -
     * pixel jednou prasklý už zůstane.
     */
    private static int crack(int x, int y, int stage)
    {
        int noise = hash(x, y) & 255;
        int threshold = 6 + stage * 13;

        if(noise < threshold)
        {
            return CRACK_DARK;
        }

        // Světlejší lem kolem prasklin, ať mají tvar a nevypadají jako šum.
        if(noise < threshold + 26)
        {
            return CRACK_EDGE;
        }

        return 0x00000000;   // průhledné - textura bloku prosvítá
    }

    /** Vršek crafting table: prkna s vyřezanou mřížkou 2x2. */
    private static int tableTop(int x, int y)
    {
        int size = BlockAtlas.TILE_PIXELS;

        boolean frame = x == 0 || y == 0 || x == size - 1 || y == size - 1;
        boolean cross = x == size / 2 - 1 || x == size / 2 || y == size / 2 - 1 || y == size / 2;

        if(frame || cross)
        {
            return TABLE_TOP_GRID;
        }

        return PLANK_TONES[hash(x >> 1, y >> 1) & 3];
    }

    // ------------------------------------------------------------------
    // skin postavy
    // ------------------------------------------------------------------

    private static final int[] SKIN_TONES  = {0xFFC69C7C, 0xFFBE9474, 0xFFCCA383, 0xFFB88E6E};
    private static final int[] HAIR_TONES  = {0xFF3B2A1E, 0xFF34251A, 0xFF412F22, 0xFF2E2117};
    private static final int[] SHIRT_TONES = {0xFF1F9C9C, 0xFF1A9292, 0xFF24A6A6, 0xFF178888};
    private static final int[] PANTS_TONES = {0xFF3A3A9C, 0xFF343492, 0xFF4040A6, 0xFF2F2F88};
    private static final int[] SHOE_TONES  = {0xFF4A4A4A, 0xFF434343, 0xFF515151, 0xFF3C3C3C};
    private static final int EYE_WHITE = 0xFFF2F2F2;
    private static final int EYE_IRIS  = 0xFF4A3A9C;
    private static final int MOUTH     = 0xFF8A5A48;

    /**
     * Skin postavy jako textura.
     *
     * ⚠️ TOHLE JE JEDINÉ MÍSTO, KDE SE SKIN VYMĚNÍ. Skutečný skin znamená
     * nahradit playerSkinPixels() načtením PNG 64x64:
     *
     *   BufferedImage image = ImageIO.read(...);
     *   int[] pixels = image.getRGB(0, 0, 64, 64, null, 0, 64);
     *
     * getRGB vrací 0xAARRGGBB po řádcích SHORA, tedy přesně v pořadí, ve kterém
     * je tahle metoda chce. PlayerModelMesh o původu pixelů neví - jeho UV jsou
     * souřadnice šablony skinu z Minecraftu.
     *
     * ⚠️ Pixely jdou do GL v pořadí OBRÁZKU (horní řádek první), ne odspodu
     * jako atlas. GL pak má t = 0 u horního okraje a UV modelu jsou rovnou
     * souřadnice ve skinu dělené 64 - bez překlápění, které by se u načteného
     * PNG snadno zapomnělo.
     */
    public static Texture playerSkin()
    {
        return Texture.fromArgb(playerSkinPixels(),
                PlayerModelMesh.SKIN_SIZE, PlayerModelMesh.SKIN_SIZE, GL_CLAMP_TO_EDGE);
    }

    /**
     * Placeholder skin: každý díl těla má svou barvu (kůže, vlasy, tričko,
     * kalhoty, boty) a obličej dvě oči a pusu, aby šlo poznat, kam postava
     * kouká. Rozložení je šablona Minecraftu 64x64, takže se barvy trefí
     * přesně na díly modelu. Nepokryté části šablony (druhá vrstva - klobouk,
     * bunda) zůstávají průhledné; model je nekreslí.
     */
    static int[] playerSkinPixels()
    {
        int size = PlayerModelMesh.SKIN_SIZE;
        int[] pixels = new int[size * size];

        // Rozbalení kvádrů (u, v, šířka, výška, hloubka) - stejná čísla jako
        // PlayerModelMesh.PARTS.
        paintBox(pixels, 0, 0, 8, 8, 8, SKIN_TONES);      // hlava
        paintBox(pixels, 16, 16, 8, 12, 4, SHIRT_TONES);  // trup
        paintBox(pixels, 40, 16, 4, 12, 4, SKIN_TONES);   // pravá ruka
        paintBox(pixels, 32, 48, 4, 12, 4, SKIN_TONES);   // levá ruka
        paintBox(pixels, 0, 16, 4, 12, 4, PANTS_TONES);   // pravá noha
        paintBox(pixels, 16, 48, 4, 12, 4, PANTS_TONES);  // levá noha

        // Vlasy: celý vršek a týl hlavy, po stranách a na čele horní dva řádky.
        paintRect(pixels, 8, 0, 8, 8, HAIR_TONES);
        paintRect(pixels, 0, 8, 32, 2, HAIR_TONES);
        paintRect(pixels, 24, 8, 8, 8, HAIR_TONES);

        // Obličej: předek hlavy je u = 8..15, v = 8..15.
        pixels[12 * size + 9]  = EYE_WHITE;
        pixels[12 * size + 10] = EYE_IRIS;
        pixels[12 * size + 13] = EYE_IRIS;
        pixels[12 * size + 14] = EYE_WHITE;
        pixels[14 * size + 11] = MOUTH;
        pixels[14 * size + 12] = MOUTH;

        // Rukávy: vršek ruky a horní čtyři pixely jejích boků.
        paintRect(pixels, 44, 16, 4, 4, SHIRT_TONES);
        paintRect(pixels, 40, 20, 16, 4, SHIRT_TONES);
        paintRect(pixels, 36, 48, 4, 4, SHIRT_TONES);
        paintRect(pixels, 32, 52, 16, 4, SHIRT_TONES);

        // Boty: spodek nohy a dolní dva pixely jejích boků.
        paintRect(pixels, 8, 16, 4, 4, SHOE_TONES);
        paintRect(pixels, 0, 30, 16, 2, SHOE_TONES);
        paintRect(pixels, 24, 48, 4, 4, SHOE_TONES);
        paintRect(pixels, 16, 62, 16, 2, SHOE_TONES);

        return pixels;
    }

    /**
     * Vybarví celé rozbalení kvádru ze šablony: nahoře vršek a spodek (každý
     * w x d), pod nimi čtyři boky vedle sebe (d, w, d, w široké, h vysoké).
     */
    private static void paintBox(int[] pixels, int u, int v, int w, int h, int d, int[] tones)
    {
        paintRect(pixels, u + d, v, 2 * w, d, tones);
        paintRect(pixels, u, v + d, 2 * d + 2 * w, h, tones);
    }

    private static void paintRect(int[] pixels, int x, int y, int width, int height, int[] tones)
    {
        int size = PlayerModelMesh.SKIN_SIZE;

        for(int py = y; py < y + height; py++)
        {
            for(int px = x; px < x + width; px++)
            {
                pixels[py * size + px] = tones[hash(px, py) & 3];
            }
        }
    }

    /**
     * Rozhoz bitů ze dvou souřadnic. Násobí se velkými lichými prvočísly,
     * aby se sousední pixely nelišily jen v nejnižším bitu a nevznikly pruhy.
     */
    private static int hash(int x, int y)
    {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) >>> 1;
    }
}
