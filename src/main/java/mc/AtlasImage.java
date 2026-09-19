package mc;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
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
 * AWT ImageIO je součást JDK - žádná další závislost. Nesahá na GL.
 */
public final class AtlasImage {

    private AtlasImage() {}

    /**
     * Zapíše atlas do PNG, adresáře případně založí. Chyba hru nepoloží:
     * vrátí false a důvod napíše na stderr.
     */
    public static boolean save(int[] pixels, Path file)
    {
        int size = BlockAtlas.ATLAS_PIXELS;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);

        for(int y = 0; y < size; y++)
        {
            // Řádek y atlasu (odspodu) je řádek size-1-y obrázku (shora).
            image.setRGB(0, size - 1 - y, size, 1, pixels, y * size, size);
        }

        try
        {
            Path parent = file.toAbsolutePath().getParent();

            if(parent != null)
            {
                Files.createDirectories(parent);
            }

            if(!ImageIO.write(image, "png", file.toFile()))
            {
                System.err.println("Atlas " + file + ": zadny zapisovac PNG");
                return false;
            }

            return true;
        }
        catch(IOException e)
        {
            System.err.println("Atlas " + file + " nejde ulozit: " + e.getMessage());
            return false;
        }
    }

    /**
     * Přečte atlas z PNG. Vrací null, když soubor není (to je běžný stav,
     * mlčky), nebo když nejde přečíst či má jiný rozměr než atlas (to se
     * ohlásí na stderr). Volající pak použije procedurální atlas.
     */
    public static int[] load(Path file)
    {
        if(!Files.isRegularFile(file))
        {
            return null;
        }

        Loaded loaded = read(file);

        if(loaded.pixels() == null)
        {
            System.err.println("Atlas " + file + ": " + loaded.error() + " - pouzije se proceduralni");
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
        int size = BlockAtlas.ATLAS_PIXELS;

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

                if(width != size || height != size)
                {
                    return new Loaded(null, "image is " + width + "x" + height + ", needs " + size + "x" + size);
                }

                BufferedImage image = reader.read(0);
                int[] pixels = new int[size * size];

                for(int y = 0; y < size; y++)
                {
                    // Řádek y atlasu (odspodu) je řádek size-1-y obrázku (shora).
                    image.getRGB(0, size - 1 - y, size, 1, pixels, y * size, size);
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
        Loaded loaded = read(file);

        if(loaded.pixels() == null)
        {
            return "Not imported: " + loaded.error() + " - atlas unchanged";
        }

        editor.importAtlas(loaded.pixels());
        String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        return "Imported " + name + " - Ctrl+Z undoes, Save keeps it";
    }
}
