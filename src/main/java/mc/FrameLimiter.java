package mc;

/**
 * Strop FPS (Max Framerate), když je vsync vypnutý.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ Čeká se na PLÁNOVANÝ začátek dalšího framu, ne "1/fps od konce tohohle".
 * Kdyby se k délce framu přičítalo pevné čekání, vyšlo by FPS vždycky nižší
 * než strop (frame 3 ms + čekání 8,3 ms = 88 místo 120). Termín se posouvá
 * o celou periodu; když se hra zdrží o víc než periodu (načítání), termín
 * se srovná na teď - jinak by se pak pár framů hnalo bez čekání, aby dohnala.
 *
 * Spí se Thread.sleep, jen poslední milisekunda se dočeká aktivně: Windows
 * budí vlákno s přesností kolem milisekundy a strop 240 FPS má periodu 4,2 ms.
 * ---------------------------------------------------------------------------
 *
 * Plánování (nextDeadline) je čistá funkce a testuje se bez časovače.
 */
final class FrameLimiter {

    private static final long SPIN_NANOS = 1_000_000L;

    private long deadline = 0;

    /** Počká do začátku dalšího framu. maxFps 0 (bez stropu) nečeká. */
    void sync(int maxFps)
    {
        if(maxFps <= 0)
        {
            deadline = 0;
            return;
        }

        long now = System.nanoTime();
        deadline = nextDeadline(deadline, now, maxFps);

        long wait = deadline - now;

        if(wait > SPIN_NANOS)
        {
            try
            {
                Thread.sleep((wait - SPIN_NANOS) / 1_000_000L, (int) ((wait - SPIN_NANOS) % 1_000_000L));
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
        }

        while(System.nanoTime() < deadline)
        {
            Thread.onSpinWait();
        }
    }

    /**
     * Kdy má začít další frame: předchozí termín + perioda, ale nikdy víc než
     * o periodu pozadu za "teď" (a úplně první frame začne od teď).
     */
    static long nextDeadline(long previous, long now, int maxFps)
    {
        long period = 1_000_000_000L / maxFps;

        if(previous == 0 || previous + period < now - period)
        {
            return now + period;
        }

        return previous + period;
    }
}
