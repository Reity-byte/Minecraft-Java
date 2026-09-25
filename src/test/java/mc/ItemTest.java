package mc;

import java.util.List;

/**
 * Predmety (ne bloky): registr s vestavenymi a z labu, soubor items.json,
 * atlas predmetu, ikona v inventari, creative prehled a to, ze predmet
 * nejde polozit.
 */
public class ItemTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        try {
            builtIn();
            registry();
            file();
            textures();
            icons();
            creativeAndPlacing();
            model();
            rendering();
        } finally {
            ItemRegistry.activate(ItemRegistry.empty());
            RecipeBook.activate(RecipeBook.empty());
        }

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    static void builtIn() {
        ItemRegistry.activate(ItemRegistry.empty());

        check("klacek a uhli jsou predmety od 256",
                ItemRegistry.STICK == Items.FIRST_ITEM && ItemRegistry.COAL == Items.FIRST_ITEM + 1
                        && Items.isItem(ItemRegistry.STICK) && !Items.isBlock(ItemRegistry.COAL), "");
        check("existuji i bez items.json",
                Items.exists(ItemRegistry.STICK) && Items.exists(ItemRegistry.COAL)
                        && !Items.exists(ItemRegistry.LAST_BUILT_IN + 1) && !Items.exists(ItemRegistry.FIRST_ID), "");
        check("jmena v UI", Items.name(ItemRegistry.STICK).equals("Stick") && Items.name(ItemRegistry.COAL).equals("Coal")
                && Items.name(World.STONE).equals("Stone"), Items.name(ItemRegistry.STICK));
        check("vestavene maji vlastni dlazdice pod BUILT_IN_TILES",
                Items.item(ItemRegistry.STICK).tile() < ItemRegistry.BUILT_IN_TILES
                        && Items.item(ItemRegistry.COAL).tile() < ItemRegistry.BUILT_IN_TILES
                        && Items.item(ItemRegistry.STICK).tile() != Items.item(ItemRegistry.COAL).tile(), "");
        check("obycejny predmet: stack 64, zadny nastroj",
                Items.item(ItemRegistry.STICK).maxStack() == ItemStack.MAX_COUNT && !Items.item(ItemRegistry.STICK).isTool(), "");

        ItemStack sticks = ItemStack.of(ItemRegistry.STICK, 5);
        check("hromadka klacku: neni blok, pokladat by se vzduch",
                !sticks.isEmpty() && !sticks.isBlock() && sticks.block() == World.AIR, sticks.toString());
        check("klacky se slevaji s klacky, ne s uhlim",
                sticks.sameItem(ItemStack.of(ItemRegistry.STICK, 1)) && !sticks.sameItem(ItemStack.of(ItemRegistry.COAL, 1)), "");

        Container c = new Container(2);
        c.add(ItemStack.of(ItemRegistry.STICK, 40));
        c.add(ItemStack.of(ItemRegistry.STICK, 40));
        check("kontejner slozi 80 klacku do 64 + 16", c.get(0).count() == 64 && c.get(1).count() == 16
                && c.countOf(ItemRegistry.STICK) == 80, c.get(0) + " / " + c.get(1));

        RecipeBook.activate(RecipeBook.empty());
        Recipes.Recipe withStick = new Recipes.Recipe(1, 2, new int[]{ItemRegistry.STICK, World.COAL_ORE},
                World.TORCH, 2);
        check("recept z labu smi mit predmet jako surovinu i vysledek",
                RecipeBook.validate(withStick) == null
                        && RecipeBook.validate(new Recipes.Recipe(1, 1, new int[]{World.LOG}, ItemRegistry.STICK, 4)) == null,
                "" + RecipeBook.validate(withStick));
        check("neznamy predmet recept odmitne",
                RecipeBook.validate(new Recipes.Recipe(1, 1, new int[]{ItemRegistry.FIRST_ID}, World.STONE, 1)) != null, "");
    }

    static void registry() {
        ItemRegistry r = ItemRegistry.empty();
        check("prvni volna dlazdice je za vestavenymi", r.freeTile() == ItemRegistry.BUILT_IN_TILES, "" + r.freeTile());

        ItemDef gem = r.define("Gem", r.freeTile());
        check("novy predmet dostane FIRST_ID", gem.id() == ItemRegistry.FIRST_ID, "" + gem.id());

        ItemRegistry withGem = r.with(gem);
        check("define do registru nepridava, with ano", r.get(gem.id()) == null && withGem.get(gem.id()) == gem, "");
        check("dalsi id jde dal", withGem.nextId() == ItemRegistry.FIRST_ID + 1, "");
        check("dlazdice je obsazena", withGem.freeTile() == ItemRegistry.BUILT_IN_TILES + 1, "");
        check("jmeno se hlida i proti vestavenym, bez velikosti pismen",
                withGem.hasName("gem") && withGem.hasName("STICK") && !withGem.hasName("Rock"), "");

        ItemRegistry removed = withGem.without(gem.id());
        check("smazani nevrati id - nextId zustava", removed.get(gem.id()) == null
                && removed.nextId() == ItemRegistry.FIRST_ID + 1, "");
        check("vestavene smazat nejde", removed.without(ItemRegistry.STICK).get(ItemRegistry.STICK) != null, "");

        check("validace", ItemRegistry.validate("", 8, 64, ItemDef.Tool.NONE, 1f) != null
                        && ItemRegistry.validate("X", 64, 64, ItemDef.Tool.NONE, 1f) != null
                        && ItemRegistry.validate("X", 8, 0, ItemDef.Tool.NONE, 1f) != null
                        && ItemRegistry.validate("X", 8, 65, ItemDef.Tool.NONE, 1f) != null
                        && ItemRegistry.validate("X", 8, 1, ItemDef.Tool.PICKAXE, 0.5f) != null
                        && ItemRegistry.validate("X", 8, 1, ItemDef.Tool.PICKAXE, 4f) == null, "");

        ItemRegistry.activate(withGem);
        check("aktivni registr: predmet z labu existuje a ma jmeno",
                Items.exists(gem.id()) && Items.name(gem.id()).equals("Gem"), "");
        ItemRegistry.activate(ItemRegistry.empty());
        check("bez nej je neznamy", !Items.exists(gem.id()) && Items.name(gem.id()).startsWith("Unknown"), "");
    }

    static void file() {
        ItemRegistry r = ItemRegistry.empty();
        ItemDef pick = r.define("Stone Pick", 9).withStack(1).withTool(ItemDef.Tool.PICKAXE, 4f);
        r = r.with(pick);
        ItemDef gem = r.define("Gem", 10);
        r = r.with(gem);

        ItemRegistry back = ItemRegistry.fromJson(r.toJson());
        check("items.json tam a zpet", back.labItems().equals(r.labItems()) && back.nextId() == r.nextId(),
                back.labItems().toString());
        check("vestavene se do souboru nepisou", !r.toJson().contains("Stick"), "");

        String handWritten = """
                {"format": 1, "items": [
                  {"id": 600, "name": "Shell", "tile": 12},
                  {"id": 601, "name": "Bad", "tile": 99},
                  {"id": 602, "name": "Axe", "tile": 13, "stack": 1, "tool": "axe", "toolSpeed": 3},
                  {"id": 603, "name": "Coal", "tile": 14},
                  {"id": 604, "name": "Weird", "tile": 15, "tool": "hammer"},
                  {"id": 5, "name": "Low", "tile": 16}
                ]}
                """;
        System.out.println("  (nize ocekavane hlasky o preskocenych predmetech)");
        ItemRegistry parsed = ItemRegistry.fromJson(handWritten);
        ItemDef shell = parsed.get(600);
        check("chybejici stack/tool/toolSpeed = obycejny predmet",
                shell != null && shell.maxStack() == 64 && shell.tool() == ItemDef.Tool.NONE && shell.toolSpeed() == 1f, "");
        check("nastroj ze souboru", parsed.get(602) != null && parsed.get(602).tool() == ItemDef.Tool.AXE
                && parsed.get(602).toolSpeed() == 3f && parsed.get(602).maxStack() == 1, "");
        check("spatna dlazdice, jmeno vestaveneho, neznamy nastroj a id mimo rozsah se preskoci",
                parsed.get(601) == null && parsed.get(603) == null && parsed.get(604) == null
                        && parsed.labItems().size() == 2, "" + parsed.labItems().size());
        check("nextId se pocita i z preskocenych", parsed.nextId() == 605, "" + parsed.nextId());

        boolean threw = false;
        try { ItemRegistry.fromJson("[1, 2]"); } catch (IllegalArgumentException e) { threw = true; }
        check("neni objekt = chyba celeho souboru", threw, "");
    }

    static void textures() {
        int[] atlas = ItemTextures.procedural();
        check("procedural: klacek i uhli namalovane",
                !Textures.tileEmpty(atlas, ItemRegistry.TILE_STICK) && !Textures.tileEmpty(atlas, ItemRegistry.TILE_COAL), "");

        int[] stick = ItemTextures.tilePixels(atlas, ItemRegistry.TILE_STICK);
        int opaque = 0;
        for (int p : stick) if ((p >>> 24) != 0) opaque++;
        check("klacek je obrys na pruhlednem pozadi (ne plna dlazdice)", opaque > 10 && opaque < 128, "" + opaque);
        check("volne dlazdice jsou pruhledne", Textures.tileEmpty(atlas, ItemRegistry.BUILT_IN_TILES), "");

        ItemRegistry r = ItemRegistry.empty();
        r = r.with(r.define("Gem", 20));
        int[] fromFile = new int[ItemTextures.SIZE * ItemTextures.SIZE];   // jako prazdny items.png
        ItemTextures.complete(fromFile, r);
        check("prazdny soubor: vestavene se doplni, predmet z labu dostane sachovnici",
                !Textures.tileEmpty(fromFile, ItemRegistry.TILE_STICK) && !Textures.tileEmpty(fromFile, 20)
                        && Textures.tileEmpty(fromFile, 21), "");
    }

    static void icons() {
        ItemRegistry.activate(ItemRegistry.empty());
        float[] out = new float[1024];
        int F = BlockIcon.FLOATS_PER_VERTEX;

        int n = BlockIcon.build(ItemRegistry.STICK, 10, 20, 32, true, out, 0);
        check("ikona predmetu je jeden ctverec (6 vrcholu)", n == 6 * F && BlockIcon.floatsFor(ItemRegistry.STICK) == n, "" + n);

        boolean itemSource = true, keepsAlpha = true, inside = true;
        float tileU0 = BlockAtlas.u0(ItemRegistry.TILE_STICK), tileU1 = BlockAtlas.u1(ItemRegistry.TILE_STICK);
        for (int v = 0; v < 6; v++) {
            itemSource &= out[v * F + 6] == 1f;
            keepsAlpha &= out[v * F + 5] == 1f;
            inside &= out[v * F] >= 10 && out[v * F] <= 42 && out[v * F + 2] >= tileU0 && out[v * F + 2] <= tileU1;
        }
        check("bere se z atlasu predmetu a s alfou", itemSource && keepsAlpha, "");
        check("vyplni ctverec a UV lezi v dlazdici klacku", inside, "");

        BlockIcon.build(ItemRegistry.FIRST_ID + 3, 0, 0, 32, true, out, 0);
        check("neznamy predmet: sachovnice z atlasu bloku", out[6] == 0f
                && out[2] >= BlockAtlas.u0(BlockAtlas.TILE_UNKNOWN) && out[2] <= BlockAtlas.u1(BlockAtlas.TILE_UNKNOWN), "");

        float[] a = new float[4096], b = new float[4096];
        int na = BlockIcon.build((int) World.FENCE, 0, 0, 32, true, a, 0);
        int nb = BlockIcon.build(World.FENCE, 0, 0, 32, b, 0);
        boolean same = na == nb;
        for (int i = 0; i < na && same; i++) same = a[i] == b[i];
        check("blok pres id kresli presne tytez vrcholy", same, na + " / " + nb);
    }

    static int[] tileWith(int... xy) {
        int[] t = new int[16 * 16];
        for (int i = 0; i < xy.length; i += 2) t[xy[i + 1] * 16 + xy[i]] = 0xFF804020;
        return t;
    }

    static int quads(int floats) {
        return floats / ItemModel.FLOATS_PER_QUAD;
    }

    static void model() {
        float[] out = new float[ItemModel.MAX_FLOATS];

        check("prazdna dlazdice = zadny model", ItemModel.build(new int[256], 3, out, 0) == 0, "");

        int one = ItemModel.build(tileWith(5, 7), 3, out, 0);
        check("jeden pixel = kvadr: predni, zadni a 4 hrany", quads(one) == 6, "" + quads(one));

        // Normaly ven: kazdy trojuhelnik jednoho pixelu miri od jeho stredu.
        boolean outward = true;
        float cx = 5.5f / 16, cy = 7.5f / 16, cz = 0.5f;
        int F = ItemModel.FLOATS_PER_VERTEX;
        for (int t = 0; t < one; t += 3 * F) {
            float ax = out[t], ay = out[t + 1], az = out[t + 2];
            float bx = out[t + F], by = out[t + F + 1], bz = out[t + F + 2];
            float qx = out[t + 2 * F], qy = out[t + 2 * F + 1], qz = out[t + 2 * F + 2];
            float nx = (by - ay) * (qz - az) - (bz - az) * (qy - ay);
            float ny = (bz - az) * (qx - ax) - (bx - ax) * (qz - az);
            float nz = (bx - ax) * (qy - ay) - (by - ay) * (qx - ax);
            float mx = (ax + bx + qx) / 3 - cx, my = (ay + by + qy) / 3 - cy, mz = (az + bz + qz) / 3 - cz;
            outward &= nx * mx + ny * my + nz * mz > 0;
        }
        check("vsechny steny otocene ven (culling je nezahodi)", outward, "");

        int[] row = new int[256];
        for (int x = 0; x < 16; x++) row[4 * 16 + x] = 0xFF000000;
        int rowFloats = ItemModel.build(row, 3, out, 0);
        check("souvisly radek = jeden ctyruhelnik vpredu a jeden vzadu (+ hrany)",
                quads(rowFloats) == 2 + 16 * 2 + 2, "" + quads(rowFloats));

        // Sachovnice, ve ktere jsou "prazdna" pole poloprusvitna - musi vyjit jako sachovnice.
        int[] half = checker();
        for (int i = 0; i < 256; i++) if (half[i] == 0) half[i] = 0x7F000000;
        check("pixel pod pulkou alfy je pruhledny", quads(ItemModel.build(half, 3, out, 0))
                == quads(ItemModel.build(checker(), 3, out, 0)), "");

        int worst = ItemModel.build(checker(), 3, out, 0);
        check("sachovnice (nejhorsi pripad) se vejde do MAX_FLOATS", worst <= ItemModel.MAX_FLOATS && worst > 0,
                worst + " / " + ItemModel.MAX_FLOATS);

        // UV v dlazdici 3, souradnice v bloku, tloustka jeden pixel kolem stredu.
        boolean uvInside = true, inBlock = true;
        float u0 = BlockAtlas.column(3) * 16f / 128, v0 = BlockAtlas.row(3) * 16f / 128;
        int stick = ItemModel.build(ItemTextures.tilePixels(ItemTextures.procedural(), ItemRegistry.TILE_STICK),
                3, out, 0);
        for (int i = 0; i < stick; i += F) {
            uvInside &= out[i + 3] > u0 && out[i + 3] < u0 + 16f / 128 && out[i + 4] > v0 && out[i + 4] < v0 + 16f / 128;
            inBlock &= out[i] >= 0 && out[i] <= 1 && out[i + 1] >= 0 && out[i + 1] <= 1
                    && out[i + 2] >= ItemModel.Z_BACK && out[i + 2] <= ItemModel.Z_FRONT;
        }
        check("klacek: UV jen z vlastni dlazdice", uvInside, "");
        check("klacek: v souradnicich bloku, jeden pixel tlusty kolem stredu", inBlock, "");
    }

    static int[] checker() {
        int[] t = new int[256];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) if (((x + y) & 1) == 0) t[y * 16 + x] = 0xFF000000;
        return t;
    }

    static void rendering() {
        ItemRegistry.activate(ItemRegistry.empty());
        int[] atlas = ItemTextures.procedural();
        float[] out = new float[HeldItemRenderer.MAX_FLOATS];

        int held = HeldItemRenderer.buildItem(ItemRegistry.STICK, atlas, out);
        check("klacek v ruce ma model", held > 0 && held <= HeldItemRenderer.MAX_FLOATS, "" + held);
        check("bez pixelu nebo neznamy predmet nic", HeldItemRenderer.buildItem(ItemRegistry.STICK, null, out) == 0
                && HeldItemRenderer.buildItem(ItemRegistry.FIRST_ID, atlas, out) == 0, "");

        org.joml.Vector3f center = HeldItemRenderer.itemMatrix(new org.joml.Matrix4f(), 1280, 720, 70f, 0f, 0f,
                HeldItemRenderer.Motion.STILL).transformProject(new org.joml.Vector3f(0.5f, 0.5f, 0.5f));
        check("predmet v ruce je vpravo dole na obrazovce",
                center.x > 0.2f && center.x < 1f && center.y < 0.2f && center.y > -1f, center.toString());

        // Polozky na zemi: bloky a predmety kazdy ve sve instanci.
        World w = CreativeTest.arena(100);
        List<DroppedItem> items = new java.util.ArrayList<>();
        DroppedItems drops = new DroppedItems();
        drops.dropFromBlock(8, 102, 8, ItemStack.of(World.STONE, 1));
        drops.dropFromBlock(9, 102, 8, ItemStack.of(ItemRegistry.STICK, 1));
        items.addAll(drops.items());

        DroppedItemMesh blocks = new DroppedItemMesh();
        DroppedItemMesh sprites = new DroppedItemMesh(atlas);
        blocks.build(items, w, 8, 102, 8, 64);
        sprites.build(items, w, 8, 102, 8, 64);
        check("instance pro bloky stavi jen kostku (36 vrcholu)", blocks.vertexCount() == 36, "" + blocks.vertexCount());
        check("instance pro predmety stavi jen klacek (jeho model)",
                sprites.vertexCount() == held / ItemModel.FLOATS_PER_VERTEX, "" + sprites.vertexCount());

        float maxY = -99, minY = 99;
        float[] v = sprites.vertices();
        for (int i = 0; i < sprites.vertexCount(); i++) {
            maxY = Math.max(maxY, v[i * DroppedItemMesh.FLOATS_PER_VERTEX + 1]);
            minY = Math.min(minY, v[i * DroppedItemMesh.FLOATS_PER_VERTEX + 1]);
        }
        check("predmet na zemi je vetsi nez kostka (az pul bloku)",
                maxY - minY > DroppedItem.SIZE && maxY - minY <= DroppedItemMesh.ITEM_SIZE + 1e-4f, (maxY - minY) + "");
        w.shutdown();
        CreativeTest.opened.remove(w);

        // Postava ve treti osobe.
        PlayerModelMesh body = new PlayerModelMesh();
        PlayerPose pose = PlayerAnimation.pose(0f, 0f, 0f, 0f, 0f, 0f, true);
        body.build(pose, 0, 0, 0, 0f, ItemRegistry.STICK, 1f, 0f, 0, 0, 0);
        check("bez pixelu predmetu postava nic nedrzi", body.itemVertexCount() == 0 && !body.heldIsItem(), "");
        body.setItemPixels(atlas);
        body.build(pose, 0, 0, 0, 0f, ItemRegistry.STICK, 1f, 0f, 0, 0, 0);
        check("postava drzi klacek (model z atlasu predmetu)",
                body.heldIsItem() && body.itemVertexCount() == held / ItemModel.FLOATS_PER_VERTEX, "" + body.itemVertexCount());
        body.build(pose, 0, 0, 0, 0f, World.STONE, 1f, 0f, 0, 0, 0);
        check("s blokem je to zase blok z atlasu bloku", !body.heldIsItem() && body.itemVertexCount() == 36, "");
    }

    static void creativeAndPlacing() {
        ItemRegistry r = ItemRegistry.empty();
        r = r.with(r.define("Gem", 20));

        List<Integer> ids = CreativeInventory.ids(BlockRegistry.empty(), r);
        int stick = ids.indexOf(ItemRegistry.STICK);
        check("creative: predmety az za bloky, vestavene pred labem",
                stick > 0 && ids.get(stick - 1) < Items.FIRST_ITEM
                        && ids.indexOf(ItemRegistry.COAL) == stick + 1 && ids.indexOf(ItemRegistry.FIRST_ID) == stick + 2,
                ids.toString());
        check("stare volani bez predmetu da jen bloky",
                CreativeInventory.container(BlockRegistry.empty()).size() == CreativeInventory.blocks(BlockRegistry.empty()).size(), "");

        World w = CreativeTest.arena(100);
        check("vzduch se 'polozit' neda (hromadka predmetu ma block() = vzduch)",
                !w.placeBlock(8, 102, 8, World.AIR) && w.getBlock(8, 102, 8) == World.AIR, "");
        check("blok se polozit da dal", w.placeBlock(8, 102, 8, World.STONE), "");
        w.shutdown();
        CreativeTest.opened.remove(w);
    }
}
