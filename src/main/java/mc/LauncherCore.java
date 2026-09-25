package mc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Logika launcheru bez okna: kde je poslední verze hry, jestli je novější
 * než nainstalovaná, stažení a rozbalení a cesta ke spustitelné hře.
 *
 * ---------------------------------------------------------------------------
 * Verze hry jsou GitHub Releases repozitáře (release.yml je tam dává při
 * každém tagu v*): v každém je balíček pro Windows, macOS a Linux. Launcher
 * si vezme ten pro svůj systém a rozbalí ho do
 *
 *   <data hry>/versions/<tag>/
 *
 * a do versions/current.txt zapíše, která verze je nainstalovaná. Data hry
 * (světy, textury) jsou o patro výš a sdílí je všechny verze - viz GameDirs.
 *
 * ⚠️ Rozbalení hlídá "zip slip": položka archivu s ../ v cestě by jinak
 * zapsala soubor kamkoliv na disk. Taková položka archiv celý odmítne.
 * ---------------------------------------------------------------------------
 *
 * Síť je jen v latest() a download(); zbytek jde otestovat bez ní.
 */
public final class LauncherCore {

    public static final String REPO = "Reity-byte/Minecraft-Java";
    public static final String LATEST_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";

    public record Asset(String name, String url, long size) {}

    public record Release(String tag, List<Asset> assets) {}

    /** Systém, pro který se stahuje, a jak se jeho balíček jmenuje a rozbaluje. */
    public enum Platform {
        WINDOWS("windows", ".zip"),
        MACOS("macos", ".zip"),
        LINUX("linux", ".tar.gz");

        final String key;
        final String extension;

        Platform(String key, String extension)
        {
            this.key = key;
            this.extension = extension;
        }

        public static Platform of(String osName)
        {
            String os = osName.toLowerCase(Locale.ROOT);
            return os.contains("win") ? WINDOWS : os.contains("mac") ? MACOS : LINUX;
        }

        public static Platform current()
        {
            return of(System.getProperty("os.name", ""));
        }
    }

    private LauncherCore() {}

    // ------------------------------------------------------------------
    // release a verze
    // ------------------------------------------------------------------

    /** Release z odpovědi GitHub API (releases/latest). Nesmysl = IllegalArgumentException. */
    public static Release parseRelease(String json)
    {
        if(!(Json.parse(json) instanceof Map<?, ?> root) || !(root.get("tag_name") instanceof String tag))
        {
            throw new IllegalArgumentException("odpoved nema tag_name");
        }

        List<Asset> assets = new ArrayList<>();

        if(root.get("assets") instanceof List<?> list)
        {
            for(Object o : list)
            {
                if(o instanceof Map<?, ?> a && a.get("name") instanceof String name
                        && a.get("browser_download_url") instanceof String url)
                {
                    long size = a.get("size") instanceof Double d ? d.longValue() : -1;
                    assets.add(new Asset(name, url, size));
                }
            }
        }

        return new Release(tag, assets);
    }

    /** Balíček hry pro systém, nebo null (release pro něj nic nemá). Launcher sám se přeskočí. */
    public static Asset assetFor(Release release, Platform platform)
    {
        for(Asset asset : release.assets())
        {
            String name = asset.name().toLowerCase(Locale.ROOT);

            if(name.startsWith("minecraftclaude-") && !name.contains("launcher")
                    && name.contains(platform.key) && name.endsWith(platform.extension))
            {
                return asset;
            }
        }

        return null;
    }

    /**
     * Porovná verze jako čísla po částech: v1.10 > v1.9, "v1.2" = "1.2.0".
     * Záporné = a je starší. null (nic nenainstalováno) je nejstarší.
     */
    public static int compareVersions(String a, String b)
    {
        if(a == null || b == null)
        {
            return a == null ? (b == null ? 0 : -1) : 1;
        }

        String[] pa = a.replaceAll("^[vV]", "").split("[^0-9]+");
        String[] pb = b.replaceAll("^[vV]", "").split("[^0-9]+");

        for(int i = 0; i < Math.max(pa.length, pb.length); i++)
        {
            long na = i < pa.length && !pa[i].isEmpty() ? Long.parseLong(pa[i]) : 0;
            long nb = i < pb.length && !pb[i].isEmpty() ? Long.parseLong(pb[i]) : 0;

            if(na != nb)
            {
                return Long.compare(na, nb);
            }
        }

        return 0;
    }

    // ------------------------------------------------------------------
    // instalace
    // ------------------------------------------------------------------

    public static Path versionsDir(Path dataRoot)
    {
        return dataRoot.resolve("versions");
    }

    /** Složka verze - jméno z tagu jen z bezpečných znaků, ať tag nemůže vést jinam. */
    public static Path installDir(Path dataRoot, String tag)
    {
        String safe = tag.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("\\.{2,}", "_");
        return versionsDir(dataRoot).resolve(safe.isEmpty() || safe.startsWith(".") ? "_" + safe : safe);
    }

    /** Nainstalovaná verze (tag), nebo null. */
    public static String installedVersion(Path dataRoot)
    {
        try
        {
            String tag = Files.readString(versionsDir(dataRoot).resolve("current.txt"), StandardCharsets.UTF_8).trim();
            return tag.isEmpty() || !Files.exists(executable(installDir(dataRoot, tag), Platform.current())) ? null : tag;
        }
        catch(IOException e)
        {
            return null;
        }
    }

    public static void markInstalled(Path dataRoot, String tag) throws IOException
    {
        Files.createDirectories(versionsDir(dataRoot));
        Files.writeString(versionsDir(dataRoot).resolve("current.txt"), tag + "\n", StandardCharsets.UTF_8);
    }

    /** Spustitelná hra uvnitř rozbaleného balíčku (jpackage app-image). */
    public static Path executable(Path installDir, Platform platform)
    {
        return switch(platform)
        {
            case WINDOWS -> installDir.resolve("MinecraftClaude").resolve("MinecraftClaude.exe");
            case MACOS -> installDir.resolve("MinecraftClaude.app").resolve("Contents").resolve("MacOS").resolve("MinecraftClaude");
            case LINUX -> installDir.resolve("MinecraftClaude").resolve("bin").resolve("MinecraftClaude");
        };
    }

    /**
     * Rozbalí zip do dest. Položka, která by vyšla mimo dest (../, absolutní
     * cesta), celý archiv odmítne - IOException, nic se nerozbalí dál.
     */
    public static void unzip(InputStream in, Path dest) throws IOException
    {
        Path root = dest.toAbsolutePath().normalize();
        Files.createDirectories(root);

        try(ZipInputStream zip = new ZipInputStream(in))
        {
            ZipEntry entry;

            while((entry = zip.getNextEntry()) != null)
            {
                // Windows PowerShell 5.1 (Compress-Archive) píše zpětná lomítka
                // a složku jako "jmeno\" - ZipEntry.isDirectory() zná jen "/".
                String name = entry.getName().replace('\\', '/');
                boolean directory = name.endsWith("/");
                Path target = root.resolve(name).normalize();

                if(!target.startsWith(root) || target.equals(root) && !directory)
                {
                    throw new IOException("Archiv ma polozku mimo cil: " + entry.getName());
                }

                if(directory)
                {
                    Files.createDirectories(target);
                }
                else
                {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Rozbalí stažený balíček. Windows jde přes Javu; macOS a Linux přes
     * systémové ditto a tar, protože zip v Javě neumí spustitelné bity
     * (hra by se pak nedala spustit).
     */
    public static void extract(Path archive, Path dest, Platform platform) throws IOException, InterruptedException
    {
        Files.createDirectories(dest);

        if(platform == Platform.WINDOWS)
        {
            try(InputStream in = Files.newInputStream(archive))
            {
                unzip(in, dest);
            }
            return;
        }

        List<String> command = platform == Platform.MACOS
                ? List.of("ditto", "-x", "-k", archive.toString(), dest.toString())
                : List.of("tar", "-xzf", archive.toString(), "-C", dest.toString());

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().transferTo(OutputStream.nullOutputStream());

        if(process.waitFor() != 0)
        {
            throw new IOException("Rozbaleni selhalo: " + String.join(" ", command));
        }
    }

    // ------------------------------------------------------------------
    // síť
    // ------------------------------------------------------------------

    /**
     * Klient se staví až při prvním dotazu, ne při načtení třídy - jinak by
     * omezená síť (sandbox, firewall) shodila i logiku, která síť nepotřebuje.
     */
    private static HttpClient http;

    private static synchronized HttpClient http()
    {
        if(http == null)
        {
            http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }

        return http;
    }

    /** Poslední release z GitHubu. */
    public static Release latest() throws IOException, InterruptedException
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_URL))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MinecraftClaude-Launcher")
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());

        if(response.statusCode() == 404)
        {
            throw new IOException("Zadny release zatim neni");
        }
        if(response.statusCode() != 200)
        {
            throw new IOException("GitHub odpovedel " + response.statusCode());
        }

        return parseRelease(response.body());
    }

    /** Stáhne soubor; progress dostává počet stažených bajtů. */
    public static void download(String url, Path target, LongConsumer progress) throws IOException, InterruptedException
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "MinecraftClaude-Launcher")
                .build();

        HttpResponse<InputStream> response = http().send(request, HttpResponse.BodyHandlers.ofInputStream());

        if(response.statusCode() != 200)
        {
            throw new IOException("Stazeni selhalo: " + response.statusCode());
        }

        Files.createDirectories(target.toAbsolutePath().getParent());

        try(InputStream in = response.body(); OutputStream out = Files.newOutputStream(target))
        {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;

            while((read = in.read(buffer)) > 0)
            {
                out.write(buffer, 0, read);
                total += read;
                progress.accept(total);
            }
        }
    }
}
