package mc;

/**
 * Přepočet polohy kurzoru z okna na pixely framebufferu.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ GLFW VRACÍ MYŠ V JINÝCH JEDNOTKÁCH, NEŽ V JAKÝCH SE KRESLÍ. Poloha
 * kurzoru (glfwGetCursorPos, cursor callback) je v souřadnicích OKNA
 * ("points"), kdežto celé UI se kreslí a hit-testuje v pixelech
 * FRAMEBUFFERU - ty chodí z glfwGetFramebufferSize a jdou rovnou do
 * glViewport i do Renderer2D.
 *
 * Na běžném monitoru jsou obě čísla stejná a není to poznat. Na Retině
 * (a na jakémkoliv displeji, kde si systém kreslí ve vyšším rozlišení) je
 * framebuffer 2x větší než okno, takže kliknutí na tlačítko vlevo nahoře
 * trefilo místo dvakrát blíž ke středu - tlačítka se kreslila správně,
 * ale reagovala "vedle".
 *
 * Poměr se bere z velikosti okna proti velikosti framebufferu, ne
 * z glfwGetWindowContentScale: na Windows s měřítkem 150 % je content scale
 * 1,5, ale okno i framebuffer jsou ve stejných pixelech, takže by přepočet
 * naopak rozbil to, co dnes funguje. Poměr obou velikostí je pravda pro
 * všechny platformy.
 *
 * ⚠️ Pro ROZHLÍŽENÍ (dx, dy do Camera) se přepočet NEPOUŽÍVÁ - citlivost
 * myši je v bodech okna, jinak by se na Retině hráč rozhlížel dvakrát rychleji.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, takže jde celé otestovat headless.
 */
final class MouseScale {

    private double scaleX = 1;
    private double scaleY = 1;

    /**
     * Kolik pixelů framebufferu připadá na jeden bod okna. Nesmyslná velikost
     * (okno zmenšené na nulu při minimalizaci) nechá poměr, jaký byl -
     * dělení nulou by z myši udělalo NaN a klikání by přestalo fungovat úplně.
     */
    static double factor(int windowSize, int framebufferSize, double previous)
    {
        if(windowSize <= 0 || framebufferSize <= 0)
        {
            return previous;
        }

        return framebufferSize / (double) windowSize;
    }

    void update(int windowWidth, int windowHeight, int framebufferWidth, int framebufferHeight)
    {
        scaleX = factor(windowWidth, framebufferWidth, scaleX);
        scaleY = factor(windowHeight, framebufferHeight, scaleY);
    }

    double scaleX() { return scaleX; }
    double scaleY() { return scaleY; }

    double toFramebufferX(double windowX)
    {
        return windowX * scaleX;
    }

    double toFramebufferY(double windowY)
    {
        return windowY * scaleY;
    }
}
