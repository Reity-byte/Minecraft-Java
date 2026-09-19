# Minecraft-Claude — stav a architektura

Voxelový engine v Javě na LWJGL. Tenhle soubor je referenční shrnutí: co kde je,
proč to tak je, a co je změřené. Aktualizovat při větších změnách.

---

## Technologie

| | |
|---|---|
| Java | 17 (`maven.compiler.*`), projektový JDK v IntelliJ je **26** |
| LWJGL | 3.3.3 — `lwjgl`, `lwjgl-glfw`, `lwjgl-opengl` + natives win/linux/macos |
| JOML | 1.10.8 — matice a vektory |
| OpenGL | **3.3 core profile** — žádná fixed-function pipeline |
| Fonty | **AWT** (`BufferedImage` + `Graphics2D`), headless — žádná další závislost |

⚠️ **LWJGL 3.3.3 hlásí při startu `Unsupported JNI version detected`** — je starší než JDK 26.
Zatím běží, ale upgrade na 3.3.4+ je jednořádková změna v `pom.xml`.

## Build a spuštění

`mvn` ani JDK 26 nejsou na PATH. Z IntelliJ stačí zelená šipka; z terminálu:

```bash
export JAVA_HOME="/c/Users/Lukášek/.jdks/openjdk-26.0.2.1"
"/c/Program Files/JetBrains/IntelliJ IDEA 2026.2.2/plugins/maven-plugin/lib/maven3/bin/mvn" -B compile
```

V Git Bashi je nutné classpath převádět `cygpath -w` a spojovat středníkem.

## Testy

`src/test/java/mc/` — **662 kontrol**, žádný JUnit, obyčejné `main()` třídy.
Spustit `mc.AllTests` (zelená šipka v IntelliJ) nebo:

```bash
mvn -B test-compile
java -cp "target/classes;target/test-classes;<lwjgl+joml jars>" mc.AllTests
```

| Test | Co hlídá |
|---|---|
| `RayTest` | DDA raycast: normály ve všech 6 směrech, dosah, start uvnitř bloku, záporné souřadnice |
| `ChunkTest` | Chunk systém, převod souřadnic v záporných číslech, vrstvy terénu, rozsah FBM, load/unload |
| `MeshTest` | Mesher proti **nezávislému naivnímu přepočtu stěn** + měření rychlosti |
| `PhysicsTest` | Gravitace, výška skoku, kolize po osách, rohy, tunelování, let, noclip |
| `SwingTest` | Máchnutí rukou: průběh křivky, délka, **držené tlačítko ho nerestartuje** |
| `MiningTest` | Doba kopání podle tvrdosti, **přepnutí cíle vynuluje postup**, puštění tlačítka, stádia prasklin, kam jde vytěžený blok (inventář, rozdělaná hromádka, **při plném inventáři na zem**) |
| `MenuTest` | Hit-testing tlačítek: pořadí, kraje, mezery, překlopení y z GLFW, změna velikosti okna |
| `CaveTest` | Jeskyně (podíl výkopu, **šířka chodeb**, propojenost, netknutý povrch, dno světa) a rudy (četnost, hloubky, shlukování, záporné souřadnice) |
| `InventoryTest` | Hromádky, slévání při sběru, přetečení, recepty (i posunuté v mřížce), klikání myší, návrat obsahu při zavření, **shift-klik** (prázdný i plný cíl, přetečení, mřížka, výstup, crafting table), **tažení myší** (rovnoměrně i po jednom, zbytek v ruce, přeskočené sloty, zrušení druhým tlačítkem) |
| `DroppedItemTest` | Předměty na zemi: dopad, stabilní ležení, tunelování, zeď, tření, voda, **vytlačení z položeného bloku**, vyhození z ruky, **zpoždění a dosah sběru**, slévání při sběru, plný a skoro plný inventář, zánik (`LIFETIME`, zahozený sloupec), mesh relativní ke kameře a jeho světlo |
| `WaterTest` | Zaplavení po hladinu, suché jeskyně, pravidla viditelnosti stěn (ručně spočítané), suchý spawn, plavání |
| `SaveTest` | Ukládání: přežití změn přes unload sloupce, round-trip na disk, poškozený soubor, záporné souřadnice, obousměrnost `columnIndex` |
| `AsyncTest` | Async generování: shoda se synchronním blok po bloku, nejhorší `update()` při chůzi, omezený počet sloupců, kritický okruh, `shutdown()` |
| `LightTest` | Šíření slunečního i blokového světla, **odebrání světla** (zhasnutá pochodeň, ucpaná díra), prázdná sekce po položení bloku, cyklus dne a noci |
| `SkyTest` | Geometrie oblohy: **orientace stěn** (jinak je culling zahodí), poloměr, slunce proti měsíci, rozptyl hvězd |
| `ModelTest` | Nekrychlové modely: tři různé „pevnosti", vnitřní stěny se nezahazují, blok za pochodní nezmizí, kolize, recepty |
| `TreeTest` | Hustota, stromy jen na trávě, **úplnost korun přes hranice chunků**, kmen stojí na zemi, řetěz kmen→prkna→stůl |
| `AtlasTest` | Mapování blok+stěna → dlaždice, UV uvnitř atlasu, půltexelové zúžení, obsah a determinismus textur |
| `PlayerModelTest` | Animace: rozmach podle rychlosti, **opačná fáze nohou**, ruka proti noze, délka kroku, strop při letu, **nezávislost na FPS**, pohupování, máchnutí z `HandSwing`, držení. Model: rozměry jako hitbox, **pravá ruka vpravo, obličej vepředu**, končetiny v póze, držený blok u pěsti, odstín podle směru ve světě. Skin: každá stěna míří do vybarvené části |
| `CameraTest` | Pořadí pohledů F5, poloha zezadu i zepředu, směr pohledu a matice, **zkrácení o zeď i podlahu** s poloměrem kamery, přesná vzdálenost k rovině stěny, oči v bloku |

**Testovat jde všechno kromě renderu** — `World`, `Player`, `Raycaster`, `ChunkMesh.build()`,
`Menu.buttonAt()`, `BlockAtlas`, `Textures.blockAtlasPixels()`, `DroppedItems`,
`DroppedItemMesh.build()`, `PlayerAnimation`, `PlayerModelMesh.build()`,
`Textures.playerSkinPixels()` ani `Camera.follow()` nesahají na GL. Myš v `ContainerScreen`
(klik, shift-klik, tažení) taky ne — na GL sahá jen jeho kreslení.

⚠️ **Testy volají `world.updateBlocking()`, ne `update()`** — ta je od zavedení worker vlákna
asynchronní a po návratu ještě žádný sloupec existovat nemusí. Blokující varianta si chybějící
sloupce dogeneruje na volajícím vlákně, takže testuje ten samý generátor, jen bez čekání
na frontu. Chyby v shaderech odhalí až spuštění (`ShaderProgram` vypíše info log).

Existoval ještě `FontTest`, který renderoval atlas do PNG a ověřoval, že headless AWT
opravdu kreslí glyfy (a ne prázdno). Splnil jednorázový účel, v repu není.

---

## Mapa tříd

**Data světa (bez GL)**
- `Chunk` — sekce 16³, plochý `byte[4096]`, `solidCount`, líně alokované světlo
- `LightEngine` — šíření slunečního i blokového světla; **bez GL**
- `DayCycle` — denní doba, síla slunce, barva oblohy, otočení oblohy; **bez GL**
- `SkyRenderer` — slunce, měsíc a hvězdy jako pevná skořápka kolem počátku
- `HeldItemRenderer` — blok v ruce, ve vlastní perspektivě před kamerou
- `ChunkColumn` — 8 sekcí nad sebou, líně alokované (prázdná sekce = `null`)
- `World` — `HashMap<Long, ChunkColumn>`, generování terénu i podzemí na worker vlákně, load/unload, dirty sekce
- `SimplexNoise` — 2D simplex (výšky) a 3D simplex (jeskyně), pevný seed `12345`
- `WorldStorage` — uložení a načtení rozdílu proti generátoru; **bez GL**

**Render**
- `WorldRenderer` — shader, cache meshů, fronta přestaveb, obrys bloku
- `ChunkMesh` — bloky → trojúhelníky → VBO; vertex `pozice(3) + uv(2) + odstín(1)`;
  dvě sady stěn (neprůhledné + průhledné) v jednom bufferu za sebou
- `BlockAtlas` — blok + stěna → dlaždice a její UV; **bez GL**, aby šel mesher testovat
- `BlockModels` — tvar bloku jako seznam kvádrů; **bez GL**
- `ShaderProgram`, `Shaders` — obal nad GLSL + zdrojáky
- `Camera` — yaw/pitch → view matice (JOML); pohledy F5 a kamera třetí osoby s kolizí
- `PlayerModelMesh` — postava z kvádrů v póze → trojúhelníky pro světový shader; `build()` **bez GL**

**Inventář a crafting (bez GL)**
- `ItemStack` — neměnná hromádka: blok + počet
- `Container` — mřížka slotů s pravidly slévání; **tohle je ta znovupoužitelná část**
- `Inventory` — kontejner hráče, sloty 0–8 hotbar, 9–35 batoh; shift-klik (`quickMove`)
- `Recipes` — tvarované i bezetvarové recepty, hledané kdekoliv v mřížce

**2D vrstva**
- `Gui` — celočíselné měřítko UI a zarovnání na GUI pixel
- `Palette` — ploché barvy UI na jednom místě, aby HUD a menu vypadaly jako jedna věc
- `Renderer2D` — obdélníky, rámečky, bevel, libovolné čtyřúhelníky; sdílí HUD i menu
- `Texture` — RGBA textura, `GL_NEAREST`
- `Textures` — procedurální dlaždice, atlas bloků a placeholder skin postavy (jediné místo výměny skinu)
- `BackgroundRenderer` — dlaždicované pozadí hlavního menu, vlastní texturovaný shader
- `FontAtlas` — ASCII 32–126 → jednokanálová textura (`GL_RED`), bez antialiasingu
- `TextRenderer` — sazba textu, počátek vlevo nahoře, kreslí v celočíselném měřítku
- `Hud` — zaměřovač, hotbar s izometrickými kostkami, ladicí výpis, loading screen
- `Menu` — tlačítka s bevelem, animované zvýraznění, hit-testing
- `ContainerScreen` — kreslení a myš (klik, shift-klik, tažení) nad **seznamem mřížek**; jedna třída pro inventář i crafting table
- `BlockIcon` — izometrická kostka bloku, sdílená hotbarem i sloty

**Hra**
- `Player` — hitbox, gravitace, kolize
- `Mining` — postup rozbíjení bloku a kam jde vytěžený kus (`harvest`); **bez GL**
- `DroppedItem` — jedna hromádka ležící ve světě: poloha, rychlost, AABB kolize; **bez GL**
- `DroppedItems` — všechny položky na zemi: vyhození, sběr, zánik; **bez GL**
- `DroppedItemMesh` — položky na zemi → trojúhelníky pro světový shader; `build()` **bez GL**
- `HandSwing` — máchnutí rukou; **bez GL**
- `PlayerAnimation` — chůze, klid a máchnutí ve třetí osobě → `PlayerPose` (úhly kloubů); **bez GL**
- `Raycaster` — DDA (Amanatides–Woo)
- `GameState` — MAIN_MENU / CREATING_WORLD / PLAYING / PAUSED
- `Main` — okno, vstup, stavový automat

---

## Klíčová rozhodnutí a proč

**Chunk 16×16×16, ne svislý sloupec.** Přestavba meshe projde 4096 buněk místo 65 536,
prázdné sekce nad terénem se přeskočí úplně, frustum culling by měl jemnější zrno.

**Plochý `byte[4096]` místo `byte[16][16][16]`.** Pole polí je v Javě 273 objektů (~9,5 KB
na 4 KB dat) roztroušených po haldě, se 3 dereferencemi na čtení. Mesher dělá ~24 000 čtení
na sekci — na souvislém poli jdou přes cache. Index: `(y << 8) | (z << 4) | x`, pořadí y-z-x,
takže smyčka s x uvnitř čte sekvenčně.

**Nekonečno v X/Z, omezená výška (128).** Bounded Y zjednodušuje generování, osvětlení
i ukládání. Klíč do mapy: `((long) cx << 32) | (cz & 0xFFFFFFFFL)`.

**⚠️ Převod souřadnic MUSÍ používat `>>` a `&`, ne `/` a `%`.** `-1 / 16 = 0`, ale `-1 >> 4 = -1`.
S dělením vznikne 16 bloků široký šev přesně na ose x=0 a z=0. `ChunkTest` to hlídá.

**Hystereze při uvolňování.** `unloadRadius (10) > loadRadius (8)` — jinak by se sloupec na
hranici pořád dokola generoval a mazal.

**Sekce se nemešuje, dokud nejsou načtení všichni 4 sousední sloupce.** Alternativa (invalidovat
sousedy při dogenerování) je křehká — jedna zapomenutá invalidace = díra v terénu. Tohle je
konstrukčně nemožné rozbít. Funguje jen proto, že `loadRadius × 16` > `renderDistance`.

**Souřadnice vrcholů jsou relativní ke kameře, ne světové.** Vrcholy 0–16 vůči počátku chunku,
uniform `uChunkOffset` = `počátek chunku − pozice kamery`, spočítaný na CPU v `double`.
Float má 24bitovou mantisu: ve světové souřadnici 100 000 je nejmenší krok ~8 mm a geometrie
by se třásla. **Proto je oko view matice v počátku** (`Camera.viewMatrix` má jen rotaci,
žádnou translaci) — kdyby tam byl posun, započítal by se dvakrát.

**Rozpočet přestaveb je časový, ne počtem sekcí.** Počet by se na pomalém stroji do framu
nevešel a na rychlém by se nevyužil. Kontroluje se před každou stavbou → přetažení max
o jednu sekci. Fronta se řadí **od nejbližší** (jinak se svět dosypává v náhodných ostrovech,
protože pořadí `HashMap` je libovolné) a **každý frame se zahodí a nasbírá znovu** — je to
samoopravné při pohybu a nemůže v ní viset odkaz na uvolněný sloupec.

**Změny bloků obcházejí rozpočet záměrně.** Rozbití označí max 4 sekce (~1,5 ms) a chce to
okamžitou odezvu. Face culling kouká jen na 6 stěnových sousedů, ne na diagonály, takže blok
na rohu sekce dotkne nejvýš 3 sousedních — ne 26.

**Kolize se řeší po jedné ose, vždy celým hitboxem.** Posunout všechny tři naráz a pak testovat
nejde — nepoznáš, o kterou stěnu se zarazit, a hráč se zasekává v rozích místo klouzání.

**Pohyb se dělí na kroky ≤ 0,4 bloku.** Test kolize kontroluje jen cílovou polohu, ne cestu
k ní; bez dělení by pád terminální rychlostí proletěl skrz jednovrstvou podlahu.
K tomu `dt` se stropem 0,05 s — jinak by jedno zaseknutí poslalo hráče skrz zeď.

**⚠️ Překryv buňky s intervalem je `floor(a)` až `ceil(b)-1`, ne `floor(b)`.** S `floor(b)` se
u hráče stojícího přesně na hraně testuje i buňka, které se jen dotýká, a hráč se zasekne
sám o sebe.

**UI texty anglicky.** Atlas je ASCII 32–126, česká diakritika by padla na fallback `?`.
Komentáře v kódu zůstávají česky.

### Hranaté UI

**⚠️ UI se navrhuje v GUI pixelech a zvětšuje CELÝM číslem.** `Gui.scale()` vybere 2×/3×/4×
podle toho, kolikrát se do okna vejde 320×240. Jeden GUI pixel je pak přesně N×N stejných
obrazovkových pixelů. **Tohle dělá „blocky" vzhled víc než cokoliv jiného** — kdyby se
měřítko počítalo plovoucí čárkou, hrany by padly mezi pixely a rozmazaly se. Vycentrování
proto vždycky projde `Gui.snap()`; `(šířka − prvek) / 2` skoro nikdy nevyjde na celý GUI pixel.

**Plastičnost dělá bevel, ne přechod.** Světlá hrana nahoře a vlevo, tmavá dole a vpravo,
kolem černý obrys — přesně to, co je v `widgets.png` Minecraftu. Jde to nakreslit obdélníky,
takže to **nepotřebuje texturu**. Přechod přes celou plochu dělá pravý opak a zbyl jen na
ztmavení scény za menu pauzy.

**⚠️ Rámečky nejsou obrysy.** `glLineWidth > 1` není v core profilu zaručeně podporovaný
a ovladače se v tom liší, takže tloušťka by byla loterie. Rámeček je proto čtveřice plných
pruhů. Jediný zbývající `glLineWidth` je obrys bloku ve `WorldRenderer` — tam je degradace
na 1 pixel jen kosmetická.

**Barva v 2D vrstvě je atribut vrcholu, ne uniform.** `Renderer2D` má vertex formát
`pozice(2) + RGBA(4)`. Zbylo to kvůli ztmavení pozadí, což je jediný přechod v UI.

**⚠️ Tvary v invertujícím míchání se nesmí překrývat.** Dvojí inverze vrátí původní barvu.
Zaměřovač je proto čtyři ramena s mezerou uprostřed, ne kříž — jinak by průsečík zmizel.

**Ikony v hotbaru jsou izometrické kostky ze tří kosodélníků.** Odstíny stěn jsou schválně
stejné konstanty jako `SHADE_*` v `ChunkMesh`, takže ikona sedí s tím, jak blok vypadá
ve světě. Tvar nese informaci „tohle je blok" — textura na to potřeba není.

### Pixelové písmo

**⚠️ Antialiasing VYPNUTÝ + `GL_NEAREST` + celočíselné zvětšení.** Všechny tři najednou,
jinak to nefunguje: s antialiasingem má glyf poloprůhledné okraje, které se zvětšením
roztáhnou do rozmazaného lemu; s `GL_LINEAR` se texely průměrují. Kterákoliv z těch věcí
zapnutá = hladké písmo, a je jedno, jak ostrý je zbytek rozhraní.

**Velikost fontu je 9 px a je to změřené, ne odhad.** Při 7 a 8 px se `SansSerif` bez
antialiasingu slévá (`Quit` vyjde jako `Quft`), od 9 px jsou tahy oddělené. Míň = nečitelné,
víc = zbytečně velké.

**⚠️ Mezi znaky musí být 1 px rozestup (`LETTER_SPACING`).** Advance z `FontMetrics` je
u malých velikostí bez antialiasingu často přesně tak široký jako samotný tvar písmene —
`M` v devítce zabírá celých svých 7 px — takže následující `i` začne hned vedle a obojí
splyne (`Minecraft` se čte jako `Nnecraft`). Není to chyba sazby po glyfech: nativní AWT
`drawString` celého řetězce vypadá stejně. Jeden pixel stačí a je to i míra Minecraftu;
dva už text roztrhají. `textWidth()` ten rozestup za posledním znakem odečítá, jinak by
vycentrovaný text seděl o půl pixelu vlevo.

**Nadpisy jsou ten samý atlas, jen větším měřítkem.** U pixelového písma je zvětšování
právě ten mechanismus, který se má použít. Druhý atlas by znamenal dvě různá zrna pixelů
v jednom rozhraní.

**Stín textu je posunutý o jeden GUI pixel a je krycí**, ne poloprůhledná čerň — je to
čtvrtina barvy písma, jako v Minecraftu.

### Textury bloků

**Ztmavení stěny je samostatný násobič, ne zapečené v barvě.** Dokud byl blok jednobarevný,
šlo ztmavení předpočítat na CPU a poslat jako barvu vrcholu. S texturou to nejde — barva
se čte až ve fragment shaderu, takže se musí přenést, čím ji vynásobit. Vertex formát tím
zůstal **stejně velký**: dřív `pozice(3) + barva(3)`, teď `pozice(3) + uv(2) + odstín(1)`.
Přechod na textury nestál ani bajt paměti meshů.

**⚠️ UV dlaždice je zúžené o půl texelu (`BlockAtlas.INSET`).** Bez toho prosakuje sousední
dlaždice na hranách bloků: UV pravého okraje dlaždice se rovná UV levého okraje té další
a interpolace přes stěnu na té hodnotě klidně skončí. Zúžení zastaví rozsah ve středech
krajních texelů — při `GL_NEAREST` je pořád dostupných všech 16, ale mimo dlaždici se
sáhnout nedá.

**Wrap atlasu je `CLAMP_TO_EDGE`, ne `REPEAT`.** U atlasu by opakování znamenalo, že UV
kousek za okrajem sáhne na protilehlou stranu atlasu, tedy do úplně jiné dlaždice.
(Dlaždice hlíny na pozadí menu má naopak `REPEAT` — tam je opakování celý smysl.)

**Šest stěn se dělí jen o dva UV vzorce.** `UV_A` a `UV_B` v `ChunkMesh` — liší se jen
směrem obcházení obvodu, což je dané pořadím vrcholů zvoleným kvůli backface cullingu.
U bočních stěn je `t` výška, takže textura stojí správně.

**Obrys bloku má vlastní shader.** Dřív jezdil na světovém a černou barvu si nesl ve
vrcholech; s `uv + odstín` by musel mířit na nějaký černý pixel v atlasu, tedy záviset
na tom, co je v textuře nakreslené. Vlastní program má navíc poloviční VBO — jen pozice.

**`World.colorFor()` zůstává.** Svět už ho nepoužívá, ale hotbar z něj bere barvu
izometrických kostek. Renderovat do ikony skutečnou texturu by znamenalo tahat atlas
do 2D vrstvy kvůli pěti čtverečkům.

### Podzemí

**Jeskyně vznikají PRŮNIKEM dvou 3D šumových polí, ne jedním.** Množina `|šum| < práh` je
okolí nulové plochy, tedy zvlněná **deska** — jedno pole by udělalo rozlehlé pukliny přes
celý svět. Průnik dvou takových desek je křivka, a ta zesílená na pár bloků dá propletené
chodby. Změřeno: vykope to 5,3 % horniny a 97 % vykopaných bloků má aspoň dva vykopané
sousedy, tedy jde opravdu o chodby a ne o rozsypané bubliny.

**⚠️ Šířku chodeb řídí FREKVENCE, ne práh.** Šířka je zhruba práh dělený gradientem šumu
a gradient škáluje s frekvencí — snížení frekvence tedy chodby rozšíří, **aniž by vykopalo
víc horniny**. Zvýšení prahu zvětší i objem. Když je potřeba „větší jeskyně, ne víc jeskyní",
sahá se na frekvenci. Změřeno na první verzi: při 0,028 byl průměrný vodorovný úsek 2,66
bloku a 60 % z nich mělo jeden až dva bloky — chodby jako chapadla. Po přechodu na 0,018
je průměr 5,3 bloku a jednoblokových skulin je 19 %.

**Práh se mění s hloubkou.** Mělko úzké chodby (0,12), hluboko síně (0,20), lineárně mezi
`CAVE_MIN_Y` a `CAVE_SHALLOW_Y`. Je to zadarmo — žádné další volání šumu — a dává sestupu
do hloubky smysl. Změřeno: 4,6 bloku v y 35–49 proti 5,7 bloku v y 3–20.

**První šumové pole má early-out.** Podmínku splní jen pár procent bloků, takže druhé volání
šumu u naprosté většiny odpadne. Bez toho by generování sloupce stálo skoro dvojnásobek.

**⚠️ Kope se jen v kameni a jen nad `y = 3`.** Povrchové vrstvy zůstávají netknuté, takže
nemůže vzniknout díra v trávníku ani tráva visící ve vzduchu, a dnem světa se nedá propadnout
ven. Cena je, že jeskyně **nemají vchody** — musíš se k nim dokopat. `CaveTest` obojí hlídá.

**Rudné žíly se počítají z mřížky 4×4×4, ne šumem.** Hash rozhodne, které buňky žílu nesou;
uvnitř se vyplní zhruba koule kolem středu a okraj se hashem roztřepí. Dva důvody proti šumu:
je to řádově levnější (dva hashe místo volání simplexu) a **závisí jen na světové pozici**,
takže žíly na hranicích chunků navazují samy od sebe — není potřeba nic dogenerovávat
do sousedů, jak to musí dělat Minecraft.

**⚠️ I tady platí `>>` místo `/`.** Buňka kolem nuly by při dělení byla dvakrát široká.
Stejná past jako u převodu na chunky, jen o patro níž.

**Vzácnost rud i velikost jeskyní jsou doladěné podle měření, ne odhadem.** První hodnoty
daly 37 uhlí na 1000 kamene, což byly celé stěny uhlí; po korekci na 9,2 ‰ zas bylo rudy
málo. Teď je to **15,9 ‰ uhlí a 5,5 ‰ železa**. `CaveTest` všechna ta čísla vypisuje —
podíl výkopu, rozložení šířek chodeb i četnost rud — takže se po každé změně prahů dají
přečíst místo odhadovat.

### Asynchronní generování

**Worker DOSTÁVÁ souřadnice a VRACÍ hotové sloupce, na mapu `columns` nesahá.** Dělba práce
je záměrně takhle přísná: nepotřebuje pak žádný zámek a nemůže vzniknout stav, kdy hlavní
vlákno čte sloupec, který se zrovna plní. Funguje to jen proto, že `generateColumn()` je
**čistá funkce souřadnic** — šum má pevný seed a neměnné tabulky. `AsyncTest` porovnává
async a synchronní svět blok po bloku, takže kdyby do generátoru někdo přidal sdílený stav,
je to hned vidět.

**⚠️ `CRITICAL_RADIUS` je pojistka proti propadnutí skrz zem.** Fyzika se ptá na zem v tom
samém framu, ve kterém se `update()` zavolá, takže 3×3 sloupce kolem hráče se dogenerují
synchronně. Za běžné chůze se to nikdy neuplatní — prstenec, který přibyde po překročení
hranice chunku, je 8 chunků daleko. Vystřelí jen při spawnu.

**Fronta je omezená (`MAX_IN_FLIGHT`) a řadí se od nejbližší.** Omezení drží frontu čerstvou,
když se hráč rozejde jinam; řazení je ze stejného důvodu jako u fronty meshů — bez něj se
svět dosypává v náhodných ostrovech, protože pořadí `HashMap` je libovolné.

**Hotový sloupec, který mezitím vyjel z dosahu, se zahodí.** Uložit ho a vzápětí smazat
by jen nafukovalo mapu.

**⚠️ `update()` se musí volat KAŽDÝ frame — práci jen zadá a vyzvedne hotové sloupce.**
Kdo ji zavolá jednou a čeká, že je hotovo, uvázne: sloupce z fronty nemá kdo vybrat, protože
je vybírá právě `update()`. Loading screen na tohle jednou doplatil (visel na 1 %) — měl
volání uvnitř `if(!worldGenerated)`, což se synchronním generováním fungovalo. `AsyncTest`
tu past hlídá.

**Loading screen počítá i chybějící sloupce, nejen frontu meshů.** Bez toho by skončil hned
v prvním framu: fronta meshů je zpočátku prázdná právě proto, že ještě není z čeho stavět.
Fáze se ale **nesčítají do jednoho čísla** — fronta meshů roste teprve s tím, jak sloupce
přibývají, takže by celkový počet během loadingu narůstal a pruh by couval. Každá fáze má
vlastní maximum i vlastní úsek pruhu (`COLUMN_PHASE`). Změřeno: generování 289 sloupců
při `loadRadius = 8` trvá ~313 ms.

**⚠️ Nový svět musí zastavit ten starý.** `World.shutdown()` se volá v `startWorldCreation()`
i při ukončení hry — jinak by po každém „Create World" přibylo jedno běžící generující vlákno.

### Perzistence

**⚠️ Neukládá se svět, ale ROZDÍL proti generátoru.** Generátor je čistá funkce souřadnic
s pevným seedem, takže se terén dá kdykoliv dopočítat znovu — na disk stačí to, co hráč
změnil. Změřeno: **64 bajtů na tři změny**. Načtení je „vygeneruj a přepiš změny".

**Ta samá mapa opravila i starší chybu.** Než změny existovaly jako samostatný záznam,
stačilo odejít za `unloadRadius` a vrátit se: sloupec se vygeneroval znovu z šumu a všechno
postavené bylo pryč. Teď si je sloupec při generování vezme sám.

**⚠️ Změny se dopisují na HLAVNÍM vlákně, ne v `generateColumn()`.** Ta běží na workeru
a mapa změn se za běhu mění (hráč pořád něco boří), takže by ji worker četl souběžně
se zápisem. Nasazují se proto až v `insert()`, když sloupec vstupuje mezi načtené.

**`GENERATOR_VERSION` v hlavičce souboru.** Když se přeladí generátor (výšky, prahy jeskyní,
četnost rud), starý uložený svět bude mít pod hráčovými stavbami jiný terén. Při neshodě
se hlásí varování, ale **svět se i tak načte** — přijít o postavené věci je horší než
posunutý terén. Zvýšit při každé změně, která posune terén.

**Chyby ukládání hru nepoloží.** `save()` vrací `false`, `load()` vrací `null` a obojí
napíše důvod na stderr. Poškozený nebo cizí soubor tedy skončí založením nového světa,
ne pádem. `SaveTest` na to střílí useknutým i cizím souborem.

**Ukládá se při odchodu do menu i při zavření okna.** Zavřít okno uprostřed hry je běžný
způsob, jak skončit, takže spoléhat jen na tlačítko v pauze by znamenalo tichou ztrátu.

### Voda

**⚠️ Voda není pevná ani neprůhledná, a rozhoduje to o třech věcech naráz.**
`World.isOpaque()` je jediné místo, kde je to napsané: mesher za vodou nesmí zahodit stěny
sousedních bloků (jinak zmizí dno jezera), hráč jí propadá (plavání řeší `Player`) a paprsek
jí prochází, takže se nedá zaměřit ani rozbít.

**Pravidla viditelnosti stěn se liší podle materiálu.** Neprůhledný blok kreslí stěnu ke
všemu, co nezakrývá — tedy i k vodě. Voda kreslí stěnu **jen ke vzduchu**: k sousední vodě
by udělala mřížku uvnitř jezera, k pevnému bloku by ležela přesně na jeho stěně a blikala
by s ním. `WaterTest` to ověřuje ručně spočítaným případem (kámen obklopený vodou = 36 stěn).

**Průhledné stěny mají v `ChunkMesh` vlastní pole a kreslí se druhým průchodem.** Tři věci,
bez kterých to nevypadá správně: kreslí se **od nejvzdálenější sekce** (míchání závisí na
pořadí), se **zápisem do depth bufferu vypnutým** (voda si nesmí zaclonit vodu za sebou,
test hloubky ale zůstává), a **až po všech neprůhledných sekcích** (jinak by se voda smíchala
s barvou oblohy místo s terénem, který se nakreslí až po ní). Obě sady leží v jednom VBO
za sebou, takže druhý průchod nestojí druhý VAO — jen jiný offset.

**Průhlednost je v ALFĚ TEXTURY, ne v uniformu.** Fragment shader bere `texel.a`; neprůhledné
dlaždice mají alfu 1, takže se pro ně nemění nic. `AtlasTest` hlídá, že voda je jediná
dlaždice pod plnou alfou — jinde by se alfa tiše zahodila v neprůhledném průchodu.

**⚠️ Voda se lije až NAD terén, takže se jeskyně nezaplaví.** Drží to i díky pravidlu, že
se kope jen v kameni: mezi dnem jezera a nejvyšším možným stropem jeskyně jsou vždycky
vrstvy půdy. Změřeno: v podzemí není ani kapka.

**`SEA_LEVEL` je o jedna níž než `SAND_LEVEL`.** Pás písku tak vyčnívá kousek nad hladinu
a kolem jezer vznikne pláž místo trávy rovnou u vody.

**⚠️ Spawn se hledá, nebere se pevný bod.** Terén u počátku souřadnic vychází pod hladinou
(výška 53 proti hladině 55), takže hra začínala po pás ve vodě. `World.findLandSpawn()`
hledá ve čtvercích od středu ven nejbližší souš; funguje to bez vygenerovaného světa,
protože výška terénu je čistá funkce šumu.

**⚠️ Všechno ve vodě se škáluje PODÍLEM PONOŘENÍ hitboxu, ne binárním „je ve vodě".**
První verze nastavovala svislou rychlost napevno, dokud se hitbox vody aspoň dotýkal —
a protože „dotýká se" platí, dokud nejsou nad hladinou i nohy, vztlak hráče vystrčil celého
ven, tam přepnul na plnou gravitaci, hráč spadl zpátky a znovu. Skákal po hladině jako
po trampolíně.

S podílem se to ustálí samo: čím výš hráč vyplave, tím menší vztlak a tím větší gravitace,
takže existuje rovnovážná hloubka. Změřeno: rozkmit **0,067 bloku** a ustálení na 62–66 %
ponoření, tedy s hlavou nad vodou.

**Svislá rychlost se ZRYCHLUJE a tlumí, nenastavuje.** Odtud pochází setrvačnost, kterou má
voda v Minecraftu — po klesání jeden frame drženého skoku pohyb neotočí. Útlum je mocnina
`pow(drag, dt)`, ne násobek: jinak by voda byla hustší při nízkém FPS. Terminální rychlosti
z toho vypadnou samy — klesání 1,2 bloku za sekundu proti víc než deseti na suchu,
vodorovná rychlost 2,15 místo 4,30 b/s.

**Ze dna se dá odrazit i ve vodě.** Skok při `onGround` má přednost před plaváním, jinak
by se z mělčiny u břehu nedalo vyskočit na souš.

**Pod vodou se mění mlha, barva oblohy i filtr přes obrazovku — všechny tři.** Krátký dohled
dělá to hlavní, ale sám nestačí: blízké bloky mají mlhový podíl skoro nulový, takže by
zůstaly bez nádechu, a obloha v dálce by svítila modře tam, kde má být kalná voda. O tom,
jestli je kamera pod vodou, rozhoduje blok v úrovni OČÍ, ne nohou.

### Inventář a crafting

**⚠️ `Container` neví nic o kreslení, o myši ani o tom, k čemu slouží.** Je to jen pole
hromádek s pravidly slévání. Inventář hráče, crafting mřížka, výsledkový slot i budoucí
truhla nebo enchantovací stůl jsou různě velké instance **téhož**. To je celý důvod, proč
se přesuny, dělení hromádek a návrat obsahu píšou jednou.

**`ContainerScreen` není „inventář", ale SEZNAM MŘÍŽEK a jejich pozic v panelu.** Obrazovka
na E je „batoh 9×3 + hotbar 9×1 + crafting 2×2 + výsledek", crafting table je to samé
s mřížkou 3×3. Rozdíl mezi nimi je jedna tovární metoda, ne nová třída. Rozvržení je
v GUI pixelech od levého horního rohu panelu — jsou to přímo souřadnice z Minecraftu
(panel 176×166, slot 18×18), takže rozměry sedí.

**⚠️ Recept si nese svou velikost a hledá se kdekoliv v mřížce.** Bez toho by se každý
recept 2×2 musel psát znovu pro 3×3, a ještě devětkrát podle toho, kam se položí. Mimo
obdélník receptu musí být prázdno — jinak by „prkna ve čtverci a kámen navíc" pořád
vyrábělo crafting table.

**Hromádka je NEMĚNNÁ (`ItemStack` je record).** Přesouvání je pak jen výměna odkazů
a nemůže se stát, že se stejný objekt ocitne ve dvou slotech a změna v jednom se projeví
i ve druhém — nejčastější chyba u drag &amp; drop v inventáři. Prázdný slot drží `EMPTY`,
ne `null`, takže odpadne kontrola v každém kreslení i přesunu.

**Při sběru se nejdřív dolévají ROZDĚLANÉ hromádky, teprve pak se bere prázdný slot.**
Opačné pořadí rozsype inventář do jednotlivých slotů s jedním kusem.

**Suroviny se spotřebují až odebráním výsledku**, ne při složení receptu — do té doby
se dá mřížka rozmyslet a rozebrat.

**Zavření obrazovky vysype kurzor i crafting mřížku do batohu.** Bez toho by se obsah
ztratil, což je klasická díra v inventářích. Co se z kurzoru nevejde ani do batohu,
`returnItems()` vrátí a Main to vyhodí na zem — dřív se to tiše zahodilo. Co se nevejde
z mřížky, zůstává v ní: mřížka je trvalý kontejner a při dalším otevření tam bude.

**Sloty jsou ZAPUŠTĚNÉ, ne vystouplé:** tmavá hrana nahoře a vlevo, světlá dole a vpravo —
přesně opačně než tlačítko. Bez toho vypadá mřížka jako pole tlačítek místo jako přihrádky.

**⚠️ Řetěz receptů musí být dosažitelný z terénu.** Prkna se negenerují (stromy nejsou),
takže recept „4 prkna → crafting table" by sám o sobě znamenal, že se první stůl nedá
vyrobit vůbec. Proto vede cesta i přes kámen: kámen → cihly → stůl. `InventoryTest`
ten řetěz explicitně prochází.

**Shift-klik přesouvá mezi DVĚMA ČÁSTMI TÉHOŽ kontejneru, ne mezi mřížkami na obrazovce.**
`Inventory.quickMove()` ví, že 0–8 je hotbar a 9–35 batoh, a volá `Container.insert(stack, od, do)`
— to je `add()` omezené na rozsah slotů. Pravidla slévání (nejdřív rozdělané hromádky, pak
prázdný slot) tak zůstala napsaná jednou a shift-klik je stejný na E i na crafting table,
protože obě obrazovky kreslí týž inventář. Co se nevejde, zůstane ve výchozím slotu; plný cíl
znamená, že se nestane nic.

**Z crafting mřížky vrací shift-klik do inventáře, z výstupního slotu zůstává obyčejným klikem.**
Mřížka → inventář je totéž, co dělá zavření obrazovky, tedy jeden řádek. „Vyrob, kolik to jde"
z výstupu je jiná a větší věc — opakované spotřebování surovin a rozhodnutí, co dělat, když se
výsledek vejde jen napůl — a zadání ji nechtělo.

**Shift s něčím v ruce se ignoruje**, stejně jako v Minecraftu: nešlo by poznat, jestli se má
přesouvat slot pod kurzorem, nebo pokládat ruka.

**⚠️ Klik je teď ZMÁČKNUTÍ + PUŠTĚNÍ, ne jedna událost.** S prázdnou rukou se jedná hned při
zmáčknutí (braní, shift-klik). S něčím v ruce zmáčknutí jen začne tažení a o tom, co se stane,
rozhodne puštění: přes dva a víc slotů se hromádka rozdělí, jinak je to obyčejný klik na ten
jeden slot. Zmáčknout a pustit na místě proto dělá přesně to, co klikání dělalo dřív — původní
testy klikání prošly beze změny. Main posílá `press` / `drag` / `release` zvlášť.

**⚠️ Během tažení se NIC NEPŘESOUVÁ.** Sbírají se jen sloty, přes které kurzor přejel; rozdělí
se až při puštění. Kdyby se rozdělovalo průběžně, musely by se při každém dalším slotu
přerozdělovat kusy, které už leží v předchozích. Náhled (zesvětlené sloty, počty ve slotech
i zbytek na kurzoru) a skutečné rozdělení počítá tatáž funkce `dragAmount()`, takže se nemůžou
rozejít.

**Pravidla tažení jsou z Minecraftu.** Levé tlačítko: každý slot dostane `v ruce / počet slotů`
celočíselně, zbytek po dělení zůstane v ruce. Pravé: jeden kus do každého slotu. Do tažení se
nezařadí výstupní slot, slot s jiným blokem ani plný slot (vzal by si podíl, který by se do něj
nevešel), a slotů nesmí být víc než kusů v ruce — jinak by některý nedostal nic. Druhé tlačítko
během tažení tažení zruší a nic se nepřesune.

### Stromy

**⚠️ Strom se razítkuje i ze sousedních sloupců (`TREE_REACH`).** Koruna je široká 5 bloků,
takže kmen stojící až dva bloky **za** hranicí chunku do sloupce pořád zasahuje listím.
Kdyby se procházel jen vlastní sloupec, byly by na každé hranici useknuté koruny — a je to
chyba, která ve hře vypadá jako „tak proste ten strom takhle vyrostl", takže se snadno
přehlédne. `TreeTest` proto kontroluje úplnost každé koruny a hlásí, kolik stromů přes
hranici vůbec přesahuje (aby test nemohl projít tím, že žádný takový není).

**Funguje to jen proto, že `hasTree()` je čistá funkce světové pozice.** Stejný strom vyjde
identicky, ať se na něj ptá kterýkoliv ze čtyř sousedních sloupců. Je to ta samá vlastnost,
na které stojí rudné žíly.

**Jeden strom na buňku 8×8, na hashované pozici uvnitř ní.** Buňka zaručí rozestupy; kdyby
se házelo mincí pro každý blok zvlášť, stromy by rostly ve shlucích jeden na druhém.
Změřeno: jeden strom na 109 bloků povrchu.

**Kmen se razítkuje AŽ PO listí**, aby přebil listí, které mu vyšlo do cesty. Listí naopak
zapisuje jen do vzduchu, takže neprožere terén ani sousední strom.

**Listí je NEPRŮHLEDNÉ.** Průhledné by znamenalo řešit, kdy se kreslí stěna mezi dvěma listy,
a průhledný průchod by u každé koruny znásobil počet stěn. Minecraft na nízké nastavení
grafiky dělá totéž.

**Teprve stromy dokončily crafting.** Prkna do té doby neměla v terénu zdroj, takže kanonický
recept „4 prkna → crafting table" byl nedosažitelný a musela existovat oklika přes kámen.
Kmen dává 4 prkna, takže je teď dostupná i ta správná cesta.

### Tvary bloků

**Model je seznam kvádrů v souřadnicích 0–1 uvnitř bloku.** Pokryje to pochodeň, plot,
schody, desky i tlakové desky — všechno, co jde poskládat z hranolů. `ChunkMesh` už neemituje
pevnou krychli, ale projde kvádry modelu; pro plnou krychli vyjde přesně to co dřív.

**⚠️ Stěna kvádru se zahazuje JEN když leží přesně na hranici bloku.** Stěna uvnitř bloku —
třeba bok tenké pochodně — musí být vidět vždycky, i když je vedle plný kámen. Bez toho by
pochodeň postavená u zdi z té strany zmizela.

**⚠️ UV se berou z rozsahu kvádru, ne z celé dlaždice.** Tenká pochodeň si z textury vezme
jen ten pruh, který jí odpovídá, takže textura zůstane obyčejná čtvercová dlaždice a nemusí
se kreslit „na míru" modelu.

**⚠️ Jeden test „není vzduch" se rozpadl na TŘI nezávislé vlastnosti.** Dokud byly všechny
bloky plné krychle, stačil jeden; s vodou a modely už každá řídí něco jiného:

| | co řídí | voda | pochodeň | plot |
|---|---|---|---|---|
| `isOpaque` | zakrývá sousedy (mesher), později zastaví světlo | ne | ne | ne |
| `blocksMovement` | kolize hráče | ne | ne | **ano** |
| `isTargetable` | dá se zaměřit paprskem a rozbít | ne | **ano** | **ano** |

Slít je zpátky do jednoho testu znamená buď pochodeň, kterou nejde rozbít, nebo plot,
kterým se propadne — podle toho, který se vybere. `ModelTest` prochází všechny tři pro
každý blok.

**Blok s nekrychlovým modelem nesmí být neprůhledný.** Kdyby byl, zahodil by stěny sousedů
a za pochodní by byla díra do prázdna. `isOpaque()` se proto ptá i `BlockModels.isFullCube()`,
takže se na to nedá zapomenout u nového bloku.

**Co systém neumí:** šikmé plochy. Květiny a sazenice jsou v Minecraftu dva zkřížené
obdélníky, ne hranoly — na ně bude potřeba model ze čtyřúhelníků. A plot se zatím nenapojuje
na sousedy; to je model závislý na okolí, tedy další krok.

### Osvětlení

**Dva nezávislé kanály, oba 0–15.** Sluneční padá shora a v noci se ztlumí; blokové vzniká
u pochodní a svítí dál. Ve fragment shaderu se skládají **maximem, ne součtem** — se součtem
by pochodeň ve dne byla jasnější než okolí a dvě vedle sebe by přepálily obraz do běla.

**⚠️ Noc se dělá ZTLUMENÍM SLUNEČNÍHO KANÁLU V SHADERU, ne přepočtem světla ve světě.**
Uložená hodnota je „kolik sem dosáhne obloha" a je pořád stejná; jak silná obloha je, řekne
až jeden uniform. Kdyby se přepočítávalo, znamenal by každý západ slunce přestavbu všech
meshů v dohledu. Proto musí kanály zůstat oddělené až do fragment shaderu — sečíst je
na CPU by tuhle možnost zabilo.

**⚠️ ODEBRÁNÍ SVĚTLA JE TĚŽŠÍ NEŽ PŘIDÁNÍ.** Když zmizí pochodeň, nestačí jí nastavit nulu:
musí se projít celá oblast, kterou osvětlovala, vynulovat ji, a **přitom si zapamatovat každý
okraj, kde se narazí na světlo patřící někomu jinému**. Ty okraje se pak použijí jako nové
zdroje a oblast se dosvítí zpátky. Bez téhle druhé fáze zůstane po zhasnuté pochodni tmavá
díra i tam, kam dosvítí slunce nebo druhá pochodeň. `LightTest` tenhle případ zkouší přímo:
dvě pochodně, jedna zhasne, a místo po ní musí dosvítit ta druhá.

**Sluneční světlo padá dolů beze ztráty.** Jinak by se pod každou dírou ve stropě zužoval
kužel a šachta by byla po pár blocích tmavá. Stejná výjimka platí i při odebírání.

**Světlo se alokuje líně.** Sekce celá nad terénem má sluneční 15, sekce hluboko pod ním
nulu — obojí je výchozí hodnota a pole se pak vůbec nevytvoří. Alokuje se teprve tehdy, když
do sekce dosáhne světlo, které se od výchozího liší.

**⚠️ Novou sekci zakládá jen `ChunkColumn.section(y, true)`, i když ji vytváří zápis bloku.**
Holé `new Chunk()` má výchozí sluneční světlo 0. `ChunkColumn.set()` ho kdysi použilo,
takže první blok položený do prázdné sekce nad terénem zatemnil celých 16³ —
`LightEngine.blockChanged` opraví jen okolí změněného bloku a zbytek sekce zůstal černý.
Ve hře stačilo postavit sloup nad terén. Ostatní testy to nechytily, protože v nich
zastřešená místnost na y=100 měla být uvnitř tmavá stejně; `LightTest` teď zkouší
jeden blok v prázdné sekci proti nezávislému přepočtu.

**⚠️ Nasvěcování sloupce zapisuje PŘÍMO DO SLOUPCE, ne přes `World.setSkyLightAt`.**
Ta cesta u každého zápisu značí okolní sekce k přestavbě meshe — jenže při zakládání sloupce
žádný mesh ještě neexistuje. Tisíce zbytečných záznamů stály víc než celé generování terénu.
Sekce k přestavbě si během šíření sbírá `LightEngine` do vlastní množiny bez boxování
a nahlásí je najednou.

**⚠️ Fronty i množiny jsou primitivní `long[]`, ne `ArrayDeque<Long>` nebo `HashSet<Long>`.**
BFS projde po jednom položení pochodně tisíce buněk; zabalený `Long` na každou z nich
rozhoupe GC natolik, že jeho pauzy jsou větší než celý rozpočet na šíření světla. Ze stejného
důvodu nealokuje ani `requestMissing`.

**Nejvíc sloupců převzatých za frame je omezené.** Převzetí není zadarmo — sloupec se musí
nasvítit, což stojí zhruba jako jeho vygenerování.

**⚠️ Světlo se NESMÍ šířit z nenačteného sloupce.** `skyLightAt()` u nenačteného sloupce
schválně lže a vrací 15, aby na okraji dohledu nebyl černý pruh — pro mesher je to správně,
pro šíření je to zdroj světla, který neexistuje. Uzel ve frontě, jehož sloupec se mezitím
zahodil, se pak tvářil jako plné slunce a rozlil 14 do okolí, **včetně jeskyní dvacet bloků
pod zemí**. Ve hře to vypadalo jako jeskyně osvětlená sluncem bez jakéhokoliv otvoru.

**⚠️ Do fronty musí i osvětlené buňky NAD čelem u země.** Svislý průchod nastaví 15 všemu
nad terénem, ale sousední sloupeček může mít terén výš a jeho stín sahá nad úroveň zdejšího
povrchu — světlo do něj musí přijít ze strany, z buněk v odpovídající výšce. Když se zařadilo
jen čelo u země, zůstal na švu chunků pruh **o jednu tmavší**. Okem k nerozeznání od stínu;
našel to až nezávislý přepočet. Zařazuje se proto rozsah po nejvyššího ze čtyř sousedů —
zařadit všechny osvětlené buňky by bylo zbytečně drahé.

**Sousední sloupce se při obnově okrajů vytáhnou jednou.** Ptát se na ně přes `skyLightAt()`
znamená tři vyhledání v mapě na každou z osmi tisíc buněk okraje, a fronta světla je pak
trvale plná (p99 vyskočilo na 8,4 ms).

**Plynulé osvětlení (smooth lighting).** Světlo se počítá **do každého rohu stěny zvlášť**,
ze čtyř buněk, které se toho rohu dotýkají: buňka před stěnou, dvě po stranách a jedna
do rohu. Karta pak mezi rohy interpoluje, takže přechod světla přes stěnu je plynulý místo
skoku na hranici bloku. Ze stejných čtyř buněk vypadne i **ambient occlusion** — čím víc
jich je zazděných, tím tmavší roh; odtud stíny v koutech a pod převisy.

**⚠️ Zazděné buňky se do průměru nepočítají.** Měly by nulu a roh u zdi by kvůli nim byl
tmavší, než jaké světlo tam ve skutečnosti je. Na to, že je roh u zdi, stačí ambient occlusion.

**⚠️ Úhlopříčka čtyřúhelníku se podle potřeby přetáčí.** Stěna se kreslí jako dva trojúhelníky
a světlo se interpoluje uvnitř každého zvlášť; když jsou protilehlé rohy různě tmavé, je přes
špatně vedenou úhlopříčku vidět ostrý šev. Dělení se proto volí tak, aby úhlopříčka spojovala
podobně osvětlené rohy.

**Stěna, která neleží na hranici bloku, dostane ploché světlo.** Bok tenké pochodně souseda
nemá; kdyby se počítalo se zazděným blokem, pochodeň by zčernala.

**`World.cellAt()` vrací blok i obě světla jedním dotazem.** Plynulé osvětlení se ptá na čtyři
buňky kolem každého rohu, tedy 24krát na blok, a každý zvlášť položený dotaz je jedno
vyhledání v `HashMap`. Sloučení tří dotazů do jednoho srazilo stavbu meshe z 0,93 na 0,71 ms.

### Obloha

**Obloha je pevná skořápka kolem počátku, ne objekt ve světě.** Funguje to jen díky tomu,
na čem stojí celý renderer: kamera sedí v počátku a svět se posouvá k ní. Nebeská tělesa se
proto kreslí na pevném poloměru kolem nuly a nemusí se s hráčem nikam posouvat — obloha
zůstává „nekonečně daleko" sama od sebe.

**Denní doba otáčí celou skořápkou a otočení se vmíchá do MATICE, ne do geometrie.**
Hvězdy se tak nahrají do VBO jednou; jinak by se každý frame přenášelo přes pět tisíc vrcholů.

**⚠️ Kreslí se PŘED světem, s vypnutým testem i zápisem hloubky.** Obloha je nekonečně
daleko, takže ji má přebít cokoliv, co se nakreslí po ní. Po světě by přetřela terén;
se zápisem hloubky by ho zaclonila.

**Hvězdy se kreslí před sluncem a měsícem**, aby přes ně neprosvítaly, a jejich průhlednost
řídí `nightFactor()` — ve dne nula, v noci jedna.

**⚠️ Vrcholy nebeských čtverců mají OTOČENÉ pořadí proti obvyklému.** Skořápku vidíme
zevnitř; osy, ze kterých se čtverec staví, dávají normálu ven, takže obvyklé pořadí
`0,1,2 / 0,2,3` vyrobí stěnu odvrácenou od kamery a **backface culling ji beze slova zahodí**.
Obloha pak byla neviditelná, aniž by cokoliv zahlásilo chybu — vypadalo to, že se nekreslí.
`SkyTest` proto počítá normálu každé stěny a kontroluje, že míří k počátku; bez opravy
hlásí 1804 odvrácených.

### Kopání

**⚠️ Postup se váže na KONKRÉTNÍ BLOK, ne na stisknuté tlačítko.** Jakmile paprsek ukáže
jinam nebo se blok pod kurzorem změní, začíná se od nuly. Bez toho by šlo „nabít" kopání
na měkké hlíně a jedním škubnutím myší rozbít kámen. `MiningTest` to zkouší přímo.

**Kopání potřebuje vědět, že se tlačítko DRŽÍ**, ne jen že bylo stisknuté — vstup proto
sleduje i puštění. Odchod do menu s drženým tlačítkem kopání ruší, jinak by po návratu
pokračovalo.

**⚠️ Vzor prasklin musí RŮST.** Co je prasklé ve stádiu 3, musí být prasklé i ve 4 — jinak
by praskliny při kopání poskakovaly místo aby se prohlubovaly. Proto se porovnává jeden
a týž hash s rostoucím prahem; pixel jednou prasklý zůstane. `AtlasTest` to kontroluje
pixel po pixelu napříč všemi deseti stádii.

**⚠️ Krychle s prasklinami je o kousek NAFOUKLÁ.** Kdyby ležela přesně na bloku, hloubkový
test by mezi ní a jeho stěnou nedokázal rozhodnout a obraz by se rozblikal. Nafouknutí je
menší než jeden pixel textury, takže není poznat.

**Praskliny mají vlastní shader bez světla a mlhy.** Světlo by nedávalo smysl — nejsou to
povrchy, ale čáry přes ně; a mlha už je započítaná v bloku pod nimi, takže by se přidala
dvakrát.

### Předměty na zemi

**Položka na zemi je objekt s polohou, ne blok.** `DroppedItem` nese polohu, rychlost, hromádku
a stáří, `DroppedItems` je jejich seznam. Seznam žije MIMO `World`: svět se na položky nikdy
neptá, jen položky se ptají světa (kolize, voda, je sloupec načtený?). Main drží jeden seznam,
hýbe s ním jen ve stavu PLAYING (v inventáři a v pauze stojí, stejně jako denní doba) a při
založení i načtení světa ho vyprázdní.

**Kolize stejnými pravidly jako hráč, jen s krabičkou 0,25³.** Týž `World.isSolid()`, tentýž
překryv `floor(a)` až `ceil(b)-1`, osy jedna po druhé, kroky ≤ 0,4 bloku a `dt` se stropem
0,05 s — ze stejných důvodů, které jsou popsané u hráče. Kód je v `DroppedItem` zvlášť, ne
vytažený z `Player` do společné třídy: sjednocení by znamenalo sáhnout na odladěnou fyziku
hráče, a to mimo rozsah téhle změny. Až přibude třetí entita, je to kandidát na sloučení.

**Konstanty jsou z Minecraftu, přepočtené z ticků na sekundy.** Gravitace 16 b/s², útlum ve
vzduchu 0,67 za sekundu (0,98 za tick), na zemi 2,4·10⁻⁵ (0,588 za tick). Terminální rychlost
se nehlídá zvlášť — vypadne z útlumu sama: 16 / −ln 0,67 ≈ 40 b/s, stejně jako v Minecraftu.
Útlum je `pow(drag, dt)`, ne násobek, ze stejného důvodu jako u hráče ve vodě. Změřeno: položka
puštěná po zemi rychlostí 3 b/s ujede 0,26 bloku; ve vodě klesne za 0,75 s o 0,6 bloku proti
4,15 ve vzduchu.

**⚠️ Položku, na kterou hráč položí blok, je nutné vytlačit.** Samotný dopad ji vrací na horní
hranu buňky POD blokem, tedy dovnitř nového bloku — a tam by zůstala. Když proto krabička na
začátku kroku vězí v pevném bloku, posune se o buňku výš (o jednu za frame, dokud není volno).
`DroppedItemTest` to zkouší se dvěma bloky nad sebou. Zakazovat pokládání bloku na položku by
nedávalo smysl — hráč ji často ani nevidí.

**Těžba jde dál rovnou do inventáře, na zem padá jen přebytek** (`Mining.harvest()`). Minecraft
vyhazuje entitu vždycky a hráč ji pak sebere; tady by to znamenalo, že kopání vzdáleného bloku
(paprsek sahá 8 bloků) nic nedá, dokud si pro něj hráč nedojde. Přebytek vypadne ze středu
rozbitého bloku s malým výskokem, ať je vidět, že něco vypadlo.

**Vyhození: Q jeden kus, Ctrl+Q celá hromádka** — schéma Minecraftu, takže sedí do ruky. Ctrl tu
zároveň znamená plížení; hráč se při vyhození jen na okamžik přikrčí, což nevadí. Držené Q sype
dál po jednom (bere se i `GLFW_REPEAT`). Položka vyletí zpod očí (−0,3 bloku) rychlostí 6 b/s
ve směru pohledu a 2 b/s navíc nahoru, takže dopadne zhruba 3 bloky před hráče.

**⚠️ Sběr má zpoždění: 0,5 s u vytěženého bloku, 2 s u vyhozeného.** Vyhozená položka vzniká
uvnitř dosahu sběru, takže bez zpoždění by skončila hned zpátky v ruce a Q by nedělalo nic.
Stejné hodnoty jako v Minecraftu.

**Dosah sběru je překryv krabiček, ne vzdálenost středů.** Hitbox hráče se zvětší o blok do stran
a o půl bloku nahoru i dolů. Se vzdáleností středů by vysoký hitbox sbíral jinak u nohou a jinak
u hlavy. Sbírá se stejně jako při těžbě — nejdřív rozdělané hromádky, pak volný slot — a co se
nevejde, zůstane ležet: při plném inventáři celá položka, při skoro plném jen zbytek.

**Kreslí se SVĚTOVÝM shaderem, ve vertex formátu `ChunkMesh`.** Položka tak má stejné denní
i blokové světlo, mlhu i mlhu pod vodou jako terén, bez vlastního programu. Světlo je ploché,
z buňky, ve které položka leží — plynulé osvětlení po rozích by u kostky, která se točí, nemělo
k čemu se vztáhnout. Tvar se bere z `BlockModels` a UV z rozsahu kvádru jako ve světě, takže
pochodeň leží na zemi jako tyčka.

**⚠️ Vrcholy jsou relativní ke kameře, odečtené na CPU v `double`.** Táž úmluva jako
`uChunkOffset` u sekcí, jen se posun zapeče rovnou do vrcholů: položky se každý frame hýbou
a točí, takže se stejně celé přenahrávají, a `uChunkOffset` je pro ně nula. `DroppedItemTest`
hlídá, že i na souřadnici 100 000 jsou vrcholy malá čísla.

**⚠️ Kreslí se PŘED vodou, uvnitř `WorldRenderer.render()`.** Voda nezapisuje hloubku, takže
položka nakreslená až po ní by přes hladinu prosvítala bez modrého nádechu, jako by ležela
nad vodou.

**Mesh se staví znovu každý frame, do jednoho VBO a jedním draw callem.** Buffer na grafice roste
s polem na CPU a pak se jen přepisuje, takže se každý frame nic nealokuje. Položky dál než
64 bloků se přeskočí — čtvrtinová kostka je tam pixel nebo dva. Změřeno (stroj měl při měření
~55 % zátěže z jiných programů, takže spíš horní odhad):

| položek | `DroppedItems.update()` | `DroppedItemMesh.build()` | data na frame |
|---|---|---|---|
| 100 | 0,03 ms | 0,06 ms | 98 KB |
| 1000 | 0,13 ms | 0,21 ms | 984 KB |

**Animace je z Minecraftu:** otočka jedna radiána za sekundu a houpání 0–0,2 bloku nad zemí
s periodou ~3 s. Po sobě vzniklé položky mají fázi posunutou o zlatý úhel, aby se dvě vedle
sebe netočily jako jedna.

**Zánik po pěti minutách (`LIFETIME`), jako v Minecraftu.** Bez toho by jich při těžbě s plným
inventářem mohlo přibývat bez omezení.

**⚠️ Známé zjednodušení: položky se NEUKLÁDAJÍ — ani do `saves/world.dat`, ani přes unload
sloupce.** Stejný druh zjednodušení jako statická voda. Ukládá se rozdíl bloků proti generátoru
a položka do toho modelu nepatří: není to buňka v mřížce, ale objekt s plovoucí polohou,
rychlostí a stářím. Uložit ji by znamenalo nový oddíl v souboru, novou verzi formátu a vlastní
převzetí při načtení sloupce (jako to mají změny bloků v `insert()`). U unloadu navíc položku
nejde nechat žít bez jejího sloupce: nenačtený sloupec je pro kolize vzduch, takže by propadala
donekonečna — musela by se „zmrazit" a přilepit ke sloupci. Proto zmizí, jakmile se sloupec pod
ní zahodí. Cena: co leží na zemi při odchodu do menu, zavření okna, nebo když se hráč vzdálí
za `unloadRadius`, je pryč. Z inventáře tím nezmizí nic — jen to, co už hráč nechal ležet.

**Další známá zjednodušení:** položky ve vodě klesají ke dnu (v Minecraftu vyplavou), neodrážejí
se, navzájem se nestrkají a stejné hromádky vedle sebe se neslévají do jedné.

### Blok v ruce

**Kreslí se ve VLASTNÍ perspektivě, ne ve světové.** Je to kus geometrie kousek před
kamerou, ne objekt ve světě — se světovou maticí by prorážel stěny a mizel v blocích.

**⚠️ Před kreslením se MAŽE hloubkový buffer, ne vypíná test.** Ruka má být vždycky vepředu,
ale sama se sebou se hloubkově porovnávat musí — s vypnutým testem by zadní stěny kostky
přebily přední.

**Tvar se bere z `BlockModels`**, takže pochodeň se v ruce drží jako tyčka a plot jako
sloupek — stejně jako ikona v hotbaru a jako blok ve světě.

**Světlo je jedno číslo pro celý model.** Ruka je u oka, takže jí stačí osvětlení místa,
kde hráč stojí; ztmavení stěn zůstává, aby kostka měla tvar.

**⚠️ Máchnutí se nepřerušuje.** Kopání drží tlačítko a spouští ho každý frame; kdyby se
pokaždé restartovalo, ruka by se roztřásla na místě místo aby se rozmáchla. `SwingTest`
to ověřuje přímo — pět framů s drženým tlačítkem musí dát tutéž hodnotu jako pět framů
bez něj.

**⚠️ Máchnutí je ROTACE PO OBLOUKU, ne posun dolů.** První verze ruku jen stahovala dolů
a k sobě; vzniklo z toho houpnutí sem a tam, ne švih. Minecraftí pocit dělá teprve to, že
se ruka otáčí kolem **tří os naráz** a každá podle **jiné křivky**:

| křivka | vzorec | vrchol | řídí |
|---|---|---|---|
| `fast()` | `sin(√p · π)` | ~27 % průběhu | zdvih kolem X (−80°) a překlopení zápěstí kolem Z (−20°) |
| `slow()` | `sin(p² · π)` | ~73 % průběhu | odklon kolem Y (−20°) |

**Kdyby obě vrcholily společně, ruka se vrací po stejné dráze** a je z toho kmit, ne
oblouk. `SwingTest` proto neměří jen rozsah 0–1, ale i **kdy** která křivka vrcholí, a
trvá na tom, že rychlá je napřed aspoň o 15 % průběhu — bez toho by regrese zpátky na
jednu křivku prošla.

Klidová poloha není zvláštní případ: je to táž matice s nulami — posun vpravo dolů
(`0,56; −0,52; −0,72`) a natočení o 45°, aby se kostka nedívala na kameru čelem.

### Postava a pohledy

**F5 přepíná tři pohledy cyklicky: první osoba → zezadu → zepředu → zpět**, jako v Minecraftu
(`Camera.View`). Přepnutí je okamžité.

**⚠️ `yaw` a `pitch` jsou pořád pohled HRÁČE.** Pohled zepředu otáčí jen to, kam se dívá kamera
(`viewDirection()`, z něj `viewMatrix()` a tím i obloha), ne kam se dívá hráč. Pohyb, míření,
hlava modelu i vyhazování předmětů jedou dál z `getLookDirection()`.

**⚠️ Míří se z OČÍ, ne z kamery.** Paprsek pro kopání a pokládání startuje v očích hráče ve všech
pohledech. Z kamery za zády by trefil blok mezi kamerou a hráčem, zepředu by mířil proti pohledu.
Obrys a praskliny se kreslí na tentýž blok, jen se na něj kouká odjinud.

**Kamera třetí osoby stojí 4 bloky od očí po přímce pohledu a zkracuje se o terén.** Vrhá se osm
rovnoběžných paprsků z rohů krychličky ±0,1 kolem očí — Minecraft to dělá stejně — a bere se
nejkratší zásah. Jeden paprsek by stačil na to, aby kamera nebyla v bloku, ale ne na blízkou
ořezovou rovinu: kamera by skončila přesně na stěně a do bloku by nahlédla. Poloměr 0,1 ji drží
dál, než leží ořezová rovina (0,05). Poloha se počítá znovu každý frame, takže v chodbě kamera
sama přijede k hráči a na volném prostranství zase odjede.

**Paprsky jsou existující `Raycaster`, žádná nová fyzika.** Raycaster vrací blok a normálu stěny,
kterou do něj paprsek vstoupil — z toho se vzdálenost dopočítá přesně: stěna s normálou +X leží
v rovině x = blok + 1, takže t = (rovina − start) / směr. Kamera se tím zarazí o všechno, co jde
zaměřit (pochodeň a plot jako plná buňka), a vodou projede. `CameraTest` hlídá, že vyjde přesně
rovina stěny minus poloměr.

**Model je z kvádrů v PIXELECH Minecraftu** — hlava 8³, trup 8×12×4, končetiny 4×12×4, postava
32 px = výška hitboxu 1,8, tedy 1 px = 0,05625 bloku —, s klouby a pořadím rotací Z, Y, X jako
`ModelBiped`. Filozofie je stejná jako u `BlockModels` (tvar je seznam kvádrů), data vlastní:
každý díl se otáčí kolem svého kloubu, takže to nemůže být statický mesh.

**Transformuje se NA CPU a staví znovu každý frame**, stejně jako předměty na zemi. Změřeno:
animace i stavba všech 252 vrcholů (postava + držený blok) **~2 µs na frame**. Šest dílů jako
šest draw callů s vlastní maticí by nic neušetřilo a stavba by nešla otestovat bez GL. Vrcholy
jsou relativní ke kameře a ve světovém formátu, takže postava jde **světovým shaderem** — se
stejným denním i blokovým světlem a mlhou jako terén, bez vlastního programu. Pro tělo se jen
na chvíli naváže textura skinu místo atlasu; držený blok leží ve stejném VBO za ním a kreslí se
z atlasu. Kreslí se před vodou, ze stejného důvodu jako předměty na zemi.

**Ztmavení stěn se počítá ze směru stěny VE SVĚTĚ.** Terén má pevné odstíny os (vršek 1, boky 0,6
a 0,8, spodek 0,5). Kdyby se vázaly ke stěnám modelu, tmavý bok by se otáčel s postavou. Normála
se proto otočí maticí dílu a odstíny os se smíchají podle druhých mocnin jejích složek (dávají
součet 1): stěna podél osy dostane přesně odstín té osy, šikmá plynulý mezistupeň.

**⚠️ Placeholder skin je v šabloně Minecraftu 64×64 a vyměňuje se na JEDNOM místě.**
`Textures.playerSkinPixels()` kreslí barvy dílů (kůže, vlasy, tričko, kalhoty, boty, oči) přímo
do rozložení šablony a UV v `PlayerModelMesh` jsou souřadnice té šablony (rozbalení kvádru
jako `ModelBox`). Skutečný skin = v `Textures.playerSkin()` načíst PNG přes `ImageIO`
a `getRGB(0, 0, 64, 64, null, 0, 64)` místo `playerSkinPixels()`; model ani UV se nemění.
Stejný princip jako „ruční textury místo procedurálních" u atlasu bloků.

**⚠️ Skin jde do GL v pořadí OBRÁZKU (horní řádek první), ne odspodu jako atlas.** GL pak má
t = 0 u horního okraje a UV modelu jsou rovnou souřadnice ve skinu dělené 64, bez překlápění.
`getRGB` vrací řádky shora, takže načtený PNG sedí taky beze změny — překlopení, které by se
u načítání snadno zapomnělo, tu vůbec není. `PlayerModelTest` hlídá, že obličej ze šablony
(u 8–16, v 8–16) leží na přední stěně hlavy a míří ve směru pohledu, a že každá stěna modelu
míří do vybarvené části skinu.

**Animace jsou vzorce z `ModelBiped`, přepočtené z ticků na sekundy:**

| | vzorec | co dělá |
|---|---|---|
| rozmach | `min(1, rychlost / 5 b/s)`, dotahovaný mocninou `0,6^20` za sekundu | jak moc se končetiny rozmáchnou |
| fáze kroku | `+= rozmach · 13,3 rad/s · dt` | kde v cyklu chůze nohy jsou |
| noha | `cos(fáze) · 1,4 · rozmach`, levá s opačným znaménkem | krok |
| ruka | `−cos(fáze) · 1,0 · rozmach` | jde proti noze na téže straně |
| klid | odklon rukou 0–0,1 rad a kmit ±0,05 rad, každé s jinou periodou | pohupování |

Změřeno: při chůzi 4,3 b/s je rozmach 0,86, noha se vychýlí o 1,2 rad a celý cyklus trvá
0,55 s — jako v Minecraftu.

**⚠️ Fáze roste rozmachem, ne ujitou vzdáleností.** Při chůzi to vyjde skoro nastejno, ale rozmach
je shora omezený: let rychlostí 12 b/s tak má stejný rytmus kroků jako sprint, místo aby nohy
zběsile cupitaly. Rozmach se řídí SKUTEČNÝM posunem hráče za frame, ne vstupem — chůze do zdi
nohama nemáchá. Dotahování je mocnina, ne násobek, takže `PlayerModelTest` ověřuje stejný
výsledek při 30 i 120 FPS.

**Máchnutí ve třetí osobě jede na týchž dvou křivkách jako ruka v první.** `HandSwing.fast()` zvedá
paži o 80° dopředu, `slow()` ji v druhé půlce stočí o 20° přes tělo — stejné úhly jako
v `HeldItemRenderer`. Přičítá se až nakonec, přes chůzi i držení, protože se kope i za chůze.
Ruka s blokem je předsunutá o π/10 a za chůze máchá jen napůl, jako v Minecraftu.

**Animace běží i v první osobě**, jen se nekreslí — po F5 postava nenaskočí z klidu uprostřed
kroku. V první osobě se vlastní tělo nekreslí vůbec a zůstává `HeldItemRenderer`.

**Známá zjednodušení:** trup se natáčí přesně s pohledem (Minecraft nechává tělo zaostávat až
o 50° a za chůze ho stáčí do směru pohybu); chybí poloha při plížení a plavání; druhá vrstva
skinu (klobouk, bunda) se nekreslí; starší skiny 64×32 bez levé ruky a nohy by se musely
zrcadlit; přepnutí pohledu nemá přechod; zaměřovač zůstává vidět i ve třetí osobě (Minecraft
ho tam skrývá); postava nevrhá stín. Světlo je jedno číslo pro celou postavu, z buňky s hlavou.

### Ostatní

**Pozadí menu je jeden quad, ne stovky dlaždic.** Textura má `GL_REPEAT` a UV jdou od 0
do `rozměr obrazovky / velikost dlaždice`. Kreslit dlaždice po jedné by bylo stovky draw
callů za nic.

**Dlaždice hlíny se generuje hashem ze souřadnic, ne generátorem náhodných čísel.** Vyjde
pokaždé stejná (žádné blikání mezi spuštěními) a je bezešvá — hash nezná okraje, takže
na hranici opakování nevznikne spára.

**Zvýraznění tlačítek je přechod, ale velikost se nemění.** Roztažení by muselo být necelým
počtem GUI pixelů a rozmazalo by hrany. Hit-test nepočítá s ničím, co se hýbe — jinak by
se tlačítko na hraně samo chytalo a pouštělo dokola.

---

## Změřený výkon

| | |
|---|---|
| FPS u země | **~1400** (dohled 96, vsync vypnutý klávesou V) |
| Stavba meshe | **0,71 ms** na sekci (0,36 před plynulým osvětlením) |
| Naplnění dohledu | ~600 ms, rozložené do rozpočtu 4 ms/frame (12 ms při loadingu) |
| Přestavba po rozbití bloku | 1–4 sekce ≈ 1,5 ms |
| Generování sloupce | ~0,9–1,5 ms na worker vlákně; s nasvícením ~3,7 ms |
| `World.update()` při letu | medián 2,1 ms, p99 **3,3 ms** (synchronně to bylo ~22 ms) |
| Paměť sekce | 4 KB bloky + 4 KB světlo, oboje líně |
| Paměť | ~32 KB na sloupec |
| Výšky terénu | min 48, max 79, průměr 63 (24 000 vzorků přes 6000×6000 bloků) |

**Frustum culling ani async generace nejsou implementované — po měření vyhodnoceny jako
předčasné.** Vrátit se k nim, až render distance nebo počet chunků naroste natolik, že se to projeví.

---

## Ovládání

| | |
|---|---|
| E | inventář (znovu E nebo Esc zavře) |
| Shift+LMB v inventáři | přesun hromádky hotbar ↔ batoh; z crafting mřížky zpět do inventáře |
| LMB / PMB táhnout v inventáři | rozdělit drženou hromádku rovnoměrně / po jednom kusu |
| Q / Ctrl+Q | vyhodit z ruky jeden kus / celou hromádku (držené Q sype dál) |
| F5 | pohled: první osoba → třetí zezadu → třetí zepředu → zpět |
| PMB na crafting table | otevře mřížku 3×3 |
| LMB (držet) | kopat — doba podle tvrdosti bloku |
| T | posun času o desetinu cyklu (ladění) |
| WASD / Space / Ctrl | pohyb / skok (nahoru v letu) / plížení (dolů v letu) |
| Shift | sprint |
| 1–9, kolečko | výběr slotu hotbaru |
| LMB / PMB | těžit / položit |
| F / C | let / noclip |
| V / Esc | vsync / pauza |

Hráč: hitbox 0,6 × 1,8, oči 1,62, chůze 4,3 b/s, gravitace 28 b/s², skok **1,19 bloku**
(vyskočí na jednoblokový schod, ne na dvoublokový).

---

## Co dál

**Grafika menu a HUD — hotovo.** Hranaté UI v celočíselném měřítku, bevel místo přechodů,
pixelové písmo, izometrické kostky v hotbaru, dlaždicované pozadí. Další úprava vzhledu =
změna `Palette` a konstant v GUI pixelech v `Hud`/`Menu`, ne nový kód.

**Texture atlas — hotovo.** Mřížka 4×4 dlaždic po 16×16 (`BlockAtlas`), textury generuje
procedurálně `Textures.blockAtlasPixels()`. Tráva má jinou texturu shora, z boku i zespodu.
Přidat blok = konstanta ve `World`, dlaždice v `BlockAtlas`, její kresba v `Textures`
a case v `colorFor()` kvůli hotbaru. V mřížce zbývá 9 volných buněk.

**Ruční textury místo procedurálních** je teď výměna jedné metody. `Texture` bere pole
`0xAARRGGBB`, takže stačí načíst PNG přes `ImageIO` a poskládat ho do stejné mřížky —
`BlockAtlas` o způsobu vzniku pixelů neví.

**Jeskyně a rudy — hotovo.** 3D šum, uhlí a železo s vlastními hloubkami. Přidat další rudu
= konstanta ve `World`, řádek v `oreAt()`, dlaždice v `BlockAtlas` a `Textures`.

**Asynchronní generování — hotovo.** Generování běží na worker vlákně, hlavní vlákno jen
předává souřadnice a přebírá hotové sloupce. Změřeno při chůzi přes 25 hranic chunku:
nejhorší `update()` **0,63 ms** místo dřívějších ~22 ms, průměr 0,1 ms.

**Vlákno je zatím jedno a stačí.** Vyrobí ~770 sloupců za sekundu; chůze rychlostí 4,3 b/s
si vyžádá 17 sloupců za 3,7 s. Přidat další vlákna je triviální (worker nemá žádný sdílený
stav), ale zatím k tomu není důvod. Kdyby se hráč začal pohybovat řádově rychleji než letem,
projevilo by se to tak, že svět nestíhá dosypávat — ne zádrhelem.

**Perzistence světa — hotovo.** Ukládá se rozdíl proti generátoru do `saves/world.dat`;
v hlavním menu přibude „Load World", jakmile nějaký uložený svět existuje.

**Zatím jeden slot světa.** Víc světů by chtělo výběr v menu (seznam souborů, název,
mazání) — `WorldStorage` bere cestu jako parametr, takže samotné ukládání je na to
připravené. Chybí i seed jako součást uloženého světa: teď je pevný v `SimplexNoise`,
takže všechny světy vypadají stejně.

**Voda — hotovo.** Jezera po hladinu `SEA_LEVEL` s písečnými plážemi, průhledné kreslení
druhým průchodem, plavání. Změřeno: vodu má 7 % sloupců.

**Inventář a crafting — hotovo.** `Container` + `ContainerScreen` jako znovupoužitelná
dvojice, obrazovka na E s mřížkou 2×2, crafting table s 3×3 po kliknutí na položený stůl.
Těžba padá do inventáře, pokládání z něj ubírá. Inventář se ukládá spolu se světem.

**Shift-klik, tažení myší a předměty na zemi — hotovo.** Shift-klik mezi hotbarem a batohem
(i z crafting mřížky), tažení levým (rovnoměrně) i pravým (po jednom) s náhledem, vyhazování
z ruky na Q / Ctrl+Q. Co se při těžbě nevejde do inventáře, vypadne na zem a dá se sebrat.

**Co k inventáři chybí:** sloty na zbroj (nemá je co plnit), shift-klik na výsledek craftingu
(„vyrob, kolik to jde"), dvojklik pro sesbírání stejného bloku, vyhazování z otevřené obrazovky
(klik mimo panel, Q nad slotem). Truhla by byla jen další `Container` a další tovární metoda
v `ContainerScreen`; shift-klik by pak přesouval mezi truhlou a inventářem.

**Stromy — hotovo.** Dub s kmenem 4–6 bloků a korunou ze čtyř vrstev, jeden na buňku 8×8,
jen na trávě nad hladinou. Kmen dává 4 prkna.

**Tvary bloků — hotovo.** `BlockModels` jako kvádrový systém, pochodeň a plot jako první
dva nekrychlové bloky. Postavené tak, aby na tom stály schody, desky a další.

**⚠️ Chybí PŘEDMĚTY (ne bloky).** `ItemStack` drží id bloku, takže klacek — který není
umístitelný — zatím nejde vyrobit. Proto je pochodeň z prkna a uhlí místo z klacku a uhlí.
Až předměty přibudou, opraví se recept a klacek se stane surovinou pro ploty a nářadí.

**Osvětlení a cyklus dne a noci — hotovo.** Sluneční i blokové světlo, pochodně, desetiminutový
den, obloha měnící barvu. Klávesa **T** posune čas o desetinu cyklu (na noc se jinak čeká minuty).

**Doba těžení — hotovo.** Tvrdost na blok (hlína 0,5 s, kámen 1,8 s, železo 3 s), deset
stádií prasklin.

**Blok v ruce — hotovo.** Model v perspektivě před kamerou, máchnutí při kopání i pokládání.

**Další na řadě:** zvuky.

**Model postavy a pohledy — hotovo.** Postava z kvádrů s placeholder skinem v šabloně Minecraftu,
chůze, pohupování a máchnutí, F5 přes tři pohledy s kamerou, která neprojede terénem. Zbývá:
tělo zaostávající za hlavou, plížení a plavání, druhá vrstva skinu, načítání skutečného skinu
(je to výměna jedné metody). Skutečný systém entit (víc postav, jiní hráči) zatím není — model
je napsaný pro hráče, ale nic v něm na hráče vázané není kromě vstupních parametrů.

**Osvětlení je uzavřené.** Plynulé osvětlení s ambient occlusion, obloha se sluncem, měsícem
a hvězdami. Co by šlo přidat později: měsíční fáze, barevný nádech při východu a západu,
a mraky.

**Potom správa světů, na kterou zatím není postavené nic:** výběr z víc uložených světů
(`WorldStorage` už bere cestu jako parametr), nastavení, volby při zakládání světa včetně
**seedu** (teď je pevný v `SimplexNoise`, takže všechny světy vypadají stejně), náhledové
obrázky u uložených světů a jejich mazání.

**Známé zjednodušení u vody:** neteče a nešíří se — je to jen statická výplň pod hladinou.
Jezero se dá zasypat, ale ne vypustit ani přelít. Chybí i utopení a bubliny.

**⚠️ Atlas nemá mipmapy** — vzdálený terén bude jiskřit. Zapnout je nejde jen tak: nižší
úrovně by průměrovaly přes hranice dlaždic a barvy by se mísily mezi bloky. Správné řešení
není padding, ale **texturové pole** (`GL_TEXTURE_2D_ARRAY`, v GL 3.0+): každá textura je
vrstva, UV vždycky 0–1, prosakování nemůže nastat a mipmapy fungují per-vrstvu. Cena je
jeden float na vrchol navíc (index vrstvy).

**Známé zjednodušení:** není step-up assist (přes 0,6bloku vysoký schod tě to nevytáhne
automaticky, musíš skočit), není fall damage, `glLineWidth > 1` není v core profilu garantovaný.
