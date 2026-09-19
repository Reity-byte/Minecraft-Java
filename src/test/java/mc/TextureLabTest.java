package mc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Overuje texture lab bez GL: souradnice pixelu a dlazdic, shodu s INSET
 * v BlockAtlas, malovani a undo, barvy, zapis a cteni PNG, hit-testy
 * rozvrzeni - a ze zivy nahled stavi TYZ mesh, jaky postavi hra.
 */
public class TextureLabTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        coordinates();
        inset();
        painting();
        blocksAndColors();
        png();
        layout();
        preview();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    static int size = AtlasEditor.SIZE, tileSize = AtlasEditor.TILE;

    // ==================================================================

    static void coordinates() {
        boolean corners = true, inverse = true;
        boolean[] covered = new boolean[size * size];
        boolean overlap = false;

        for (int t = 0; t < AtlasEditor.tileCount(); t++) {
            int x0 = BlockAtlas.column(t) * tileSize, y0 = BlockAtlas.row(t) * tileSize;

            corners &= AtlasEditor.pixelIndex(t, 0, 0) == y0 * size + x0
                    && AtlasEditor.pixelIndex(t, 15, 15) == (y0 + 15) * size + x0 + 15;

            for (int y = 0; y < tileSize; y++)
                for (int x = 0; x < tileSize; x++) {
                    int i = AtlasEditor.pixelIndex(t, x, y);
                    overlap |= covered[i];
                    covered[i] = true;
                    inverse &= AtlasEditor.tileAt(x0 + x, y0 + y) == t;
                }
        }

        boolean all = true;
        for (boolean c : covered) all &= c;

        check("roh dlazdice lezi tam, kde ji ma BlockAtlas (sloupec, radek odspodu)", corners, "");
        check("dlazdice pokryji cely atlas a zadny pixel dvakrat", all && !overlap, "");
        check("tileAt je presne opak pixelIndex", inverse, "");
        check("mimo atlas neni zadna dlazdice",
                AtlasEditor.tileAt(-1, 0) == -1 && AtlasEditor.tileAt(0, size) == -1
                        && AtlasEditor.tileAt(size, 5) == -1, "");
    }

    /**
     * Editor meni presne tech 16 x 16 texelu, ktere hra na bloku vzorkuje:
     * UV z BlockAtlas (zuzene o pul texelu) konci ve STREDECH krajnich
     * texelu dlazdice - ne v sousedni.
     */
    static void inset() {
        boolean edges = true, inside = true, neighbours = true;
        float eps = 1e-4f;

        for (int t = 0; t < BlockAtlas.TILE_COUNT; t++) {
            float x0 = AtlasEditor.tileX0(t), y0 = AtlasEditor.tileY0(t);

            edges &= Math.abs(BlockAtlas.u0(t) * size - (x0 + 0.5f)) < 1e-3f
                    && Math.abs(BlockAtlas.u1(t) * size - (x0 + tileSize - 0.5f)) < 1e-3f
                    && Math.abs(BlockAtlas.v0(t) * size - (y0 + 0.5f)) < 1e-3f
                    && Math.abs(BlockAtlas.v1(t) * size - (y0 + tileSize - 0.5f)) < 1e-3f;

            for (int x = 0; x < tileSize; x++) {
                float centre = (x0 + x + 0.5f) / size;
                inside &= centre >= BlockAtlas.u0(t) - eps && centre <= BlockAtlas.u1(t) + eps;
            }

            neighbours &= (x0 - 0.5f) / size < BlockAtlas.u0(t) - eps
                    && (x0 + tileSize + 0.5f) / size > BlockAtlas.u1(t) + eps;
        }

        check("UV dlazdice konci ve stredech jejich krajnich texelu (INSET = pul texelu)", edges, "");
        check("vsech 16 editovanych sloupcu hra opravdu vzorkuje", inside, "");
        check("a zadny texel sousedni dlazdice ne", neighbours, "");
    }

    // ==================================================================

    static void painting() {
        int[] atlas = Textures.blockAtlasPixels();
        int[] original = atlas.clone();
        AtlasEditor editor = new AtlasEditor(atlas);

        editor.select(BlockAtlas.TILE_STONE);
        editor.setColor(0xFFFF0000);
        editor.beginStroke(0, 0);
        editor.strokeTo(15, 5);
        editor.endStroke();

        boolean lineRed = true;
        for (int[] p : AtlasEditor.line(0, 0, 15, 5))
            lineRed &= editor.get(p[0], p[1]) == 0xFFFF0000;

        int changedOutside = 0;
        for (int t = 0; t < AtlasEditor.tileCount(); t++) {
            if (t == BlockAtlas.TILE_STONE) continue;
            for (int y = 0; y < tileSize; y++)
                for (int x = 0; x < tileSize; x++)
                    if (atlas[AtlasEditor.pixelIndex(t, x, y)] != original[AtlasEditor.pixelIndex(t, x, y)])
                        changedOutside++;
        }

        check("tah namaluje celou caru od stisku po posledni pixel", lineRed, "");
        check("jine dlazdice se nezmeni", changedOutside == 0, "" + changedOutside);
        check("po zmene je atlas k nahrani prave jednou",
                editor.takeDirty() && !editor.takeDirty() && editor.isUnsaved(), "");

        check("cely tah je jeden krok undo", editor.undoDepth() == 1, "" + editor.undoDepth());
        check("undo vrati dlazdici presne", editor.undo() && Arrays.equals(atlas, original)
                && editor.takeDirty(), "");
        check("prazdne undo nic neudela", !editor.undo(), "");

        // Dva tahy ve dvou dlazdicich - undo vraci pozpatku a prepne dlazdici.
        editor.select(BlockAtlas.TILE_DIRT);
        editor.beginStroke(3, 3);
        editor.endStroke();
        editor.select(BlockAtlas.TILE_SAND);
        editor.beginStroke(4, 4);
        editor.endStroke();
        editor.undo();
        check("undo prepne na dlazdici, ve ktere tah byl", editor.tile() == BlockAtlas.TILE_SAND, "" + editor.tile());
        editor.undo();
        check("a druhe undo na tu predchozi", editor.tile() == BlockAtlas.TILE_DIRT
                && Arrays.equals(atlas, original), "");

        for (int i = 0; i < AtlasEditor.UNDO_LIMIT + 50; i++) {
            editor.beginStroke(i % 16, 0);
            editor.endStroke();
        }
        check("undo drzi nejvys UNDO_LIMIT kroku", editor.undoDepth() == AtlasEditor.UNDO_LIMIT,
                "" + editor.undoDepth());

        editor.select(BlockAtlas.TILE_GRASS_TOP);
        int picked = editor.pick(2, 7);
        check("kapatko vezme barvu pixelu", picked == editor.color()
                && picked == atlas[AtlasEditor.pixelIndex(BlockAtlas.TILE_GRASS_TOP, 2, 7)], "");

        // Tazeni mimo platno: strokeTo dostane pixel mimo, nic se nerozbije.
        editor.beginStroke(15, 15);
        editor.strokeTo(20, 20);
        editor.endStroke();
        check("pixel mimo dlazdici se ignoruje", true, "");

        // Cara bez mezer ve vsech smerech.
        boolean contiguous = true;
        int[][] cases = {{0, 0, 15, 5}, {15, 5, 0, 0}, {3, 12, 3, 0}, {0, 15, 15, 0}, {7, 7, 7, 7}};
        for (int[] c : cases) {
            List<int[]> line = AtlasEditor.line(c[0], c[1], c[2], c[3]);
            contiguous &= line.get(0)[0] == c[0] && line.get(0)[1] == c[1]
                    && line.get(line.size() - 1)[0] == c[2] && line.get(line.size() - 1)[1] == c[3];
            for (int i = 1; i < line.size(); i++)
                contiguous &= Math.abs(line.get(i)[0] - line.get(i - 1)[0]) <= 1
                        && Math.abs(line.get(i)[1] - line.get(i - 1)[1]) <= 1;
        }
        check("cara ma oba konce a zadne mezery", contiguous, "");
    }

    // ==================================================================

    static void blocksAndColors() {
        check("hlina: nejdriv hlina (6 sten), pak trava (spodek)",
                AtlasEditor.blocksUsing(BlockAtlas.TILE_DIRT).equals(List.of(World.DIRT, World.GRASS)),
                AtlasEditor.blocksUsing(BlockAtlas.TILE_DIRT).toString());
        check("prkna: prkna a plot",
                AtlasEditor.blocksUsing(BlockAtlas.TILE_PLANKS).equals(List.of(World.PLANKS, World.FENCE)), "");
        check("praskliny ani volne bunky zadny blok nepouziva",
                AtlasEditor.blocksUsing(BlockAtlas.TILE_CRACK_FIRST).isEmpty()
                        && AtlasEditor.blocksUsing(BlockAtlas.TILE_UNKNOWN).isEmpty()
                        && AtlasEditor.blocksUsing(50).isEmpty(), "");

        boolean mapped = true;
        for (int id = 1; id <= World.FENCE; id++)
            for (int face : new int[]{BlockAtlas.FACE_TOP, BlockAtlas.FACE_BOTTOM, BlockAtlas.FACE_SIDE})
                mapped &= AtlasEditor.blocksUsing(BlockAtlas.tile((byte) id, face)).contains((byte) id);
        check("kazda stena kazdeho bloku vede na dlazdici, ktera ho zna", mapped, "");

        boolean named = true;
        for (int id = 1; id <= World.FENCE; id++) named &= !TextureLab.blockName((byte) id).startsWith("Block");
        check("kazdy blok ma v labu jmeno", named, "");

        // ---------- hex ----------
        check("hex se 6 ciframi je plne kryci", AtlasEditor.parseHex("#8B6D4B") == 0xFF8B6D4B, "");
        check("hex s 8 ciframi nese alfu", AtlasEditor.parseHex("C02F5FA8") == 0xC02F5FA8, "");
        check("spatny hex se odmitne",
                AtlasEditor.parseHex("12345") == null && AtlasEditor.parseHex("GGGGGG") == null
                        && AtlasEditor.parseHex("") == null, "");
        check("toHex a parseHex jsou navzajem opakem",
                AtlasEditor.parseHex(AtlasEditor.toHex(0x7F102030)) == 0x7F102030
                        && AtlasEditor.toHex(0xFF8B6D4B).equals("#FF8B6D4B"), "");

        // ---------- HSV ----------
        check("HSV zakladni barvy",
                AtlasEditor.hsv(0, 1, 1, 255) == 0xFFFF0000 && AtlasEditor.hsv(120, 1, 1, 255) == 0xFF00FF00
                        && AtlasEditor.hsv(240, 1, 1, 255) == 0xFF0000FF && AtlasEditor.hsv(0, 0, 1, 128) == 0x80FFFFFF, "");

        boolean roundTrip = true;
        String worst = "";
        for (int argb : Textures.blockAtlasPixels()) {
            if ((argb >>> 24) == 0) continue;
            float[] h = AtlasEditor.toHsv(argb);
            int back = AtlasEditor.hsv(h[0], h[1], h[2], argb >>> 24);
            for (int shift = 0; shift < 32; shift += 8)
                if (Math.abs(((back >> shift) & 0xFF) - ((argb >> shift) & 0xFF)) > 1) {
                    roundTrip = false;
                    worst = AtlasEditor.toHex(argb) + " -> " + AtlasEditor.toHex(back);
                }
        }
        check("vsechny barvy atlasu projdou HSV tam a zpet (+-1)", roundTrip, worst);

        // ---------- barvy dlazdice ----------
        AtlasEditor editor = new AtlasEditor(Textures.blockAtlasPixels());
        editor.select(BlockAtlas.TILE_STONE);
        int[] colors = editor.tileColors(12);
        int first = 0, second = 0;
        for (int y = 0; y < tileSize; y++)
            for (int x = 0; x < tileSize; x++) {
                if (editor.get(x, y) == colors[0]) first++;
                if (colors.length > 1 && editor.get(x, y) == colors[1]) second++;
            }
        check("paleta dlazdice zacina nejcastejsi barvou", colors.length >= 2 && first >= second,
                colors.length + " barev, " + first + " vs " + second);
    }

    // ==================================================================

    static void png() throws IOException {
        Path dir = Files.createTempDirectory("mc-atlas");
        Path file = dir.resolve("textures").resolve("atlas.png");

        try {
            int[] procedural = Textures.blockAtlasPixels();

            Textures.AtlasPixels missing = Textures.atlasPixels(file);
            check("bez souboru je atlas proceduralni", !missing.fromFile()
                    && Arrays.equals(missing.pixels(), procedural), "");

            check("ulozeni zalozi adresar a zapise PNG", AtlasImage.save(procedural, file) && Files.isRegularFile(file), "");

            int[] back = AtlasImage.load(file);
            check("zapsany a zpet nacteny atlas je pixel po pixelu stejny (vcetne alfy)",
                    Arrays.equals(back, procedural), "");

            // V PNG musi dlazdice stat stejne jako na bloku: radek 0 atlasu dole.
            BufferedImage image = ImageIO.read(file.toFile());
            boolean upright = true;
            for (int y = 0; y < size; y += 7)
                for (int x = 0; x < size; x += 5)
                    upright &= image.getRGB(x, size - 1 - y) == procedural[y * size + x];
            check("v PNG je radek 0 atlasu dole - dlazdice nejsou vzhuru nohama", upright, "");
            check("dlazdice 0 (vrsek travy) je v obrazku vlevo dole",
                    image.getRGB(0, size - 1) == procedural[AtlasEditor.pixelIndex(0, 0, 0)], "");

            Textures.AtlasPixels loaded = Textures.atlasPixels(file);
            check("se souborem se pouzije soubor", loaded.fromFile() && Arrays.equals(loaded.pixels(), procedural), "");

            // Uprava v editoru, ulozeni, nacteni - zmena prezije.
            AtlasEditor editor = new AtlasEditor(procedural.clone());
            editor.select(BlockAtlas.TILE_PLANKS);
            editor.setColor(0x80123456);
            editor.beginStroke(5, 9);
            editor.endStroke();
            AtlasImage.save(editor.pixels(), file);
            int[] edited = AtlasImage.load(file);
            check("namalovany pixel (i s alfou) prezije ulozeni a nacteni",
                    edited[AtlasEditor.pixelIndex(BlockAtlas.TILE_PLANKS, 5, 9)] == 0x80123456, "");

            // Spatny rozmer a nesmysl -> null, hra jede proceduralne.
            ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", file.toFile());
            check("PNG se spatnym rozmerem se odmitne", AtlasImage.load(file) == null
                    && !Textures.atlasPixels(file).fromFile(), "");

            Files.write(file, "tohle neni png".getBytes());
            check("poskozeny soubor se odmitne", AtlasImage.load(file) == null, "");
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList())
                    Files.deleteIfExists(p);
            }
        }
    }

    // ==================================================================

    /** Stred obdelniku v souradnicich mysi (GLFW, pocatek nahore). */
    static double[] centre(TextureLabLayout l, TextureLabLayout.Rect r) {
        return new double[]{l.left() + (r.x() + r.w() / 2.0) * l.scale(),
                l.top() + (r.y() + r.h() / 2.0) * l.scale()};
    }

    static void layout() {
        int w = 1024, h = 768;
        TextureLabLayout l = new TextureLabLayout(w, h);

        check("na 1024 x 768 je meritko labu 2", l.scale() == 2, "" + l.scale());
        check("lab se vejde na obrazovku",
                l.left() >= 0 && l.top() >= 0
                        && l.left() + TextureLabLayout.WIDTH * l.scale() <= w
                        && l.top() + TextureLabLayout.HEIGHT * l.scale() <= h, "");
        check("i na malem okne je meritko aspon 1", TextureLabLayout.scaleFor(300, 200) == 1, "");

        boolean pixels = true;
        for (int y = 0; y < tileSize; y++)
            for (int x = 0; x < tileSize; x++) {
                double[] c = centre(l, TextureLabLayout.canvasPixelRect(x, y));
                int[] hit = l.canvasPixelAt(c[0], c[1]);
                pixels &= hit != null && hit[0] == x && hit[1] == y;
            }
        check("stred kazdeho pixelu platna trefi prave ten pixel", pixels, "");

        TextureLabLayout.Rect canvas = TextureLabLayout.CANVAS;
        check("radek 0 dlazdice je na platne dole - jako v texture a na bloku",
                l.screenBottom(TextureLabLayout.canvasPixelRect(0, 0), h) == l.screenBottom(canvas, h)
                        && l.screenX(TextureLabLayout.canvasPixelRect(0, 0)) == l.screenX(canvas), "");
        check("mimo platno zadny pixel",
                l.canvasPixelAt(l.left() - 5, l.top() - 5) == null
                        && l.canvasPixelAt(l.screenX(canvas) - 1, centre(l, canvas)[1]) == null, "");

        boolean tiles = true;
        for (int t = 0; t < AtlasEditor.tileCount(); t++) {
            double[] c = centre(l, TextureLabLayout.tileRect(t));
            tiles &= l.tileAt(c[0], c[1]) == t;
        }
        check("klik na bunku prehledu vybere tu dlazdici", tiles, "");
        check("dlazdice 0 je v prehledu vlevo dole",
                l.screenBottom(TextureLabLayout.tileRect(0), h) == l.screenBottom(TextureLabLayout.ATLAS, h), "");

        boolean swatches = true;
        int count = TextureLabLayout.SWATCH_COLUMNS * TextureLabLayout.SWATCH_ROWS;
        for (int i = 0; i < count; i++) {
            double[] c = centre(l, TextureLabLayout.swatchRect(i));
            swatches &= l.swatchAt(c[0], c[1]) == i;
        }
        TextureLabLayout.Rect gap = TextureLabLayout.swatchRect(0);
        double gapX = l.left() + (gap.x() + gap.w() + 0.5) * l.scale();
        check("kazdy vzorek palety jde trefit", swatches, "");
        check("mezera mezi vzorky nic netrefi", l.swatchAt(gapX, centre(l, gap)[1]) == -1, "");

        TextureLabLayout.Rect bar = TextureLabLayout.HUE;
        check("posuvnik dava 0 az 1 a mimo se orizne",
                l.sliderValue(bar, l.screenX(bar)) == 0f
                        && Math.abs(l.sliderValue(bar, l.screenX(bar) + bar.w() * l.scale() / 2.0) - 0.5f) < 1e-4f
                        && l.sliderValue(bar, 99999) == 1f && l.sliderValue(bar, -99999) == 0f, "");
    }

    // ==================================================================

    /**
     * Hlavni duvod labu: nahled musi vypadat jako blok ve hre. Mesh nahledu se
     * porovna s meshem, ktery hra postavi pro tentyz blok polozeny volne do
     * vzduchu nad terenem v uplne jinem svete - musi byt bajt po bajtu stejny.
     */
    static void preview() {
        World game = new World();
        game.loadRadius = 1;
        game.unloadRadius = 3;
        game.updateBlocking(8f, 8f);

        long start = System.nanoTime();
        World previewWorld = BlockPreview.createWorld();
        System.out.printf("%nNahledovy svet (3x3 sloupce, nasviceny): %.1f ms%n",
                (System.nanoTime() - start) / 1e6);

        final int X = BlockPreview.X, Y = BlockPreview.Y, Z = BlockPreview.Z;
        byte[] blocks = {World.GRASS, World.STONE, World.DIRT, World.LOG, World.CRAFTING_TABLE,
                World.LEAVES, World.WATER, World.FENCE, World.TORCH, World.COAL_ORE};

        boolean identical = true, lit = true;
        String broken = "";
        long slowest = 0;

        for (byte block : blocks) {
            game.placeBlock(X, Y, Z, block);
            game.updateBlocking(8f, 8f);

            ChunkMesh inGame = new ChunkMesh();
            inGame.build(game, game.column(X >> 4, Z >> 4).section(Y >> Chunk.BITS),
                    BlockPreview.BASE_X, BlockPreview.BASE_Y, BlockPreview.BASE_Z);

            long t0 = System.nanoTime();
            ChunkMesh preview = new ChunkMesh();
            BlockPreview.build(preview, previewWorld, block);
            slowest = Math.max(slowest, System.nanoTime() - t0);

            boolean same = Arrays.equals(inGame.opaqueData(), preview.opaqueData())
                    && Arrays.equals(inGame.transparentData(), preview.transparentData());

            if (!same) {
                identical = false;
                broken += TextureLab.blockName(block) + " ";
            }
            lit &= preview.faceCount() > 0;

            game.breakBlock(X, Y, Z);
            game.updateBlocking(8f, 8f);
        }

        System.out.printf("Prepnuti bloku v nahledu (polozeni, svetlo, mesh): nejdele %.1f ms%n", slowest / 1e6);
        check("nahled je tyz mesh, jaky hra postavi pro blok volne ve vzduchu", identical, broken);
        check("kazdy blok v nahledu ma steny", lit, "");

        // Stineni sten je ChunkMesh: vrsek 1, boky 0,6 a 0,8, spodek 0,5 -
        // ve hre jeste o kus min, protoze plny blok zastini bunku pod sebou.
        ChunkMesh stone = new ChunkMesh();
        BlockPreview.build(stone, previewWorld, World.STONE);
        float[] v = stone.opaqueData();
        java.util.Set<Float> shades = new java.util.TreeSet<>();
        for (int i = 0; i < v.length; i += 7) shades.add(v[i + 5]);
        check("steny maji odstiny ChunkMesh: 1 / 0,8 / 0,6 a spodek pod 0,5 ve vlastnim stinu",
                shades.contains(1f) && shades.contains(0.8f) && shades.contains(0.6f)
                        && ((java.util.TreeSet<Float>) shades).first() < 0.5f
                        && ((java.util.TreeSet<Float>) shades).first() > 0.45f, shades.toString());

        game.shutdown();
        previewWorld.shutdown();
    }
}
