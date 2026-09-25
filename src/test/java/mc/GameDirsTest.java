package mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Datova slozka hry podle systemu a prenos dat z pracovniho adresare. */
public class GameDirsTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        check("bez Launch (IntelliJ, testy) je koren pracovni adresar",
                GameDirs.root().equals(Path.of("")) && Options.FILE.equals(Path.of("options.json"))
                        && WorldSaves.ROOT.equals(Path.of("saves")), GameDirs.root().toString());

        check("Windows: %APPDATA%\\MinecraftClaude",
                GameDirs.userDataDir("Windows 11", "C:\\Users\\a\\AppData\\Roaming", null, "C:\\Users\\a")
                        .equals(Path.of("C:\\Users\\a\\AppData\\Roaming", "MinecraftClaude")), "");
        check("Windows bez APPDATA: pod domovskou slozkou",
                GameDirs.userDataDir("Windows 10", null, null, "/home/a")
                        .equals(Path.of("/home/a", "AppData", "Roaming", "MinecraftClaude")), "");
        check("macOS: Library/Application Support",
                GameDirs.userDataDir("Mac OS X", null, null, "/Users/a")
                        .equals(Path.of("/Users/a", "Library", "Application Support", "MinecraftClaude")), "");
        check("Linux: XDG_DATA_HOME, jinak ~/.local/share",
                GameDirs.userDataDir("Linux", null, "/x", "/home/a").equals(Path.of("/x", "minecraft-claude"))
                        && GameDirs.userDataDir("Linux", null, null, "/home/a")
                        .equals(Path.of("/home/a", ".local", "share", "minecraft-claude")), "");

        Path from = Files.createTempDirectory("mc-from");
        Path to = Files.createTempDirectory("mc-to");
        try {
            Files.createDirectories(from.resolve("saves/World 1"));
            Files.writeString(from.resolve("saves/World 1/world.dat"), "svet");
            Files.createDirectories(from.resolve("textures"));
            Files.writeString(from.resolve("textures/atlas.png"), "atlas");
            Files.writeString(from.resolve("options.json"), "stare");
            Files.writeString(to.resolve("options.json"), "nove");

            List<String> moved = GameDirs.migrate(from, to);
            check("prenese svety a textury (i vnorene)", moved.contains("saves") && moved.contains("textures")
                    && Files.readString(to.resolve("saves/World 1/world.dat")).equals("svet")
                    && Files.readString(to.resolve("textures/atlas.png")).equals("atlas"), moved.toString());
            check("co v cili uz je, neprepise", !moved.contains("options.json")
                    && Files.readString(to.resolve("options.json")).equals("nove"), "");
            check("originaly zustanou jako zaloha", Files.exists(from.resolve("saves/World 1/world.dat")), "");
            check("podruhe uz nic (vsechno tam je)", GameDirs.migrate(from, to).isEmpty(), "");
            check("ze slozky do sebe same nic", GameDirs.migrate(to, to).isEmpty(), "");
        } finally {
            deleteTree(from);
            deleteTree(to);
        }

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
