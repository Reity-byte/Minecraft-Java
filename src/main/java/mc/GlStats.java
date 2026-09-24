package mc;

/**
 * Počítadlo draw callů za frame.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ Je to JEDINÉ číslo, které o výkonu kreslení vypovídá nezávisle na
 * grafice a ovladači. Čas v milisekundách se liší mašinu od mašiny (a na
 * macOS je ovladač OpenGL mnohem dražší na jeden draw call než na Windows),
 * ale počet draw callů je pro daný obsah obrazovky všude stejný. Když se
 * z 1000 stane 50, zlepší se to všude - jen na různých strojích různě moc.
 *
 * Inkrement je jedno sečtení statického intu, takže může zůstat zapnutý
 * pořád; nikdo ho nemusí zapínat příznakem.
 * ---------------------------------------------------------------------------
 */
public final class GlStats {

    private GlStats() {}

    private static int drawCalls = 0;

    /**
     * Volá KAŽDÉ glDrawArrays ve hře - UI (Renderer2D, ImageRenderer,
     * TextRenderer, BlockIcon, pozadí) i svět (sekce, obloha, ruka, položky
     * na zemi, postava, praskliny, obrys).
     *
     * ⚠️ Nové glDrawArrays bez countDraw() je neviditelné: ikony (BlockIcon)
     * dlouho kreslily draw call za kvádr a F3 v labu to neukázalo, protože
     * se nepočítaly. Hlídá to RenderSourceTest.
     */
    public static void countDraw()
    {
        drawCalls++;
    }

    /** Kolik draw callů od posledního resetu. */
    public static int drawCalls()
    {
        return drawCalls;
    }

    /** Začátek nového framu - vynuluje počítadlo a vrátí, kolik jich byl ten minulý. */
    public static int resetFrame()
    {
        int was = drawCalls;
        drawCalls = 0;
        return was;
    }
}
