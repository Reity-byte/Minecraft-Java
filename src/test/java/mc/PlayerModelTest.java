package mc;

/**
 * Overuje model postavy: matematiku animace (uhel koncetiny podle rychlosti
 * a casu), geometrii modelu v poze a placeholder skin.
 *
 * PlayerAnimation, PlayerModelMesh.build() ani Textures.playerSkinPixels()
 * nesahaji na GL, takze jde vsechno krome samotneho kresleni.
 */
public class PlayerModelTest {

    static int failures = 0;
    static final float DT = 1f / 60f;
    static final float WALK = 4.3f;   // chuze hrace, b/s

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + (detail.isEmpty() ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    static boolean near(float a, float b, float tol) { return Math.abs(a - b) <= tol; }

    /** Necha postavu jit n framu danou rychlosti. */
    static void walk(PlayerAnimation a, float speed, float dt, int frames) {
        for (int i = 0; i < frames; i++) a.update(dt, speed * dt);
    }

    public static void main(String[] args) {
        animation();
        geometry();
        skin();

        System.out.println(failures == 0 ? "\nVSECHNO PROSLO" : "\nSELHALO: " + failures);
    }

    // ==================================================================
    // animace
    // ==================================================================

    static void animation() {
        // ---------- klid ----------
        PlayerAnimation still = new PlayerAnimation();
        walk(still, 0f, DT, 120);
        PlayerPose rest = still.pose(0f, 0f, 0f, false);
        check("v klidu se nohy nehybou", rest.rightLegX() == 0f && rest.leftLegX() == 0f,
                rest.rightLegX() + " / " + rest.leftLegX());

        // ---------- chuze ----------
        PlayerAnimation a = new PlayerAnimation();
        walk(a, WALK, DT, 120);
        float expected = PlayerAnimation.swingAmountFor(WALK);
        check("rozmach odpovida rychlosti chuze (4,3 / 5)", near(a.amount(), expected, 0.01f),
                String.format("%.3f vs %.3f", a.amount(), expected));

        float maxLeg = 0f;
        boolean opposite = true, armAgainstLeg = true;
        float phaseBefore = a.phase();
        for (int i = 0; i < 60; i++) {
            a.update(DT, WALK * DT);
            PlayerPose p = a.pose(0f, 0f, 0f, false);
            maxLeg = Math.max(maxLeg, Math.abs(p.rightLegX()));
            opposite &= p.rightLegX() == -p.leftLegX();
            if (Math.abs(p.rightLegX()) > 0.3f) armAgainstLeg &= p.rightArmX() * p.rightLegX() < 0;
        }
        float phasePerSecond = a.phase() - phaseBefore;

        check("nohy se rozmachnou o LEG_SWING * rozmach",
                near(maxLeg, PlayerAnimation.LEG_SWING * expected, 0.03f),
                String.format("%.3f rad", maxLeg));
        check("nohy jdou vzdy v opacne fazi", opposite, "");
        check("ruka jde proti noze na stejne strane", armAgainstLeg, "");

        float period = (float) (2 * Math.PI / phasePerSecond);
        System.out.printf("%nKrok pri chuzi: %.2f rad/s, cely cyklus %.2f s%n", phasePerSecond, period);
        check("cyklus chuze trva jako v Minecraftu (~0,55 s)", near(period, 0.548f, 0.02f),
                String.format("%.3f s", period));

        // ---------- sprint a let ----------
        // Rozmach je shora omezeny, takze let 12 b/s ma stejny rytmus jako sprint.
        PlayerAnimation sprint = new PlayerAnimation();
        PlayerAnimation fly = new PlayerAnimation();
        walk(sprint, 5.6f, DT, 120);
        walk(fly, 12f, DT, 120);
        float sprintStart = sprint.phase(), flyStart = fly.phase();
        walk(sprint, 5.6f, DT, 60);
        walk(fly, 12f, DT, 60);
        check("rychly let neroztoci nohy rychleji nez sprint",
                near(fly.phase() - flyStart, sprint.phase() - sprintStart, 1e-3f) && fly.amount() <= 1f,
                String.format("%.2f vs %.2f rad/s", fly.phase() - flyStart, sprint.phase() - sprintStart));

        // ---------- plynuly nabeh a nezavislost na FPS ----------
        PlayerAnimation start = new PlayerAnimation();
        start.update(DT, WALK * DT);
        check("rozmach nabiha plynule, ne skokem", start.amount() > 0f && start.amount() < 0.5f * expected,
                String.format("%.3f po prvnim framu", start.amount()));
        walk(start, WALK, DT, 29);
        check("za pul vteriny je skoro naplno", start.amount() > 0.95f * expected,
                String.format("%.3f", start.amount()));

        PlayerAnimation slow = new PlayerAnimation(), fast = new PlayerAnimation();
        walk(slow, WALK, 1f / 30f, 30);
        walk(fast, WALK, 1f / 120f, 120);
        check("rozmach vyjde stejne pri 30 i 120 FPS", near(slow.amount(), fast.amount(), 1e-4f),
                slow.amount() + " vs " + fast.amount());

        walk(a, 0f, DT, 60);
        check("po zastaveni se nohy vrati do klidu", a.amount() < 0.01f, String.format("%.4f", a.amount()));

        // ---------- pohupovani v klidu ----------
        PlayerAnimation idle = new PlayerAnimation();
        float minSway = 1f, maxSway = -1f;
        boolean outward = true, mirrored = true;
        for (int i = 0; i < 600; i++) {
            idle.update(DT, 0f);
            PlayerPose p = idle.pose(0f, 0f, 0f, false);
            minSway = Math.min(minSway, p.leftArmZ());
            maxSway = Math.max(maxSway, p.leftArmZ());
            outward &= p.rightArmZ() <= 0f && p.leftArmZ() >= 0f;
            mirrored &= p.rightArmZ() == -p.leftArmZ() && p.rightArmX() == -p.leftArmX();
        }
        check("v klidu se ruce jemne pohupuji", maxSway - minSway > 0.05f && maxSway <= 0.1001f,
                String.format("%.3f az %.3f rad", minSway, maxSway));
        check("pohupuji se od tela ven a zrcadlove", outward && mirrored, "");

        // ---------- machnuti ----------
        PlayerPose calm = PlayerAnimation.pose(0f, 0f, 0f, 0f, 0f, 0f, false);
        PlayerPose raised = PlayerAnimation.pose(0f, 0f, 0f, 0f, 1f, 0f, false);
        PlayerPose swept = PlayerAnimation.pose(0f, 0f, 0f, 0f, 0f, 1f, false);
        check("rychla krivka zvedne pravou ruku dopredu o 80 stupnu",
                near(raised.rightArmX() - calm.rightArmX(), -PlayerAnimation.SWING_RAISE, 1e-5f),
                String.format("%.1f st", Math.toDegrees(raised.rightArmX() - calm.rightArmX())));
        check("pomala krivka ji stoci pres telo o 20 stupnu",
                near(swept.rightArmY(), PlayerAnimation.SWING_SWEEP, 1e-5f) && calm.rightArmY() == 0f, "");
        check("leva ruka se pri machnuti nehne",
                raised.leftArmX() == calm.leftArmX() && raised.leftArmZ() == calm.leftArmZ(), "");

        // Skutecne machnuti z HandSwing: ruka se zveda a zase vrati.
        HandSwing swing = new HandSwing();
        swing.trigger();
        float highest = 0f;
        while (swing.isSwinging()) {
            swing.update(DT);
            PlayerPose p = PlayerAnimation.pose(0f, 0f, 0f, 0f, swing.fast(), swing.slow(), false);
            highest = Math.max(highest, calm.rightArmX() - p.rightArmX());
        }
        PlayerPose after = PlayerAnimation.pose(0f, 0f, 0f, 0f, swing.fast(), swing.slow(), false);
        check("machnuti z HandSwing ruku zvedne skoro o cely rozsah a vrati zpet",
                highest > 0.95f * PlayerAnimation.SWING_RAISE && after.rightArmX() == calm.rightArmX(),
                String.format("nejvys %.1f st", Math.toDegrees(highest)));

        // ---------- drzeni a hlava ----------
        PlayerPose holding = PlayerAnimation.pose(0f, 0f, 0f, 0f, 0f, 0f, true);
        check("ruka s necim v ruce je predsunuta o pi/10",
                near(holding.rightArmX(), PlayerAnimation.HOLD_ARM, 1e-5f), "" + holding.rightArmX());

        float free = Math.abs(PlayerAnimation.pose(0f, 1f, 0f, 0f, 0f, 0f, false).rightArmX());
        float held = Math.abs(PlayerAnimation.pose(0f, 1f, 0f, 0f, 0f, 0f, true).rightArmX()
                - PlayerAnimation.HOLD_ARM);
        check("s necim v ruce macha za chuze jen napul", near(held, free * 0.5f, 1e-5f),
                String.format("%.3f vs %.3f", held, free));

        check("hlava kopiruje sklon pohledu (nahoru = zaporne kolem X)",
                near(a.pose(30f, 0f, 0f, false).headPitch(), (float) -Math.toRadians(30), 1e-6f), "");
    }

    // ==================================================================
    // geometrie
    // ==================================================================

    /** Slozka vrcholu i (0-6: x, y, z, u, v, slunce, blok). */
    static float at(PlayerModelMesh m, int vertex, int component) {
        return m.vertices()[vertex * PlayerModelMesh.FLOATS_PER_VERTEX + component];
    }

    static int first(int part) { return part * PlayerModelMesh.VERTICES_PER_BOX; }

    /** Prumer slozky pres vrcholy [from, from + count). */
    static float avg(PlayerModelMesh m, int from, int count, int component) {
        float sum = 0;
        for (int i = from; i < from + count; i++) sum += at(m, i, component);
        return sum / count;
    }

    /** Postava s chodidly v pocatku, kamera taky v pocatku, plne slunce. */
    static PlayerModelMesh build(PlayerPose pose, float yaw, byte held) {
        PlayerModelMesh m = new PlayerModelMesh();
        m.build(pose, 0f, 0f, 0f, yaw, held, 1f, 0f, 0f, 0f, 0f);
        return m;
    }

    static void geometry() {
        float px = PlayerModelMesh.PX;

        // Yaw 0 = hrac kouka po +X; jeho pravice je pak +Z (viz Camera.rightVector).
        PlayerModelMesh m = build(PlayerPose.REST, 0f, World.AIR);
        check("postava je 6 kvadru po 36 vrcholech", m.skinVertexCount() == 6 * 36 && m.itemVertexCount() == 0,
                m.skinVertexCount() + " + " + m.itemVertexCount());

        float minY = 1e9f, maxY = -1e9f, maxSide = 0f, maxDepth = 0f;
        for (int i = 0; i < m.skinVertexCount(); i++) {
            minY = Math.min(minY, at(m, i, 1));
            maxY = Math.max(maxY, at(m, i, 1));
            maxSide = Math.max(maxSide, Math.abs(at(m, i, 2)));
            maxDepth = Math.max(maxDepth, Math.abs(at(m, i, 0)));
        }
        check("stoji chodidly na zemi a meri presne jako hitbox",
                near(minY, 0f, 1e-5f) && near(maxY, Player.HEIGHT, 1e-5f), minY + " az " + maxY);
        check("ruce siroke 16 px, hlava hluboka 8 px",
                near(maxSide, 8 * px, 1e-5f) && near(maxDepth, 4 * px, 1e-5f),
                String.format("%.3f / %.3f", maxSide, maxDepth));

        // ---------- ktera ruka je prava ----------
        boolean rightOnRight = true, leftOnLeft = true;
        for (int i = 0; i < 36; i++) {
            rightOnRight &= at(m, first(PlayerModelMesh.PART_RIGHT_ARM) + i, 2) >= 4 * px - 1e-5f;
            leftOnLeft &= at(m, first(PlayerModelMesh.PART_LEFT_ARM) + i, 2) <= -4 * px + 1e-5f;
        }
        check("prava ruka je po prave ruce hrace", rightOnRight && leftOnLeft, "");

        // ---------- oblicej je vepredu ----------
        // Predni stena hlavy jsou vrcholy 24-29 (poradi sten v emitPart).
        PlayerModelMesh west = build(PlayerPose.REST, -90f, World.AIR);   // kouka po -Z
        float faceU = avg(west, 24, 6, 3) * PlayerModelMesh.SKIN_SIZE;
        float faceV = avg(west, 24, 6, 4) * PlayerModelMesh.SKIN_SIZE;
        check("obliceji ze skinu (u 8-16, v 8-16) patri predni stena hlavy",
                near(faceU, 12f, 1e-3f) && near(faceV, 12f, 1e-3f), faceU + ", " + faceV);
        check("a ta miri ve smeru pohledu",
                near(avg(west, 24, 6, 2), -4 * px, 1e-5f) && near(avg(west, 24, 6, 0), 0f, 1e-5f),
                String.format("z=%.3f", avg(west, 24, 6, 2)));

        // ---------- koncetiny v poze ----------
        PlayerPose stride = new PlayerPose(0, 0, 0, 0, 0, 0, -0.6f, 0.6f);
        PlayerModelMesh s = build(stride, 0f, World.AIR);
        float rightFoot = avg(s, first(PlayerModelMesh.PART_RIGHT_LEG), 36, 0);
        float leftFoot = avg(s, first(PlayerModelMesh.PART_LEFT_LEG), 36, 0);
        check("noha se zapornym uhlem jde dopredu, druha dozadu", rightFoot > 0.1f && leftFoot < -0.1f,
                String.format("%.3f / %.3f", rightFoot, leftFoot));

        PlayerPose reach = new PlayerPose(0, (float) (-Math.PI / 2), 0, 0, 0, 0, 0, 0);
        PlayerModelMesh r = build(reach, 0f, World.AIR);
        float armForward = -1e9f;
        for (int i = 0; i < 36; i++) armForward = Math.max(armForward, at(r, first(PlayerModelMesh.PART_RIGHT_ARM) + i, 0));
        check("ruka zvednuta o 90 stupnu miri dopredu", armForward > 9 * px,
                String.format("spicka %.3f bloku pred telem", armForward));

        PlayerPose lookUp = new PlayerPose((float) (-Math.PI / 4), 0, 0, 0, 0, 0, 0, 0);
        PlayerModelMesh up = build(lookUp, 0f, World.AIR);
        check("hlava se zapornym sklonem kouka nahoru", avg(up, 24, 6, 1) > avg(m, 24, 6, 1) + 0.05f,
                String.format("%.3f -> %.3f", avg(m, 24, 6, 1), avg(up, 24, 6, 1)));

        // ---------- drzeny blok ----------
        PlayerModelMesh stone = build(PlayerPose.REST, 0f, World.STONE);
        PlayerModelMesh torch = build(PlayerPose.REST, 0f, World.TORCH);
        check("drzeny blok je jeden kvadr navic (i pochoden)",
                stone.itemVertexCount() == 36 && torch.itemVertexCount() == 36
                        && stone.skinVertexCount() == 6 * 36, "");

        float itemSide = avg(stone, stone.skinVertexCount(), 36, 2);
        float itemY = avg(stone, stone.skinVertexCount(), 36, 1);
        check("blok je v prave ruce, u pesti",
                itemSide > 4 * px && itemY > 9 * px && itemY < 14 * px,
                String.format("strana %.3f, vyska %.3f", itemSide, itemY));

        // ---------- relativne ke kamere ----------
        PlayerModelMesh far = new PlayerModelMesh();
        far.build(PlayerPose.REST, 100000.5f, 70f, 100000.5f, 0f, World.STONE, 1f, 0f,
                100000f, 71.62f, 100002f);
        boolean small = true;
        for (int i = 0; i < far.skinVertexCount() + far.itemVertexCount(); i++)
            small &= Math.abs(at(far, i, 0)) < 3f && Math.abs(at(far, i, 2)) < 3f;
        check("i daleko od pocatku jsou vrcholy mala cisla", small, "");

        // ---------- odstin podle smeru ve svete ----------
        // Pri yaw 0 miri predek (+Z modelu) po svetove ose X: odstin 0,6.
        // Pri yaw 90 po ose Z: 0,8. Sikmo mezi nimi: pul na pul = 0,7.
        check("vrsek hlavy ma plny odstin, spodek polovicni",
                near(at(m, 0, 5), 1f, 1e-5f) && near(at(m, 6, 5), 0.5f, 1e-5f), "");
        check("odstin predku se ridi smerem ve svete, ne stenou modelu",
                near(at(m, 24, 5), 0.6f, 1e-4f) && near(at(build(PlayerPose.REST, 90f, World.AIR), 24, 5), 0.8f, 1e-4f)
                        && near(at(build(PlayerPose.REST, 45f, World.AIR), 24, 5), 0.7f, 1e-4f),
                at(m, 24, 5) + "");
    }

    // ==================================================================
    // skin
    // ==================================================================

    static void skin() {
        int size = PlayerModelMesh.SKIN_SIZE;
        int[] pixels = Textures.playerSkinPixels();

        // Kazdy dil ma v sablone dva radky: vrsek+spodek a ctyri boky.
        boolean opaque = true;
        for (PlayerModelMesh.Part p : PlayerModelMesh.PARTS) {
            int w = (int) (p.x1() - p.x0()), h = (int) (p.y1() - p.y0()), d = (int) (p.z1() - p.z0());
            opaque &= allOpaque(pixels, p.skinU() + d, p.skinV(), 2 * w, d)
                    && allOpaque(pixels, p.skinU(), p.skinV() + d, 2 * d + 2 * w, h);
        }
        check("cele rozbaleni kazdeho dilu je ve skinu vybarvene", opaque, "");

        // Kazda stena modelu musi mirit do vybarvene casti skinu - kdyby UV
        // v PlayerModelMesh a rozlozeni v Textures nesedely, tady se to ukaze.
        PlayerModelMesh m = build(PlayerPose.REST, 0f, World.AIR);
        boolean mapped = true;
        for (int face = 0; face < m.skinVertexCount() / 6; face++) {
            int u = (int) (avg(m, face * 6, 6, 3) * size);
            int v = (int) (avg(m, face * 6, 6, 4) * size);
            mapped &= u >= 0 && u < size && v >= 0 && v < size && (pixels[v * size + u] >>> 24) == 0xFF;
        }
        check("kazda stena modelu miri do vybarvene casti skinu", mapped, "");

        boolean eyes = false, hairBack = true;
        for (int x = 8; x < 16; x++) eyes |= (pixels[12 * size + x] & 0xFFFFFF) == 0xF2F2F2;
        for (int x = 24; x < 32; x++) hairBack &= (pixels[12 * size + x] & 0xFFFFFF) != 0xF2F2F2;
        check("oblicej ma oci, tyl ne - je poznat, kam postava kouka", eyes && hairBack, "");

        check("druha vrstva skinu (klobouk) zustava pruhledna", (pixels[8 * size + 40] >>> 24) == 0, "");
    }

    static boolean allOpaque(int[] pixels, int x, int y, int width, int height) {
        int size = PlayerModelMesh.SKIN_SIZE;
        for (int py = y; py < y + height; py++)
            for (int px = x; px < x + width; px++)
                if ((pixels[py * size + px] >>> 24) != 0xFF) return false;
        return true;
    }
}
