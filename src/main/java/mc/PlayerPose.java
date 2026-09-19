package mc;

/**
 * Póza postavy: natočení kloubů v radiánech. Jen data, nic nekreslí.
 *
 * Úhly jsou v souřadnicích MODELU: postava kouká po +Z, nahoru je +Y a její
 * pravá ruka je na -X. Otáčí se v pořadí Z, Y, X kolem kloubu (rameno,
 * kyčel, krk) - stejně jako v Minecraftu. Kladné X u končetiny ji vychýlí
 * DOZADU, záporné dopředu; kladné Z u pravé ruky ji přitáhne k tělu.
 *
 * Levá ruka nemá natočení kolem Y - máchá jen pravá.
 */
public record PlayerPose(float headPitch,
                         float rightArmX, float rightArmY, float rightArmZ,
                         float leftArmX, float leftArmZ,
                         float rightLegX, float leftLegX) {

    /** Postava stojí rovně, ruce podél těla. */
    public static final PlayerPose REST = new PlayerPose(0, 0, 0, 0, 0, 0, 0, 0);
}
