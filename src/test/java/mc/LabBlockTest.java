package mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.SortedSet;

/**
 * Overuje bloky z texture labu VE HRE: ze se jejich data (tvrdost, pevny,
 * nepruhledny, dlazdice) opravdu propisou do World, Mining, ChunkMesh,
 * LightEngine, Raycaster, zvuku, atlasu, nahledu v labu i do ulozeneho sveta.
 *
 * Soubor blocks.json sam (format, stabilita id, poskozeny soubor) hlida
 * BlockRegistryTest; tady se zkousi napojeni.
 *
 * ⚠️ Aktivni registr je staticky - test ho na konci VZDY vrati na prazdny,
 * jinak by ovlivnil testy, ktere po nem bezi ve stejne JVM.
 */
public class LabBlockTest {

    static int failures = 0;
    static final float DT = 1f / 60f;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    // Tri bloky z labu: kazdy jina kombinace vlastnosti a jine dlazdice po stenach.
    static final byte MARBLE, GLASS, GHOST;
    static final BlockRegistry REGISTRY;

    static {
        BlockRegistry r = BlockRegistry.empty();
        BlockDef marble = r.define("Marble", 1.0f, true, true, 63, 62, 61);
        r = r.with(marble);
        BlockDef glass = r.define("Glass", 0.2f, true, false, 60, 60, 60);
        r = r.with(glass);
        BlockDef ghost = r.define("Ghost", 0.5f, false, false, 59, 58, 57);
        r = r.with(ghost);
        REGISTRY = r;
        MARBLE = marble.id();
        GLASS = glass.id();
        GHOST = ghost.id();
    }

    public static void main(String[] args) throws IOException {
        try {
            BlockRegistry.activate(REGISTRY);

            properties();
            atlas();
            mining();
            mesh();
            light();
            preview();
            sound();
            save();
        } finally {
            BlockRegistry.activate(BlockRegistry.empty());
        }

        withoutRegistry();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    static World arena() {
        World w = new World();
        w.loadRadius = 1;
        w.unloadRadius = 3;
        w.updateBlocking(8f, 8f);
        return w;
    }

    // ==================================================================

    static void properties() {
        check("id z labu zacinaji na 64 a jdou po sobe",
                MARBLE == BlockRegistry.FIRST_ID && GLASS == MARBLE + 1 && GHOST == GLASS + 1, "");

        check("mramor: pevny a nepruhledny",
                World.blocksMovement(MARBLE) && World.isOpaque(MARBLE), "");
        check("sklo: pevne, ale propousti (nezakryva sousedy)",
                World.blocksMovement(GLASS) && !World.isOpaque(GLASS), "");
        check("duch: projde se skrz a nic nezakryva",
                !World.blocksMovement(GHOST) && !World.isOpaque(GHOST), "");
        check("vsechny bloky z labu jdou zamerit a rozbit (i duch)",
                World.isTargetable(MARBLE) && World.isTargetable(GLASS) && World.isTargetable(GHOST), "");
        check("tvar je vzdycky plna krychle",
                BlockModels.isFullCube(MARBLE) && BlockModels.isFullCube(GHOST), "");
        check("tvrdost je z dat, ve stejnych sekundach jako vestavene bloky",
                World.hardness(MARBLE) == 1.0f && World.hardness(GLASS) == 0.2f && World.hardness(GHOST) == 0.5f, "");

        byte unknown = (byte) 100;
        check("nezname id z rozsahu labu se chova jako driv: pevna nepruhledna kostka",
                World.isOpaque(unknown) && World.blocksMovement(unknown) && World.isTargetable(unknown)
                        && World.hardness(unknown) == 0.5f, "");

        check("vestavene bloky se nezmenily",
                World.isOpaque(World.STONE) && !World.isOpaque(World.WATER) && !World.isOpaque(World.TORCH)
                        && World.blocksMovement(World.FENCE) && !World.blocksMovement(World.TORCH)
                        && World.hardness(World.STONE) == 1.8f && World.hardness(World.DIRT) == 0.5f, "");

        World w = arena();
        final int Y = 100;
        w.placeBlock(8, Y, 8, GHOST);
        w.placeBlock(9, Y, 8, MARBLE);
        check("pevnost ve svete: skrz ducha projdes, o mramor se zarazis",
                !w.isSolid(8, Y, 8) && w.isSolid(9, Y, 8), "");

        Raycaster.RaycastHit hit = Raycaster.cast(w, 8.5f, Y + 3.5f, 8.5f, 0f, -1f, 0f, 8f);
        check("paprsek zameri i ducha (jinak by nesel vytezit)",
                hit != null && hit.x() == 8 && hit.y() == Y && hit.z() == 8, "" + hit);

        // Hrac postaveny do ducha propadne, na mramoru stoji.
        Player p = new Player();
        p.x = 8.5f; p.y = Y + 1.2f; p.z = 8.5f;
        for (int i = 0; i < 120; i++) p.update(w, DT, 0f);
        check("hrac propadne duchem az na zem pod nim", p.y < Y, String.format("y=%.2f", p.y));

        Player q = new Player();
        q.x = 9.5f; q.y = Y + 1.2f; q.z = 8.5f;
        for (int i = 0; i < 120; i++) q.update(w, DT, 0f);
        check("na mramoru hrac stoji", Math.abs(q.y - (Y + 1)) < 0.01f && q.onGround, String.format("y=%.2f", q.y));

        w.shutdown();
    }

    // ==================================================================

    static void atlas() {
        check("dlazdice mramoru po stenach: vrsek 63, bok 62, spodek 61",
                BlockAtlas.tile(MARBLE, BlockAtlas.FACE_TOP) == 63
                        && BlockAtlas.tile(MARBLE, BlockAtlas.FACE_SIDE) == 62
                        && BlockAtlas.tile(MARBLE, BlockAtlas.FACE_BOTTOM) == 61, "");
        check("nezname id z labu dostane kriklavou dlazdici",
                BlockAtlas.tile((byte) 100, BlockAtlas.FACE_TOP) == BlockAtlas.TILE_UNKNOWN, "");
        check("vestavene mapovani se nezmenilo",
                BlockAtlas.tile(World.GRASS, BlockAtlas.FACE_TOP) == BlockAtlas.TILE_GRASS_TOP
                        && BlockAtlas.tile(World.GRASS, BlockAtlas.FACE_BOTTOM) == BlockAtlas.TILE_DIRT, "");

        check("lab u dlazdice 62 ukaze mramor", AtlasEditor.blocksUsing(62).equals(java.util.List.of(MARBLE)), "");
        check("lab zna jmeno bloku", TextureLab.blockName(MARBLE).equals("Marble"), "");

        // Procedural atlas (bez atlas.png) nema dlazdice bloku z labu -
        // misto cerne diry je sachovnice "neznamy blok".
        int[] procedural = Textures.blockAtlasPixels();
        int[] marked = procedural.clone();
        Textures.markMissingTiles(marked, REGISTRY);
        int size = BlockAtlas.ATLAS_PIXELS;
        int unknownTexel = procedural[AtlasEditor.pixelIndex(BlockAtlas.TILE_UNKNOWN, 0, 0)];
        boolean filled = true, untouched = true;
        for (int tile = 0; tile < AtlasEditor.tileCount(); tile++) {
            boolean used = REGISTRY.usedTiles()[tile] && tile >= BlockAtlas.TILE_COUNT;
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++) {
                    int i = AtlasEditor.pixelIndex(tile, x, y);
                    if (used) filled &= marked[i] == procedural[AtlasEditor.pixelIndex(BlockAtlas.TILE_UNKNOWN, x, y)];
                    else untouched &= marked[i] == procedural[i];
                }
        }
        check("bez atlas.png maji dlazdice bloku z labu sachovnici neznameho bloku", filled && unknownTexel != 0, "");
        check("ostatni dlazdice zustanou, jak byly", untouched && marked.length == size * size, "");
    }

    // ==================================================================

    static void mining() {
        World w = arena();
        final int Y = 100;
        w.placeBlock(8, Y, 8, MARBLE);
        w.placeBlock(9, Y, 8, GLASS);
        w.placeBlock(10, Y, 8, World.STONE);

        Mining m = new Mining();
        int marble = MiningTest.framesToBreak(w, m, 8, Y, 8, 2000);
        m.cancel();
        int glass = MiningTest.framesToBreak(w, m, 9, Y, 8, 2000);
        m.cancel();
        int stone = MiningTest.framesToBreak(w, m, 10, Y, 8, 2000);
        m.cancel();

        System.out.printf("%nRozbiti: mramor (1 s) %d framu, sklo (0,2 s) %d, kamen (1,8 s) %d%n", marble, glass, stone);
        check("mramor s tvrdosti 1 s se rozbije za 1 s",
                Math.abs(marble * DT - 1.0f) < 0.05f, String.format("%.2f s", marble * DT));
        check("sklo (0,2 s) je rychlejsi nez mramor a mramor nez kamen (1,8 s)",
                glass > 0 && glass < marble && marble < stone, glass + " < " + marble + " < " + stone);

        // Vytezeny blok z labu jde do inventare jako tentyz blok - a jde znovu polozit.
        w.placeBlock(8, Y, 8, MARBLE);
        Inventory inv = new Inventory();
        DroppedItems drops = new DroppedItems();
        m.cancel();
        MiningTest.framesToBreak(w, m, 8, Y, 8, 2000);
        boolean harvested = m.harvest(w, inv, drops, SoundSink.SILENT);
        check("vytezeny mramor je v inventari",
                harvested && inv.get(0).block() == MARBLE && inv.get(0).count() == 1
                        && w.getBlock(8, Y, 8) == World.AIR, inv.get(0).toString());
        check("a jde znovu polozit", w.placeBlock(8, Y, 8, inv.get(0).block()) && w.getBlock(8, Y, 8) == MARBLE, "");

        // Lab da po zalozeni jednu hromadku na volny slot.
        Inventory fresh = new Inventory();
        fresh.set(0, ItemStack.of(World.STONE, 10));
        ItemStack rest = fresh.add(ItemStack.of(GLASS, TextureLab.CREATED_STACK));
        check("hromadka noveho bloku padne na prvni volny slot",
                rest.isEmpty() && fresh.get(1).block() == GLASS && fresh.get(1).count() == ItemStack.MAX_COUNT, "");

        w.shutdown();
    }

    // ==================================================================

    /**
     * Mesh bloku z labu: kazda stena musi brat UV ze SVE dlazdice (vrsek 63,
     * boky 62, spodek 61) - stejny princip jako AtlasTest, jen pres ChunkMesh.
     */
    static void mesh() {
        World w = arena();
        final int X = 8, Y = 104, Z = 8;
        w.placeBlock(X, Y, Z, MARBLE);
        w.updateBlocking(8f, 8f);

        ChunkMesh mesh = new ChunkMesh();
        int bx = (X >> 4) << 4, by = (Y >> 4) << 4, bz = (Z >> 4) << 4;
        mesh.build(w, w.column(X >> 4, Z >> 4).section(Y >> 4), bx, by, bz);
        float[] v = mesh.opaqueData();

        int[] faces = new int[3];
        boolean right = true;
        String wrong = "";
        for (int q = 0; q < v.length; q += 6 * 7) {
            float x0 = v[q], y0 = v[q + 1], z0 = v[q + 2];
            boolean sameX = true, sameY = true, sameZ = true;
            for (int k = 1; k < 6; k++) {
                sameX &= v[q + k * 7] == x0;
                sameY &= v[q + k * 7 + 1] == y0;
                sameZ &= v[q + k * 7 + 2] == z0;
            }
            int face = sameY ? (y0 > Y - by ? BlockAtlas.FACE_TOP : BlockAtlas.FACE_BOTTOM) : BlockAtlas.FACE_SIDE;
            int tile = BlockAtlas.tile(MARBLE, face);
            faces[face]++;
            for (int k = 0; k < 6; k++) {
                float u = v[q + k * 7 + 3], t = v[q + k * 7 + 4];
                boolean inside = u >= BlockAtlas.u0(tile) - 1e-6f && u <= BlockAtlas.u1(tile) + 1e-6f
                        && t >= BlockAtlas.v0(tile) - 1e-6f && t <= BlockAtlas.v1(tile) + 1e-6f;
                if (!inside) {
                    right = false;
                    wrong = "stena " + face + " uv " + u + "," + t;
                }
            }
            if (!(sameX || sameY || sameZ)) right = false;
        }
        check("mramor ve vzduchu ma 6 sten: vrsek, spodek a 4 boky",
                faces[BlockAtlas.FACE_TOP] == 1 && faces[BlockAtlas.FACE_BOTTOM] == 1 && faces[BlockAtlas.FACE_SIDE] == 4,
                Arrays.toString(faces));
        check("kazda stena mramoru bere UV ze sve dlazdice (63 / 62 / 61)", right, wrong);
        check("neprusvitne bloky z labu jdou do neprusvitneho pruchodu",
                mesh.transparentData().length == 0, "");

        // Culling: kamen vedle mramoru stenu k nemu zahodi, vedle skla ne.
        World c = arena();
        c.placeBlock(8, Y, 8, World.STONE);
        c.placeBlock(9, Y, 8, MARBLE);
        c.placeBlock(8, Y, 10, World.STONE);
        c.placeBlock(9, Y, 10, GLASS);
        c.updateBlocking(8f, 8f);
        ChunkMesh cm = new ChunkMesh();
        cm.build(c, c.column(0, 0).section(Y >> 4), bx, by, bz);
        // 4 bloky po 6 stenach = 24. Kamen a mramor si zakryji stenu navzajem
        // (-2). Sklo stenu kamene nezakryje, jen kamen zakryje stenu skla (-1).
        check("nepruhledny blok z labu zakryje stenu souseda, pruhledny ne",
                cm.faceCount() == 21, "" + cm.faceCount());

        w.shutdown();
        c.shutdown();
    }

    // ==================================================================

    static void light() {
        World w = arena();
        final int Y = 110;
        w.placeBlock(4, Y, 4, MARBLE);
        w.placeBlock(12, Y, 12, GLASS);
        w.updateBlocking(8f, 8f);

        int underMarble = w.skyLightAt(4, Y - 1, 4);
        int underGlass = w.skyLightAt(12, Y - 1, 12);
        check("nepruhledny blok z labu vrha stin (slunce pod nim < 15)", underMarble < 15, "" + underMarble);
        check("pruhledny blok z labu svetlo propusti (15)", underGlass == 15, "" + underGlass);
        w.shutdown();
    }

    // ==================================================================

    /** Nahled v labu jde celou cestou jako hra - i pro blok z labu. */
    static void preview() {
        World game = arena();
        World previewWorld = BlockPreview.createWorld();
        final int X = BlockPreview.X, Y = BlockPreview.Y, Z = BlockPreview.Z;

        boolean identical = true;
        for (byte block : new byte[]{MARBLE, GLASS, GHOST}) {
            game.placeBlock(X, Y, Z, block);
            game.updateBlocking(8f, 8f);
            ChunkMesh inGame = new ChunkMesh();
            inGame.build(game, game.column(X >> 4, Z >> 4).section(Y >> Chunk.BITS),
                    BlockPreview.BASE_X, BlockPreview.BASE_Y, BlockPreview.BASE_Z);

            ChunkMesh preview = new ChunkMesh();
            BlockPreview.build(preview, previewWorld, block);

            identical &= Arrays.equals(inGame.opaqueData(), preview.opaqueData())
                    && Arrays.equals(inGame.transparentData(), preview.transparentData())
                    && preview.faceCount() == 6;

            game.breakBlock(X, Y, Z);
            game.updateBlocking(8f, 8f);
        }
        check("nahled bloku z labu je tyz mesh, jaky postavi hra", identical, "");
        game.shutdown();
        previewWorld.shutdown();
    }

    // ==================================================================

    static void sound() {
        boolean consistent = true;
        String broken = "";
        for (int id = 1; id <= World.FENCE; id++) {
            byte b = (byte) id;
            if (b == World.WATER || b == World.TORCH || b == World.COAL_ORE || b == World.IRON_ORE) continue;
            if (Sound.Material.byHardness(World.hardness(b)) != Sound.Material.of(b)) {
                consistent = false;
                broken += TextureLab.blockName(b) + " ";
            }
        }
        check("material podle tvrdosti sedi s vestavenymi skupinami", consistent, broken);
        check("blok z labu zni podle sve tvrdosti (1 s drevo, 0,2 s rostlina, 0,5 s hlina)",
                Sound.Material.of(MARBLE) == Sound.Material.WOOD && Sound.Material.of(GLASS) == Sound.Material.PLANT
                        && Sound.Material.of(GHOST) == Sound.Material.EARTH, "");
        check("tvrdy blok z labu zni jako kamen", Sound.Material.byHardness(2.5f) == Sound.Material.STONE, "");
        check("rozbiti bloku z labu ma zvuk", Sound.breakOf(MARBLE) != null, "");
    }

    // ==================================================================

    /**
     * Ulozeny svet: bloky z labu se ukladaji jako kazdy jiny byte - format se
     * nemeni a svet bez nich je bajt po bajtu tentyz jako drive.
     */
    static void save() throws IOException {
        Path dir = Files.createTempDirectory("mc-labblock");
        try {
            World w = arena();
            final int Y = 100;
            w.placeBlock(8, Y, 8, MARBLE);
            w.placeBlock(9, Y, 8, World.STONE);

            ItemStack[] inv = new ItemStack[Inventory.SIZE];
            Arrays.fill(inv, ItemStack.EMPTY);
            inv[0] = ItemStack.of(GLASS, 64);
            inv[1] = ItemStack.of(World.DIRT, 5);

            Path file = dir.resolve("world.dat");
            check("svet s bloky z labu se ulozi",
                    WorldStorage.save(file, new WorldStorage.Save(8, Y + 1, 8, 0, 0, false, 0, w.changes(), inv,
                            DayCycle.START_TIME)), "");
            WorldStorage.Save back = WorldStorage.load(file);

            check("po nacteni je mramor porad mramor",
                    back != null && back.changes().values().stream()
                            .anyMatch(col -> col.containsValue(MARBLE)), "");
            check("po nacteni je sklo porad v inventari",
                    back != null && back.inventory()[0].block() == GLASS && back.inventory()[0].count() == 64, "");

            SortedSet<Integer> ids = WorldStorage.labBlockIds(back);
            check("svet nese bloky z labu 64 a 65", ids.equals(new java.util.TreeSet<>(java.util.List.of(64, 65))), "" + ids);
            check("registr je zna", WorldStorage.unknownLabBlocks(back, REGISTRY).isEmpty(), "");
            check("bez blocks.json by byly nezname (varovani, ne pad)",
                    WorldStorage.unknownLabBlocks(back, BlockRegistry.empty()).equals(ids), "");

            // Svet jen s vestavenymi bloky je stejny soubor, at je registr aktivni, nebo ne.
            World plain = arena();
            plain.placeBlock(8, Y, 8, World.PLANKS);
            ItemStack[] plainInv = new ItemStack[Inventory.SIZE];
            Arrays.fill(plainInv, ItemStack.EMPTY);
            plainInv[3] = ItemStack.of(World.STONE, 12);
            Map<Long, Map<Integer, Byte>> changes = plain.changes();
            Path a = dir.resolve("a.dat"), b = dir.resolve("b.dat");
            WorldStorage.save(a, new WorldStorage.Save(1, 2, 3, 4, 5, false, 2, changes, plainInv,
                    DayCycle.START_TIME));
            BlockRegistry.activate(BlockRegistry.empty());
            WorldStorage.save(b, new WorldStorage.Save(1, 2, 3, 4, 5, false, 2, changes, plainInv,
                    DayCycle.START_TIME));
            WorldStorage.Save plainBack = WorldStorage.load(b);
            BlockRegistry.activate(REGISTRY);
            check("svet bez bloku z labu: soubor je bajt po bajtu stejny s registrem i bez nej",
                    Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b)), "");
            check("a nese nula bloku z labu", plainBack != null && WorldStorage.labBlockIds(plainBack).isEmpty(), "");

            w.shutdown();
            plain.shutdown();

            // Cely koloběh jako ve hre: lab zalozi blok, ulozi, hra se "restartuje"
            // (nacte blocks.json znovu) a blok ma porad tytez vlastnosti a dlazdice.
            Path blocksFile = dir.resolve("textures").resolve("blocks.json");
            BlockDraft draft = new BlockDraft(BlockAtlas.TILE_STONE);
            draft.name = "Obsidian";
            draft.hardnessStep = Arrays.binarySearch(BlockDraft.HARDNESS_STEPS, 10f);
            draft.tiles[BlockAtlas.FACE_TOP] = BlockDraft.freeTile(REGISTRY, draft.tiles);
            check("navrh jde zalozit", draft.problem(REGISTRY) == null, "" + draft.problem(REGISTRY));
            BlockDef obsidian = draft.toDef(REGISTRY);
            check("zalozeny blok se ulozi", REGISTRY.with(obsidian).save(blocksFile), "");

            BlockRegistry restarted = BlockRegistry.load(blocksFile);
            BlockRegistry.activate(restarted);
            check("po restartu ma blok totez id, tvrdost a dlazdice",
                    restarted.get(obsidian.id()) != null
                            && World.hardness(obsidian.id()) == 10f
                            && BlockAtlas.tile(obsidian.id(), BlockAtlas.FACE_TOP) == draft.tiles[BlockAtlas.FACE_TOP]
                            && BlockAtlas.tile(obsidian.id(), BlockAtlas.FACE_SIDE) == BlockAtlas.TILE_STONE
                            && BlockAtlas.tile(MARBLE, BlockAtlas.FACE_TOP) == 63, "");
            BlockRegistry.activate(REGISTRY);
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted((x, y) -> y.getNameCount() - x.getNameCount()).toList())
                    Files.deleteIfExists(p);
            }
        }
    }

    // ==================================================================

    /** Bez blocks.json (prazdny registr) je vsechno jako drive. */
    static void withoutRegistry() {
        check("bez registru je id 64 nezname: kriklava dlazdice, pevna kostka",
                BlockAtlas.tile(MARBLE, BlockAtlas.FACE_TOP) == BlockAtlas.TILE_UNKNOWN
                        && World.isOpaque(MARBLE) && World.blocksMovement(MARBLE), "");
        check("bez registru je proceduralni atlas presne jako drive",
                Arrays.equals(Textures.atlasPixels(Path.of("neexistuje", "atlas.png")).pixels(),
                        Textures.blockAtlasPixels()), "");
    }
}
