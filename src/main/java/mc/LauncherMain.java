package mc;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Path;

/**
 * Vstupní bod launcheru: jediný program, který si hráč stáhne. Hru si
 * stáhne, aktualizuje a spustí sám (LauncherWindow). Data sdílí se hrou
 * přes GameDirs - stejná složka podle systému.
 */
public final class LauncherMain {

    private LauncherMain() {}

    public static void main(String[] args)
    {
        Path root = GameDirs.useUserData();

        try
        {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        }
        catch(Exception ignored)
        {
            // Výchozí vzhled Swingu stačí.
        }

        SwingUtilities.invokeLater(() -> new LauncherWindow(root).show());
    }
}
