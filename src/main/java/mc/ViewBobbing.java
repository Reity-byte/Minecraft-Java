package mc;

import org.joml.Matrix4f;

/**
 * Houpání pohledu při chůzi (view bobbing) v první osobě.
 *
 * ---------------------------------------------------------------------------
 * Vzorec je z Minecraftu (EntityRenderer.setupViewBobbing). Za chůze se
 * pohled v prostoru kamery:
 *
 *   posune do strany   sin(fáze)      - jednou doleva, jednou doprava za
 *                                       dva kroky (jako přenášení váhy)
 *   klesne             -|cos(fáze)|   - nejníž, když jsou nohy nejvíc od
 *                                       sebe, tedy dvakrát za cyklus
 *   se naklopí         kolem Z a X, o desetiny stupně
 *
 * Fáze je TATÁŽ jako fáze nohou postavy (PlayerAnimation.phase), takže
 * houpání sedí s krokem ve třetí osobě. Síla je PlayerAnimation.bob():
 * 0 na místě, ve vzduchu, v letu i ve vodě, ~0,86 při chůzi.
 *
 * ⚠️ Stejná matice se dává na svět (Camera.viewMatrix) i na ruku
 * (HeldItemRenderer) - ruka se tak houpe s pohledem a vůči světu stojí,
 * jako v Minecraftu. Míří se dál z očí bez houpání (Raycaster), takže
 * zaměřovač může uhnout o desetiny stupně - Minecraft taky.
 * ---------------------------------------------------------------------------
 *
 * Nesahá na GL, čistá funkce.
 */
public final class ViewBobbing {

    /** Síla houpání při plném rozmachu - Minecraftí "cameraYaw" 0,1. */
    static final float STRENGTH = 0.1f;

    /** Posun do strany a pokles jako násobek síly (bloky). */
    static final float SIDE = 0.5f;
    static final float DIP = 1.0f;

    /** Náklon kolem Z a X jako násobek síly (stupně). */
    static final float ROLL = 3f;
    static final float PITCH = 5f;

    private ViewBobbing() {}

    /**
     * Vynásobí dest (zprava) houpáním pro danou fázi kroku a rozmach 0 až 1.
     * Rozmach 0 je přesně identita - na místě se nic nehýbe.
     */
    public static Matrix4f apply(Matrix4f dest, float phase, float amount)
    {
        if(amount <= 0f)
        {
            return dest;
        }

        float s = STRENGTH * amount;
        float sin = (float) Math.sin(phase);
        float cos = (float) Math.cos(phase);

        return dest.translate(sin * s * SIDE, -Math.abs(cos * s) * DIP, 0f)
                .rotateZ((float) Math.toRadians(sin * s * ROLL))
                .rotateX((float) Math.toRadians(Math.abs((float) Math.cos(phase - 0.2f) * s) * PITCH));
    }
}
