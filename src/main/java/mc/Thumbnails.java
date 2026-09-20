package mc;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;

/**
 * Náhled světa: z obsahu obrazovky malý obrázek a zpátky ze souboru.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ ŘÁDKY SE PŘEKLÁPĚJÍ. glReadPixels vrací řádek 0 DOLE (počátek GL je
 * v levém dolním rohu), kdežto obrázek má řádek 0 NAHOŘE. Bez překlopení by
 * byl náhled vzhůru nohama - a je to chyba, které si v malém obrázku nemusí
 * nikdo všimnout, dokud se na něj nepodívá pořádně.
 *
 * ⚠️ ZMENŠUJE SE PRŮMĚROVÁNÍM, NE VYNECHÁVÁNÍM PIXELŮ. Okno je řádově 10x
 * větší než náhled, takže brát každý desátý pixel znamená, že z celého stromu
 * rozhodne jeden texel - výsledek je šum, který se mezi snímky mění. Box filtr
 * (průměr všech zdrojových pixelů, co na cílový pixel padnou) dá klidný obrázek
 * a stojí jedno projití obrazovky.
 *
 * Nejdřív se ale vystřihne STŘED se správným poměrem stran. Zmáčknout širokou
 * obrazovku do 16:9 náhledu by krajinu natáhlo.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL - dostane hotový buffer z glReadPixels, takže jde testovat
 * headless. AWT ImageIO je součást JDK, žádná další závislost.
 */
public final class Thumbnails {

    /** Rozměr náhledu v pixelech - 16:9, ať sedí s obvyklým oknem. */
    public static final int WIDTH = 128;
    public static final int HEIGHT = 72;

    private Thumbnails() {}

    // ------------------------------------------------------------------
    // z obrazovky do obrázku
    // ------------------------------------------------------------------

    /**
     * Zmenší obsah framebufferu na náhled outW x outH.
     *
     * rgba je to, co vrátí glReadPixels(0, 0, width, height, GL_RGBA,
     * GL_UNSIGNED_BYTE) - čtyři bajty na pixel a ŘÁDEK 0 DOLE. Výsledek je
     * ARGB v pořadí obrázku (řádek 0 nahoře) s alfou 0xFF.
     */
    public static int[] fromFramebuffer(ByteBuffer rgba, int width, int height, int outW, int outH)
    {
        if(width <= 0 || height <= 0 || outW <= 0 || outH <= 0)
        {
            throw new IllegalArgumentException("rozmery musi byt kladne: "
                    + width + "x" + height + " -> " + outW + "x" + outH);
        }

        long needed = (long) width * height * 4;

        if(rgba.remaining() < needed)
        {
            throw new IllegalArgumentException("buffer ma " + rgba.remaining()
                    + " bajtu, potreba je " + needed);
        }

        // Absolutní čtení pozici bufferu nemění, tak si ji zapamatujeme sami.
        int base = rgba.position();

        // Výřez se správným poměrem stran: co je "moc", se ořízne.
        int cropW = width;
        int cropH = height;

        if((long) width * outH > (long) height * outW)
        {
            cropW = clamp((int) Math.round((double) height * outW / outH), width);
        }
        else
        {
            cropH = clamp((int) Math.round((double) width * outH / outW), height);
        }

        int cropX = (width - cropW) / 2;
        int cropY = (height - cropH) / 2;   // v souřadnicích obrázku, tedy shora

        int[] out = new int[outW * outH];

        for(int oy = 0; oy < outH; oy++)
        {
            int sy0 = cropY + (int) ((long) oy * cropH / outH);
            int sy1 = cropY + (int) ((long) (oy + 1) * cropH / outH);

            // Při zvětšování (okno menší než náhled) by interval byl prázdný.
            if(sy1 <= sy0)
            {
                sy1 = sy0 + 1;
            }

            for(int ox = 0; ox < outW; ox++)
            {
                int sx0 = cropX + (int) ((long) ox * cropW / outW);
                int sx1 = cropX + (int) ((long) (ox + 1) * cropW / outW);

                if(sx1 <= sx0)
                {
                    sx1 = sx0 + 1;
                }

                long r = 0, g = 0, b = 0;
                int count = 0;

                for(int iy = sy0; iy < sy1; iy++)
                {
                    // Řádek iy obrázku (shora) je řádek height-1-iy framebufferu.
                    int row = base + (height - 1 - iy) * width * 4;

                    for(int ix = sx0; ix < sx1; ix++)
                    {
                        int p = row + ix * 4;

                        r += rgba.get(p) & 0xFF;
                        g += rgba.get(p + 1) & 0xFF;
                        b += rgba.get(p + 2) & 0xFF;
                        count++;
                    }
                }

                // Alfa je vždycky plná: obrazovka žádnou průhlednost nenese
                // a průhledný náhled by se v seznamu světů ztratil v pozadí.
                out[oy * outW + ox] = 0xFF000000
                        | (average(r, count) << 16)
                        | (average(g, count) << 8)
                        | average(b, count);
            }
        }

        return out;
    }

    private static int clamp(int value, int max)
    {
        return Math.max(1, Math.min(max, value));
    }

    private static int average(long sum, int count)
    {
        return (int) ((sum + count / 2) / count);   // zaokrouhlení, ne useknutí
    }

    // ------------------------------------------------------------------
    // soubor
    // ------------------------------------------------------------------

    /**
     * Zapíše náhled jako PNG. Pole je už v pořadí obrázku (řádek 0 nahoře),
     * takže se tady na rozdíl od AtlasImage nic nepřeklápí.
     *
     * ⚠️ Zapisuje se do dočasného souboru a ten se pak přejmenuje, jako
     * všechno ostatní - pád uprostřed zápisu by jinak nechal v seznamu světů
     * useknutý obrázek. Chyba hru nepoloží: vrátí false a důvod jde na stderr.
     */
    public static boolean save(int[] argb, int w, int h, Path file)
    {
        if(argb == null || w <= 0 || h <= 0 || argb.length < w * h)
        {
            System.err.println("Nahled " + file + ": " + w + "x" + h
                    + " neodpovida poli o " + (argb == null ? 0 : argb.length) + " pixelech");
            return false;
        }

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, argb, 0, w);

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

            ByteArrayOutputStream encoded = new ByteArrayOutputStream();

            if(!ImageIO.write(image, "png", encoded))
            {
                System.err.println("Nahled " + file + ": zadny zapisovac PNG");
                return false;
            }

            // force() dostane data na disk dřív, než je přejmenování ukáže.
            try(FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
            {
                ByteBuffer bytes = ByteBuffer.wrap(encoded.toByteArray());

                while(bytes.hasRemaining())
                {
                    channel.write(bytes);
                }

                channel.force(true);
            }

            SafeFiles.moveReplacing(temp, target);
            moved = true;
            return true;
        }
        catch(IOException e)
        {
            System.err.println("Nahled " + file + " nejde ulozit: " + e);
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

    /** Načtený obrázek: ARGB v pořadí obrázku (řádek 0 nahoře). */
    public record Image(int[] argb, int width, int height) {}

    /**
     * Přečte náhled ze souboru. Vrací null, když soubor není (běžný stav,
     * mlčky), když to není obrázek, nebo když je větší než limit.
     *
     * ⚠️ Rozměr se zjišťuje z HLAVIČKY, ještě než se obrázek dekóduje. Do
     * složky světa může kdokoliv podstrčit fotku 8000x6000 a dekódovat ji
     * celou jen proto, aby se pak odmítla, by stálo stovky megabajtů - a to
     * při otevření seznamu světů, kde se čtou náhledy všech najednou.
     */
    public static Image load(Path file, int maxWidth, int maxHeight)
    {
        if(!Files.isRegularFile(file))
        {
            return null;
        }

        try(ImageInputStream input = ImageIO.createImageInputStream(file.toFile()))
        {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);

            if(readers == null || !readers.hasNext())
            {
                System.err.println("Nahled " + file + ": neni to obrazek");
                return null;
            }

            ImageReader reader = readers.next();

            try
            {
                reader.setInput(input, true, true);

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                if(width <= 0 || height <= 0 || width > maxWidth || height > maxHeight)
                {
                    System.err.println("Nahled " + file + " je " + width + "x" + height
                            + ", nejvic smi byt " + maxWidth + "x" + maxHeight);
                    return null;
                }

                BufferedImage image = reader.read(0);
                int[] argb = new int[width * height];
                image.getRGB(0, 0, width, height, argb, 0, width);

                return new Image(argb, width, height);
            }
            finally
            {
                reader.dispose();
            }
        }
        catch(IOException | RuntimeException e)
        {
            // RuntimeException: dekodéry ImageIO na poškozených datech umí
            // hodit i něco jiného než IOException - a hra kvůli tomu padat nemá.
            System.err.println("Nahled " + file + " nejde precist: " + e);
            return null;
        }
    }
}
