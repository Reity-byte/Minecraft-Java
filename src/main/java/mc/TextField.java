package mc;

/**
 * Textové pole: obsah, fokus a úpravy. Kreslení je ve Widgets, vstup posílá
 * obrazovka - tady je jen to, co jde otestovat bez GL.
 *
 * Píše se ZNAKY z GLFW char callbacku, ne kódy kláves: velká písmena, mezery
 * a rozložení klávesnice pak fungují samy. Bere se jen ASCII 32-126, protože
 * nic jiného font nemá - česká písmena by se kreslila jako otazníky.
 * Kurzor je vždycky na konci (jako v labu): na jméno světa a seed to stačí.
 */
public final class TextField {

    private final int maxLength;
    private final StringBuilder text = new StringBuilder();
    private boolean focused = false;

    public TextField(int maxLength)
    {
        this.maxLength = maxLength;
    }

    public String text()        { return text.toString(); }
    public boolean isFocused()  { return focused; }

    public void setFocused(boolean on)
    {
        focused = on;
    }

    public void setText(String value)
    {
        text.setLength(0);
        insert(value);
    }

    /** Jeden napsaný znak. Mimo fokus, mimo ASCII nebo přes délku se zahodí. */
    public boolean type(int codepoint)
    {
        if(!focused || !printable(codepoint) || text.length() >= maxLength)
        {
            return false;
        }

        text.append((char) codepoint);
        return true;
    }

    /**
     * Vložený text (Ctrl+V). Nepovolené znaky se vynechají, konce řádků
     * se změní na mezeru a co se nevejde, se usekne.
     */
    public void insert(String value)
    {
        if(value == null)
        {
            return;
        }

        for(int i = 0; i < value.length() && text.length() < maxLength; i++)
        {
            char c = value.charAt(i);
            char use = c == '\n' || c == '\r' || c == '\t' ? ' ' : c;

            if(printable(use))
            {
                text.append(use);
            }
        }
    }

    public void backspace()
    {
        if(focused && text.length() > 0)
        {
            text.setLength(text.length() - 1);
        }
    }

    public void clear()
    {
        text.setLength(0);
    }

    static boolean printable(int codepoint)
    {
        return codepoint >= 32 && codepoint <= 126;
    }
}
