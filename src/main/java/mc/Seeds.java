package mc;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Seed z toho, co hráč napsal do políčka.
 *
 * ---------------------------------------------------------------------------
 * Pravidlo je stejné jako v Minecraftu, protože je to to jediné, co hráč zná
 * a co si dokáže předat dál:
 *
 *   prázdné pole          -> náhodný seed
 *   text, který je číslo  -> to číslo ("12345" i "-7")
 *   cokoliv jiného        -> String.hashCode() toho textu
 *
 * ⚠️ Ten hashCode NENÍ implementační detail, na který se nemá spoléhat -
 * specifikace Javy ho má předepsaný vzorcem (s[0]*31^(n-1) + ...), takže
 * "hello" dá stejný seed ve všech verzích Javy, na všech strojích a napořád.
 * Kdyby se seed odvozoval jinak (třeba hashem pole bajtů), stejný text by
 * po upgradu JDK dal jiný svět.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL.
 */
public final class Seeds {

    private Seeds() {}

    /**
     * Seed z textu. random se zavolá jen u prázdného pole - díky tomu jde
     * v testech dosadit předvídatelný zdroj a hra si podá Seeds::random.
     */
    public static long parse(String text, LongSupplier random)
    {
        String trimmed = text == null ? "" : text.trim();

        if(trimmed.isEmpty())
        {
            return random.getAsLong();
        }

        try
        {
            return Long.parseLong(trimmed);
        }
        catch(NumberFormatException e)
        {
            // Text i číslo mimo rozsah long (Minecraft to dělá stejně).
            return trimmed.hashCode();
        }
    }

    /**
     * Náhodný seed. ThreadLocalRandom, ne new Random(): Random má jen
     * 48bitový stav, takže by z něj většina 64bitových seedů nikdy nevypadla.
     */
    public static long random()
    {
        return ThreadLocalRandom.current().nextLong();
    }
}
