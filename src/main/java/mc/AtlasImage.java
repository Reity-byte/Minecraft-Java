package mc;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Atlas bloků jako soubor PNG - zápis z texture labu a čtení při startu.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ ŘÁDKY SE PŘI ZÁPISU I ČTENÍ PŘEKLÁPĚJÍ. Pole atlasu má řádek 0 DOLE
 * (tak ho čte GL a tak počítá řádky BlockAtlas), kdežto obrázek má řádek 0
 * NAHOŘE. Bez překlopení by atlas v editoru obrázků ležel vzhůru nohama
 * a tráva by rostla z dolního okraje dlaždice. S ním vypadá PNG přesně
 * jako dlaždice na bloku: dlaždice 0 je vlevo dole, jako v labu.
 *
 * Alfa se zachovává (voda má 0xC0, praskliny jsou poloprůhledné), a proto
 * se píše TYPE_INT_ARGB, ne RGB.
 * ---------------------------------------------------------------------------
 *
 * ⚠️ PŘEKLÁPĚNÍ JE VOLBA, PROTOŽE SKIN HO NEMÁ. Tatáž třída čte a píše
 * i textury kůže postavy (textures/skin.png): pole skinu má řádek 0 NAHOŘE,
 * protože UV modelu jsou přímo souřadnice šablony Minecraftu a v GL leží
 * t = 0 u horního okraje. Atlas se tedy překlápí, skin ne - a je to jediný
 * rozdíl mezi nimi. Viz PlayerModelMesh a Textures.playerSkin().
 *
 * AWT ImageIO je součást JDK - žádná další závislost. Nesahá na GL.
 */
public final class AtlasImage {

    private AtlasImage() {}

    /** Zapíše atlas bloků (128x128, řádky se překlápějí). */
    public static boolean save(int[] pixels, Path file)
    {
        return save(pixels, file, BlockAtlas.ATLAS_PIXELS, true);
    }

    /**
     * Zapíše čtvercový obrázek do PNG, adresáře případně založí. Chyba hru
     * nepoloží: vrátí false a důvod napíše na stderr.
     *
     * flipRows = true u atlasu (pole má řádek 0 dole, obrázek nahoře),
     * false u skinu (pole i obrázek mají řádek 0 nahoře).
     *
     * ⚠️ PNG SE KÓDUJE DO PAMĚTI A NA DISK JDE PŘES SafeFiles. ImageIO.write
     * do souboru cíl nejdřív SMAŽE a teprve pak píše, takže plný disk nebo
     * pád uprostřed nechal useknutý atlas.png - a s ním byla pryč veškerá
     * malba z labu včetně dlaždic bloků z blocks.json. Teď se píše do .tmp
     * a přejmenuje, a soubor, který nejde načíst jako obrázek tohohle
     * rozměru (poškozený, nebo třeba 256x256 připravený ručně), se před
     * přepsáním zazálohuje do .bak - stejný vzor jako u JSON souborů.
     */
    public static boolean save(int[] pixels, Path file, int size, boolean flipRows)
    {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);

        for(int y = 0; y < size; y++)
        {
            image.setRGB(0, flipRows ? size - 1 - y : y, size, 1, pixels, y * size, size);
        }

        ByteArrayOutputStream png = new ByteArrayOutputStream();

        try
        {
            if(!ImageIO.write(image, "png", png))
            {
                System.err.println("Obrazek " + file + ": zadny zapisovac PNG");
                return false;
            }
        }
        catch(IOException e)
        {
            System.err.println("Obrazek " + file + " nejde zakodovat: " + e.getMessage());
            return false;
        }

        return SafeFiles.writeAtomically(file, png.toByteArray(),
                existing -> read(existing, size, flipRows).pixels() != null, "Obrazek");
    }

    /**
     * Přečte atlas z PNG. Vrací null, když soubor není (to je běžný stav,
     * mlčky), nebo když nejde přečíst či má jiný rozměr než atlas (to se
     * ohlásí na stderr). Volající pak použije procedurální atlas.
     */
    public static int[] load(Path file)
    {
        return load(file, BlockAtlas.ATLAS_PIXELS, true);
    }

    /** Přečte čtvercový obrázek daného rozměru; null = není, nebo nejde použít. */
    public static int[] load(Path file, int size, boolean flipRows)
    {
        if(!Files.isRegularFile(file))
        {
            return null;
        }

        Loaded loaded = read(file, size, flipRows);

        if(loaded.pixels() == null)
        {
            System.err.println("Obrazek " + file + ": " + loaded.error() + " - pouzije se proceduralni");
        }

        return loaded.pixels();
    }

    /** Pixely atlasu, nebo důvod, proč z obrázku atlas není (anglicky - ukazuje ho lab). */
    public record Loaded(int[] pixels, String error) {}

    /**
     * Přečte obrázek jako atlas a zkontroluje rozměr.
     *
     * ⚠️ Rozměr se zjišťuje z HLAVIČKY, ještě než se obrázek dekóduje. Do
     * importu se dá strčit cokoliv, třeba fotka 8000 x 6000 - dekódovat ji
     * celou jen proto, aby se pak odmítla, by stálo stovky megabajtů.
     */
    public static Loaded read(Path file)
    {
        return read(file, BlockAtlas.ATLAS_PIXELS, true);
    }

    /** Totéž pro libovolný čtvercový rozměr - skin je 64x64 a nepřeklápí se. */
    public static Loaded read(Path file, int size, boolean flipRows)
    {
        if(!Files.isRegularFile(file))
        {
            return new Loaded(null, "file not found");
        }

        try(ImageInputStream input = ImageIO.createImageInputStream(file.toFile()))
        {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);

            if(readers == null || !readers.hasNext())
            {
                return new Loaded(null, "not an image");
            }

            ImageReader reader = readers.next();

            try
            {
                reader.setInput(input, true, true);

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                // ⚠️ Hláška říká OBĚ čísla a v tomhle pořadí: co se čekalo
                // a co přišlo. "needs 128x128" samo o sobě neřekne, jak velký
                // obrázek uživatel vlastně podstrčil, takže neví, o kolik vedle
                // je - a ani jestli si nespletl soubor.
                if(width != size || height != size)
                {
                    return new Loaded(null, "expected " + size + "x" + size
                            + ", got " + width + "x" + height);
                }

                BufferedImage image = reader.read(0);
                int[] pixels = new int[size * size];

                for(int y = 0; y < size; y++)
                {
                    image.getRGB(0, flipRows ? size - 1 - y : y, size, 1, pixels, y * size, size);
                }

                return new Loaded(pixels, null);
            }
            finally
            {
                reader.dispose();
            }
        }
        catch(IOException | RuntimeException e)
        {
            // RuntimeException: dekodéry ImageIO na poškozených datech umí
            // hodit i něco jiného než IOException - a lab kvůli tomu padat nemá.
            return new Loaded(null, "can't read it (" + e.getMessage() + ")");
        }
    }

    /**
     * Import hotového atlasu do editoru labu. Vrací zprávu pro stavový řádek.
     *
     * ⚠️ Při JAKÉKOLIV chybě zůstane atlas beze změny - obrázek se nejdřív
     * celý přečte a zkontroluje a do editoru jde až potom, najednou.
     * Povedený import jde vrátit přes Ctrl+Z a na disk jde až se Save.
     */
    public static String importInto(AtlasEditor editor, Path file)
    {
        // Doplní se jako při startu (Textures.completeAtlas) - tentýž soubor
        // má vypadat stejně, ať přišel načtením, nebo importem.
        return importInto(editor, file, BlockAtlas.ATLAS_PIXELS, true, "atlas",
                pixels -> Textures.completeAtlas(pixels, BlockRegistry.active()));
    }

    /**
     * Import do libovolného editoru pixelů - atlas bloků i kůže postavy.
     * what je slovo do hlášky ("atlas" / "skin"), ať uživatel pozná, co
     * zůstalo beze změny.
     */
    public static String importInto(PixelEditor editor, Path file,
                                    int size, boolean flipRows, String what)
    {
        return importInto(editor, file, size, flipRows, what, pixels -> {});
    }

    /** Totéž s úpravou přečtených pixelů před vložením do editoru. */
    private static String importInto(PixelEditor editor, Path file, int size, boolean flipRows,
                                     String what, java.util.function.Consumer<int[]> complete)
    {
        Loaded loaded = read(file, size, flipRows);

        if(loaded.pixels() == null)
        {
            return "Not imported: " + loaded.error() + " - " + what + " unchanged";
        }

        complete.accept(loaded.pixels());
        editor.importImage(loaded.pixels());
        String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        return "Imported " + name + " - Ctrl+Z undoes, Save keeps it";
    }
}
