package mc;

/**
 * Overuje nekrychlove modely bloku.
 *
 * Dve pravidla, na kterych cely system stoji, a obe se daji tise porusit:
 *
 *  1) Stena kvadru se zahazuje JEN kdyz lezi presne na hranici bloku. Kdyby
 *     se zahazovala i vnitrni stena, pochoden postavena u zdi by z te strany
 *     zmizela.
 *
 *  2) Blok s nekrychlovym modelem NENI nepruhledny. Kdyby byl, zahodil by
 *     steny sousedu a za pochodni by byla dira do prazdna.
 *
 * K tomu se rozpadl jediny drivejsi test "neni vzduch" na tri nezavisle
 * vlastnosti - zakryva, zastavi hrace, da se zamerit - a kazda plati pro jine
 * bloky. Test je prochazi vsechny.
 */
public class ModelTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    /** Sekce vysoko nad terenem, at do testu nemluvi nic dalsiho. */
    static final int SECTION = 6;
    static final int BASE_Y = SECTION << Chunk.BITS;

    public static void main(String[] args) {
        // ---------- tri ruzne vlastnosti ----------
        check("kamen zakryva sousedy", World.isOpaque(World.STONE), "");
        check("voda nezakryva", !World.isOpaque(World.WATER), "");
        check("pochoden nezakryva (neni plna krychle)", !World.isOpaque(World.TORCH), "");
        check("plot nezakryva", !World.isOpaque(World.FENCE), "");
        check("listi zakryva (je plna krychle)", World.isOpaque(World.LEAVES), "");

        check("kamen zastavi hrace", World.blocksMovement(World.STONE), "");
        check("plot zastavi hrace", World.blocksMovement(World.FENCE), "");
        check("pochodni se projde", !World.blocksMovement(World.TORCH), "");
        check("vodou se projde", !World.blocksMovement(World.WATER), "");

        check("pochoden se da zamerit", World.isTargetable(World.TORCH), "");
        check("voda se zamerit neda", !World.isTargetable(World.WATER), "");
        check("vzduch se zamerit neda", !World.isTargetable(World.AIR), "");

        // ---------- tvary ----------
        check("kamen je plna krychle", BlockModels.isFullCube(World.STONE), "");
        check("pochoden plna krychle neni", !BlockModels.isFullCube(World.TORCH), "");
        check("plot plna krychle neni", !BlockModels.isFullCube(World.FENCE), "");

        BlockModels.BlockBox torch = BlockModels.of(World.TORCH)[0];
        check("pochoden je uzka a nesaha do stropu",
                torch.maxX() < 1f && torch.maxY() < 1f && torch.minY() == 0f,
                String.format("%.2f x %.2f", torch.maxX() - torch.minX(), torch.maxY()));

        BlockModels.BlockBox fence = BlockModels.of(World.FENCE)[0];
        check("plot je uzky sloupek pres celou vysku",
                fence.maxX() < 1f && fence.minY() == 0f && fence.maxY() == 1f, "");

        // ---------- pocty sten ----------
        World w = new World();
        w.loadRadius = 1;
        w.unloadRadius = 3;
        w.updateBlocking(8f, 8f);

        ChunkMesh mesh = new ChunkMesh();

        // Osamocena pochoden ve vzduchu: jeden kvadr, sest sten.
        w.placeBlock(8, BASE_Y + 4, 8, World.TORCH);
        mesh.build(w, w.column(0, 0).section(SECTION), 0, BASE_Y, 0);
        check("osamocena pochoden ma sest sten", mesh.faceCount() == 6, "" + mesh.faceCount());

        // ⚠️ Pochoden obalena kamenem musi mit porad sest sten krome spodni:
        // jen spodni stena lezi na hranici bloku, ostatni jsou uvnitr.
        w.placeBlock(9, BASE_Y + 4, 8, World.STONE);
        w.placeBlock(7, BASE_Y + 4, 8, World.STONE);
        w.placeBlock(8, BASE_Y + 4, 9, World.STONE);
        w.placeBlock(8, BASE_Y + 4, 7, World.STONE);
        w.placeBlock(8, BASE_Y + 5, 8, World.STONE);
        w.placeBlock(8, BASE_Y + 3, 8, World.STONE);

        mesh.build(w, w.column(0, 0).section(SECTION), 0, BASE_Y, 0);

        // 6 kamennych bloku po 5 vnejsich stenach = 30 (sesta miri na pochoden,
        // ale ta nezakryva, takze se kresli taky -> 6*6 = 36),
        // pochoden ma 5 sten (spodni je na hranici a pod ni je kamen).
        int torchFaces = 5;
        int stoneFaces = 6 * 6;
        check("pochoden u zdi neztrati boky (5 sten) a kamen se za ni nezahodi",
                mesh.faceCount() == torchFaces + stoneFaces,
                mesh.faceCount() + " misto " + (torchFaces + stoneFaces));

        // ---------- kamen za pochodni se porad kresli ----------
        // Kdyby pochoden byla nepruhledna, stena kamene smerem k ni by zmizela.
        World bare = new World();
        bare.loadRadius = 1;
        bare.unloadRadius = 3;
        bare.updateBlocking(8f, 8f);

        bare.placeBlock(8, BASE_Y + 4, 8, World.STONE);
        ChunkMesh alone = new ChunkMesh();
        alone.build(bare, bare.column(0, 0).section(SECTION), 0, BASE_Y, 0);
        int stoneAlone = alone.faceCount();

        bare.placeBlock(9, BASE_Y + 4, 8, World.TORCH);
        alone.build(bare, bare.column(0, 0).section(SECTION), 0, BASE_Y, 0);

        check("pochoden vedle kamene mu nezahodi stenu",
                alone.faceCount() == stoneAlone + 6,
                alone.faceCount() + " misto " + (stoneAlone + 6));

        // ---------- pochodni se projde, plot zastavi ----------
        World arena = new World();
        arena.loadRadius = 1;
        arena.unloadRadius = 3;
        arena.updateBlocking(8f, 8f);

        final int FLOOR = BASE_Y;
        for (int x = 4; x <= 12; x++)
            for (int z = 4; z <= 12; z++) arena.placeBlock(x, FLOOR, z, World.STONE);

        arena.placeBlock(8, FLOOR + 1, 8, World.TORCH);

        Player p = new Player();
        p.x = 8.5f; p.z = 8.5f; p.y = FLOOR + 1;
        p.inputForward = 0;
        p.update(arena, 1f / 60f, 0f);
        check("hrac stoji v pochodni a nezasekne se", Math.abs(p.y - (FLOOR + 1)) < 0.05f,
                "y=" + p.y);

        arena.breakBlock(8, FLOOR + 1, 8);
        arena.placeBlock(8, FLOOR + 1, 8, World.FENCE);
        check("plot zastavi", arena.isSolid(8, FLOOR + 1, 8), "");
        check("pochoden nezastavi", !World.blocksMovement(World.TORCH), "");

        // ---------- napojovani plotu ----------
        byte A = World.AIR;
        check("osamoceny plot je jen sloupek",
                BlockModels.of(World.FENCE, A, A, A, A).length == 1, "");
        check("plot k plotu: sloupek a dve pricky",
                BlockModels.of(World.FENCE, World.FENCE, A, A, A).length == 3, "");
        check("plot ze vsech stran: 1 + 4x2 kvadru = MAX_BOXES",
                BlockModels.of(World.FENCE, World.FENCE, World.FENCE, World.FENCE, World.FENCE).length == 9
                        && BlockModels.MAX_BOXES == 9, "" + BlockModels.MAX_BOXES);
        check("napoji se na zed (kamen, prkna), ne na listi, vodu, pochoden, vzduch",
                BlockModels.fenceConnects(World.STONE) && BlockModels.fenceConnects(World.PLANKS)
                        && !BlockModels.fenceConnects(World.LEAVES) && !BlockModels.fenceConnects(World.WATER)
                        && !BlockModels.fenceConnects(World.TORCH) && !BlockModels.fenceConnects(A), "");
        check("ostatni bloky na sousedech nezavisi",
                BlockModels.of(World.STONE, World.FENCE, A, A, A) == BlockModels.of(World.STONE)
                        && BlockModels.of(World.TORCH, World.STONE, A, A, A) == BlockModels.of(World.TORCH), "");
        check("v ruce, v inventari a na zemi je plot sloupek", BlockModels.of(World.FENCE).length == 1, "");

        // Pricka k +X vede od sloupku k hrane bloku, ve vysce 6-9 a 12-15 px.
        boolean eastBars = true;
        for (BlockModels.BlockBox b : BlockModels.of(World.FENCE, World.FENCE, A, A, A)) {
            if (b.maxY() == 1f) continue;   // sloupek
            eastBars &= b.maxX() == 1f && b.minX() == 10 / 16f && b.minZ() == 7 / 16f && b.maxZ() == 9 / 16f;
        }
        check("pricky miri ke spravnemu sousedovi a konci na hrane", eastBars, "");

        World fences = new World();
        fences.loadRadius = 1;
        fences.unloadRadius = 3;
        fences.updateBlocking(8f, 8f);
        ChunkMesh fm = new ChunkMesh();

        fences.placeBlock(8, BASE_Y + 8, 8, World.FENCE);
        fm.build(fences, fences.column(0, 0).section(SECTION), 0, BASE_Y, 0);
        check("osamoceny plot ve svete: sest sten", fm.faceCount() == 6, "" + fm.faceCount());

        fences.placeBlock(9, BASE_Y + 8, 8, World.FENCE);
        fm.build(fences, fences.column(0, 0).section(SECTION), 0, BASE_Y, 0);
        check("dva ploty vedle sebe: kazdy sloupek + 2 pricky (2 x 18 sten)",
                fm.faceCount() == 36, "" + fm.faceCount());

        fences.placeBlock(4, BASE_Y + 8, 4, World.FENCE);
        fences.placeBlock(3, BASE_Y + 8, 4, World.STONE);
        fm.build(fences, fences.column(0, 0).section(SECTION), 0, BASE_Y, 0);
        check("plot u zdi: konec pricky u kamene se zahodi (6 + 2x5), kamen cely (6)",
                fm.faceCount() == 36 + 16 + 6, "" + fm.faceCount());

        // Plot na hranici chunku se napoji i na souseda v jinem chunku.
        fences.placeBlock(15, BASE_Y + 8, 8, World.FENCE);
        fences.placeBlock(16, BASE_Y + 8, 8, World.FENCE);
        ChunkMesh left = new ChunkMesh(), right = new ChunkMesh();
        left.build(fences, fences.column(0, 0).section(SECTION), 0, BASE_Y, 0);
        right.build(fences, fences.column(1, 0).section(SECTION), 16, BASE_Y, 0);
        check("pres hranici chunku se ploty napoji z obou stran",
                left.faceCount() == 36 + 16 + 6 + 18 && right.faceCount() == 18,
                left.faceCount() + " / " + right.faceCount());
        fences.shutdown();

        // ---------- recepty ----------
        // Plot jako v Minecraftu: prkno, klacek, prkno ve dvou radach.
        Container fenceGrid = new Container(9);
        for (int row = 0; row < 2; row++) {
            fenceGrid.set(row * 3, ItemStack.of(World.PLANKS, 1));
            fenceGrid.set(row * 3 + 1, ItemStack.of(ItemRegistry.STICK, 1));
            fenceGrid.set(row * 3 + 2, ItemStack.of(World.PLANKS, 1));
        }
        ItemStack fenceResult = Recipes.match(fenceGrid, 3, 3);
        check("prkna a klacky daji tri ploty",
                fenceResult.block() == World.FENCE && fenceResult.count() == 3,
                fenceResult.toString());

        Container row = new Container(9);
        for (int i = 0; i < 3; i++) row.set(i, ItemStack.of(World.PLANKS, 1));
        check("tri prkna v rade uz plot nejsou", Recipes.match(row, 3, 3).isEmpty(), "");

        Container sticks = new Container(4);
        sticks.set(0, ItemStack.of(World.PLANKS, 1));
        sticks.set(2, ItemStack.of(World.PLANKS, 1));
        ItemStack stickResult = Recipes.match(sticks, 2, 2);
        check("dve prkna pod sebou daji ctyri klacky (i v male mrizce)",
                stickResult.id() == ItemRegistry.STICK && stickResult.count() == 4, stickResult.toString());

        Container torchGrid = new Container(4);
        torchGrid.set(1, ItemStack.of(ItemRegistry.COAL, 1));
        torchGrid.set(3, ItemStack.of(ItemRegistry.STICK, 1));
        ItemStack torches = Recipes.match(torchGrid, 2, 2);
        check("uhli nad klackem dava ctyri pochodne",
                torches.block() == World.TORCH && torches.count() == 4, torches.toString());

        Container flipped = new Container(4);
        flipped.set(1, ItemStack.of(ItemRegistry.STICK, 1));
        flipped.set(3, ItemStack.of(ItemRegistry.COAL, 1));
        check("klacek nad uhlim ne (tvarovany recept)", Recipes.match(flipped, 2, 2).isEmpty(), "");

        Container ore = new Container(4);
        ore.set(2, ItemStack.of(World.COAL_ORE, 1));
        ItemStack coal = Recipes.match(ore, 2, 2);
        check("stara uhelna ruda jde rozbit na uhli", coal.id() == ItemRegistry.COAL && coal.count() == 1, coal.toString());

        w.shutdown();
        bare.shutdown();
        arena.shutdown();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
