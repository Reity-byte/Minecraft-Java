package mc;

/**
 * Blok založený v texture labu: jen data, žádný kód.
 *
 * ---------------------------------------------------------------------------
 * Vestavěné bloky (konstanty ve World) tu NEJSOU - ty dál žijí v kódu
 * a jejich id se nemění. BlockDef popisuje jen bloky z textures/blocks.json,
 * které mají id od BlockRegistry.FIRST_ID výš.
 *
 * Tvar je vždycky plná krychle. Vlastní model (pochodeň, plot) z dat udělat
 * nejde - BlockModels je kód a ten zůstává kódem.
 * ---------------------------------------------------------------------------
 *
 * @param hardness sekundy do rozbití holou rukou - stejná škála jako World.hardness()
 * @param solid    zastaví hráče (World.blocksMovement)
 * @param opaque   zakrývá sousedy a zastaví světlo (World.isOpaque)
 */
public record BlockDef(byte id, String name, float hardness, boolean solid, boolean opaque,
                       int topTile, int sideTile, int bottomTile) {

    /** Dlaždice pro stěnu; face je jedna z BlockAtlas.FACE_*. */
    public int tile(int face)
    {
        return switch(face)
        {
            case BlockAtlas.FACE_TOP    -> topTile;
            case BlockAtlas.FACE_BOTTOM -> bottomTile;
            default                     -> sideTile;
        };
    }

    public BlockDef withTiles(int top, int side, int bottom)
    {
        return new BlockDef(id, name, hardness, solid, opaque, top, side, bottom);
    }
}
