package mc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Čtení a zápis souborů WAV (RIFF, nekomprimované PCM).
 *
 * ---------------------------------------------------------------------------
 * Syntetizované zvuky i skutečné nahrávky jdou STEJNOU CESTOU: vznikne pole
 * bajtů souboru WAV a to se dekóduje sem. Syntéza tedy nevyrábí vzorky přímo
 * pro OpenAL, ale "soubor v paměti" - a nahrávka z disku je jen jiný zdroj
 * týchž bajtů. Stejná myšlenka jako u textur, kde Texture bere pole pixelů
 * a je jí jedno, jestli je vygenerovala metoda, nebo přišla z PNG.
 *
 * ⚠️ Výsledek je vždycky MONO. OpenAL umisťuje do prostoru jen monofonní
 * buffery - stereo zahraje "do uší" bez ohledu na polohu zdroje. Stereo
 * nahrávka se proto při čtení smíchá do jednoho kanálu, jinak by rozbití
 * bloku nahrané ve stereu znělo vždycky zepředu.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na OpenAL.
 */
public final class Wav {

    /** Zvuk jako 16bitové mono vzorky a vzorkovací frekvence. */
    public record Pcm(short[] samples, int sampleRate) {

        public float seconds()
        {
            return samples.length / (float) sampleRate;
        }
    }

    private static final int FORMAT_PCM = 1;
    private static final int FORMAT_EXTENSIBLE = 0xFFFE;

    private Wav() {}

    /** Zapíše mono 16bitové PCM jako kompletní soubor WAV. */
    public static byte[] encode(Pcm pcm)
    {
        int dataBytes = pcm.samples().length * 2;
        ByteBuffer out = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);

        out.put(ascii("RIFF")).putInt(36 + dataBytes).put(ascii("WAVE"));

        out.put(ascii("fmt ")).putInt(16)
                .putShort((short) FORMAT_PCM)
                .putShort((short) 1)                 // kanály
                .putInt(pcm.sampleRate())
                .putInt(pcm.sampleRate() * 2)        // bajtů za sekundu
                .putShort((short) 2)                 // bajtů na vzorek přes všechny kanály
                .putShort((short) 16);               // bitů na vzorek

        out.put(ascii("data")).putInt(dataBytes);

        for(short sample : pcm.samples())
        {
            out.putShort(sample);
        }

        return out.array();
    }

    /**
     * Přečte soubor WAV. Umí 8 a 16 bitů, mono i stereo (stereo smíchá do
     * mona). Na cokoliv jiného - komprese, 24 bitů, useknutý soubor - vrátí
     * null; volající pak použije syntézu. Chybný soubor hru nepoloží.
     */
    public static Pcm decode(byte[] data)
    {
        if(data == null || data.length < 12)
        {
            return null;
        }

        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        if(!tag(in, 0, "RIFF") || !tag(in, 8, "WAVE"))
        {
            return null;
        }

        int format = -1, channels = 0, sampleRate = 0, bits = 0;
        int dataStart = -1, dataLength = 0;

        // Soubor je řada bloků "jméno + délka + obsah". Na pořadí se nedá
        // spoléhat a mezi nimi můžou být cizí bloky (LIST s metadaty apod.).
        int position = 12;

        while(position + 8 <= data.length)
        {
            int length = in.getInt(position + 4);

            // ⚠️ V long: délka kolem Integer.MAX_VALUE by v int přetekla do
            // záporu, kontrola by prošla a pozice by pak skočila za pole
            // (IndexOutOfBoundsException) - jeden vadný soubor tím dřív
            // vypnul celý zvuk místo jednoho placeholderu.
            if(length < 0 || (long) position + 8 + length > data.length)
            {
                // Useknutý blok dat se ještě dá přečíst, co v něm je.
                if(tag(in, position, "data"))
                {
                    length = data.length - position - 8;
                }
                else
                {
                    return null;
                }
            }

            if(tag(in, position, "fmt ") && length >= 16)
            {
                format = in.getShort(position + 8) & 0xFFFF;
                channels = in.getShort(position + 10) & 0xFFFF;
                sampleRate = in.getInt(position + 12);
                bits = in.getShort(position + 22) & 0xFFFF;
            }
            else if(tag(in, position, "data"))
            {
                dataStart = position + 8;
                dataLength = length;
            }

            // Bloky se zarovnávají na sudý počet bajtů. Délka je teď ověřená
            // proti velikosti pole, takže součet se do int vejde.
            position += 8 + length + (length & 1);
        }

        if((format != FORMAT_PCM && format != FORMAT_EXTENSIBLE)
                || (channels != 1 && channels != 2)
                || (bits != 8 && bits != 16)
                || sampleRate <= 0 || dataStart < 0)
        {
            return null;
        }

        int bytesPerFrame = channels * bits / 8;
        int frames = dataLength / bytesPerFrame;
        short[] samples = new short[frames];

        for(int i = 0; i < frames; i++)
        {
            int offset = dataStart + i * bytesPerFrame;
            int sum = 0;

            for(int channel = 0; channel < channels; channel++)
            {
                sum += bits == 16
                        ? in.getShort(offset + channel * 2)
                        // 8bitové WAV je BEZ znaménka se středem 128.
                        : ((data[offset + channel] & 0xFF) - 128) << 8;
            }

            samples[i] = (short) (sum / channels);
        }

        return new Pcm(samples, sampleRate);
    }

    private static boolean tag(ByteBuffer in, int position, String name)
    {
        if(position + 4 > in.limit())
        {
            return false;
        }

        for(int i = 0; i < 4; i++)
        {
            if(in.get(position + i) != name.charAt(i))
            {
                return false;
            }
        }

        return true;
    }

    private static byte[] ascii(String text)
    {
        return text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
