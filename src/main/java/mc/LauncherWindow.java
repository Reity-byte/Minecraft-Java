package mc;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Okno launcheru: stav, průběh stahování a tři tlačítka.
 *
 * ---------------------------------------------------------------------------
 * Po otevření se na pozadí zeptá GitHubu na poslední verzi. Play pak:
 *   - je-li novější verze (nebo žádná nainstalovaná), stáhne ji a rozbalí,
 *   - spustí nainstalovanou hru jako samostatný proces a launcher zavře.
 * Bez internetu Play spustí poslední staženou verzi.
 *
 * Síť a rozbalování běží ve vlastním vlákně, okno se mění jen přes
 * SwingUtilities.invokeLater - Swing není vláknově bezpečný.
 * ---------------------------------------------------------------------------
 */
final class LauncherWindow {

    private static final Color BACKGROUND = new Color(0x2B2B2B);
    private static final Color TEXT = new Color(0xEEEEEE);
    private static final Color ACCENT = new Color(0x5E9C3A);

    private final Path dataRoot;
    private final LauncherCore.Platform platform = LauncherCore.Platform.current();

    private final JFrame frame = new JFrame("Minecraft Claude Launcher");
    private final JLabel status = new JLabel("Checking for updates...", SwingConstants.CENTER);
    private final JLabel versionLabel = new JLabel("", SwingConstants.CENTER);
    private final JProgressBar progress = new JProgressBar();
    private final JButton play = new JButton("Play");
    private final JButton check = new JButton("Check for updates");
    private final JButton folder = new JButton("Open game folder");

    /** Poslední release z GitHubu, nebo null (offline / ještě se ptá). */
    private volatile LauncherCore.Release latest;

    private volatile boolean busy = false;

    LauncherWindow(Path dataRoot)
    {
        this.dataRoot = dataRoot;
    }

    void show()
    {
        JLabel title = new JLabel("Minecraft Claude", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));
        title.setForeground(TEXT);
        status.setForeground(TEXT);
        versionLabel.setForeground(new Color(0xAAAAAA));

        JPanel center = new JPanel(new GridLayout(4, 1, 0, 6));
        center.setOpaque(false);
        center.add(title);
        center.add(versionLabel);
        center.add(status);
        progress.setStringPainted(true);
        progress.setVisible(false);
        center.add(progress);

        // Barvu pozadí systémový vzhled (Windows) ignoruje, text by pak byl
        // bílý na bílém - proto jen tučné písmo a zelený rámeček.
        play.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT, 2), BorderFactory.createEmptyBorder(4, 18, 4, 18)));
        play.setFont(play.getFont().deriveFont(Font.BOLD, 18f));
        play.addActionListener(e -> startPlay());
        check.addActionListener(e -> checkForUpdates());
        folder.addActionListener(e -> openFolder());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        buttons.setOpaque(false);
        buttons.add(folder);
        buttons.add(check);
        buttons.add(play);

        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBackground(BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(24, 24, 20, 24));
        root.add(center, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(560, 320);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        refreshVersionLabel();
        checkForUpdates();
    }

    private void refreshVersionLabel()
    {
        String installed = LauncherCore.installedVersion(dataRoot);
        versionLabel.setText("Installed: " + (installed == null ? "none" : installed)
                + "   Data: " + dataRoot.toAbsolutePath());
    }

    // ------------------------------------------------------------------

    private void checkForUpdates()
    {
        if(busy)
        {
            return;
        }

        setBusy(true, "Checking for updates...");

        Thread worker = new Thread(() -> {
            String message;

            try
            {
                latest = LauncherCore.latest();
                String installed = LauncherCore.installedVersion(dataRoot);

                if(LauncherCore.assetFor(latest, platform) == null)
                {
                    message = "Version " + latest.tag() + " has no " + platform.key + " build";
                }
                else if(LauncherCore.compareVersions(installed, latest.tag()) < 0)
                {
                    message = (installed == null ? "Ready to download " : "Update available: ") + latest.tag()
                            + " - press Play";
                }
                else
                {
                    message = "Up to date (" + installed + ")";
                }
            }
            catch(Exception e)
            {
                latest = null;
                message = LauncherCore.installedVersion(dataRoot) != null
                        ? "Offline - Play starts the installed version"
                        : "Can't reach GitHub: " + e.getMessage();
            }

            String finalMessage = message;
            SwingUtilities.invokeLater(() -> setBusy(false, finalMessage));
        }, "launcher-check");

        worker.setDaemon(true);
        worker.start();
    }

    private void startPlay()
    {
        if(busy)
        {
            return;
        }

        setBusy(true, "Preparing...");

        Thread worker = new Thread(() -> {
            try
            {
                String installed = LauncherCore.installedVersion(dataRoot);
                LauncherCore.Asset asset = latest == null ? null : LauncherCore.assetFor(latest, platform);

                if(asset != null && LauncherCore.compareVersions(installed, latest.tag()) < 0)
                {
                    install(latest.tag(), asset);
                    installed = latest.tag();
                }

                if(installed == null)
                {
                    throw new IllegalStateException("No game installed yet - connect to the internet and try again");
                }

                launch(installed);
            }
            catch(Exception e)
            {
                SwingUtilities.invokeLater(() -> {
                    progress.setVisible(false);
                    setBusy(false, "Failed: " + e.getMessage());
                });
            }
        }, "launcher-play");

        worker.setDaemon(true);
        worker.start();
    }

    /** Stáhne balíček do dočasného souboru, rozbalí do čisté složky verze a zapíše ji jako aktuální. */
    private void install(String tag, LauncherCore.Asset asset) throws Exception
    {
        Path archive = LauncherCore.versionsDir(dataRoot).resolve("download" + platform.extension);
        Path dest = LauncherCore.installDir(dataRoot, tag);

        SwingUtilities.invokeLater(() -> {
            progress.setVisible(true);
            progress.setValue(0);
            status.setText("Downloading " + tag + "...");
        });

        LauncherCore.download(asset.url(), archive, bytes -> SwingUtilities.invokeLater(() -> {
            if(asset.size() > 0)
            {
                progress.setValue((int) (bytes * 100 / asset.size()));
                progress.setString(bytes / (1024 * 1024) + " / " + asset.size() / (1024 * 1024) + " MB");
            }
        }));

        SwingUtilities.invokeLater(() -> status.setText("Installing " + tag + "..."));

        deleteTree(dest);
        LauncherCore.extract(archive, dest, platform);
        Files.deleteIfExists(archive);

        if(!Files.exists(LauncherCore.executable(dest, platform)))
        {
            throw new IllegalStateException("The download has no game inside");
        }

        LauncherCore.markInstalled(dataRoot, tag);
        SwingUtilities.invokeLater(this::refreshVersionLabel);
    }

    /** Spustí hru jako samostatný proces (pracovní adresář = data) a launcher zavře. */
    private void launch(String tag) throws Exception
    {
        Path exe = LauncherCore.executable(LauncherCore.installDir(dataRoot, tag), platform);
        SwingUtilities.invokeLater(() -> status.setText("Starting " + tag + "..."));

        new ProcessBuilder(exe.toString()).directory(dataRoot.toFile()).inheritIO().start();

        Thread.sleep(1500);
        System.exit(0);
    }

    private void openFolder()
    {
        try
        {
            Files.createDirectories(dataRoot);
            Desktop.getDesktop().open(dataRoot.toFile());
        }
        catch(Exception e)
        {
            status.setText("Can't open the folder: " + e.getMessage());
        }
    }

    private void setBusy(boolean on, String message)
    {
        busy = on;
        status.setText(message);
        play.setEnabled(!on);
        check.setEnabled(!on);
    }

    private static void deleteTree(Path dir) throws java.io.IOException
    {
        if(!Files.exists(dir))
        {
            return;
        }

        try(var walk = Files.walk(dir))
        {
            for(Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(p);
            }
        }
    }
}
