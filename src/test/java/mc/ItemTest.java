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
            draft();
            gameplay();
            durability();
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

    static void draft() {
        ItemRegistry.activate(ItemRegistry.empty());
        ItemRegistry r = ItemRegistry.empty();

        ItemDraft d = new ItemDraft(10);
        check("novy predmet: obycejny, stack 64", d.stack() == 64 && d.tool == ItemDef.Tool.NONE && d.speed() == 1f, "");
        check("bez jmena nejde", d.problem(r) != null, "");

        d.name = "Gem";
        check("se jmenem a vlastni dlazdici jde", d.problem(r) == null, "" + d.problem(r));

        d.fewer();
        check("hromadka se krokuje dolu (32)", d.stack() == 32, "" + d.stack());
        for (int i = 0; i < 10; i++) d.fewer();
        check("a zastavi se na 1", d.stack() == 1, "");
        for (int i = 0; i < 10; i++) d.more();
        check("nahoru nejvys 64", d.stack() == 64, "");

        d.nextTool();
        check("nastroj (krumpac) = hromadka 1 a rychlost 4x",
                d.tool == ItemDef.Tool.PICKAXE && d.stack() == 1 && d.speed() == 4f && d.toolLabel().equals("Pickaxe"), "");
        d.nextSpeed();
        check("rychlost se krokuje", d.speed() == 6f, "" + d.speed());
        d.nextTool(); d.nextTool(); d.nextTool();
        check("dokola zpatky na obycejny predmet se 64", d.tool == ItemDef.Tool.NONE && d.stack() == 64
                && d.speed() == 1f && d.toolLabel().equals("No tool"), "");

        d.nextTool();
        ItemDef def = d.toDef(r);
        check("toDef: id z registru, vlastnosti z navrhu", def.id() == ItemRegistry.FIRST_ID && def.tile() == 10
                && def.tool() == ItemDef.Tool.PICKAXE && def.maxStack() == 1 && def.toolSpeed() == 6f, def.toString());

        d.name = "coal";
        check("jmeno vestaveneho predmetu nejde", d.problem(r) != null && d.problem(r).contains("item"), "" + d.problem(r));
        d.name = "Stone";
        check("jmeno bloku taky ne", d.problem(r) != null && d.problem(r).contains("block"), "" + d.problem(r));
        d.name = "Rock";
        d.tile = ItemRegistry.TILE_STICK;
        check("dlazdice vestavenych predmetu nejde", d.problem(r) != null && d.problem(r).contains("built-in"), "");

        int[] pixels = ItemTextures.procedural();
        check("volna dlazdice: prvni prazdna za vestavenymi",
                ItemDraft.freeTile(r, pixels, -1) == ItemRegistry.BUILT_IN_TILES, "");
        check("krome te, ktera se kopiruje",
                ItemDraft.freeTile(r, pixels, ItemRegistry.BUILT_IN_TILES) == ItemRegistry.BUILT_IN_TILES + 1, "");
        ItemRegistry used = r.with(r.define("Gem", ItemRegistry.BUILT_IN_TILES));
        pixels[AtlasEditor.pixelIndex(ItemRegistry.BUILT_IN_TILES + 1, 3, 3)] = 0xFF123456;
        check("dlazdice predmetu i namalovana se preskoci, kdyz je prazdna jinde",
                ItemDraft.freeTile(used, pixels, -1) == ItemRegistry.BUILT_IN_TILES + 2, "" + ItemDraft.freeTile(used, pixels, -1));
    }

    /** Snimku kopani (1/60 s) do rozbiti bloku, s danou veci v ruce. */
    static int framesToBreak(World w, int x, int y, int z, int heldId) {
        Mining m = new Mining();
        Raycaster.RaycastHit hit = new Raycaster.RaycastHit(x, y, z, 0, 1, 0);
        for (int f = 1; f < 5000; f++) {
            if (m.update(w, 1f / 60f, true, hit, GameMode.SURVIVAL, heldId)) return f;
        }
        return -1;
    }

    static void gameplay() {
        ItemRegistry r = ItemRegistry.empty();
        ItemDef pick = r.define("Pick", 20).withStack(1).withTool(ItemDef.Tool.PICKAXE, 4f);
        r = r.with(pick);
        ItemDef axe = r.define("Axe", 21).withStack(1).withTool(ItemDef.Tool.AXE, 2f);
        r = r.with(axe);
        ItemRegistry.activate(r);

        check("krumpac na kamen 4x, na hlinu jako ruka",
                Items.miningSpeed(pick.id(), World.STONE) == 4f && Items.miningSpeed(pick.id(), World.DIRT) == 1f
                        && Items.miningSpeed(pick.id(), World.IRON_ORE) == 4f, "");
        check("sekera na drevo, klacek ani blok v ruce nic",
                Items.miningSpeed(axe.id(), World.LOG) == 2f && Items.miningSpeed(ItemRegistry.STICK, World.STONE) == 1f
                        && Items.miningSpeed(World.STONE, World.STONE) == 1f, "");

        World w = CreativeTest.arena(100);
        w.placeBlock(8, 101, 8, World.STONE);
        int hand = framesToBreak(w, 8, 101, 8, World.AIR);
        int withPick = framesToBreak(w, 8, 101, 8, pick.id());
        int withStick = framesToBreak(w, 8, 101, 8, ItemRegistry.STICK);
        check("kamen krumpacem 4x rychleji nez rukou", Math.abs(hand - 4 * withPick) <= 4 && withStick == hand,
                hand + " / " + withPick + " / " + withStick);

        check("uhelna ruda da uhli, ostatni bloky samy sebe",
                Mining.dropOf(World.COAL_ORE) == ItemRegistry.COAL && Mining.dropOf(World.IRON_ORE) == World.IRON_ORE
                        && Mining.dropOf(World.STONE) == World.STONE, "");

        w.placeBlock(9, 101, 8, World.COAL_ORE);
        Mining m = new Mining();
        Raycaster.RaycastHit oreHit = new Raycaster.RaycastHit(9, 101, 8, 0, 1, 0);
        while (!m.update(w, 1f / 60f, true, oreHit, GameMode.SURVIVAL, pick.id())) { /* kope se */ }
        DroppedItems drops = new DroppedItems();
        m.harvest(w, new Inventory(), drops, SoundSink.SILENT);
        check("vytezena uhelna ruda lezi na zemi jako uhli",
                drops.size() == 1 && drops.items().get(0).stack().id() == ItemRegistry.COAL, "" + drops.size());
        w.shutdown();
        CreativeTest.opened.remove(w);

        // Nastroj se nestackuje.
        ItemStack one = ItemStack.of(pick.id(), 1);
        check("nastroj: slot unese jeden kus", one.maxCount() == 1 && one.space() == 0
                && ItemStack.of(ItemRegistry.STICK, 1).maxCount() == 64 && ItemStack.of(World.STONE, 1).maxCount() == 64, "");
        Container c = new Container(4);
        ItemStack rest = c.add(ItemStack.of(pick.id(), 3));
        check("tri krumpace do tri slotu", rest.isEmpty() && c.get(0).count() == 1 && c.get(1).count() == 1
                && c.get(2).count() == 1, c.get(0) + " " + c.get(1));
        check("misto pro krumpace = volne sloty", c.room(one) == 1, "" + c.room(one));
        check("recept nesmi dat dva nastroje naraz",
                RecipeBook.validate(new Recipes.Recipe(1, 1, new int[]{World.STONE}, pick.id(), 2)) != null
                        && RecipeBook.validate(new Recipes.Recipe(1, 1, new int[]{World.STONE}, pick.id(), 1)) == null, "");

        ItemRegistry.activate(ItemRegistry.empty());
    }

    static void durability() {
        ItemRegistry r = ItemRegistry.empty();
        ItemDef pick = r.define("Pick", 20).withStack(1).withTool(ItemDef.Tool.PICKAXE, 4f).withDurability(3);
        r = r.with(pick);
        ItemRegistry.activate(r);

        ItemStack fresh = ItemStack.of(pick.id(), 1);
        ItemStack once = fresh.worn(1);
        check("pouziti ubere bod vydrze", once.damage() == 1 && once.id() == pick.id() && once.count() == 1, once.toString());
        check("na posledni bod nastroj praskne (EMPTY)", once.worn(1).worn(1).isEmpty() && fresh.worn(3).isEmpty(), "");
        check("predmet bez vydrze ani blok se neopotrebi (tataz hromadka)",
                ItemStack.of(ItemRegistry.STICK, 5).worn(1).equals(ItemStack.of(ItemRegistry.STICK, 5))
                        && ItemStack.of(World.STONE, 1).worn(1).damage() == 0, "");
        check("ruzne opotrebene nejsou totez, pocet opotrebeni drzi",
                !fresh.sameItem(once) && once.sameItem(once.withCount(1)) && once.plus(0).damage() == 1, "");

        check("validace vydrze",
                ItemRegistry.validate("X", 20, 64, ItemDef.Tool.PICKAXE, 4f, 10) != null
                        && ItemRegistry.validate("X", 20, 1, ItemDef.Tool.PICKAXE, 4f, -1) != null
                        && ItemRegistry.validate("X", 20, 1, ItemDef.Tool.PICKAXE, 4f, ItemDef.MAX_DURABILITY + 1) != null
                        && ItemRegistry.validate("X", 20, 1, ItemDef.Tool.PICKAXE, 4f, 131) == null, "");
        check("items.json nese vydrz, chybejici = nerozbitny",
                ItemRegistry.fromJson(r.toJson()).get(pick.id()).durability() == 3
                        && ItemRegistry.fromJson("{\"format\": 1, \"items\": [{\"id\": 700, \"name\": \"A\", \"tile\": 9}]}")
                        .get(700).durability() == 0, "");

        check("novy nastroj pruh nema, opotrebeny ano",
                Durability.remaining(fresh) < 0 && Math.abs(Durability.remaining(ItemStack.of(pick.id(), 1, 1)) - 2f / 3f) < 1e-5f
                        && Durability.remaining(ItemStack.of(ItemRegistry.STICK, 1)) < 0, "");
        float[] green = Durability.color(1f), red = Durability.color(0f);
        check("pruh: novy zeleny, skoro prasknuty cerveny",
                green[1] > 0.9f && green[0] < 0.1f && red[0] > 0.9f && red[1] < 0.1f, "");

        check("nastroje se opotrebi jen v survivalu",
                GameMode.SURVIVAL.wearsTools() && !GameMode.CREATIVE.wearsTools(), "");

        ItemDraft d = new ItemDraft(20);
        check("obycejny predmet je nerozbitny", d.durability() == 0 && d.durabilityLabel().equals("Unbreakable"), "");
        d.nextTool();
        check("nastroj dostane vydrz kamene (131)", d.durability() == 131 && d.stack() == 1
                && d.durabilityLabel().equals("Uses 131"), d.durabilityLabel());
        d.nextTool();
        check("krokovani nastroju vydrz nemeni", d.durability() == 131, "");
        d.more();
        check("s vydrzi zustava hromadka 1", d.stack() == 1, "" + d.stack());
        d.nextDurability();
        d.name = "Iron Axe";
        ItemDef made = d.toDef(r);
        check("toDef nese vydrz (zelezo 250)", made.durability() == 250 && d.problem(r) == null, "" + d.problem(r));
        d.nextTool(); d.nextTool();
        check("zpatky na obycejny predmet: bez vydrze a 64", d.tool == ItemDef.Tool.NONE && d.durability() == 0 && d.stack() == 64, "");

        ItemRegistry.activate(ItemRegistry.empty());
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
