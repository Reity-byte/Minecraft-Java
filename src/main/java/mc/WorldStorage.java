package mc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Uložení a načtení světa.
 *
 * ---------------------------------------------------------------------------
 * NEUKLÁDÁ SE SVĚT, ALE ROZDÍL PROTI GENERÁTORU.
 *
 * Generátor je čistá funkce souřadnic a seedu světa, takže se terén dá
 * kdykoliv dopočítat znovu. Na disk stačí to, co hráč změnil - tedy pár
 * set záznamů místo desítek megabajtů bloků. Načtení je pak "vygeneruj
 * a přepiš změny".
 *
 * ⚠️ Cena za to je GENERATOR_VERSION. Když se změní ladění generátoru
 * (výšky terénu, prahy jeskyní, četnost rud), starý uložený svět bude mít
 * pod hráčovými stavbami jiný terén. Verze se proto ukládá do hlavičky a při
 * neshodě se hlásí varování. Svět se i tak načte - přijít o postavené věci
 * je horší než posunutý terén.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL ani na GLFW, takže jde celé otestovat headless (viz SaveTest).
 */
public final class WorldStorage {

    /**
     * První čtyři bajty souboru, aby se poznal cizí nebo poškozený soubor.
     *
     * ⚠️ MAGIC nese i verzi FORMÁTU, ne jen značku. "MCW1" je starší soubor
     * bez inventáře; "MCW2" ho už má; "MCW3" k němu přidává denní dobu.
     * Číst se umí všechny - jinak by přidání inventáře (a teď času) smazalo
     * každému rozehraný svět.
     *
     * ⚠️ NOVÁ POLOŽKA SE PŘIDÁVÁ NA KONEC a čte se jen u novějšího MAGIC.
     * Formát je poziční binárka bez délek, takže vložit pole doprostřed by
     * znamenalo, že starší soubor od toho místa čte úplně jiná čísla - a to
     * by se neprojevilo výjimkou, ale nesmyslnou polohou hráče.
     */
    private static final int MAGIC_V1 = 0x4D435731;
    private static final int MAGIC_V2 = 0x4D435732;
    private static final int MAGIC_V3 = 0x4D435733;

    /**
     * Zvýšit při každé změně generátoru, která posune terén.
     * Historie: 1 = holý terén, 2 = jeskyně a rudy, 3 = voda, 4 = stromy,
     * 5 = biomy. (Pochodeň a plot terén neposouvají, takže verzi nezvyšují.)
     *
     * ⚠️ CO TO PRO STARÝ SVĚT ZNAMENÁ, PŘESNĚ. Neukládá se svět, ale rozdíl
     * proti generátoru, takže se terén při načtení dopočítá ZNOVU - a to
     * novým generátorem. Ve starém světě tedy po biomech vypadá krajina jinak
     * i tam, kde už hráč byl; co zůstane beze změny, jsou jeho vlastní změny
     * bloků (stavby, vykopané díry) a inventář, protože ty jsou v souboru.
     * Je to ta samá cena, jakou stálo zavedení jeskyní, vody i stromů; verze
     * v hlavičce je tu proto, aby se o tom aspoň napsalo. SaveTest to ověřuje
     * čtením souboru zapsaného ve verzi 4.
     */
    public static final int GENERATOR_VERSION = 5;

    /**
     * Co všechno se ukládá. changes je mapa ze World.changes().
     *
     * dayTime je poloha v denním cyklu v sekundách (viz DayCycle). Soubory
     * z verze 1 a 2 ji nemají a dostanou DayCycle.START_TIME, takže se
     * otevřou dopoledne jako nový svět.
     *
     * inventory je libovolně dlouhé pole slotů; Main do něj za 36 slotů
     * inventáře ukládá i obě crafting mřížky (Main.inventorySnapshot()).
     */
    public record Save(float x, float y, float z,
                       float yaw, float pitch,
                       boolean flying,
                       int selectedSlot,
                       Map<Long, Map<Integer, Byte>> changes,
                       ItemStack[] inventory,
                       float dayTime) {}

    /** Kam se hráč postaví, když uložená poloha nedává smysl (NaN, nekonečno, mimo svět). */
    static final float FALLBACK_XZ = 8.5f;

    /** Poloha y dál než tohle od nuly je nesmysl - svět má 128 bloků. */
    static final float MAX_SANE_Y = 4096f;

    private WorldStorage() {}

    public static boolean exists(Path path)
    {
        return Files.isRegularFile(path);
    }

    /**
     * Zapíše svět. Vrací false, když se to nepovedlo - hra kvůli neúspěšnému
     * uložení nemá padat, jen o tom musí být vidět zpráva.
     *
     * ⚠️ NEPÍŠE SE PŘÍMO DO world.dat. Svět se nejdřív celý zakóduje do paměti
     * a na disk jde přes SafeFiles (.tmp, force, přejmenování). Dřív se cíl
     * otevřel s TRUNCATE_EXISTING, takže plný disk nebo zabití procesu při
     * ukládání v okamžiku zavření okna nechalo useknutý soubor - a svět, který
     * nejde načíst, playWorld() záměrně nehraje. Byl by pryč celý, ne jen
     * poslední session. world.dat je jediná část světa, kterou nejde dopočítat.
     * Soubor, který nejde načíst, se navíc před přepsáním zazálohuje do .bak.
     */
    public static boolean save(Path path, Save save)
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try
        {
            try(DataOutputStream out = new DataOutputStream(new BufferedOutputStream(bytes)))
            {
                out.writeInt(MAGIC_V3);
                out.writeInt(GENERATOR_VERSION);

                out.writeFloat(save.x());
                out.writeFloat(save.y());
                out.writeFloat(save.z());
                out.writeFloat(save.yaw());
                out.writeFloat(save.pitch());
                out.writeBoolean(save.flying());
                out.writeInt(save.selectedSlot());

                out.writeInt(save.changes().size());

                for(Map.Entry<Long, Map<Integer, Byte>> column : save.changes().entrySet())
                {
                    out.writeLong(column.getKey());
                    out.writeInt(column.getValue().size());

                    for(Map.Entry<Integer, Byte> block : column.getValue().entrySet())
                    {
                        out.writeInt(block.getKey());
                        out.writeByte(block.getValue());
                    }
                }

                out.writeInt(save.inventory().length);

                for(ItemStack stack : save.inventory())
                {
                    // null je platny "prazdny slot" - volajici nemusi pole predvyplnovat.
                    ItemStack safe = stack == null ? ItemStack.EMPTY : stack;
                    out.writeByte(safe.block());
                    out.writeInt(safe.count());
                }

                // Denní doba, až úplně na konci - viz poznámka u MAGIC.
                out.writeFloat(save.dayTime());
            }
        }
        catch(IOException e)
        {
            // Do paměti se zapsat nepovede jen výjimečně, ale DataOutputStream to deklaruje.
            System.err.println("Ulozeni sveta selhalo: " + e);
            return false;
        }

        return SafeFiles.writeAtomically(path, bytes.toByteArray(), WorldStorage::readsCompletely, "Svet");
    }

    /** Jde dosavadní soubor načíst? Tiše - na stderr píše jen skutečné načítání. */
    private static boolean readsCompletely(Path path)
    {
        return read(path, message -> { }) != null;
    }

    /**
     * Načte svět. Vrací null, když soubor neexistuje, je poškozený nebo to
     * není náš formát - důvod napíše na stderr a volající svět nehraje.
     */
    public static Save load(Path path)
    {
        return read(path, System.err::println);
    }

    /** Vlastní čtení; zprávy jdou do report (stderr, nebo nikam u tiché kontroly). */
    static Save read(Path path, Consumer<String> report)
    {
        try(DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path))))
        {
            int magic = in.readInt();

            if(magic != MAGIC_V1 && magic != MAGIC_V2 && magic != MAGIC_V3)
            {
                // "MCW" + jiná číslice je NAŠE značka, jen z novější verze hry -
                // říct to, ať si hráč nemyslí, že je soubor poškozený.
                report.accept((magic & 0xFFFFFF00) == (MAGIC_V1 & 0xFFFFFF00)
                        ? "Ulozeny svet je z novejsi verze hry (format " + (char) (magic & 0xFF)
                                + "), tahle umi jen do 3: " + path
                        : "Ulozeny svet ma cizi format: " + path);
                return null;
            }

            int version = in.readInt();
            if(version != GENERATOR_VERSION)
            {
                // Nenacist by znamenalo prijit o vsechno postavene. Terén se
                // posune, stavby zůstanou - to je z těch dvou možností lepší.
                report.accept("Ulozeny svet je z generatoru verze " + version
                        + ", ted je " + GENERATOR_VERSION
                        + " - teren pod stavbami muze byt jiny.");
            }

            float x = in.readFloat();
            float y = in.readFloat();
            float z = in.readFloat();
            float yaw = in.readFloat();
            float pitch = in.readFloat();

            // ⚠️ Hodnoty ze souboru se ořezávají, stejně jako denní doba
            // (DayCycle.setTime): NaN v poloze nebo v kameře by dal černý
            // obraz bez jediné hlášky a první uložení by ho zapsalo zpátky.
            // Svět se kvůli tomu NEZAHAZUJE - hráč jen přistane jinde.
            if(!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                    || Math.abs(y) > MAX_SANE_Y)
            {
                report.accept("Ulozeny svet ma nesmyslnou polohu hrace (" + x + ", " + y + ", " + z
                        + ") - hrac zacne nad " + FALLBACK_XZ + ", " + FALLBACK_XZ);
                x = FALLBACK_XZ;
                z = FALLBACK_XZ;
                y = World.WORLD_HEIGHT;
            }

            if(!Float.isFinite(yaw))
            {
                yaw = 0f;
            }

            pitch = Float.isFinite(pitch) ? Math.max(-Camera.MAX_PITCH, Math.min(Camera.MAX_PITCH, pitch)) : 0f;

            boolean flying = in.readBoolean();
            int selectedSlot = in.readInt();

            int columnCount = in.readInt();
            if(columnCount < 0)
            {
                report.accept("Ulozeny svet je poskozeny: zaporny pocet sloupcu");
                return null;
            }

            Map<Long, Map<Integer, Byte>> changes = new HashMap<>();

            for(int c = 0; c < columnCount; c++)
            {
                long key = in.readLong();
                int blockCount = in.readInt();

                if(blockCount < 0)
                {
                    report.accept("Ulozeny svet je poskozeny: zaporny pocet bloku");
                    return null;
                }

                Map<Integer, Byte> column = new HashMap<>();

                for(int b = 0; b < blockCount; b++)
                {
                    column.put(in.readInt(), in.readByte());
                }

                changes.put(key, column);
            }

            ItemStack[] inventory = new ItemStack[0];

            // Starší soubor inventář nemá - načte se prázdný a hráč začne s ničím.
            if(magic == MAGIC_V2 || magic == MAGIC_V3)
            {
                int slots = in.readInt();

                if(slots < 0 || slots > 1024)
                {
                    report.accept("Ulozeny svet je poskozeny: divny pocet slotu");
                    return null;
                }

                inventory = new ItemStack[slots];
                int clamped = 0;

                for(int i = 0; i < slots; i++)
                {
                    byte block = in.readByte();
                    int count = in.readInt();

                    // Hromádka přes MAX_COUNT by rozbila slévání (záporné
                    // místo ve slotu) - ořízne se a ohlásí, soubor se nezahodí.
                    if(count > ItemStack.MAX_COUNT)
                    {
                        count = ItemStack.MAX_COUNT;
                        clamped++;
                    }

                    inventory[i] = ItemStack.of(block, count);
                }

                if(clamped > 0)
                {
                    report.accept("Ulozeny svet ma " + clamped + " hromadek pres "
                            + ItemStack.MAX_COUNT + " kusu - oriznuty na " + ItemStack.MAX_COUNT);
                }
            }

            // Soubor bez denní doby (verze 1 a 2) se otevře dopoledne jako
            // nový svět - to je přesně to, co dělal dosud, protože čas se
            // neukládal vůbec.
            float dayTime = DayCycle.START_TIME;

            if(magic == MAGIC_V3)
            {
                dayTime = in.readFloat();
            }

            Save save = new Save(x, y, z, yaw, pitch, flying, selectedSlot,
                    changes, inventory, dayTime);

            // Stejná opatrnost jako u GENERATOR_VERSION: varovat, nepadat.
            // Svět s bloky z labu, které blocks.json nezná (soubor zmizel nebo
            // blok z něj někdo smazal), se načte a ty kostky se ukážou jako
            // "neznámý blok" - dokud se blocks.json nevrátí, pak zase správně.
            SortedSet<Integer> unknown = unknownLabBlocks(save, BlockRegistry.active());

            if(!unknown.isEmpty())
            {
                report.accept("Ulozeny svet obsahuje bloky z labu " + unknown + ", ktere "
                        + BlockRegistry.FILE + " nezna - ukazou se jako neznamy blok.");
            }

            return save;
        }
        catch(IOException e)
        {
            // Sem spadne i useknuty soubor - readInt na konci hodi EOFException.
            report.accept("Nacteni sveta selhalo: " + e);
            return null;
        }
    }

    /**
     * Id bloků z labu (od BlockRegistry.FIRST_ID), která uložený svět nese -
     * v postavených blocích i v inventáři.
     *
     * Formát souboru se kvůli nim NEMĚNÍ: id bloku z labu je byte jako
     * u vestavěných bloků a je stabilní napříč sezeními (BlockRegistry ho
     * nikdy nepřečísluje ani nepoužije podruhé), takže se ukládá a načítá
     * úplně stejně. Svět bez bloků z labu je bajt po bajtu tentýž jako dřív.
     */
    public static SortedSet<Integer> labBlockIds(Save save)
    {
        SortedSet<Integer> ids = new TreeSet<>();

        for(Map<Integer, Byte> column : save.changes().values())
        {
            for(byte block : column.values())
            {
                if(block >= BlockRegistry.FIRST_ID)
                {
                    ids.add((int) block);
                }
            }
        }

        for(ItemStack stack : save.inventory())
        {
            if(stack != null && stack.block() >= BlockRegistry.FIRST_ID)
            {
                ids.add((int) stack.block());
            }
        }

        return ids;
    }

    /** Ty z labBlockIds(), které registr nezná. */
    public static SortedSet<Integer> unknownLabBlocks(Save save, BlockRegistry registry)
    {
        SortedSet<Integer> unknown = new TreeSet<>();

        for(int id : labBlockIds(save))
        {
            if(registry.get((byte) id) == null)
            {
                unknown.add(id);
            }
        }

        return unknown;
    }
}
