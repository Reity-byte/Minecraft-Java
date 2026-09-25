package mc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimální JSON: čtení do obyčejných Java objektů a zápis řetězce.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ VLASTNÍ PARSER MÍSTO KNIHOVNY SCHVÁLNĚ. JSON soubory hry (nastavení,
 * klávesy, tuning, bloky a recepty z labu, metadata světů) mají pár desítek
 * řádků; kvůli nim tahat do pom.xml Jackson nebo Gson (a s nimi reflexi
 * a stovky kilobajtů) nestojí za to.
 * Čte se striktní JSON podle RFC 8259, žádná rozšíření (komentáře, čárky
 * za posledním prvkem, řetězce v apostrofech) - soubor, který projde tady,
 * přečte i jakýkoliv jiný nástroj.
 *
 * Co z čeho vznikne:
 *   objekt -> LinkedHashMap<String,Object> (pořadí klíčů jako v souboru,
 *             u duplicitního klíče vyhrává poslední),
 *   pole   -> List<Object>,
 *   číslo  -> Double (i celé - rozlišit int od zlomku je věc volajícího),
 *   true / false -> Boolean, null -> null, řetězec -> String.
 *
 * Chyba je vždycky IllegalArgumentException s řádkem a sloupcem - nikdy
 * jiná výjimka, takže volajícímu stačí chytat jednu.
 * ---------------------------------------------------------------------------
 */
final class Json {

    /**
     * Strop vnoření. Rekurzivní sestup by na souboru "[[[[[..." s milionem
     * závorek spadl na StackOverflowError, a ten se jako chyba souboru
     * nechytá. Skutečný soubor bloků má hloubku 4.
     */
    private static final int MAX_DEPTH = 64;

    private final String text;
    private int pos;
    private int depth;

    private Json(String text)
    {
        this.text = text;
    }

    // ------------------------------------------------------------------
    // čtení
    // ------------------------------------------------------------------

    /** Přečte celý text jako jednu JSON hodnotu. Cokoliv za ní kromě mezer je chyba. */
    static Object parse(String text)
    {
        Json parser = new Json(text);

        // BOM na začátku přidávají některé editory na Windows (Poznámkový blok).
        if(!text.isEmpty() && text.charAt(0) == 0xFEFF)
        {
            parser.pos = 1;
        }

        parser.skipWhitespace();
        Object value = parser.value();
        parser.skipWhitespace();

        if(parser.pos < text.length())
        {
            throw parser.error("za koncem hodnoty je jeste " + parser.found());
        }

        return value;
    }

    private Object value()
    {
        if(pos >= text.length())
        {
            throw error("ocekava se hodnota, je tu konec textu");
        }

        char c = text.charAt(pos);

        return switch(c)
        {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default ->
            {
                if(c == '-' || isDigit(c))
                {
                    yield number();
                }
                throw error("ocekava se hodnota, je tu " + found());
            }
        };
    }

    private Map<String, Object> object()
    {
        enter();
        pos++; // '{'

        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();

        if(peek() == '}')
        {
            pos++;
            depth--;
            return map;
        }

        while(true)
        {
            skipWhitespace();

            if(peek() != '"')
            {
                throw error("ocekava se klic v uvozovkach, je tu " + found());
            }

            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, value());
            skipWhitespace();

            if(peek() == ',')
            {
                pos++;
                continue;
            }
            if(peek() == '}')
            {
                pos++;
                depth--;
                return map;
            }

            throw error("ocekava se ',' nebo '}', je tu " + found());
        }
    }

    private List<Object> array()
    {
        enter();
        pos++; // '['

        List<Object> list = new ArrayList<>();
        skipWhitespace();

        if(peek() == ']')
        {
            pos++;
            depth--;
            return list;
        }

        while(true)
        {
            skipWhitespace();
            list.add(value());
            skipWhitespace();

            if(peek() == ',')
            {
                pos++;
                continue;
            }
            if(peek() == ']')
            {
                pos++;
                depth--;
                return list;
            }

            throw error("ocekava se ',' nebo ']', je tu " + found());
        }
    }

    private String string()
    {
        int start = pos;
        pos++; // otevírací '"'

        StringBuilder out = new StringBuilder();

        while(true)
        {
            if(pos >= text.length())
            {
                pos = start;
                throw error("neukonceny retezec");
            }

            char c = text.charAt(pos++);

            if(c == '"')
            {
                return out.toString();
            }
            if(c < 0x20)
            {
                // RFC 8259: řídicí znaky smí být v řetězci jen escapované.
                pos--;
                throw error("neescapovany ridici znak v retezci");
            }
            if(c != '\\')
            {
                out.append(c);
                continue;
            }

            if(pos >= text.length())
            {
                pos = start;
                throw error("neukonceny retezec");
            }

            char escape = text.charAt(pos++);

            switch(escape)
            {
                case '"'  -> out.append('"');
                case '\\' -> out.append('\\');
                case '/'  -> out.append('/');
                case 'b'  -> out.append('\b');
                case 'f'  -> out.append('\f');
                case 'n'  -> out.append('\n');
                case 'r'  -> out.append('\r');
                case 't'  -> out.append('\t');
                case 'u'  -> out.append(unicodeEscape());
                default ->
                {
                    pos -= 2;
                    throw error("neznama escape sekvence \\" + printable(escape));
                }
            }
        }
    }

    /** Čtyři hex číslice za \\u. Náhradní páry (emoji) se složí samy - jsou to dva po sobě. */
    private char unicodeEscape()
    {
        if(pos + 4 > text.length())
        {
            pos -= 2;
            throw error("useknute \\u");
        }

        int code = 0;

        for(int i = 0; i < 4; i++)
        {
            char c = text.charAt(pos + i);

            // Character.digit by vzal i arabské a plnošířkové číslice - JSON chce jen ASCII.
            int digit = c < 128 ? Character.digit(c, 16) : -1;

            if(digit < 0)
            {
                pos -= 2;
                throw error("za \\u musi byt 4 hex cislice");
            }

            code = code * 16 + digit;
        }

        pos += 4;
        return (char) code;
    }

    /** Číslo přesně podle gramatiky JSON: -?(0|[1-9][0-9]*)(.[0-9]+)?([eE][+-]?[0-9]+)? */
    private Double number()
    {
        int start = pos;

        if(peek() == '-')
        {
            pos++;
        }

        if(peek() == '0')
        {
            pos++;
        }
        else if(isDigit(peek()))
        {
            digits();
        }
        else
        {
            throw error("za '-' musi byt cislice");
        }

        if(peek() == '.')
        {
            pos++;

            if(!isDigit(peek()))
            {
                throw error("za desetinnou teckou musi byt cislice");
            }

            digits();
        }

        if(peek() == 'e' || peek() == 'E')
        {
            pos++;

            if(peek() == '+' || peek() == '-')
            {
                pos++;
            }
            if(!isDigit(peek()))
            {
                throw error("v exponentu musi byt cislice");
            }

            digits();
        }

        // Gramatika je zkontrolovaná, parseDouble už projde vždycky
        // (i "1e999" - to je nekonečno a posoudí ho až volající).
        return Double.parseDouble(text.substring(start, pos));
    }

    private Object literal(String word, Object value)
    {
        if(!text.startsWith(word, pos))
        {
            throw error("ocekava se hodnota, je tu " + found());
        }

        pos += word.length();
        return value;
    }

    // ------------------------------------------------------------------
    // pomocné
    // ------------------------------------------------------------------

    private void enter()
    {
        if(++depth > MAX_DEPTH)
        {
            throw error("prilis hluboke vnoreni (vic nez " + MAX_DEPTH + ")");
        }
    }

    private void expect(char c)
    {
        if(peek() != c)
        {
            throw error("ocekava se '" + c + "', je tu " + found());
        }

        pos++;
    }

    /** Znak na aktuální pozici, nebo 0 na konci textu (0 v JSON mimo řetězec nikdy není platný). */
    private char peek()
    {
        return pos < text.length() ? text.charAt(pos) : 0;
    }

    private void digits()
    {
        while(isDigit(peek()))
        {
            pos++;
        }
    }

    private static boolean isDigit(char c)
    {
        return c >= '0' && c <= '9';
    }

    private void skipWhitespace()
    {
        while(pos < text.length())
        {
            char c = text.charAt(pos);

            if(c != ' ' && c != '\t' && c != '\n' && c != '\r')
            {
                return;
            }

            pos++;
        }
    }

    /** Popis znaku na aktuální pozici pro chybovou zprávu. */
    private String found()
    {
        return pos >= text.length() ? "konec textu" : printable(text.charAt(pos));
    }

    /** Znak do zprávy na stderr - ta je ASCII, cokoliv jiného jako U+XXXX. */
    private static String printable(char c)
    {
        return c >= 32 && c <= 126
                ? "'" + c + "'"
                : String.format(Locale.ROOT, "U+%04X", (int) c);
    }

    private IllegalArgumentException error(String message)
    {
        int line = 1;
        int column = 1;

        for(int i = 0; i < pos && i < text.length(); i++)
        {
            if(text.charAt(i) == '\n')
            {
                line++;
                column = 1;
            }
            else
            {
                column++;
            }
        }

        return new IllegalArgumentException("JSON radek " + line + ", sloupec " + column + ": " + message);
    }

    // ------------------------------------------------------------------
    // zápis
    // ------------------------------------------------------------------

    /**
     * Řetězec v uvozovkách, escapovaný tak, aby ho přečetl parse() i každý
     * jiný parser. Ne-ASCII znaky zůstávají, jak jsou - soubor je UTF-8.
     */
    static String quote(String s)
    {
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');

        for(int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);

            switch(c)
            {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default ->
                {
                    if(c < 0x20)
                    {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    }
                    else
                    {
                        out.append(c);
                    }
                }
            }
        }

        return out.append('"').toString();
    }
}
