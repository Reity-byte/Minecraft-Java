package mc;

/**
 * Kubická sekce světa 16x16x16 - nejmenší jednotka, kterou se svět ukládá,
 * generuje a (od kroku A3) přepočítává do meshe.
 *
 * Proč 16x16x16 a ne celý svislý sloupec:
 *  - přepočet meshe po rozbití bloku projde 4096 buněk, ne 65 536
 *  - sekce nad terénem jsou celé prázdné a dají se přeskočit úplně
 *  - frustum culling má jemnější zrno
 */
public class Chunk {

    public static final int BITS   = 4;                    // log2(SIZE)
    public static final int SIZE   = 1 << BITS;            // 16
    public static final int MASK   = SIZE - 1;             // 15, pro rychlé "modulo 16"
    public static final int VOLUME = SIZE * SIZE * SIZE;   // 4096

    /**
     * Plochý byte[] místo byte[16][16][16].
     *
     * byte[][][] je v Javě 273 samostatných objektů (1 + 16 + 256) roztroušených
     * po haldě, dohromady ~9,5 KB na 4 KB dat, a na každé čtení jsou 3 dereference.
     * Tohle je jeden souvislý blok 4 KB s jednou dereferencí.
     *
     * Proč to vadí: mesher projde chunk 4096x a u každého bloku sáhne na 6 sousedů,
     * tedy ~24 000 čtení. Na souvislém poli jdou přes cache, na poli polí se skáče.
     */
    private final byte[] blocks = new byte[VOLUME];

    /**
     * Kolik bloků v sekci není AIR. Sekce s nulou se dá přeskočit celá -
     * bez iterace, bez meshe, bez draw callu. U nekonečného světa je to zásadní,
     * protože nad terénem je většina sekcí prázdná.
     */
    private int solidCount = 0;

    /**
     * Světlo, jeden bajt na blok: horní nibble sluneční, dolní blokové (0-15).
     *
     * Alokuje se LÍNĚ. Naprostá většina sekcí je buď celá osvícená sluncem
     * (nad terénem) nebo celá tmavá (hluboko pod ním), a obojí odpovídá
     * výchozí hodnotě - pole se pak vůbec nevytvoří a sekce zůstane na 4 KB
     * místo 8 KB. Alokuje se teprve tehdy, když do sekce dosáhne světlo,
     * které se od výchozího liší.
     */
    private byte[] light;

    /** Výchozí hodnota, dokud pole světla neexistuje. Nastavuje ji World. */
    private byte defaultLight = 0;

    /**
     * Pořadí y-z-x znamená, že sousední x leží v paměti vedle sebe, takže
     * smyčka s x nejvíc uvnitř čte sekvenčně.
     * Posuny místo násobení fungují jen proto, že SIZE je mocnina dvojky.
     */
    public static int index(int x, int y, int z)
    {
        return (y << (BITS * 2)) | (z << BITS) | x;
    }

    public byte get(int x, int y, int z)
    {
        return blocks[index(x, y, z)];
    }

    public void set(int x, int y, int z, byte id)
    {
        int i = index(x, y, z);
        byte old = blocks[i];

        if(old == id)
        {
            return;
        }

        // Počítadlo se posune jen při přechodu vzduch <-> pevný blok.
        // Záměna kamene za trávu s ním nehýbe.
        if(old == World.AIR)
        {
            solidCount++;
        }
        else if(id == World.AIR)
        {
            solidCount--;
        }

        blocks[i] = id;
    }

    public boolean isEmpty()
    {
        return solidCount == 0;
    }

    // ------------------------------------------------------------------
    // světlo
    // ------------------------------------------------------------------

    /**
     * Nastaví hodnotu, kterou má sekce, dokud se do ní nezasáhne.
     * Sekce nad terénem dostanou plné sluneční světlo, ostatní tmu.
     */
    public void setDefaultLight(int sky, int block)
    {
        defaultLight = pack(sky, block);

        if(light != null)
        {
            java.util.Arrays.fill(light, defaultLight);
        }
    }

    public int skyLight(int x, int y, int z)
    {
        return (raw(x, y, z) >> 4) & 0xF;
    }

    public int blockLight(int x, int y, int z)
    {
        return raw(x, y, z) & 0xF;
    }

    public void setSkyLight(int x, int y, int z, int value)
    {
        int i = index(x, y, z);
        ensureLight();
        light[i] = pack(value, light[i] & 0xF);
    }

    public void setBlockLight(int x, int y, int z, int value)
    {
        int i = index(x, y, z);
        ensureLight();
        light[i] = pack((light[i] >> 4) & 0xF, value);
    }

    private byte raw(int x, int y, int z)
    {
        return light == null ? defaultLight : light[index(x, y, z)];
    }

    private void ensureLight()
    {
        if(light == null)
        {
            light = new byte[VOLUME];
            java.util.Arrays.fill(light, defaultLight);
        }
    }

    private static byte pack(int sky, int block)
    {
        return (byte) (((sky & 0xF) << 4) | (block & 0xF));
    }
}
