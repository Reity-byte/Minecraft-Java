package mc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

        int size = BlockAtlas.ATLAS_PIXELS;

        try
        {
            BufferedImage image = ImageIO.read(file.toFile());

            if(image == null)
            {
                System.err.println("Atlas " + file + ": neni to obrazek - pouzije se proceduralni");
                return null;
            }

            if(image.getWidth() != size || image.getHeight() != size)
            {
                System.err.println("Atlas " + file + ": ma " + image.getWidth() + "x" + image.getHeight()
                        + ", ceka se " + size + "x" + size + " - pouzije se proceduralni");
                return null;
            }

            int[] pixels = new int[size * size];

            for(int y = 0; y < size; y++)
            {
                image.getRGB(0, size - 1 - y, size, 1, pixels, y * size, size);
            }

            return pixels;
        }
        catch(IOException e)
        {
            System.err.println("Atlas " + file + " nejde precist: " + e.getMessage()
                    + " - pouzije se proceduralni");
            return null;
        }
    }
}
