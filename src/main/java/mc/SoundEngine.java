package mc;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.Random;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_LINEAR_DISTANCE_CLAMPED;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Přehrávání zvuků přes OpenAL.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ ŽIVOTNÍ CYKLUS JE STEJNÝ JAKO U WORLD. Main otevře engine při startu,
 * při založení nebo načtení světa ho zavře a otevře nový, a při ukončení hry
 * ho zavře. shutdown() uvolní zdroje, buffery, kontext i zařízení a dá se
 * volat opakovaně. Nový svět tak nezdědí nic, co ještě hraje ze starého.
 *
 * ⚠️ Chyba zvuku hru NEPOLOŽÍ. Bez zvukového zařízení (nebo bez nativní
 * knihovny) se engine otevře jako tichý: open() napíše důvod na stderr
 * a všechna play*() nic nedělají. Stejně jako u ukládání světa.
 *
 * Všechny zvuky se nahrají do bufferů hned při otevření - je jich pár desítek
 * kilobajtů, takže není co odkládat. Přehrává se z pevného fondu zdrojů;
 * když jsou všechny obsazené, nový zvuk se zahodí (proti "kulometu" chrání
 * hlavně cooldown, fond je jen pojistka).
 * ---------------------------------------------------------------------------
 */
public final class SoundEngine implements SoundSink {

    /** Celková hlasitost - jediný ovladač hlasitosti ve hře. */
    public static final float MASTER_VOLUME = 0.7f;

    /** Kolik zvuků může hrát najednou. */
    private static final int SOURCES = 16;

    /**
     * Útlum se vzdáleností: do 1 bloku plná hlasitost, pak lineárně k nule
     * na 16 blocích - jako v Minecraftu. Lineární model s ořezem je jediný,
     * který opravdu utichne; výchozí inverzní model jen slábne donekonečna.
     */
    private static final float REFERENCE_DISTANCE = 1f;
    private static final float MAX_DISTANCE = 16f;

    private long device = NULL;
    private long context = NULL;

    private final int[] buffers = new int[Sound.values().length];
    private final int[] sources = new int[SOURCES];

    private final SoundThrottle throttle = new SoundThrottle();
    private final Random random = new Random();

    private boolean open = false;

    /** Jak dlouho trvalo otevření (zařízení, kontext, všechny buffery), ms. */
    private float openMillis = 0f;

    private SoundEngine() {}

    /**
     * Otevře zvukové zařízení a nahraje zvuky. Nikdy nevyhodí výjimku -
     * když to nejde, vrátí tichý engine.
     */
    public static SoundEngine open(Path soundDirectory)
    {
        SoundEngine engine = new SoundEngine();
        long start = System.nanoTime();

        try
        {
            engine.init(soundDirectory);
        }
        catch(RuntimeException | LinkageError e)
        {
            System.err.println("Zvuk vypnuty: " + e.getMessage());
            engine.shutdown();
        }

        engine.openMillis = (System.nanoTime() - start) / 1e6f;
        return engine;
    }

    private void init(Path soundDirectory)
    {
        device = alcOpenDevice((ByteBuffer) null);

        if(device == NULL)
        {
            throw new IllegalStateException("zadne zvukove zarizeni");
        }

        ALCCapabilities deviceCaps = ALC.createCapabilities(device);
        context = alcCreateContext(device, (IntBuffer) null);

        if(context == NULL || !alcMakeContextCurrent(context))
        {
            throw new IllegalStateException("nejde vytvorit kontext OpenAL");
        }

        AL.createCapabilities(deviceCaps);

        alDistanceModel(AL_LINEAR_DISTANCE_CLAMPED);
        alListenerf(AL_GAIN, MASTER_VOLUME);

        for(Sound sound : Sound.values())
        {
            Wav.Pcm pcm = SoundLibrary.load(sound, soundDirectory);
            int buffer = alGenBuffers();
            alBufferData(buffer, AL_FORMAT_MONO16, pcm.samples(), pcm.sampleRate());
            buffers[sound.ordinal()] = buffer;
        }

        for(int i = 0; i < SOURCES; i++)
        {
            int source = alGenSources();
            alSourcef(source, AL_REFERENCE_DISTANCE, REFERENCE_DISTANCE);
            alSourcef(source, AL_MAX_DISTANCE, MAX_DISTANCE);
            alSourcef(source, AL_ROLLOFF_FACTOR, 1f);
            sources[i] = source;
        }

        int error = alGetError();

        if(error != AL_NO_ERROR)
        {
            throw new IllegalStateException("chyba OpenAL 0x" + Integer.toHexString(error));
        }

        open = true;
    }

    public boolean isOpen()      { return open; }
    public float openMillis()    { return openMillis; }

    // ------------------------------------------------------------------
    // přehrávání
    // ------------------------------------------------------------------

    /**
     * Posluchač = kamera. Poloha je ve světových souřadnicích jako float:
     * u zvuku na rozdíl od kreslení nevadí, že float má daleko od počátku
     * krok v centimetrech - ucho to nepozná. Proto tu není převod relativně
     * ke kameře, který potřebuje renderer.
     */
    public void listen(float x, float y, float z, float[] forward)
    {
        if(!open)
        {
            return;
        }

        alListener3f(AL_POSITION, x, y, z);
        alListenerfv(AL_ORIENTATION, new float[]{forward[0], forward[1], forward[2], 0f, 1f, 0f});
    }

    @Override
    public void play(Sound sound)
    {
        int source = claim(sound);

        if(source == 0)
        {
            return;
        }

        // Relativně k posluchači na nule = přímo "v hlavě", bez útlumu i směru.
        alSourcei(source, AL_SOURCE_RELATIVE, AL_TRUE);
        alSource3f(source, AL_POSITION, 0f, 0f, 0f);
        alSourcePlay(source);
    }

    @Override
    public void playAt(Sound sound, float x, float y, float z)
    {
        int source = claim(sound);

        if(source == 0)
        {
            return;
        }

        alSourcei(source, AL_SOURCE_RELATIVE, AL_FALSE);
        alSource3f(source, AL_POSITION, x, y, z);
        alSourcePlay(source);
    }

    /**
     * Připraví volný zdroj pro zvuk, nebo vrátí 0, když zvuk hrát nemá:
     * engine je tichý, blok nezní, druh je v cooldownu, nebo nic není volné.
     * Výška a hlasitost se nastaví tady - pro poziční i nepoziční stejně.
     */
    private int claim(Sound sound)
    {
        if(!open || sound == null)
        {
            return 0;
        }

        int source = freeSource();

        if(source == 0 || !throttle.allow(sound.kind, System.nanoTime() / 1e9))
        {
            return 0;
        }

        alSourcei(source, AL_BUFFER, buffers[sound.ordinal()]);
        alSourcef(source, AL_PITCH, sound.kind.pitch(random));
        alSourcef(source, AL_GAIN, sound.kind.gain);
        return source;
    }

    private int freeSource()
    {
        for(int source : sources)
        {
            if(alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING)
            {
                return source;
            }
        }

        return 0;
    }

    // ------------------------------------------------------------------

    /**
     * Uvolní všechno, co engine drží. Dá se volat opakovaně a i po
     * neúspěšném otevření - uklidí jen to, co opravdu vzniklo.
     */
    public void shutdown()
    {
        if(context != NULL)
        {
            alcMakeContextCurrent(context);

            for(int i = 0; i < SOURCES; i++)
            {
                if(sources[i] != 0)
                {
                    alSourceStop(sources[i]);
                    alDeleteSources(sources[i]);
                    sources[i] = 0;
                }
            }

            for(int i = 0; i < buffers.length; i++)
            {
                if(buffers[i] != 0)
                {
                    alDeleteBuffers(buffers[i]);
                    buffers[i] = 0;
                }
            }

            alcMakeContextCurrent(NULL);
            alcDestroyContext(context);
            context = NULL;

            // Schopnosti LWJGL patří ke zničenému kontextu - nesmí zůstat
            // "aktuální", jinak by omylem zavolaná funkce šla do prázdna.
            AL.setCurrentProcess(null);
        }

        if(device != NULL)
        {
            alcCloseDevice(device);
            device = NULL;
        }

        open = false;
    }
}
