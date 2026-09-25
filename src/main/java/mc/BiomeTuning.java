package mc;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Doladění generátoru po biomech: `biome_tuning.json`.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ JEN ČÍSLA, NIKDY BLOKY. Tuner mění výšky, amplitudy, hustoty a rozsahy -
 * tedy ČÍSLA kolem generování. CO se pokládá (dub, smrk, bříza, sníh, železo)
 * zůstává v kódu a v datech `Biome`. Je to tatáž hranice, kvůli které musí
 * být bloky generátoru vestavěné a ne z `blocks.json`: generátor musí
 * fungovat i bez nepovinného souboru, takže se na něj nesmí vázat ničím,
 * co by z něj udělalo zdroj pravdy. Chybějící soubor = dnešní pevné hodnoty
 * z kódu, bit po bitu.
 *
 * ⚠️ GENERÁTOR SI TUNING VEZME PŘI SVÉM VZNIKU, NE PŘI KAŽDÉM SLOUPCI.
 * `TerrainGenerator` je neměnná třída, na které stojí generování bez zámku
 * na worker vlákně (viz jeho komentář) - kdyby četl `active()` za běhu, mohl
 * by uprostřed světa změnit parametry a sousední sloupce by na sebe přestaly
 * navazovat. Změna se proto projeví u PŘÍŠTÍHO světa (nový nebo načtený),
 * ne uprostřed rozehraného. Restart hry potřeba není.
 *
 * ⚠️ CO TO UDĚLÁ S EXISTUJÍCÍM SVĚTEM. Ukládá se ROZDÍL proti generátoru,
 * takže se terén při načtení dopočítá znovu - a to generátorem s aktuálním
 * tuningem. Změna čísel tedy posune krajinu i tam, kde už hráč byl, přesně
 * jako by se změnil kód generátoru; hráčovy stavby a inventář zůstanou.
 * Je to táž cena, jakou stálo zavedení jeskyní, vody, stromů i biomů.
 * ---------------------------------------------------------------------------
 *
 * Neměnná třída s jedním aktivním exemplářem, jako `RecipeBook` a `Keybinds`.
 * Nesahá na GL, takže jde celá otestovat headless.
 */
public final class BiomeTuning {

    /** Verze formátu souboru. Novější se načte s varováním, jako u recipes.json. */
    public static final int FORMAT = 1;

    /** Vedle `options.json` a `keybinds.json` - nastavení hry, ne data k texturám. */
    public static final Path FILE = Path.of("biome_tuning.json");

    // ------------------------------------------------------------------
    // meze
    //
    // Nejsou kosmetika: hodnota mimo ně by udělala svět, který se nedá hrát
    // (terén nad stropem, strom bez kmene, nekonečná smyčka hledání rudy).
    // Soubor se hodnotou mimo meze NEZAHODÍ - ořízne se a ohlásí, jako
    // u options.json.
    // ------------------------------------------------------------------

    public static final int MIN_BASE = 8,  MAX_BASE = 110;
    public static final int MIN_AMPLITUDE = 0, MAX_AMPLITUDE = 60;

    /** Kolik buněk z Biome.DENSITY_SCALE nese strom. 0 = biom bez stromů. */
    public static final int MIN_DENSITY = 0, MAX_DENSITY = Biome.DENSITY_SCALE;

    public static final int MIN_TRUNK = 1, MAX_TRUNK = 16;

    /**
     * Poloměr koruny. Strop 6 není libovolný: koruna o poloměru r je široká
     * 2r+1 bloků a razítkuje se ze sousedních sloupců (TREE_REACH), takže
     * při 7 by přesáhla celou šířku chunku a strom by se musel hledat o dva
     * sloupce dál. Šest dá korunu 13 bloků širokou, což je pořád obří.
     */
    public static final int MIN_CROWN = 0, MAX_CROWN = 6;

    /** Násobek hustoty rudy. 0 = ruda v tom biomu není, 8 = osmkrát víc žil. */
    public static final double MIN_ORE = 0.0, MAX_ORE = 8.0;

    // ------------------------------------------------------------------
    // jeden biom
    // ------------------------------------------------------------------

    /**
     * Čísla jednoho biomu.
     *
     * Rozsah kmene i koruny je MIN a MAX, ne jedna hodnota - viz
     * `TerrainGenerator.trunkHeight()` a `crownDelta()`. Když se min rovná
     * max, nesáhne generátor na hash vůbec a vyjde přesně to, co vycházelo
     * před tunerem.
     *
     * `ironDensity` a `coalDensity` jsou NÁSOBKY množství, ne vzácnosti:
     * 3 znamená třikrát víc žil (a tedy třetinovou vzácnost). Takhle to je
     * čitelné - "v horách je třikrát víc železa" je přesně to, co dnes
     * dělá pevná konstanta IRON_RARITY_MOUNTAINS.
     */
    public record Tune(int baseHeight, int amplitude, int treeDensity,
                       int trunkMin, int trunkMax,
                       int crownMin, int crownMax,
                       double ironDensity, double coalDensity) {

        /** Ořízne všechno na meze a srovná min/max, když je přehozené. */
        public Tune clamped()
        {
            int trMin = clamp(trunkMin, MIN_TRUNK, MAX_TRUNK);
            int trMax = clamp(trunkMax, MIN_TRUNK, MAX_TRUNK);
            int crMin = clamp(crownMin, MIN_CROWN, MAX_CROWN);
            int crMax = clamp(crownMax, MIN_CROWN, MAX_CROWN);

            // ⚠️ Přehozený rozsah se PROHODÍ, ne ořeže na jedno číslo.
            // "min 9, max 4" je skoro jistě překlep v ručně psaném souboru
            // a uživatel chtěl 4 až 9; srovnat obojí na 9 by tiše zahodilo
            // polovinu rozsahu a strom by se přestal měnit.
            if(trMin > trMax)
            {
                int swap = trMin; trMin = trMax; trMax = swap;
            }
            if(crMin > crMax)
            {
                int swap = crMin; crMin = crMax; crMax = swap;
            }

            return new Tune(
                    clamp(baseHeight, MIN_BASE, MAX_BASE),
                    clamp(amplitude, MIN_AMPLITUDE, MAX_AMPLITUDE),
                    clamp(treeDensity, MIN_DENSITY, MAX_DENSITY),
                    trMin, trMax, crMin, crMax,
                    clamp(ironDensity, MIN_ORE, MAX_ORE),
                    clamp(coalDensity, MIN_ORE, MAX_ORE));
        }

        /** Je to už oříznuté a srovnané? */
        public boolean isClamped()
        {
            return equals(clamped());
        }

        /** Kolik různých výšek kmene rozsah nabízí. Aspoň 1. */
        public int trunkVariants()
        {
            return trunkMax - trunkMin + 1;
        }

        /** Kolik různých poloměrů koruny rozsah nabízí. Aspoň 1. */
        public int crownVariants()
        {
            return crownMax - crownMin + 1;
        }

        /** Mění se u tohohle biomu velikost stromu vůbec? */
        public boolean variesTreeSize()
        {
            return trunkVariants() > 1 || crownVariants() > 1;
        }
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max)
    {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // vzácnost rudy
    // ------------------------------------------------------------------

    /**
     * Vzácnost žíly z násobku hustoty: "jedna buňka z tolika nese žílu".
     *
     * ⚠️ Hustota žil je 1/vzácnost, takže se násobkem DĚLÍ, ne násobí.
     * Výchozí stav to musí trefit přesně: 60 / 3 = 20, což je dnešní
     * IRON_RARITY_MOUNTAINS. Nula znamená "tahle ruda v tom biomu není"
     * a vrací se jako 0, což generátor pozná a žílu vůbec nezkusí -
     * dělením by jinak vyšlo nekonečno.
     */
    public static int rarity(int baseRarity, double density)
    {
        if(density <= 0.0)
        {
            return 0;
        }

        // ⚠️ Math.round(double) vrací long a přetypování na int ořízne horní
        // bity: u hustoty pod ~3e-8 vyšlo záporné číslo, max(1, ...) z něj
        // udělal 1 - a "skoro žádná ruda" dala žílu v každé buňce. Proto se
        // nasytí na Integer.MAX_VALUE, tedy "prakticky nikde".
        long rarity = Math.round(baseRarity / density);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, rarity));
    }

    /**
     * Násobek, jaký rudě doopravdy platí: vzácnost je celé číslo, takže
     * třeba 7,0× uhlí (30 / 7 = 4,29) se zaokrouhlí na vzácnost 4 = 7,5×.
     * Lab ukazuje tohle číslo, ne to, co je v souboru.
     */
    public static double effectiveDensity(int baseRarity, double density)
    {
        int rarity = rarity(baseRarity, density);
        return rarity == 0 ? 0.0 : (double) baseRarity / rarity;
    }

    // ------------------------------------------------------------------
    // aktivní tuning
    // ------------------------------------------------------------------

    private static final BiomeTuning DEFAULTS = buildDefaults();

    /** volatile ze stejného důvodu jako u RecipeBook - viz tam. */
    private static volatile BiomeTuning active = DEFAULTS;

    public static BiomeTuning active()
    {
        return active;
    }

    /**
     * Vymění aktivní tuning. Projeví se u příštího vzniklého generátoru,
     * ne v rozehraném světě - viz poznámka u třídy.
     */
    public static void activate(BiomeTuning tuning)
    {
        active = tuning == null ? DEFAULTS : tuning;
    }

    /** Dnešní pevné hodnoty z kódu. */
    public static BiomeTuning defaults()
    {
        return DEFAULTS;
    }

    /**
     * ⚠️ VÝCHOZÍ HODNOTY SE ČTOU Z `Biome`, NEOPISUJÍ SE. Kdyby tu byla
     * vlastní kopie čísel, rozešla by se s enumem při první změně tam
     * a "chybějící soubor = dnešní hra" by přestalo platit, aniž by to
     * někdo poznal. Rozsahy kmene a koruny vycházejí z `TreeType`, takže
     * výchozí tuning generuje bit po bitu tentýž terén jako před tunerem.
     */
    private static BiomeTuning buildDefaults()
    {
        Tune[] tunes = new Tune[Biome.values().length];

        for(Biome biome : Biome.values())
        {
            Biome.TreeType type = biome.treeType();

            // ⚠️ clamped() i na VÝCHOZÍ hodnoty. TreeType.NONE (poušť) má
            // v datech kmen 0, což je mimo meze - a nezkrácený default by
            // po uložení a načtení vyšel jinak, protože načítání ořezává.
            // Pouště se to nijak netýká: bez stromů se rozsah nepoužije.
            tunes[biome.ordinal()] = new Tune(
                    biome.baseHeight(), biome.amplitude(), biome.treeDensity(),
                    type.trunkMin, type.trunkMax(),
                    type.maxRadius(), type.maxRadius(),
                    biome == Biome.MOUNTAINS ? 3.0 : 1.0,
                    1.0).clamped();
        }

        return new BiomeTuning(tunes);
    }

    // ------------------------------------------------------------------
    // data
    // ------------------------------------------------------------------

    private final Tune[] tunes;

    private BiomeTuning(Tune[] tunes)
    {
        this.tunes = tunes.clone();
    }

    public Tune tune(Biome biome)
    {
        return tunes[biome.ordinal()];
    }

    /** Nový tuning s jinými čísly jednoho biomu. Tenhle zůstává, jak byl. */
    public BiomeTuning with(Biome biome, Tune tune)
    {
        Tune[] copy = tunes.clone();
        copy[biome.ordinal()] = tune.clamped();
        return new BiomeTuning(copy);
    }

    /** Stejná čísla ve všech biomech? Lab tím pozná neuložené změny. */
    public boolean sameNumbers(BiomeTuning other)
    {
        return java.util.Arrays.equals(tunes, other.tunes);
    }

    /** Jsou to přesně dnešní hodnoty z kódu? */
    public boolean isDefault()
    {
        return sameNumbers(DEFAULTS);
    }

    /**
     * Největší poloměr koruny, jaký může v tomhle tuningu vyrůst.
     *
     * ⚠️ Z TOHO SE POČÍTÁ `TREE_REACH`. Koruna zasahuje do sousedních
     * sloupců a kdyby se dosah počítal z pevného čísla, ořezaly by se
     * vytuněné koruny přesně na švech chunků - tatáž chyba, kvůli které
     * se dosah už dnes počítá z dat druhů a ne ručně.
     *
     * Biom bez stromů (TreeType.NONE) se nepočítá, i když má v souboru
     * jakýkoliv poloměr: nic tam nevyroste, takže by jen zbytečně zvětšil
     * hledaný okruh u každého sloupce.
     */
    public int maxCrownRadius()
    {
        int max = 0;

        for(Biome biome : Biome.values())
        {
            if(biome.treeType() != Biome.TreeType.NONE)
            {
                max = Math.max(max, tunes[biome.ordinal()].crownMax());
            }
        }

        return max;
    }

    /**
     * Nejvyšší strom, jaký může v tomhle tuningu vyrůst - kmen a nad ním
     * ještě poslední vrstva koruny. Z toho se počítá strop výšky terénu,
     * aby se nejvyšší strom vždycky vešel pod strop světa.
     */
    public int maxTreeHeight()
    {
        int max = 0;

        for(Biome biome : Biome.values())
        {
            if(biome.treeType() != Biome.TreeType.NONE)
            {
                max = Math.max(max, tunes[biome.ordinal()].trunkMax() + 2);
            }
        }

        return max;
    }

    // ------------------------------------------------------------------
    // soubor
    // ------------------------------------------------------------------

    /**
     * Načte tuning. Chybějící soubor je běžný stav (mlčky dnešní hodnoty),
     * poškozený se ohlásí na stderr a taky skončí dnešními hodnotami. Jeden
     * vadný biom shodí jen sám sebe - ten zůstane na výchozích číslech.
     */
    public static BiomeTuning load(Path file)
    {
        if(Files.notExists(file))
        {
            return DEFAULTS;
        }

        String json;

        try
        {
            json = Files.readString(file, StandardCharsets.UTF_8);
        }
        catch(CharacterCodingException e)
        {
            System.err.println("Biomy " + file + ": neni to text v UTF-8 - vychozi hodnoty");
            return DEFAULTS;
        }
        catch(IOException e)
        {
            System.err.println("Biomy " + file + " nejdou precist: " + e + " - vychozi hodnoty");
            return DEFAULTS;
        }

        List<String> problems = new ArrayList<>();

        try
        {
            BiomeTuning tuning = fromJson(json, problems);
            report(file.toString(), problems);
            return tuning;
        }
        catch(IllegalArgumentException e)
        {
            report(file.toString(), problems);
            System.err.println("Biomy " + file + ": " + e.getMessage() + " - vychozi hodnoty");
            return DEFAULTS;
        }
        catch(RuntimeException e)
        {
            // Pojistka: hra kvůli souboru s tuningem nesmí spadnout.
            System.err.println("Biomy " + file + ": neocekavana chyba " + e + " - vychozi hodnoty");
            return DEFAULTS;
        }
    }

    /** Zapíše tuning atomicky; nečitelný dosavadní soubor zazálohuje do .bak. */
    public boolean save(Path file)
    {
        return SafeFiles.writeAtomically(file, toJson(), BiomeTuning::loadsCompletely, "Biomy");
    }

    private static boolean loadsCompletely(Path file)
    {
        try
        {
            List<String> problems = new ArrayList<>();
            fromJson(Files.readString(file, StandardCharsets.UTF_8), problems);
            return problems.isEmpty();
        }
        catch(IOException | RuntimeException e)
        {
            return false;
        }
    }

    private static void report(String source, List<String> problems)
    {
        String prefix = source.isEmpty() ? "Biomy: " : "Biomy " + source + ": ";

        for(String problem : problems)
        {
            System.err.println(prefix + problem);
        }
    }

    /**
     * Tuning jako JSON - odsazení dvě mezery, "\n" i na Windows, na konci
     * nový řádek. Biomy jdou v pořadí enumu, takže je druhý zápis téhož
     * nastavení bajt po bajtu stejný.
     */
    String toJson()
    {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"format\": ").append(FORMAT).append(",\n");
        out.append("  \"biomes\": {\n");

        Biome[] biomes = Biome.values();

        for(int i = 0; i < biomes.length; i++)
        {
            Tune t = tunes[biomes[i].ordinal()];

            out.append("    ").append(Json.quote(id(biomes[i]))).append(": {\n");
            out.append("      \"baseHeight\": ").append(t.baseHeight()).append(",\n");
            out.append("      \"amplitude\": ").append(t.amplitude()).append(",\n");
            out.append("      \"treeDensity\": ").append(t.treeDensity()).append(",\n");
            out.append("      \"trunkMin\": ").append(t.trunkMin()).append(",\n");
            out.append("      \"trunkMax\": ").append(t.trunkMax()).append(",\n");
            out.append("      \"crownMin\": ").append(t.crownMin()).append(",\n");
            out.append("      \"crownMax\": ").append(t.crownMax()).append(",\n");
            out.append("      \"ironDensity\": ").append(decimalText(t.ironDensity())).append(",\n");
            out.append("      \"coalDensity\": ").append(decimalText(t.coalDensity())).append("\n");
            out.append(i < biomes.length - 1 ? "    },\n" : "    }\n");
        }

        out.append("  }\n");
        return out.append("}\n").toString();
    }

    /**
     * Klíč biomu v souboru. Malými písmeny, takže se dá soubor přečíst -
     * a je to `name()` enumu, tedy nic, co by šlo omylem přejmenovat jinde.
     */
    static String id(Biome biome)
    {
        return biome.name().toLowerCase(Locale.ROOT);
    }

    static BiomeTuning fromJson(String json)
    {
        List<String> problems = new ArrayList<>();

        try
        {
            return fromJson(json, problems);
        }
        finally
        {
            report("", problems);
        }
    }

    /**
     * Vlastní čtení. Chyby celého souboru hází jako IllegalArgumentException,
     * výhrady k jednotlivým biomům a hodnotám přidá do problems; chybějící
     * hodnota zůstane výchozí, hodnota mimo meze se ořízne.
     */
    static BiomeTuning fromJson(String json, List<String> problems)
    {
        if(!(Json.parse(json) instanceof Map<?, ?> root))
        {
            throw new IllegalArgumentException("koren neni objekt");
        }

        if(root.get("format") instanceof Double format && format.intValue() > FORMAT)
        {
            problems.add("format " + format.intValue() + " je novejsi nez " + FORMAT
                    + " - co nezname, se preskoci");
        }

        if(!(root.get("biomes") instanceof Map<?, ?> map))
        {
            throw new IllegalArgumentException("chybi objekt \"biomes\"");
        }

        Tune[] tunes = DEFAULTS.tunes.clone();

        for(Map.Entry<?, ?> entry : map.entrySet())
        {
            String key = String.valueOf(entry.getKey());
            Biome biome = biomeById(key);

            if(biome == null)
            {
                problems.add("biom \"" + key + "\" neexistuje - preskocen");
                continue;
            }

            if(!(entry.getValue() instanceof Map<?, ?> values))
            {
                problems.add(key + ": neni objekt - vychozi hodnoty");
                continue;
            }

            tunes[biome.ordinal()] = parseTune(key, values, DEFAULTS.tunes[biome.ordinal()], problems);
        }

        return new BiomeTuning(tunes);
    }

    private static Biome biomeById(String id)
    {
        for(Biome biome : Biome.values())
        {
            if(id(biome).equals(id))
            {
                return biome;
            }
        }

        return null;
    }

    private static Tune parseTune(String key, Map<?, ?> values, Tune fallback, List<String> problems)
    {
        Tune raw = new Tune(
                integer(key, values, "baseHeight", fallback.baseHeight(), problems),
                integer(key, values, "amplitude", fallback.amplitude(), problems),
                integer(key, values, "treeDensity", fallback.treeDensity(), problems),
                integer(key, values, "trunkMin", fallback.trunkMin(), problems),
                integer(key, values, "trunkMax", fallback.trunkMax(), problems),
                integer(key, values, "crownMin", fallback.crownMin(), problems),
                integer(key, values, "crownMax", fallback.crownMax(), problems),
                decimal(key, values, "ironDensity", fallback.ironDensity(), problems),
                decimal(key, values, "coalDensity", fallback.coalDensity(), problems));

        Tune clamped = raw.clamped();

        if(!raw.equals(clamped))
        {
            problems.add(key + ": hodnoty mimo meze se oriznuly na " + describe(clamped));
        }

        return clamped;
    }

    /**
     * Násobek rudy do souboru: dvě desetinná místa, když na nich hodnota
     * přesně sedí (lab krokuje po 0,25), jinak plná přesnost.
     *
     * ⚠️ Dřív vždycky %.2f: načtení drželo plnou přesnost, zápis ji ořízl,
     * takže 0,004 (ruda vzácná) se po uložení z labu změnilo na 0,00 (ruda
     * v biomu vypnutá) - a stačilo v labu upravit úplně jiný biom.
     */
    static String decimalText(double value)
    {
        String twoPlaces = String.format(Locale.ROOT, "%.2f", value);
        return Double.parseDouble(twoPlaces) == value ? twoPlaces : Double.toString(value);
    }

    private static String describe(Tune t)
    {
        return "base " + t.baseHeight() + ", amp " + t.amplitude()
                + ", trees " + t.treeDensity()
                + ", trunk " + t.trunkMin() + "-" + t.trunkMax()
                + ", crown " + t.crownMin() + "-" + t.crownMax()
                + ", iron " + t.ironDensity() + ", coal " + t.coalDensity();
    }

    private static int integer(String key, Map<?, ?> values, String name, int fallback,
                               List<String> problems)
    {
        Object value = values.get(name);

        if(value == null)
        {
            return fallback;   // chybějící hodnota = výchozí, mlčky
        }

        if(value instanceof Double number && Double.isFinite(number))
        {
            // ⚠️ Nasytit, ne přetypovat: (int) Math.round(3e9) přetekl na
            // záporné číslo a ořez pak dal SPODNÍ mez místo horní - bez hlášky,
            // protože výsledek ležel v mezích. Přetečení teď skončí mimo meze
            // a clamped() ho ohlásí jako každou jinou hodnotu mimo rozsah.
            long rounded = Math.round(number);
            int whole = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, rounded));

            // Zlomek se zaokrouhlí, ale ohlásí - jako options.json. Tiše by
            // překlep nevznikl ani .bak a příští Save by ho přepsal.
            if(number != Math.rint(number))
            {
                problems.add(key + "." + name + " " + number + " neni cele cislo - zaokrouhleno na " + whole);
            }

            return whole;
        }

        problems.add(key + "." + name + " neni cislo - vychozi " + fallback);
        return fallback;
    }

    private static double decimal(String key, Map<?, ?> values, String name, double fallback,
                                  List<String> problems)
    {
        Object value = values.get(name);

        if(value == null)
        {
            return fallback;
        }

        if(value instanceof Double number && Double.isFinite(number))
        {
            return number;
        }

        problems.add(key + "." + name + " neni cislo - vychozi " + fallback);
        return fallback;
    }
}
