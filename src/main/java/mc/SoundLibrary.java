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

    private SoundLibrary() {}

    /** Zvuk z adresáře, když tam je a jde přečíst; jinak syntetizovaný. */
    public static Wav.Pcm load(Sound sound, Path directory)
    {
        Path file = directory.resolve(sound.fileName() + ".wav");

        if(Files.isRegularFile(file))
        {
            try
            {
                Wav.Pcm recorded = Wav.decode(Files.readAllBytes(file));

                if(recorded != null)
                {
                    return recorded;
                }

                System.err.println("Zvuk " + file + ": nepodporovany format WAV"
                        + " (jen PCM 8/16 bit, mono/stereo) - hraje placeholder");
            }
            catch(IOException e)
            {
                System.err.println("Zvuk " + file + " nejde precist: " + e.getMessage()
                        + " - hraje placeholder");
            }
        }

        return Wav.decode(SoundSynth.wav(sound));
    }
}
