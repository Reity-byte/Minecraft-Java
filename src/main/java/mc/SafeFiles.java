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
 * Bezpečný zápis souborů hry.
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
 *
 * ⚠️ EXISTUJÍCÍ ZÁLOHA SE NIKDY NEPŘEPISUJE. Dřív šla každá další záloha
 * přes tu první (REPLACE_EXISTING), takže druhé poškození smazalo, co
 * zachránilo první - a u world.json i migrační údaje, podle kterých se
 * pozná už přenesený starý svět. Teď: stejný obsah se nezálohuje podruhé,
 * jiný jde do první volné z .bak, .bak.1 ... .bak.9. Když jsou obsazené
 * všechny, soubor se radši nepřepíše (stejně jako při selhání zálohy).
 *
 * Používají ho všechny soubory hry: JSON nastavení, klávesy, tuning, bloky
 * a recepty z labu, metadata i data světů (world.json, world.dat) a obrázky
 * z labu (atlas.png, skin.png).
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL.
 */
final class SafeFiles {

    private SafeFiles() {}

    /** Kolik záloh jednoho souboru nejvýš vedle sebe leží (.bak a .bak.1 až .bak.9). */
    static final int MAX_BACKUPS = 10;

    /**
     * Cesta, jak ji ukazuje UI: s lomítky i na Windows, ať hláška vypadá
     * všude stejně (a sedí s tím, co píše ARCHITECTURE.md).
     */
    static String shown(Path file)
    {
        return file.toString().replace('\\', '/');
    }

    /**
     * Hláška pro UI, když se soubor nepovedlo zapsat.
     *
     * ⚠️ JEDNO ZNĚNÍ PRO VŠECHNY LABY. Dřív pixelové módy psaly "Save failed
     * - see console" (bez souboru) a ostatní "Could not write <soubor>" (bez
     * odkazu na konzoli), takže tatáž chyba vypadala pokaždé jinak. Důvod
     * (plný disk, práva…) vypsal writeAtomically na stderr, UI říká jen kde.
     */
    static String writeFailed(Path file)
    {
        return "Could not write " + shown(file) + " - see console";
    }

    /** Kam jde první záloha souboru, který nešel celý načíst: vedle něj, s příponou .bak. */
    static Path backupOf(Path file)
    {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    /** n-tá záloha: 0 je .bak, další .bak.1, .bak.2 ... */
    static Path backupOf(Path file, int n)
    {
        return n == 0 ? backupOf(file) : file.resolveSibling(file.getFileName() + ".bak." + n);
    }

    /**
     * Atomicky zapíše text v UTF-8. readsCompletely říká, jestli dosavadní
     * soubor jde načíst celý (bez jediné výhrady) - když ne, nejdřív se
     * zazálohuje (viz backup()). label je začátek zprávy na stderr ("Nastaveni").
     *
     * Chyba hru nepoloží: vrátí false a důvod napíše na stderr.
     */
    static boolean writeAtomically(Path file, String content, Predicate<Path> readsCompletely, String label)
    {
        return writeAtomically(file, content.getBytes(StandardCharsets.UTF_8), readsCompletely, label);
    }

    /** Totéž pro bajty - world.dat a obrázky PNG. */
    static boolean writeAtomically(Path file, byte[] content, Predicate<Path> readsCompletely, String label)
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
                Path backup = backup(target);
                System.err.println(label + " " + file + ": puvodni soubor nesel cely nacist, zaloha je v " + backup);
            }

            // force() dostane data na disk dřív, než přejmenování ukáže nový
            // soubor - jinak by po výpadku proudu mohl zůstat nový, ale prázdný.
            try(FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
            {
                ByteBuffer bytes = ByteBuffer.wrap(content);

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
     * Zazálohuje soubor a vrátí, kde záloha leží. Existující zálohu nikdy
     * nepřepíše: když už některá má bajt po bajtu týž obsah, vrátí ji;
     * jinak zapíše do první volné. Když volná není, hodí IOException -
     * volající pak soubor radši nepřepíše.
     */
    static Path backup(Path file) throws IOException
    {
        for(int n = 0; n < MAX_BACKUPS; n++)
        {
            Path candidate = backupOf(file, n);

            if(!Files.exists(candidate))
            {
                Files.copy(file, candidate);
                return candidate;
            }

            if(Files.isRegularFile(candidate) && Files.mismatch(file, candidate) == -1)
            {
                return candidate;
            }
        }

        throw new IOException("vsech " + MAX_BACKUPS + " zaloh (" + backupOf(file).getFileName()
                + " az .bak." + (MAX_BACKUPS - 1) + ") je obsazenych - uklid je a zkus to znovu");
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
