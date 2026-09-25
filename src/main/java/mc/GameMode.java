package mc;

/**
 * Herní mód světa: survival nebo creative.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ MÓD JE VLASTNOST SVĚTA, NE HRÁČE. Vybírá se při zakládání světa, ukládá
 * se do saves/&lt;složka&gt;/world.json vedle seedu a za běhu se nemění. Přepínat
 * ho v Minecraftu umí jen příkaz /gamemode, a příkazová řádka tu není -
 * viz ARCHITECTURE.md, sekce "Creative mód".
 *
 * ⚠️ TŘÍDA JE TU PROTO, ABY "if (creative)" BYLO NA JEDNOM MÍSTĚ. Každá
 * otázka, kterou se hra na mód ptá, je tu jako pojmenovaná metoda. Kdyby se
 * místo toho po kódu rozsypaly testy "mode == CREATIVE", nešlo by najít,
 * co všechno creative vlastně mění - a hlavně by se dala snadno změnit
 * i survival větev. Takhle je survival chování doslova "to, co bylo".
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, takže jde celé otestovat headless (viz CreativeTest).
 */
public enum GameMode {

    SURVIVAL("Survival", "Mine, gather and craft to survive"),
    CREATIVE("Creative", "Unlimited blocks, instant mining, free flight");

    /** Jméno na obrazovce zakládání světa i v ladicím výpisu. UI je anglicky. */
    private final String label;

    /** Jednořádkový popis pod přepínačem, jako v Minecraftu. */
    private final String description;

    GameMode(String label, String description)
    {
        this.label = label;
        this.description = description;
    }

    public String label()       { return label; }
    public String description() { return description; }

    /** Druhý mód - přepínač na obrazovce zakládání světa jimi cykluje. */
    public GameMode next()
    {
        return this == SURVIVAL ? CREATIVE : SURVIVAL;
    }

    // ------------------------------------------------------------------
    // pravidla
    // ------------------------------------------------------------------

    /**
     * Rozbije se blok hned, bez ohledu na tvrdost?
     *
     * ⚠️ Zkracuje se jen ČAS. Jestli se na blok vůbec dá mířit a jestli se dá
     * rozbít, rozhoduje dál World.isTargetable() - creative ta pravidla
     * neobchází, jen v nich nečeká.
     */
    public boolean instantMining()
    {
        return this == CREATIVE;
    }

    /** Putuje vytěžený blok do inventáře (a přebytek na zem)? V creative mizí. */
    public boolean keepsMinedBlock()
    {
        return this == SURVIVAL;
    }

    /** Opotřebovávají se nástroje? V creative ne, jako v Minecraftu. */
    public boolean wearsTools()
    {
        return this == SURVIVAL;
    }

    /** Může hráč přepnout volný let? Ladicí klávesa F na mód nekouká - viz Main. */
    public boolean canFly()
    {
        return this == CREATIVE;
    }

    /**
     * Spotřebuje se po položení bloku kus z hotbaru?
     *
     * Volá Main hned po úspěšném World.placeBlock(). Je to metoda, a ne
     * podmínka v Main, aby se to dalo otestovat headless - pokládání samo
     * je uvnitř GL smyčky.
     */
    public void afterPlace(Container inventory, int slot)
    {
        if(this == SURVIVAL)
        {
            inventory.removeOne(slot);
        }
    }

    // ------------------------------------------------------------------
    // ukládání
    // ------------------------------------------------------------------

    /** Jak se mód píše do world.json. Malá písmena, aby soubor šel psát ručně. */
    public String id()
    {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Mód podle zápisu ve world.json. Cokoliv neznámého (i null) je survival:
     * světy založené před creativem klíč vůbec nemají a survival je to, čím
     * dosud byly. Jestli šlo o překlep, nebo o chybějící klíč, si volající
     * ověří přes known() - WorldSaves to tak odliší ve výpisu.
     */
    public static GameMode byId(String id)
    {
        for(GameMode mode : values())
        {
            if(mode.id().equalsIgnoreCase(id))
            {
                return mode;
            }
        }

        return SURVIVAL;
    }

    /** Je to zápis, který byId() opravdu zná? Překlep ve world.json se tím pozná. */
    public static boolean known(String id)
    {
        for(GameMode mode : values())
        {
            if(mode.id().equalsIgnoreCase(id))
            {
                return true;
            }
        }

        return false;
    }
}
