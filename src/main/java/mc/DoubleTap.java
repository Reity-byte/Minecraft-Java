package mc;

/**
 * Dvojí stisk téže klávesy v krátkém okně.
 *
 * ---------------------------------------------------------------------------
 * Slouží k přepnutí letu v creative módu - dvojklik na mezerník, jako
 * v Minecraftu. Je to vlastní třída, a ne dvě pole v Main, ze dvou důvodů:
 * jednak to jde otestovat headless (Main je celý kolem GL smyčky), jednak
 * je tím na jednom místě to jediné, co je na dvojstisku ošidné.
 *
 * ⚠️ PO ÚSPĚŠNÉM DVOJSTISKU SE OKNO ZAHODÍ. Bez toho by trojí stisk přepnul
 * let dvakrát (druhý stisk s prvním, třetí s druhým) a rychlé poskakování
 * by letem blikalo. Takhle platí: každá DVOJICE stisků je jedno přepnutí.
 *
 * Čas se předává zvenčí (glfwGetTime v sekundách), takže třída nemá vlastní
 * hodiny a test si může skákat v čase, jak potřebuje.
 * ---------------------------------------------------------------------------
 */
public final class DoubleTap {

    /**
     * Nejdelší pauza mezi stisky, která se ještě počítá jako dvojstisk.
     * Minecraft má zhruba 0,25 s; o kousek delší okno odpouští pomalejší
     * prsty a pořád se netrefí při běžném opakovaném skákání.
     */
    public static final double WINDOW = 0.3;

    /** Čas předchozího stisku, nebo "nikdy". */
    private double lastPress = Double.NEGATIVE_INFINITY;

    /**
     * Ohlásí stisk klávesy. Vrací true, když je to druhý stisk v okně -
     * tedy právě ve framu, kdy se má let přepnout.
     */
    public boolean tap(double now)
    {
        boolean doubled = now - lastPress <= WINDOW;

        // Po dvojstisku se začíná od nuly, jinak by třetí stisk přepnul zas.
        lastPress = doubled ? Double.NEGATIVE_INFINITY : now;
        return doubled;
    }

    /**
     * Zapomene rozdělaný dvojstisk. Volá se při odchodu ze hry (pauza,
     * inventář, menu): mezerník před odchodem a mezerník po návratu spolu
     * nemají co dělat, i kdyby mezi nimi uběhla desetina vteřiny.
     */
    public void reset()
    {
        lastPress = Double.NEGATIVE_INFINITY;
    }
}
