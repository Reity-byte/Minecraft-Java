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

        // ---------- recepty ----------
        Container row = new Container(9);
        for (int i = 0; i < 3; i++) row.set(i, ItemStack.of(World.PLANKS, 1));
        ItemStack fenceResult = Recipes.match(row, 3, 3);
        check("tri prkna v rade daji tri ploty",
                fenceResult.block() == World.FENCE && fenceResult.count() == 3,
                fenceResult.toString());

        Container small = new Container(4);
        for (int i = 0; i < 3; i++) small.set(i, ItemStack.of(World.PLANKS, 1));
        check("do male mrizky se plot nevejde", Recipes.match(small, 2, 2).isEmpty(), "");

        Container torchGrid = new Container(4);
        torchGrid.set(0, ItemStack.of(World.PLANKS, 1));
        torchGrid.set(3, ItemStack.of(World.COAL_ORE, 1));
        ItemStack torches = Recipes.match(torchGrid, 2, 2);
        check("prkno a uhli daji ctyri pochodne",
                torches.block() == World.TORCH && torches.count() == 4, torches.toString());

        w.shutdown();
        bare.shutdown();
        arena.shutdown();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }
}
