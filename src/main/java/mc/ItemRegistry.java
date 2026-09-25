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
 * Předměty: vestavěné v kódu a z labu v textures/items.json.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ STEJNÁ PRAVIDLA JAKO BlockRegistry, jen pro předměty:
 *
 *   256 až 511   vestavěné předměty (konstanty tady, jako bloky ve World)
 *   512 až 1023  předměty z labu; nextId jen roste, id se nikdy nepoužije
 *                dvakrát - uložený svět by jinak po smazání a založení
 *                předmětu měl v inventáři něco jiného
 *
 * Dlaždice 0 až BUILT_IN_TILES-1 v atlasu předmětů patří vestavěným,
 * zbytek labu. Instance je neměnná, aktivní registr se jen vymění.
 *
 * Jeden špatný předmět v souboru neshodí ostatní; poškozený soubor se
 * ohlásí na stderr a hra jede s vestavěnými předměty.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL.
 */
public final class ItemRegistry {

    // --- vestavěné předměty ---
    public static final int STICK = Items.FIRST_ITEM;
    public static final int COAL = Items.FIRST_ITEM + 1;

    /** Poslední id vestavěného předmětu. Nový vestavěný předmět ho posune. */
    public static final int LAST_BUILT_IN = COAL;

    /** Dlaždice vestavěných předmětů v atlasu předmětů. */
    public static final int TILE_STICK = 0;
    public static final int TILE_COAL = 1;

    /** Kolik dlaždic atlasu předmětů mají vestavěné předměty (rezerva pro další). */
    public static final int BUILT_IN_TILES = 8;

    private static final ItemDef[] BUILT_IN = {
            ItemDef.plain(STICK, "Stick", TILE_STICK),
            ItemDef.plain(COAL, "Coal", TILE_COAL),
    };

    /** Předměty z labu: FIRST_ID až Items.LAST_ID. */
    public static final int FIRST_ID = 512;
    public static final int LAST_ID = Items.LAST_ID;

    public static final int FORMAT = 1;

    /** Vedle blocks.json a atlasu - nepovinná data labu. */
    public static final Path FILE = Path.of("textures", "items.json");

    public static final int MAX_NAME_LENGTH = BlockRegistry.MAX_NAME_LENGTH;

    /** Kolik dlaždic má atlas předmětů - stejná mřížka jako atlas bloků. */
    public static final int TILES = BlockAtlas.TILES_PER_ROW * BlockAtlas.TILES_PER_ROW;

    private static final ItemRegistry EMPTY = new ItemRegistry(new ItemDef[LAST_ID + 1], FIRST_ID);

    private static volatile ItemRegistry active = EMPTY;

    /** Index je id; null = nepoužité. Jen rozsah labu - vestavěné jsou v BUILT_IN. */
    private final ItemDef[] byId;
    private final int nextId;

    private ItemRegistry(ItemDef[] byId, int nextId)
    {
        this.byId = byId;
        this.nextId = Math.max(FIRST_ID, nextId);
    }

    // ------------------------------------------------------------------
    // aktivní registr
    // ------------------------------------------------------------------

    public static ItemRegistry empty()
    {
        return EMPTY;
    }

    public static ItemRegistry active()
    {
        return active;
    }

    public static void activate(ItemRegistry registry)
    {
        active = registry == null ? EMPTY : registry;
    }

    /** Předmět podle id - vestavěný, nebo z aktivního registru; jinak null. */
    public static ItemDef lookup(int id)
    {
        return active.get(id);
    }

    // ------------------------------------------------------------------
    // dotazy
    // ------------------------------------------------------------------

    /** Vestavěné předměty podle id. */
    public static List<ItemDef> builtIn()
    {
        return List.of(BUILT_IN);
    }

    /** Předmět podle id (vestavěný i z labu), nebo null. */
    public ItemDef get(int id)
    {
        if(id >= Items.FIRST_ITEM && id <= LAST_BUILT_IN)
        {
            return BUILT_IN[id - Items.FIRST_ITEM];
        }

        return id >= FIRST_ID && id <= LAST_ID ? byId[id] : null;
    }

    /** Předměty z labu podle id. */
    public List<ItemDef> labItems()
    {
        List<ItemDef> list = new ArrayList<>();

        for(int id = FIRST_ID; id <= LAST_ID; id++)
        {
            if(byId[id] != null)
            {
                list.add(byId[id]);
            }
        }

        return list;
    }

    /** Všechny předměty: nejdřív vestavěné, pak z labu, každé podle id. */
    public List<ItemDef> items()
    {
        List<ItemDef> list = new ArrayList<>(builtIn());
        list.addAll(labItems());
        return list;
    }

    public int nextId()
    {
        return nextId;
    }

    public boolean isFull()
    {
        return nextId > LAST_ID;
    }

    /** Má už nějaký předmět (i vestavěný) tohle jméno? Bez ohledu na velikost písmen. */
    public boolean hasName(String name)
    {
        return hasName(name, -1);
    }

    /** Totéž, ale předmět exceptId se nepočítá - úprava si smí nechat své jméno. */
    public boolean hasName(String name, int exceptId)
    {
        String wanted = name.trim().toLowerCase(Locale.ROOT);

        for(ItemDef def : items())
        {
            if(def.id() != exceptId && def.name().toLowerCase(Locale.ROOT).equals(wanted))
            {
                return true;
            }
        }

        return false;
    }

    /** Dlaždice atlasu předmětů, které má nějaký předmět z labu. */
    public boolean[] usedTiles()
    {
        boolean[] used = new boolean[TILES];

        for(ItemDef def : labItems())
        {
            used[def.tile()] = true;
        }

        return used;
    }

    /** První dlaždice pro nový předmět z labu, kterou nikdo nemá; -1 = žádná. */
    public int freeTile()
    {
        boolean[] used = usedTiles();

        for(int tile = BUILT_IN_TILES; tile < TILES; tile++)
        {
            if(!used[tile])
            {
                return tile;
            }
        }

        return -1;
    }

    // ------------------------------------------------------------------
    // změny - vždycky nový registr
    // ------------------------------------------------------------------

    /** Nový předmět s id nextId(). Do registru ho nepřidává - to dělá with(). */
    public ItemDef define(String name, int tile)
    {
        if(isFull())
        {
            throw new IllegalStateException("dosla id predmetu z labu");
        }

        return ItemDef.plain(nextId, name.trim(), tile);
    }

    /** Registr s přidaným (nebo nahrazeným) předmětem z labu. */
    public ItemRegistry with(ItemDef def)
    {
        if(def.id() < FIRST_ID || def.id() > LAST_ID)
        {
            throw new IllegalArgumentException("id " + def.id() + " neni z rozsahu labu");
        }

        ItemDef[] copy = byId.clone();
        copy[def.id()] = def;
        return new ItemRegistry(copy, Math.max(nextId, def.id() + 1));
    }

    /** Registr bez předmětu z labu. nextId zůstává - id se znovu nepoužije. */
    public ItemRegistry without(int id)
    {
        if(id < FIRST_ID || id > LAST_ID)
        {
            return this;
        }

        ItemDef[] copy = byId.clone();
        copy[id] = null;
        return new ItemRegistry(copy, nextId);
    }

    /** Kontrola hodnot předmětu bez výdrže - viz plné validate(). */
    public static String validate(String name, int tile, int maxStack, ItemDef.Tool tool, float toolSpeed)
    {
        return validate(name, tile, maxStack, tool, toolSpeed, 0);
    }

    /** Kontrola hodnot předmětu; null = v pořádku, jinak důvod anglicky (ukazuje lab). */
    public static String validate(String name, int tile, int maxStack, ItemDef.Tool tool, float toolSpeed,
                                  int durability)
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
        if(tile < 0 || tile >= TILES)
        {
            return "Tile " + tile + " is outside the item atlas";
        }
        if(maxStack < 1 || maxStack > ItemStack.MAX_COUNT)
        {
            return "Stack size must be 1 to " + ItemStack.MAX_COUNT;
        }
        if(tool == null)
        {
            return "Unknown tool";
        }
        if(!(toolSpeed >= 1f && toolSpeed <= ItemDef.MAX_TOOL_SPEED))
        {
            return "Tool speed must be 1 to " + (int) ItemDef.MAX_TOOL_SPEED;
        }
        if(durability < 0 || durability > ItemDef.MAX_DURABILITY)
        {
            return "Durability must be 0 to " + ItemDef.MAX_DURABILITY;
        }
        if(durability > 0 && maxStack != 1)
        {
            return "Items that wear out stack only 1";
        }

        return null;
    }

    // ------------------------------------------------------------------
    // soubor textures/items.json
    // ------------------------------------------------------------------

    /** Načte registr; chybějící soubor mlčky prázdný, poškozený se ohlásí. */
    public static ItemRegistry load(Path file)
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
            System.err.println("Predmety " + file + ": neni to text v UTF-8 - jen vestavene predmety");
            return EMPTY;
        }
        catch(IOException e)
        {
            System.err.println("Predmety " + file + " nejdou precist: " + e + " - jen vestavene predmety");
            return EMPTY;
        }

        List<String> problems = new ArrayList<>();

        try
        {
            ItemRegistry registry = parse(json, problems);
            report(file.toString(), problems);
            return registry;
        }
        catch(IllegalArgumentException e)
        {
            report(file.toString(), problems);
            System.err.println("Predmety " + file + ": " + e.getMessage() + " - jen vestavene predmety");
            return EMPTY;
        }
        catch(RuntimeException e)
        {
            System.err.println("Predmety " + file + ": neocekavana chyba " + e + " - jen vestavene predmety");
            return EMPTY;
        }
    }

    /** Zapíše atomicky, se zálohou souboru, který nejde načíst celý (jako blocks.json). */
    public boolean save(Path file)
    {
        return SafeFiles.writeAtomically(file, toJson(), ItemRegistry::loadsCompletely, "Predmety");
    }

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

    /** Registr jako JSON - jen předměty z labu; vestavěné jsou v kódu. */
    String toJson()
    {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"format\": ").append(FORMAT).append(",\n");
        out.append("  \"nextId\": ").append(nextId).append(",\n");

        List<ItemDef> list = labItems();

        if(list.isEmpty())
        {
            out.append("  \"items\": []\n");
            return out.append("}\n").toString();
        }

        out.append("  \"items\": [\n");

        for(int i = 0; i < list.size(); i++)
        {
            ItemDef def = list.get(i);
            String speed = Float.isFinite(def.toolSpeed()) ? Float.toString(def.toolSpeed()) : "null";

            out.append("    {\n");
            out.append("      \"id\": ").append(def.id()).append(",\n");
            out.append("      \"name\": ").append(Json.quote(def.name())).append(",\n");
            out.append("      \"tile\": ").append(def.tile()).append(",\n");
            out.append("      \"stack\": ").append(def.maxStack()).append(",\n");
            out.append("      \"tool\": ").append(Json.quote(def.tool().key())).append(",\n");
            out.append("      \"toolSpeed\": ").append(speed).append(",\n");
            out.append("      \"durability\": ").append(def.durability()).append("\n");
            out.append(i < list.size() - 1 ? "    },\n" : "    }\n");
        }

        out.append("  ]\n");
        return out.append("}\n").toString();
    }

    static ItemRegistry fromJson(String json)
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
        String prefix = source.isEmpty() ? "Predmety: " : "Predmety " + source + ": ";

        for(String problem : problems)
        {
            System.err.println(prefix + problem);
        }
    }

    /**
     * Vlastní čtení. Chyby celého souboru jako IllegalArgumentException,
     * výhrady k jednotlivým předmětům do problems. nextId se počítá i ze
     * PŘESKOČENÝCH předmětů - jejich id už mohou nést uložené světy.
     *
     * stack, tool, toolSpeed a durability smí chybět (obyčejný předmět: 64,
     * žádný nástroj, 1, nerozbitný) - ručně psaný soubor pak stačí s id,
     * name a tile.
     */
    private static ItemRegistry parse(String json, List<String> problems)
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
            problems.add("format " + format + " je novejsi nez " + FORMAT + " - nezname udaje se ignoruji");
        }

        if(!(root.get("items") instanceof List<?> entries))
        {
            throw new IllegalArgumentException("chybi pole \"items\"");
        }

        ItemDef[] byId = new ItemDef[LAST_ID + 1];
        Set<String> names = new HashSet<>();
        int next = FIRST_ID;

        for(ItemDef def : BUILT_IN)
        {
            names.add(def.name().toLowerCase(Locale.ROOT));
        }

        Object rawNext = root.get("nextId");

        if(rawNext != null)
        {
            Integer fileNext = wholeNumber(rawNext);

            if(fileNext == null)
            {
                problems.add("nextId neni cele cislo - dopocita se z predmetu");
            }
            else if(fileNext > LAST_ID + 1)
            {
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
            String where = "predmet c. " + (i + 1);

            if(!(entries.get(i) instanceof Map<?, ?> entry))
            {
                problems.add(where + " preskocen: neni to objekt");
                continue;
            }

            Integer id = wholeNumber(entry.get("id"));

            if(id == null || id < FIRST_ID || id > LAST_ID)
            {
                problems.add(where + " preskocen: id neni cele cislo od " + FIRST_ID + " do " + LAST_ID);
                continue;
            }

            where = "predmet " + id + " (c. " + (i + 1) + ")";
            next = Math.max(next, id + 1);

            if(byId[id] != null)
            {
                problems.add(where + " preskocen: id " + id + " uz ma predmet \"" + byId[id].name() + "\"");
                continue;
            }

            Object name = entry.get("name");
            Integer tile = wholeNumber(entry.get("tile"));
            Integer stack = entry.containsKey("stack") ? wholeNumber(entry.get("stack")) : ItemStack.MAX_COUNT;
            ItemDef.Tool tool = entry.containsKey("tool")
                    ? (entry.get("tool") instanceof String key ? ItemDef.Tool.byKey(key) : null)
                    : ItemDef.Tool.NONE;
            Float speed = entry.containsKey("toolSpeed")
                    ? (entry.get("toolSpeed") instanceof Double d ? (float) (double) d : null)
                    : 1f;
            Integer durability = entry.containsKey("durability") ? wholeNumber(entry.get("durability")) : 0;

            String reason;

            if(!(name instanceof String text))
            {
                reason = "chybi name nebo to neni text";
            }
            else if(tile == null)
            {
                reason = "chybi tile nebo to neni cele cislo";
            }
            else if(stack == null)
            {
                reason = "stack neni cele cislo";
            }
            else if(speed == null)
            {
                reason = "toolSpeed neni cislo";
            }
            else if(durability == null)
            {
                reason = "durability neni cele cislo";
            }
            else
            {
                reason = validate(text, tile, stack, tool, speed, durability);
            }

            if(reason != null)
            {
                problems.add(where + " preskocen: " + reason);
                continue;
            }

            String trimmed = ((String) name).trim();

            if(!names.add(trimmed.toLowerCase(Locale.ROOT)))
            {
                problems.add(where + " preskocen: jmeno \"" + trimmed + "\" uz ma jiny predmet");
                continue;
            }

            byId[id] = new ItemDef(id, trimmed, tile, stack, tool, speed, durability);
        }

        return new ItemRegistry(byId, next);
    }

    private static Integer wholeNumber(Object value)
    {
        if(value instanceof Double d && d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE)
        {
            return (int) (double) d;
        }

        return null;
    }
}
