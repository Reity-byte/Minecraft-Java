package mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Odkud se berou zvuky.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ TOHLE JE JEDINÉ MÍSTO VÝMĚNY ZA SKUTEČNÉ NAHRÁVKY - a ani tady se nemusí
 * nic měnit. Stačí dát do adresáře sounds/ vedle hry soubor se jménem zvuku,
 * třeba sounds/break_stone.wav, a ten přebije syntézu. Chybí-li, nebo nejde
 * přečíst, použije se syntetizovaný placeholder. Dá se tak nahrazovat po
 * jednom a hra zní vždycky celá.
 *
 * Obě cesty končí týmiž bajty souboru WAV a týmž dekodérem (Wav.decode),
 * takže OpenAL nepozná, odkud zvuk přišel.
 *
 * .ogg zatím NE: dekodér Vorbisu je v LWJGL v modulu lwjgl-stb (STBVorbis),
 * tedy další závislost. Přidat ho znamená jeden modul v pom.xml a jednu
 * větev tady, která místo Wav.decode zavolá stb_vorbis_decode_memory.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na OpenAL.
 */
public final class SoundLibrary {

    /** Adresář se skutečnými zvuky, relativně k pracovnímu adresáři - jako saves/. */
    public static final Path SOUND_DIR = Path.of("sounds");

    /**
     * Největší soubor, který se vůbec zkusí číst. Zvuky hry jsou desetiny
     * sekundy; 32 MB je přes tři minuty stereo 16 bit 44,1 kHz. Větší soubor
     * (omylem pojmenovaná nahrávka) by přes readAllBytes a dekódování shodil
     * hru OutOfMemoryError - a to "chyba zvuku hru nepoloží" nedovoluje.
     */
    static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    private SoundLibrary() {}

    /** Zvuk z adresáře, když tam je a jde přečíst; jinak syntetizovaný. */
    public static Wav.Pcm load(Sound sound, Path directory)
    {
        Path file = directory.resolve(sound.fileName() + ".wav");

        if(Files.isRegularFile(file))
        {
            try
            {
                long size = Files.size(file);

                if(size > MAX_FILE_BYTES)
                {
                    System.err.println("Zvuk " + file + " ma " + size / (1024 * 1024)
                            + " MB, vic nez " + MAX_FILE_BYTES / (1024 * 1024) + " MB - hraje placeholder");
                    return Wav.decode(SoundSynth.wav(sound));
                }

                Wav.Pcm recorded = Wav.decode(Files.readAllBytes(file));

                if(recorded != null)
                {
                    return recorded;
                }

                System.err.println("Zvuk " + file + ": nepodporovany format WAV"
                        + " (jen PCM 8/16 bit, mono/stereo) - hraje placeholder");
            }
            catch(IOException | RuntimeException e)
            {
                // RuntimeException taky: vadný soubor nesmí propadnout až do
                // SoundEngine.open() a vypnout všech 13 zvuků (a hláška
                // "Zvuk vypnuty: null" neřekla, který soubor za to může).
                System.err.println("Zvuk " + file + " nejde precist: " + e
                        + " - hraje placeholder");
            }
        }

        return Wav.decode(SoundSynth.wav(sound));
    }
}
