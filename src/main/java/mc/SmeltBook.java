package mc;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Recepty tavení z labu: textures/smelting.json.
 *
 * ---------------------------------------------------------------------------
 * Stejná úmluva jako RecipeBook: soubor je nepovinný (chybí = jen vestavěné
 * tavení), poškozený se ohlásí a hra jede dál, jeden neplatný recept shodí
 * jen sám sebe, zápis atomicky se zálohou. Recept je surovina -> výsledek
 * a počet; surovina smí mít nejvýš jeden recept (vestavěný má přednost,
 * lab ho nepřebije - Smelting.builtInHas).
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL.
 */
public final class SmeltBook {

    public static final int FORMAT = 1;

    public static final Path FILE = GameDirs.path("textures", "smelting.json");

    private static final SmeltBook EMPTY = new SmeltBook(List.of());

    private static volatile SmeltBook active = EMPTY;

    private final List<Smelting.Recipe> recipes;

    private SmeltBook(List<Smelting.Recipe> recipes)
    {
        this.recipes = recipes;
    }

    public static SmeltBook empty()   { return EMPTY; }
    public static SmeltBook active()  { return active; }

    public static void activate(SmeltBook book)
    {
        active = book == null ? EMPTY : book;
    }

    public List<Smelting.Recipe> recipes()
    {
        return recipes;
    }

    public int size()
    {
        return recipes.size();
    }

    /** Recept z labu pro surovinu, nebo null. */
    public Smelting.Recipe find(int input)
    {
        for(Smelting.Recipe recipe : recipes)
        {
            if(recipe.input() == input)
            {
                return recipe;
            }
        }

        return null;
    }

    /** Kniha s receptem (nahradí recept téže suroviny). Neplatný hodí IllegalArgumentException. */
    public SmeltBook with(Smelting.Recipe recipe)
    {
        String problem = validate(recipe);

        if(problem != null)
        {
            throw new IllegalArgumentException(problem);
        }

        List<Smelting.Recipe> copy = new ArrayList<>(recipes);
        copy.removeIf(r -> r.input() == recipe.input());
        copy.add(recipe);
        return new SmeltBook(Collections.unmodifiableList(copy));
    }

    /** Kniha bez receptu pro tuhle surovinu. */
    public SmeltBook without(int input)
    {
        List<Smelting.Recipe> copy = new ArrayList<>(recipes);
        copy.removeIf(r -> r.input() == input);
        return copy.isEmpty() ? EMPTY : new SmeltBook(Collections.unmodifiableList(copy));
    }

    /** null = v pořádku, jinak důvod anglicky (ukazuje lab). */
    public static String validate(Smelting.Recipe recipe)
    {
        if(recipe == null)
        {
            return "no recipe";
        }
        if(!Items.exists(recipe.input()))
        {
            return "unknown ingredient " + recipe.input();
        }
        if(!Items.exists(recipe.result()))
        {
            return "unknown result " + recipe.result();
        }
        if(Smelting.builtInHas(recipe.input()))
        {
            return "A built-in recipe already smelts " + Items.name(recipe.input());
        }

        int limit = Items.maxStack(recipe.result());

        if(recipe.count() < 1 || recipe.count() > limit)
        {
            return "result count must be 1 to " + limit;
        }

        return null;
    }

    // ------------------------------------------------------------------
    // soubor
    // ------------------------------------------------------------------

    public static SmeltBook load(Path file)
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
            System.err.println("Taveni " + file + ": neni to text v UTF-8 - jen vestavene taveni");
            return EMPTY;
        }
        catch(IOException e)
        {
            System.err.println("Taveni " + file + " nejde precist: " + e + " - jen vestavene taveni");
            return EMPTY;
        }

        List<String> problems = new ArrayList<>();

        try
        {
            SmeltBook book = parse(json, problems);
            report(file.toString(), problems);
            return book;
        }
        catch(RuntimeException e)
        {
            report(file.toString(), problems);
            System.err.println("Taveni " + file + ": " + e.getMessage() + " - jen vestavene taveni");
            return EMPTY;
        }
    }

    public boolean save(Path file)
    {
        return SafeFiles.writeAtomically(file, toJson(), SmeltBook::loadsCompletely, "Taveni");
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

    String toJson()
    {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"format\": ").append(FORMAT).append(",\n");

        if(recipes.isEmpty())
        {
            out.append("  \"recipes\": []\n");
            return out.append("}\n").toString();
        }

        out.append("  \"recipes\": [\n");

        for(int i = 0; i < recipes.size(); i++)
        {
            Smelting.Recipe r = recipes.get(i);
            out.append("    {\"input\": ").append(r.input())
                    .append(", \"result\": ").append(r.result())
                    .append(", \"count\": ").append(r.count())
                    .append(i < recipes.size() - 1 ? "},\n" : "}\n");
        }

        out.append("  ]\n");
        return out.append("}\n").toString();
    }

    static SmeltBook fromJson(String json)
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
        String prefix = source.isEmpty() ? "Taveni: " : "Taveni " + source + ": ";

        for(String problem : problems)
        {
            System.err.println(prefix + problem);
        }
    }

    private static SmeltBook parse(String json, List<String> problems)
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
            problems.add("format " + format + " je novejsi nez " + FORMAT + " - co nezname, se preskoci");
        }
        if(!(root.get("recipes") instanceof List<?> list))
        {
            throw new IllegalArgumentException("chybi pole \"recipes\"");
        }

        List<Smelting.Recipe> parsed = new ArrayList<>();

        for(int i = 0; i < list.size(); i++)
        {
            if(!(list.get(i) instanceof Map<?, ?> map))
            {
                problems.add("recept " + i + " neni objekt - preskocen");
                continue;
            }

            Integer input = whole(map.get("input"));
            Integer result = whole(map.get("result"));
            Integer count = map.containsKey("count") ? whole(map.get("count")) : Integer.valueOf(1);

            if(input == null || result == null || count == null)
            {
                problems.add("recept " + i + ": chybi input, result nebo count - preskocen");
                continue;
            }

            Smelting.Recipe recipe = new Smelting.Recipe(input, result, count);
            String problem = validate(recipe);

            if(problem != null)
            {
                problems.add("recept " + i + ": " + problem + " - preskocen");
                continue;
            }

            if(parsed.stream().anyMatch(r -> r.input() == recipe.input()))
            {
                problems.add("recept " + i + ": surovina " + input + " uz recept ma - preskocen");
                continue;
            }

            parsed.add(recipe);
        }

        return parsed.isEmpty() ? EMPTY : new SmeltBook(Collections.unmodifiableList(parsed));
    }

    private static Integer whole(Object value)
    {
        if(value instanceof Double d && d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE)
        {
            return (int) (double) d;
        }

        return null;
    }
}
