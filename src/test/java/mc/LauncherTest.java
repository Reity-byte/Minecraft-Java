package mc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Logika launcheru bez site a bez okna. */
public class LauncherTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    static final String RELEASE = """
            {"tag_name": "v1.3", "name": "x", "assets": [
              {"name": "MinecraftClaudeLauncher-windows.zip", "browser_download_url": "https://l/w", "size": 100},
              {"name": "MinecraftClaude-windows.zip", "browser_download_url": "https://g/w", "size": 31000000},
              {"name": "MinecraftClaude-macos.zip", "browser_download_url": "https://g/m", "size": 5},
              {"name": "MinecraftClaude-linux.tar.gz", "browser_download_url": "https://g/l", "size": 6}
            ]}
            """;

    public static void main(String[] args) throws IOException {
        LauncherCore.Release r = LauncherCore.parseRelease(RELEASE);
        check("release: tag a ctyri soubory", r.tag().equals("v1.3") && r.assets().size() == 4, "");

        LauncherCore.Asset win = LauncherCore.assetFor(r, LauncherCore.Platform.WINDOWS);
        check("Windows: hra, ne launcher", win != null && win.url().equals("https://g/w") && win.size() == 31000000, "" + win);
        check("macOS a Linux: svuj balicek",
                LauncherCore.assetFor(r, LauncherCore.Platform.MACOS).url().equals("https://g/m")
                        && LauncherCore.assetFor(r, LauncherCore.Platform.LINUX).url().equals("https://g/l"), "");
        check("bez balicku pro system: null",
                LauncherCore.assetFor(new LauncherCore.Release("v1", java.util.List.of()), LauncherCore.Platform.LINUX) == null, "");
        boolean threw = false;
        try { LauncherCore.parseRelease("{\"message\": \"Not Found\"}"); } catch (IllegalArgumentException e) { threw = true; }
        check("odpoved bez tagu se odmitne", threw, "");

        check("system podle jmena",
                LauncherCore.Platform.of("Windows 11") == LauncherCore.Platform.WINDOWS
                        && LauncherCore.Platform.of("Mac OS X") == LauncherCore.Platform.MACOS
                        && LauncherCore.Platform.of("Linux") == LauncherCore.Platform.LINUX, "");

        check("verze: cisla, ne text (1.10 > 1.9)",
                LauncherCore.compareVersions("v1.10", "v1.9") > 0 && LauncherCore.compareVersions("v1.2", "1.2.0") == 0
                        && LauncherCore.compareVersions("v1.2", "v1.3") < 0 && LauncherCore.compareVersions(null, "v1") < 0
                        && LauncherCore.compareVersions("v2", null) > 0, "");

        Path root = Path.of("data");
        check("slozka verze jen z bezpecnych znaku",
                LauncherCore.installDir(root, "v1.3").equals(root.resolve("versions").resolve("v1.3"))
                        && LauncherCore.installDir(root, "../../evil").startsWith(root.resolve("versions"))
                        && !LauncherCore.installDir(root, "../../evil").toString().contains(".."),
                LauncherCore.installDir(root, "../../evil").toString());
        check("spustitelna hra v balicku podle systemu",
                LauncherCore.executable(root, LauncherCore.Platform.WINDOWS).endsWith(Path.of("MinecraftClaude", "MinecraftClaude.exe"))
                        && LauncherCore.executable(root, LauncherCore.Platform.MACOS)
                        .endsWith(Path.of("MinecraftClaude.app", "Contents", "MacOS", "MinecraftClaude")), "");

        Path dir = Files.createTempDirectory("mc-launcher");
        try {
            LauncherCore.unzip(new ByteArrayInputStream(zip("MinecraftClaude/MinecraftClaude.exe", "a/b/c.txt")), dir);
            check("rozbaleni vcetne vnorenych slozek", Files.readString(dir.resolve("a/b/c.txt")).equals("obsah")
                    && Files.exists(dir.resolve("MinecraftClaude/MinecraftClaude.exe")), "");

            // Zip z Windows PowerShellu: zpetna lomitka, slozka jako "jmeno\".
            LauncherCore.unzip(new ByteArrayInputStream(zip("hra\\legal\\", "hra\\legal\\a.txt")), dir.resolve("ps"));
            check("zip se zpetnymi lomitky (Compress-Archive): slozka je slozka",
                    Files.isDirectory(dir.resolve("ps/hra/legal")) && Files.exists(dir.resolve("ps/hra/legal/a.txt")), "");

            threw = false;
            try {
                LauncherCore.unzip(new ByteArrayInputStream(zip("..\\utek2.txt")), dir.resolve("y"));
            } catch (IOException e) { threw = true; }
            check("zip slip i se zpetnym lomitkem", threw && !Files.exists(dir.resolve("utek2.txt")), "");

            threw = false;
            try {
                LauncherCore.unzip(new ByteArrayInputStream(zip("../utek.txt")), dir.resolve("x"));
            } catch (IOException e) { threw = true; }
            check("zip slip (../) se odmitne a nic nevznikne", threw && !Files.exists(dir.resolve("utek.txt")), "");

            check("nic nenainstalovano = null", LauncherCore.installedVersion(dir) == null, "");
            Path game = LauncherCore.executable(LauncherCore.installDir(dir, "v1.3"), LauncherCore.Platform.current());
            Files.createDirectories(game.getParent());
            Files.writeString(game, "x");
            LauncherCore.markInstalled(dir, "v1.3");
            check("po instalaci je verze znama", "v1.3".equals(LauncherCore.installedVersion(dir)), "" + LauncherCore.installedVersion(dir));
            Files.delete(game);
            check("smazana hra = jako nenainstalovano", LauncherCore.installedVersion(dir) == null, "");
        } finally {
            GameDirsTest.deleteTree(dir);
        }

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (String name : names) {
                out.putNextEntry(new ZipEntry(name));
                out.write("obsah".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
