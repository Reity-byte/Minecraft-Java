package mc;

/**
 * Zdrojáky GLSL shaderů.
 *
 * Jsou zatím přímo tady jako text bloky, ne v resources - je jich málo a takhle
 * je nemůže rozbít chybějící soubor v jaru. Až jich bude víc, přesunou se
 * do src/main/resources a načtou se za běhu.
 *
 * ---------------------------------------------------------------------------
 * POZOR na jednu věc, která se táhne celým rendererem: souřadnice vrcholů
 * jsou RELATIVNÍ K POČÁTKU CHUNKU (0-16), ne světové, a uniform uChunkOffset
 * je "počátek chunku MÍNUS pozice kamery". Kamera tedy sedí v počátku a svět
 * se posouvá k ní.
 *
 * Proč: float má 24bitovou mantisu. Ve světové souřadnici 100 000 je nejmenší
 * rozlišitelný krok ~0,008 bloku, v milionu už 0,06 - a geometrie by se začala
 * viditelně třást. Když se odečtení kamery udělá na CPU v double a do shaderu
 * jdou jen malá čísla, tenhle problém úplně zmizí. U nekonečného světa je to
 * rozdíl mezi "funguje všude" a "funguje blízko počátku".
 * ---------------------------------------------------------------------------
 */
public class Shaders {

    public static final String WORLD_VERTEX = """
            #version 330 core

            layout (location = 0) in vec3 aPos;    // 0-16, relativně k počátku chunku
            layout (location = 1) in vec2 aUv;     // dlaždice v atlasu bloků
            layout (location = 2) in float aSky;   // sluneční světlo, už se ztmavením stěny
            layout (location = 3) in float aBlock; // blokové světlo, taky se ztmavením

            uniform mat4 uViewProjection;
            uniform vec3 uChunkOffset;             // počátek chunku minus pozice kamery

            out vec2 vUv;
            out float vSky;
            out float vBlock;
            out float vDistance;

            void main()
            {
                // Pozice vůči kameře. Protože kamera je v počátku, je délka
                // tohohle vektoru rovnou vzdálenost od kamery - použije se na mlhu.
                vec3 cameraRelative = aPos + uChunkOffset;

                vUv = aUv;
                vSky = aSky;
                vBlock = aBlock;
                vDistance = length(cameraRelative);

                gl_Position = uViewProjection * vec4(cameraRelative, 1.0);
            }
            """;

    /**
     * ⚠️ SLUNEČNÍ A BLOKOVÉ SVĚTLO SE SČÍTAJÍ MAXIMEM, NE SOUČTEM, a slábne
     * jen to sluneční.
     *
     * Kdyby se sčítaly, byla by pochodeň ve dne jasnější než okolí a v jeskyni
     * by dvě pochodně vedle sebe přepálily obraz do běla. Maximum odpovídá
     * tomu, jak to dělá Minecraft.
     *
     * A hlavně: kanály musí zůstat oddělené až sem. Ztlumit slunce znamená
     * změnit jeden uniform, ne přepočítat světlo celého světa - jinak by
     * každý západ slunce znamenal přestavbu všech meshů.
     */
    public static final String WORLD_FRAGMENT = """
            #version 330 core

            in vec2 vUv;
            in float vSky;
            in float vBlock;
            in float vDistance;

            uniform sampler2D uAtlas;
            uniform vec3 uFogColor;
            uniform float uFogStart;
            uniform float uFogEnd;
            uniform float uDaylight;   // 0 = půlnoc, 1 = poledne
            uniform float uAmbient;    // aby ani úplná tma nebyla černá díra
            uniform float uBrightness; // jas z nastavení, 0 = beze změny (viz Options.brighten)

            out vec4 fragColor;

            void main()
            {
                vec4 texel = texture(uAtlas, vUv);

                float light = max(max(vSky * uDaylight, vBlock), uAmbient);

                // Jas zvedá hlavně tmu a osvětlené stěny skoro nechá - stínování
                // stěn je zapečené ve světle vrcholu. Nenastavený uniform je 0
                // (náhled v labu), a pak se nemění nic.
                float dark = 1.0 - light;
                light += 0.25 * uBrightness * dark * dark * dark * dark;
                vec3 color = texel.rgb * light;

                // Lineární mlha, stejný vzorec jako mělo staré GL_LINEAR:
                // 1.0 = plná barva bloku, 0.0 = úplně přebito barvou mlhy.
                float fog = clamp((uFogEnd - vDistance) / (uFogEnd - uFogStart), 0.0, 1.0);

                // Průhlednost se bere z ALFY TEXTURY. Neprůhledné dlaždice mají
                // alfu 1, takže se pro ně nemění nic; jen voda je pod jedničkou.
                fragColor = vec4(mix(uFogColor, color, fog), texel.a);
            }
            """;

    /**
     * Blok v ruce. Vlastní matice, protože ruka není objekt ve světě - je to
     * geometrie kousek před kamerou s vlastní perspektivou.
     *
     * Světlo je JEDNO číslo pro celý model, ne na vrchol: ruka je u oka, takže
     * jí stačí osvětlení místa, kde hráč stojí. Ztmavení stěn zůstává, aby
     * kostka měla tvar.
     */
    public static final String HAND_VERTEX = """
            #version 330 core

            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aUv;
            layout (location = 2) in float aShade;

            uniform mat4 uMvp;

            out vec2 vUv;
            out float vShade;

            void main()
            {
                vUv = aUv;
                vShade = aShade;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
            """;

    public static final String HAND_FRAGMENT = """
            #version 330 core

            in vec2 vUv;
            in float vShade;

            uniform sampler2D uAtlas;
            uniform float uLight;
            uniform float uKeepAlpha;   // 1 = voda v ruce, 0 = ostatní i holá ruka

            out vec4 fragColor;

            void main()
            {
                vec4 texel = texture(uAtlas, vUv);
                // Stejné pravidlo jako ikona a svět: alfa platí jen u vody.
                fragColor = vec4(texel.rgb * vShade * uLight, mix(1.0, texel.a, uKeepAlpha));
            }
            """;

    /**
     * Praskliny na rozbíjeném bloku: textura z atlasu, ale bez světla a mlhy.
     *
     * Světlo by nedávalo smysl - praskliny nejsou povrch, jsou to čáry přes něj;
     * a mlha už je započítaná v bloku pod nimi, takže by se přidala dvakrát.
     */
    public static final String CRACK_VERTEX = """
            #version 330 core

            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aUv;

            uniform mat4 uViewProjection;
            uniform vec3 uChunkOffset;

            out vec2 vUv;

            void main()
            {
                vUv = aUv;
                gl_Position = uViewProjection * vec4(aPos + uChunkOffset, 1.0);
            }
            """;

    public static final String CRACK_FRAGMENT = """
            #version 330 core

            in vec2 vUv;

            uniform sampler2D uAtlas;

            out vec4 fragColor;

            void main()
            {
                fragColor = texture(uAtlas, vUv);
            }
            """;

    /**
     * Obrys vybraného bloku má vlastní program, i když sdílí matice se světem.
     *
     * Dřív jezdil na světovém shaderu a černou barvu si nesl ve vrcholech.
     * Jenže vertex formát světa je teď uv + odstín, takže by obrys musel mít
     * UV mířící na nějaký černý pixel v atlasu - tedy záviset na tom, co je
     * v textuře nakreslené. Vlastní shader s barvou v uniformu je čistší
     * a jeho VBO je navíc poloviční, protože nese jen pozice.
     */
    public static final String OUTLINE_VERTEX = """
            #version 330 core

            layout (location = 0) in vec3 aPos;

            uniform mat4 uViewProjection;
            uniform vec3 uChunkOffset;

            void main()
            {
                gl_Position = uViewProjection * vec4(aPos + uChunkOffset, 1.0);
            }
            """;

    public static final String OUTLINE_FRAGMENT = """
            #version 330 core

            uniform vec4 uColor;

            out vec4 fragColor;

            void main()
            {
                fragColor = uColor;
            }
            """;

    /**
     * HUD kreslí v pixelech obrazovky. Dřív to řešilo glOrtho, v core profilu
     * se převod do clip space musí udělat ručně - je to jen přeškálování
     * z <0, šířka> na <-1, 1>.
     *
     * Barva je ATRIBUT VRCHOLU, ne uniform. Kvůli přechodům: gradient se dá
     * udělat jedině tak, že se barva interpoluje mezi vrcholy. Uniform by
     * znamenal jednu barvu na celý tvar, takže by každý přechod potřeboval
     * vlastní shader nebo rozdělení na pruhy.
     */
    public static final String HUD_VERTEX = """
            #version 330 core

            layout (location = 0) in vec2 aPos;    // v pixelech, (0,0) vlevo dole
            layout (location = 1) in vec4 aColor;  // RGBA, míchá se mezi vrcholy

            uniform vec2 uScreenSize;

            out vec4 vColor;

            void main()
            {
                vColor = aColor;

                vec2 ndc = (aPos / uScreenSize) * 2.0 - 1.0;
                gl_Position = vec4(ndc, 0.0, 1.0);
            }
            """;

    public static final String HUD_FRAGMENT = """
            #version 330 core

            in vec4 vColor;

            out vec4 fragColor;

            void main()
            {
                fragColor = vColor;
            }
            """;

    /**
     * Texturovaný 2D quad - dlaždicované pozadí menu a obrázky v labu (ImageRenderer).
     *
     * UV se schválně nechává přetéct za 1.0: textura má GL_REPEAT, takže
     * u = šířka obrazovky / velikost dlaždice vyskládá dlaždice přes celou
     * plochu jediným quadem. Kreslit je po jedné by bylo stovky draw callů.
     */
    public static final String UI_TEXTURED_VERTEX = """
            #version 330 core

            layout (location = 0) in vec2 aPos;   // v pixelech, (0,0) vlevo dole
            layout (location = 1) in vec2 aUv;    // klidně > 1, dlaždice se opakuje

            uniform vec2 uScreenSize;

            out vec2 vUv;

            void main()
            {
                vUv = aUv;

                vec2 ndc = (aPos / uScreenSize) * 2.0 - 1.0;
                gl_Position = vec4(ndc, 0.0, 1.0);
            }
            """;

    public static final String UI_TEXTURED_FRAGMENT = """
            #version 330 core

            in vec2 vUv;

            uniform sampler2D uTexture;
            uniform vec4 uTint;        // násobí se s texturou, tj. ztmavuje

            out vec4 fragColor;

            void main()
            {
                fragColor = texture(uTexture, vUv) * uTint;
            }
            """;

    /**
     * Ikona bloku v UI: texturovaný kosodélník s vlastním ztmavením stěny.
     *
     * Vlastní program, i když se od světového liší jen chybějící mlhou
     * a projekcí - ten pracuje ve 3D s maticí, tenhle v pixelech obrazovky.
     */
    public static final String UI_BLOCK_VERTEX = """
            #version 330 core

            layout (location = 0) in vec2 aPos;    // v pixelech, (0,0) vlevo dole
            layout (location = 1) in vec2 aUv;
            layout (location = 2) in float aShade;
            layout (location = 3) in float aKeepAlpha;   // 1 = voda a předměty, 0 = ostatní
            layout (location = 4) in float aSource;      // 0 = atlas bloků, 1 = atlas předmětů

            uniform vec2 uScreenSize;

            out vec2 vUv;
            out float vShade;
            out float vKeepAlpha;
            out float vSource;

            void main()
            {
                vUv = aUv;
                vShade = aShade;
                vKeepAlpha = aKeepAlpha;
                vSource = aSource;

                vec2 ndc = (aPos / uScreenSize) * 2.0 - 1.0;
                gl_Position = vec4(ndc, 0.0, 1.0);
            }
            """;

    public static final String UI_BLOCK_FRAGMENT = """
            #version 330 core

            in vec2 vUv;
            in float vShade;
            in float vKeepAlpha;
            in float vSource;

            uniform sampler2D uAtlas;
            uniform sampler2D uItems;

            out vec4 fragColor;

            void main()
            {
                // Oba atlasy mají tutéž mřížku, takže UV platí v obou.
                vec4 texel = vSource > 0.5 ? texture(uItems, vUv) : texture(uAtlas, vUv);
                // Alfa jen u bloku, který je průhledný i ve světě (voda).
                // Ostatní jdou ve světě neprůhledným průchodem, kde se alfa
                // zahodí - průhledný pixel tam má svou barvu, tak i tady.
                fragColor = vec4(texel.rgb * vShade, mix(1.0, texel.a, vKeepAlpha));
            }
            """;

    public static final String TEXT_VERTEX = """
            #version 330 core

            layout (location = 0) in vec2 aPos;    // v pixelech, (0,0) vlevo dole
            layout (location = 1) in vec2 aUv;
            layout (location = 2) in vec4 aColor;  // barva je ve VRCHOLU, ne v uniformu

            uniform vec2 uScreenSize;

            out vec2 vUv;
            out vec4 vColor;

            void main()
            {
                vUv = aUv;
                vColor = aColor;
                vec2 ndc = (aPos / uScreenSize) * 2.0 - 1.0;
                gl_Position = vec4(ndc, 0.0, 1.0);
            }
            """;

    /**
     * Atlas fontu drží jen průhlednost glyfu v jediném kanálu (GL_RED), ne barvu.
     * Barva se dodá ve vrcholu, takže jeden atlas obslouží text jakékoliv barvy.
     *
     * ⚠️ Dřív to byl UNIFORM. Uniform se ale mění mezi draw cally, takže každý
     * řádek textu - a každý jeho stín - musel být vlastní draw call. Lab jich
     * tak měl přes třicet jen na texty. Barva ve vrcholu dovolí sesypat celou
     * obrazovku textu do jedné dávky; viz TextRenderer.
     */
    public static final String TEXT_FRAGMENT = """
            #version 330 core

            in vec2 vUv;
            in vec4 vColor;

            uniform sampler2D uFont;

            out vec4 fragColor;

            void main()
            {
                float alpha = texture(uFont, vUv).r;
                fragColor = vec4(vColor.rgb, vColor.a * alpha);
            }
            """;

    private Shaders() {}
}
