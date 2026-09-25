package mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 * VARIANTY: vedle sounds/break_stone.wav se berou i break_stone_1.wav až
 * break_stone_8.wav (klidně bez toho prvního). Přehrávač pak pokaždé vybere
 * náhodně jednu - deset kroků za sebou nezní jako jedna nahrávka. Bez
 * souborů jsou varianty syntetizované (SoundSynth.variants).
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
    public static final Path SOUND_DIR = GameDirs.path("sounds");

    /**
     * Největší soubor, který se vůbec zkusí číst. Zvuky hry jsou desetiny
     * sekundy; 32 MB je přes tři minuty stereo 16 bit 44,1 kHz. Větší soubor
     * (omylem pojmenovaná nahrávka) by přes readAllBytes a dekódování shodil
     * hru OutOfMemoryError - a to "chyba zvuku hru nepoloží" nedovoluje.
     */
    static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    private SoundLibrary() {}

    /** Nejvíc očíslovaných variant jednoho zvuku: <jméno>_1.wav až _8.wav. */
    static final int MAX_VARIANTS = 8;

    /** První varianta zvuku - viz variants(). */
    public static Wav.Pcm load(Sound sound, Path directory)
    {
        return variants(sound, directory).get(0);
    }

    /**
     * Všechny varianty zvuku, nikdy prázdné: soubory <jméno>.wav a
     * <jméno>_1.wav až _8.wav, které jdou přečíst, v tomhle pořadí.
     * Když nejde ani jeden, syntetizované placeholdery.
     *
     * Vadný soubor se jen přeskočí (a ohlásí) - ostatní varianty hrají dál.
     */
    public static List<Wav.Pcm> variants(Sound sound, Path directory)
    {
        List<Wav.Pcm> found = new ArrayList<>();
        addIfReadable(found, directory.resolve(sound.fileName() + ".wav"));

        for(int i = 1; i <= MAX_VARIANTS; i++)
        {
            addIfReadable(found, directory.resolve(sound.fileName() + "_" + i + ".wav"));
        }

        if(found.isEmpty())
        {
            // Přes WAV a zpět, ať obě cesty končí týmž dekodérem.
            for(int v = 0; v < SoundSynth.variants(sound); v++)
            {
                found.add(Wav.decode(Wav.encode(SoundSynth.synthesize(sound, v))));
            }
        }

        return found;
    }

    private static void addIfReadable(List<Wav.Pcm> into, Path file)
    {
        if(!Files.isRegularFile(file))
        {
            return;
        }

        try
        {
            long size = Files.size(file);

            if(size > MAX_FILE_BYTES)
            {
                System.err.println("Zvuk " + file + " ma " + size / (1024 * 1024)
                        + " MB, vic nez " + MAX_FILE_BYTES / (1024 * 1024) + " MB - preskocen");
                return;
            }

            Wav.Pcm recorded = Wav.decode(Files.readAllBytes(file));

            if(recorded != null)
            {
                into.add(recorded);
                return;
            }

            System.err.println("Zvuk " + file + ": nepodporovany format WAV"
                    + " (jen PCM 8/16 bit, mono/stereo) - preskocen");
        }
        catch(IOException | RuntimeException e)
        {
            // RuntimeException taky: vadný soubor nesmí propadnout až do
            // SoundEngine.open() a vypnout všechny zvuky (a hláška
            // "Zvuk vypnuty: null" neřekla, který soubor za to může).
            System.err.println("Zvuk " + file + " nejde precist: " + e + " - preskocen");
        }
    }
}
