package mc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Overuje nahled sveta: zmenseni obsahu framebufferu a PNG tam a zpatky.
 *
 * Dve pasti, na ktere se tu strili: framebuffer ma radek 0 DOLE (obrazek
 * nahore), takze se snadno ulozi nahled vzhuru nohama, a zmensovani musi
 * PRUMEROVAT - vynechavani pixelu by ze sachovnice udelalo jednu barvu
 * a z terenu sum.
 */
public class ThumbnailTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    /** Jedna barva pixelu podle jeho pozice V OBRAZKU (radek 0 nahore). */
    interface Paint {
        int rgb(int x, int imageY);
    }

    /** Framebuffer z glReadPixels: RGBA, radek 0 dole. */
    static ByteBuffer framebuffer(int width, int height, Paint paint) {
        ByteBuffer buffer = ByteBuffer.allocate(width * height * 4);

        for (int imageY = 0; imageY < height; imageY++) {
            int row = (height - 1 - imageY) * width * 4;

            for (int x = 0; x < width; x++) {
                int rgb = paint.rgb(x, imageY);
                buffer.put(row + x * 4, (byte) (rgb >> 16));
                buffer.put(row + x * 4 + 1, (byte) (rgb >> 8));
                buffer.put(row + x * 4 + 2, (byte) rgb);
                buffer.put(row + x * 4 + 3, (byte) 0);   // alfa z obrazovky je k nicemu
            }
        }

        return buffer;
    }

    public static void main(String[] args) throws IOException {
        scaling();
        orientation();
        cropping();
        averaging();
        files();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ==================================================================
    // 1) zmenseni
    // ==================================================================

    static void scaling() {
        System.out.println("\n-- zmenseni --");

        check("nahled je 16:9", Thumbnails.WIDTH * 9 == Thumbnails.HEIGHT * 16,
                Thumbnails.WIDTH + "x" + Thumbnails.HEIGHT);

        ByteBuffer flat = framebuffer(320, 180, (x, y) -> 0x0AC81E);
        int[] out = Thumbnails.fromFramebuffer(flat, 320, 180, Thumbnails.WIDTH, Thumbnails.HEIGHT);

        check("vysledek ma rozmer nahledu", out.length == Thumbnails.WIDTH * Thumbnails.HEIGHT,
                "" + out.length);

        boolean uniform = true;
        for (int pixel : out) if (pixel != 0xFF0AC81E) uniform = false;

        check("jednobarevny vstup da jednobarevny vystup (a plnou alfu)", uniform,
                String.format("0x%08X", out[0]));

        // Okno mensi nez nahled se nesmi rozsypat, i kdyz to je nesmysl.
        int[] tiny = Thumbnails.fromFramebuffer(framebuffer(64, 36, (x, y) -> 0x102030),
                64, 36, Thumbnails.WIDTH, Thumbnails.HEIGHT);
        boolean tinyOk = tiny.length == Thumbnails.WIDTH * Thumbnails.HEIGHT;
        for (int pixel : tiny) if (pixel != 0xFF102030) tinyOk = false;

        check("okno mensi nez nahled se zvetsi, ne rozsype", tinyOk, "");
    }

    // ==================================================================
    // 2) orientace
    // ==================================================================

    static void orientation() {
        System.out.println("\n-- orientace --");

        // ⚠️ Horni polovina OBRAZOVKY musi byt horni polovina obrazku.
        ByteBuffer vertical = framebuffer(256, 144, (x, y) -> y < 72 ? 0xFF0000 : 0x0000FF);
        int[] out = Thumbnails.fromFramebuffer(vertical, 256, 144, 128, 72);

        check("horni radek framebufferu je horni radek obrazku",
                out[0] == 0xFFFF0000, String.format("0x%08X", out[0]));
        check("spodni radek zustal dole",
                out[out.length - 1] == 0xFF0000FF, String.format("0x%08X", out[out.length - 1]));

        boolean split = true;
        for (int y = 0; y < 72; y++)
            for (int x = 0; x < 128; x++) {
                int expected = y < 36 ? 0xFFFF0000 : 0xFF0000FF;
                if (out[y * 128 + x] != expected) split = false;
            }

        check("vodorovna hranice sedi presne v pulce", split, "");

        ByteBuffer horizontal = framebuffer(256, 144, (x, y) -> x < 128 ? 0x00FF00 : 0xFF0000);
        int[] sides = Thumbnails.fromFramebuffer(horizontal, 256, 144, 128, 72);

        check("levy okraj zustal vlevo", sides[0] == 0xFF00FF00, String.format("0x%08X", sides[0]));
        check("pravy okraj zustal vpravo", sides[127] == 0xFFFF0000,
                String.format("0x%08X", sides[127]));
    }

    // ==================================================================
    // 3) vyrez stredu
    // ==================================================================

    static void cropping() {
        System.out.println("\n-- vyrez stredu --");

        // Sirsi obrazovka nez 16:9: vystrihne se prostrednich 128 sloupcu.
        ByteBuffer wide = framebuffer(300, 72, (x, y) -> x >= 86 && x < 214 ? 0x0000FF : 0xFF0000);
        int[] out = Thumbnails.fromFramebuffer(wide, 300, 72, 128, 72);

        boolean allBlue = true;
        for (int pixel : out) if (pixel != 0xFF0000FF) allBlue = false;

        check("ze siroke obrazovky se vezme stred, ne okraje", allBlue,
                String.format("0x%08X", out[0]));

        // Vyssi obrazovka: vystrihne se prostrednich 72 radku.
        ByteBuffer tall = framebuffer(128, 200, (x, y) -> y >= 64 && y < 136 ? 0x0000FF : 0xFF0000);
        int[] fromTall = Thumbnails.fromFramebuffer(tall, 128, 200, 128, 72);

        boolean tallBlue = true;
        for (int pixel : fromTall) if (pixel != 0xFF0000FF) tallBlue = false;

        check("z vysoke obrazovky taky stred", tallBlue, String.format("0x%08X", fromTall[0]));

        // A obraz se nesmi natahnout: ctverec zustane ctvercem.
        ByteBuffer square = framebuffer(200, 200,
                (x, y) -> x >= 64 && x < 136 && y >= 64 && y < 136 ? 0xFFFFFF : 0x000000);
        int[] fromSquare = Thumbnails.fromFramebuffer(square, 200, 200, 72, 72);

        // Vyrez je cely obrazek (200x200 -> 72x72), bily ctverec zabira
        // prostredni 72/200 obrazu v obou osach stejne.
        int whiteRows = 0, whiteCols = 0;
        for (int y = 0; y < 72; y++) if (bright(fromSquare[y * 72 + 36])) whiteRows++;
        for (int x = 0; x < 72; x++) if (bright(fromSquare[36 * 72 + x])) whiteCols++;

        check("ctverec zustane ctvercem (nic se nenatahuje)",
                Math.abs(whiteRows - whiteCols) <= 1, whiteRows + " x " + whiteCols);
    }

    static boolean bright(int argb) {
        return (argb & 0xFF) > 127;
    }

    // ==================================================================
    // 4) prumerovani
    // ==================================================================

    static void averaging() {
        System.out.println("\n-- prumerovani --");

        // ⚠️ Sachovnice po jednom pixelu: pri vynechavani pixelu by vysla
        // cela cerna nebo cela bila, pri prumerovani seda.
        ByteBuffer board = framebuffer(256, 144, (x, y) -> ((x + y) & 1) == 0 ? 0xFFFFFF : 0x000000);
        int[] out = Thumbnails.fromFramebuffer(board, 256, 144, 128, 72);

        int min = 255, max = 0;
        for (int pixel : out) {
            int value = pixel & 0xFF;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        check("sachovnice 1 px se zprumeruje na sedou (2x2 zdrojove pixely)",
                min >= 125 && max <= 130, min + " az " + max);

        // Stejne to musi dopadnout i pri lichem poctu zdrojovych pixelu
        // na jeden cilovy (1920x1080 -> 15x15).
        ByteBuffer full = framebuffer(1920, 1080, (x, y) -> ((x + y) & 1) == 0 ? 0xFFFFFF : 0x000000);
        int[] fromFull = Thumbnails.fromFramebuffer(full, 1920, 1080, 128, 72);

        int min2 = 255, max2 = 0;
        for (int pixel : fromFull) {
            int value = pixel & 0xFF;
            min2 = Math.min(min2, value);
            max2 = Math.max(max2, value);
        }

        check("sachovnice na plne obrazovce taky (15x15 zdrojovych pixelu)",
                min2 >= 125 && max2 <= 130, min2 + " az " + max2);

        // Kdyby se pixely vynechavaly misto prumerovani, jeden svisly pruh
        // by v nahledu zmizel uplne.
        ByteBuffer stripe = framebuffer(1280, 720, (x, y) -> x == 640 ? 0xFFFFFF : 0x000000);
        int[] fromStripe = Thumbnails.fromFramebuffer(stripe, 1280, 720, 128, 72);

        int brightest = 0;
        for (int pixel : fromStripe) brightest = Math.max(brightest, pixel & 0xFF);

        check("jednopixelovy detail se neztrati, jen zesvetli", brightest > 0,
                "nejsvetlejsi " + brightest);
    }

    // ==================================================================
    // 5) soubor
    // ==================================================================

    static void files() throws IOException {
        System.out.println("\n-- PNG --");

        Path dir = Files.createTempDirectory("mc-thumb-test");
        Path file = dir.resolve("svet").resolve(WorldSaves.ICON_FILE);

        int[] argb = new int[Thumbnails.WIDTH * Thumbnails.HEIGHT];
        for (int y = 0; y < Thumbnails.HEIGHT; y++)
            for (int x = 0; x < Thumbnails.WIDTH; x++)
                argb[y * Thumbnails.WIDTH + x] = 0xFF000000 | (x * 2 << 16) | (y * 3 << 8) | (x ^ y);

        check("ulozeni projde (a zalozi si slozku)",
                Thumbnails.save(argb, Thumbnails.WIDTH, Thumbnails.HEIGHT, file), "");
        check("soubor existuje", Files.isRegularFile(file), "");
        check("po ulozeni nezbyl docasny soubor",
                !Files.exists(file.resolveSibling(WorldSaves.ICON_FILE + ".tmp")), "");

        Thumbnails.Image loaded = Thumbnails.load(file, Thumbnails.WIDTH, Thumbnails.HEIGHT);
        check("nacteni projde", loaded != null, "");
        check("rozmer sedi",
                loaded != null && loaded.width() == Thumbnails.WIDTH && loaded.height() == Thumbnails.HEIGHT,
                loaded == null ? "null" : loaded.width() + "x" + loaded.height());
        check("pixely jsou tam a zpet bez zmeny",
                loaded != null && Arrays.equals(argb, loaded.argb()), "");

        check("ulozeni pres existujici soubor projde",
                Thumbnails.save(argb, Thumbnails.WIDTH, Thumbnails.HEIGHT, file), "");

        // Limit: nahled vetsi nez povoleny se odmitne jeste pred dekodovanim.
        check("o pixel sirsi limit neprojde",
                Thumbnails.load(file, Thumbnails.WIDTH - 1, Thumbnails.HEIGHT) == null, "");
        check("o pixel nizsi limit neprojde",
                Thumbnails.load(file, Thumbnails.WIDTH, Thumbnails.HEIGHT - 1) == null, "");
        check("velkorysy limit projde", Thumbnails.load(file, 1024, 1024) != null, "");

        Path big = dir.resolve("velky.png");
        int[] bigPixels = new int[400 * 300];
        Arrays.fill(bigPixels, 0xFF123456);
        Thumbnails.save(bigPixels, 400, 300, big);
        check("vetsi obrazek se odmitne",
                Thumbnails.load(big, Thumbnails.WIDTH, Thumbnails.HEIGHT) == null, "");
        check("a s odpovidajicim limitem se nacte",
                Thumbnails.load(big, 400, 300) != null, "");

        Path junk = dir.resolve("neobrazek.png");
        Files.write(junk, "tohle neni PNG".getBytes(StandardCharsets.UTF_8));
        check("co neni obrazek, se odmitne", Thumbnails.load(junk, 1024, 1024) == null, "");

        Path truncated = dir.resolve("useknuty.png");
        byte[] wholePng = Files.readAllBytes(file);
        Files.write(truncated, Arrays.copyOf(wholePng, wholePng.length / 2));
        check("useknuty soubor se odmitne misto padu",
                Thumbnails.load(truncated, 1024, 1024) == null, "");

        check("chybejici soubor je mlcky null",
                Thumbnails.load(dir.resolve("neni.png"), 1024, 1024) == null, "");

        check("prilis male pole se neulozi",
                !Thumbnails.save(new int[10], Thumbnails.WIDTH, Thumbnails.HEIGHT,
                        dir.resolve("kratky.png")), "");
        check("a soubor po sobe nenecha", !Files.exists(dir.resolve("kratky.png")), "");
    }
}
