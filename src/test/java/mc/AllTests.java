package mc;

/**
 * Spustí všechny headless testy najednou.
 *
 * V IntelliJ stačí zelená šipka u main(). Nepotřebuje JUnit ani žádnou další
 * závislost - testy jsou obyčejné třídy, které si počítají chyby do static pole.
 *
 * Testovat jde všechno kromě renderu: World, Player, Raycaster i ChunkMesh.build()
 * nesahají na OpenGL. Chyby v shaderech a v kreslení odhalí až spuštění hry.
 */
public class AllTests {

    private static final String NEWLINE = System.lineSeparator();

    public static void main(String[] args) throws Exception
    {
        int failures = 0;

        System.out.println("========== RayTest ==========");
        RayTest.main(args);
        failures += RayTest.failures;

        System.out.println("\n========== ChunkTest ==========");
        ChunkTest.main(args);
        failures += ChunkTest.failures;

        System.out.println("\n========== MeshTest ==========");
        MeshTest.main(args);
        failures += MeshTest.failures;

        System.out.println("\n========== PerfTest ==========");
        PerfTest.main(args);
        failures += PerfTest.failures;

        System.out.println("\n========== PhysicsTest ==========");
        PhysicsTest.main(args);
        failures += PhysicsTest.failures;

        System.out.println("\n========== MenuTest ==========");
        MenuTest.main(args);
        failures += MenuTest.failures;

        System.out.println("\n========== AtlasTest ==========");
        AtlasTest.main(args);
        failures += AtlasTest.failures;

        System.out.println(NEWLINE + "========== BiomeTest ==========");
        BiomeTest.main(args);
        failures += BiomeTest.failures;

        System.out.println(NEWLINE + "========== CaveTest ==========");
        CaveTest.main(args);
        failures += CaveTest.failures;

        System.out.println(NEWLINE + "========== TreeTest ==========");
        TreeTest.main(args);
        failures += TreeTest.failures;

        System.out.println(NEWLINE + "========== AsyncTest ==========");
        AsyncTest.main(args);
        failures += AsyncTest.failures;

        System.out.println(NEWLINE + "========== MainStateTest ==========");
        MainStateTest.main(args);
        failures += MainStateTest.failures;

        System.out.println(NEWLINE + "========== SaveTest ==========");
        SaveTest.main(args);
        failures += SaveTest.failures;

        System.out.println(NEWLINE + "========== WaterTest ==========");
        WaterTest.main(args);
        failures += WaterTest.failures;

        System.out.println(NEWLINE + "========== InventoryTest ==========");
        InventoryTest.main(args);
        failures += InventoryTest.failures;

        System.out.println(NEWLINE + "========== ModelTest ==========");
        ModelTest.main(args);
        failures += ModelTest.failures;

        System.out.println(NEWLINE + "========== MiningTest ==========");
        MiningTest.main(args);
        failures += MiningTest.failures;

        System.out.println(NEWLINE + "========== DroppedItemTest ==========");
        DroppedItemTest.main(args);
        failures += DroppedItemTest.failures;

        System.out.println(NEWLINE + "========== SwingTest ==========");
        SwingTest.main(args);
        failures += SwingTest.failures;

        System.out.println(NEWLINE + "========== PlayerModelTest ==========");
        PlayerModelTest.main(args);
        failures += PlayerModelTest.failures;

        System.out.println(NEWLINE + "========== CameraTest ==========");
        CameraTest.main(args);
        failures += CameraTest.failures;

        System.out.println(NEWLINE + "========== SoundTest ==========");
        SoundTest.main(args);
        failures += SoundTest.failures;

        System.out.println(NEWLINE + "========== TextureLabTest ==========");
        TextureLabTest.main(args);
        failures += TextureLabTest.failures;

        System.out.println(NEWLINE + "========== LightTest ==========");
        LightTest.main(args);
        failures += LightTest.failures;

        System.out.println(NEWLINE + "========== SkyTest ==========");
        SkyTest.main(args);
        failures += SkyTest.failures;

        System.out.println(NEWLINE + "========== BlockRegistryTest ==========");
        BlockRegistryTest.main(args);
        failures += BlockRegistryTest.failures;

        System.out.println(NEWLINE + "========== RecipeLabTest ==========");
        RecipeLabTest.main(args);
        failures += RecipeLabTest.failures;

        System.out.println(NEWLINE + "========== KeybindTest ==========");
        KeybindTest.main(args);
        failures += KeybindTest.failures;

        System.out.println(NEWLINE + "========== BiomeTuningTest ==========");
        BiomeTuningTest.main(args);
        failures += BiomeTuningTest.failures;

        System.out.println(NEWLINE + "========== LabModesTest ==========");
        LabModesTest.main(args);
        failures += LabModesTest.failures;

        System.out.println(NEWLINE + "========== LabBlockTest ==========");
        LabBlockTest.main(args);
        failures += LabBlockTest.failures;

        System.out.println(NEWLINE + "========== SafeFilesTest ==========");
        SafeFilesTest.main(args);
        failures += SafeFilesTest.failures;

        System.out.println(NEWLINE + "========== WorldSavesTest ==========");
        WorldSavesTest.main(args);
        failures += WorldSavesTest.failures;

        System.out.println(NEWLINE + "========== SeedTest ==========");
        SeedTest.main(args);
        failures += SeedTest.failures;

        System.out.println(NEWLINE + "========== ThumbnailTest ==========");
        ThumbnailTest.main(args);
        failures += ThumbnailTest.failures;

        System.out.println(NEWLINE + "========== WorldScreenTest ==========");
        WorldScreenTest.main(args);
        failures += WorldScreenTest.failures;

        System.out.println(NEWLINE + "========== MouseScaleTest ==========");
        MouseScaleTest.main(args);
        failures += MouseScaleTest.failures;

        System.out.println(NEWLINE + "========== OptionsTest ==========");
        OptionsTest.main(args);
        failures += OptionsTest.failures;

        System.out.println(NEWLINE + "========== CreativeTest ==========");
        CreativeTest.main(args);
        failures += CreativeTest.failures;

        System.out.println(NEWLINE + "========== BlockIconTest ==========");
        BlockIconTest.main(args);
        failures += BlockIconTest.failures;

        System.out.println(failures == 0
                ? "\n>>> ALL PASSED"
                : "\n>>> FAILURES: " + failures);

        System.exit(failures == 0 ? 0 : 1);
    }
}
