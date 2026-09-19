package mc;

/**
 * Svislý sloupec sekcí na jedné pozici (cx, cz) v mřížce chunků.
 *
 * Svět je nekonečný v X a Z, ale omezený ve výšce - proto se načítá a zahazuje
 * po sloupcích, ne po jednotlivých sekcích. Odpovídá to i tomu, jak funguje
 * generování: pro každé (x, z) se spočítá jedna výška a sloupec se zaplní dolů.
 */
public class ChunkColumn {

    public static final int SECTIONS = World.WORLD_HEIGHT / Chunk.SIZE;

    public final int cx, cz;

    /**
     * Sekce se alokují líně - dokud je celá sekce vzduch, zůstane tady null.
     * U nekonečného světa je to podstatná úspora: se zemí kolem y=64 je horní
     * polovina každého sloupce prázdná, takže se nealokuje ani bajt.
     */
    private final Chunk[] sections = new Chunk[SECTIONS];

    public ChunkColumn(int cx, int cz)
    {
        this.cx = cx;
        this.cz = cz;
    }

    /** Vrací null, když je sekce celá vzduch. */
    public Chunk section(int index)
    {
        return sections[index];
    }

    /** lx, lz jsou lokální (0-15), y je absolutní výška ve světě (0 - WORLD_HEIGHT). */
    public byte get(int lx, int y, int lz)
    {
        if(y < 0 || y >= World.WORLD_HEIGHT)
        {
            return World.AIR;
        }

        Chunk section = sections[y >> Chunk.BITS];
        if(section == null)
        {
            return World.AIR;
        }

        return section.get(lx, y & Chunk.MASK, lz);
    }

    // ------------------------------------------------------------------
    // světlo
    //
    // Sekce se alokují líně, takže se na světlo musí ptát přes sloupec:
    // ten ví, že neexistující sekce má výchozí hodnotu, a při zápisu si
    // sekci vytvoří.
    // ------------------------------------------------------------------

    /** Nad touhle sekcí je celý sloupec vzduch, takže do ní svítí slunce naplno. */
    private int fullySunlitAbove = SECTIONS;

    public int skyLight(int lx, int y, int lz)
    {
        if(y < 0 || y >= World.WORLD_HEIGHT)
        {
            // Nad světem je obloha, pod ním skála - nahoře plno, dole tma.
            return y >= World.WORLD_HEIGHT ? 15 : 0;
        }

        Chunk section = sections[y >> Chunk.BITS];
        return section == null ? (y >> Chunk.BITS) >= fullySunlitAbove ? 15 : 0
                : section.skyLight(lx, y & Chunk.MASK, lz);
    }

    public int blockLight(int lx, int y, int lz)
    {
        if(y < 0 || y >= World.WORLD_HEIGHT)
        {
            return 0;
        }

        Chunk section = sections[y >> Chunk.BITS];
        return section == null ? 0 : section.blockLight(lx, y & Chunk.MASK, lz);
    }

    public void setSkyLight(int lx, int y, int lz, int value)
    {
        section(y, true).setSkyLight(lx, y & Chunk.MASK, lz, value);
    }

    public void setBlockLight(int lx, int y, int lz, int value)
    {
        section(y, true).setBlockLight(lx, y & Chunk.MASK, lz, value);
    }

    /**
     * Sekce pro zápis světla; podle potřeby ji vytvoří.
     *
     * ⚠️ Prázdná sekce musí umět držet světlo, jinak by v ní zůstal stín
     * pod převisem neuložený a osvětlení by se po každém načtení lišilo.
     */
    private Chunk section(int y, boolean create)
    {
        int index = y >> Chunk.BITS;
        Chunk section = sections[index];

        if(section == null && create)
        {
            section = new Chunk();
            section.setDefaultLight(index >= fullySunlitAbove ? 15 : 0, 0);
            sections[index] = section;
        }

        return section;
    }

    /**
     * Řekne sloupci, od které sekce nahoru je jen vzduch. Prázdné sekce nad
     * touhle hranicí pak hlásí plné sluneční světlo, aniž by musely existovat.
     */
    public void setFullySunlitAbove(int sectionIndex)
    {
        fullySunlitAbove = sectionIndex;

        for(int i = 0; i < SECTIONS; i++)
        {
            if(sections[i] != null)
            {
                sections[i].setDefaultLight(i >= fullySunlitAbove ? 15 : 0, 0);
            }
        }
    }

    public void set(int lx, int y, int lz, byte id)
    {
        if(y < 0 || y >= World.WORLD_HEIGHT)
        {
            return;
        }

        // Nemá smysl alokovat 4 KB kvůli zapsání vzduchu do prázdné sekce.
        //
        // ⚠️ Novou sekci vytvářet jen přes section(y, true). Holé new Chunk()
        // má výchozí sluneční světlo 0, takže jeden blok položený do prázdné
        // sekce nad terénem zatemnil celých 16³. LightEngine.blockChanged pak
        // opraví jen okolí změněného bloku, zbytek sekce zůstal černý.
        Chunk section = section(y, id != World.AIR);

        if(section == null)
        {
            return;
        }

        section.set(lx, y & Chunk.MASK, lz, id);
    }
}
