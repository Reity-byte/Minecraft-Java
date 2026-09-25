package mc;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    public static final Path FILE = GameDirs.path("textures", "blocks.json");

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
        return hasName(name, -1);
    }

    /** Totéž, ale blok exceptId se nepočítá - úprava si smí nechat své jméno. */
    public boolean hasName(String name, int exceptId)
    {
        String wanted = name.trim().toLowerCase(Locale.ROOT);

        for(BlockDef def : blocks())
        {
            if(def.id() != exceptId && def.name().toLowerCase(Locale.ROOT).equals(wanted))
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
     * Registr bez bloku z labu. nextId zůstává - id se znovu nepoužije, takže
     * kostky smazaného bloku v uloženém světě zůstanou "neznámý blok" a nikdy
     * se nepromění v jiný.
     */
    public BlockRegistry without(int id)
    {
        if(id < FIRST_ID || id > LAST_ID)
        {
            return this;
        }

        BlockDef[] copy = byId.clone();
        copy[id] = null;
        return new BlockRegistry(copy, nextId);
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
     *
     * ⚠️ JEDEN ŠPATNÝ BLOK NESHODÍ OSTATNÍ. Soubor se dá psát i ručně a překlep
     * v jednom záznamu (dlaždice 64, zlomkové id, duplicitní jméno) přeskočí
     * jen ten záznam - se zprávou na stderr. Kdyby kvůli němu zmizely všechny
     * bloky, svět by přestal znát i jejich kostky. Celý soubor se zahodí jen
     * tehdy, když to vůbec není JSON nebo chybí format či pole blocks.
     */
    public static BlockRegistry load(Path file)
    {
        if(Files.notExists(file))
        {
            return EMPTY;
        }

        String json;

        try
        {
            json = Files.readString(file, StandardCharsets.UTF_8);
        }
        catch(CharacterCodingException e)
        {
            System.err.println("Bloky " + file + ": neni to text v UTF-8 - jen vestavene bloky");
            return EMPTY;
        }
        catch(IOException e)
        {
            System.err.println("Bloky " + file + " nejdou precist: " + e + " - jen vestavene bloky");
            return EMPTY;
        }

        List<String> problems = new ArrayList<>();

        try
        {
            BlockRegistry registry = parse(json, problems);
            report(file.toString(), problems);
            return registry;
        }
        catch(IllegalArgumentException e)
        {
            report(file.toString(), problems);
            System.err.println("Bloky " + file + ": " + e.getMessage() + " - jen vestavene bloky");
            return EMPTY;
        }
        catch(RuntimeException e)
        {
            // Pojistka: hra kvůli souboru bloků nesmí spadnout, ani kdyby tu byla chyba v kódu.
            System.err.println("Bloky " + file + ": neocekavana chyba " + e + " - jen vestavene bloky");
            return EMPTY;
        }
    }

    /**
     * Zapíše registr. Chyba hru nepoloží: vrátí false a důvod napíše na stderr.
     *
     * ⚠️ ZAPISUJE SE DO DOČASNÉHO SOUBORU A TEN SE PAK PŘEJMENUJE. Pád hry,
     * plný disk nebo výpadek proudu uprostřed zápisu tak nechá starý soubor
     * celý - přepisovat blocks.json napřímo by v tu chvíli nechalo useknutý
     * JSON a s ním přišly všechny bloky z labu.
     *
     * ⚠️ SOUBOR, KTERÝ NEJDE CELÝ NAČÍST, SE PŘED PŘEPSÁNÍM ZÁLOHUJE do
     * blocks.json.bak. Poškozený soubor (nebo soubor, ze kterého load musel
     * přeskočit blok, nebo soubor novějšího formátu) se načetl jen zčásti,
     * takže registr v paměti nemá všechno, co v něm je. První Save z labu by
     * ten zbytek tiše smazal; ruční oprava překlepu by pak neměla z čeho
     * vycházet. Když se zálohovat nepovede, soubor se radši nepřepíše.
     */
    public boolean save(Path file)
    {
        return SafeFiles.writeAtomically(file, toJson(), BlockRegistry::loadsCompletely, "Bloky");
    }

    /** Přečte se soubor bez jediné výhrady? Nic nevypisuje - to už udělal load. */
    private static boolean loadsCompletely(Path file)
    {
        try
        {
            List<String> problems = new ArrayList<>();
            parse(Files.readString(file, StandardCharsets.UTF_8), problems);
            return problems.isEmpty();
        }
        catch(IOException | RuntimeException e)
        {
            return false;
        }
    }

    /**
     * Registr jako JSON - přesně to, co zapíše save(). Bloky podle id, odsazení
     * dvě mezery, "\n" i na Windows (soubor má vypadat všude stejně, ať jde
     * porovnat nebo dát do gitu), na konci nový řádek.
     */
    String toJson()
    {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"format\": ").append(FORMAT).append(",\n");
        out.append("  \"nextId\": ").append(nextId).append(",\n");

        List<BlockDef> list = blocks();

        if(list.isEmpty())
        {
            out.append("  \"blocks\": []\n");
        }
        else
        {
            out.append("  \"blocks\": [\n");

            for(int i = 0; i < list.size(); i++)
            {
                BlockDef def = list.get(i);

                // Float.toString nezávisí na locale (vždycky tečka) a vrací nejkratší
                // zápis, který se přečte zpátky na tentýž float. NaN a nekonečno
                // JSON nezná - null z nich udělá jeden přeskočený blok místo
                // souboru, který nejde přečíst celý.
                String hardness = Float.isFinite(def.hardness()) ? Float.toString(def.hardness()) : "null";

                out.append("    {\n");
                out.append("      \"id\": ").append(def.id()).append(",\n");
                out.append("      \"name\": ").append(Json.quote(def.name())).append(",\n");
                out.append("      \"hardness\": ").append(hardness).append(",\n");
                out.append("      \"solid\": ").append(def.solid()).append(",\n");
                out.append("      \"opaque\": ").append(def.opaque()).append(",\n");
                out.append("      \"tiles\": {\"top\": ").append(def.topTile())
                        .append(", \"side\": ").append(def.sideTile())
                        .append(", \"bottom\": ").append(def.bottomTile()).append("}\n");
                out.append(i < list.size() - 1 ? "    },\n" : "    }\n");
            }

            out.append("  ]\n");
        }

        return out.append("}\n").toString();
    }

    /**
     * Registr z JSON textu. Když to není JSON nebo chybí format či blocks,
     * hodí IllegalArgumentException se srozumitelnou zprávou; neplatné
     * jednotlivé bloky jen přeskočí a ohlásí na stderr.
     */
    static BlockRegistry fromJson(String json)
    {
        List<String> problems = new ArrayList<>();

        try
        {
            return parse(json, problems);
        }
        finally
        {
            report("", problems);
        }
    }

    private static void report(String source, List<String> problems)
    {
        String prefix = source.isEmpty() ? "Bloky: " : "Bloky " + source + ": ";

        for(String problem : problems)
        {
            System.err.println(prefix + problem);
        }
    }

    /**
     * Vlastní čtení. Chyby celého souboru hází jako IllegalArgumentException,
     * výhrady k jednotlivým blokům (a varování) přidá do problems - load je
     * vypíše, save podle nich pozná, že je potřeba záloha.
     *
     * ⚠️ nextId = max(nextId ze souboru, největší id ze souboru + 1, FIRST_ID)
     * a do "největšího id" se počítají i PŘESKOČENÉ bloky. Přeskočený blok
     * může mít kostky v uloženém světě; kdyby jeho id dostal nový blok,
     * po opravě souboru by se o id přetahovaly dva bloky.
     */
    private static BlockRegistry parse(String json, List<String> problems)
    {
        if(!(Json.parse(json) instanceof Map<?, ?> root))
        {
            throw new IllegalArgumentException("koren neni objekt");
        }

        if(!(root.get("format") instanceof Double format))
        {
            throw new IllegalArgumentException("chybi cislo \"format\"");
        }
        if(format > FORMAT)
        {
            // Stejně jako GENERATOR_VERSION: varovat, ale číst - neznámá pole
            // se ignorují a známé bloky zůstanou.
            problems.add("format " + number(format) + " je novejsi nez " + FORMAT
                    + " - nezname udaje se ignoruji");
        }

        if(!(root.get("blocks") instanceof List<?> entries))
        {
            throw new IllegalArgumentException("chybi pole \"blocks\"");
        }

        BlockDef[] byId = new BlockDef[LAST_ID + 1];
        Set<String> names = new HashSet<>();
        int next = FIRST_ID;

        Object rawNext = root.get("nextId");

        if(rawNext != null)
        {
            Integer fileNext = wholeNumber(rawNext);

            if(fileNext == null)
            {
                problems.add("nextId neni cele cislo - dopocita se z bloku");
            }
            else if(fileNext > LAST_ID + 1)
            {
                // Radši plný registr než riskovat, že se nějaké id použije podruhé.
                problems.add("nextId " + fileNext + " je za koncem rozsahu - nova id dosla");
                next = LAST_ID + 1;
            }
            else
            {
                next = Math.max(next, fileNext);
            }
        }

        for(int i = 0; i < entries.size(); i++)
        {
            String where = "blok c. " + (i + 1);

            if(!(entries.get(i) instanceof Map<?, ?> entry))
            {
                problems.add(where + " preskocen: neni to objekt");
                continue;
            }

            if(!(entry.get("id") instanceof Double rawId))
            {
                problems.add(where + " preskocen: chybi id");
                continue;
            }
            if(rawId != Math.rint(rawId) || rawId < FIRST_ID || rawId > LAST_ID)
            {
                problems.add(where + " preskocen: id " + number(rawId)
                        + " neni cele cislo od " + FIRST_ID + " do " + LAST_ID);
                continue;
            }

            int id = (int) (double) rawId;
            where = "blok " + id + " (c. " + (i + 1) + ")";

            // Id je platné, takže ho tenhle záznam drží, i kdyby se dál přeskočil.
            next = Math.max(next, id + 1);

            if(byId[id] != null)
            {
                problems.add(where + " preskocen: id " + id + " uz ma blok \"" + byId[id].name() + "\"");
                continue;
            }

            String reason = null;
            Integer top = null, side = null, bottom = null;

            Object name = entry.get("name");
            Object hardness = entry.get("hardness");
            Object solid = entry.get("solid");
            Object opaque = entry.get("opaque");

            if(!(name instanceof String))
            {
                reason = "chybi name nebo to neni text";
            }
            else if(!(hardness instanceof Double))
            {
                reason = "chybi hardness nebo to neni cislo";
            }
            else if(!(solid instanceof Boolean))
            {
                reason = "chybi solid nebo to neni true/false";
            }
            else if(!(opaque instanceof Boolean))
            {
                reason = "chybi opaque nebo to neni true/false";
            }
            else if(!(entry.get("tiles") instanceof Map<?, ?> tiles))
            {
                reason = "chybi objekt tiles";
            }
            else
            {
                top = wholeNumber(tiles.get("top"));
                side = wholeNumber(tiles.get("side"));
                bottom = wholeNumber(tiles.get("bottom"));

                if(top == null || side == null || bottom == null)
                {
                    reason = "tiles musi mit cela cisla top, side a bottom";
                }
                else
                {
                    reason = validate((String) name, (float) (double) (Double) hardness, top, side, bottom);
                }
            }

            if(reason != null)
            {
                problems.add(where + " preskocen: " + reason);
                continue;
            }

            String trimmed = ((String) name).trim();

            if(!names.add(trimmed.toLowerCase(Locale.ROOT)))
            {
                problems.add(where + " preskocen: jmeno \"" + trimmed + "\" uz ma jiny blok");
                continue;
            }

            byId[id] = new BlockDef((byte) id, trimmed, (float) (double) (Double) hardness,
                    (Boolean) solid, (Boolean) opaque, top, side, bottom);
        }

        return new BlockRegistry(byId, next);
    }

    /** Celé číslo z JSON hodnoty, nebo null pro jiný typ, zlomek a číslo mimo int. */
    private static Integer wholeNumber(Object value)
    {
        if(value instanceof Double d && d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE)
        {
            return (int) (double) d;
        }

        return null;
    }

    /** Číslo do zprávy: celé bez ".0". */
    private static String number(double value)
    {
        return value == Math.rint(value) && Math.abs(value) < 1e15
                ? Long.toString((long) value)
                : Double.toString(value);
    }
}
