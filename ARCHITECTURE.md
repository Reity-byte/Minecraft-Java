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
| Zvuk | **OpenAL** — `lwjgl-openal` 3.3.3 + natives win/linux/macos (OpenAL Soft je v nich) |

⚠️ **LWJGL 3.3.3 hlásí při startu `Unsupported JNI version detected`** — je starší než JDK 26.
Zatím běží, ale upgrade na 3.3.4+ je jednořádková změna v `pom.xml`.

## Build a spuštění

`mvn` ani JDK 26 nejsou na PATH. Z IntelliJ stačí zelená šipka; z terminálu:

```bash
export JAVA_HOME="/c/Users/Lukášek/.jdks/openjdk-26.0.2.1"
"/c/Program Files/JetBrains/IntelliJ IDEA 2026.2.2/plugins/maven-plugin/lib/maven3/bin/mvn" -B compile
```

V Git Bashi je nutné classpath převádět `cygpath -w` a spojovat středníkem.

**Jeden spustitelný jar pro poslání ven:** `mvn -B package` vyrobí
`target/minecraft-base-1.0.jar` (maven-shade-plugin, `Main-Class: mc.Main`) se
všemi závislostmi i nativy uvnitř — Windows x64, Linux x64, macOS x64
i macOS arm64, tedy 18 knihoven; LWJGL si za běhu rozbalí tu svou. Vedle zůstává
`target/original-minecraft-base-1.0.jar`, což je ten tenký bez závislostí.

```bash
java -jar minecraft-base-1.0.jar                    # Windows, Linux
java -XstartOnFirstThread -jar minecraft-base-1.0.jar   # ⚠️ macOS, jinak GLFW okno neotevře
```

Potřebná Java je **17** (`maven.compiler.source/target`); ověřeno překladem
s `--release 17`, že v kódu není novější API. Hra si data (`saves/`, `textures/`,
`options.json`) zakládá v PRACOVNÍM ADRESÁŘI, takže se jar spouští z té složky,
kde mají data být.

## Testy

`src/test/java/mc/` — **1452 kontrol**, žádný JUnit, obyčejné `main()` třídy.
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
| `MiningTest` | Doba kopání podle tvrdosti, **přepnutí cíle vynuluje postup**, puštění tlačítka, stádia prasklin, kam jde vytěžený blok (inventář, rozdělaná hromádka, **při plném inventáři na zem**), zvuk rozbití podle materiálu ze středu bloku |
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
| `PlayerModelTest` | Animace: rozmach podle rychlosti, **opačná fáze nohou**, ruka proti noze, délka kroku, strop při letu, **nezávislost na FPS**, pohupování, máchnutí z `HandSwing`, držení. Model: rozměry jako hitbox, **pravá ruka vpravo, obličej vepředu**, končetiny v póze, držený blok u pěsti, odstín podle směru ve světě. Skin: každá stěna míří do vybarvené části. **Holá ruka v první osobě:** tytéž UV jako pravá ruka postavy, 4×12×4 px, v klidu vpravo dole před kamerou, při máchnutí u zaměřovače, **zpátky jde níž než tam (oblouk)**, po doběhnutí přesně klid; s blokem v ruce dál blok |
| `CameraTest` | Pořadí pohledů F5, poloha zezadu i zepředu, směr pohledu a matice, **zkrácení o zeď i podlahu** s poloměrem kamery, přesná vzdálenost k rovině stěny, oči v bloku |
| `SoundTest` | Materiál zvuku = **stejné skupiny jako tvrdost**, obměna výšky, **cooldown proti „kulometu"**, interval kroků podle rychlosti, kroky skutečného hráče (stoj, chůze, let, hrana), syntéza (slyšitelná, bez lupnutí, deterministická), WAV (tam a zpět, 8 bit stereo, cizí bloky, useknutý soubor), **výměna placeholderu souborem** |
| `TextureLabTest` | Index pixelu a hranice dlaždic (pokrytí celého atlasu), **shoda s `BlockAtlas.INSET`** (editovaných 16 texelů je přesně to, co hra vzorkuje), malování tahem, undo, kapátko, bloky podle dlaždice, hex a HSV, **PNG tam a zpět včetně alfy a orientace řádků**, přepínač procedurální/soubor, **globální paleta jako čistá funkce** (četnost, bez průhledné, řazení podle odstínu, kde se barva vyskytuje), **import PNG** (správný rozměr i s undo; 64×64, 128×64, 256×256, ne-obrázek a chybějící soubor → hláška a atlas beze změny), **návrh bloku** (přidělení buněk 63→27 a -1 při plném atlasu, jména, tvrdosti na škále vestavěných bloků, došlá id), hit-testy rozvržení **a žádné překryvy ovládacích prvků v obou režimech**, **náhled = bajt po bajtu tentýž mesh jako ve hře** |
| `MouseScaleTest` | Přepočet myši z bodů okna na pixely framebufferu: poměr pro 1,0 / 1,5 / 2,0 / 3,0, každá osa zvlášť, ochrana proti dělení nulou — a **simulovaná Retina přes všechny klikací obrazovky** (menu, deset položek Options i konec posuvníku, řádek seznamu světů, pole seedu, dlaždice a pixel plátna v labu, slot hotbaru): co je nakreslené na daném místě, to tam po přepočtu i reaguje. Jedna kontrola schválně hlídá, že bez přepočtu by klik trefil jiné tlačítko |
| `WorldSavesTest` | Světy na disku: **migrace starého `saves/world.dat`** (bajtová shoda, metadata, `world.dat.migrated`, druhý běh bez duplicity, pád uprostřed, obsazené jméno, poškozený zdroj), očištění jména na složku (zakázané znaky, `CON`/`com1`/`aux.txt`, tečky a mezery na konci), unikátní složka bez ohledu na velikost písmen, metadata tam a zpět (i `Long.MIN_VALUE`), **poškozený `world.json` svět neschová**, řazení podle posledního hraní, mazání jen vlastní složky |
| `SeedTest` | Seed: prázdné pole → náhodný, číslo → to číslo, text → `hashCode` (a pokaždé stejně), stejný seed = stejné sloupce blok po bloku, jiný seed = jiný terén, **kontrolní součty výchozího terénu změřené před refaktorem** (tři oblasti i záporné souřadnice, výšky přes 6000×6000, spawn) |
| `ThumbnailTest` | Náhled: orientace (horní řádek obrazovky = horní řádek obrázku), výřez středu podle poměru stran, zmenšení průměrováním, PNG tam a zpět, odmítnutí příliš velkého obrázku |
| `WorldScreenTest` | Obrazovky světů: psaní do pole se jménem i seedem, náhled cílové složky (i s `(2)`), Tab/Esc/Enter/Ctrl+V, seznam od naposledy hraného, výběr klikem, **dvojklik hraje**, šipky a rolování, **mazání až po potvrzení**, prázdný seznam, a celá cesta založit → uložit → najít v seznamu → načíst se stejným terénem |
| `CreativeTest` | Creative mód: přepínač na obrazovce zakládání světa (cyklus, nepřekryje Create/Cancel, `reset()` vrací survival), **okamžitá těžba** (praskne v prvním framu i u železa, ale vzduch, voda, puštěné tlačítko a kurzor mimo blok dál ne), **vytěžený blok mizí** (nic do inventáře, nic na zem, ani s plným inventářem), pokládání neubírá z hotbaru (50 položení, jeden kus vydrží 200), obsah přehledu (přesně jeden záznam na placovatelný vestavěný blok i na každý lab blok, bez `blocks.json` jen vestavěné, determinismus, pořadí), **nekonečný zdroj** (braní kopíruje, shift-klik kopíruje do hotbaru, položení do přehledu zahodí, rolování a klik po odrolování), let (stoupání i klesání, obě klávesy se vyruší, Shift zrychlí, **kolize v letu platí** proti propadnutí s noclipem, po vypnutí letu dopad), dvojstisk mezerníku (**z trojice přepne jen druhý**, reset, běžné skákání ne), mód ve `world.json` (tam a zpět, `touch()` ho zachová, **chybějící klíč i překlep → survival**) — a ke každému pravidlu **kontrola, že survival větev je nezměněná** |
| `OptionsTest` | Nastavení: výchozí hodnoty = dnešní hra, oříznutí na meze, **render ≤ simulation po 2000 náhodných změnách**, převod na čísla enginu (dohled < `(loadRadius-1)·16`), `options.json` tam a zpět, **chybějící i šest druhů poškozeného souboru → výchozí hodnoty**, jedna špatná hodnota → výchozí jen pro ni, záloha `.bak`, obrazovka Options (hit-testy, tažení posuvníku i mimo dráhu, žádné překryvy), výběr monitoru pro fullscreen, plánování stropu FPS, křivka jasu, GUI měřítko |
| `BlockRegistryTest` | `textures/blocks.json`: tvar výstupu, round-trip přes text i disk, **neexistující a poškozený soubor → jen vestavěné bloky** (náhodné bajty, useknutý JSON, špatné typy), přeskočení jednotlivých neplatných bloků, **stabilita id přes víc sezení** (i po ručním smazání bloku ze souboru), novější `format`, escape v JSON, plný registr, **záloha poškozeného souboru do `.bak`** |
| `LabBlockTest` | Blok z labu ve hře: pevný/neprůhledný/obojí ne, neznámé id, **doba kopání podle tvrdosti z dat** (`Mining`), vytěžený blok do inventáře a zpět do světa, **každá stěna meshe bere UV ze své dlaždice**, culling a stín podle neprůhlednosti, hráč duchem propadne a na mramoru stojí, paprsek zaměří i ducha, zvuk podle tvrdosti, náhled labu = mesh hry, **uložený svět nese id beze změny formátu** (svět bez bloků z labu je bajt po bajtu stejný), koloběh lab → soubor → restart |

**Testovat jde všechno kromě renderu** — `World`, `Player`, `Raycaster`, `ChunkMesh.build()`,
`Menu.buttonAt()`, `BlockAtlas`, `Textures.blockAtlasPixels()`, `DroppedItems`,
`DroppedItemMesh.build()`, `PlayerAnimation`, `PlayerModelMesh.build()`,
`Textures.playerSkinPixels()`, `Camera.follow()`, `HeldItemRenderer.build()` / `matrix()` ani `BlockRegistry` a `BlockDraft` nesahají na GL. Myš v `ContainerScreen`
(klik, shift-klik, tažení) taky ne — na GL sahá jen jeho kreslení. Ze zvuku potřebuje OpenAL
jen `SoundEngine`; výběr zvuku, cooldown, kroky, syntéza i čtení WAV jdou bez něj. Z texture labu
jdou bez GL `AtlasEditor`, `AtlasImage`, `TextureLabLayout` i stavba meshe náhledu
(`BlockPreview.build()`).

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
- `HeldItemRenderer` — blok v ruce, s prázdným slotem holá ruka; ve vlastní perspektivě před kamerou
- `ChunkColumn` — 8 sekcí nad sebou, líně alokované (prázdná sekce = `null`)
- `World` — `HashMap<Long, ChunkColumn>`, generování terénu i podzemí na worker vlákně, load/unload, dirty sekce
- `SimplexNoise` — 2D simplex (výšky) a 3D simplex (jeskyně), pevný seed `12345`
- `WorldStorage` — uložení a načtení rozdílu proti generátoru (jeden svět); **bez GL**
- `WorldSaves` — světy v `saves/<složka>/`, metadata, migrace starého formátu; **bez GL**
- `Seeds` — seed z textu (prázdné = náhodný); **bez GL**
- `GameMode` — survival / creative: jméno, popis a všechna pravidla módu jako pojmenované metody; **bez GL**
- `TerrainGenerator` — neměnný generátor terénu pro jeden seed (výšky, jeskyně, rudy, stromy, spawn); **bez GL**
- `Thumbnails` — náhled světa: framebuffer → PNG a zpátky; **bez GL**
- `BlockRegistry` — bloky z texture labu (id 64–127) nad vestavěnými konstantami, `textures/blocks.json`; **bez GL**
- `BlockDef` — jeden blok z labu: jméno, tvrdost, pevný, neprůhledný, dlaždice po stěnách
- `Json` — malý čtenář a zapisovač JSON pro `blocks.json` (žádná nová závislost)

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
- `CreativeInventory` — obsah creative přehledu: jeden kus od každého placovatelného bloku (vestavěné + z labu)

**Nastavení a obrazovky**
- `Options` — hodnoty nastavení, meze a `options.json`; **bez GL**
- `OptionsScreen` — obrazovka Options ve dvou sloupcích; hit-testy a hodnoty **bez GL**
- `ScreenLayout` — rozvržení obrazovky v GUI pixelech a hit-testy; **bez GL**
- `Widgets` — tlačítko, posuvník, zapuštěné pole a texty pro nové obrazovky
- `TextField` — obsah a úpravy textového pole; **bez GL**
- `WindowMode` — přepnutí okno / celá obrazovka (GLFW), výběr monitoru **bez GL**
- `FrameLimiter` — strop FPS, když je vsync vypnutý; plánování **bez GL**
- `SafeFiles` — atomický zápis textového souboru se zálohou `.bak`; **bez GL**
- `SelectWorldScreen` — seznam světů s náhledy, mazání s potvrzením; hit-testy **bez GL**
- `CreateWorldScreen` — jméno, seed a herní mód nového světa; hit-testy **bez GL**

**2D vrstva**
- `Gui` — celočíselné měřítko UI (i volba GUI Scale) a zarovnání na GUI pixel
- `MouseScale` — poloha kurzoru z bodů okna na pixely framebufferu (Retina); **bez GL**
- `Palette` — ploché barvy UI na jednom místě, aby HUD a menu vypadaly jako jedna věc
- `Renderer2D` — obdélníky, rámečky, bevel, libovolné čtyřúhelníky; sdílí HUD i menu
- `Texture` — RGBA textura, `GL_NEAREST`; `update()` přepíše obsah té samé textury
- `Textures` — procedurální dlaždice, atlas bloků a placeholder skin postavy (jediné místo výměny skinu);
  přepínač atlasu `textures/atlas.png` / procedurální
- `BackgroundRenderer` — dlaždicované pozadí hlavního menu, vlastní texturovaný shader
- `FontAtlas` — ASCII 32–126 → jednokanálová textura (`GL_RED`), bez antialiasingu
- `TextRenderer` — sazba textu, počátek vlevo nahoře, kreslí v celočíselném měřítku
- `Hud` — zaměřovač, hotbar s izometrickými kostkami, ladicí výpis, loading screen
- `Menu` — tlačítka s bevelem, animované zvýraznění, hit-testing
- `ContainerScreen` — kreslení a myš (klik, shift-klik, tažení) nad **seznamem mřížek**; jedna třída pro inventář, crafting table i creative přehled (mřížka s příznakem `infinite` a rolováním)
- `BlockIcon` — izometrická kostka bloku, sdílená hotbarem i sloty

**Texture lab (F6)**
- `TextureLab` — obrazovka: přehled atlasu, plátno, paleta, HSV, hex, tlačítka; kreslení a vstup
- `TextureLabLayout` — rozvržení v GUI pixelech a hit-testy; **bez GL**
- `AtlasEditor` — pixely atlasu: souřadnice, malování tahem, undo, kapátko, barvy, globální paleta; **bez GL**
- `AtlasImage` — atlas jako PNG (ImageIO), řádky překlopené, alfa zachovaná, import s kontrolou rozměru; **bez GL**
- `BlockDraft` — rozepsaný nový blok: vlastnosti, dlaždice stěn, přidělení volné buňky; **bez GL**
- `BlockPreview` — živá kostka: malý skutečný svět → `ChunkMesh` → světový shader, kamera obíhá
- `ImageRenderer` — libovolný výřez textury jako obdélník (shader `UI_TEXTURED`)

**Hra**
- `Player` — hitbox, gravitace, kolize; ohlašuje kroky (`stepped`, `stepBlock`)
- `Mining` — postup rozbíjení bloku a kam jde vytěžený kus (`harvest`); **bez GL**
- `DroppedItem` — jedna hromádka ležící ve světě: poloha, rychlost, AABB kolize; **bez GL**
- `DroppedItems` — všechny položky na zemi: vyhození, sběr, zánik; **bez GL**
- `DroppedItemMesh` — položky na zemi → trojúhelníky pro světový shader; `build()` **bez GL**
- `HandSwing` — máchnutí rukou; **bez GL**
- `DoubleTap` — dvojí stisk klávesy v krátkém okně (přepnutí letu mezerníkem); **bez GL**
- `PlayerAnimation` — chůze, klid a máchnutí ve třetí osobě → `PlayerPose` (úhly kloubů); **bez GL**
- `Raycaster` — DDA (Amanatides–Woo)
- `GameState` — MAIN_MENU / CREATING_WORLD / PLAYING / PAUSED
- `Main` — okno, vstup, stavový automat

**Zvuk (bez OpenAL, kromě `SoundEngine`)**
- `Sound` — všechny zvuky jako druh × materiál; cooldown, obměna výšky a hlasitost druhu; jméno souboru
- `SoundSynth` — procedurální placeholdery jako soubory WAV v paměti
- `Wav` — čtení a zápis WAV (PCM 8/16 bit), stereo se smíchá do mona
- `SoundLibrary` — `sounds/<jméno>.wav`, a když není, syntéza — **jediné místo výměny**
- `SoundThrottle` — cooldown na druh zvuku
- `Footsteps` — kdy zazní krok (podle ujité vzdálenosti)
- `SoundSink` — rozhraní „zahraj zvuk", aby herní logika šla testovat s nahrávačem
- `SoundEngine` — OpenAL: zařízení, kontext, buffery, fond zdrojů, posluchač; životní cyklus jako `World`

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

**⚠️ MYŠ CHODÍ V JINÝCH JEDNOTKÁCH, NEŽ V JAKÝCH SE KRESLÍ.** GLFW vrací polohu
kurzoru v souřadnicích OKNA („points"), ale celé UI se kreslí i hit-testuje
v pixelech FRAMEBUFFERU (`glfwGetFramebufferSize` → `glViewport` → `Renderer2D`).
Na běžném monitoru jsou obě čísla stejná a není to poznat; na Retině je
framebuffer dvakrát větší, takže klik trefil místo dvakrát blíž k levému hornímu
rohu — tlačítka se kreslila správně, ale reagovala „vedle". `Main` proto myš
přepočítá hned v cursor callbacku (`MouseScale`) a obrazovky dostávají všude
tytéž jednotky. Poměr se bere z velikosti okna proti velikosti framebufferu,
**ne z `glfwGetWindowContentScale`**: na Windows se 150 % je content scale 1,5,
ale okno i framebuffer jsou ve stejných pixelech, takže by přepočet podle něj
rozbil to, co funguje. ⚠️ Rozhlížení kamerou zůstává na NEPŘEPOČÍTANÝCH bodech
okna — citlivost myši je v nich a jinak by se na Retině zdvojnásobila.

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

**Ikony v hotbaru i slotech jsou z atlasu** (`BlockIcon`), ne z barev — dřívější
`World.colorFor()` už neexistuje. Blok, kterému `BlockAtlas.tile()` dá dlaždice (i blok
z labu), má tedy ikonu sám od sebe.

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

### Světy na disku

**Každý svět má vlastní složku.** `saves/<složka>/world.dat` (formát `WorldStorage` se
nezměnil ani o bajt), vedle něj `world.json` s metadaty a `icon.png` s náhledem. Název
složky je jen odvozený od jména světa — skutečné jméno je v `world.json`, takže dva světy
se klidně smí jmenovat stejně. `WorldSaves.folderFor()` řeší tři pasti najednou: zakázané
znaky Windows `<>:"/\|?*`, jména zařízení (`CON`, `NUL`, `COM1`… jsou zakázaná i s příponou,
takže `con.txt` → `con_.txt`) a tečky nebo mezery na konci, které Windows tiše zahodí.
K tomu tečka na začátku → `_`, protože skrytou složku seznam světů přeskakuje. Unikátnost
se hledá bez ohledu na velikost písmen (`World (2)`), jinak by se na Windows druhý svět
nasypal do složky prvního.

```json
{
  "format": 2,
  "name": "Cave Base",
  "seed": "99162322",
  "seedText": "hello",
  "gameMode": "creative",
  "created": 1790000000000,
  "lastPlayed": 1790000100000
}
```

**⚠️ `format` je 2 kvůli hernímu módu.** Klíč `gameMode` přibyl ve formátu 2; svět
z formátu 1 ho nemá a je survival (to je přesně to, čím dosud byl). Verze se zvýšila
schválně, i když by chybějící klíč starší čtečka jen ignorovala — právě proto: starší
build by jinak creative svět tiše hrál jako survival, takhle aspoň napíše, že je formát
novější. Svět se i tak načte, seed je v souboru od formátu 1.

**⚠️ Seed je ve `world.json` jako ŘETĚZEC.** `Json` čte čísla jako `double` a ten má
53bitovou mantisu — seed nad 2^53 by se načetl jako jiné číslo, tedy jako úplně jiný svět.
Zápis jde přes `SafeFiles.writeAtomically`, takže platí i `.bak` pro soubor, který nejde
celý načíst. **Poškozená metadata nesmí svět schovat:** `world.dat` je to jediné, co nejde
dopočítat, takže když `world.json` nejde přečíst, zkusí se `world.json.bak` a pak se jméno
odvodí ze složky, seed z `World.DEFAULT_SEED` a časy z `mtime` souboru — svět je pořád
v seznamu a pořád se dá hrát, jen se o tom napíše na stderr.

**Migrace starého `saves/world.dat` je stavěná tak, aby se nedalo přijít o data.** Postup je
kopie do `saves/.migrating` → ověření bajt po bajtu (`Files.mismatch`) → metadata →
přejmenování dočasné složky na světovou → **teprve nakonec** přejmenování starého souboru na
`world.dat.migrated` (nikdy se nepřepíše, druhý dostane `-2`). Starý soubor se **nemaže** —
smaže ho uživatel sám, až si svět ověří. Pád kdekoliv uprostřed nechá starý soubor tam, kde
byl: zbytek `.migrating` se příště smaže a přenos se zopakuje, a když už světová složka
existuje (pád mezi oběma přejmenováními), pozná se podle `migratedFrom` a podle velikosti
a CRC32 zdroje v metadatech a jen se dokončí přejmenování (`FINISHED_EARLIER`) — duplikát
nevznikne ani tehdy, když se v přeneseném světě mezitím hrálo. Přenesený svět se jmenuje
**„Old World"** a dostává `World.DEFAULT_SEED`, protože starý svět vznikl s pevným seedem
12345 a terén pod stavbami musí zůstat tentýž.

**Svět, který nejde načíst, se NEPŘEPISUJE novým.** Dřív byl svět jeden, takže poškozený
soubor prostě znamenal „založ nový". Teď by to znamenalo přepsat konkrétní uložený svět,
a tak `Main.playWorld()` jen napíše důvod na konzoli a nechá hráče v seznamu.

### Seed

**Pravidlo pro políčko Seed je stejné jako v Minecraftu:** prázdné → náhodné číslo, text,
který je číslo → to číslo, cokoliv jiného → `String.hashCode()`. Ten hash není implementační
detail — Java ho má předepsaný specifikací, takže stejný text dá stejný svět napořád i po
upgradu JDK. **`SimplexNoise` je instance se seedem** (statické `noise()` zůstalo pro
`ChunkTest` a jede na výchozím seedu); ⚠️ `Random` si ze seedu bere jen dolních 48 bitů,
takže se horní bity přimíchají, jinak by dva seedy lišící se jen nahoře daly identický svět.
Generování se přestěhovalo z `World` do neměnné třídy `TerrainGenerator` (výšky, jeskyně,
rudy, stromy, spawn) — neměnnost je podmínka, ne ozdoba: worker vlákno na ní volá
`generateColumn()` bez zámku a oddělení od `World` konstrukčně zaručuje, že generátor nevidí
na mapu sloupců ani na změny hráče. Změřeno A/B na témže stroji: horká cesta (výšky + jeskynní
šum přes 160×160 sloupců) vyšla po refaktoru na **29 ms proti 34 ms** předtím a `World.update()`
při letu má tentýž medián — instanční pole generátor nezpomalila.

**⚠️ S `World.DEFAULT_SEED` musí vyjít bit po bitu tentýž terén jako před zavedením seedů**,
jinak by se všem uloženým světům posunul terén pod stavbami (a `GENERATOR_VERSION` se kvůli
seedům nezvyšoval). Seed se proto do hashů stromů a rud míchá XORem hodnoty
`fmix64(seed ^ DEFAULT_SEED)`, která je pro výchozí seed nula — XOR nulou se nepozná. XOR,
a ne přičtení: lineární část hashe je v `x` invertovatelná, takže přičtená konstanta by
znamenala jen posun světa v ose x a dva seedy by daly tentýž vzor stromů a žil, jen jinde.
`SeedTest` shodu hlídá kontrolními součty změřenými na kódu před refaktorem (tři oblasti
bloků včetně záporných souřadnic, výšky na ploše 6000×6000, spawn a výška u něj).

### Náhled světa

`Thumbnails` dělá z obsahu obrazovky obrázek 128×72 a zpátky. ⚠️ `glReadPixels` má řádek 0
**dole**, obrázek **nahoře**, takže se řádky překlápí. ⚠️ Zmenšuje se **průměrováním** (box
filtr přes zdrojové pixely každého cílového), ne vynecháváním — okno je řádově 10× větší,
takže brát každý desátý pixel znamená, že z celého stromu rozhodne jeden texel a náhled šumí.
Nejdřív se ale vystřihne střed se správným poměrem stran, jinak by se krajina natáhla. Zápis
PNG jde přes dočasný soubor a `SafeFiles.moveReplacing`, čtení kontroluje rozměr z hlavičky
ještě před dekódováním — do složky světa může kdokoliv podstrčit fotku 8000×6000 a seznam
světů čte náhledy všech najednou. Celé je to bez GL (dostane hotový buffer), takže to jde
testovat headless.

**Náhled vzniká přesně tam, kde se svět ukládá:** při „Save and Quit to Title" a při zavření
okna uprostřed hry. Svět se pro něj překreslí ZNOVU, bez HUD a bez menu pauzy (`renderWorld()`
a hned `glReadPixels` ze zadního bufferu), takže v seznamu je čistý záběr místa, kde hráč
skončil — ne obrazovka s tlačítky přes půlku.

### Obrazovky světů

**Menu vede přes Singleplayer do seznamu světů**, ne rovnou do hry: hlavní menu má
Singleplayer / Options / Texture Lab / Quit, pauza Resume / Options / Save and Quit to Title.
Seznam je seřazený od naposledy hraného (to je taky ten předvybraný), u každého světa náhled,
jméno, datum a seed; klik vybírá, dvojklik i Enter hrají, šipky přebírají výběr a kolečko
roluje. **Mazání se ptá**, protože je to jediná nevratná věc v celém menu — a maže jen složku
uvnitř `saves/`.

⚠️ **Při potvrzovacím dialogu se texty seznamu vůbec nekreslí.** Text jde na obrazovku až po
všech tvarech (dva průchody, jako v labu), takže by jinak prosvítal skrz panel dialogu —
ztmavení pod ním zakryje jen tvary.

**Zakládání světa je jméno, seed a herní mód.** Pod jménem je vidět, do jaké složky svět půjde, včetně
`(2)`, když stejná složka existuje. Prázdný seed znamená náhodný svět; hint pod polem to
říká. Ctrl+V vloží do pole, ve kterém je fokus — seedy se obvykle odněkud kopírují.
Pod seedem je cyklující tlačítko Game Mode s popisem toho, co mód znamená; volba se
uloží ke světu a za běhu se nemění (viz **Creative mód**).

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

**S prázdným slotem je vidět holá ruka — TENTÝŽ kvádr jako pravá ruka modelu postavy.**
Rozbalení kvádru do skinu (UV) je jediná funkce `PlayerModelMesh.unfold()` a volá ji postava
i `HeldItemRenderer`, takže ruka vypadá v první i třetí osobě stejně a skin se mění na jednom
místě; `PlayerModelTest` porovnává UV obou stěnu po stěně. Kreslí se stejným shaderem jako
blok, jen s navázaným skinem místo atlasu, se stejnými odstíny stěn a stejným světlem.
Klidová póza je z Minecraftu 1.8 (`renderPlayerArm`), rozepsaná na tři otočení kolem ramene
(stočení 55°, zdvih 125°, odklon −25°); rameno leží pod dolním okrajem obrazu, takže je
vidět jen předloktí a pěst. **Máchnutí má dva klouby:** kolem ramene se předloktí stočí
dovnitř a kolem OKA se celá ruka posune po oblouku k zaměřovači (rychlá křivka) a v druhé
půlce pod klidovou polohu (pomalá křivka) — kolem samotného ramene by pěst k zaměřovači
nedosáhla. **Kliknutí levým do vzduchu máchne** s prázdnou rukou i s blokem, jako
v Minecraftu; držení do vzduchu už znovu nemáchá, opakované máchání zůstává kopání.

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
kroku. V první osobě se vlastní tělo nekreslí vůbec a zůstává `HeldItemRenderer` — s blokem,
nebo s holou rukou z téhož kvádru a skinu jako model (viz „Blok v ruce").

**Známá zjednodušení:** trup se natáčí přesně s pohledem (Minecraft nechává tělo zaostávat až
o 50° a za chůze ho stáčí do směru pohybu); chybí poloha při plížení a plavání; druhá vrstva
skinu (klobouk, bunda) se nekreslí; starší skiny 64×32 bez levé ruky a nohy by se musely
zrcadlit; přepnutí pohledu nemá přechod; zaměřovač zůstává vidět i ve třetí osobě (Minecraft
ho tam skrývá); postava nevrhá stín. Světlo je jedno číslo pro celou postavu, z buňky s hlavou.

### Zvuk

**OpenAL přes `lwjgl-openal`**, stejná verze a stejné natives (win/linux/macos) jako ostatní
moduly LWJGL. V natives je přibalený OpenAL Soft, takže se nic neinstaluje.

**⚠️ Zvukový engine žije stejně jako svět.** `SoundEngine.open()` při startu; při založení
i načtení světa `shutdown()` a nový `open()` — hned vedle `World.shutdown()` ve `freshWorld()`;
při ukončení hry `shutdown()`. Nový svět tak nezdědí nic, co ještě hraje ze starého: zdroje,
buffery i kontext jsou nové. `shutdown()` jde volat opakovaně a uklidí i po nepovedeném otevření.

**Změřeno** (stroj měl ~55 % zátěže z jiných programů):

| | |
|---|---|
| první otevření | **285–330 ms** — načtení nativní knihovny 160–210 ms, kontext 80–195 ms, zbytek zařízení, syntéza a buffery |
| úplně první spuštění po stažení knihovny | 1,5 s — LWJGL rozbaluje natives do cache, jen jednou |
| každé další otevření (nový svět) | 55–150 ms |
| zavření | 24–55 ms |

Proto se engine otevírá **dřív než okno** — čtvrtsekunda se lépe prosedí před oknem než na
zamrzlém černém obraze. Znovuotevření při zakládání světa zdrží kliknutí na „Create World"
o ~0,1–0,2 s, než naskočí loading screen; samotné generování trvá déle.

**⚠️ Chyba zvuku hru nepoloží.** Bez zvukového zařízení nebo bez nativní knihovny se engine
otevře tichý: důvod na stderr, `play*()` nic nedělají, ladicí výpis ukáže `sound off`. Stejný
přístup jako u ukládání světa.

**⚠️ Zvuk kliknutí v menu hraje AŽ PO akci tlačítka.** „Create World" a „Load World" engine
zavřou a otevřou nový, takže zvuk pouštěný před akcí by se hned uťal. `Hud` nemá nic
klikacího a sloty inventáře nezní ani v Minecraftu.

**Placeholdery jsou syntetizované — stejná filozofie jako procedurální textury.** Žádné soubory,
jen kód, který zvuk spočítá (`SoundSynth`): šum z hashe (vyjde pokaždé stejně), jednopólové
filtry a obálka s náběhem a doběhem do nuly (bez lupnutí). Zvuk je druh × materiál. Druh dává
délku a doznívání — krok 0,09 s, položení 0,13 s, rozbití 0,24 s —, materiál barvu: hlína je
tlumený zrnitý šum, kámen ostřejší šum s cvaknutím 140 Hz, dřevo tlumený tón 200 Hz
s klepnutím, listí vysoký šum s pomalým náběhem. Kliknutí je pípnutí 1,4 kHz. Všech 13 zvuků
má dohromady 81 KB a 1,9 s; syntéza se vejde do otevření enginu.

**⚠️ Syntéza nevyrábí vzorky pro OpenAL, ale SOUBOR WAV v paměti.** Nahrávka z disku je jen jiný
zdroj týchž bajtů a obojí jde stejným dekodérem (`Wav.decode`). Výměna za skutečné zvuky proto
nevyžaduje změnu kódu: **stačí položit `sounds/<jméno>.wav` vedle hry** (třeba
`sounds/break_stone.wav`; jména dává `Sound.fileName()`). Soubor přebije syntézu, ostatní zvuky
zůstanou syntetizované, takže se dá nahrazovat po jednom. Nečitelný nebo nepodporovaný soubor
se ohlásí na stderr a hraje placeholder. Rozhoduje se na jediném místě, v `SoundLibrary.load()`.

**Čte se WAV PCM 8 i 16 bit, mono i stereo; .ogg zatím ne.** Dekodér Vorbisu je v LWJGL
v modulu `lwjgl-stb` (STBVorbis), tedy další závislost. Až bude potřeba, je to jeden modul
v `pom.xml` a jedna větev v `SoundLibrary.load()`.

**⚠️ Všechno se čte jako MONO.** OpenAL umisťuje do prostoru jen monofonní buffery — stereo by
hrálo „do uší" bez ohledu na polohu zdroje. Stereo soubor se proto při čtení smíchá do jednoho
kanálu; nahrané rozbití bloku by jinak znělo vždycky zepředu.

**V prostoru zní jen rozbití a položení bloku**, ze středu bloku. Kroky a UI jsou nepoziční
(zdroj relativní k posluchači na nule). Posluchač je kamera, i ve třetí osobě, aby zvuk seděl
s obrazem. Útlum je lineární s ořezem: do 1 bloku plná hlasitost, na 16 blocích ticho, jako
v Minecraftu. Výchozí inverzní model OpenAL jen slábne donekonečna a úplně neutichne nikdy.

**Poloha zdrojů je ve světových souřadnicích jako float — na rozdíl od kreslení.** U obrazu
dělá krok floatu daleko od počátku (~8 mm na 100 000) třesoucí se geometrii; ucho to nepozná,
takže převod relativně ke kameře tu není potřeba.

**Materiál bloku je STEJNÉ ROZDĚLENÍ jako tvrdost** (`World.hardness()`): co má stejnou
tvrdost, zní stejně. Jen rudy s vlastní tvrdostí zní jako kámen a pochodeň jako dřevo, ze
kterého je. `SoundTest` projde všechny bloky a hlídá, že se tabulky nerozejdou, když přibude
nový blok.

**⚠️ Cooldown je na DRUH zvuku, ne na jednotlivý zvuk.** Rychlé kopání střídavě hlíny a listí
by jinak prošlo, protože každý má svůj zvuk — a „kulomet" je problém druhu. Rozbití a položení
0,1 s, krok 0,15 s, kliknutí 0,05 s. Co přijde během cooldownu, se zahodí, neodkládá: zvuk
zahraný opožděně by k ničemu nepatřil. Změřeno v `SoundTest`: rozbíjení každý frame zazní
**10× za sekundu místo 60×**. Odmítnutý pokus cooldown neprodlužuje — jinak by při drženém
tlačítku nezaznělo už nikdy nic.

**Výška tónu se náhodně mění**, rovnoměrně v 1 ± 0,08 (krok), ± 0,1 (rozbití, položení)
a ± 0,03 (kliknutí). Dva kroky za sebou pak nezní jako jedna nahrávka puštěná dvakrát; UI
má znít pořád stejně.

**Kroky se počítají z UJITÉ VZDÁLENOSTI, ne z času** — krok každých 1/0,6 bloku (to je
`distanceWalkedModified` z Minecraftu), takže interval je přesně délka kroku / rychlost:
**0,39 s chůzí, 0,30 s sprintem, 1,3 s plížením**. Časovač by musel řešit, co s rozběhnutým
krokem, když se uprostřed změní rychlost. Posun je skutečný (chůze do zdi nešlape), vzdálenost
se sčítá i ve vzduchu, ale krok zazní jen na zemi — po skoku dopředu tedy při dopadu, a po
dlouhém letu jeden, ne salva. Hráč krok jen ohlásí (`Player.stepped`, `stepBlock`) a zvuk
pouští Main, takže `Player` dál nesahá na nic kromě `World`.

**Blok pod nohama se hledá i pod rohy hitboxu.** Hráč na hraně stojí středem nad vzduchem
a drží ho kraj hitboxu; bez toho by chůze po hraně byla neslyšná.

**Hlasitost je jedna konstanta**, `SoundEngine.MASTER_VOLUME` (0,7).

**Známá zjednodušení:** jeden zvuk na druh a materiál (Minecraft jich má několik a střídá je —
tady to zastupuje obměna výšky); chybí ťukání při kopání, dopad z výšky, plavání a hudba;
fond má 16 zdrojů a když jsou všechny obsazené, nový zvuk se zahodí; kliknutí na „Quit" utne
ukončení hry; kroky zní i po dně pod vodou.

### Nastavení (Options)

**Co bylo natvrdo v kódu a teď je v nastavení:** FOV (`Main.FOV = 70`), dohled
(`World.renderDistance = 96` bloků), okruh načítání (`World.loadRadius = 8`,
`unloadRadius = 10`), citlivost myši (`Camera` 0,12 °/px) a měřítko UI (`Gui.scale`
čistě automatické). Za běhu šel dřív přepnout jen vsync (V) a to se nikam neukládalo;
celá obrazovka ani fullscreen neexistovaly. **Natvrdo dál zůstává** délka dne
(`DayCycle.DAY_LENGTH`), hlasitost (`SoundEngine.MASTER_VOLUME` — zvuk je mimo rozsah
zadání), rozpočty na stavbu meshů, dosah zvuku, vzdálenost kreslení položek na zemi
a měřítko texture labu.

**Deset hodnot, a každá je tu proto, že engine umí, co mění:** Fullscreen, VSync,
Max Framerate, Render Distance, Simulation Distance, FOV, Brightness, GUI Scale,
Sensitivity, Invert Mouse. Vynechané jsou věci, které by musel nejdřív umět engine
(hlasitost je mimo zadání, plynulé osvětlení jde zapnout jen přestavbou všech meshů,
mraky a částice nejsou, přebindování kláves je mimo rozsah).

**⚠️ Render distance nikdy není větší než simulation distance.** Simulation distance
je, kam se sloupce NAČÍTAJÍ (generují, svítí, drží změny a předměty na zemi); render
distance je, co se z nich kreslí — kreslit jde jen načtené. Posunutí jednoho posuvníku
přes druhý proto potáhne i ten druhý. (Vanilla Minecraft má vztah opačně, protože
tam se chunky posílají klientovi na render distance a simulation distance řeší jen
tikání; tady „simulation" přímo znamená načtení, takže opačný vztah nedává smysl.)
Převod na engine: `loadRadius = simulation + 2` a `renderDistance = render · 16`.
Ty dva chunky navíc jsou proto, že se sekce mešuje, až když jsou načtení všichni čtyři
sousedi, a kamera může stát kdekoliv ve svém chunku. Výchozí 6/6 dá přesně dnešních
`loadRadius 8` a `renderDistance 96`.

**Změna se projeví HNED.** Obrazovka mění přímo `Options` a hlásí to Mainu, který
`applyOptions()` rozveze do `World` (okruhy, dohled), `Camera` (citlivost, obrácená
osa), `Gui` (měřítko), `WorldRenderer` (jas), GLFW (vsync, fullscreen) a do matice
(FOV). Posuvník render distance tak ubírá a přidává svět při tažení — proto se
obrazovka otevřená z pauzy kreslí přes svět a svět se pod ní dál generuje.

**Jas nezvedá všechno stejně.** Ztmavení stěn je v tomhle enginu ZAPEČENÉ ve světle
vrcholu (boky 0,6 a 0,8, spodek 0,5), takže gamma křivka z Minecraftu by na plném
slunci srovnala boky s vrškem a bloky by ztratily tvar. Vzorec je proto
`light + 0,25 · jas · (1 − light)⁴`: tmu zvedne (0,05 → 0,26), šero znatelně
(0,2 → 0,30) a osvětlené stěny skoro vůbec (0,6 → 0,606). Je ve `Options.brighten()`
i ve fragment shaderu světa (`uBrightness`); nenastavený uniform je 0, takže náhled
v labu vypadá dál stejně.

**Fullscreen přepíná TÝŽ window a TÝŽ GL kontext** (`glfwSetWindowMonitor`), takže se
nic nenahrává znovu. Poloha a velikost okna se zapamatují před přepnutím, jinak by
se okno vrátilo do rohu v rozlišení monitoru. Jde na monitor, na kterém okno leží
největší plochou — na dvou monitorech by jinak hra skočila na primární. F11 přepíná
odkudkoliv, jako v Minecraftu.

**Strop FPS čeká na PLÁNOVANÝ začátek dalšího framu**, ne „period od konce tohohle" —
jinak by FPS vyšlo vždycky nižší než strop (frame 3 ms + čekání 8,3 ms = 88 místo 120).
Spí se `Thread.sleep`, poslední milisekunda se dočeká aktivně (Windows budí vlákno
s přesností kolem milisekundy). S vsyncem se nečeká vůbec — ten frame časuje sám.

**Soubor `options.json` v pracovním adresáři hry** (vedle `saves/` a `textures/`),
stejný vzor jako `textures/blocks.json`: chybějící = výchozí hodnoty mlčky, poškozený =
výchozí hodnoty a zpráva na stderr, **jedna špatná hodnota = výchozí jen pro ni**
(ostatní se načtou), hodnota mimo meze se ořízne a ohlásí. Zápis je atomický přes
`.tmp` a nečitelný soubor se před přepsáním zálohuje do `options.json.bak` —
společný kód je v `SafeFiles`. Ukládá se při zavření obrazovky a hned po F11 / V.

```json
{
  "format": 1,
  "fullscreen": false,
  "vsync": true,
  "maxFps": 0,
  "renderDistance": 6,
  "simulationDistance": 6,
  "fov": 70,
  "brightness": 0.00,
  "guiScale": 0,
  "sensitivity": 1.00,
  "invertMouse": false
}
```

`maxFps: 0` = bez stropu, `guiScale: 0` = automaticky. **Zvolené GUI měřítko nikdy
nepřeroste to, co se vejde** (`Gui.scale`), jinak by na malém okně byla tlačítka mimo
obrazovku.

**Obrazovky mimo lab mají společné kousky** (`ScreenLayout`, `Widgets`, `TextField`):
tlačítko s bevelem jako v menu, zapuštěné pole jako slot inventáře a posuvník jako
HSV posuvníky labu. Není to nový UI systém — je to totéž, co už hra kreslí, jen na
jednom místě. Rozvržení je v GUI pixelech a měřítko je `Gui.scale()`, takže obrazovky
reagují na volbu GUI Scale ve stejném framu.

### Texture lab

**Vývojářská obrazovka na úpravy dlaždic atlasu, na F6 nebo z hlavního menu („Texture Lab").**
Vlevo přehled atlasu (klik vybere dlaždici), uprostřed dlaždice jako plátno 16×16 (levé
tlačítko maluje, tažením čára, pravé bere barvu), vpravo živý 3D náhled bloku, pod tím paleta,
HSV posuvníky, hex a tlačítka Save / Revert / Close. F6 je volná — Minecraft ji nepoužívá,
F3 je ladicí výpis a F5 pohled. Otevřený ze hry nechává za sebou kreslit svět.

**⚠️ Proč živý náhled přes SKUTEČNÝ shader, ne malování naslepo.** Dlaždice na plátně vypadá
jinak než na bloku: boky jsou ztmavené na 0,6 a 0,8, spodek na 0,5, a vzor, který se na plátně
jeví jako nenápadný šum, se na stěnách vedle sebe opakuje jako tapeta. Malovat naslepo znamená
pro každou změnu restartovat hru. Náhled proto nejde „podobnou kostkou", ale celou cestou jako
hra: blok stojí v malém skutečném světě, světlo mu spočítá `LightEngine`, sekci postaví
`ChunkMesh.build()` a kreslí ji světový shader s týmž atlasem. `SHADE_*`, UV vzorce,
půltexelové zúžení, modely (pochodeň, plot), průhlednost vody i záře pochodně jsou tak
přesně jako ve hře, protože je počítá tentýž kód. `TextureLabTest` porovná mesh náhledu
s meshem, který hra postaví pro tentýž blok v jiném světě — bajt po bajtu.

**⚠️ Prázdný svět NESTAČÍ — změřeno testem.** První verze stavěla náhled nad světem bez
načtených sloupců: `World` na nenačtené místo odpovídá „vzduch s plným sluncem", což vypadá
jako přesně okolí bloku ve vzduchu. Test ale ukázal rozdíl ve spodní stěně všech plných
krychlí: ve hře plný blok zastíní buňku pod sebou (sluneční světlo 14, ne 15), takže jeho
spodek vyjde 0,4917 místo 0,5. Malý skutečný svět (3×3 sloupce) ten vlastní stín má.
Změřeno: připravit ho trvá **23 ms**, přepnutí bloku v náhledu (položení, světlo, mesh)
nejvýš **2 ms**, otevření celého labu **~100 ms**, frame labu **1,4 ms**.

**⚠️ OTÁČÍ SE KAMERA, NE BLOK.** Ztmavení stěn je ve hře vázané na osy SVĚTA (vršek 1,
boky X 0,6, boky Z 0,8). Kdyby se točil blok, točily by se s ním i odstíny a náhled by
ukazoval něco, co ve hře nikdy neuvidíš. Kamera obíhá kolem bloku jako hráč, který kolem něj
chodí. Světlo je poledne bez mlhy.

**⚠️ Lab maluje PŘÍMO DO ATLASU HRY, ne do kopie.** `AtlasEditor` upravuje totéž pole pixelů,
ze kterého je nahraná textura atlasu, a lab ho jednou za frame (když se něco změnilo) nahraje
do TÉŽE textury přes `Texture.update()` (`glTexSubImage2D`). Změnu tak ve stejném framu vidí
náhledová kostka, ikony v hotbaru i svět za labem. Ověřeno sondou s GL oknem: po přemalování
dlaždice kamene načerveno měla kostka hned v dalším framu barvu přesně červená × 0,8 (bok Z).
Neuložené úpravy zůstanou ve hře do jejího ukončení; na disk je dostane až Save.

**Plátno a přehled atlasu se kreslí z textury na grafice, ne z pixelů na CPU** (`ImageRenderer`
na existujícím shaderu `UI_TEXTURED`). Ukazují tedy přesně to, co pak vzorkuje svět. Výřez
dlaždice na plátně jde přesně po hranách, ne se zúžením z `BlockAtlas` — to by krajní pixely
ukázalo poloviční. Plátno je zarovnané na celé pixely, takže středy fragmentů hranu netrefí.

**⚠️ Řádek 0 je DOLE — na plátně, v přehledu atlasu i v poli pixelů.** Tak čte data GL a tak
počítá řádky `BlockAtlas`; dlaždice pak na plátně stojí stejně jako na boku bloku. Myš chodí
z GLFW s počátkem nahoře, takže se řádek překlápí na jediném místě, v `TextureLabLayout`.

**Editovaných 16×16 texelů je přesně to, co hra vzorkuje.** UV z `BlockAtlas` jsou zúžené
o půl texelu a končí ve STŘEDECH krajních texelů dlaždice; při `GL_NEAREST` je tak dostupných
všech 16 sloupců a řádků a ani jeden texel sousední dlaždice. `TextureLabTest` to ověřuje pro
každou dlaždici.

**Lab má vlastní referenční velikost 448×300 GUI pixelů**, ne 320×240 z `Gui`: vedle sebe
potřebuje atlas, plátno i náhled a pod nimi paletu celého atlasu. Bere největší CELÉ
měřítko, při kterém se vejde (na 1024×768 je to 2, na Full HD 3), takže pixely zůstávají
ostré. S výškou 256 (bez palety atlasu) to na Full HD byla 4 — paleta stála jeden stupeň.
`TextureLabTest` hlídá, že se v žádném režimu dva ovládací prvky nepřekrývají.

**Paleta:** první řádek pevný — průhledná (guma, na vodu a praskliny), černá, tři šedé, bílá
a šest sytých barev; druhý řádek jsou nejčastější barvy vybrané dlaždice, protože pixel-art
se maluje hlavně odstíny, které v dlaždici už jsou. K tomu kapátko (pravé tlačítko), HSV
posuvníky a hex `AARRGGBB` (klik na pole, psát, Enter). HSV se drží zvlášť, ne jen jako
přepočet z barvy — u šedé by se jinak odstín ztratil a posuvník odstínu skákal na červenou.

**Paleta celého atlasu (pruh dole).** Paleta dlaždice ukazuje jen odstíny té jedné
dlaždice; na sjednocení odstínů napříč bloky je potřeba vidět všechny. `AtlasEditor.atlasColors()`
je čistá funkce nad polem pixelů: nejčastější barvy celého atlasu (bez plně průhledných —
to jsou prázdné buňky), nejvýš 86. **Zobrazují se seřazené podle odstínu, ne podle četnosti**:
šedé napřed, pak výseče po 30° a v každé od tmavé ke světlé. Dvě skoro stejné hnědé
z různých bloků tak leží hned vedle sebe a je vidět, že jsou dvě. **Najetí myší na vzorek
(i v paletě dlaždice) orámuje v přehledu atlasu dlaždice, které tu barvu obsahují.**
Přepočítává se jen po změně pixelů (`AtlasEditor.revision()`), ne každý frame.

**Import hotového PNG** (z Aseprite, GIMPu…) jde **do editoru, ne na disk**: `AtlasImage.importInto()`
obrázek přečte, zkontroluje a teprve pak ho celý najednou vloží do pole atlasu. Jde vrátit
jedním Ctrl+Z (snímek celého atlasu, 64 KB) a na disk ho dostane až Save. Zdroj je
`textures/import.png` (tlačítko Import PNG), nebo **libovolný soubor přetažený do okna**
(GLFW drop callback). Okno výběru souboru to není schválně: AWT běží headless (kvůli
macOS, viz `Main`) a tinyfiledialogs by byl nový modul LWJGL. **⚠️ Rozměr se čte
z hlavičky dřív, než se obrázek dekóduje** — fotka 8000×6000 se odmítne bez stovek megabajtů
v paměti. Jiný rozměr než 128×128, poškozený soubor i ne-obrázek skončí hláškou
(„Not imported: image is 64x64, needs 128x128 - atlas unchanged") a atlas zůstane, jak byl.

**Undo (Ctrl+Z) je snímek dlaždice před tahem**, 1 KB na krok, nejvýš 100 kroků. Celý tah
tažením je jeden krok. Revert undo zahazuje — vracel by tahy na jiný obsah.

**Kam se ukládá: `textures/atlas.png`**, relativně k pracovnímu adresáři hry, stejně jako
`saves/` a `sounds/`. PNG 128×128 s alfou; řádky se při zápisu i čtení překlápějí, takže
v editoru obrázků leží atlas správně (dlaždice 0 vlevo dole, tráva nahoře ne vzhůru nohama).
AWT `ImageIO` je součást JDK — žádná nová závislost.

**⚠️ Přepínač procedurální / nahraná textura je jeden: existence souboru.** `Textures.atlasPixels()`
při startu zkusí `textures/atlas.png`; když existuje a má 128×128, použije se, jinak procedurální
generování jako dřív. Smazání souboru vrací hru k procedurálnímu atlasu. Poškozený soubor nebo
jiný rozměr se ohlásí na stderr a hra jede procedurálně. Který atlas běží, píše konzole při
startu (`Atlas bloku: …`), ladicí výpis (`atlas textures/atlas.png` / `atlas procedural`)
i titulek labu. Revert v labu vrací k tomu, co je na disku. `BlockAtlas` o ničem z toho neví —
pro něj jsou to pořád jen pixely v mřížce.

**Mapování blok → dlaždice lab nemění**, jen čte (`BlockAtlas.tile()`): u dlaždice ukáže, které
bloky ji používají, seřazené podle počtu stěn (hlína: hlína na šesti stěnách, pak spodek trávy),
a klik na náhled přepne na další z nich. Praskliny a volné buňky žádný blok nemají — jdou
malovat, jen bez náhledu.

**Známá zjednodušení:** jeden atlas, žádné resource packy; mapování VESTAVĚNÝCH bloků lab
jen čte (nové bloky a jejich dlaždice viz „Bloky z labu"); bez animovaných textur; praskliny
nemají náhled přes blok; náhled je vždycky poledne; import neumí jiný rozměr mřížky.

### Bloky z labu (datově řízené)

**Proč data, a ne kód.** Přidat blok znamenalo čtyři místa v kódu (konstanta ve `World`,
dlaždice v `BlockAtlas`, kresba v `Textures`, case ve switchích) a restart. Lab teď založí
blok z dat: jméno, tvrdost, pevný, neprůhledný a dlaždici pro vršek, bok a spodek. Kód se
nemění a vestavěné bloky zůstávají přesně, jak byly — **jen přidávání, nic se nepřejmenuje
ani nepřečísluje**. Data drží `BlockDef` a neměnný `BlockRegistry`; `World.isOpaque()`,
`blocksMovement()`, `hardness()`, `BlockAtlas.tile()` a `Sound.Material.of()` se pro id od 64
zeptají aktivního registru. Tím se blok dostane všude, kam tyhle funkce vedou: mesher, světlo,
kolize, paprsek, kopání (`Mining`), ikony, blok v ruce, předměty na zemi, model postavy.

**⚠️ Rozsah id je rozdělený napevno: 0–63 vestavěné bloky v kódu, 64–127 bloky z labu.**
Kdyby lab přiděloval hned za posledním vestavěným blokem (15, 16…), další blok přidaný do
kódu by dostal id, které už v uloženém světě nese blok z labu. Nad 127 se nejde — id je
`byte` a záporná čísla by rozbila porovnání `id >= FIRST_ID`. Bloků z labu je tedy nejvýš 64.

**⚠️ Id se nikdy nepoužije dvakrát.** Nový blok dostane `nextId`, které se ukládá do souboru
a jen roste — ne „nejnižší volné". Blok ručně smazaný z `blocks.json` tak své id nepředá
dalšímu; v uloženém světě by se jinak jeho kostky tiše proměnily v nový blok.

**⚠️ Nové dlaždice se berou od konce atlasu (63, 62…).** Vestavěné dlaždice přibývají v kódu
odspodu (dnes končí na 26), takže se obě skupiny potkají až úplně na konci — stejná úvaha
jako rozdělení id. Volná buňka = žádný vestavěný blok, žádný blok z labu, ne praskliny, ne
„neznámý blok". Dnes je jich 37; když dojdou, lab napíše „Atlas is full - reuse an existing tile".

**Soubor `textures/blocks.json`** je JSON (UTF-8, odsazení dvě mezery, `\n`, bloky podle id):

```json
{
  "format": 1,
  "nextId": 65,
  "blocks": [
    {
      "id": 64,
      "name": "Marble",
      "hardness": 1.5,
      "solid": true,
      "opaque": true,
      "tiles": {"top": 63, "side": 62, "bottom": 63}
    }
  ]
}
```

Čte a píše ho vlastní malý parser `Json` (žádná nová závislost). **Chybějící soubor = mlčky
prázdný registr a hra je přesně jako dřív**, stejný vzor jako `textures/atlas.png`. Soubor,
který není JSON nebo mu chybí `format` či `blocks`, se ohlásí na stderr a hra jede jen
s vestavěnými bloky. Neplatný jednotlivý blok (id mimo 64–127, duplicitní id nebo jméno,
dlaždice mimo atlas…) se jen přeskočí se zprávou. Novější `format` se načte s varováním.
**`nextId` = max(ze souboru, největší id v souboru + 1, 64)** a počítají se i přeskočené bloky.
**Zápis je atomický:** data jdou do `blocks.json.tmp`, po `force()` se přejmenuje přes starý
soubor, takže pád uprostřed zápisu nechá starý soubor celý. **Soubor, který nejde celý
načíst** (poškozený, s přeskočeným blokem, novějšího formátu), se před přepsáním zkopíruje do
`blocks.json.bak` — jinak by první uložení z labu tiše smazalo, co registr v paměti nemá.

**Načítá se při startu PŘED atlasem.** Když chybí `textures/atlas.png`, procedurální atlas
dlaždice bloků z labu nezná; `Textures.markMissingTiles()` je vyplní šachovnicí „neznámý blok"
místo průhledné (v neprůhledném průchodu černé) díry. Bez bloků z labu se atlas nemění ani o pixel.

**Uložený svět se nemění.** Blok z labu je v něm `byte` jako každý jiný a jeho id je stabilní,
takže `saves/world.dat` nepotřebuje novou verzi — svět bez bloků z labu je bajt po bajtu stejný
jako dřív. Stejná opatrnost jako u `GENERATOR_VERSION`: když svět nese id, které `blocks.json`
nezná (soubor zmizel), `WorldStorage.load()` varuje na stderr a svět se načte; ty kostky jsou
pevné neprůhledné s dlaždicí „neznámý blok" (chování neznámého id z dřívějška) a jakmile se
soubor vrátí, jsou zase správně.

**Postup v labu:** New block → napsat jméno (znaky přes GLFW char callback, jen ASCII) →
tvrdost `-`/`+` po krocích, které obsahují všechny tvrdosti vestavěných bloků (u hodnoty se
píše, kterému odpovídá: „1.8 s Stone") → Solid / Opaque → vybrat stěnu (Top / Side / Bottom)
a kliknout na dlaždici v atlasu, nebo **New tile**: stěna dostane volnou buňku s kopií své
dosavadní dlaždice a hned se maluje na plátně → Create. **Náhled ukazuje rozepsaný blok přes
skutečný mesher**: lab po každé změně aktivuje dočasný registr s návrhem (s id, které blok
dostane) a náhled postaví znovu; Cancel, Esc i zavření labu vrátí původní registr. **Create
uloží nejdřív `atlas.png`, pak `blocks.json`** — nové dlaždice existují jen v pixelech atlasu,
takže blok bez uloženého atlasu by po restartu byl bez textur; když atlas uložit nejde, blok
se nezaloží. Hráč pak dostane **jednu hromádku (64 ks) do prvního volného slotu** (plný
inventář: vyhodí ji před sebe); z labu otevřeného v menu ji dostane v prvním světě, jinak by
ji načtení uloženého inventáře přepsalo.

**Meze:** tvar je vždycky plná krychle — model (pochodeň, plot) je kód v `BlockModels` a z dat
ho udělat nejde. Bez receptu (blok jde jen z labu). Nesvítí. Bloky z labu se v labu nedají
upravit ani smazat, vestavěné bloky už vůbec ne. **„Neprůhledný = ne" mění culling a světlo,
ne průhlednost pixelů** — blok jde neprůhledným průchodem jako listí, takže průhledný pixel
se kreslí svou barvou (skutečné sklo by potřebovalo průhledný průchod jako voda). Paprsek
blok z labu zaměří vždycky, i ducha (ne pevný, ne neprůhledný) — jinak by nešel vytěžit.
Zvuk se odvodí z tvrdosti stejnými skupinami jako u vestavěných bloků.

### Creative mód

**Mód je vlastnost SVĚTA, ne hráče.** Vybírá se při zakládání světa (cyklující
tlačítko „Game Mode: Survival / Creative" na obrazovce Create New World, pod ním
jednořádkový popis jako v Minecraftu), ukládá se do `world.json` vedle seedu a za
běhu se **nemění**. Měnit ho v Minecraftu umí jen příkaz `/gamemode`, a příkazová
řádka tu není — postavit ji kvůli jednomu přepínači by byl větší kus práce než celý
creative mód. Přepínač v Options by zase znamenal, že survival svět jde jedním klikem
„vyléčit" a hra o pravidla přijde. Pevná volba při založení je tedy záměr, ne
zjednodušení, a `world.json` se dá v nouzi přepsat ručně (mód je tam textem).

**⚠️ Všechna pravidla módu jsou pojmenované metody na `GameMode`, ne `if` rozsypané
po kódu.** `instantMining()`, `keepsMinedBlock()`, `canFly()` a `afterPlace()` — každá
otázka, kterou se hra na mód ptá, je vidět na jednom místě. Bez toho by nešlo najít, co
všechno creative mění, a hlavně by se dala snadno změnit i survival větev. Takhle je
survival doslova „to, co bylo": `Mining.update()` i `harvest()` mají původní signaturu
jako přetížení, které dosadí `SURVIVAL`, a `CreativeTest` u každého creative pravidla
ověřuje, že volání bez módu vyjde stejně jako předtím.

**Těžba je okamžitá, ale pravidla platí dál.** Creative větev v `Mining.update()`
zkracuje jen ČEKÁNÍ — test na `World.isTargetable()` zůstává nad ní, takže se vzduch ani
voda nerozbijí ani v creative. Praskliny se nestihnou objevit (`stage()` vrací −1), což
je správně: blok praskne ve framu, kdy se na něj začne s drženým tlačítkem mířit.
Držená myš pak při přejíždění boří blok za blokem, stejně jako ve vanille; zvuk před
kulometem chrání `SoundThrottle`, který tam byl dřív.

**Vytěžený blok v creative MIZÍ** — nejde do inventáře ani nevypadne na zem.
`Mining.harvest()` se v creative inventáře a seznamu položek vůbec nedotkne, takže se
jich nemá jak dotknout ani omylem. Rozbití a zvuk jsou pro oba módy tytéž; liší se
jen to, co se stane s kusem.

**Pokládání nespotřebovává.** Po úspěšném `World.placeBlock()` volá `Main` jeden řádek
`mode.afterPlace(inventory, slot)`; v survivalu je to původní `removeOne()`, v creative
nic. Je to metoda, a ne podmínka v `Main`, aby to šlo otestovat headless — pokládání
samo je uvnitř GL smyčky.

**Creative přehled je JINÁ OBRAZOVKA než inventář na E, ne jeho režim.** Postavená je
ale na témže `ContainerScreen` — je to jen další tovární metoda (`creativeInventory`),
přesně jak to ta třída od začátku zamýšlela: „batoh + hotbar + mřížka všech bloků"
místo „batoh + hotbar + crafting + výsledek". Panel je vyšší (186×206 místo 176×166),
crafting mřížka tam schválně není a nahoře je mřížka 9×5 s posuvníkem.

**⚠️ Horní mřížka je NEKONEČNÝ ZDROJ (`infinite`), ne kontejner k přesouvání.** Braní
z ní vrací KOPII a ve slotu zůstává, co tam bylo — jinak by si hráč přehled po chvíli
vysbíral. Položit do ní něco znamená to zahodit (koš, jako v Minecraftu), shift-klik
kopíruje rovnou do hotbaru (do batohu by blok zmizel do řady, kterou hráč nemá na
očích) a do tažení hromádky se nezařadí. Nekonečnost je pravidlo OBRAZOVKY, ne
`Container`u — ten o sobě dál neví nic, takže survival inventář ani crafting se
nemusely dotknout.

**Ve slotu přehledu je jeden kus, protože se jeden kus nikdy nespotřebuje.** Co je
vidět, to se taky vezme. Minecraft dává 64, ale tady se pokládáním neubírá, takže
by se vyšší číslo jen rozcházelo s tím, co je ve slotu napsané.

**Obsah přehledu se POČÍTÁ, neskládá ručně** (`CreativeInventory`). Vestavěné bloky
jsou id 1 až `World.LAST_BUILT_IN` (nula je vzduch, tedy „nic", ne blok), bloky z labu
si řekne aktivní `BlockRegistry`. Nový blok — v kódu i v labu — se tím v přehledu
objeví sám, bez druhého místa na údržbu; `World.LAST_BUILT_IN` je jediná konstanta,
kterou je při přidání vestavěného bloku potřeba zvýšit (samotné `hardness()` ani
`isOpaque()` neexistenci id nepoznají, mají default větev). Voda v přehledu je:
`placeBlock()` na druh bloku nekouká a v creative je to přesně ten blok, ke kterému
se hráč jinak nedostane, protože vytěžit ho nejde. Bez `blocks.json` vyjdou jen
vestavěné bloky a hra je přesně jako dřív. **Přehled se staví ZNOVU při každém
otevření**, takže blok právě založený v labu je v něm hned.

**Rolování posouvá jen INDEXY, ne kreslení.** Mřížka zůstává, kde je, a mění se to,
co je v ní vidět — stejně jako v seznamu světů. Kdyby se posouvaly souřadnice, musely
by se sloty ořezávat na okraji panelu. Posuvník vedle mřížky je jen ukazatel a kreslí
se, až když je co rolovat; roluje se kolečkem. Tažení za značku by znamenalo další stav
myši v obrazovce, která už tři má (klik, shift-klik, tažení hromádky).

**Let: dvojstisk mezerníku, jako v Minecraftu.** Je to jediné ovládání letu, které
nepotřebuje nic vysvětlovat — kdo hrál Minecraft, zkusí ho první. Nová klávesa by
navíc musela být volná: F, C, T, V, E, Q i F3–F11 už něco dělají. Dvojstisk hlídá
`DoubleTap` (okno 0,3 s, o kousek delší než minecraftích 0,25 s kvůli pomalejším
prstům); **⚠️ po úspěšném dvojstisku se okno zahodí**, jinak by trojí stisk přepnul
let dvakrát a rychlé poskakování by letem blikalo. Odchod do menu, pauzy nebo
inventáře rozdělaný dvojstisk zahodí ze stejného důvodu jako zrušení kopání — skok
před odchodem a skok po návratu spolu nemají co dělat. **Let jde jen v creative**
(`GameMode.canFly()`); v letu je mezerník nahoru, Ctrl dolů a Shift zrychluje.

**Ctrl, a ne Shift, protože Shift je tady sprint.** Minecraft má dolů „klávesu plížení"
— tady je to Ctrl (viz tabulka ovládání), takže je to TÁŽ role, jen jiná klávesa.
Shift zůstává zrychlením, což je zase přesně to, co dělá v Minecraftu sprint v letu.
Samotná fyzika letu se nepsala znovu: `Player.flying` i větev bez gravitace v `update()`
existovaly jako ladicí přepínač, creative jim jen přidal herní spouštěč.

**⚠️ Ladicí klávesy F a C ZŮSTÁVAJÍ, jaké byly — v obou módech.** Je to samostatná
vrstva, která pravidla obchází schválně, stejně jako T (posun času); odebrat je
survivalu by byla změna survival chování, kterou tahle úprava dělat nemá. Rozšířit
C (noclip) na creative let by navíc byla chyba: v Minecraftu creative let **koliduje**,
kdežto noclip kolize vypíná úplně. Jsou to dvě různé věci a `CreativeTest` to hlídá
přímo — hráč v letu na podlaze stojí, hráč s noclipem jí propadne. C proto zůstává
ladicím přepínačem a s módem nemá nic společného.

**Mimo rozsah (a proč):** přepnutí módu za běhu (chtělo by příkazovou řádku),
crafting v creative (v Minecraftu je pod vlastní záložkou a v creative není k čemu),
záložky/vyhledávání v přehledu (78 bloků se vejde do dvou obrazovek rolování) a
hlad ani zdraví (ve hře neexistují, zavádět je kvůli módu by bylo naopak). **Známé
zjednodušení:** v creative světě není survival inventář na E vůbec dostupný, takže
crafting mřížka 2×2 je jen v survivalu; crafting table pravým tlačítkem funguje v obou
módech dál.

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
| Singleplayer → seznam světů | hrát (dvojklik / Enter), založit, smazat (s potvrzením) |
| Create New World → Game Mode | přepínač Survival / Creative; mód se uloží ke světu a dál se nemění |
| E | inventář (znovu E nebo Esc zavře); v creative **přehled všech bloků** |
| v creative přehledu: klik / shift-klik / kolečko | vzít kopii bloku / poslat ho do hotbaru / rolovat |
| Shift+LMB v inventáři | přesun hromádky hotbar ↔ batoh; z crafting mřížky zpět do inventáře |
| LMB / PMB táhnout v inventáři | rozdělit drženou hromádku rovnoměrně / po jednom kusu |
| Q / Ctrl+Q | vyhodit z ruky jeden kus / celou hromádku (držené Q sype dál) |
| F5 | pohled: první osoba → třetí zezadu → třetí zepředu → zpět |
| F3 | ladicí výpis vlevo nahoře — schovat / ukázat (výchozí: ukázaný) |
| F11 | okno / celá obrazovka (uloží se do `options.json`) |
| F6 | texture lab (znovu F6 nebo Esc zavře); taky z hlavního menu „Texture Lab" |
| v labu: New block / Import PNG | nový blok z labu (Esc zruší) / načíst `textures/import.png`; PNG přetažené do okna se naimportuje hned |
| PMB na crafting table | otevře mřížku 3×3 |
| LMB (držet) | kopat — doba podle tvrdosti bloku |
| T | posun času o desetinu cyklu (ladění) |
| WASD / Space / Ctrl | pohyb / skok (nahoru v letu) / plížení (dolů v letu) |
| **Space 2× (jen creative)** | zapnout a vypnout let |
| Shift | sprint |
| 1–9, kolečko | výběr slotu hotbaru |
| LMB / PMB | těžit / položit |
| F / C | let / noclip — **ladicí klávesy, platí v obou módech** |
| V / Esc | vsync / pauza |

Hráč: hitbox 0,6 × 1,8, oči 1,62, chůze 4,3 b/s, gravitace 28 b/s², skok **1,19 bloku**
(vyskočí na jednoblokový schod, ne na dvoublokový).

---

## Co dál

**Grafika menu a HUD — hotovo.** Hranaté UI v celočíselném měřítku, bevel místo přechodů,
pixelové písmo, izometrické kostky v hotbaru, dlaždicované pozadí. Další úprava vzhledu =
změna `Palette` a konstant v GUI pixelech v `Hud`/`Menu`, ne nový kód.

**Texture atlas — hotovo.** Mřížka 8×8 dlaždic po 16×16, tedy atlas 128×128 (`BlockAtlas`);
obsazených je 27 buněk (16 dlaždic bloků, neznámý blok a 10 stádií prasklin). Textury generuje
procedurálně `Textures.blockAtlasPixels()`. Tráva má jinou texturu shora, z boku i zespodu.
Přidat vestavěný blok = konstanta ve `World` (id do 63), dlaždice v `BlockAtlas` (odspodu),
její kresba v `Textures` a case ve switchích. Plná kostka jde přidat i bez kódu, v labu
(„Bloky z labu"). Volných je 37 buněk.

**Ruční textury místo procedurálních — hotovo, přes texture lab.** Upravený atlas se uloží
do `textures/atlas.png` a hra ho při startu načte místo procedurálního; viz sekce Texture lab.

**Texture lab — hotovo.** F6 nebo „Texture Lab" v hlavním menu: úpravy dlaždic s živou 3D
kostkou přes skutečný mesher a shader, export do PNG, import hotového PNG, paleta celého
atlasu a zakládání nových bloků (plná kostka) do `textures/blocks.json`. Zbývá: víc atlasů /
resource packy, úpravy a mazání bloků z labu, vlastní tvary, recepty pro bloky z labu,
průsvitné bloky, animované textury.

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

**Víc světů — hotovo.** `saves/<složka>/` s metadaty, náhledem a vlastním seedem, obrazovky
na výběr i zakládání světa a migrace starého jednoho slotu. Zbývá: přejmenování světa,
kopie světa, víc typů světa (superflat), záloha při poškození `world.dat`.

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

**Blok v ruce — hotovo.** Model v perspektivě před kamerou, máchnutí při kopání i pokládání;
s prázdným slotem holá ruka z modelu postavy.

**Zvuk — hotovo.** OpenAL, kroky, rozbití a položení bloku v prostoru, kliknutí v menu,
syntetizované placeholdery nahraditelné soubory `sounds/<jméno>.wav` bez změny kódu. Zbývá:
skutečné nahrávky, víc variant na zvuk, ťukání při kopání, dopad, plavání, hudba, .ogg
(modul `lwjgl-stb`) a nastavení hlasitosti v UI.

**Model postavy a pohledy — hotovo.** Postava z kvádrů s placeholder skinem v šabloně Minecraftu,
chůze, pohupování a máchnutí, F5 přes tři pohledy s kamerou, která neprojede terénem. Zbývá:
tělo zaostávající za hlavou, plížení a plavání, druhá vrstva skinu, načítání skutečného skinu
(je to výměna jedné metody). Skutečný systém entit (víc postav, jiní hráči) zatím není — model
je napsaný pro hráče, ale nic v něm na hráče vázané není kromě vstupních parametrů.

**Osvětlení je uzavřené.** Plynulé osvětlení s ambient occlusion, obloha se sluncem, měsícem
a hvězdami. Co by šlo přidat později: měsíční fáze, barevný nádech při východu a západu,
a mraky.

**Správa světů a nastavení — hotovo.** Výběr z víc světů s náhledy, zakládání se jménem
a seedem, mazání s potvrzením, obrazovka Options s okamžitým účinkem a `options.json`.
Zbývá: přejmenování světa, volby při zakládání nad rámec seedu (typ světa, bonusová truhla),
přebindování kláves a hlasitost.

**Známé zjednodušení u vody:** neteče a nešíří se — je to jen statická výplň pod hladinou.
Jezero se dá zasypat, ale ne vypustit ani přelít. Chybí i utopení a bubliny.

**⚠️ Atlas nemá mipmapy** — vzdálený terén bude jiskřit. Zapnout je nejde jen tak: nižší
úrovně by průměrovaly přes hranice dlaždic a barvy by se mísily mezi bloky. Správné řešení
není padding, ale **texturové pole** (`GL_TEXTURE_2D_ARRAY`, v GL 3.0+): každá textura je
vrstva, UV vždycky 0–1, prosakování nemůže nastat a mipmapy fungují per-vrstvu. Cena je
jeden float na vrchol navíc (index vrstvy).

**Známé zjednodušení:** není step-up assist (přes 0,6bloku vysoký schod tě to nevytáhne
automaticky, musíš skočit), není fall damage, `glLineWidth > 1` není v core profilu garantovaný.
