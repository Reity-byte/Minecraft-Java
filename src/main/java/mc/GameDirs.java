package mc;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kde hra drží svoje data: světy, textury z labu, zvuky, nastavení.
 *
 * ---------------------------------------------------------------------------
 * Výchozí je PRACOVNÍ ADRESÁŘ - tak se hra chovala odjakživa, tak ji spouští
 * IntelliJ (mc.Main) a tak běží testy, které tak nikdy nesáhnou do
 * skutečných dat hráče.
 *
 * Nainstalovaná hra (vstupní bod mc.Launch, jar a jpackage) si před startem
 * zavolá useUserData(): data jdou do složky podle systému, jako .minecraft:
 *
 *   Windows  %APPDATA%\MinecraftClaude
 *   macOS    ~/Library/Application Support/MinecraftClaude
 *   Linux    $XDG_DATA_HOME/minecraft-claude (jinak ~/.local/share/...)
 *
 * Program pak může ležet kdekoliv (i v Program Files, kam se nedá psát),
 * a víc kopií hry sdílí tytéž světy. -Dmc.home=<složka> přebije všechno.
 *
 * ⚠️ Cesty jsou statické konstanty (Options.FILE, WorldSaves.ROOT...), takže
 * se kořen musí nastavit DŘÍV, než se načte kterákoli z těch tříd - proto
 * samostatný vstupní bod Launch, který na nic jiného nesahá.
 * ---------------------------------------------------------------------------
 */
public final class GameDirs {

    /** Jméno složky dat (Windows, macOS). */
    public static final String NAME = "MinecraftClaude";

    /** Co se při prvním startu přenese z pracovního adresáře (viz migrate). */
    static final String[] MIGRATED = {"saves", "textures", "sounds", "options.json",
            "keybinds.json", "biome_tuning.json"};

    private static Path root = Path.of("");
    private static boolean used = false;

    private GameDirs() {}

    /** Cesta uvnitř datové složky (relativní, dokud se kořen nezmění). */
    public static Path path(String first, String... more)
    {
        used = true;
        return root.resolve(Path.of(first, more));
    }

    public static Path root()
    {
        return root;
    }

    /**
     * Přepne data do složky podle systému (nebo -Dmc.home) a založí ji.
     * Musí se volat, než kdokoli zavolá path() - jinak by část konstant
     * ukazovala do pracovního adresáře a část jinam.
     */
    public static Path useUserData()
    {
        if(used)
        {
            throw new IllegalStateException("GameDirs.useUserData() az po prvni ceste - volat na zacatku Launch");
        }

        String override = System.getProperty("mc.home");
        root = override != null && !override.isBlank()
                ? Path.of(override)
                : userDataDir(System.getProperty("os.name", ""), System.getenv("APPDATA"),
                System.getenv("XDG_DATA_HOME"), System.getProperty("user.home", "."));

        try
        {
            Files.createDirectories(root);
        }
        catch(IOException e)
        {
            System.err.println("Datova slozka " + root + " nejde zalozit: " + e + " - pouzije se pracovni adresar");
            root = Path.of("");
        }

        return root;
    }

    /** Složka dat pro daný systém - čistá funkce kvůli testům. */
    static Path userDataDir(String osName, String appData, String xdgDataHome, String userHome)
    {
        String os = osName.toLowerCase(Locale.ROOT);

        if(os.contains("win"))
        {
            return appData != null && !appData.isBlank()
                    ? Path.of(appData, NAME)
                    : Path.of(userHome, "AppData", "Roaming", NAME);
        }

        if(os.contains("mac"))
        {
            return Path.of(userHome, "Library", "Application Support", NAME);
        }

        return xdgDataHome != null && !xdgDataHome.isBlank()
                ? Path.of(xdgDataHome, "minecraft-claude")
                : Path.of(userHome, ".local", "share", "minecraft-claude");
    }

    /**
     * Při prvním startu ze složky, kde hra dřív ležela (from), ZKOPÍRUJE do
     * datové složky (to) světy, textury, zvuky a nastavení - jen to, co tam
     * ještě není. Originály zůstávají jako záloha; nic se nepřepisuje.
     * Vrací, co se přeneslo (pro výpis).
     */
    static List<String> migrate(Path from, Path to)
    {
        List<String> moved = new ArrayList<>();

        try
        {
            if(from.toAbsolutePath().normalize().equals(to.toAbsolutePath().normalize()))
            {
                return moved;
            }
        }
        catch(RuntimeException e)
        {
            return moved;
        }

        for(String name : MIGRATED)
        {
            Path source = from.resolve(name);
            Path target = to.resolve(name);

            if(!Files.exists(source) || Files.exists(target))
            {
                continue;
            }

            try
            {
                copy(source, target);
                moved.add(name);
            }
            catch(IOException e)
            {
                System.err.println("Prenos " + source + " do " + target + " selhal: " + e);
            }
        }

        return moved;
    }

    private static void copy(Path source, Path target) throws IOException
    {
        if(!Files.isDirectory(source))
        {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }

        Files.walkFileTree(source, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
            {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
