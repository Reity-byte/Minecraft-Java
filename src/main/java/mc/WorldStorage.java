package mc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Uložení a načtení světa.
 *
 * ---------------------------------------------------------------------------
 * NEUKLÁDÁ SE SVĚT, ALE ROZDÍL PROTI GENERÁTORU.
 *
 * Generátor je čistá funkce souřadnic s pevným seedem, takže se terén dá
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
     * bez inventáře; "MCW2" ho už má. Číst se umí obojí - jinak by přidání
     * inventáře smazalo každému rozehraný svět.
     */
    private static final int MAGIC_V1 = 0x4D435731;
    private static final int MAGIC_V2 = 0x4D435732;

    /**
     * Zvýšit při každé změně generátoru, která posune terén.
     * Historie: 1 = holý terén, 2 = jeskyně a rudy, 3 = voda, 4 = stromy.
     * (Pochodeň a plot terén neposouvají, takže verzi nezvyšují.)
     */
    public static final int GENERATOR_VERSION = 4;

    /** Co všechno se ukládá. changes je mapa ze World.changes(). */
    public record Save(float x, float y, float z,
                       float yaw, float pitch,
                       boolean flying,
                       int selectedSlot,
                       Map<Long, Map<Integer, Byte>> changes,
                       ItemStack[] inventory) {}

    private WorldStorage() {}

    public static boolean exists(Path path)
    {
        return Files.isRegularFile(path);
    }

    /**
     * Zapíše svět. Vrací false, když se to nepovedlo - hra kvůli neúspěšnému
     * uložení nemá padat, jen o tom musí být vidět zpráva.
     */
    public static boolean save(Path path, Save save)
    {
        try
        {
            Path parent = path.getParent();
            if(parent != null)
            {
                Files.createDirectories(parent);
            }

            try(DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(path))))
            {
                out.writeInt(MAGIC_V2);
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
            }

            return true;
        }
        catch(IOException e)
        {
            System.err.println("Ulozeni sveta selhalo: " + e);
            return false;
        }
    }

    /**
     * Načte svět. Vrací null, když soubor neexistuje, je poškozený nebo to
     * není náš formát - volající si pak založí nový svět.
     */
    public static Save load(Path path)
    {
        try(DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path))))
        {
            int magic = in.readInt();

            if(magic != MAGIC_V1 && magic != MAGIC_V2)
            {
                System.err.println("Ulozeny svet ma cizi format: " + path);
                return null;
            }

            int version = in.readInt();
            if(version != GENERATOR_VERSION)
            {
                // Nenacist by znamenalo prijit o vsechno postavene. Terén se
                // posune, stavby zůstanou - to je z těch dvou možností lepší.
                System.err.println("Ulozeny svet je z generatoru verze " + version
                        + ", ted je " + GENERATOR_VERSION
                        + " - teren pod stavbami muze byt jiny.");
            }

            float x = in.readFloat();
            float y = in.readFloat();
            float z = in.readFloat();
            float yaw = in.readFloat();
            float pitch = in.readFloat();
            boolean flying = in.readBoolean();
            int selectedSlot = in.readInt();

            int columnCount = in.readInt();
            if(columnCount < 0)
            {
                System.err.println("Ulozeny svet je poskozeny: zaporny pocet sloupcu");
                return null;
            }

            Map<Long, Map<Integer, Byte>> changes = new HashMap<>();

            for(int c = 0; c < columnCount; c++)
            {
                long key = in.readLong();
                int blockCount = in.readInt();

                if(blockCount < 0)
                {
                    System.err.println("Ulozeny svet je poskozeny: zaporny pocet bloku");
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
            if(magic == MAGIC_V2)
            {
                int slots = in.readInt();

                if(slots < 0 || slots > 1024)
                {
                    System.err.println("Ulozeny svet je poskozeny: divny pocet slotu");
                    return null;
                }

                inventory = new ItemStack[slots];

                for(int i = 0; i < slots; i++)
                {
                    byte block = in.readByte();
                    int count = in.readInt();
                    inventory[i] = ItemStack.of(block, count);
                }
            }

            Save save = new Save(x, y, z, yaw, pitch, flying, selectedSlot, changes, inventory);

            // Stejná opatrnost jako u GENERATOR_VERSION: varovat, nepadat.
            // Svět s bloky z labu, které blocks.json nezná (soubor zmizel nebo
            // blok z něj někdo smazal), se načte a ty kostky se ukážou jako
            // "neznámý blok" - dokud se blocks.json nevrátí, pak zase správně.
            SortedSet<Integer> unknown = unknownLabBlocks(save, BlockRegistry.active());

            if(!unknown.isEmpty())
            {
                System.err.println("Ulozeny svet obsahuje bloky z labu " + unknown + ", ktere "
                        + BlockRegistry.FILE + " nezna - ukazou se jako neznamy blok.");
            }

            return save;
        }
        catch(IOException e)
        {
            // Sem spadne i useknuty soubor - readInt na konci hodi EOFException.
            System.err.println("Nacteni sveta selhalo: " + e);
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
