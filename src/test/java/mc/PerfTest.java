package mc;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Výkonové invarianty, které jde hlídat bez GL a bez měření času.
 *
 * ---------------------------------------------------------------------------
 * PROČ TENHLE TEST JE. Všechny čtyři věci tady byly v ARCHITECTURE.md
 * popsané jako vyřešené a potichu přestaly platit, protože je nic nehlídalo:
 *
 *  - mapa sloupců: klíč `long` v HashMap&lt;Long, …&gt; měl hash `cx ^ cz`,
 *    koše se měnily na stromy a každý dotaz alokoval (cellAt ~62 ns, 76 B);
 *  - `update()` v klidu "bez jediné alokace" alokoval ~20 KB na frame;
 *  - ChunkMesh si po nahrání držel CPU kopii vrcholů (52 MB při dohledu 6);
 *  - ikony bloků kreslily draw call za kvádr a GlStats je nepočítal.
 *
 * Měří se alokace (ThreadMXBean), ne čas - alokace je na daném kódu
 * stejná na každém stroji, čas ne.
 * ---------------------------------------------------------------------------
 */
public class PerfTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        longMapMatchesHashMap();
        worldLookupsDoNotAllocate();
        meshKeepsOnlyExactData();
        everyDrawIsCounted();
        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ==================================================================
    // LongMap proti HashMap jako referenci
    // ==================================================================

    static void longMapMatchesHashMap() {
        System.out.println("\n-- LongMap: stejne chovani jako HashMap --");

        LongMap<Integer> map = new LongMap<>(4);
        Map<Long, Integer> reference = new HashMap<>();
        Random random = new Random(7);
        boolean same = true;
        String firstDifference = "";

        // Klíče jako skutečné sloupce: malé, i záporné souřadnice, hodně kolizí
        // cx ^ cz - přesně to, na čem HashMap selhávala.
        for (int step = 0; step < 200_000 && same; step++) {
            int cx = random.nextInt(41) - 20, cz = random.nextInt(41) - 20;
            long key = World.key(cx, cz);
            int op = random.nextInt(10);

            if (op < 5) {
                Integer a = map.put(key, step), b = reference.put(key, step);
                same = java.util.Objects.equals(a, b);
            } else if (op < 8) {
                Integer a = map.remove(key), b = reference.remove(key);
                same = java.util.Objects.equals(a, b);
            } else {
                same = java.util.Objects.equals(map.get(key), reference.get(key))
                        && map.containsKey(key) == reference.containsKey(key);
            }

            same &= map.size() == reference.size();
            if (!same) firstDifference = "krok " + step + " klic " + cx + "," + cz;
        }
        check("200 000 nahodnych put/remove/get sedi s HashMap (vc. mazani s posunem)", same, firstDifference);

        // Celý obsah, ne jen dotazy na klíče, které test zrovna trefil.
        long valueSum = 0, referenceSum = 0;
        for (int v : map.values()) valueSum += v;
        for (int v : reference.values()) referenceSum += v;
        check("values() vrati presne obsah", valueSum == referenceSum && map.values().size() == reference.size(),
                valueSum + " vs " + referenceSum);

        // removeIf: smaze presne vybrane, predikat na kazdou polozku jednou.
        int[] calls = {0};
        int before = map.size();
        map.removeIf((key, value) -> { calls[0]++; return value % 3 == 0; });
        reference.values().removeIf(value -> value % 3 == 0);
        boolean after = map.size() == reference.size();
        for (Map.Entry<Long, Integer> e : reference.entrySet()) after &= e.getValue().equals(map.get(e.getKey()));
        check("removeIf smaze presne vybrane", after, map.size() + " vs " + reference.size());
        check("a predikat zavola na kazdou polozku jednou", calls[0] == before, calls[0] + " vs " + before);

        map.clear();
        check("clear() mapu vyprazdni", map.isEmpty() && map.get(World.key(0, 0)) == null
                && !map.values().iterator().hasNext(), "");

        boolean rejected = false;
        try { map.put(1L, null); } catch (IllegalArgumentException e) { rejected = true; }
        check("null hodnota se odmitne (null = 'neni')", rejected, "");
    }

    // ==================================================================
    // World: dotazy na sloupce bez alokace
    // ==================================================================

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    static void worldLookupsDoNotAllocate() {
        System.out.println("\n-- World: dotazy na sloupce bez alokace --");

        World w = new World(4242L);
        w.loadRadius = 8;
        w.unloadRadius = 10;
        w.updateBlocking(8f, 8f);

        // Zahřát (JIT, první alokace polí), pak měřit.
        for (int i = 0; i < 3000; i++) w.update(8f, 8f);
        int calls = 2000;
        long start = allocated();
        for (int i = 0; i < calls; i++) w.update(8f, 8f);
        long perCall = (allocated() - start) / calls;
        // Dřív ~20 600 B (289 zabalených klíčů + stromové koše). Rezerva na
        // drobnosti mimo mapu sloupců, ne na návrat boxingu.
        check("update() v klidu alokuje pod 1 KB na volani", perCall < 1024, perCall + " B");

        Random random = new Random(3);
        long sum = 0;
        for (int i = 0; i < 200_000; i++) sum += w.cellAt(random.nextInt(200) - 100, random.nextInt(128), random.nextInt(200) - 100);
        start = allocated();
        int lookups = 1_000_000;
        for (int i = 0; i < lookups; i++) sum += w.cellAt((i * 7) % 200 - 100, i % 128, (i * 13) % 200 - 100);
        double perLookup = (allocated() - start) / (double) lookups;
        check("cellAt nealokuje (drive ~76 B na volani)", perLookup < 1, String.format("%.2f B", perLookup) + " " + (sum & 1));

        w.shutdown();
    }

    // ==================================================================
    // ChunkMesh: po build() jen přesně velká data
    // ==================================================================

    static void meshKeepsOnlyExactData() {
        System.out.println("\n-- ChunkMesh: CPU kopie jen presne velka --");

        World w = new World();
        w.updateBlocking(8f, 8f);
        ChunkColumn column = w.column(0, 0);

        List<ChunkMesh> meshes = new ArrayList<>();
        List<float[]> expected = new ArrayList<>();
        boolean exact = true;

        for (int s = 0; s < ChunkColumn.SECTIONS; s++) {
            Chunk section = column.section(s);
            if (section == null || section.isEmpty()) continue;
            ChunkMesh mesh = new ChunkMesh();
            mesh.build(w, section, 0, s << Chunk.BITS, 0);
            exact &= mesh.retainedFloats() == mesh.opaqueData().length + mesh.transparentData().length;
            meshes.add(mesh);
            expected.add(mesh.opaqueData());
        }
        check("mesh drzi jen tolik floatu, kolik postavil", exact, meshes.size() + " meshu");

        // Pracovní pole jsou sdílená: stavba dalších meshů nesmí přepsat data
        // meshe, který ještě čeká na upload().
        boolean intact = true;
        for (int i = 0; i < meshes.size(); i++) {
            intact &= java.util.Arrays.equals(meshes.get(i).opaqueData(), expected.get(i));
        }
        check("dalsi stavby neprepisuji data drivejsich meshu", intact, "");

        // Prázdná sekce: upload() bez GL skončí hned a nic nedrží.
        ChunkMesh empty = new ChunkMesh();
        empty.build(w, new Chunk(), 0, 7 << Chunk.BITS, 0);
        empty.upload();
        check("prazdny mesh po upload() nedrzi nic", empty.isEmpty() && empty.retainedFloats() == 0, "");

        w.shutdown();
    }

    // ==================================================================
    // GlStats: žádný glDrawArrays bez countDraw()
    // ==================================================================

    static void everyDrawIsCounted() throws Exception {
        System.out.println("\n-- GlStats: kazdy glDrawArrays se pocita --");

        Path sources = sourceRoot();
        check("zdrojaky hry jsou k nalezeni", sources != null, "hledano od " + codeSource());
        if (sources == null) return;

        List<String> uncounted = new ArrayList<>();
        int draws = 0;

        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (!line.startsWith("glDrawArrays(") && !line.startsWith("glDrawElements(")) continue;
                    draws++;
                    String next = i + 1 < lines.size() ? lines.get(i + 1).trim() : "";
                    if (!next.equals("GlStats.countDraw();")) {
                        uncounted.add(file.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }

        check("nasly se nejake draw cally (test opravdu cte zdrojaky)", draws > 10, draws + "");
        check("za kazdym glDrawArrays hned GlStats.countDraw()", uncounted.isEmpty(), uncounted.toString());
    }

    static Path codeSource() {
        try {
            return Path.of(PerfTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return Path.of(".");
        }
    }

    /** src/main/java/mc - od pracovního adresáře, nebo od target/test-classes. */
    static Path sourceRoot() {
        for (Path base : new Path[]{Path.of(""), codeSource().getParent() == null ? null : codeSource().getParent().getParent()}) {
            if (base == null) continue;
            Path candidate = base.resolve("src/main/java/mc");
            if (Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }
}
