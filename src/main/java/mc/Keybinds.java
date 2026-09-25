package mc;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Přebindování kláves: `keybinds.json`.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ CHYBĚJÍCÍ SOUBOR = PŘESNĚ DNEŠNÍ KLÁVESY. Výchozí hodnoty nejsou opsané
 * ručně, jsou to tytéž konstanty GLFW, jaké měl `Main` natvrdo v callbacku -
 * `Action` je nese jako data. Soubor je tedy NEPOVINNÝ, stejně jako
 * `blocks.json` nebo `recipes.json`: bez něj se hra chová tak, jako by
 * přebindování neexistovalo.
 *
 * ⚠️ KOLIZE NESMÍ TIŠE SPUSTIT OBĚ AKCE. Když je jedna klávesa přiřazená
 * dvěma akcím, dělá ta klávesa NIC - `actionFor()` vrátí null a
 * `effectiveKey()` vrátí NONE. Alternativy jsou obě horší: spustit obě akce
 * najednou znamená, že E otevře inventář a zároveň vypne vsync, a spustit
 * "tu první" znamená, že o chování rozhoduje pořadí v enumu, které uživatel
 * nevidí. Takhle je kolize nefunkční klávesa, což je v labu vidět červeně
 * a ohlásí se i na stderr při načtení ručně upraveného souboru.
 *
 * ⚠️ ESCAPE FUNGUJE VŽDYCKY, i když je PAUSE přebindovaná jinam. Bez téhle
 * pojistky by stačilo přiřadit Escape omylem k něčemu jinému a hráč by se
 * z pauzy, inventáře ani labu nedostal jinak než zabitím procesu - je to
 * jediná klávesa, která zavírá obrazovky. Přebindovat PAUSE jde, jen tím
 * Escape nepřestane platit; `Main` to má jako vlastní větev.
 *
 * ⚠️ PLÍŽENÍ A KLESÁNÍ V LETU JSOU JEDNA AKCE. V `Main` to byly dva řádky
 * nad toutéž klávesou (`inputSneak` i `inputDescend` z LEFT_CONTROL). Dvě
 * samostatné akce se stejnou výchozí klávesou by znamenaly, že hra startuje
 * s kolizí, tedy s nefunkčním Ctrl - proto je to jedna akce a dva efekty.
 * ---------------------------------------------------------------------------
 *
 * Neměnná třída s jedním aktivním exemplářem, jako `RecipeBook`: lab si
 * postaví nový a aktivuje ho, takže nové klávesy platí OKAMŽITĚ, bez
 * restartu - `Main` se ptá `active()`, ne souboru.
 *
 * Nesahá na GL ani na GLFW za běhu (konstanty `GLFW_KEY_*` jsou `static final
 * int`, překladač je vkládá do kódu), takže jde celé otestovat headless.
 */
public final class Keybinds {

    /** Verze formátu souboru. Novější se načte s varováním, jako u recipes.json. */
    public static final int FORMAT = 1;

    /**
     * Vedle `options.json` v pracovním adresáři, ne v `textures/`.
     * Klávesy jsou nastavení stroje, ne data labu k texturám.
     */
    public static final Path FILE = GameDirs.path("keybinds.json");

    /** "Žádná klávesa" - akce bez klávesy nebo klávesa v kolizi. */
    public static final int NONE = -1;

    // ------------------------------------------------------------------
    // akce
    // ------------------------------------------------------------------

    /**
     * Všechno, co šlo v `Main` udělat klávesou.
     *
     * ⚠️ `id` je klíč v souboru a NESMÍ SE MĚNIT - přejmenování by tiše
     * zahodilo uživatelovo nastavení té akce a vrátilo ji na výchozí klávesu.
     * `label` je popisek v labu a měnit se smí.
     *
     * Pořadí = pořadí v seznamu v labu, proto jsou pohromadě pohyb, pak
     * obrazovky, pak ladicí klávesy a nakonec hotbar.
     */
    public enum Action {

        FORWARD  ("forward",   "Walk forward",      GLFW_KEY_W),
        BACK     ("back",      "Walk backward",     GLFW_KEY_S),
        LEFT     ("left",      "Strafe left",       GLFW_KEY_A),
        RIGHT    ("right",     "Strafe right",      GLFW_KEY_D),
        JUMP     ("jump",      "Jump / fly up",     GLFW_KEY_SPACE),
        SPRINT   ("sprint",    "Sprint",            GLFW_KEY_LEFT_SHIFT),

        /** Plížení na zemi a klesání v letu - jedna klávesa, viz poznámka u třídy. */
        SNEAK    ("sneak",     "Sneak / fly down",  GLFW_KEY_LEFT_CONTROL),

        INVENTORY("inventory", "Inventory",         GLFW_KEY_E),
        DROP     ("drop",      "Drop held item",    GLFW_KEY_Q),
        PAUSE    ("pause",     "Pause / close",     GLFW_KEY_ESCAPE),

        VIEW     ("view",      "Camera view",       GLFW_KEY_F5),
        DEBUG    ("debug",     "Debug overlay",     GLFW_KEY_F3),
        LAB      ("lab",       "Open the lab",      GLFW_KEY_F6),
        FULLSCREEN("fullscreen", "Fullscreen",      GLFW_KEY_F11),
        VSYNC    ("vsync",     "Toggle VSync",      GLFW_KEY_V),
        SKIP_TIME("skipTime",  "Skip time ahead",   GLFW_KEY_T),

        /** Ladicí klávesa: volný let bez gravitace. Platí v obou herních módech. */
        FLY      ("fly",       "Flight (debug)",    GLFW_KEY_F),

        /** Ladicí klávesa: průchod čímkoliv. */
        NOCLIP   ("noclip",    "Noclip (debug)",    GLFW_KEY_C),

        HOTBAR_1 ("hotbar1",   "Hotbar slot 1",     GLFW_KEY_1),
        HOTBAR_2 ("hotbar2",   "Hotbar slot 2",     GLFW_KEY_2),
        HOTBAR_3 ("hotbar3",   "Hotbar slot 3",     GLFW_KEY_3),
        HOTBAR_4 ("hotbar4",   "Hotbar slot 4",     GLFW_KEY_4),
        HOTBAR_5 ("hotbar5",   "Hotbar slot 5",     GLFW_KEY_5),
        HOTBAR_6 ("hotbar6",   "Hotbar slot 6",     GLFW_KEY_6),
        HOTBAR_7 ("hotbar7",   "Hotbar slot 7",     GLFW_KEY_7),
        HOTBAR_8 ("hotbar8",   "Hotbar slot 8",     GLFW_KEY_8),
        HOTBAR_9 ("hotbar9",   "Hotbar slot 9",     GLFW_KEY_9);

        private final String id;
        private final String label;
        private final int defaultKey;

        Action(String id, String label, int defaultKey)
        {
            this.id = id;
            this.label = label;
            this.defaultKey = defaultKey;
        }

        /** Klíč v souboru. Neměnit - viz poznámka u enumu. */
        public String id() { return id; }

        /** Popisek v labu. */
        public String label() { return label; }

        /** Klávesa, jakou akce měla natvrdo v Main před zavedením souboru. */
        public int defaultKey() { return defaultKey; }

        /** Akce s tímhle id, nebo null. */
        public static Action byId(String id)
        {
            for(Action action : values())
            {
                if(action.id.equals(id))
                {
                    return action;
                }
            }

            return null;
        }

        /** Slot hotbaru (0-8) pro akce HOTBAR_*, jinak -1. */
        public int hotbarSlot()
        {
            return ordinal() >= HOTBAR_1.ordinal() ? ordinal() - HOTBAR_1.ordinal() : -1;
        }
    }

    // ------------------------------------------------------------------
    // aktivní nastavení
    // ------------------------------------------------------------------

    private static final Keybinds DEFAULTS = buildDefaults();

    /**
     * ⚠️ volatile ze stejného důvodu jako u BlockRegistry a RecipeBook:
     * aktivní exemplář vyměňuje hlavní vlákno a číst ho může i jiné.
     */
    private static volatile Keybinds active = DEFAULTS;

    public static Keybinds active()
    {
        return active;
    }

    /** Vymění aktivní klávesy. Od téhle chvíle platí i v rozehrané hře. */
    public static void activate(Keybinds binds)
    {
        active = binds == null ? DEFAULTS : binds;
    }

    /** Výchozí klávesy - přesně to, co měl Main natvrdo. */
    public static Keybinds defaults()
    {
        return DEFAULTS;
    }

    private static Keybinds buildDefaults()
    {
        int[] keys = new int[Action.values().length];

        for(Action action : Action.values())
        {
            keys[action.ordinal()] = action.defaultKey();
        }

        return new Keybinds(keys);
    }

    // ------------------------------------------------------------------
    // data
    // ------------------------------------------------------------------

    /** Klávesa každé akce, indexovaná Action.ordinal(). */
    private final int[] keys;

    private Keybinds(int[] keys)
    {
        this.keys = keys.clone();
    }

    /** Klávesa akce tak, jak je nastavená - i když je v kolizi. */
    public int key(Action action)
    {
        return keys[action.ordinal()];
    }

    /**
     * Klávesa, která akci opravdu spustí: NONE, když je nepřiřazená nebo
     * v kolizi s jinou akcí. Tohle se ptá `Main` u držených kláves.
     */
    public int effectiveKey(Action action)
    {
        int key = keys[action.ordinal()];
        return key == NONE || conflicted(action) ? NONE : key;
    }

    /** Nové nastavení s jinou klávesou. Tohle zůstává, jak bylo. */
    public Keybinds with(Action action, int key)
    {
        int[] copy = keys.clone();
        copy[action.ordinal()] = key;
        return new Keybinds(copy);
    }

    /**
     * Akce, kterou tahle klávesa spustí - nebo null, když ji nemá žádná
     * akce, nebo když ji mají DVĚ (viz poznámka o kolizích u třídy).
     */
    public Action actionFor(int key)
    {
        if(key == NONE)
        {
            return null;
        }

        Action found = null;

        for(Action action : Action.values())
        {
            if(keys[action.ordinal()] == key)
            {
                if(found != null)
                {
                    return null;   // kolize: klávesa nedělá nic
                }

                found = action;
            }
        }

        return found;
    }

    /** Má tuhle akci ještě někdo další na téže klávese? */
    public boolean conflicted(Action action)
    {
        int key = keys[action.ordinal()];

        if(key == NONE)
        {
            return false;
        }

        for(Action other : Action.values())
        {
            if(other != action && keys[other.ordinal()] == key)
            {
                return true;
            }
        }

        return false;
    }

    /** Jsou nějaké kolize? Lab kvůli tomu odmítne uložit. */
    public boolean hasConflicts()
    {
        for(Action action : Action.values())
        {
            if(conflicted(action))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Popis kolizí pro UI: jedna věta na kolidující klávesu, v pořadí akcí.
     * Prázdný seznam = nic nekoliduje.
     */
    public List<String> conflicts()
    {
        Map<Integer, List<Action>> byKey = new LinkedHashMap<>();

        for(Action action : Action.values())
        {
            int key = keys[action.ordinal()];

            if(key != NONE)
            {
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(action);
            }
        }

        List<String> lines = new ArrayList<>();

        for(Map.Entry<Integer, List<Action>> entry : byKey.entrySet())
        {
            List<Action> sharing = entry.getValue();

            if(sharing.size() < 2)
            {
                continue;
            }

            StringBuilder line = new StringBuilder(keyName(entry.getKey())).append(" = ");

            for(int i = 0; i < sharing.size(); i++)
            {
                line.append(i > 0 ? " + " : "").append(sharing.get(i).label());
            }

            lines.add(line.toString());
        }

        return lines;
    }

    /** Jsou to přesně výchozí klávesy? */
    /** Stejné klávesy pro všechny akce? Lab tím pozná neuložené změny. */
    public boolean sameKeys(Keybinds other)
    {
        return Arrays.equals(keys, other.keys);
    }

    public boolean isDefault()
    {
        return sameKeys(DEFAULTS);
    }

    // ------------------------------------------------------------------
    // jména kláves
    //
    // ⚠️ DO SOUBORU SE PÍŠE JMÉNO, NE ČÍSLO. "F6" je v ručně upraveném
    // souboru k přečtení, 295 není - je to tentýž důvod, proč je vzor
    // receptu pole čísel bloků po řádcích a ne jeden zabalený řetězec.
    // Neznámý kód se zapíše jako "#295" a tak se i přečte, takže je převod
    // tam a zpět úplný i pro klávesu, kterou tabulka nezná.
    // ------------------------------------------------------------------

    private static final Map<Integer, String> KEY_NAMES = buildKeyNames();

    private static Map<Integer, String> buildKeyNames()
    {
        Map<Integer, String> names = new LinkedHashMap<>();

        for(int key = GLFW_KEY_A; key <= GLFW_KEY_Z; key++)
        {
            names.put(key, String.valueOf((char) ('A' + key - GLFW_KEY_A)));
        }

        for(int key = GLFW_KEY_0; key <= GLFW_KEY_9; key++)
        {
            names.put(key, String.valueOf((char) ('0' + key - GLFW_KEY_0)));
        }

        for(int i = 1; i <= 25; i++)
        {
            names.put(GLFW_KEY_F1 + i - 1, "F" + i);
        }

        for(int i = 0; i <= 9; i++)
        {
            names.put(GLFW_KEY_KP_0 + i, "NUM " + i);
        }

        names.put(GLFW_KEY_SPACE, "SPACE");
        names.put(GLFW_KEY_ESCAPE, "ESC");
        names.put(GLFW_KEY_ENTER, "ENTER");
        names.put(GLFW_KEY_TAB, "TAB");
        names.put(GLFW_KEY_BACKSPACE, "BACKSPACE");
        names.put(GLFW_KEY_INSERT, "INSERT");
        names.put(GLFW_KEY_DELETE, "DELETE");
        names.put(GLFW_KEY_HOME, "HOME");
        names.put(GLFW_KEY_END, "END");
        names.put(GLFW_KEY_PAGE_UP, "PAGE UP");
        names.put(GLFW_KEY_PAGE_DOWN, "PAGE DOWN");
        names.put(GLFW_KEY_RIGHT, "RIGHT");
        names.put(GLFW_KEY_LEFT, "LEFT");
        names.put(GLFW_KEY_DOWN, "DOWN");
        names.put(GLFW_KEY_UP, "UP");
        names.put(GLFW_KEY_CAPS_LOCK, "CAPS LOCK");
        names.put(GLFW_KEY_LEFT_SHIFT, "LEFT SHIFT");
        names.put(GLFW_KEY_RIGHT_SHIFT, "RIGHT SHIFT");
        names.put(GLFW_KEY_LEFT_CONTROL, "LEFT CTRL");
        names.put(GLFW_KEY_RIGHT_CONTROL, "RIGHT CTRL");
        names.put(GLFW_KEY_LEFT_ALT, "LEFT ALT");
        names.put(GLFW_KEY_RIGHT_ALT, "RIGHT ALT");
        names.put(GLFW_KEY_MINUS, "-");
        names.put(GLFW_KEY_EQUAL, "=");
        names.put(GLFW_KEY_LEFT_BRACKET, "[");
        names.put(GLFW_KEY_RIGHT_BRACKET, "]");
        names.put(GLFW_KEY_SEMICOLON, ";");
        names.put(GLFW_KEY_APOSTROPHE, "'");
        names.put(GLFW_KEY_COMMA, ",");
        names.put(GLFW_KEY_PERIOD, ".");
        names.put(GLFW_KEY_SLASH, "/");
        names.put(GLFW_KEY_BACKSLASH, "\\");
        names.put(GLFW_KEY_GRAVE_ACCENT, "`");
        names.put(GLFW_KEY_KP_ADD, "NUM +");
        names.put(GLFW_KEY_KP_SUBTRACT, "NUM -");
        names.put(GLFW_KEY_KP_MULTIPLY, "NUM *");
        names.put(GLFW_KEY_KP_DIVIDE, "NUM /");
        names.put(GLFW_KEY_KP_DECIMAL, "NUM .");
        names.put(GLFW_KEY_KP_ENTER, "NUM ENTER");

        return names;
    }

    /**
     * Jméno klávesy, na které akce AKTIVNĚ je - pro nápovědy v UI. Nápověda
     * s klávesou napsanou natvrdo by po přebindování v Keybind Labu radila
     * klávesu, která nic nedělá.
     */
    public static String activeKeyName(Action action)
    {
        return keyName(active().key(action));
    }

    /** Jméno klávesy pro UI i pro soubor. Neznámý kód dostane tvar "#kód". */
    public static String keyName(int key)
    {
        if(key == NONE)
        {
            return "NONE";
        }

        String name = KEY_NAMES.get(key);
        return name != null ? name : "#" + key;
    }

    /** Kód klávesy podle jména. NONE, když jméno nic neznamená. */
    public static int keyCode(String name)
    {
        if(name == null)
        {
            return NONE;
        }

        String trimmed = name.trim().toUpperCase(java.util.Locale.ROOT);

        if(trimmed.isEmpty() || trimmed.equals("NONE"))
        {
            return NONE;
        }

        if(trimmed.charAt(0) == '#')
        {
            try
            {
                int code = Integer.parseInt(trimmed.substring(1));
                return isUsableKey(code) ? code : NONE;
            }
            catch(NumberFormatException e)
            {
                return NONE;
            }
        }

        for(Map.Entry<Integer, String> entry : KEY_NAMES.entrySet())
        {
            if(entry.getValue().equals(trimmed))
            {
                return entry.getKey();
            }
        }

        return NONE;
    }

    /**
     * Dá se tenhle kód přiřadit?
     *
     * GLFW posílá GLFW_KEY_UNKNOWN (-1) u kláves, které nemá v tabulce -
     * a to je zrovna naše NONE, takže by se z "nerozpoznaná klávesa" stalo
     * "žádná klávesa" a akce by tiše zmizela. Proto se takový stisk zahodí.
     */
    public static boolean isUsableKey(int key)
    {
        // Od GLFW_KEY_SPACE (32): glfwGetKey kódy 1-31 odmítá jako
        // GLFW_INVALID_ENUM, takže "#5" v souboru by se načetl a akce by
        // tiše nikdy nesepnula.
        return key >= GLFW_KEY_SPACE && key <= GLFW_KEY_LAST;
    }

    /**
     * Smí tahle akce mít tuhle klávesu?
     *
     * ⚠️ ESC PATŘÍ JEN PAUZE. Esc je pojistka, kterou se zavírá každá
     * obrazovka, i když je keybinds.json rozbitý - a Main ji testuje až za
     * větvemi akcí, takže Esc přiřazený třeba celé obrazovce nebo vyhazování
     * by ji přebil (Esc v inventáři by přepnul fullscreen místo zavření).
     * Lab Esc přiřadit neumí (ruší čekání), tohle hlídá ručně upravený soubor.
     */
    public static boolean allowedFor(Action action, int key)
    {
        return key != GLFW_KEY_ESCAPE || action == Action.PAUSE;
    }

    // ------------------------------------------------------------------
    // soubor
    // ------------------------------------------------------------------

    /**
     * Načte klávesy. Chybějící soubor je běžný stav (mlčky výchozí),
     * poškozený se ohlásí na stderr a skončí taky výchozími. Jedna vadná
     * položka shodí jen sama sebe - ta akce zůstane na výchozí klávese,
     * stejné pravidlo jako u options.json.
     */
    public static Keybinds load(Path file)
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
            System.err.println("Klavesy " + file + ": neni to text v UTF-8 - vychozi klavesy");
            return DEFAULTS;
        }
        catch(IOException e)
        {
            System.err.println("Klavesy " + file + " nejdou precist: " + e + " - vychozi klavesy");
            return DEFAULTS;
        }

        List<String> problems = new ArrayList<>();

        try
        {
            Keybinds binds = fromJson(json, problems);
            report(file.toString(), problems);
            return binds;
        }
        catch(IllegalArgumentException e)
        {
            report(file.toString(), problems);
            System.err.println("Klavesy " + file + ": " + e.getMessage() + " - vychozi klavesy");
            return DEFAULTS;
        }
        catch(RuntimeException e)
        {
            // Pojistka: hra kvůli souboru s klávesami nesmí spadnout.
            System.err.println("Klavesy " + file + ": neocekavana chyba " + e + " - vychozi klavesy");
            return DEFAULTS;
        }
    }

    /** Zapíše klávesy atomicky; nečitelný dosavadní soubor zazálohuje do .bak. */
    public boolean save(Path file)
    {
        return SafeFiles.writeAtomically(file, toJson(), Keybinds::loadsCompletely, "Klavesy");
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
        String prefix = source.isEmpty() ? "Klavesy: " : "Klavesy " + source + ": ";

        for(String problem : problems)
        {
            System.err.println(prefix + problem);
        }
    }

    /**
     * Klávesy jako JSON - odsazení dvě mezery, "\n" i na Windows, na konci
     * nový řádek. Pořadí je pořadí akcí v enumu, takže je druhý zápis téhož
     * nastavení bajt po bajtu stejný.
     */
    String toJson()
    {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"format\": ").append(FORMAT).append(",\n");
        out.append("  \"keys\": {\n");

        Action[] actions = Action.values();

        for(int i = 0; i < actions.length; i++)
        {
            out.append("    ").append(Json.quote(actions[i].id())).append(": ")
                    .append(Json.quote(keyName(keys[actions[i].ordinal()])))
                    .append(i < actions.length - 1 ? ",\n" : "\n");
        }

        out.append("  }\n");
        return out.append("}\n").toString();
    }

    static Keybinds fromJson(String json)
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
     * výhrady k jednotlivým akcím přidá do problems a nechá jim výchozí
     * klávesu.
     */
    static Keybinds fromJson(String json, List<String> problems)
    {
        if(!(Json.parse(json) instanceof Map<?, ?> root))
        {
            throw new IllegalArgumentException("koren neni objekt");
        }

        if(root.get("format") instanceof Double format && format > FORMAT)
        {
            problems.add("format " + format + " je novejsi nez " + FORMAT
                    + " - co nezname, se preskoci");
        }

        if(!(root.get("keys") instanceof Map<?, ?> map))
        {
            throw new IllegalArgumentException("chybi objekt \"keys\"");
        }

        int[] keys = DEFAULTS.keys.clone();

        for(Map.Entry<?, ?> entry : map.entrySet())
        {
            Action action = Action.byId(String.valueOf(entry.getKey()));

            if(action == null)
            {
                problems.add("akce \"" + entry.getKey() + "\" neexistuje - preskocena");
                continue;
            }

            if(!(entry.getValue() instanceof String name))
            {
                problems.add(action.id() + ": klavesa neni text - vychozi "
                        + keyName(action.defaultKey()));
                continue;
            }

            int code = keyCode(name);

            if(code == NONE && !name.trim().equalsIgnoreCase("NONE"))
            {
                problems.add(action.id() + ": klavesu \"" + name + "\" neznam - vychozi "
                        + keyName(action.defaultKey()));
                continue;
            }

            if(!allowedFor(action, code))
            {
                problems.add(action.id() + ": Esc patri jen pauze (zavira vsechny obrazovky) - vychozi "
                        + keyName(action.defaultKey()));
                continue;
            }

            keys[action.ordinal()] = code;
        }

        Keybinds binds = new Keybinds(keys);

        // Kolize soubor nezahodí - jen se o nich napíše a ty klávesy pak
        // nedělají nic (viz poznámka u třídy). Ručně upravený soubor se
        // dvěma akcemi na jedné klávese se tak pozná hned při startu.
        for(String conflict : binds.conflicts())
        {
            problems.add("kolize " + conflict + " - ta klavesa nedela nic");
        }

        return binds;
    }
}
