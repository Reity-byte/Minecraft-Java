package mc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Predicate;

/**
 * Bezpečný zápis malých souborů hry (options.json, metadata světů).
 *
 * ---------------------------------------------------------------------------
 * Stejný vzor jako textures/blocks.json (BlockRegistry.save), vytažený na
 * jedno místo pro nové soubory:
 *
 * ⚠️ ZAPISUJE SE DO DOČASNÉHO SOUBORU A TEN SE PAK PŘEJMENUJE. Pád hry,
 * plný disk nebo výpadek proudu uprostřed zápisu tak nechá starý soubor
 * celý - přepisovat napřímo by v tu chvíli nechalo useknutý soubor.
 *
 * ⚠️ SOUBOR, KTERÝ NEJDE CELÝ NAČÍST, SE PŘED PŘEPSÁNÍM ZÁLOHUJE do
 * <jméno>.bak. Načetl se jen zčásti (nebo vůbec), takže to, co je v paměti,
 * nemá všechno, co v něm je - první uložení by zbytek tiše smazalo. Když se
 * zálohovat nepovede, soubor se radši nepřepíše.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL.
 */
final class SafeFiles {

    private SafeFiles() {}

    /** Kam jde záloha souboru, který nešel celý načíst: vedle něj, s příponou .bak. */
    static Path backupOf(Path file)
    {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    /**
     * Atomicky zapíše text v UTF-8. readsCompletely říká, jestli dosavadní
     * soubor jde načíst celý (bez jediné výhrady) - když ne, nejdřív se
     * zkopíruje do .bak. label je začátek zprávy na stderr ("Nastaveni").
     *
     * Chyba hru nepoloží: vrátí false a důvod napíše na stderr.
     */
    static boolean writeAtomically(Path file, String content, Predicate<Path> readsCompletely, String label)
    {
        Path target = file.toAbsolutePath();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        boolean moved = false;

        try
        {
            Path parent = target.getParent();

            if(parent != null)
            {
                Files.createDirectories(parent);
            }

            if(Files.isRegularFile(target) && !completely(readsCompletely, target))
            {
                Path backup = backupOf(target);
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                System.err.println(label + " " + file + ": puvodni soubor nesel cely nacist, zaloha je v " + backup);
            }

            // force() dostane data na disk dřív, než přejmenování ukáže nový
            // soubor - jinak by po výpadku proudu mohl zůstat nový, ale prázdný.
            try(FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
            {
                ByteBuffer bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));

                while(bytes.hasRemaining())
                {
                    channel.write(bytes);
                }

                channel.force(true);
            }

            moveReplacing(temp, target);
            moved = true;
            return true;
        }
        catch(IOException e)
        {
            System.err.println(label + " " + file + " nejde ulozit: " + e);
            return false;
        }
        finally
        {
            if(!moved)
            {
                try
                {
                    Files.deleteIfExists(temp);
                }
                catch(IOException ignored)
                {
                    // Zbytek .tmp nevadí - příští zápis ho přepíše.
                }
            }
        }
    }

    /**
     * Přejmenuje soubor přes cíl, atomicky, když to souborový systém umí;
     * jinak aspoň obyčejným přesunem - pořád lepší než psát přímo do cíle.
     */
    static void moveReplacing(Path from, Path to) throws IOException
    {
        try
        {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch(AtomicMoveNotSupportedException e)
        {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean completely(Predicate<Path> readsCompletely, Path file)
    {
        try
        {
            return readsCompletely.test(file);
        }
        catch(RuntimeException e)
        {
            return false;
        }
    }
}
