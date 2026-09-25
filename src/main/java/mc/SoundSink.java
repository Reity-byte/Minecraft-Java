package mc;

/**
 * Kam se posílají zvuky. SoundEngine je hraje přes OpenAL; herní logika
 * (Mining) zná jen tohle rozhraní, takže jde otestovat s nahrávačem místo
 * reproduktoru. null místo zvuku se tiše ignoruje - blok, který nezní
 * (Sound.breakOf(AIR)), tak nemusí nikdo kontrolovat.
 */
public interface SoundSink {

    /** Nepoziční zvuk - kroky, UI. Zní stejně ze všech stran. */
    void play(Sound sound);

    /** Zvuk v prostoru na světových souřadnicích - rozbití a položení bloku. */
    void playAt(Sound sound, float x, float y, float z);

    /**
     * Hlasitost smyčky prostředí (Sound.loops()), 0 = ticho. Smyčka hraje
     * pořád; volá se každý frame s aktuální hlasitostí.
     */
    default void loop(Sound sound, float gain) {}

    /** Nic nehraje. Pro testy, které zvuk nezajímá. */
    SoundSink SILENT = new SoundSink() {
        @Override public void play(Sound sound) {}
        @Override public void playAt(Sound sound, float x, float y, float z) {}
    };
}
