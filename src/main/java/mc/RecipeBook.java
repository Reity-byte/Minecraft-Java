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
 * Recepty založené v labu: `textures/recipes.json`.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ PŘIDÁVAJÍ SE K VESTAVĚNÝM, NENAHRAZUJÍ JE. `Recipes.match()` projde
 * nejdřív recepty napsané v kódu a teprve pak tenhle seznam, takže chybějící,
 * prázdný i poškozený soubor znamená hru přesně takovou, jaká byla předtím.
 * Je to ta samá úmluva jako u `textures/blocks.json`: soubor je NEPOVINNÁ
 * data, ne zdroj pravdy.
 *
 * ⚠️ RECEPT SI NENESE VLASTNÍ PRAVIDLA SHODY. Ukládá se přesně to, co umí
 * `Recipes.Recipe` - rozměr, vzor po řádcích shora dolů, výsledek a počet -
 * a vyhodnocuje ho tentýž `matchesShaped()`, jaký vyhodnocuje vestavěné
 * recepty. Lab tedy nemůže vyrobit recept, který by se choval jinak než
 * recept z kódu; kdyby si vymýšlel vlastní porovnávání, byla by dvě místa,
 * kde "co je shoda" a dvě odpovědi na tutéž otázku.
 *
 * ⚠️ NEMĚNNÁ TŘÍDA A JEDEN AKTIVNÍ SEZNAM, jako `BlockRegistry`. Lab si
 * postaví nový seznam a aktivuje ho; `Recipes` čte `active()`. Díky tomu se
 * nově uložený recept projeví v inventáři i na crafting table OKAMŽITĚ,
 * bez restartu - hra se nikde neptá souboru, jen aktivního seznamu.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL ani na GLFW, takže jde celé otestovat headless.
 */
public final class RecipeBook {

    /** Verze formátu souboru. Novější se načte s varováním, jako u blocks.json. */
    public static final int FORMAT = 1;

    /** Vedle blocks.json - obojí jsou nepovinná data labu. */
    public static final Path FILE = Path.of("textures", "recipes.json");

    /**
     * Největší mřížka, kterou umí crafting table. Recept větší než tohle by
     * se nikdy nedal vyrobit, takže ho lab ani soubor nesmí založit.
     */
    public static final int MAX_SIZE = 3;

    private static final RecipeBook EMPTY = new RecipeBook(List.of());

    /**
     * ⚠️ volatile ze stejného důvodu jako u BlockRegistry: aktivní seznam
     * vyměňuje hlavní vlákno a číst ho může i jiné.
     */
    private static volatile RecipeBook active = EMPTY;

    private final List<Recipes.Recipe> recipes;

    private RecipeBook(List<Recipes.Recipe> recipes)
    {
        this.recipes = List.copyOf(recipes);
    }

    public static RecipeBook empty()
    {
        return EMPTY;
    }

    /** Seznam, kterého se ptá Recipes.match(). */
    public static RecipeBook active()
    {
        return active;
    }

    /** Vymění aktivní seznam. Od téhle chvíle platí i v rozehrané hře. */
    public static void activate(RecipeBook book)
    {
        active = book == null ? EMPTY : book;
    }

    /** Recepty v pořadí, v jakém se vyhodnocují (a v jakém se zapíšou). */
    public List<Recipes.Recipe> recipes()
    {
        return recipes;
    }

    public int size()
    {
        return recipes.size();
    }

    public boolean isEmpty()
    {
        return recipes.isEmpty();
    }

    /** Nový seznam s přidaným receptem. Tenhle zůstává, jak byl. */
    public RecipeBook with(Recipes.Recipe recipe)
    {
        String problem = validate(recipe);

        if(problem != null)
        {
            throw new IllegalArgumentException(problem);
        }

        List<Recipes.Recipe> copy = new ArrayList<>(recipes);
        copy.add(normalize(recipe));
        return new RecipeBook(copy);
    }

    /**
     * Ořízne vzor na nejmenší obdélník, ve kterém něco je.
     *
     * ⚠️ MUSÍ SE TO DĚLAT, NENÍ TO KOSMETIKA. Recept si nese svou velikost
     * a hledá se kdekoliv v mřížce; recept 3x3 s jedinou obsazenou buňkou
     * uprostřed by se do malé mřížky 2x2 nevešel VŮBEC, i když potřebuje
     * jednu surovinu. Lab skládá vždycky do 3x3, takže bez ořezu by v malé
     * mřížce nefungoval ani jeden recept, který se tam vejít měl.
     */
    public static Recipes.Recipe normalize(Recipes.Recipe recipe)
    {
        int minX = recipe.width(), maxX = -1, minY = recipe.height(), maxY = -1;

        for(int y = 0; y < recipe.height(); y++)
        {
            for(int x = 0; x < recipe.width(); x++)
            {
                if(recipe.pattern()[y * recipe.width() + x] != World.AIR)
                {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if(maxX < 0)
        {
            return recipe;   // prázdný vzor - validate() ho stejně odmítne
        }

        int width = maxX - minX + 1;
        int height = maxY - minY + 1;

        if(width == recipe.width() && height == recipe.height())
        {
            return recipe;
        }

        byte[] pattern = new byte[width * height];

        for(int y = 0; y < height; y++)
        {
            for(int x = 0; x < width; x++)
            {
                pattern[y * width + x] = recipe.pattern()[(y + minY) * recipe.width() + x + minX];
            }
        }

        return new Recipes.Recipe(width, height, pattern, recipe.result(), recipe.resultCount());
    }

    /**
     * Kontrola receptu. Vrací null, když je v pořádku, jinak důvod anglicky -
     * ukazuje ho lab, takže to musí být věta pro člověka, ne kód chyby.
     */
    public static String validate(Recipes.Recipe recipe)
    {
        if(recipe == null)
        {
            return "no recipe";
        }

        if(recipe.width() < 1 || recipe.height() < 1
                || recipe.width() > MAX_SIZE || recipe.height() > MAX_SIZE)
        {
            return "grid must be 1x1 to " + MAX_SIZE + "x" + MAX_SIZE;
        }

        if(recipe.pattern() == null || recipe.pattern().length != recipe.width() * recipe.height())
        {
            return "pattern does not match the grid size";
        }

        boolean any = false;

        for(byte block : recipe.pattern())
        {
            if(block == World.AIR)
            {
                continue;
            }

            any = true;

            if(!isKnownBlock(block))
            {
                return "unknown ingredient block " + block;
            }
        }

        if(!any)
        {
            return "recipe needs at least one ingredient";
        }

        if(!isKnownBlock(recipe.result()))
        {
            return "unknown result block " + recipe.result();
        }

        if(recipe.resultCount() < 1 || recipe.resultCount() > ItemStack.MAX_COUNT)
        {
            return "result count must be 1 to " + ItemStack.MAX_COUNT;
        }

        return null;
    }

    /**
     * Existuje takový blok?
     *
     * ⚠️ Recepty SMÍ odkazovat na bloky z labu (id 64+). Pravidlo "jen
     * vestavěné bloky" platí pro generátor terénu, protože ten musí fungovat
     * i bez blocks.json; recept je naopak data vedle dat a je v pořádku, aby
     * jedna nepovinná věc odkazovala na druhou. Když blok z receptu zmizí,
     * přeskočí se jen ten recept - stejně jako neplatný blok v blocks.json.
     */
    private static boolean isKnownBlock(byte block)
    {
        if(block == World.AIR)
        {
            return false;
        }

        if(block >= BlockRegistry.FIRST_ID)
        {
            return BlockRegistry.lookup(block) != null;
        }

        return block <= World.LAST_BUILT_IN;
    }

    /** Vyrábí už některý recept (vestavěný i z labu) přesně tenhle vzor? */
    public boolean containsPattern(Recipes.Recipe recipe)
    {
        Recipes.Recipe wanted = normalize(recipe);

        for(Recipes.Recipe existing : recipes)
        {
            if(existing.width() == wanted.width() && existing.height() == wanted.height()
                    && java.util.Arrays.equals(existing.pattern(), wanted.pattern()))
            {
                return true;
            }
        }

        return false;
    }

    // ------------------------------------------------------------------
    // soubor
    // ------------------------------------------------------------------

    /**
     * Načte soubor. Chybějící soubor = prázdný seznam MLČKY a hra je přesně
     * jako dřív; poškozený se ohlásí na stderr a taky skončí prázdným
     * seznamem. Jeden neplatný recept shodí jen sám sebe, ne ostatní -
     * stejné pravidlo jako u blocks.json.
     */
    public static RecipeBook load(Path file)
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
            System.err.println("Recepty " + file + ": neni to text v UTF-8 - jen vestavene recepty");
            return EMPTY;
        }
        catch(IOException e)
        {
            System.err.println("Recepty " + file + " nejdou precist: " + e + " - jen vestavene recepty");
            return EMPTY;
        }

        List<String> problems = new ArrayList<>();

        try
        {
            RecipeBook book = parse(json, problems);
            report(file.toString(), problems);
            return book;
        }
        catch(IllegalArgumentException e)
        {
            report(file.toString(), problems);
            System.err.println("Recepty " + file + ": " + e.getMessage() + " - jen vestavene recepty");
            return EMPTY;
        }
        catch(RuntimeException e)
        {
            // Pojistka: hra kvůli souboru receptů nesmí spadnout.
            System.err.println("Recepty " + file + ": neocekavana chyba " + e + " - jen vestavene recepty");
            return EMPTY;
        }
    }

    /**
     * Zapíše seznam. Chyba hru nepoloží: vrátí false a důvod napíše na stderr.
     *
     * Atomicky přes `.tmp` a se zálohou `.bak`, když soubor nejde načíst celý -
     * oboje ze stejného důvodu jako u blocks.json: pád uprostřed zápisu nechá
     * starý soubor celý, a první uložení z labu nesmí tiše smazat recepty,
     * které se kvůli překlepu nenačetly do paměti.
     */
    public boolean save(Path file)
    {
        return SafeFiles.writeAtomically(file, toJson(), RecipeBook::loadsCompletely, "Recepty");
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

    /**
     * Seznam jako JSON - odsazení dvě mezery, "\n" i na Windows, na konci
     * nový řádek. Vzor je jedno pole čísel po řádcích shora dolů; nula je
     * prázdná buňka (World.AIR), takže se dá recept přečíst i očima.
     */
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
            Recipes.Recipe recipe = recipes.get(i);

            out.append("    {\n");
            out.append("      \"width\": ").append(recipe.width()).append(",\n");
            out.append("      \"height\": ").append(recipe.height()).append(",\n");
            out.append("      \"pattern\": [");

            for(int p = 0; p < recipe.pattern().length; p++)
            {
                out.append(p > 0 ? ", " : "").append(recipe.pattern()[p]);
            }

            out.append("],\n");
            out.append("      \"result\": ").append(recipe.result()).append(",\n");
            out.append("      \"count\": ").append(recipe.resultCount()).append("\n");
            out.append(i < recipes.size() - 1 ? "    },\n" : "    }\n");
        }

        out.append("  ]\n");
        return out.append("}\n").toString();
    }

    static RecipeBook fromJson(String json)
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
        String prefix = source.isEmpty() ? "Recepty: " : "Recepty " + source + ": ";

        for(String problem : problems)
        {
            System.err.println(prefix + problem);
        }
    }

    /**
     * Vlastní čtení. Chyby celého souboru hází jako IllegalArgumentException,
     * výhrady k jednotlivým receptům přidá do problems.
     */
    private static RecipeBook parse(String json, List<String> problems)
    {
        if(!(Json.parse(json) instanceof Map<?, ?> root))
        {
            throw new IllegalArgumentException("koren neni objekt");
        }

        if(!(root.get("format") instanceof Double format))
        {
            throw new IllegalArgumentException("chybi cislo \"format\"");
        }

        if(format.intValue() > FORMAT)
        {
            // Stejně jako u GENERATOR_VERSION: varovat, ale číst - neznámá
            // pole se ignorují a co je známé, se použije.
            problems.add("format " + format.intValue() + " je novejsi nez " + FORMAT
                    + " - co nezname, se preskoci");
        }

        if(!(root.get("recipes") instanceof List<?> list))
        {
            throw new IllegalArgumentException("chybi pole \"recipes\"");
        }

        List<Recipes.Recipe> parsed = new ArrayList<>();

        for(int i = 0; i < list.size(); i++)
        {
            Recipes.Recipe recipe = parseRecipe(list.get(i), i, problems);

            if(recipe != null)
            {
                parsed.add(recipe);
            }
        }

        return parsed.isEmpty() ? EMPTY : new RecipeBook(parsed);
    }

    private static Recipes.Recipe parseRecipe(Object element, int index, List<String> problems)
    {
        if(!(element instanceof Map<?, ?> map))
        {
            problems.add("recept " + index + " neni objekt - preskocen");
            return null;
        }

        Integer width = wholeNumber(map.get("width"));
        Integer height = wholeNumber(map.get("height"));
        Integer result = wholeNumber(map.get("result"));
        Integer count = wholeNumber(map.get("count"));

        if(width == null || height == null || result == null || count == null)
        {
            problems.add("recept " + index + ": chybi width, height, result nebo count - preskocen");
            return null;
        }

        if(!(map.get("pattern") instanceof List<?> cells))
        {
            problems.add("recept " + index + ": chybi pole \"pattern\" - preskocen");
            return null;
        }

        if(width < 1 || height < 1 || width > MAX_SIZE || height > MAX_SIZE
                || cells.size() != width * height)
        {
            problems.add("recept " + index + ": rozmer " + width + "x" + height
                    + " nesedi se vzorem o " + cells.size() + " bunkach - preskocen");
            return null;
        }

        byte[] pattern = new byte[width * height];

        for(int c = 0; c < cells.size(); c++)
        {
            Integer block = wholeNumber(cells.get(c));

            if(block == null || block < 0 || block > 127)
            {
                problems.add("recept " + index + ": bunka " + c + " neni id bloku - preskocen");
                return null;
            }

            pattern[c] = block.byteValue();
        }

        if(result < 0 || result > 127)
        {
            problems.add("recept " + index + ": vysledek " + result + " neni id bloku - preskocen");
            return null;
        }

        Recipes.Recipe recipe = new Recipes.Recipe(width, height, pattern,
                result.byteValue(), count);

        String problem = validate(recipe);

        if(problem != null)
        {
            problems.add("recept " + index + ": " + problem + " - preskocen");
            return null;
        }

        return normalize(recipe);
    }

    /**
     * Json čte čísla jako double. Zlomek ani nečíslo tu nedává smysl -
     * id bloku i rozměr mřížky jsou celá čísla.
     */
    private static Integer wholeNumber(Object value)
    {
        if(!(value instanceof Double number))
        {
            return null;
        }

        if(number != Math.floor(number) || number.isInfinite() || number.isNaN())
        {
            return null;
        }

        return number.intValue();
    }

    /** Prázdný seznam pro testy, které si sestavují recepty samy. */
    static RecipeBook of(List<Recipes.Recipe> list)
    {
        return list.isEmpty() ? EMPTY : new RecipeBook(Collections.unmodifiableList(new ArrayList<>(list)));
    }
}
