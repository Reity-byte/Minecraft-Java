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
 * Nastavení hry, uložená v options.json vedle hry.
 *
 * ---------------------------------------------------------------------------
 * Hodnoty jsou v jednotkách, které vidí hráč (chunky, stupně, procenta), a na
 * čísla enginu je převádí metody dole (loadRadius(), renderDistanceBlocks()...).
 * Každý setter hodnotu OŘÍZNE na povolené meze - obrazovka nastavení, soubor
 * i klávesové zkratky tak nemůžou nastavit nesmysl, i kdyby chtěly.
 *
 * ⚠️ RENDER DISTANCE NIKDY NENÍ VĚTŠÍ NEŽ SIMULATION DISTANCE. Simulation
 * distance je, kam se sloupce NAČÍTAJÍ (generují, svítí, drží změny a předměty
 * na zemi); render distance je, co se z nich kreslí. Kreslit jde jen načtené,
 * takže render > simulation by nemělo co ukázat. Posunutí jednoho posuvníku
 * přes druhý proto potáhne i ten druhý - hráč dostane, co posunul.
 *
 * Soubor: chybějící = výchozí hodnoty mlčky, poškozený = výchozí hodnoty
 * a zpráva na stderr, jedna špatná hodnota = výchozí jen pro ni. Zápis je
 * atomický a nečitelný soubor se před přepsáním zazálohuje do .bak - stejný
 * vzor jako textures/blocks.json (SafeFiles).
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, takže jde celé otestovat headless.
 */
public final class Options {

    /** V pracovním adresáři hry, stejně jako saves/ a textures/. */
    public static final Path FILE = Path.of("options.json");

    public static final int FORMAT = 1;

    // --- meze (v jednotkách, které vidí hráč) ---
    public static final int MIN_RENDER = 2, MAX_RENDER = 16;
    public static final int MIN_SIMULATION = 2, MAX_SIMULATION = 16;
    public static final int MIN_FOV = 30, MAX_FOV = 110;
    public static final int MIN_FPS = 30, MAX_FPS = 250, FPS_STEP = 10;
    /** maxFps 0 = bez stropu. Posuvník má tuhle hodnotu za MAX_FPS. */
    public static final int UNLIMITED_FPS = 0;
    public static final int MAX_GUI_SCALE = 4;
    /** guiScale 0 = automaticky, největší měřítko, které se vejde (Gui.scale). */
    public static final int AUTO_GUI_SCALE = 0;
    public static final float MIN_SENSITIVITY = 0.1f, MAX_SENSITIVITY = 2f;

    // --- výchozí hodnoty = dnešní chování hry ---
    public static final int DEFAULT_RENDER = 6;       // 96 bloků, jako World.renderDistance
    public static final int DEFAULT_SIMULATION = 6;   // loadRadius 8, jako World.loadRadius
    public static final int DEFAULT_FOV = 70;

    /**
     * Kolik sloupců za hranou simulation distance se ještě načítá.
     *
     * ⚠️ Sekce se mešuje, až když jsou načtení všichni čtyři sousední sloupci,
     * a kamera může stát kdekoliv ve svém chunku - nejvzdálenější viditelný
     * sloupec je tedy až render + 1 chunků od hráčova chunku a potřebuje
     * načtené sousedy o další chunk dál. Dvě navíc to pokryjí i pro render =
     * simulation; s výchozí 6 vyjde přesně dnešní loadRadius 8.
     */
    static final int LOAD_MARGIN = 2;

    /** Hystereze zahazování, jako dnes (loadRadius 8, unloadRadius 10). */
    static final int UNLOAD_MARGIN = 2;

    /** Kolik chunků je vidět - 16 bloků na chunk. */
    static final float BLOCKS_PER_CHUNK = 16f;

    private boolean fullscreen = false;
    private boolean vsync = true;
    private int maxFps = UNLIMITED_FPS;
    private int renderDistance = DEFAULT_RENDER;
    private int simulationDistance = DEFAULT_SIMULATION;
    private int fov = DEFAULT_FOV;
    private float brightness = 0f;
    private int guiScale = AUTO_GUI_SCALE;
    private float sensitivity = 1f;
    private boolean invertMouse = false;
    private boolean viewBobbing = true;

    // ------------------------------------------------------------------
    // hodnoty
    // ------------------------------------------------------------------

    public boolean fullscreen()      { return fullscreen; }
    public boolean vsync()           { return vsync; }
    public int maxFps()              { return maxFps; }
    public int renderDistance()      { return renderDistance; }
    public int simulationDistance()  { return simulationDistance; }
    public int fov()                 { return fov; }
    public float brightness()        { return brightness; }
    public int guiScale()            { return guiScale; }
    public float sensitivity()       { return sensitivity; }
    public boolean invertMouse()     { return invertMouse; }
    public boolean viewBobbing()     { return viewBobbing; }

    public void setFullscreen(boolean on)  { fullscreen = on; }
    public void setVsync(boolean on)       { vsync = on; }
    public void setInvertMouse(boolean on) { invertMouse = on; }
    public void setViewBobbing(boolean on) { viewBobbing = on; }

    /** Strop FPS po desítkách; nad MAX_FPS (nebo 0) = bez stropu. */
    public void setMaxFps(int fps)
    {
        if(fps <= 0 || fps > MAX_FPS)
        {
            maxFps = UNLIMITED_FPS;
            return;
        }

        int snapped = Math.round(fps / (float) FPS_STEP) * FPS_STEP;
        maxFps = clamp(snapped, MIN_FPS, MAX_FPS);
    }

    /** Render distance v chunkách. Větší než simulation ji potáhne s sebou. */
    public void setRenderDistance(int chunks)
    {
        renderDistance = clamp(chunks, MIN_RENDER, MAX_RENDER);

        if(simulationDistance < renderDistance)
        {
            simulationDistance = clamp(renderDistance, MIN_SIMULATION, MAX_SIMULATION);
        }
    }

    /** Simulation distance v chunkách. Menší než render ji stáhne s sebou. */
    public void setSimulationDistance(int chunks)
    {
        simulationDistance = clamp(chunks, MIN_SIMULATION, MAX_SIMULATION);

        if(renderDistance > simulationDistance)
        {
            renderDistance = clamp(simulationDistance, MIN_RENDER, MAX_RENDER);
        }
    }

    public void setFov(int degrees)
    {
        fov = clamp(degrees, MIN_FOV, MAX_FOV);
    }

    /** 0 = dnešní vzhled ("Moody"), 1 = nejsvětlejší ("Bright"). */
    public void setBrightness(float value)
    {
        // Po procentech - v souboru se píše na dvě desetinná místa a musí
        // se přečíst zpátky přesně totéž.
        brightness = clamp(Math.round(value * 100f) / 100f, 0f, 1f);
    }

    /** 0 = automaticky, jinak 1 až MAX_GUI_SCALE. */
    public void setGuiScale(int scale)
    {
        guiScale = clamp(scale, AUTO_GUI_SCALE, MAX_GUI_SCALE);
    }

    /** Násobek dnešní citlivosti myši, 1 = 100 %. Po pěti procentech. */
    public void setSensitivity(float value)
    {
        float snapped = Math.round(value * 20f) / 20f;
        sensitivity = clamp(snapped, MIN_SENSITIVITY, MAX_SENSITIVITY);
    }

    /** GUI měřítko dokola: Auto, 1, 2, 3, 4, Auto... (tlačítko v nastavení). */
    public void cycleGuiScale()
    {
        setGuiScale(guiScale >= MAX_GUI_SCALE ? AUTO_GUI_SCALE : guiScale + 1);
    }

    // ------------------------------------------------------------------
    // převod na čísla enginu
    // ------------------------------------------------------------------

    /** World.loadRadius: simulation distance a okraj pro mešování (LOAD_MARGIN). */
    public int loadRadius()
    {
        return simulationDistance + LOAD_MARGIN;
    }

    /** World.unloadRadius: vždycky víc než loadRadius, jinak by sloupec na hraně blikal. */
    public int unloadRadius()
    {
        return loadRadius() + UNLOAD_MARGIN;
    }

    /** World.renderDistance v blocích - odtud se počítá mlha i co se kreslí. */
    public float renderDistanceBlocks()
    {
        return renderDistance * BLOCKS_PER_CHUNK;
    }

    /** Citlivost pro Camera: dnešních 0,12 stupně na pixel krát násobek. */
    public float mouseDegreesPerPixel()
    {
        return Camera.DEFAULT_SENSITIVITY * sensitivity;
    }

    /** O kolik nejvýš jas zvedne úplnou tmu. */
    static final float BRIGHTNESS_LIFT = 0.25f;

    /**
     * Jas jako v Minecraftu: nezvedá všechno stejně, ale hlavně tmu.
     *
     * light' = light + 0,25 * jas * (1 - light)^4
     *
     * ⚠️ Ne gamma křivka 1 - (1 - l)^n. Ztmavení stěn (boky 0,6 a 0,8, spodek
     * 0,5) je tady ZAPEČENÉ ve světle vrcholu, ne násobené zvlášť jako
     * v Minecraftu - gamma by na plném slunci boky skoro srovnala s vrškem
     * a bloky by ztratily tvar. Čtvrtá mocnina zvedne tmu (0,05 -> 0,26 při
     * plném jasu), šero znatelně (0,2 -> 0,30) a osvětlené stěny skoro vůbec
     * (0,6 -> 0,606). Pro jas <= 1 je to rostoucí funkce světla - nic, co
     * bylo světlejší, nevyjde tmavší.
     *
     * Při jasu 0 se nemění nic (dnešní vzhled). Tentýž vzorec je ve fragment
     * shaderu světa (uBrightness); tady je kvůli ruce v první osobě, jejíž
     * světlo se počítá na CPU, a kvůli testu.
     */
    public static float brighten(float light, float brightness)
    {
        float dark = 1f - light;
        return light + BRIGHTNESS_LIFT * brightness * dark * dark * dark * dark;
    }

    // ------------------------------------------------------------------
    // popisky pro obrazovku nastavení (anglicky - font je jen ASCII)
    // ------------------------------------------------------------------

    public String fpsLabel()
    {
        return maxFps == UNLIMITED_FPS ? "Unlimited" : maxFps + " fps";
    }

    public String brightnessLabel()
    {
        if(brightness <= 0f) return "Moody";
        if(brightness >= 1f) return "Bright";
        return "+" + Math.round(brightness * 100f) + "%";
    }

    public String guiScaleLabel()
    {
        return guiScale == AUTO_GUI_SCALE ? "Auto" : Integer.toString(guiScale);
    }

    public String sensitivityLabel()
    {
        return Math.round(sensitivity * 100f) + "%";
    }

    // ------------------------------------------------------------------
    // soubor options.json
    // ------------------------------------------------------------------

    /** Výchozí nastavení - hra se s nimi chová přesně jako před zavedením souboru. */
    public static Options defaults()
    {
        return new Options();
    }

    public Options copy()
    {
        return fromJson(toJson(), new ArrayList<>());
    }

    /**
     * Načte nastavení. Chybějící soubor je běžný stav (mlčky výchozí hodnoty),
     * poškozený se ohlásí na stderr a použijí se výchozí hodnoty.
     */
    public static Options load(Path file)
    {
        // notExists, ne !exists: když existenci nejde zjistit (práva
        // adresáře), jde se dál ke čtení a chyba se ohlásí - jako u ostatních
        // souborů. !exists by to mlčky vzal jako "soubor není".
        if(Files.notExists(file))
        {
            return defaults();
        }

        String json;

        try
        {
            json = Files.readString(file, StandardCharsets.UTF_8);
        }
        catch(CharacterCodingException e)
        {
            System.err.println("Nastaveni " + file + ": neni to text v UTF-8 - vychozi hodnoty");
            return defaults();
        }
        catch(IOException e)
        {
            System.err.println("Nastaveni " + file + " nejdou precist: " + e + " - vychozi hodnoty");
            return defaults();
        }

        List<String> problems = new ArrayList<>();

        try
        {
            Options options = fromJson(json, problems);
            report(file, problems);
            return options;
        }
        catch(RuntimeException e)
        {
            report(file, problems);
            System.err.println("Nastaveni " + file + ": " + e.getMessage() + " - vychozi hodnoty");
            return defaults();
        }
    }

    /** Zapíše nastavení atomicky; nečitelný dosavadní soubor zazálohuje do .bak. */
    public boolean save(Path file)
    {
        return SafeFiles.writeAtomically(file, toJson(), Options::loadsCompletely, "Nastaveni");
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

    private static void report(Path file, List<String> problems)
    {
        for(String problem : problems)
        {
            System.err.println("Nastaveni " + file + ": " + problem);
        }
    }

    /** Nastavení jako JSON - přesně to, co zapíše save(). */
    String toJson()
    {
        return "{\n"
                + "  \"format\": " + FORMAT + ",\n"
                + "  \"fullscreen\": " + fullscreen + ",\n"
                + "  \"vsync\": " + vsync + ",\n"
                + "  \"maxFps\": " + maxFps + ",\n"
                + "  \"renderDistance\": " + renderDistance + ",\n"
                + "  \"simulationDistance\": " + simulationDistance + ",\n"
                + "  \"fov\": " + fov + ",\n"
                + "  \"brightness\": " + String.format(Locale.ROOT, "%.2f", brightness) + ",\n"
                + "  \"guiScale\": " + guiScale + ",\n"
                + "  \"sensitivity\": " + String.format(Locale.ROOT, "%.2f", sensitivity) + ",\n"
                + "  \"invertMouse\": " + invertMouse + ",\n"
                + "  \"viewBobbing\": " + viewBobbing + "\n"
                + "}\n";
    }

    /**
     * Nastavení z JSON textu. Když to není JSON objekt, hodí
     * IllegalArgumentException. Chybějící, špatného typu nebo mimo meze jednotlivé
     * hodnoty jen oznámí do problems a nechá výchozí, resp. oříznutou hodnotu.
     *
     * Pořadí render a simulation distance: nejdřív simulation, pak render
     * přes clamp - v souboru s render > simulation vyhraje simulation (render
     * se stáhne), protože ta říká, kolik světa se smí načíst.
     */
    static Options fromJson(String json, List<String> problems)
    {
        if(!(Json.parse(json) instanceof Map<?, ?> root))
        {
            throw new IllegalArgumentException("koren neni objekt");
        }

        if(root.get("format") instanceof Double format && format > FORMAT)
        {
            problems.add("format " + format + " je novejsi nez " + FORMAT
                    + " - nezname udaje se ignoruji");
        }

        Options o = new Options();

        Boolean b;
        Double d;

        if((b = bool(root, "fullscreen", problems)) != null)  o.fullscreen = b;
        if((b = bool(root, "vsync", problems)) != null)       o.vsync = b;
        if((b = bool(root, "invertMouse", problems)) != null) o.invertMouse = b;
        if((b = bool(root, "viewBobbing", problems)) != null) o.viewBobbing = b;

        if((d = number(root, "maxFps", problems)) != null)
        {
            o.setMaxFps(d.intValue());
            checkRange(problems, "maxFps", d, o.maxFps);
        }

        Double simulation = number(root, "simulationDistance", problems);
        Double render = number(root, "renderDistance", problems);

        if(simulation != null)
        {
            o.simulationDistance = clamp(simulation.intValue(), MIN_SIMULATION, MAX_SIMULATION);
            checkRange(problems, "simulationDistance", simulation, o.simulationDistance);
        }
        if(render != null)
        {
            o.renderDistance = clamp(render.intValue(), MIN_RENDER, MAX_RENDER);
            checkRange(problems, "renderDistance", render, o.renderDistance);
        }
        if(o.renderDistance > o.simulationDistance)
        {
            problems.add("renderDistance " + o.renderDistance + " je vetsi nez simulationDistance "
                    + o.simulationDistance + " - stahuje se");
            o.renderDistance = o.simulationDistance;
        }

        if((d = number(root, "fov", problems)) != null)
        {
            o.setFov(d.intValue());
            checkRange(problems, "fov", d, o.fov);
        }
        if((d = number(root, "brightness", problems)) != null)
        {
            o.setBrightness(d.floatValue());
            checkRange(problems, "brightness", d, o.brightness);
        }
        if((d = number(root, "guiScale", problems)) != null)
        {
            o.setGuiScale(d.intValue());
            checkRange(problems, "guiScale", d, o.guiScale);
        }
        if((d = number(root, "sensitivity", problems)) != null)
        {
            o.setSensitivity(d.floatValue());
            checkRange(problems, "sensitivity", d, o.sensitivity);
        }

        return o;
    }

    private static Boolean bool(Map<?, ?> root, String key, List<String> problems)
    {
        Object value = root.get(key);

        if(value == null)
        {
            return null;   // chybějící hodnota = výchozí, mlčky (starší soubor)
        }
        if(value instanceof Boolean flag)
        {
            return flag;
        }

        problems.add(key + " neni true/false - vychozi hodnota");
        return null;
    }

    private static Double number(Map<?, ?> root, String key, List<String> problems)
    {
        Object value = root.get(key);

        if(value == null)
        {
            return null;
        }
        if(value instanceof Double number && Double.isFinite(number))
        {
            return number;
        }

        problems.add(key + " neni cislo - vychozi hodnota");
        return null;
    }

    /** Hodnota ze souboru se musela oříznout (nebo zaokrouhlit na krok)? Ohlásit. */
    private static void checkRange(List<String> problems, String key, double original, double used)
    {
        if(Math.abs(original - used) > 1e-3)
        {
            problems.add(key + " " + original + " je mimo meze - pouzije se " + used);
        }
    }

    static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    static float clamp(float value, float min, float max)
    {
        return Float.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
