package mc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bloky z texture labu nad vestavěnými konstantami ve World.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ ROZSAH ID JE ROZDĚLENÝ NAPEVNO: 0 až 63 patří vestavěným blokům v kódu,
 * FIRST_ID (64) až LAST_ID (127) blokům z labu. Kdyby lab přiděloval hned
 * za posledním vestavěným blokem (15, 16, ...), další blok přidaný do kódu
 * by dostal id, které už v uloženém světě nese blok z labu - a ten by se
 * tiše proměnil v něco jiného. Nad 127 se nejde: id je byte a záporná čísla
 * by rozbila porovnání typu id >= FIRST_ID.
 *
 * ⚠️ ID SE NIKDY NEPOUŽIJE DVAKRÁT. nextId se ukládá do souboru a jen roste;
 * nový blok dostane nextId, ne "nejnižší volné". Blok ručně smazaný
 * z blocks.json tak svoje id nepředá dalšímu - v uloženém světě by se jinak
 * jeho kostky přejmenovaly na nový blok.
 *
 * Instance je NEMĚNNÁ a aktivní registr se jen vymění (activate). Ptá se
 * na něj mesher, světlo i fyzika, a výměna jednoho odkazu nemůže nikoho
 * zastihnout v půlce změny.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, takže jde celé otestovat headless.
 */
public final class BlockRegistry {

    /** První id pro bloky z labu. Pod ním je rezerva pro vestavěné bloky v kódu. */
    public static final int FIRST_ID = 64;
    public static final int LAST_ID = 127;

    /** Verze formátu blocks.json. */
    public static final int FORMAT = 1;

    /** Vedle atlasu, relativně k pracovnímu adresáři - stejně jako textures/atlas.png. */
    public static final Path FILE = Path.of("textures", "blocks.json");

    /** Delší jméno by se nevešlo do labu ani do ladicího výpisu. */
    public static final int MAX_NAME_LENGTH = 20;

    /** Nejtvrdší vestavěný blok je železo (3 s); víc než minuta nedává smysl. */
    public static final float MAX_HARDNESS = 60f;

    private static final BlockRegistry EMPTY = new BlockRegistry(new BlockDef[LAST_ID + 1], FIRST_ID);

    private static volatile BlockRegistry active = EMPTY;

    /** Index je id bloku; null = id z labu nepoužité. */
    private final BlockDef[] byId;
    private final int nextId;

    private BlockRegistry(BlockDef[] byId, int nextId)
    {
        this.byId = byId;
        this.nextId = Math.max(FIRST_ID, nextId);
    }

    // ------------------------------------------------------------------
    // aktivní registr
    // ------------------------------------------------------------------

    /** Registr bez bloků z labu - hra se pak chová přesně jako bez blocks.json. */
    public static BlockRegistry empty()
    {
        return EMPTY;
    }

    public static BlockRegistry active()
    {
        return active;
    }

    public static void activate(BlockRegistry registry)
    {
        active = registry == null ? EMPTY : registry;
    }

    /** Blok z labu podle id v aktivním registru, nebo null. */
    public static BlockDef lookup(byte id)
    {
        return active.get(id);
    }

    // ------------------------------------------------------------------
    // dotazy
    // ------------------------------------------------------------------

    /** Blok z labu, nebo null pro vestavěné, nepoužité a záporné id. */
    public BlockDef get(byte id)
    {
        return id >= FIRST_ID ? byId[id] : null;
    }

    /** Všechny bloky z labu, seřazené podle id. */
    public List<BlockDef> blocks()
    {
        List<BlockDef> list = new ArrayList<>();

        for(int id = FIRST_ID; id <= LAST_ID; id++)
        {
            if(byId[id] != null)
            {
                list.add(byId[id]);
            }
        }

        return list;
    }

    public int size()
    {
        return blocks().size();
    }

    /** Id, které dostane příští nový blok. Větší než LAST_ID = došla. */
    public int nextId()
    {
        return nextId;
    }

    public boolean isFull()
    {
        return nextId > LAST_ID;
    }

    /** Je už blok z labu s tímhle jménem? Bez ohledu na velikost písmen. */
    public boolean hasName(String name)
    {
        String wanted = name.trim().toLowerCase(Locale.ROOT);

        for(BlockDef def : blocks())
        {
            if(def.name().toLowerCase(Locale.ROOT).equals(wanted))
            {
                return true;
            }
        }

        return false;
    }

    /** Dlaždice, které používá aspoň jeden blok z labu (index = dlaždice). */
    public boolean[] usedTiles()
    {
        boolean[] used = new boolean[BlockAtlas.TILES_PER_ROW * BlockAtlas.TILES_PER_ROW];

        for(BlockDef def : blocks())
        {
            used[def.topTile()] = true;
            used[def.sideTile()] = true;
            used[def.bottomTile()] = true;
        }

        return used;
    }

    // ------------------------------------------------------------------
    // změny - vždycky nový registr, tenhle zůstává, jak byl
    // ------------------------------------------------------------------

    /**
     * Nový blok s id nextId(). Do registru ho NEPŘIDÁVÁ - to dělá with().
     * Rozdělené schválně: lab si z návrhu staví dočasný registr pro živý
     * náhled a teprve Create ho uloží.
     */
    public BlockDef define(String name, float hardness, boolean solid, boolean opaque,
                           int topTile, int sideTile, int bottomTile)
    {
        if(isFull())
        {
            throw new IllegalStateException("dosla id bloku z labu");
        }

        return new BlockDef((byte) nextId, name.trim(), hardness, solid, opaque,
                topTile, sideTile, bottomTile);
    }

    /** Registr s přidaným (nebo nahrazeným) blokem. nextId se posune za něj. */
    public BlockRegistry with(BlockDef def)
    {
        if(def.id() < FIRST_ID)
        {
            throw new IllegalArgumentException("id " + def.id() + " neni z rozsahu labu");
        }

        BlockDef[] copy = byId.clone();
        copy[def.id()] = def;
        return new BlockRegistry(copy, Math.max(nextId, def.id() + 1));
    }

    /**
     * Kontrola hodnot bloku. Vrací null, když je všechno v pořádku, jinak
     * důvod anglicky - ukazuje ho lab.
     */
    public static String validate(String name, float hardness, int topTile, int sideTile, int bottomTile)
    {
        String trimmed = name == null ? "" : name.trim();

        if(trimmed.isEmpty())
        {
            return "Name is empty";
        }
        if(trimmed.length() > MAX_NAME_LENGTH)
        {
            return "Name is longer than " + MAX_NAME_LENGTH;
        }
        for(int i = 0; i < trimmed.length(); i++)
        {
            char c = trimmed.charAt(i);

            // Font je jen ASCII 32-126 - cokoliv jiného by se kreslilo jako '?'.
            if(c < 32 || c > 126 || c == '"' || c == '\\')
            {
                return "Name has an unsupported character";
            }
        }
        if(!(hardness >= 0f && hardness <= MAX_HARDNESS))
        {
            return "Hardness must be 0 to " + (int) MAX_HARDNESS + " s";
        }

        int tiles = BlockAtlas.TILES_PER_ROW * BlockAtlas.TILES_PER_ROW;

        for(int tile : new int[]{topTile, sideTile, bottomTile})
        {
            if(tile < 0 || tile >= tiles)
            {
                return "Tile " + tile + " is outside the atlas";
            }
        }

        return null;
    }

    // ------------------------------------------------------------------
    // soubor textures/blocks.json
    // ------------------------------------------------------------------

    /**
     * Načte registr. Chybějící soubor je běžný stav (mlčky prázdný registr),
     * poškozený se ohlásí na stderr a hra jede jen s vestavěnými bloky.
     */
    public static BlockRegistry load(Path file)
    {
        // TODO(registry): JSON, viz zadání
        return EMPTY;
    }

    /** Zapíše registr. Chyba hru nepoloží: vrátí false a důvod napíše na stderr. */
    public boolean save(Path file)
    {
        // TODO(registry): JSON, viz zadání
        return false;
    }
}
