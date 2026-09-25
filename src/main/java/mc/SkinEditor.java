package mc;

/**
 * Úpravy pixelů kůže postavy - druhý režim texture labu.
 *
 * ---------------------------------------------------------------------------
 * Malování, barva, kapátko, undo a sledování změn jsou v PixelEditor,
 * stejně jako u atlasu bloků; tady je jen to vlastní kůži: oblast na plátně
 * není dlaždice mřížky, ale STĚNA DÍLU TĚLA (obličej 8x8, bok ruky 4x12).
 * Kde ta stěna ve skinu leží, počítá SkinLayout ze stejných vzorců, jaké
 * dávají UV modelu.
 *
 * ⚠️ Editor pracuje PŘÍMO NA POLI, ze kterého je nahraná textura skinu -
 * tedy na tom, které hře kreslí postavu i ruku v první osobě. Tah štětcem
 * je proto vidět na modelu v náhledu i na postavě za labem ve stejném framu,
 * úplně stejně jako u atlasu bloků.
 * ---------------------------------------------------------------------------
 */
public final class SkinEditor extends PixelEditor {

    public static final int SIZE = SkinLayout.SIZE;

    /** Která stěna dílu je na plátně. Výchozí je obličej - tam se pozná nejvíc. */
    private int face = SkinLayout.face(PlayerModelMesh.PART_HEAD, SkinLayout.FRONT);

    public SkinEditor(int[] skinPixels)
    {
        super(skinPixels, SIZE);
    }

    @Override public int regionWidth()  { return SkinLayout.width(face); }
    @Override public int regionHeight() { return SkinLayout.height(face); }

    @Override public int index(int x, int y)
    {
        return SkinLayout.index(face, x, y);
    }

    @Override protected int region()
    {
        return face;
    }

    @Override protected void selectRegion(int region)
    {
        face = region;
    }

    public int face()
    {
        return face;
    }

    public void select(int face)
    {
        if(face >= 0 && face < SkinLayout.FACE_COUNT)
        {
            endStroke();
            this.face = face;
        }
    }

    /** Jméno vybrané stěny do rozhraní labu. */
    public String faceName()
    {
        return SkinLayout.name(face);
    }
}
