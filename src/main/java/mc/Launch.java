package mc;

import java.nio.file.Path;
import java.util.List;

/**
 * Vstupní bod nainstalované hry (jar, jpackage, launcher).
 *
 * Přepne data do složky podle systému (GameDirs), při prvním startu do ní
 * zkopíruje světy a textury z pracovního adresáře a teprve pak spustí Main.
 * ⚠️ Nesmí sáhnout na nic dalšího - Main a třídy s cestami (Options,
 * WorldSaves...) se musí načíst až po nastavení kořene.
 *
 * Z IntelliJ se dál spouští přímo mc.Main: data zůstávají v projektu.
 */
public final class Launch {

    private Launch() {}

    public static void main(String[] args)
    {
        Path root = GameDirs.useUserData();
        List<String> moved = GameDirs.migrate(Path.of(""), root);

        System.out.println("Data hry: " + root.toAbsolutePath()
                + (moved.isEmpty() ? "" : "  (prenesene z pracovniho adresare: " + String.join(", ", moved) + ")"));

        Main.main(args);
    }
}
