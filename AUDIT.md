# AUDIT — nedostatky, chyby a nekonzistence

Stav k commitu `75b3270` („Recept z labu: 9 hliny -> 3 travy"), větev
`claude/trusting-sagan-0pt85q` (totožná s `master` i s oběma remote větvemi).
Audit je **jen report**. V herním kódu, v testech ani v `ARCHITECTURE.md` se nic
neměnilo. Popis toho, co existuje a proč, je v `ARCHITECTURE.md`. Tento soubor
obsahuje jen to, co je špatně nebo nekonzistentní.

## Stav oprav

Opravy jdou podle doporučeného pořadí na konci souboru, každý bod jako
samostatný commit. Nálezy níž zůstávají v původním znění (popisují stav
v `75b3270`), tady se jen odškrtávají.

| bod | nálezy | stav |
|---|---|---|
| 1. Ukládání při ukončení | MAIN-1, MAIN-6, MAIN-8, MAIN-9 | ✅ opraveno: `Main.worldInPlay()` (switch bez default) + test v `MainStateTest`; při zavření okna se obrazovka nejdřív zavře jako běžně, pak se uloží svět a až po něm náhled, vše ve `finally`; `lastPlayed` jen po úspěšném uložení. Ověřeno i naživo (Xvfb): zavření okna z inventáře i z Options otevřených z pauzy svět uloží, za Options z pauzy je vidět svět. |
| 2. Bezpečný zápis všude | PER-1, PER-2, PER-3, PER-8 | ✅ opraveno: `world.dat`, `atlas.png` a `skin.png` jdou přes `SafeFiles` (kódování do paměti → `.tmp` → `force` → přejmenování, nečitelný soubor do `.bak`); existující záloha se nikdy nepřepíše (stejný obsah se nezálohuje, jiný jde do `.bak.1`…`.bak.9`, když jsou plné, soubor se nepřepíše); `BlockRegistry`/`RecipeBook` místo vlastních kopií volají `SafeFiles`; `WorldSaves.touch()` bere migraci ze zálohy. Nový `SafeFilesTest` (vč. MCW1 a selhaného zápisu), regrese v `WorldSavesTest`; ověřeno, že proti starému kódu testy selžou. |
| 3. Duplikace a ztráta předmětů | INV-1, INV-2, INV-4, PER-6 | ✅ opraveno: `takeResult` přidává výsledek jen celý (`Container.room()`), `insert()` z přerostlé hromádky neubírá; obě crafting mřížky se ukládají za inventářem (formát `world.dat` beze změny, starší build je přeskočí); `world.dat` ořízne NaN/nekonečnou polohu, pitch na `Camera.MAX_PITCH` a hromádky nad 64 (svět se nezahodí). Testy výstupního slotu s plnou rukou, prohození, zbytku v mřížce, `room()`, ukládání mřížek a ořezu hodnot. |
| 4. Reset stavu mezi světy | MAIN-2, MAIN-7 | ✅ opraveno: `resetPlayerState()` vrací i let, noclip, rychlost (`Player.resetForNewWorld()`), yaw/pitch kamery, dvojstisk, kopání a zaměřený blok (pohled F5 a citlivost jsou nastavení hráče a zůstávají). `MainStateTest.everyFieldIsClassified()` reflexí prochází všechna pole `Main`, `Player` a `Camera` - nové pole bez rozhodnutí „svět / sezení“ test shodí. |
| 5. Kritické chyby labů | LAB-1, LAB-2, LAB-3, LAB-4, LAB-10 | ✅ opraveno: `applyHsv()` nastaví oba editory (hex se předvyplní z aktivního); drop souboru mimo Blocks/Skin nic neimportuje a řekne proč; editory drží `Main` po celý běh, takže „(unsaved)“ a undo přežijí zavření labu, a `delete()` dotáhne rozdělaný obdélník na grafiku; hláška se odpočítává jednou. Nový `LabModesTest`: konvence `LabMode.key()` přes `TextureLab.closesLab()` se skutečnými módy (Delete v Recipes lab nezavře), čekání na klávesu v Keybind Labu, `BiomeTunerLab.step()`. HSV v Skin a „(unsaved)“/undo po F6 → F6 ověřeny naživo (Xvfb). Při tom vidět: řádek „Click a body face on the sheet“ v Skin přetéká přes pole hex (patří k LAB-16). |
| 6. Generátor a tuning | GEN-1, GEN-2, GEN-3, GEN-5, GEN-6, PER-7, PER-10, LAB-9 | ✅ opraveno: vrstva koruny stažená na poloměr 0 je jeden blok (bez ořezu rohů), výchozí stromy se nezměnily; `rarity()` se nasytí místo přetečení; krok rudy v labu vždy změní skutečnou vzácnost a lab ukazuje skutečný násobek (`effectiveDensity`); `biome_tuning.json`: přetečení skončí na mezi a ohlásí se, zlomek se ohlásí, `describe()` vypisuje rudy, násobek se zapíše beze ztráty; náhled stromu stojí na terénu s výchozím tuningem (`World(seed, tuning)`). Testy: koruna všech velikostí, rudy, čísla v souboru, tuněná výška proti naivní sumě s různými čísly pro každý biom, švy s natuněnou korunou, terén náhledu. `SeedTest` (kontrolní součty výchozího terénu) prochází beze změny. |
| 7. Konzistence vstupu a zvuku | MAIN-3, MAIN-4, MAIN-5, MAIN-10, MAIN-11, MAIN-12, SND-2, UI-1, UI-2, UI-5 | ✅ opraveno: Esc smí mít v `keybinds.json` jen pauza (`Keybinds.allowedFor`), kódy pod mezerníkem se odmítnou; lab vrací `CONSUMED`/`CLOSE`/`UNUSED` a nevyužitá F11 přepne celou obrazovku (Keybind Lab si ji při čekání vezme); na obrazovkách opakování Esc/Enter nic nezavře a FULLSCREEN na psací klávese se nechytí; kliknutí u Play a Create hraje až po akci; přepínače v Options, Delete a dialog v seznamu světů a Game Mode znějí jako ostatní tlačítka (`takeClicked()`); zavření Options pustí posuvník; nápovědy (F3 výpis, Close v labu, Options) berou klávesy z `Keybinds`; schránka se čte jen při vložení, Cmd+V funguje. Nezměněno: animovaný hover jen v `Menu` (zbytek UI-2). |
| 8. Robustnost zvuku | SND-1, SND-3 | ✅ opraveno: `Wav.decode` počítá meze bloku v `long` (obří délka už nepřeteče do výjimky); `SoundLibrary.load` odmítne soubor nad 32 MB ještě před čtením a chytá i `RuntimeException` s názvem souboru - vadný soubor dá placeholder jen svému zvuku. `SoundTest` a `LabBlockTest` procházejí materiál zvuku až do `World.LAST_BUILT_IN`. Nové testy: obří délka `LIST`/`fmt `/`data`, řídký soubor nad strop. |
| 9. Neplatné meshe | WLD-1, WLD-2, WLD-4, WLD-5, WLD-8, WLD-9 (+ část WLD-10) | ✅ opraveno: `markDirtyAround` označí každou sekci, která protíná okolí 3×3×3 (na rohu až 8, dřív jen 6 stěnových); `LightEngine.touch()` totéž a sluneční paprsek po otevření šachty značí celou dráhu; mesh se staví až s načtenými všemi 8 sousedy (i diagonálními); výjimka v generátoru nezabije worker (`generateSafely` → prázdný sloupec, hláška); při načítání má světlo rozpočet 12 ms a obrazovka načítání čeká i na frontu světla; zápis stejného bloku (rozbití vzduchu) je no-op a vrací `false`. Testy: `MeshTest.dirtyIsComplete()` porovnává skutečné meshe před/po proti `dirtySections()`, `AsyncTest.workerSurvivesException()`, `ChunkTest` §7 přepsaný na skutečné kontroly líné sekce a `solidCount`. A/B sonda: neoznačené změněné sekce 16 → 0 (označených 188 → 222), načtení 17×17 sloupců 1007,5 → 1012,6 ms (medián, v šumu), světlo při načítání hotové za 95 snímků místo 309–342. Zbytek WLD-10 (`changes` drží stav místo rozdílu) zůstává do bodu 12. |
| 10. Výkon | REN-1, REN-2, REN-3, WLD-3, WLD-6 | ✅ opraveno: sloupce a meshe sloupců jsou v `LongMap` (klíč `long` bez zabalení, promíchaný hash, mazání posunem); `ChunkMesh` staví do sdílených pracovních polí vlákna, drží jen přesně velkou kopii a po `upload()` ji zahodí; `BlockIcon` sbírá celou dávku `begin()`…`end()` a pošle ji jedním draw callem (orphaning přes `glBufferData`); `GlStats.countDraw()` je za každým `glDrawArrays` (obloha, pozadí, ruka, položky na zemi, praskliny, obrys, ikony). Nový `PerfTest`: `LongMap` proti `HashMap` (200 000 náhodných operací), alokace `update()` v klidu a `cellAt`, přesná velikost dat meshe, a sken zdrojáků „žádný `glDrawArrays` bez `countDraw()`“. A/B: `cellAt` 62 → ~10 ns a 76 → 0 B; `update()` v klidu 11,5 µs / 20 638 B → ~1,4 µs / 24 B; `ChunkMesh.build` 0,55 → 0,24 ms a 906 → 56 KB na sekci; živá hra (dohled 6, po GC): `float[]` 50,9 → 0,87 MB, halda 63,6 → 13,1 MB; lab Recipes 5 draw callů za frame i s ikonami (dřív draw call za kvádr, a nezapočítaný). Ověřeno naživo (Xvfb): ikony v Recipes, hotbaru a inventáři vč. hromádky na kurzoru, terén a přestavba po kopání. |
| 11. Sjednocení labů | LAB-5, LAB-6, LAB-7, LAB-18 (+ část LAB-12) | ✅ opraveno (rozhodnutí uživatele: přepnutí drží, zavření varuje): rozepsaná práce v Recipes, Keys i Biomes přežije přepnutí módu (Keys a Biomes už v `onEnter()` nepřepisují návrh); rozepsaný blok se přepnutím napoprvé nezahodí („click Skin again to discard it“); zavření labu (Esc, klávesa labu, Close) s neuloženou prací napoprvé jen řekne „Unsaved work in Keys, Biomes - close again to discard it“, držený Esc to nepotvrdí (`LabGuard`, bez GL); „(unsaved)“ v titulku Recipes/Keys/Biomes proti tomu, co platí, a „built-in/custom“ popisuje aktivní nastavení. F3 zapíná měření hub klávesou `Keybinds.Action.DEBUG` ve všech módech, hláška má přednost před řádkem měření, obsah všech módů se měří (fáze `LabProfiler` jdou vnořovat). `LabMode` dostal `help`, `scroll`, `fileDropped`, `delete`, `unsaved`, `leaveWarning` s výchozí odpovědí „nic“ — hub už se na identitu módu neptá. Chyby zápisu všude „Could not write <soubor> - see console“ (`SafeFiles.writeFailed`), cesty přes `SafeFiles.shown`. Z LAB-12: smazaný zastaralý Javadoc nad `keyPixel()`. Testy v `LabModesTest` (+33 kontrol, 2176 celkem); sonda s GL oknem: F3 v Keys, pojistka Esc/Close, přepnutí s blokem, snímky, žádná chyba GL. Nezměněno: tři styly „vybraného“ tlačítka (LAB-18, jen pojmenováno); zavření celého okna z labu návrhy dál zahodí. |

---

## 0. Build a testy (fakta)

| krok | výsledek |
|---|---|
| `mvn -B compile` | **BUILD SUCCESS**: 98 zdrojových souborů, 1 varování (`system modules path not set in conjunction with -source 17`, viz BLD-1) |
| `mvn -B test-compile` | **BUILD SUCCESS**: 37 testových souborů, totéž jediné varování |
| `mc.AllTests` | **ALL PASSED**: 36 sad, **1914 kontrol OK, 0 FAIL**, exit kód 0, 24,8 s |

Prostředí: Apache Maven 3.9.11, OpenJDK **21.0.10** (ARCHITECTURE počítá s JDK 26
v IntelliJ, ale na 21 projde všechno). `mc.AllTests` běžel s pracovním adresářem
v kopii `textures/` mimo repo, aby testy nic nezapsaly do projektu. Výsledek je
stejný jako z kořene: testy si zapisují jen do `Files.createTempDirectory`.

Počet kontrol v sadách: AtlasTest 214, TextureLabTest 169, WorldSavesTest 142,
BlockRegistryTest 123, CreativeTest 106, BiomeTuningTest 80, KeybindTest 77,
OptionsTest 73, InventoryTest 66, BiomeTest 66, RecipeLabTest 60,
PlayerModelTest 57, LabBlockTest 49, SoundTest 47, SaveTest 41, SeedTest 39,
DroppedItemTest 37, LightTest 36, MouseScaleTest 33, ChunkTest 33,
WorldScreenTest 32, ThumbnailTest 32, WaterTest 31, TreeTest 30, CaveTest 30,
MiningTest 29, PhysicsTest 27, MainStateTest 27, ModelTest 26, MenuTest 26,
RayTest 25, CameraTest 15, AsyncTest 13, SwingTest 11, SkyTest 7, MeshTest 5.
Všech 36 testových tříd v `src/test` je zapojených v `AllTests`, žádná nevisí mimo.

Na stderr se během testů objevují hlášky „Nacteni sveta selhalo", „simulovana
chyba po kroku …" a „Nahled … nejde precist". Jsou to **očekávané** výstupy
testů, které schválně střílejí poškozenými soubory, a ne selhání.

## 0.1 Premisa „ItemStack přepracovaný na Block/Item" — v repu NENÍ

Zadání počítá s nedávným přepracováním `ItemStack` na základ Block/Item,
s Item Labem a s `items.json`. **Nic z toho v repozitáři neexistuje:**

- `src/main/java/mc/ItemStack.java:13` je pořád `public record ItemStack(byte block, int count)`;
- žádná třída `Item`, žádný `ItemLab`, žádný `items.json` (grep přes `src/` i `ARCHITECTURE.md`;
  `git log --all -S "class Item"`, `-S "items.json"`, `-S "ItemLab"` nevrátí nic). Jediné
  `Item` je `enum Item` položek obrazovky nastavení v `OptionsScreen.java:28`;
- boční panel labu má pět módů: Blocks, Skin, Recipes, Keys, Biomes (`TextureLab.java:221-227`);
- `ARCHITECTURE.md:2599` to říká výslovně: „⚠️ Chybí PŘEDMĚTY (ne bloky)".

Pokud ta práce proběhla v jiné session, nedostala se do commitu ani na remote.
Nálezy o „zbytcích starého předpokladu po refaktoru" proto neexistují a nejsou
vymyšlené. Místo nich je v příloze C **mapa míst, kde je „hromádka = blok" dnes
zadrátované**, jako podklad pro budoucí zavedení předmětů. Tatáž oprava zadání
platí pro Item Lab v bodě 3 a pro `items.json` v bodě 2.

## 0.2 Jak se audit dělal a jak číst závažnost

Kód prověřilo paralelně osm nezávislých průchodů: generování světa, jádro světa
a souběžnost, render, inventář a registry, laby, persistence, hráč a vstup,
zvuk a obrazovky. Kde to šlo, byla podezření potvrzena **sondou**: malým
programem proti `target/classes`, spuštěným ve scratchpadu mimo repo. Nálezy
hlášené víc průchody jsou sloučené do jednoho. Všechny KRITICKÉ nálezy jsem
navíc ověřil čtením kódu při slučování.

Kalibrace závažnosti, stejná ve všech oblastech:

- **KRITICKÝ**: skutečná chyba v chování s následkem, který uživatel při běžném
  použití potká (ztráta dat nebo práce, duplikace předmětů, porušená pravidla
  módu, nefunkční funkce), nebo porušení bezpečnostního vzoru (atomický zápis,
  `.bak`, bezpečný pád na výchozí hodnoty).
- **STŘEDNÍ**: nekonzistence, chybějící test na netriviální větev, porušený
  zdokumentovaný invariant. Patří sem i skutečná chyba, která potřebuje
  neobvyklý vstup (ručně upravený nebo poškozený soubor, krajní hodnoty), nebo
  je čistě vizuální. Výkonové rudy s naměřeným dopadem jsou tu taky.
- **NÍZKÝ**: styl, mrtvý kód, zastaralý komentář, drobnost, podezření bez
  potvrzeného spouštěče.

U starých, změřených částí (`World`, `Chunk`, `WorldRenderer`, `Player`) je
každý nález podložený sondou s konkrétním vstupem a výstupem. Čistě vizuální
chyby v nich zůstaly schválně na STŘEDNÍ.

Značení: `MAIN` = stavový automat a vstup v `Main`, `PER` = persistence,
`INV` = inventář, `LAB` = laby, `GEN` = generování světa, `WLD` = jádro
světa, `REN` = render, `PLR` = hráč a kamera, `SND` = zvuk, `UI` = obrazovky
mimo lab, `DOC` = rozpory `ARCHITECTURE.md` s kódem, `BLD` = build.

---

## 1. Main — stavový automat, ukládání při ukončení, vstup (MAIN)

### [KRITICKÝ] MAIN-1 Zavření okna s otevřeným inventářem nebo v Options otevřených z pauzy svět neuloží
- **Kde:** `src/main/java/mc/Main.java:233-235` (`run()`), `:924` a `:933` (`loop()`, větev `OPTIONS`), `:1294-1297` (`openOptions()`), `:1575` a `:1589` (`handleMenuClick()`)
- **Co:** Podmínka ukládání při ukončení je
  ```java
  if (state == GameState.PLAYING || state == GameState.PAUSED
          || (state == GameState.OPTIONS && optionsReturnState == GameState.PLAYING)
          || (state == GameState.TEXTURE_LAB && labReturnState == GameState.PLAYING))
  ```
  (a) Stav `CONTAINER` (inventář na E, crafting table) v ní chybí, i když se v něm svět
  hraje (`loop()` v něm volá `world.update` i `renderWorld`).
  (b) `openOptions()` se volá jen z hlavního menu (stav `MAIN_MENU`) a z pauzy (stav
  `PAUSED`), takže `optionsReturnState == PLAYING` **nikdy neplatí**. Třetí větev
  podmínky je mrtvá. Totéž srovnání na `:924` a `:933` rozhoduje, jestli se za Options
  kreslí svět a jestli se kreslí ztmavení (`overWorld`), takže se svět za Options
  z pauzy nekreslí ani negeneruje. Chyba je v kódu od commitu `dd8e638`.
- **Proč je to problém:** Autosave neexistuje. `saveWorld()` volá jen `quitToTitle()`
  a `run()`, takže zavření okna v těchto stavech zahodí všechno od posledního „Save and
  Quit": stavby, inventář, polohu, denní dobu i náhled. U `CONTAINER` se navíc nevolá
  `closeContainer()` → `returnItems()`, takže propadne i to, co je na kurzoru a v mřížce.
  Porušuje to `ARCHITECTURE.md:686` („Ukládá se při odchodu do menu i při zavření okna …
  spoléhat jen na tlačítko v pauze by znamenalo tichou ztrátu") a `ARCHITECTURE.md:1522-1523`
  (Options z pauzy se kreslí přes svět a posuvník render distance je vidět hned).
- **Scénář:** (1) Hra → E → zavřít okno křížkem nebo Alt+F4. (2) Hra → Esc → Options →
  zavřít okno. V obou případech se při příštím načtení objeví svět z předchozího uložení.
  (3) Esc → Options → táhnout Render Distance: místo světa je za obrazovkou hlína z menu.
- **Ověření:** čtením kódu (všechna volání `openOptions`, všechna čtení `optionsReturnState`
  a všechna místa, kde se nastavuje `CONTAINER`). Nahlásilo pět z osmi průchodů.

### [KRITICKÝ] MAIN-2 Nový svět zdědí let, noclip a pohled kamery z předchozího světa
- **Kde:** `src/main/java/mc/Main.java:1113-1129` (`resetPlayerState()`), `:1081-1104` (`freshWorld()`), `:1026-1040` (`restore()`), `src/main/java/mc/Player.java:128-148` (`spawn()`)
- **Co:** `Player` a `Camera` jsou `final` pole `Main` (`:42-43`) a mezi světy se nevyměňují.
  `resetPlayerState()` na ně nesahá a `Player.spawn()` nastaví jen polohu, rychlost
  a `onGround`. `player.flying` nastaví jen `restore()`, tedy jen u načteného světa.
  `player.noclip` nenastavuje nic kromě klávesy C (`:487`). `camera.yaw`/`pitch` a pohled F5
  se u nového světa nevracejí.
- **Proč je to problém:** Jde přesně o tu třídu chyby, před kterou varuje komentář nad
  `freshWorld()` a `ARCHITECTURE.md:634-646` („co drží `Main` a co se vztahuje ke
  KONKRÉTNÍMU světu, patří do `resetPlayerState()`"). `flying` se ukládá do `world.dat`,
  takže je to stav světa. Nově založený **survival** svět začne v letu bez gravitace a dvojstisk
  mezerníku ho v survivalu nevypne (`GameMode.canFly()`). Noclip přechází i do načtených
  světů.
- **Scénář:** Creative svět → 2× mezerník (let) → Esc → Save and Quit → Singleplayer →
  Create New World (Survival). Hráč visí ve vzduchu. Sonda na headless `Main` (stejně jako
  `MainStateTest`) vypíše po `resetPlayerState()` + `spawn()` `flying=true noclip=true`.
- **Ověření:** sondou a čtením kódu.

### [STŘEDNÍ] MAIN-3 Pojistka „Esc zavírá vždycky" neplatí, když má Esc v `keybinds.json` akci DROP, FULLSCREEN nebo LAB
- **Kde:** `src/main/java/mc/Main.java:394-398` (DROP), `:418` a `:434-439` (FULLSCREEN), `:442-446` (LAB). Všechno je PŘED `:453` (`if (key == GLFW_KEY_ESCAPE || bound == PAUSE)`)
- **Co:** Větev pro Esc přichází až po větvích, které rozhoduje akce `actionFor(key)`.
  `Keybinds.load` přijme `"ESC"` pro libovolnou akci, například `{"pause":"P","fullscreen":"ESC"}`.
  Pak Esc ve hře, v inventáři i na obrazovkách přepíná celou obrazovku a nic nezavře.
  S `"drop":"ESC"` hází předměty, s `"lab":"ESC"` otevře lab.
  Související věc: přebindovatelnou akci PAUSE respektuje jen zavírání kontejneru. Options,
  Select World, Create World i lab znají jen natvrdo Esc.
- **Proč je to problém:** `ARCHITECTURE.md:2053-2059` i komentář `:448-452` slibují,
  že Esc zavírá **vždycky**, a zdůvodňují to právě překlepem v `keybinds.json`. V labu Esc
  přiřadit nejde, ručně v souboru ano, a to je přesně případ, na který pojistka míří.
- **Scénář:** `keybinds.json` = `{"format":1,"keys":{"pause":"P","fullscreen":"ESC"}}` → hra → E → Esc přepne fullscreen, inventář zůstane otevřený.
- **Ověření:** čtením kódu.

### [STŘEDNÍ] MAIN-4 F11 (celá obrazovka) v labu nefunguje
- **Kde:** `src/main/java/mc/Main.java:407-412` (větev `TEXTURE_LAB` skončí `return` dřív než `:434`)
- **Co:** Ve stavu `TEXTURE_LAB` dostane klávesu jen `lab.key()` a callback hned skončí.
  `TextureLab.key` (`TextureLab.java:421-432`) ani žádný mód akci FULLSCREEN neznají.
- **Proč je to problém:** `ARCHITECTURE.md:1537-1538` i komentář `:433` tvrdí, že F11 „přepíná
  odkudkoliv". Všechny ostatní obrazovky ji propouštějí, lab je jediná výjimka a zdokumentovaná
  není. Pozor: Keybind Lab si při čekání na klávesu F11 vzít musí, jinak by ji nešlo
  přiřadit. Hub ale umí vrátit jen „zavři", ne „nespotřebováno".
- **Scénář:** F6 → F11 → nic.
- **Ověření:** čtením kódu.

### [STŘEDNÍ] MAIN-5 Obrazovky reagují na opakování klávesy (`GLFW_REPEAT`) i u Esc a Enter, komentář tvrdí opak
- **Kde:** `src/main/java/mc/Main.java:414-424` (key callback), `:1262-1286` (`screenKey()`)
- **Co:** Komentář `:414-415` říká „Nové obrazovky berou i opakování klávesy …, **zavírají
  se ale jen stiskem**". Kód ale do `screenKey()` posílá PRESS i REPEAT pro všechny klávesy,
  Esc a Enter nevyjímaje. Lab to má správně (`:408`, zavírá jen na `GLFW_PRESS`).
- **Proč je to problém:** Držený Esc na Create New World projde po ~0,5 s přes Select
  World až do hlavního menu. Držený Esc v potvrzovacím dialogu mazání zavře dialog i obrazovku.
  Držený Enter při selhání `playWorld`/`createWorld` zkouší akci ~30× za sekundu a pokaždé
  vypíše chybu.
- **Scénář:** Create New World → podržet Esc déle než 0,5 s → skončí se v hlavním menu místo v seznamu světů.
- **Ověření:** čtením kódu.

### [STŘEDNÍ] MAIN-6 Hromádka bloku založeného v labu se ztratí, když se okno zavře z labu
- **Kde:** `src/main/java/mc/Main.java:233-246` (`run()`), `:1175-1186` (`closeTextureLab()`), `:1194-1201` (`giveCreatedBlocks()`)
- **Co:** Hromádku 64 kusů předá hráči jen `closeTextureLab()` (a `updateCreatingWorld()`
  u labu otevřeného z menu). `run()` svět při zavření okna uloží (větev
  `TEXTURE_LAB && labReturnState == PLAYING`), ale `takeCreatedBlocks()` a `giveCreatedBlocks()`
  nezavolá.
- **Proč je to problém:** `ARCHITECTURE.md:2307` slibuje „jednu hromádku (64 ks)". Blok
  z labu nemá recept, takže v survivalu je to jediný zdroj. Blok zůstane v `blocks.json`,
  ale kusy tiše zmizí.
- **Scénář:** Ze hry F6 → Blocks → New block → Create → zavřít okno křížkem → po načtení bloky v inventáři nejsou.
- **Ověření:** čtením kódu.

### [STŘEDNÍ] MAIN-7 `MainStateTest` nehlídá, co o něm tvrdí `Main`, a stavový automat nemá žádný test
- **Kde:** `src/main/java/mc/Main.java:1072-1075` (Javadoc `freshWorld()`), `src/test/java/mc/MainStateTest.java:233-237`, `:279-330`
- **Co:** Komentář tvrdí: „`MainStateTest` prochází tenhle seznam a kdyby se sem přidalo
  pole a zapomnělo na reset, spadne." Test ale natvrdo kontroluje jen inventář, vybraný slot,
  crafting mřížky a denní dobu. Pole `Main` neprochází (žádná reflexe ani seznam), a proto
  prošel MAIN-2. Bez testu jsou i tyto netriviální rozhodovací větve `Main`, které by šly
  vytáhnout do čistých funkcí jako `mainMenuAction()`:
  1. podmínka „ukládat při zavření okna" (MAIN-1),
  2. směrování klávesy podle stavu a akce (MAIN-3, MAIN-5),
  3. vedlejší účinky `setState()` (kurzor, `firstMouse`, zrušení kopání a dvojstisku),
  4. kam se vrací Options a lab.
- **Proč je to problém:** Komentář dává falešnou jistotu, a obě KRITICKÉ chyby v této
  sekci prošly právě tou mezerou.
- **Scénář:** Kdokoliv přidá do `Main` nebo `Player` stav vázaný na svět a zapomene ho resetovat. Všech 1914 kontrol pořád projde.
- **Ověření:** čtením kódu a testů.

### [NÍZKÝ] MAIN-8 Výjimka kdekoliv ve hře přeskočí uložení; náhled se vyrábí před uložením bez ochrany
- **Kde:** `src/main/java/mc/Main.java:227-240` (`run()`, `loop()` není v `try/finally`), `:238-239` a `:1251-1252` (`captureThumbnail()` před `saveWorld()`), `:1219-1232`
- **Co:** Jakákoliv `RuntimeException` během hraní, i z callbacku GLFW přes `glfwPollEvents`,
  projde kolem `saveWorld()` i `saveOptions()`. Nepodstatný náhled (`renderWorld`,
  `glReadPixels`, PNG) běží před podstatným uložením a v `try` není.
- **Proč je to problém:** Chyba v jakémkoliv modulu znamená ztrátu celé session. Spolu
  s MAIN-1 je jediné spolehlivé uložení tlačítko Save and Quit.
- **Scénář:** Libovolná výjimka během hraní. Konkrétní spouštěč výjimky v náhledu se nenašel (podezření).
- **Ověření:** čtením kódu (návrhová mezera).

### [NÍZKÝ] MAIN-9 `saveWorld()` ignoruje neúspěch uložení a přesto posune `lastPlayed`
- **Kde:** `src/main/java/mc/Main.java:1239-1246` (`saveWorld()`), `:1250-1253` (`quitToTitle()`)
- **Co:** Návratová hodnota `WorldStorage.save(...)` se zahodí a hned se volá `WorldSaves.touch(...)`.
  Komentář přitom říká „Poslední hraní se posune až po uložení".
- **Proč je to problém:** Seznam světů ukáže nový čas, i když se nic neuložilo, a hráč bez konzole se o chybě nedozví.
- **Scénář:** Plný disk → Save and Quit.
- **Ověření:** čtením kódu.

### [NÍZKÝ] MAIN-10 Nápovědy v UI mají klávesy natvrdo a ignorují Keybinds
- **Kde:** `src/main/java/mc/Main.java:1739-1743` (`debugLines()`: „F3 debug F5 view F6 texture lab F11 fullscreen", „F fly C noclip 1-9 slot Q drop T time V vsync Esc pause"), `:1717` („E inventory"), `src/main/java/mc/TextureLab.java:1946` a `:1970` („Close (Esc / F6)"), `src/main/java/mc/OptionsScreen.java:29-30` („toggles with F11 too", „(V in game)")
- **Co:** Texty jmenují výchozí klávesy místo `Keybinds.keyName(Keybinds.active().key(...))`.
  Ladicí výpis navíc používá staré jméno „texture lab".
- **Proč je to problém:** Po přebindování v Keybind Labu nápověda ukazuje klávesy, které nic nedělají.
- **Scénář:** Keys → DEBUG na F4 → Save → výpis dál hlásí „F3 debug".
- **Ověření:** čtením kódu.

### [NÍZKÝ] MAIN-11 Akce FULLSCREEN přebindovaná na písmeno nebo Enter má přednost před psaním do polí
- **Kde:** `src/main/java/mc/Main.java:416-427` (`bound == Keybinds.Action.FULLSCREEN` se testuje dřív než `screenKey`)
- **Co:** Keybinds nijak neomezuje, na co jde FULLSCREEN dát. Char callback (`:372`) písmeno do pole napíše a zároveň se přepne celá obrazovka. Enter na FULLSCREEN přebije Create/Play.
- **Proč je to problém:** `ARCHITECTURE.md:2105-2106` říká, že psaní do polí herní akce není.
- **Scénář:** `"fullscreen": "G"` → Create New World → napsat „Gold".
- **Ověření:** čtením kódu.

### [NÍZKÝ] MAIN-12 Klávesy s kódem 1–31 projdou validací, ale `glfwGetKey` je odmítne; chyby GLFW jsou tiché
- **Kde:** `src/main/java/mc/Keybinds.java:487-490` (`isUsableKey`: `key > 0 && key <= GLFW_KEY_LAST`), `src/main/java/mc/Main.java:1681-1684` (`down()`)
- **Co:** `glfwGetKey` odmítá všechno pod `GLFW_KEY_SPACE` (32) jako `GLFW_INVALID_ENUM`.
  `"#5"` v souboru se tedy načte, ale akce nikdy nesepne. Main nenastavuje
  `glfwSetErrorCallback` (0 výskytů), takže se o tom nikdo nedozví. Totéž platí pro každou
  jinou chybu GLFW.
- **Proč je to problém:** Akce tiše nefunguje a diagnostika chybí.
- **Scénář:** `keybinds.json`: `"jump": "#5"`.
- **Ověření:** čtením kódu (hranice 32 podle implementace GLFW).

### [NÍZKÝ] MAIN-13 `hit` se nezneplatní po rozbití bloku ani po výměně světa
- **Kde:** `src/main/java/mc/Main.java:617-648` (pokládání v mouse callbacku), `:1412`, `:1420-1424` (`updatePlaying()`), `:1350-1353`
- **Co:** Zaměření se počítá jednou za frame před kopáním. Když `mining.harvest()` blok
  ve stejném framu rozbije, obrys se nakreslí na vzduch a PMB v nejbližším `pollEvents`
  položí blok na `hit.place*` bez opory. Ve framu přechodu `CREATING_WORLD → PLAYING`
  platí `hit` ze starého světa.
- **Proč je to problém:** Plovoucí blok nebo blok položený mimo zaměření. Okno trvá jeden frame.
- **Scénář:** Creative, držet LMB (blok padá každý frame, PLR-1) a zmáčknout PMB.
- **Ověření:** čtením kódu.

### [NÍZKÝ] MAIN-14 Starý svět žije v paměti celou dobu v menu; první `World` vzniká před načtením tuningu
- **Kde:** `src/main/java/mc/Main.java:1250-1259` (`quitToTitle()` nevolá `world.shutdown()` ani `worldRenderer.reset()`), `:44` (`World world = new World()` v inicializátoru pole) proti `:759-762` (`init()` načítá `biome_tuning.json`), `src/main/java/mc/BlockPreview.java:72`, `src/main/java/mc/TreePreview.java:90`
- **Co:** Po „Save and Quit" zůstává v menu živé worker vlákno starého světa, načtené
  sloupce, ~33 MB VBO a ~53 MB polí meshů (REN-3) i `drops`. Uvolní je až další
  `freshWorld()`. Není to únik, ale zadržená paměť. Úvodní `world` z `:44` navíc vzniká se
  snapshotem `BiomeTuning.DEFAULTS` dřív, než `init()` načte soubor, přestože komentář
  u `init()` říká „MUSÍ být před prvním světem". Dnes je to neškodné, protože `playWorld()`
  ho vždycky vymění. Náhledy v labu vytvářejí `World`, jehož worker nikdy nic nedělá a jen
  se 10× za sekundu probouzí.
- **Proč je to problém:** Paměť a vlákna navíc a past pro budoucí kód, který by úvodní svět použil.
- **Scénář:** Save and Quit → menu → paměť zůstává obsazená.
- **Ověření:** čtením kódu.

### [NÍZKÝ] MAIN-15 Duplicitní kód a zdvojené komentáře v `Main`
- **Kde:** `src/main/java/mc/Main.java`: `glClear + background.draw(width, height, BACKGROUND_TINT)` pětkrát v `loop()` (`:901-902`, `:928-929`, `:940-941`, `:945-946`, `:956-957`); „`world.update` + `renderWorld`" za obrazovkou třikrát (`:917-918`, `:925-926`, `:953-954`); tentýž komentář dvakrát za sebou (`:230-232` a `:236-237`); vzor výpisu „soubor je / není" čtyřikrát v `init()` (`:742-768`); převod akce obrazovky na přechod dvakrát (myš `:586-601`, klávesnice `:1262-1286`)
- **Co:** Tatáž podmínka „za obrazovkou se hraje svět" je napsaná na několika místech. Právě tady se rozešla s podmínkou ukládání (MAIN-1). Rozdílný zvuk kliknutí u myši a klávesnice (UI-2) vznikl na druhém páru míst.
- **Proč je to problém:** Rozchod dvou kopií téže podmínky už jednou způsobil ztrátu dat. Jen pojmenováno, refaktor se nenavrhuje.
- **Ověření:** čtením kódu.

---

## 2. Persistence a soubory na disku (PER)

Matice vzoru „atomický zápis + `.bak` + bezpečný pád" pro všechny soubory je
v příloze A.

### [KRITICKÝ] PER-1 `world.dat` se zapisuje přímo přes starý soubor: nepovedený zápis zničí celý svět
- **Kde:** `src/main/java/mc/WorldStorage.java:106-107` (`save()`)
- **Co:** `new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))`,
  tedy `CREATE + TRUNCATE_EXISTING` přímo na cíl, bez `.tmp`, `force()` a přejmenování.
  Soubor se zkrátí na nulu dřív, než se zapíše první bajt.
- **Proč je to problém:** `world.dat` je jediná část světa, kterou nejde dopočítat. Po
  useknutí vrátí `load()` `null` a `Main.playWorld()` (`Main.java:988-995`) svět záměrně
  nehraje. Svět je tak pryč celý, ne jen poslední session, a `.bak` neexistuje. `ARCHITECTURE.md:2566`
  uvádí jako známou mezeru jen „zálohu při poškození `world.dat`". Neatomický zápis zdokumentovaný
  není, odporuje vzoru `SafeFiles` a komentáři v `Thumbnails.java:162-163` („jako všechno ostatní").
- **Scénář:** Sonda s `ulimit -f 8` (simulace plného disku): platný `world.dat` měl
  102 625 B, `save()` vrátil `false` a soubor pak měl 8192 B, na kterých `load()` skončí
  `EOFException`. Totéž udělá zabití procesu nebo výpadek proudu při ukládání v okamžiku
  zavření okna, což je přesně chvíle, kdy OS aplikaci ukončuje.
- **Ověření:** sondou a čtením kódu.

### [KRITICKÝ] PER-2 `atlas.png` a `skin.png` se nezapisují atomicky a nečitelný soubor se přepíše bez zálohy
- **Kde:** `src/main/java/mc/AtlasImage.java:69` (`save()`, `ImageIO.write(image, "png", file.toFile())`); volá se z `TextureLab.java:787` (`save()`), `:806` (`saveSkin()`) a `:970` (`createBlock()`)
- **Co:** Tato varianta `ImageIO.write` cílový soubor nejdřív smaže a teprve pak píše
  (potvrzeno v bytekódu JDK i sondou s hardlinkem). Chybí `.tmp` s přejmenováním i `.bak`,
  které mají všechny JSON soubory. Soubor, který se při startu nepodařilo načíst (poškozený
  nebo jiného rozměru), se při Save přepíše bez zálohy. `catch` chytá jen `IOException`.
- **Proč je to problém:** Atlas je jediné úložiště pixelů všech dlaždic z labu, včetně
  dlaždic bloků z `blocks.json`. Selhání uprostřed zápisu nechá useknutý PNG, hra při startu
  tiše přejde na procedurální atlas a veškerá malba je pryč. Pravidlo „Create uloží nejdřív
  atlas; když to nejde, blok se nezaloží" blok sice nezaloží, jenže starý atlas už v tu chvíli
  neexistuje.
- **Scénář:** (1) Sonda s `ulimit -f 8`: `atlas.png` 65 776 B → po chybě 8192 B → načtení
  skončí „Error skipping PNG metadata" a atlas je procedurální. (2) Uživatel připraví
  `textures/atlas.png` 256×256, hra napíše „expected 128x128", v labu stiskne Save a jeho
  soubor je bez zálohy přepsaný.
- **Ověření:** sondou (atomicita) a čtením kódu (chybějící `.bak`).

### [STŘEDNÍ] PER-3 `.bak` se přepisuje bezpodmínečně: poškozený soubor přepíše dobrou zálohu
- **Kde:** `src/main/java/mc/SafeFiles.java:65-68` (`writeAtomically()`), kopie téže logiky v `BlockRegistry.java:403` a `RecipeBook.java:407`; konkrétní dopad v `src/main/java/mc/WorldSaves.java:335` (`touch()`)
- **Co:** `Files.copy(target, backup, REPLACE_EXISTING)` přepíše existující `.bak` pokaždé,
  když aktuální soubor nejde celý načíst. Obsah staré zálohy se nekontroluje. Ve `WorldSaves`:
  `readInfo()` načte údaje z dobré `world.json.bak` (`:222-236`), ale `touch()` čte migrační
  údaje z poškozeného `world.json`, dostane `null` a nezapíše je. `SafeFiles` pak poškozený
  soubor zkopíruje přes dobrou zálohu.
- **Proč je to problém:** Druhé poškození smaže zálohu z prvního. U `world.json` se ztratí
  `migratedFrom`, `migratedBytes` a `migratedCrc32`, tedy i rozpoznání `FINISHED_EARLIER`
  (`WorldSaves.java:625-676`). Když se starý `saves/world.dat` znovu objeví, přenese se podruhé
  jako „Old World (2)", a to `ARCHITECTURE.md:733-735` vylučuje.
- **Scénář:** Sonda: migrace → dobrý `world.json` zkopírovaný do `.bak` → poškození `world.json` → `list()` (čte z `.bak`) → `touch()`. Výsledek: `world.json` bez `migratedFrom` a `.bak` = `{ poskozeno`.
- **Ověření:** sondou.

### [STŘEDNÍ] PER-4 Dlaždice bloků z labu, které v nahraném `atlas.png` chybějí, se nevyznačí a bloky vyjdou černé
- **Kde:** `src/main/java/mc/Textures.java:131-144` (`atlasPixels()`)
- **Co:** `markMissingTiles()` se volá jen v procedurální větvi (`:142`). Atlas ze souboru
  dostane jen `fillMissingBuiltInTiles()` pro vestavěné dlaždice (< `TILE_COUNT` = 35).
  Chybějící dlaždice bloků z labu (63, 62, …) zůstanou průhledné.
- **Proč je to problém:** Je to přesně symptom popsaný v `Textures.java:214-217` („v neprůhledném
  průchodu černé"), jenže ošetřený jen pro chybějící soubor, ne pro soubor starší než
  `blocks.json`. `atlas.png` je v gitu, `blocks.json` ne (PER-5), takže se ty dva soubory
  rozejdou snadno.
- **Scénář:** Lab založí blok s New tile (buňka 63) a uloží atlas i `blocks.json` →
  `git checkout -- textures/atlas.png` (nebo stash, nebo přepnutí větve) → blok se kreslí jako
  černá kostka bez jakékoliv hlášky. Sonda potvrdila, že v trackovaném atlasu jsou buňky 27–63 prázdné.
- **Ověření:** čtením kódu a sondou.

### [STŘEDNÍ] PER-5 `.gitignore` neodpovídá tomu, co hra zapisuje
- **Kde:** `.gitignore:42-49`; `src/main/java/mc/Keybinds.java:58-62`; `src/main/java/mc/TextureLab.java:970-979` (`createBlock()`)
- **Co:** Ignorované jsou jen `saves/`, `options.json` a `run.bat`. Neignorované a netrackované
  zůstávají `keybinds.json`, `biome_tuning.json`, `textures/blocks.json`, `textures/skin.png`,
  `textures/import.png`, `sounds/` a všechny `*.bak` a `*.tmp`, včetně `options.json.bak`
  a `options.json.tmp`. Trackované jsou `textures/atlas.png` a `textures/recipes.json`.
- **Proč je to problém:**
  - (a) `options.json` je ignorovaný jako „nastavení vázané na stroj". `keybinds.json` má
    v kódu i v `ARCHITECTURE.md:2066-2067` stejné zdůvodnění („klávesy jsou nastavení
    stroje"), a ignorovaný není.
  - (b) Záloha ignorovaného souboru (`options.json.bak`) se v `git status` ukáže jako nový
    soubor a `git add -A` ji commitne.
  - (c) `createBlock()` jedním kliknutím zapíše trackovaný `atlas.png` a netrackovaný
    `blocks.json`. `git commit -a` pak commitne dlaždice bez bloků a checkout atlasu vrátí
    bloky bez dlaždic (PER-4).
  - (d) Recept z Recipe Labu s blokem z labu (id ≥ 64) by se přes trackovaný `recipes.json`
    dostal do čistého klonu, tam se přeskočí a první uložení v labu ho ze souboru smaže.
    Dnešní `recipes.json` používá jen id 3 a 1, takže se to zatím neděje.
- **Scénář:** Přebindovat klávesy a uložit tuning → `git status` ukáže dva nové soubory.
- **Ověření:** čtením kódu a `git check-ignore`.

### [STŘEDNÍ] PER-6 Hodnoty z `world.dat` se nevalidují, na rozdíl od denní doby
- **Kde:** `src/main/java/mc/WorldStorage.java:184-190` a `:237-242` (`load()`), `src/main/java/mc/Main.java:1032-1046` (`restore()`), `src/main/java/mc/ItemStack.java:19-22` (`of()`)
- **Co:** Poloha `x/y/z`, `yaw` a `pitch` jdou ze souboru rovnou do `player` a `camera`,
  bez kontroly NaN, nekonečna a rozsahu. `pitch` se neořezává na ±89. Hromádka jde přes
  `ItemStack.of(in.readByte(), in.readInt())`, a `of()` ořezává jen `count <= 0` a `AIR`,
  takže projde i `count > 64` a neexistující vestavěné id 21–63. Při `count > 64` vrací
  `space()` záporné číslo a `Container.insert()` pak „přidává" záporné množství. Sonda:
  `add(1 kámen)` do `[100 kamenů, …]` dá sloty `64 / 37`. Ve spojení s INV-1 se přebytek ztratí.
- **Proč je to problém:** Denní dobu `ARCHITECTURE.md:1026-1029` výslovně ořezává s odůvodněním
  „NaN z poškozeného souboru … svět by zčernal". Stejný důvod platí pro polohu a kameru, jen
  tam se neuplatnil. NaN v kameře dá černý obraz bez hlášky a první uložení NaN zapíše zpátky.
  UI pak kreslí „100" přes okraj slotu.
- **Scénář:** Bitová chyba v prvních bajtech za hlavičkou, nebo ručně upravený `world.dat`.
- **Ověření:** chybějící validace čtením kódu, přerovnání slotů sondou, černý obraz je podezření.

### [STŘEDNÍ] PER-7 `biome_tuning.json`: celé číslo přeteče na platnou hodnotu a zlomek se tiše zaokrouhlí
- **Kde:** `src/main/java/mc/BiomeTuning.java:571-573` (`integer()`, `(int) Math.round(number)`), `:553-558` (`describe()`)
- **Co:** `Math.round(double)` vrací `long` a zúžení na `int` hodnotu zabalí. Výsledek může
  padnout dovnitř mezí, takže `clamped()` nic nenahlásí. Zlomky (`64.4`) se zaokrouhlí bez
  hlášky. `describe()` v hlášce o ořezu nevypisuje `ironDensity` ani `coalDensity`.
- **Proč je to problém:** Porušuje `BiomeTuning.java:55-57` („Soubor se hodnotou mimo meze
  NEZAHODÍ - ořízne se a ohlásí, jako u options.json"). `Options` zlomek hlásí, `BlockRegistry`
  a `RecipeBook` ho odmítnou, jen `BiomeTuning` mlčí. Protože `problems` zůstane prázdné,
  nevznikne ani `.bak` a překlep se při dalším Save tiše přepíše.
- **Scénář:** Sonda: `"trunkMax": 4294967300, "trunkMin": 4294967298, "baseHeight": 64.4` se načte jako `trunkMin=2, trunkMax=4, baseHeight=64` a na stderr nepřijde nic. `"baseHeight": 3000000000` dá 8 místo stropu 110.
- **Ověření:** sondou.

### [STŘEDNÍ] PER-8 Netestované netriviální větve ukládání a načítání
- **Kde:** `src/main/java/mc/SafeFiles.java:65-70` (větev „záloha selže → cíl nepřepisovat") a její kopie v `BlockRegistry.backupIfDamaged` a `RecipeBook.backupIfDamaged`; `src/main/java/mc/WorldStorage.java:222-253` (`load()`, větev MCW1); `AtlasImage.save` (atomicita)
- **Co:** Žádný test nesimuluje selhání `Files.copy` do `.bak` (například když je `.bak`
  neprázdná složka) a neověřuje, že cíl zůstane netknutý a `save()` vrátí `false`. Přitom
  je to pojistka, kterou slibuje `SafeFiles.java:25-28`. Žádný test nezapíše MCW1
  (`0x4D435731`), ačkoli se na jeho čitelnost odvolávají komentáře i `ARCHITECTURE.md:1016-1023`.
  Zbylý `.tmp` po úspěšném zápisu kontrolují jen testy Options, Blocks a Thumbnail.
- **Proč je to problém:** Regrese v pořadí „záloha, pak přepis" nebo ve čtení MCW1 by prošla všemi 1914 kontrolami.
- **Ověření:** čtením testů.

### [NÍZKÝ] PER-9 Každý JSON soubor má jiná pravidla pro `format` a pro zjištění existence souboru
- **Kde:** `Options.java:375`, `BlockRegistry.java:522`, `WorldSaves.java:815` (`format > FORMAT`) proti `Keybinds.java:629`, `RecipeBook.java:508`, `BiomeTuning.java:481` (`format.intValue() > FORMAT`). Chybějící `format`: `BlockRegistry.java:518-520` a `RecipeBook.java:503-505` zahodí celý soubor, `WorldSaves.java:811-813` varuje, Options, Keybinds a BiomeTuning mlčí. `Options.java:275` používá `!Files.exists`, ostatní `Files.notExists`.
- **Co:** Tři politiky pro chybějící `format` a dvě pro porovnání verze. Na `"format": 1.5` varuje jen polovina souborů a jen ta vyrobí `.bak`. Když nejde zjistit existenci souboru (práva adresáře), `Options` mlčky vezme výchozí hodnoty, ostatní to hlásí.
- **Proč je to problém:** Soubory mají podle `ARCHITECTURE.md` „stejný vzor" a ten se v detailech rozešel. Praktický dopad je malý.
- **Ověření:** čtením kódu.

### [NÍZKÝ] PER-10 Uložení tuningu ořízne násobek rudy na dvě desetinná místa, generátor přitom používá plnou přesnost
- **Kde:** `src/main/java/mc/BiomeTuning.java:435-438` (`toJson()`, `%.2f`) proti `:590-592` (`decimal()`)
- **Co / Proč:** Tentýž soubor dá před uložením z labu a po něm jiný svět. `ironDensity 0.004` (vzácnost 15 000) se po uložení změní na `0.00` a ruda se v biomu vypne. Lab ukládá celý tuning, takže stačí upravit jiný biom.
- **Ověření:** sondou.

### [NÍZKÝ] PER-11 Novější `world.dat` se hlásí jako „cizí formát"; `DirectoryIteratorException` se nechytá
- **Kde:** `src/main/java/mc/WorldStorage.java:168-172` (`load()`); `src/main/java/mc/WorldSaves.java:187-208` (`list()`), `:478-492` (`uniqueFolder()`)
- **Co:** Každé MAGIC kromě MCW1–3, tedy i budoucí MCW4, dostane hlášku „Ulozeny svet ma cizi format",
  takže uživatel dojde k závěru, že je soubor poškozený, a ne novější. Kolem `DirectoryStream`
  se chytá jen `IOException`, ale chyba I/O během iterace přichází jako `DirectoryIteratorException`
  (`RuntimeException`) a vyletí přes callback GLFW (podezření: vadné médium nebo síťový disk).
- **Ověření:** čtením kódu.

### [NÍZKÝ] PER-12 Duplicitní logika čtení a zápisu (jen pojmenováno)
- **Kde:** `BlockRegistry.java:327-418` a `RecipeBook.java:335-423` (`save` + `backupIfDamaged` + `loadsCompletely`) jsou téměř doslovné kopie `SafeFiles.writeAtomically` (`SafeFiles.java:50-126`). `Thumbnails.java:168-230` duplikuje tmp + force + move pro bajty.
- **Co:** Kostra `load()` (notExists → `readString` → `CharacterCodingException`/`IOException` → parse → `IAE`/`RuntimeException`) je pětkrát, `report()` pětkrát, `loadsCompletely()` pětkrát. `wholeNumber()` je dvakrát a pokaždé s jinou sémantikou: `BlockRegistry` odmítne `|d| > Integer.MAX_VALUE`, `RecipeBook` saturuje. `BiomeTuning.integer()` přetéká (PER-7).
- **Proč je to problém:** Kopie se už rozešly v detailech (PER-7, PER-9) a oprava vzoru (PER-3) se musí udělat třikrát.
- **Ověření:** čtením kódu.

### [NÍZKÝ] PER-13 Zastaralé komentáře o persistenci
- **Kde:** `src/main/java/mc/Json.java:13-15` („Jediný JSON ve hře je textures/blocks.json", dnes jich je šest); `src/main/java/mc/SafeFiles.java:15` (vyjmenovává jen options.json a metadata světů); `src/main/java/mc/WorldStorage.java:157-160` („volající si pak založí nový svět", dnes `playWorld()` svět naopak nepřepisuje) a `:21` („s pevným seedem")
- **Ověření:** čtením kódu.

---

## 3. Inventář, crafting, registry bloků, předměty na zemi (INV)

### [KRITICKÝ] INV-1 Odebrání výsledku craftingu duplikuje předměty, když se výsledek vejde do inventáře jen zčásti
- **Kde:** `src/main/java/mc/ContainerScreen.java:715-743` (`takeResult()`), `src/main/java/mc/Container.java:85-121` (`insert()`/`add()`)
- **Co:**
  ```java
  ItemStack leftover = playerInventory.add(result);
  if(!leftover.isEmpty())
  {
      return;   // není kam, takže se nic nespotřebuje
  }
  ```
  Kód předpokládá, že `add()` přidá všechno, nebo nic. `Container.add()` ale nejdřív doplní
  rozdělané hromádky a vrátí jen zbytek. Když zbytek není prázdný, `takeResult` skončí bez
  `Recipes.consume()`. Doplněná část výsledku v inventáři zůstane, suroviny zůstanou v mřížce
  a výsledek dál ve výstupním slotu.
- **Proč je to problém:** Předměty zadarmo a opakovaně: stačí přesunout doplněné hromádky
  jinam a kliknout znovu. Porušuje to `ARCHITECTURE.md:888` („Suroviny se spotřebují až
  odebráním výsledku").
- **Scénář:** Sonda: inventář plný hlíny, ve slotu 0 je 62 cihel, v mřížce 2×2 čtyři kameny
  (výsledek 4 cihly), na kurzoru půlka hlíny, takže žádný slot není prázdný. Klik na výsledek:
  cihel v inventáři 62 → 64, kamenů v mřížce 4 → 4, výsledek pořád `4x blok 10`. Stejnou
  větev spustí i držení téhož bloku bez dost místa (`held.space() < result.count()`, `:728`),
  například opakované craftění plotu po třech kusech s 63 kusy na kurzoru.
- **Ověření:** sondou. Větev nemá test (INV-4).

### [STŘEDNÍ] INV-2 Obsah crafting mřížky, který se při zavření nevešel do inventáře, se uložením světa ztratí
- **Kde:** `src/main/java/mc/ContainerScreen.java:204-212` (`returnItems()`), `src/main/java/mc/Main.java:1239-1243` (`saveWorld()`), `:1122-1124` (`resetPlayerState()`)
- **Co:** Co se z mřížky nevejde, v ní podle `ARCHITECTURE.md:894` zůstane („mřížka je trvalý
  kontejner a při dalším otevření tam bude"). `WorldStorage.Save` ale na mřížky pole nemá
  a `resetPlayerState()` je při načtení vyprázdní. `MainStateTest.java:319-326` tohle chování
  dokonce vyžaduje („crafting mrizka je i u nacteneho sveta prazdna").
- **Proč je to problém:** Předměty zmizí, přestože `ARCHITECTURE.md:1244` slibuje „Z inventáře tím nezmizí nic". Pro hráče je mřížka součástí inventáře.
- **Scénář:** Sonda: plný inventář, v `craftingSmall[0]` leží 5 železné rudy → `returnItems` → uložit → načíst. Mřížka je prázdná a rudu nemá ani inventář.
- **Ověření:** sondou. Potřebuje plný inventář, proto STŘEDNÍ.

### [STŘEDNÍ] INV-3 Recipe Lab uloží recept, který vestavěný bezetvarý recept vždycky přebije, a ohlásí, že funguje
- **Kde:** `src/main/java/mc/Recipes.java:140-154` (`builtInHasPattern()`), `src/main/java/mc/RecipeLab.java:206-209` (`problem()`), `:509-520` (`drawText()`)
- **Co:** `builtInHasPattern` prochází jen `SHAPED`, `SHAPELESS` vůbec ne. `match()` přitom
  zkouší bezetvaré recepty (`:109-115`) dřív než recepty z labu (`:128`). Vzor jedna tráva,
  jeden kmen, březový nebo smrkový kmen, nebo prkno + uhlí proto projde (`problem() == null`),
  lab ho uloží s hláškou „works right now, no restart" (`RecipeLab.java:335`) a recept nikdy
  nevyhraje. Lab přitom ukazuje dvě protichůdné hlášky naráz: „Already makes Dirt x1" a „Ready: …".
- **Proč je to problém:** Porušuje `ARCHITECTURE.md:1976` („lab vzor, který už vestavěný recept má, ani neuloží (řekne proč)"). Do `recipes.json` se zapíše mrtvý recept.
- **Scénář:** Sonda: 1 tráva uprostřed → `problem()=null` a `existingResult()=1x blok 3`. Po `activate(...with(draft))` vrací `Recipes.match` pořád hlínu.
- **Ověření:** sondou. `RecipeLabTest.labDraft()` (`:519-527`) zkouší jen tvarovaný vzor (4 prkna).

### [STŘEDNÍ] INV-4 Netriviální větve `ContainerScreen` bez testu
- **Kde:** `src/main/java/mc/ContainerScreen.java:724-741` (`takeResult()`: sloučení s držením, cesta do inventáře, plný inventář), `:706-708` (`putDown()`: prohození různých bloků), `:208-211` (`returnItems()`: zbytek zůstane v mřížce), `:459-464` (`release()` s prázdným `dragSlots`)
- **Co:** `InventoryTest` a `CreativeTest` zkoušejí výstupní slot jen s prázdnou rukou (`InventoryTest.java:300-303`). Prohození hromádek různých bloků ani zbytek, který zůstane v mřížce, se nezkoušejí.
- **Proč je to problém:** Právě v netestovaných větvích jsou INV-1 a INV-2.
- **Ověření:** čtením testů.

### [NÍZKÝ] INV-5 Pravidlo „jde to slít" je rozepsané na pěti místech, centrální `stacksWith()` je mrtvé
- **Kde:** `src/main/java/mc/ItemStack.java:37-40` (`stacksWith()`, nikdo ho nevolá, ani testy); ručně rozepsané `slot.block() == held.block()` v `Container.java:97`, `ContainerScreen.java:558`, `:682`, `:698`, `:728`. Komentář `Container.countOf` (`:154`, „Pro testy a ladicí výpis") nesedí, výpis používá `Main.usedSlots()`.
- **Proč je to problém:** Až přibudou předměty (porovnání podle druhu, ne podle `byte`), bude potřeba najít pět míst místo jednoho.
- **Ověření:** čtením kódu.

### [NÍZKÝ] INV-6 „Přidej do inventáře, přebytek jinam" je napsané čtyřikrát a jednou jinak
- **Kde:** `Mining.java:154-155` (`harvest()`), `Main.java:1196-1197` (`giveCreatedBlocks()`), `Main.java:1207-1208` (`closeContainer()`), `DroppedItems.java:169-178` (`update()`); výjimka je `ContainerScreen.java:735-740` (`takeResult()`)
- **Co / Proč:** První čtyři místa zbytek předají dál (na zem, zpátky do položky), `takeResult` ho zahodí. Na tomhle rozchodu stojí INV-1. Jen pojmenováno.
- **Ověření:** čtením kódu.

### [NÍZKÝ] INV-7 Dvě kopie porovnání vzoru receptu; „neměnný" `RecipeBook` vydává měnitelné pole
- **Kde:** `src/main/java/mc/Recipes.java:144-151` (`builtInHasPattern()`) a `src/main/java/mc/RecipeBook.java:259-266` (`containsPattern()`, jehož komentář „vestavěný i z labu" nesedí, prochází jen seznam z labu); `Recipes.java:24` (`record Recipe(… byte[] pattern …)`), `RecipeBook.java:90-93` (`recipes()`), `:623-627` (`of()`)
- **Co:** Tatáž smyčka „šířka, výška, `Arrays.equals`" je dvakrát. `List.copyOf` zkopíruje seznam, ale ne pole `byte[]`, takže `active().recipes().get(i).pattern()[k] = …` změní aktivní recepty. `normalize()` u už ořezaného receptu vrací tutéž instanci i s polem volajícího. `equals` záznamu porovnává pole podle reference. Javadoc `of()` („Prázdný seznam pro testy") nesedí a metoda kopíruje dvakrát.
- **Proč je to problém:** Třída slibuje neměnnost (`RecipeBook.java:35-38`), ale platí jen napůl. Dnes to nikdo nezneužívá.
- **Ověření:** čtením kódu.

### [NÍZKÝ] INV-8 Časovače položky na zemi běží se zastropovaným `dt`
- **Kde:** `src/main/java/mc/DroppedItem.java:103-106` (`update()`)
- **Co / Proč:** `dt = Math.min(dt, MAX_TIME_STEP)` (0,05 s) platí i pro `age` a `pickupDelay`, ne jen pro fyziku. Pod 20 FPS se zpoždění sběru i `LIFETIME` natahují: při 10 FPS na dvojnásobek, proti „2 s" a „pět minut" v `ARCHITECTURE.md:1195` a `:1233`.
- **Ověření:** čtením kódu.

### [NÍZKÝ] INV-9 `ContainerScreen` kreslí počty ve slotech přes blok držený kurzorem
- **Kde:** `src/main/java/mc/ContainerScreen.java:786-803`, `:863-897`
- **Co / Proč:** `drawCounts()`, počet na kurzoru i titulek se kreslí až po průchodu ikon, takže číslice slotu leží přes drženou kostku. K tomu jsou to tři textové průchody tam, kde by stačil jeden.
- **Ověření:** čtením kódu.

---

## 4. Laby a jejich sdílená infrastruktura (LAB)

Srovnávací tabulka všech labů (hlášky, validace, undo, revert, neuložená práce,
klávesy) je v příloze B.

**Konvence `LabMode.key()` dnes DRŽÍ:** všech pět implementací vrací „spotřeboval
jsem" a o zavření rozhoduje hub (`TextureLab.java:421-432`). Chyba z commitu
`459850e` se nevrátila. Nemá ale regresní test (LAB-10) a vedle správné konvence
visí komentář s tou starou (LAB-12).

### [KRITICKÝ] LAB-1 HSV posuvníky v módu Skin nemění barvu štětce
- **Kde:** `src/main/java/mc/TextureLab.java:766-771` (`applyHsv()`); souvisí `:1113` (`pressPixel()`, předvyplnění hexu), `:1545` a `:1897` (vzorek a popisek)
- **Co:** `applyHsv()` nastaví barvu jen editoru atlasu:
  `editor.setColor(AtlasEditor.hsv(hue, saturation, value, …))`. `setColor()` (`:747-750`)
  nastavuje oba editory, `applyHsv()` jen jeden. V módu Skin se ale maluje přes `active()`,
  tedy editorem `skin`. Vzorek CURRENT i hex kreslí `active().color()`. Klik do hexu navíc
  předvyplní `editor.color()`, tedy barvu atlasu, ne tu, kterou vzorek ukazuje.
- **Proč je to problém:** V módu Skin tah posuvníkem H/S/V posune jen značku posuvníku,
  vzorek, hex i štětec zůstanou na staré barvě. Rozejde se tím i „společná barva"
  (`ARCHITECTURE.md:1747-1749`): po návratu do Blocks platí barva z posuvníků, v Skin jiná.
- **Scénář:** Skin → klik na černý vzorek → potáhnout H, S, V → malovat na obličej: maluje se černě.
- **Ověření:** čtením kódu (`skin.setColor` se volá jen v `setColor()`).

### [KRITICKÝ] LAB-2 Soubor přetažený do okna v Recipes, Keys nebo Biomes se neviditelně naimportuje do atlasu a uloží se při příštím Save
- **Kde:** `src/main/java/mc/TextureLab.java:857-870` (`fileDropped()`), `:837-851` (`importImage()`), `:1427-1430` a `:1601` (jediné `updateRegion`, jen v `drawPixelContent()`), `:2316-2328` (`delete()`); `src/main/java/mc/Main.java:379-383` (drop callback)
- **Co:** Hub importuje bez ohledu na aktivní mód. Cíl (atlas nebo kůže) určuje
  `skinMode()`, tedy **poslední** pixelový mód, protože `onLeave()` ho neresetuje.
  Změněný obdélník se na GPU nahrává jen v `drawPixelContent()`, tedy jen v Blocks nebo Skin.
  `delete()` nevyřízený obdélník nenahraje a nový `AtlasEditor` při dalším otevření začíná
  s prázdným.
- **Proč je to problém:** V Recipes, Keys a Biomes drop přepíše pole atlasu hry a uživatel
  nic nevidí. Hláška radí Ctrl+Z, který v těch módech nedělá nic. Když se lab zavře dřív, než
  se přepne do Blocks, zůstane CPU pole a GPU textura trvale rozjetá: svět ukazuje starý atlas,
  ale příští Save (nebo Create block) zapíše na disk import, který uživatel nikdy neviděl.
  Když byl posledním pixelovým módem Skin, dostane uživatel v Recipes matoucí „… skin unchanged".
- **Scénář:** Lab → Recipes → přetáhnout PNG 128×128 → Esc → ve hře se nic nezměnilo → F6, namalovat pixel, Save → restart → atlas je importovaný obrázek.
- **Ověření:** čtením kódu.

### [KRITICKÝ] LAB-3 Po zavření a novém otevření labu zmizí „(unsaved)" i undo, přestože neuložené pixely ve hře zůstaly
- **Kde:** `src/main/java/mc/Main.java:1168-1170` (`openTextureLab()`), `src/main/java/mc/TextureLab.java:199-200` (konstruktor), `src/main/java/mc/PixelEditor.java:60` (`unsaved = false`), titulek `TextureLab.java:1876`
- **Co:** Lab vzniká při každém otevření znovu a staví nové `AtlasEditor` a `SkinEditor` nad TÝMIŽ poli `atlasPixels` a `skinPixels`. Příznak `unsaved` i zásobník undo začínají od nuly, přestože pole nese neuložené tahy z minulého otevření.
- **Proč je to problém:** `ARCHITECTURE.md:1640-1641` počítá s tím, že neuložené úpravy zůstanou ve hře do jejího ukončení, a jediné varování je „(unsaved)" v titulku. Po F6 → F6 titulek hlásí uložený stav, Ctrl+Z odpoví „Nothing to undo" a ukončení hry práci bez varování zahodí. Zavřít lab, podívat se na blok ve světě a vrátit se je přitom postup, kvůli kterému živý atlas existuje.
- **Scénář:** Blocks → namalovat dlaždici („(unsaved)") → F6 do hry → F6 zpět → titulek bez „(unsaved)" → ukončit hru → tahy jsou pryč.
- **Ověření:** čtením kódu.

### [STŘEDNÍ] LAB-4 Stavová hláška v Blocks a Skin mizí po 2,5 s místo 5 s, protože se odečítá dvakrát
- **Kde:** `src/main/java/mc/TextureLab.java:455-463` (`update()`) a `:872-877` (`updatePixel()`)
- **Co:** Hub odečte `statusLeft = Math.max(0f, statusLeft - dt)` a pak zavolá `current().update(dt)`, který v pixelovém módu vede na `updatePixel()` a ten odečte znovu (`statusLeft -= dt;`). Je to pozůstatek refaktoru `622cee5`: hub dostal vlastní odečet a starý zůstal.
- **Proč je to problém:** `STATUS_SECONDS = 5` platí jen v Recipes, Keys a Biomes. Nejdelší hlášky („Not imported: expected 128x128, got 64x64 - atlas unchanged", „Created X (id 64) - 64 go to your inventory") jsou přitom v pixelových módech.
- **Ověření:** čtením kódu a historie v gitu.

### [STŘEDNÍ] LAB-5 Každý lab zachází s neuloženou prací jinak a žádný nevaruje
- **Kde:** `src/main/java/mc/KeybindLab.java:86-93` (`onEnter()`: `draft = Keybinds.active()`), `src/main/java/mc/BiomeTunerLab.java:133-137` (`onEnter()`: `draft = BiomeTuning.active()`), `src/main/java/mc/RecipeLab.java:114-120` (`onLeave()` návrh nechává), `src/main/java/mc/TextureLab.java:285-292` (`PixelMode.onLeave()` → `cancelBlock()`), `:1339-1347`
- **Co:**
  - Přepnutí módu: Recipes rozepsaný recept drží, s odůvodněním „přepnout se na chvíli do Blocks a podívat se, jak surovina vypadá, je normální postup". Keys a Biomes rozepsané změny po návratu tiše zahodí. Návrh bloku se tiše zruší.
  - Zavření labu zahodí všechno kromě pixelů.
  - Recipes, Keys a Biomes nemají ukazatel neuloženého. Titulek Keys a Biomes ukazuje `draft.isDefault()` („built-in"/„custom"), tedy stav návrhu, ne rozdíl proti souboru: po kliknutí na Defaults napíše „built-in", i když na disku i ve hře jsou vlastní hodnoty.
  - U návrhu bloku Esc práci chrání (`:1339`), ale F6 a klik do bočního panelu ji bez hlášky zahodí.
- **Proč je to problém:** Tentýž úkon (odskočit si do jiného módu) dopadne v každém labu jinak a zdůvodnění v `RecipeLab` přímo odporuje chování `KeybindLab` a `BiomeTunerLab`. Uživatel přijde o přebindované klávesy nebo natuněná čísla bez jediného slova.
- **Scénář:** Keys → přebindovat pět kláves → ikona Blocks → zpět do Keys → vše je zpátky, bez hlášky.
- **Ověření:** čtením kódu.

### [STŘEDNÍ] LAB-6 Měření labu (F3) se zapíná natvrdo a jen v Blocks a Skin, ale jeho výstup přebije stav a nápovědu ve všech módech
- **Kde:** `src/main/java/mc/TextureLab.java:1315-1320` (`keyPixel()`: `key == GLFW_KEY_F3`), `:562-571` (`drawStatusAndHelp()`), `:496` (`current().drawShapes()` mimo fáze měření), `src/main/java/mc/LabProfiler.java` (`lines()`: „(F3 hides)")
- **Co:** (a) Přepínač se ptá na `GLFW_KEY_F3`, ne na `Keybinds.Action.DEBUG`, ačkoli klávesu labu hub zjišťuje přes `Keybinds` (`:431`). (b) V Recipes, Keys a Biomes F3 nic nedělá, i když řádek měření hlásí „(F3 hides)". Zapnuté měření v nich přitom schová stavový řádek i nápovědu. (c) `drawShapes()` nových módů neleží v žádné fázi měření.
- **Proč je to problém:** Kdo si zapne měření v Blocks, neuvidí v Recipes hlášku „Could not write …" a v Keys návod „Press a key for …", a vypnout ho musí zpátky v Blocks. S DEBUG přebindovaným na F4 je ladicí výpis ve hře na F4, v labu pořád na F3.
- **Ověření:** čtením kódu.

### [STŘEDNÍ] LAB-7 Hub porušuje pravidlo „přidat mód = třída a jeden řádek"
- **Kde:** `src/main/java/mc/TextureLab.java:443-453` (`scroll()`), `:590-616` (`hubHelp()`), `:2327` (`delete()`: `biomeLab.delete()`), `:857-870` (`fileDropped()`); `src/main/java/mc/LabMode.java` (chybí `scroll`, `help`, `delete`, `fileDropped`)
- **Co:** Kolečko, nápověda i úklid se rozhodují podle identity módu (`current() == recipeLab`, `== biomeLab`, `instanceof PixelMode`). Poslední větev nápovědy je bez podmínky: `return biomeLab.help(layout, mouseX, mouseY);`.
- **Proč je to problém:** `ARCHITECTURE.md:1898-1900` i Javadoc `LabMode` („Nic jiného.") slibují opak. Šestý mód přidaný jen řádkem by ukazoval nápovědu Biomes, ignoroval kolečko a neuvolnil své GL prostředky. LAB-2 je tentýž problém u dropu souboru.
- **Ověření:** čtením kódu.

### [STŘEDNÍ] LAB-8 Informační řádek v Biomes se nevejde a uříznuté je právě varování na jeho konci
- **Kde:** `src/main/java/mc/BiomeTunerLab.java:458-460` (`drawText()`, `lab.label(...)` bez `fit()`), `src/main/java/mc/TextureLabLayout.java:340` (`TUNE_INFO_Y`)
- **Co:** Řádek „Plains: trunk 5, crown radius 2 (range …)   -   saved tuning applies to the NEXT world, not this one" vyjde podle AWT metrik písma z `FontAtlas` asi na 675 GUI px. K dispozici je 440 px.
- **Proč je to problém:** Text přeteče panel. Uříznutá je věta „applies to the NEXT world", kterou `ARCHITECTURE.md:2136-2138` výslovně chce mít vidět. Ostatní laby `fit()` používají. Podobně „CLASH … - neither one fires" v Keys (`KeybindLab.java:371`) vyjde s nejdelšími názvy asi na 443 px (podezření).
- **Ověření:** sondou (šířky) a výpočtem.

### [STŘEDNÍ] LAB-9 Náhled stromu stojí v terénu z AKTIVNÍHO tuningu, ale v pevné výšce
- **Kde:** `src/main/java/mc/TreePreview.java:50` (`GROUND = 64`), `:88-95` (`createWorld()`: `new World()` → `TerrainGenerator(seed)` → `BiomeTuning.active()`)
- **Co:** Plošinka a strom stojí napevno na y = 63/64, s předpokladem „povrch kolem 53" (komentář `:44-48`). Svět náhledu ale generuje aktivní tuning a v bodě (8, 8) leží pláně. Sonda: základ plání 64 → povrch 52, 74 → 62, 78 → 66 (na y = 64 je hlína), 110/60 → 77 (z 110 bloků stromu se položí jen 54).
- **Proč je to problém:** Po uložení tuningu s vyššími pláněmi (asi od 76 v povoleném rozsahu 8–110) ukáže Biomes při příštím otevření labu strom zasypaný v kopci a bez koruny. Náhled, který má být „pravda o tom, co vyroste", pak klame. Komentář `TerrainGenerator.java:225-228` tvrdí, že náhled na aktivním tuningu nezávisí. Generátor stromu opravdu ne, svět pod ním ano.
- **Scénář:** Biomes → Plains base 90 → Save → zavřít a otevřít lab → Biomes.
- **Ověření:** sondou.

### [STŘEDNÍ] LAB-10 Mezery v testech přesně tam, kde už jednou byla chyba
- **Kde:** `src/test/java/mc/KeybindTest.java:365-370`; `src/main/java/mc/TextureLab.java:421-432` (`key()`), `src/main/java/mc/KeybindLab.java:252-269` (`key()`), `src/main/java/mc/BiomeTunerLab.java:191-234` (`step()`), `RecipeLab.key()`/`scroll()`
- **Co:** Netestuje se konvence `LabMode.key()` ani rozhodování hubu o zavření, takže chyba
  „Delete v Recipes zavřel lab" z `459850e` nemá regresní test. Dál chybí testy na
  `KeybindLab.key()` při čekání na klávesu (Esc zruší, F6 se přiřadí, neznámá se odmítne),
  na `selectMode()` → `onLeave()` → `cancelBlock()` (návrat registru), na `fileDropped()`
  podle módu, na `applyHsv()`, na časovač hlášky, na `BiomeTunerLab.step()` (jde postavit
  s `null` a na GL nesahá) a na `RecipeLab.key()`/`scroll()`. Příčina: logika módů sedí ve
  třídách, které kvůli `lab.say()` potřebují `TextureLab`, a tedy GL. To odporuje pravidlu
  v Javadocu `LabMode`, podle kterého má být logika jinde a testovatelná headless.
- **Proč je to problém:** Headless test by chytil LAB-1, LAB-2, LAB-4 i LAB-6. Opakování záměny konvence dnes nechytí nic.
- **Ověření:** čtením testů.

### [NÍZKÝ] LAB-11 Nové laby přepočítávají a alokují ve vykreslování každý frame
- **Kde:** `src/main/java/mc/RecipeLab.java:509-520` (`drawText()`: `existingResult()` → `Recipes.match`, `problem()` → `normalize`/`validate`/`builtInHasPattern`/`containsPattern`), `src/main/java/mc/KeybindLab.java:299`, `:345`, `:361` (`conflicted()` 2× pro 27 akcí, tedy 27×27 porovnání, a `conflicts()` s `LinkedHashMap`), `src/main/java/mc/BiomeTunerLab.java:490-491` (`String.format`)
- **Co / Proč:** Výsledky se mění jen po kliknutí, takže by stačilo je počítat při změně, jak to dělají palety pixelových módů (`ARCHITECTURE.md:1843-1850`). Samo o sobě je to levné, ale leží to na per-frame cestě. Draw cally ikon v Recipes jsou v REN-1.
- **Ověření:** čtením kódu (neměřeno).

### [NÍZKÝ] LAB-12 Zastaralé a protichůdné komentáře kolem vstupu labu
- **Kde:** `src/main/java/mc/TextureLab.java:1259-1262` (nad `keyPixel()` jsou dva Javadocy a ten první říká „Vrací true, když se má lab zavřít (Esc, F6)", tedy **starou, chybnou konvenci**, kvůli které vznikla chyba z `459850e`); `:2093-2106` (Javadoc o trojici begin/draw/end nepatří k metodě pod ním a tvrdí, že problém draw callů je vyřešený, viz REN-1); `:30-36` (Javadoc třídy „DVĚ ZÁLOŽKY: BLOCKS A SKIN", dnes je to boční panel s pěti módy); `src/main/java/mc/LabMode.java:71-75` proti `:89` (`press()` vrací „zavři lab", `key()` „spotřeboval jsem": obě konvence jsou zdokumentované, ale vedle sebe v jednom rozhraní jsou tatáž past); `src/main/java/mc/TextureLabLayout.java:544` (nadpis „formulář nového bloku" nad `recipeCell()`)
- **Ověření:** čtením kódu.

### [NÍZKÝ] LAB-13 Mrtvý kód po refaktoru na boční panel
- **Kde:** `TextureLabLayout.java:390` (`hitPanel()`), `:401` (`panelTextLeft()`); `TextureLab.java:619` (`profiler()`), `:636` (`mode()`), `:662` (`currentModeTitle()`, „pro testy", ale testy ho nevolají), `:667` (`modeCount()`); `SkinEditor.java:70` (`faceColors()`); `AtlasEditor.java:162` (`tileSnapshot()`); `PixelEditor.java:292` (`clearUndo()`). Jen z testů se volají `SkinEditor.faceName()`, `AtlasEditor.importAtlas()` a `PixelEditor.takeDirty()`.
- **Ověření:** grep přes main i test.

### [NÍZKÝ] LAB-14 Import PNG nedoplní chybějící vestavěné dlaždice, načtení při startu ano
- **Kde:** `src/main/java/mc/AtlasImage.java:203-216` (`importInto()`) proti `src/main/java/mc/Textures.java:131-139` (`atlasPixels()` volá `fillMissingBuiltInTiles()`)
- **Co / Proč:** Import staršího atlasu (bez buněk 27–34) udělá ze sněhu, bříz a smrků v labu i ve světě černé kostky. Po Save a restartu se buňky zase doplní procedurálně. Tentýž soubor tak dopadne jinak podle toho, jak se do hry dostal.
- **Ověření:** čtením kódu.

### [NÍZKÝ] LAB-15 Drobné nekonzistence editoru: prázdný krok undo, přeživší hex, přepsaná „volná" buňka
- **Kde:** `src/main/java/mc/PixelEditor.java:190-199` (`beginStroke()`); `src/main/java/mc/TextureLab.java:285-292` (`PixelMode.onLeave()` nenuluje `hexInput`); `src/main/java/mc/BlockDraft.java:149-190` (`freeTile()`) a `TextureLab.java:931-945` (`newTile()`)
- **Co:**
  - Snímek undo se uloží při každém stisku, i když `paint()` nic nezmění, takže Ctrl+Z hlásí „Undo" a nic se nestane.
  - Po přepnutí módu a návratu si klávesy dál bere pole hex, takže F6 lab nezavře a uživatel neví proč.
  - New tile přepíše bez hlášky, co si uživatel namaloval do buňky, kterou žádný blok nepoužívá. Jde to vrátit přes Ctrl+Z.
- **Ověření:** čtením kódu.

### [NÍZKÝ] LAB-16 Texty v Recipes jsou natěsno a test překryvů texty nevidí
- **Kde:** `src/main/java/mc/TextureLabLayout.java:165-167` (`RECIPE_INFO_Y = 92`, `RECIPE_LIST_Y = 106`, `PICKER_LABEL_Y = 118`) proti `:145` (tlačítka −/počet/+ na y 82–94) a `:156` (rámeček přehledu od y 127); `src/test/java/mc/TextureLabTest.java:671-678`
- **Co / Proč:** Řádek na 118 má účaří na 127, takže dotahy „p", „g", „y" sahají do rámečku. Test „prvky se nepřekrývají" kontroluje jen obdélníky ovládacích prvků, řádky textu ne. Totéž platí v Keys a Biomes.
- **Ověření:** podezření (výpočet z metrik písma, bez snímku).

### [NÍZKÝ] LAB-17 Náhled stromu mešuje i terén a jezero pod plošinkou, proti tvrzení komentáře a commitu
- **Kde:** `src/main/java/mc/TreePreview.java:199-247` (`rebuildMeshes()`, `from = (GROUND - 1) >> 4 = 3`)
- **Co / Proč:** Sekce 3 (y 48–63) obsahuje horní vrstvu terénu. Sonda v ní našla 1937 bloků (písek, kámen, 364 vody, hlína, tráva) a hladinu na y = 63, přesně ve výšce plošinky. Komentář („ten do náhledu nepatří") i commit `86cf0e2` tvrdí opak a sekce se přestaví při každém kliknutí na +/−. Jestli je terén v záběru kamery vidět, ověřené není (výpočtem spíš ne).
- **Ověření:** sondou (obsah sekce), viditelnost je podezření.

### [NÍZKÝ] LAB-18 Nejednotné znění chyb zápisu a tři styly „vybraného" tlačítka (jen pojmenováno)
- **Kde:** `TextureLab.java:795` („Save failed - see console"), `:972` a `:981` („… - block not created, see console") proti `RecipeLab.java:330`, `KeybindLab.java:190`, `BiomeTunerLab.java:275` („Could not write <soubor>", bez odkazu na konzoli); vybraný mód v panelu je zapuštěný s rámečkem (`TextureLab.java:528-530`), záložka biomu je vystouplá s rámečkem (`BiomeTunerLab.java:382-387`), tlačítko klávesy má vlastní bevel (`KeybindLab.java:289-320`); `toString().replace('\\', '/')` se opakuje zhruba 16× v pěti souborech
- **Proč je to problém:** Uživatel dostane tutéž chybu pokaždé v jiné podobě a opravy stylu je potřeba dělat na několika místech.
- **Ověření:** čtením kódu.

---

## 5. Generování světa: biomy, jeskyně, rudy, stromy, tuning (GEN)

**Pravidlo „generátor jen z vestavěných bloků" DRŽÍ:** `TerrainGenerator`, `Biome`,
`TreeShape` ani `BiomeTuning` nesahají na `BlockRegistry`, `RecipeBook` ani na data
z labu, a to ani nepřímo (`ChunkColumn.set`/`Chunk.set` nevolají
`isOpaque`/`hardness`). `BiomeTuning.Tune` nenese žádné id bloku. `TerrainGenerator`
je neměnný a tuning se bere jen jako snapshot v konstruktoru.

### [KRITICKÝ] GEN-1 Zmenšená koruna ztratí u dubu, břízy a pralesního stromu vrchní list; při poloměru 0 zmizí celé listí
- **Kde:** `src/main/java/mc/TreeShape.java:68` a `:75` (`stamp()`)
- **Co:** Vrstva, kterou delta stáhne na poloměr 0, se dál ořezává o rohy:
  `int radius = base == 0 ? 0 : Math.max(0, base + crownDelta);` a pak
  `if(trimCorners && Math.abs(dx) == radius && Math.abs(dz) == radius) continue;`.
  Při `radius == 0` je jediný blok vrstvy (dx = dz = 0) zároveň „roh", takže vrstva
  s `layerTrim = true` nepoloží nic. Dub a bříza mají poslední vrstvu `{1, trim = true}`
  a prales všechny vrstvy s ořezem. Smrková špička (základ 0, bez ořezu) funguje.
- **Proč je to problém:** Komentář téže třídy (`:19-20`) říká, že poslední vrstva leží
  nad kmenem, „bez toho by kmen končil holým špalkem". Přesně to se stane. Koruna 0 u tří ze
  čtyř druhů neznamená malou korunu, ale holý kůl. Generátor i náhled volají tentýž `stamp()`,
  takže se to trvale zapeče do terénu a `TreeShape.reach()` přitom dál hlásí, jako by koruna existovala.
- **Scénář:** Sonda, kmen 5: dub a bříza s korunou 1 dají 8 listů a nad kmenem nic, s korunou 0 žádný list. Prales s korunou 2 (jedno kliknutí na `Crown max −` z výchozích 3) dá 44 listů a nad kmenem nic.
- **Ověření:** sondou a čtením kódu.

### [STŘEDNÍ] GEN-2 `BiomeTuning.rarity()` přeteče: téměř nulová hustota rudy dá nejhustší možnou rudu
- **Kde:** `src/main/java/mc/BiomeTuning.java:185` (`rarity()`)
- **Co:** `return Math.max(1, (int) Math.round(baseRarity / density));`. Pro `density` pod asi 2,8·10⁻⁸ (železo) nebo 1,4·10⁻⁸ (uhlí) přetypování na `int` přeteče a `Math.max(1, …)` z toho udělá vzácnost 1, tedy žílu v každé buňce 4×4×4.
- **Proč je to problém:** Hodnota je v mezích 0–8, `clamped()` ji propustí a soubor ji přijme bez hlášky. Uživatel chtěl „skoro žádnou rudu" a dostane svět, ve kterém je velká část kamene ruda.
- **Scénář:** Ručně `"plains": {"ironDensity": 1e-9, "coalDensity": 1e-9}`. Sonda naměřila v pláních **413 ‰ železa a 188 ‰ uhlí** proti 11,1 ‰ a 9,0 ‰ výchozím. Test na hodnoty těsně nad nulou chybí.
- **Ověření:** sondou.

### [STŘEDNÍ] GEN-3 Násobky rud se zaokrouhlí na celočíselnou vzácnost, takže řada kroků v labu nic nezmění
- **Kde:** `src/main/java/mc/BiomeTuning.java:185` (`rarity()`) a `src/main/java/mc/BiomeTunerLab.java:59`, `:223-224` (`ORE_STEP = 0.25`)
- **Co / Proč:** `ARCHITECTURE.md:2178-2179` slibuje, že „3 znamená třikrát víc žil", jenže to platí jen pro dělitele základu. Sonda přes všech 32 kroků: uhlí (základ 30) dá jen **18 různých vzácností**. Kroky 6,75–8,0 dají všechny vzácnost 4, takže „8,0×" je ve skutečnosti 7,5× a „6,75×" dá bit po bitu tentýž svět. Železo dá 23 různých vzácností. Uživatel kliká na `+` a svět se nemění.
- **Ověření:** sondou.

### [STŘEDNÍ] GEN-4 Invariant „jeskyně nemají vchody a vodu vidí jen přes půdu" drží jen při malém převýšení a tuner ho umí rozbít
- **Kde:** `src/main/java/mc/TerrainGenerator.java:309` a `:322-341` (`generateColumn()`: `SOIL_DEPTH`, kope se jen v kameni, voda jen nad terénem)
- **Co:** Ochrana je jen svislá: nad nejvyšší jeskyní ve sloupci jsou čtyři vrstvy půdy. Vodorovně se jeskynní vzduch sloupce A dotkne vody nebo vzduchu sloupce B, jakmile `h_B <= h_A - 5`. S výchozími čísly je největší krok mezi sousedy 3 bloky, s tuningem až 11.
- **Proč je to problém:** Zdokumentované zjednodušení (`ARCHITECTURE.md:411-413`, `:826-828`: „jeskyně nemají vchody", „v podzemí není ani kapka") bez varování přestane platit. Vznikne svislá stěna nehybné vody v chodbě (voda neteče, takže se jeskyně nezaplaví) nebo vchod na útesu. `WaterTest` i `CaveTest` běží jen na výchozím tuningu.
- **Scénář:** Sonda 13×13 sloupců: výchozí tuning dá krok 3 a 0 stěn k vodě. Kopce a hory na 110, ostatní na 8, amplituda 60 dá krok 11, **57 jeskynních stěn k vodě a 16 k otevřenému vzduchu**.
- **Ověření:** sondou.

### [STŘEDNÍ] GEN-5 Úplnost korun na švech chunků se s natuněným poloměrem netestuje
- **Kde:** `src/test/java/mc/BiomeTuningTest.java:566-571` (`tunedTerrain()`) proti `src/main/java/mc/TerrainGenerator.java:249` a `:545-547` (`treeReach`, `stampTrees()`)
- **Co / Proč:** Kontrola „větší koruna zvětší dosah razítkování" volá jen `bigCrowns.maxCrownRadius() == 6`, tedy metodu `BiomeTuning`. Že ji generátor opravdu použije, neověřuje nic. `TreeTest` hlídá úplnost korun jen při výchozím tuningu. `ARCHITECTURE.md:2171-2175` to přitom označuje ⚠️ jako past. Kdyby se `treeReach` vrátil na hodnotu z druhů, všechny testy projdou.
- **Ověření:** čtením kódu.

### [STŘEDNÍ] GEN-6 Tuněný `surfaceHeight` se proti naivní sumě ověřuje jen na výchozích číslech, kde mají prales a březový les tatáž data
- **Kde:** `src/test/java/mc/BiomeTest.java:196-205` proti `src/main/java/mc/Biome.java:341-360` (`surfaceHeight(BiomeTuning, …)`)
- **Co / Proč:** Test porovnává jen výchozí tuning a v něm mají `BIRCH_FOREST` i `JUNGLE` 64/18. Záměna `birchBase` a `jungleBase` (nebo amplitud) na `:353-354` či `:359-360` by prošla oběma testovými sadami a tuning pralesa by se projevil v březovém lese. `BiomeTuningTest.tunedTerrain()` zvedá jen pláně. Dnes je kód správně, chybí jen pojistka.
- **Ověření:** čtením kódu.

### [NÍZKÝ] GEN-7 Strop terénu je spřažený s nejvyšším kmenem kteréhokoli biomu; kontrola místa nad stromem je vždy pravdivá
- **Kde:** `src/main/java/mc/TerrainGenerator.java:250` (konstruktor) a `:632-634` (`treeTypeAt()`)
- **Co / Proč:** `maxTerrainHeight = WORLD_HEIGHT - tuning.maxTreeHeight() - 1` bere maximum přes všechny biomy. Kmen 16 v pralese tak sníží strop i horám, kde nad hranicí lesa stromy nerostou. Sonda: podíl horských sloupců useknutých na strop stoupne z 0,031 % na 0,428 % (14×) a `MAX_BASE = 110` přestane být dosažitelný (strop 109). Kontrola `height + tallest < World.WORLD_HEIGHT` na `:634` je díky stropu vždy pravdivá, jde tedy o mrtvou větev.
- **Ověření:** sondou.

### [NÍZKÝ] GEN-8 Kmen kratší než počet vrstev koruny zapustí spodní vrstvy koruny pod úroveň terénu
- **Kde:** `src/main/java/mc/TreeShape.java:66` (`int y = top - (layers - 2) + layer;`)
- **Co / Proč:** Tuner povoluje kmen od 1. Při kmeni 1 leží spodní vrstva dubu 2 bloky a smrku 3 bloky pod zemí. Ve světě se v sousedních sloupcích na svahu zapíše do vzduchu pod patou stromu, v náhledu visí pod plošinkou. Náhled a svět se tak u téhož stromu liší.
- **Ověření:** čtením kódu.

### [NÍZKÝ] GEN-9 Hranice žil mezi biomy: „60 dělitelné 20" s tunerem neplatí a test hlídá jen literály
- **Kde:** `src/main/java/mc/TerrainGenerator.java:834-846` (`oreAt()`), `src/test/java/mc/CaveTest.java:350-351`
- **Co / Proč:** `ARCHITECTURE.md:546-549` se o to, že se žíly na hranici biomů neposouvají, opírá o dělitelnost. S tunerem vzniknou libovolné dvojice vzácností (třeba 30 a 20) a žíla na hranici se usekne, a to i u uhlí. Test `check("…", 60 % 20 == 0, "")` je tautologie nad literály a na kódu nezávisí. Dopad je jen vizuální.
- **Ověření:** čtením kódu.

### [NÍZKÝ] GEN-10 Duplicitní výpočet biomu a výšky u každého stromu a podhodnocená cena v komentáři
- **Kde:** `src/main/java/mc/TerrainGenerator.java:705-707` (`placeTree()`) proti `:600` a `:617` (`treeTypeAt()`)
- **Co / Proč:** `treeTypeAt()` spočítá `biomeAt()` i `terrainHeight()` a výsledky zahodí. `placeTree()` je počítá znovu, což je 10 vzorků šumu navíc na strom a sloupec (v pralese asi 75 z ~1800). Komentář na `:698` uvádí tři.
- **Ověření:** čtením kódu.

### [NÍZKÝ] GEN-11 Mrtvý kód a past `TerrainGenerator.DEFAULT`
- **Kde:** `src/main/java/mc/TerrainGenerator.java:181-182` (`DEFAULT`), `:253-257` (`tuning()`); `src/main/java/mc/BiomeTuning.java:131-152` (`isClamped()`, `variesTreeSize()` volají jen testy); `src/main/java/mc/Biome.java:165-168` (`TreeType.totalHeight()` volají jen testy)
- **Co / Proč:** `DEFAULT` nikdo nepoužívá a jeho komentář („dokud si hra seed nepamatuje") je zastaralý. Statický inicializátor přitom čte `BiomeTuning.active()`, takže obsah pole závisí na tom, kdy se třída poprvé načte. Kdo ho v budoucnu použije po `activate(custom)`, dostane natuněný generátor.
- **Ověření:** grep přes `src/`.

### [NÍZKÝ] GEN-12 Vzorec „kmen + 2" je na třech místech a nesedí na vlastní komentář
- **Kde:** `src/main/java/mc/TreeShape.java:116-119` (`totalHeight()`), `src/main/java/mc/Biome.java:165-168`, `src/main/java/mc/BiomeTuning.java:317` (`maxTreeHeight()`: `trunkMax() + 2` napsané přímo)
- **Co / Proč:** Komentář („kmen a nad jeho vrcholem ještě poslední vrstva") odpovídá `trunk + 1`, protože `stamp()` pokládá nejvyšší blok na `ground + trunk`. Rezerva je kvůli stropu 114 zřejmě záměrná, ale tři kopie se můžou rozejít a `maxTreeHeight()` `TreeShape.totalHeight()` nepoužívá.
- **Ověření:** čtením kódu.

### [NÍZKÝ] GEN-13 Nepřesné komentáře a tautologický test v generátoru
- **Kde:** `src/main/java/mc/TerrainGenerator.java:214-216` (`seedMix`: „nulu nedá žádný jiný seed", ale `:233` ořezává `fmix64` na `int`, takže nulu dá 2³² seedů); `:150-151` (`IRON_RARITY_MOUNTAINS`: „CaveTest to porovnává", ve skutečnosti jen `BiomeTuningTest:307`); `src/main/java/mc/BiomeTuning.java:67-72` (`MAX_CROWN`: „při 7 by přesáhla šířku chunku", ale 2·7+1 = 15 < 16); `src/test/java/mc/BiomeTuningTest.java:476-507` (`terrainUnchanged()` porovnává `DEFAULTS` sám se sebou, skutečnou shodu hlídají až kontrolní součty v `SeedTest`)
- **Ověření:** čtením kódu.

### [NÍZKÝ] GEN-14 Záložní větev `findLandSpawn()` a generování na mezích tuningu nemají test
- **Kde:** `src/main/java/mc/TerrainGenerator.java:529` (`findLandSpawn()`, návrat výchozího bodu), `:413` (`heightFrom()`, oba ořezy)
- **Co / Proč:** Bezpečnost proti `IndexOutOfBounds` na worker vlákně (WLD-4) dnes stojí jen na ořezech a nikdo ji neověřuje. Sonda prošla 8 kombinací mezí po 81 sloupcích bez výjimky. Při base 8 souš neexistuje a spawn se po 129² dotazech vrátí na (8, 8) do vody. Nic nespadne, jen to žádný test nedrží.
- **Ověření:** sondou (dnes v pořádku, chybí test).

### [NÍZKÝ] GEN-15 Dvě nezávislé konstanty `GROUND_HEIGHT` drží vztah `SEA_LEVEL = SAND_LEVEL − 1`
- **Kde:** `src/main/java/mc/World.java:98`, `:106`; `src/main/java/mc/TerrainGenerator.java:28`, `:34`
- **Co / Proč:** Změna jedné kopie rozejde pláž s hladinou. Komentář `World.java:97` („Ladí ji TerrainGenerator") je od biomů zastaralý.
- **Ověření:** čtením kódu.

---

## 6. Jádro světa, světlo a souběžnost (WLD)

**Souběžnost worker ↔ hlavní vlákno je ČISTÁ:** klíče jdou přes
`LinkedBlockingQueue`, sloupce zpět přes `ConcurrentLinkedQueue` (happens-before).
Worker sahá jen na neměnný generátor. Všechny čtyři globální singletony
(`BlockRegistry`, `BiomeTuning`, `RecipeBook`, `Keybinds`) jsou `volatile`
s neměnnými instancemi a z workeru je nikdo nečte. `shutdown()` je idempotentní
a fronty patří instanci, takže sloupec starého světa se do nového dostat nemůže.
Všechny nálezy níže jsou ve staré, změřené části a každý je podložený sondou
s konkrétním vstupem.

### [STŘEDNÍ] WLD-1 Změna bloku na hraně sekce nepřestaví diagonální sekce, i když jejich mesh na ní závisí (plynulé osvětlení a AO)
- **Kde:** `src/main/java/mc/World.java:976-995` (`markDirtyAround()`), `src/main/java/mc/LightEngine.java:550-564` (`touch()`); závislost vzniká v `src/main/java/mc/ChunkMesh.java:294-302` (`corner()`: `cellAt(fx + ax + bx, …)`)
- **Co:** Obě funkce vycházejí z invariantu `ARCHITECTURE.md:274-276`: „Face culling kouká
  jen na 6 stěnových sousedů … blok na rohu sekce dotkne nejvýš 3 sousedních". Plynulé
  osvětlení (přidané později) ale pro každý roh stěny čte i buňky do strany a do rohu, takže
  stěna závisí na všech 26 sousedech. Sekce diagonálně od místa změny (přes hranu nebo roh)
  se neoznačí.
- **Proč je to problém:** Diagonální sekce si ponechá staré AO a světlo, dokud ji neoznačí
  něco jiného. Po položení bloku chybí v jednom kvadrantu stín AO, po rozbití zůstane „stín duch".
  Data jsou správně, jen mesh ne, proto STŘEDNÍ a ne KRITICKÝ. Souvisí s tím podezření, že
  `WorldRenderer.neighboursLoaded()` (`WorldRenderer.java:432-438`) kontroluje jen čtyři stěnové
  sloupce, zatímco plynulé osvětlení čte i diagonální.
- **Scénář:** Sonda (seed 12345): prkna na suchou zem na roh chunku `(-64, 64, -64)`. Mesh sekce `(-5, 3, -5)` se změnil, ale v `dirtySections()` není. Po rozbití bloku je výsledek stejný.
- **Ověření:** sondou.

### [STŘEDNÍ] WLD-2 Slunce po otevření stropu nad šachtou se neprojeví v meshi nižších sekcí
- **Kde:** `src/main/java/mc/LightEngine.java:367-384` (`resendSunlightColumn()`, `:381`)
- **Co:** `world.setSkyLightAt(x, scan, z, MAX_LIGHT)` zapisuje bez `touch()`, a `World.setSkyLightAt` sekci záměrně neoznačí (`World.java:862-869`). Následné uzly BFS v šachtě 1×1 nic nezmění, takže se neoznačí ani ony. Označená zůstane jen sekce rozbitého bloku.
- **Proč je to problém:** Data světla jsou správně (proto to `LightTest` §6 nevidí), ale stěny šachty pod první sekcí zůstanou v meshi tmavé, dokud je něco jiného nepřestaví.
- **Scénář:** Sonda: šachta 40 bloků v (8, 8) → zastropit → strop rozbít. Všech 40 buněk má sky 15, mesh sekce `(0, 0, 0)` se změnil, ale sekce označená není.
- **Ověření:** sondou.

### [STŘEDNÍ] WLD-3 Klíč sloupce má degenerovaný `hashCode` a `HashMap` se mění na stromové koše
- **Kde:** `src/main/java/mc/World.java:328-331` (`key()`), `:113` (`columns`); postihuje `getBlock` (`:699`), `skyLightAt`/`blockLightAt` (`:808`/`:815`), `cellAt` (`:829`), `isColumnLoaded` (`:859`), `requestMissing` (`:477`), a stejný klíč používají `World.changes` a `WorldRenderer.columnMeshes` (`WorldRenderer.java:222`, `:451`, `:483`)
- **Co:** `Long.hashCode(((long) cx << 32) | (cz & 0xFFFFFFFFL))` vychází jako `cx ^ cz`, takže všechny body na téže „diagonále XOR" mají stejný hash. Z 289 načtených sloupců je jen 32 různých hashů a `HashMap` staví `TreeNode` koše, jejichž hledání navíc alokuje.
- **Proč je to problém:** Jde o nejteplejší cestu hry: mesher volá `cellAt` zhruba 24× na blok a BFS světla několikrát na uzel. `ARCHITECTURE.md:250` klíč popisuje, ale o rozptylu hashů mlčí, takže to není zdokumentovaný kompromis. Změřená čísla v ARCHITECTURE (mesh 0,71 ms) už tuhle cenu obsahují.
- **Scénář:** Sonda: obsazeno 16 z 512 košů, z toho 8 jako `TreeNode`. `cellAt` stojí 39,6 ns a 76 B na volání a `ChunkMesh.build` jedné sekce alokuje 767 KB. `containsKey` stojí 32,7 ns / 69 B, s promíchaným klíčem 8,1 ns / 24 B.
- **Ověření:** sondou. Že alokace pochází z `comparableClassFor`, je odvozené z kódu JDK.

### [STŘEDNÍ] WLD-4 Výjimka ve worker vlákně tiše a natrvalo zastaví generování a loading screen visí navždy
- **Kde:** `src/main/java/mc/World.java:253-276` (`generateLoop()`, `:273`), `requestMissing` `:507`, `src/main/java/mc/Main.java:1350`
- **Co / Proč:** Kolem `generateColumn` chybí `try/catch`. Výjimka ukončí vlákno, klíče zůstanou navždy v `inFlight`, `pendingColumns()` nikdy neklesne na 0 a hráč nedostane žádnou hlášku. `isWorkerAlive()` čte jen `AsyncTest`.
- **Scénář:** Sonda simuluje NPE v generátoru: worker umře a po 2000 voláních `update()` zůstává `pendingColumns=254`. Reálný vstup, na kterém by dnešní generátor hodil výjimku, se nenašel (GEN-14).
- **Ověření:** důsledek sondou, spouštěč je podezření.

### [STŘEDNÍ] WLD-5 Loading screen nečeká na světlo, přestože `pendingLight()` existuje právě kvůli němu
- **Kde:** `src/main/java/mc/Main.java:1343-1350` (`updateCreatingWorld()`) proti `src/main/java/mc/World.java:899-903` (Javadoc „Čte loading screen.")
- **Co / Proč:** Loading končí podmínkou `missingColumns == 0 && pendingMeshes == 0 && loadingFrames > 3`. `pendingLight()` se v `src/main` nevolá nikde. Hra přepne do PLAYING s nedopočítaným světlem a dosvícení pak značí sekce, které se přestavují bez rozpočtu.
- **Scénář:** Sonda: sloupce jsou hotové po 96 framech, ale zbývá 699 115 uzlů světla, které doběhnou až po dalších 279 voláních `update()` (asi 4,6 s při 60 FPS).
- **Ověření:** chybějící podmínka čtením kódu, objem zbývajícího světla sondou.

### [STŘEDNÍ] WLD-6 Invariant „`requestMissing` bez jediné alokace" neplatí: `World.update()` v klidu alokuje asi 20 KB na frame
- **Kde:** `src/main/java/mc/World.java:447-455`, `:477`, `ensureCritical` `:428`; `ARCHITECTURE.md:1065-1068`
- **Co / Proč:** `columns.containsKey(k)` autoboxuje `long` a spolu se stromovými koši (WLD-3) to dělá 289 alokujících dotazů na frame, i když nic nechybí. Výslovně označený invariant ⚠️ neplatí.
- **Scénář:** Sonda: `update()` v klidu alokuje 20 616 B na volání, z toho 20 024 B připadá na `containsKey`.
- **Ověření:** sondou.

### [STŘEDNÍ] WLD-7 Raycaster: nulová složka směru a start na celé souřadnici v ose Y nebo Z dají NaN a paprsek nic netrefí
- **Kde:** `src/main/java/mc/Raycaster.java:52-79` (`tMax*`), `:87` a `:99` (výběr osy v `cast()`)
- **Co:** `(startZ - blockZ) / Math.abs(0)` dá `0/0 = NaN`. NaN v ose Y nebo Z zablokuje výběr os X i Y, takže se krokuje v Z, `traveled = NaN` a smyčka `while (traveled < maxDistance)` skončí.
- **Proč je to problém:** Hráč nezaměří blok, na který se dívá. Spouštěč ve hře je vzácný (yaw nebo pitch přesně 0 a zároveň oko na celé souřadnici), proto STŘEDNÍ.
- **Scénář:** Sonda: kámen 3 bloky v +X, start (8.5, 100.5, 8.0), směr (1, 0, 0) → `null`. Ze z = 8.5 blok trefí.
- **Ověření:** sondou a čtením kódu.

### [STŘEDNÍ] WLD-8 `ChunkTest` §7 („líný alokátor sekcí + solidCount") nic netestuje
- **Kde:** `src/test/java/mc/ChunkTest.java:188-194`
- **Co / Proč:** Komentář tvrdí, že se ke sloupci „přes veřejné API nejde", ale `World.column()` i `ChunkColumn.section()` veřejné jsou. Kontroly `!isSolid(8,120,8)` projdou vždycky a proměnná `col` je mrtvá. Kontrola „nic nezmění" je nepravdivá, protože `breakBlock` na vzduchu zapíše změnu (WLD-10). Přechody `solidCount` v `Chunk.set()` a `isEmpty()`, podle kterého `WorldRenderer` přeskakuje sekce, tak nemají žádný test.
- **Ověření:** čtením kódu.

### [STŘEDNÍ] WLD-9 Mezery v testech async a světelných větví, které testy s `updateBlocking` maskují
- **Kde:** `AsyncTest` (§6 zastavuje jen nečinný svět), `LightTest` (kontroluje jen data, ne meshe), `SaveTest.java:44-71`, `RayTest.java:44-93`
- **Co:** Chybí testy na:
  1. úplnost `dirtySections()` proti skutečné změně meshe (chytil by WLD-1 i WLD-2),
  2. výjimku ve workeru a `shutdown()` při neprázdné frontě,
  3. větev `insert()` s `hasLightSources` (pochodeň z `changes` po unloadu, dnes funguje),
  4. zahození duplicitního výsledku v `collectFinished` (`:408-409`),
  5. Raycaster se startem na celé souřadnici a na hranici `maxDistance`.
- **Ověření:** čtením testů.

### [NÍZKÝ] WLD-10 `breakBlock`/`setBlock` vrací `true` i na vzduchu a zapisuje zbytečné změny
- **Kde:** `src/main/java/mc/World.java:915-919`, `:939-967` (`:962-963`)
- **Co / Proč:** Na vzduchu vrací `true`, vodu tiše vysuší a `changes` drží poslední stav každé dotčené buňky, ne rozdíl proti generátoru. Správnost drží jen volající (`Mining.java:140-142`) a uložený rozdíl roste s každým postaveným a rozbitým lešením.
- **Ověření:** čtením kódu.

### [NÍZKÝ] WLD-11 Raycaster vrací zásah až o jednu buňku za `maxDistance`
- **Kde:** `src/main/java/mc/Raycaster.java:86-119`
- **Co / Proč:** Limit se kontroluje před krokem, takže skutečný dosah je až asi 9,7 místo 8. Sonda: `maxDistance 2.6` trefí blok ve vzdálenosti 3,5.
- **Ověření:** sondou.

### [NÍZKÝ] WLD-12 Drobnosti v jádru světa
- **Kde a co:**
  - `World.java:399-412`: `adopted++` proběhne dřív, než se zjistí, jestli se sloupec zahodí, takže strop `MAX_ADOPTED_PER_FRAME` počítá i zahozené sloupce a po teleportu zdržuje převzetí.
  - `LightEngine.java:62-64` proti `:566-573`, `:597`: komentář slibuje ±33 mil. bloků, `sectionKey` unese jen ±8,4 mil. a `sectionKey(-524288, 0, 0) == EMPTY`.
  - `LightEngine.java:678-728`: fronty jen rostou, po načtení drží asi 8 MB (sonda).
  - `World.java:390`, `LightEngine.java:417-419`: srovnání `nanoTime() < deadline` není idiom odolný proti přetečení.
  - `World.java:690-693` proti `:841`: `getBlock(y<0)` vrací STONE, `cellAt(y<0)` vrací AIR se sky 0.
  - `World.java:440-455`, `LightEngine.java:85-103`: dva Javadocy za sebou, první je osiřelý.
- **Ověření:** čtením kódu, velikost front sondou.

### [NÍZKÝ] WLD-13 Testy nezastavují `World`, takže v jedné JVM drží živé workery staré světy v paměti
- **Kde:** `RayTest.java:33`, `:89`; `ChunkTest.java:83`, `:212`; dál `CaveTest` (4×), `CreativeTest` (3×), `MeshTest` (1×), `PhysicsTest` (4×)
- **Co / Proč:** Tyto světy nevolají `shutdown()` a živé daemon vlákno přes `this::generateLoop` drží každý z nich (asi 17 MB) až do konce běhu `AllTests`.
- **Ověření:** čtením kódu.

---

## 7. Render pipeline (REN)

**Úniky GL objektů nejsou:** výměna světa volá `reset()`, unload sloupce maže
meshe, `TextureLab.delete()` uvolní všechny náhledy, import a revert přepisují
tutéž texturu, konec hry uvolní všechno. `ShaderProgram` kontroluje
`COMPILE_STATUS` i `LINK_STATUS` a má cache lokací uniformů. GL stav mezi
průchody (depth, blend, mask, scissor, viewport) se obnovuje.

### [STŘEDNÍ] REN-1 `BlockIcon` kreslí každý kvádr vlastním draw callem: vzor „422 draw callů" se vrátil jinde
- **Kde:** `src/main/java/mc/BlockIcon.java:106-143` (`draw()`), `:194-209` (`flush()`: `glBufferSubData` + `glDrawArrays` pro každý kvádr); volající `Hud.java:168-192`, `ContainerScreen.java:863-897`, `RecipeLab.java:438-479`
- **Co:** Každá ikona (a každý kvádr modelu) je vlastní `glBufferSubData` do téhož místa téhož VBO a vlastní `glDrawArrays`. Za jeden frame to je: HUD až 9, inventář v survivalu až 42, crafting table až 47, creative přehled až 82, lab Recipes 30 až asi 98. `blockIconsBegin/End` ušetří jen přepínání shaderu, a osiřelý Javadoc (`TextureLab.java:2093-2105`) přitom tvrdí, že to je vyřešené.
- **Proč je to problém:** `ARCHITECTURE.md:1814-1821` uvádí právě tenhle vzor jako potvrzenou příčinu sekání na MacBooku. Přepis bufferu, který předchozí draw call ještě čte, navíc nutí ovladač synchronizovat.
- **Scénář:** Creative svět s plným inventářem → E: asi 82 draw callů na frame jen za ikony.
- **Ověření:** čtením kódu (počty ze skutečných rozměrů mřížek).

### [STŘEDNÍ] REN-2 `GlStats` nevidí draw cally `BlockIcon`, oblohy, pozadí, ruky, položek na zemi ani prasklin
- **Kde:** `src/main/java/mc/GlStats.java:23`, `BlockIcon.java:208`, `BackgroundRenderer.java:100`, `SkyRenderer.java:201-208`, `HeldItemRenderer.java:146`, `DroppedItemMesh.java:293`, `WorldRenderer.java:665` a `:733`
- **Co / Proč:** Javadoc slibuje, že se počítá každý draw call v UI vrstvě, ale `BlockIcon` `countDraw()` nevolá. Ve světové vrstvě počítají jen `ChunkMesh` a `PlayerModelMesh`. „Jediné číslo o výkonu nezávislé na stroji" (`ARCHITECTURE.md:1865-1867`) proto REN-1 nezachytí: F3 v Recipes ukáže asi 4 draw cally místo 34–98. Mimo lab počítadlo nikdo nenuluje, ale před čtením se vždycky vynuluje, takže přetečení je neškodné.
- **Ověření:** čtením kódu.

### [STŘEDNÍ] REN-3 `ChunkMesh` si trvale drží CPU kopii všech vrcholů
- **Kde:** `src/main/java/mc/ChunkMesh.java:64-67`, `:467-503` (`upload()`), `WorldRenderer.java:418`
- **Co:** Po `upload()` zůstávají v paměti obě pole vrcholů. Pole se zdvojují a nikdy nezmenšují. `upload()` k tomu pokaždé alokuje dočasnou kopii `combined` (`:485`).
- **Proč je to problém:** Sonda: při výchozí render distance 6 je to 679 meshů a **52,6 MB** polí, z toho 33,3 MB platných dat. Při maximu 16 je to 4481 meshů a **339 MB**. `ARCHITECTURE.md:2449` uvádí „~32 KB na sloupec" a tuhle paměť nezmiňuje. Hra se spouští `java -jar` bez `-Xmx`.
- **Ověření:** sondou (horní odhad, vzdálenost se počítá jen vodorovně).

### [STŘEDNÍ] REN-4 Průhledné pixely se v náhledu, v první a ve třetí osobě chovají různě
- **Kde:** `src/main/java/mc/SkinPreview.java:136-141` (míchání zapnuté), `WorldRenderer.java:313-328` (`drawPlayer()` bez míchání), `HeldItemRenderer.java:136` (míchání zapnuté), `BlockIcon.java:78`
- **Co / Proč:** Pixel `0x00000000` (guma, první barva palety labu) je v náhledu kůže průhledný, ve hře ve třetí osobě černý a na holé ruce v první osobě průhledný. U bloků z labu totéž: ve světě a na zemi se kreslí barvou pixelu, v ikoně a v ruce průhledně. Náhled má podle `ARCHITECTURE.md:1780-1787` ukázat přesně to, co hra.
- **Scénář:** Gumou přemalovat pixel trička: v náhledu je díra, ve hře po F5 černá skvrna.
- **Ověření:** čtením kódu.

### [STŘEDNÍ] REN-5 Geometrie `BlockIcon` nemá test, protože GL je už v konstruktoru
- **Kde:** `src/main/java/mc/BlockIcon.java:53-73`, `:106-192`
- **Co / Proč:** Pořadí rohů stěn, UV a odstíny `SHADE_*` nejdou ověřit bez GL. U ruky, postavy i položek na zemi je stavba čistá funkce a testovaná. Stejná chyba („culling potichu schová celou oblohu") už jednou nastala a kvůli ní vznikl `SkyTest`. Dnes jsou stěny ručně ověřené proti směru hodinových ručiček.
- **Ověření:** čtením kódu.

### [NÍZKÝ] REN-6 `glLineWidth(3f)` je ve forward-compatible kontextu pokaždé `GL_INVALID_VALUE`
- **Kde:** `src/main/java/mc/WorldRenderer.java:728-736`; kontext se zakládá v `Main.java:287` (`FORWARD_COMPAT = TRUE`)
- **Co / Proč:** Podle specifikace GL 3.3 (příloha E.2.1) je šířka nad 1.0 ve forward-compatible kontextu chyba, ne jen „nezaručená podpora", jak píše `ARCHITECTURE.md:318-321`. Obrys je proto vždycky 1 px a každý frame se zaměřeným blokem nastaví chybový příznak GL.
- **Ověření:** čtením kódu podle specifikace.

### [NÍZKÝ] REN-7 Drobnosti v `ShaderProgram` a `HeldItemRenderer`
- **Kde:** `src/main/java/mc/ShaderProgram.java:29-40`, `:55-61` (při chybě kompilace nebo linku se nesmažou shader objekty ani program), `:79` (`setMatrix4` alokuje `new float[16]` při každém volání, asi 5× za frame, v rozporu s komentářem `WorldRenderer.java:75`); `src/main/java/mc/HeldItemRenderer.java:43-44`, `:72`, `:323` (pevný buffer na 4 kvádry bez kontroly: model s pěti kvádry, třeba plánovaný napojený plot, skončí `ArrayIndexOutOfBoundsException`)
- **Ověření:** čtením kódu.

### [NÍZKÝ] REN-8 Náhledy světů se neuvolní při odchodu ze seznamu, i když to Javadoc slibuje
- **Kde:** `src/main/java/mc/SelectWorldScreen.java:24-25`, `:436-445`; Main volá `delete()` jen při ukončení hry (`Main.java:248`)
- **Co / Proč:** Textury náhledů se smažou až při dalším `refresh()` nebo na konci hry. Není to únik, jen VRAM obsazená celou hru a Javadoc, který nesedí.
- **Ověření:** čtením kódu.

### [NÍZKÝ] REN-9 `TextRenderer.widthOf()` bere měřítko z posledního `begin()`, `fit()` ho dostává parametrem
- **Kde:** `src/main/java/mc/TextRenderer.java:149-158`, `Widgets.java:158-172`, `TextureLab.java:2072-2087`
- **Co / Proč:** Dnes se oba údaje shodují, jde o latentní nekonzistenci. `fit()` navíc každý frame staví řetězce s O(n²) alokacemi.
- **Ověření:** čtením kódu.

### [NÍZKÝ] REN-10 Mrtvý kód v renderu
- **Kde:** `WorldRenderer.java:119`, `:426`, `:743` (`meshesBuilt()` a `meshesBuiltThisFrame` nikdo nečte); `FontAtlas.java:215` (`ascent()` nikdo nevolá); `Hud.java:58-59`, `:97-128` (nepoužitý parametr `world` a smyčka, ve které po `if` zbyl jen komentář)
- **Ověření:** grepem.

### [NÍZKÝ] REN-11 Duplicitní GL programy a konstanty (jen pojmenováno)
- **Kde a co:**
  - `TextureLab.java:211` vytváří vlastní `BlockIcon` vedle `Main.icons`.
  - `BackgroundRenderer` a `ImageRenderer` sdílejí program a mají skoro stejný kód.
  - `SkyRenderer.java:45` kompiluje program `OUTLINE` podruhé.
  - Tři náhledy kompilují každý svůj program `WORLD`, takže každé otevření labu slinkuje 5 programů, které hra už má.
  - Konstanty `SHADE_*` jsou v pěti třídách.
- **Ověření:** čtením kódu.

### [NÍZKÝ] REN-12 Zastaralé komentáře v renderu
- **Kde a co:**
  - `BlockAtlas.java:13-15`: uvádí mřížku 4×4 a 64×64, skutečnost je 8×8 a 128×128. `:33-34` odkazuje na `Textures.blockAtlas()`, `:61` na neexistující `colorFor()`.
  - `Texture.java:18-23`: „GL_REPEAT" a „až přijde atlas".
  - `FontAtlas.java:23-24`: barva „uniformem", ve skutečnosti je ve vrcholu.
  - `Renderer2D.java:17`: „TŘI ROZHODNUTÍ", vyjmenovaná jsou čtyři.
  - `Shaders.java:109-123`: osiřelé Javadocy, `:272-273` „zatím jen pozadí".
  - `SkyRenderer.java:19`: „čtyři tisíce" vrcholů, ve skutečnosti 5412.
  - `ChunkMesh.java:36-38`: odkazuje na „Hud", správně `BlockIcon`.
- **Ověření:** čtením kódu.

### [NÍZKÝ] REN-13 Sazba písma ve `FontAtlas` nejde testovat bez GL
- **Kde:** `src/main/java/mc/FontAtlas.java:80-147`, `:202-240`
- **Co / Proč:** `textWidth`, `LETTER_SPACING` a náhrada znaku `?` jsou čistá aritmetika, ale konstruktor nahrává texturu, takže na ně test nejde napsat. Při ručním průchodu je kód v pořádku.
- **Ověření:** čtením kódu.

---

## 8. Hráč, kamera, pohyb, animace (PLR)

**Keybinds v pohybu DRŽÍ:** držené klávesy se čtou jen přes `down()` →
`effectiveKey()` s ochranou `NONE`, takže kolidující klávesa nedělá nic.
Natvrdo jsou jen zdokumentované výjimky (Esc, Ctrl jako modifikátor Q, myš).
`GL.createCapabilities()` a `glReady` předcházejí první `windowMode.apply()`.
Rozhlížení jede z nepřepočítaných bodů okna.

### [STŘEDNÍ] PLR-1 Creative: s drženým tlačítkem padne jeden blok za FRAME, takže obyčejný klik vykope tunel
- **Kde:** `src/main/java/mc/Mining.java:83-87` (`update()`, větev creative), `src/main/java/mc/Main.java:1411-1424` (`updatePlaying()`)
- **Co:** V creative vrací `update()` true hned ve framu, kdy se s drženým tlačítkem míří na blok, a nemá žádnou prodlevu. V dalším framu paprsek trefí blok ZA ním a padne taky. Tlačítko se drží i při běžném kliknutí (80–120 ms).
- **Proč je to problém:** `ARCHITECTURE.md:2340-2343` tohle zdůvodňuje slovy „stejně jako ve vanille". Vanilla ale mezi rozbitími v creative čeká 5 ticků (0,25 s), takže jedno kliknutí zboří jeden blok. Tady je počet zbořených bloků úměrný FPS a stavět v creative je nepraktické. `CreativeTest` testuje jen první frame.
- **Scénář:** Sonda: sloupec kamene, pohled dolů, 100 ms drženého LMB → 6 bloků při 60 FPS, 8 (strop dosahu) při 240 FPS.
- **Ověření:** sondou.

### [STŘEDNÍ] PLR-2 `Camera.processMouse` nemá test a `PhysicsTest` běží jen s dt = 1/60
- **Kde:** `src/test/java/mc/CameraTest.java`, `src/test/java/mc/PhysicsTest.java:8`; `src/main/java/mc/Camera.java` (`processMouse`, `invertMouseY`)
- **Co / Proč:** Ořez pitch na ±89, na kterém stojí nedegenerovaná matice `setLookAt`, ani obrácená osa nemají test (grep `processMouse|invertMouseY` v testech najde 0 výskytů). Závislost fyziky na FPS (skok 1,29–1,47, blikání `onGround`, viz PLR-3) nic nehlídá, jediné jiné dt je tunelovací test s `dt = 5`.
- **Ověření:** čtením testů.

### [NÍZKÝ] PLR-3 `onGround` nad asi 167 FPS bliká
- **Kde:** `src/main/java/mc/Player.java:394-398` (`moveY()`, dosednutí s `EPSILON` 1e-3), `:89`
- **Co / Proč:** Stojící hráč má `y = n + 0,001`. Když `½·g·dt²` vyjde pod 0,001 (dt < ~6 ms), první frame pádu kolizi nenajde. Sonda: `onGround` je true v 1000/1000 framech při 60 a 144 FPS, v 500/1000 při 240 a v 125/1000 při 1000 FPS. Skok s drženým mezerníkem se zpozdí o jeden frame (240 FPS) až pět framů (1000 FPS), počet kroků se nemění. Nastává to ve výchozím `maxFps = Unlimited` s vypnutým vsyncem.
- **Ověření:** sondou.

### [NÍZKÝ] PLR-4 Neomezeně rostoucí floaty (`PlayerAnimation.time`, `phase`, `Camera.yaw`) časem ztrácejí přesnost
- **Kde:** `src/main/java/mc/PlayerAnimation.java:81` (`time += dt`), `:86` (`phase += …`), `src/main/java/mc/Camera.java:51` (`yaw += …`)
- **Co / Proč:** Nic se nebalí modulo 2π nebo 360. `yaw` se ukládá do `world.dat` a roste napříč sezeními. Sonda: od `time = 16384` (asi 4,5 h v jednom běhu) při 1000 FPS běží pohupování 1,95× rychleji a od 65 536 stojí úplně.
- **Ověření:** sondou.

### [NÍZKÝ] PLR-5 Kreslená postava dostane neořezané `dt`, ale posun z ořezané fyziky
- **Kde:** `src/main/java/mc/Main.java:1395` (`animation.update(dt, hypot(...))`) proti `Player.java:159` (`dt = Math.min(dt, 0.05f)`)
- **Co / Proč:** Po zaseknutí (F11, GC, tah oknem) vyjde rychlost několikrát menší a nohy postavy ve třetí osobě na jeden frame cuknou do klidu.
- **Ověření:** čtením kódu.

### [NÍZKÝ] PLR-6 `WindowMode.apply()` při selhání fullscreenu nechá nastavení a okno v rozporu
- **Kde:** `src/main/java/mc/WindowMode.java:77-81` (`apply()`)
- **Co / Proč:** Bez video módu se jen vypíše chyba, `fullscreen` zůstane false, ale `options.fullscreen()` je true. Každé další `applyOptions()` (i každý tah posuvníkem) pokus zopakuje a znovu vypíše chybu, přičemž Options ukazuje „Fullscreen: ON".
- **Ověření:** čtením kódu.

### [NÍZKÝ] PLR-7 Podezření ze vstupu a smyčky bez potvrzeného dopadu
- **Kde a co:**
  - `Main.java:965-969`: pořadí `pollEvents` → `limiter.sync` přidává při stropu FPS jeden frame latence myši (při 30 FPS až ~33 ms).
  - `Main.java:676-677`: `signum(yoffset)` z každé události kolečka, takže trackpad na macOS protočí hotbar o celé sloty.
  - `Main.java:292-313`, `:969`: v minimalizovaném okně hra dál simuluje a se zapnutým vsyncem a neblokujícím `swapBuffers` se smyčka točí naprázdno.
- **Ověření:** podezření (odvozeno z pořadí volání, závisí na platformě).

### [NÍZKÝ] PLR-8 Mrtvý kód a zastaralé komentáře kolem kamery a okna
- **Kde:** `src/main/java/mc/Camera.java:65-86`, `:196-220` (`getForwardMoveTarget`, `getRightMoveTarget`, `getUpMoveTarget`, `moveForward`, `moveRight`, `moveUp`, `rightVector`: nikdo je nevolá, pozůstatek volné kamery); `Camera.java:5-8` („Simple free-fly camera … no gravity/collision yet"), `:37`; `WindowMode.java:36-39` (`isFullscreen()` se nepoužívá), `:51` (odkaz na neexistující `WindowModeTest`); `GameState.java:24` („Texture lab - úpravy dlaždic atlasu"); `Camera.java:88-95` (`forwardVector()` alokuje `float[3]` při každém volání, asi 5× za frame plus 8 paprsků ve třetí osobě)
- **Ověření:** grepem a čtením kódu.

---

## 9. Zvuk (SND)

**Životní cyklus OpenAL DRŽÍ:** každé `alGen*` má `alDelete*` v `shutdown()`,
`shutdown()` je idempotentní a uklidí i po nepovedeném `open()`, `play*` po
`shutdown()` nic neudělá.

### [STŘEDNÍ] SND-1 Jeden vadný nebo obří soubor v `sounds/` vypne celý zvuk, nebo shodí hru
- **Kde:** `src/main/java/mc/Wav.java:94-96`, `:123` (`decode()`), `src/main/java/mc/SoundLibrary.java:39-58`, `:220-236` (`load()`, `:222` `Files.readAllBytes`), `src/main/java/mc/SoundEngine.java:79-84` (`open()`)
- **Co:** (a) Test `position + 8 + length > data.length` přeteče do záporu pro délku bloku kolem
  `Integer.MAX_VALUE`, kontrola projde, `position` zezáporní a `in.getInt()` vyhodí
  `IndexOutOfBoundsException`. `SoundLibrary.load` chytá jen `IOException`, takže výjimka
  doletí do `SoundEngine.open()`, a ten vypne **celý** engine s hláškou „Zvuk vypnuty: null",
  ze které nejde poznat, který soubor to způsobil. (b) `readAllBytes` bez stropu velikosti
  u souboru nad 2 GB hodí `OutOfMemoryError`, který nechytá ani `SoundLibrary`, ani
  `SoundEngine.open()` (`catch(RuntimeException | LinkageError)`), takže hra spadne při startu
  (`Main.java:274`) nebo při načtení světa (`:1091`).
- **Proč je to problém:** Porušuje `ARCHITECTURE.md:1415` („⚠️ Chyba zvuku hru nepoloží")
  i `SoundLibrary.java:13-15` („nejde přečíst → placeholder … hra zní vždycky celá"). Místo
  jednoho zvuku zmizí všech 13, a to po každém `freshWorld()` znovu.
- **Scénář:** `sounds/break_stone.wav` s blokem `LIST` o deklarované délce `0x7FFFFFF0`. Useknutý nebo streamovaný export může vypadat přesně takhle.
- **Ověření:** sondou (přetečení i řídký 3 GB soubor s `-Xmx256m`).

### [STŘEDNÍ] SND-2 Zvuk kliknutí u „Play Selected World", u dvojkliku a u „Create" hraje PŘED akcí, která engine zavře
- **Kde:** `src/main/java/mc/Main.java:588` (`case PLAY -> { sound.play(Sound.CLICK); playWorld(...); }`), `:599` (`case CREATE -> { sound.play(Sound.CLICK); createWorld(); }`); zastaralý Javadoc `handleMenuClick()` `:1561-1564`
- **Co:** `playWorld`/`createWorld` → `freshWorld` → `sound.shutdown()` (`alSourceStop` + `alcCloseDevice`) proběhne v tomtéž callbacku pár milisekund po `alSourcePlay`. Javadoc `handleMenuClick()` pořád mluví o tlačítkách „Create World" a „Load World" v hlavním menu, která už neexistují. Pravidlo se tedy při přesunu tlačítek na nové obrazovky nepřeneslo.
- **Proč je to problém:** Přímo porušuje ⚠️ pravidlo `ARCHITECTURE.md:1419-1421` („Zvuk kliknutí v menu hraje AŽ PO akci tlačítka … zvuk pouštěný před akcí by se hned uťal"), a to právě u tlačítek, kvůli kterým vzniklo.
- **Scénář:** Singleplayer → Play Selected World (nebo dvojklik na řádek, nebo Create).
- **Ověření:** pořadí volání čtením kódu, slyšitelný dopad neměřen.

### [STŘEDNÍ] SND-3 `SoundTest` a `LabBlockTest` kontrolují materiál zvuku jen do `World.FENCE` (14), ne do `LAST_BUILT_IN` (20)
- **Kde:** `src/test/java/mc/SoundTest.java:68` (`for (int id = 0; id <= World.FENCE; id++)`), `src/test/java/mc/LabBlockTest.java:333`
- **Co / Proč:** Sníh, březové a smrkové kmeny, listí a pralesní listí (id 15–20) se nekontrolují, a totéž bude platit pro každý další vestavěný blok. `ARCHITECTURE.md:1455-1458` přitom tvrdí, že test „projde všechny bloky a hlídá, že se tabulky nerozejdou, když přibude nový blok". Nový blok zapomenutý v `Sound.Material.of()` spadne na `default -> EARTH` a test to nepozná. Dnes jsou bloky 15–20 náhodou konzistentní (ověřeno ručně).
- **Ověření:** čtením kódu.

### [NÍZKÝ] SND-4 `WAVE_FORMAT_EXTENSIBLE` se přijme bez kontroly subformátu
- **Kde:** `src/main/java/mc/Wav.java:126` (`decode()`)
- **Co / Proč:** U 0xFFFE se nečte GUID subformátu, takže A-law nebo µ-law v extensible obálce se dekóduje jako PCM a místo hlášky a placeholderu hraje šum. Vzácný vstup.
- **Ověření:** podezření (podle specifikace formátu).

### [NÍZKÝ] SND-5 Komentář připisuje kontrolu špatnému testu
- **Kde:** `src/main/java/mc/Sound.java:115-116` (`byHardness`: „SoundTest hlídá…")
- **Co / Proč:** Tu kontrolu ve skutečnosti dělá `LabBlockTest.sound()`, ne `SoundTest`.
- **Ověření:** čtením kódu.

---

## 10. Obrazovky mimo lab (UI)

### [STŘEDNÍ] UI-1 Posuvník v Options zůstane „chycený", když se obrazovka zavře Escem během tažení
- **Kde:** `src/main/java/mc/OptionsScreen.java:79`, `:151-162` (`drag()`, `release()`), `src/main/java/mc/Main.java:338-346`, `:579-581` (release jen ve stavu `OPTIONS`), `:1299-1302` (`closeOptions()`)
- **Co / Proč:** `dragged` nuluje jen `release()`, a ten Main volá jen ve stavu `OPTIONS`. Instance `OptionsScreen` je jedna na celý běh, takže po dalším otevření jde posuvník za myší bez drženého tlačítka a každý pohyb volá `applyOptions()`. Hodnota se tak mění „sama".
- **Scénář:** Options → držet tlačítko na FOV → Esc → pustit myš → znovu Options → pohnout myší → FOV se mění.
- **Ověření:** čtením kódu.

### [STŘEDNÍ] UI-2 Nekonzistentní zvuk kliknutí a hover mezi stejně vypadajícími tlačítky
- **Kde:** `src/main/java/mc/OptionsScreen.java:137-147` (přepínače vracejí `false`), `SelectWorldScreen.java:168-180`, `:202-206` (dialog a Delete vracejí `NONE`), `CreateWorldScreen.java:113-117` (Game Mode vrací `NONE`), `Main.java:571-601`, `:1262-1286`, `:553-563`
- **Co / Proč:** Main pouští `Sound.CLICK` jen u akcí, které obrazovka vrátí (Done, Play, Create, Cancel). Tlačítka, která vypadají stejně (`Widgets.button`), ale obslouží se uvnitř obrazovky, nezní: Fullscreen, VSync, GUI Scale, Invert Mouse, Delete, oba knoflíky dialogu, Game Mode. Lab nezní vůbec a Enter/Esc přes `screenKey()` neklikají. Hover u `Widgets.button` je skokový, kdežto `Menu` má animovaný přechod, a `ARCHITECTURE.md:2432` říká „Zvýraznění tlačítek je přechod". Jako tichá místa `ARCHITECTURE.md:1420-1421` uvádí jen HUD a sloty.
- **Scénář:** Options → klik na „Fullscreen" (ticho) proti kliku na „Done" (zvuk).
- **Ověření:** čtením kódu.

### [NÍZKÝ] UI-3 Ladicí výpis F3 formátuje čísla podle výchozí lokalizace a každý frame
- **Kde:** `src/main/java/mc/Main.java:1702-1745` (`debugLines()`, volané každý frame z `:1433`)
- **Co / Proč:** Asi 13× `String.format` bez `Locale`. Font umí jen ASCII 32–126, takže v lokalizacích ar-SA, fa-IR nebo mr-IN jsou všechna čísla nečitelná (`?? FPS`), ověřeno sondou. Jinde projekt `Locale.ROOT` používá (`Options.toJson`). `showDebug` je ve výchozím stavu zapnutý, takže se pole a formátování alokují každý frame.
- **Ověření:** sondou (lokalizace), alokace čtením kódu.

### [NÍZKÝ] UI-4 Text v polích Create New World přeteče pole i panel
- **Kde:** `src/main/java/mc/CreateWorldScreen.java:197`, `:202` (`render()`: `widgets.label(...)` bez `fit` a bez ořezu)
- **Co / Proč:** Pole má pro text 192 GUI px, limit je 32 znaků a „M" zabere 8 px, takže 32 × 8 = 256 px, ještě bez kurzoru. Dlouhé jméno nebo textový seed vyjede z pole.
- **Scénář:** Napsat do jména 32× „W".
- **Ověření:** čtením kódu (šířky glyfů podle ARCHITECTURE).

### [NÍZKÝ] UI-5 Schránka se čte při každé klávese a Cmd+V na macOS nefunguje
- **Kde:** `src/main/java/mc/Main.java:1277` (`screenKey()`: `glfwGetClipboardString` pro každý PRESS i REPEAT), `src/main/java/mc/CreateWorldScreen.java:144-146` (reaguje jen na `GLFW_MOD_CONTROL`)
- **Co / Proč:** Na X11 znamená každé čtení round-trip k vlastníkovi schránky, takže se psaní může zadrhávat (podezření). Hra macOS podporuje, ale `GLFW_MOD_SUPER` (Cmd) nebere.
- **Ověření:** čtením kódu.

### [NÍZKÝ] UI-6 Při tažení posuvníku se `applyOptions()` volá při každém pohybu myši, i když se hodnota nezmění
- **Kde:** `src/main/java/mc/OptionsScreen.java:184-208` (`slide()` nastaví `changed = true` vždycky), `Main.java:340-344`
- **Co / Proč:** Každá událost kurzoru vyvolá `glfwSwapInterval`, `windowMode.apply`, přepis okruhů `World` a `setBrightness`, i když krok posuvníku zůstal stejný. Dopad neměřen.
- **Ověření:** čtením kódu.

### [NÍZKÝ] UI-7 Tautologická kontrola v `MenuTest`; `OptionsTest.java` git bere jako binární
- **Kde:** `src/test/java/mc/MenuTest.java:106-107` (`buttonAt(...) >= -1` platí vždycky, takže úzké okno se reálně netestuje); `src/test/java/mc/OptionsTest.java` (jediný soubor v repu s CRLF a doslovnými bajty `0x00 0x01 0x02` na `:156`, takže ho `file` hlásí jako `data`, `git log --numstat` jako `-  -` a diff změn testu nejde vidět); `OptionsTest.java:~339-341` (test vkládání „ne-ASCII" vkládá čisté ASCII a usekne se dřív, než by k diakritice došel, takže filtr v `TextField.insert` není pokrytý)
- **Ověření:** `file`, `git log --numstat`, `cat -A`, čtení kódu.

### [NÍZKÝ] UI-8 Zastaralé komentáře, mrtvý kód a duplicitní logika v obrazovkách
- **Kde a co:**
  - `CreateWorldScreen.java:11` („Dvě textová pole a dvě tlačítka", jsou tři) a `:13` („uvozovkou (2)", správně „se závorkou").
  - `Hud.java:13-14` („pro 5 slotů", `HOTBAR_SIZE` je 9).
  - `SelectWorldScreen.java:24-25`, `:436` („volat při zavření obrazovky", viz REN-8).
  - `TextField.java:25` (`maxLength()` nikdo nevolá).
  - Tlačítko s bevelem a hit-test je třikrát: `Menu.render/buttonAt` (`Menu.java:83-171`, inkluzivní hrany, animovaný hover), `Widgets.button` + `ScreenLayout.Rect.contains` (`Widgets.java:74-90`, `ScreenLayout.java:87-90`, polootevřený interval, skokový hover) a lab (`TextureLab.java:~533-541`).
  - Psaní ASCII jména dvakrát: `TextField.type/printable` a `TextureLab.typedPixel` (`:1245-1257`).
  - Testové pomocné funkce `separate`/`centre` jsou ve třech testech zvlášť.
- **Ověření:** čtením kódu.

---

## 11. Dokumentace: `ARCHITECTURE.md` proti kódu (DOC)

Rozpory, ve kterých `ARCHITECTURE.md` popisuje něco, co neplatí. Neopravují se
tady, jen se hlásí.

### [NÍZKÝ] DOC-1 Počty dlaždic a bloků se rozešly s kódem
- **Kde:** `ARCHITECTURE.md:2252-2255` („vestavěné dlaždice … dnes končí na 26", „Dnes je jich 37" volných) a `BlockDraft.java:15` proti `BlockAtlas.java:66-75` (`TILE_COUNT = 35`, vestavěné končí na 34, volných 29); `ARCHITECTURE.md:2511` a `:2515` (dvakrát za sebou „obsazených 35"); `:2416` („78 bloků" v creative přehledu, dnes 20 + 64 = 84)
- **Proč je to problém:** Rezerva volných buněk se biomy tiše zmenšila o osm. Kdo si před biomy založil 30 a víc dlaždic z labu, sdílí dnes buňky 27–34 se sněhem, břízou, smrkem a pralesním listím, protože `BlockRegistry.load` u dlaždic kontroluje jen rozsah.
- **Ověření:** čtením kódu a sondou `ProbeFreeTiles`.

### [NÍZKÝ] DOC-2 Změřená čísla fyziky neodpovídají kódu
- **Kde:** `ARCHITECTURE.md:2499` („skok 1,19 bloku"), `Player.java:30-33` („1.26 bloku"), `ARCHITECTURE.md:851` („klesání 1,2 bloku za sekundu")
- **Co:** Sonda naměřila skok 1,33 při 60 FPS (1,29 při 144, 1,47 při ≤ 20 FPS kvůli stropu dt) a klesání ve vodě 1,83 b/s. Komentář u `WATER_VERTICAL_DRAG` („kolem 1,9") sedí, ARCHITECTURE ne. Pravidlo „jeden schod ano, dva ne" platí při všech FPS.
- **Ověření:** sondou.

### [NÍZKÝ] DOC-3 Tvrzení, která se vztahují ke stavu před pozdějšími změnami
- **Kde a co:**
  - `ARCHITECTURE.md:2457-2458`: „Frustum culling ani async generace nejsou implementované". Async generování je implementované a má vlastní sekci (`:597`).
  - `ARCHITECTURE.md:144`: vertex `ChunkMesh` „`pozice(3) + uv(2) + odstín(1)`", ve skutečnosti 7 floatů (slunce + blokové světlo).
  - `ARCHITECTURE.md:2449`: „Paměť ~32 KB na sloupec" nezahrnuje CPU kopie meshů (REN-3).
  - `ARCHITECTURE.md:683-684`: „Poškozený nebo cizí soubor tedy skončí založením nového světa", což odporuje `:739-741`.
  - `ARCHITECTURE.md:2292` a `:2560-2562`: `saves/world.dat` a tlačítko „Load World" v menu, dnes víc světů a Select World.
  - `ARCHITECTURE.md:2181-2182`: „`CaveTest` i `BiomeTuningTest` to porovnávají". `IRON_RARITY_MOUNTAINS` porovnává jen `BiomeTuningTest`.
  - `ARCHITECTURE.md:275`, `:1068`, `:1457`: invarianty, které přestaly platit (viz tabulka níže).
- **Ověření:** čtením kódu.

---

## 12. Build (BLD)

### [NÍZKÝ] BLD-1 `pom.xml` používá `source`/`target` místo `release`, takže překlad na novějším JDK nehlídá API Javy 17
- **Kde:** `pom.xml:10-11` (`maven.compiler.source/target = 17`)
- **Co:** Varování `system modules path not set in conjunction with -source 17` znamená, že
  javac překládá proti knihovnám JDK, na kterém běží (21 zde, 26 v IntelliJ), ne proti API
  Javy 17. `ARCHITECTURE.md:44-45` tvrdí, že v kódu „není novější API", ale ověřeno to bylo
  jednorázovým ručním překladem s `--release 17`. Build to nehlídá.
- **Proč je to problém:** Použití metody z Javy 21+ projde buildem a pád (`NoSuchMethodError`) se ukáže až uživateli na JRE 17.
- **Ověření:** výstup `mvn -B compile`.

---

## Invarianty z ARCHITECTURE.md, které přestaly platit

Odpověď na bod 4 zadání: „známá zjednodušení", ⚠️ pravidla a slíbené ochrany,
které pozdější změna nevědomky porušila.

| invariant (ARCHITECTURE.md) | co ho rozbilo | nález |
|---|---|---|
| „Ukládá se při zavření okna" (`:686`) | stav `CONTAINER` a Options z pauzy nejsou v podmínce | MAIN-1 |
| „Options z pauzy se kreslí přes svět" (`:1522-1523`) | `optionsReturnState` je `PAUSED`, kód se ptá na `PLAYING` | MAIN-1 |
| „Co se vztahuje ke konkrétnímu světu, patří do `resetPlayerState()`" (`:645-646`) | let a noclip přibyly později | MAIN-2 |
| „Esc zavírá vždycky" (`:2053`) | větve DROP, FULLSCREEN a LAB jsou před Esc | MAIN-3 |
| „F11 přepíná odkudkoliv" (`:1537-1538`) | lab spolkne všechny klávesy | MAIN-4 |
| „Zvuk kliknutí hraje AŽ PO akci" (`:1419`) | tlačítka se přestěhovala z menu na nové obrazovky | SND-2 |
| „Chyba zvuku hru nepoloží" (`:1415`) | `SoundLibrary` chytá jen `IOException` | SND-1 |
| „`SoundTest` projde všechny bloky" (`:1457`) | smyčka končí na `FENCE`, biomy přidaly id 15–20 | SND-3 |
| „Face culling kouká jen na 6 sousedů" (`:275`) | plynulé osvětlení čte diagonály | WLD-1 |
| „`requestMissing` nealokuje" (`:1068`) | boxing v `containsKey` + stromové koše | WLD-6 |
| „Jeskyně nemají vchody, v podzemí ani kapka" (`:411-413`, `:826-828`) | tuner zvětšil možné převýšení | GEN-4 |
| „60 dělitelné 20 → žíly se neposunou" (`:546-549`) | tuner povoluje libovolné vzácnosti | GEN-9 |
| „3 znamená třikrát víc žil" (`:2178`) | celočíselná vzácnost | GEN-3 |
| „Náhled stromu = pravda o tom, co vyroste" (`:2202-2211`) | svět náhledu je z aktivního tuningu, výška je pevná | LAB-9 |
| „Poslední vrstva koruny nad kmenem" (`TreeShape.java:19-20`) | delta koruny + ořez rohů | GEN-1 |
| „Lab vestavěný vzor ani neuloží" (`:1976`) | kontrola nezná bezetvaré recepty | INV-3 |
| „Suroviny se spotřebují až odebráním výsledku" (`:888`) | `add()` přidá i jen část | INV-1 |
| „Mřížka je trvalý kontejner" (`:894`) | mřížka se neukládá do `world.dat` | INV-2 |
| „Přidat mód = třída a jeden řádek" (`:1898`) | hub rozhoduje podle identity módu | LAB-7 |
| „Barva je společná pro obě záložky" (`:1747`) | `applyHsv()` nastaví jen atlas | LAB-1 |
| „Počet draw callů je jediné číslo o výkonu" (`:1865`) | `BlockIcon` nevolá `countDraw` | REN-1, REN-2 |
| „~32 KB na sloupec" (`:2449`) | CPU kopie meshů | REN-3 |
| „`MainStateTest` prochází seznam" (`Main.java:1073`) | test má napevno jen čtyři položky | MAIN-7 |
| „Atomický zápis + `.bak`" (`:1572-1574`, `:2282-2285`) | `world.dat`, `atlas.png`, `skin.png` vzor nemají | PER-1, PER-2 |

---

## Příloha A — Matice vzoru ukládání (odpověď na bod 2, JSON a soubory)

| soubor (třída) | atomický zápis | `.bak` nečitelného před přepsáním | chybí → mlčky výchozí | poškozený → výchozí + stderr | vadná položka shodí jen sebe | novější `format` → varování |
|---|---|---|---|---|---|---|
| `textures/blocks.json` (`BlockRegistry`) | ano, vlastní kopie kódu (`:327`) | ano, ale přepíše starší `.bak` (PER-3) | ano | ano; bez `format`/`blocks` se zahodí celý soubor | ano | ano |
| `textures/recipes.json` (`RecipeBook`) | ano, vlastní kopie (`:335`) | ano, přepíše starší `.bak` | ano | ano; bez `format` se zahodí celý soubor | ano (i recept s neznámým blokem) | ano, přes `intValue()` |
| `keybinds.json` (`Keybinds`) | ano (`SafeFiles`) | ano | ano | ano | ano | ano; chybějící `format` mlčí |
| `biome_tuning.json` (`BiomeTuning`) | ano (`SafeFiles`) | ano | ano | ano | **ne úplně**: přetečení a zlomek tiše (PER-7) | ano; chybějící `format` mlčí |
| `options.json` (`Options`) | ano (`SafeFiles`) | ano | ano (`!Files.exists`) | ano | ano + ořez s hlášením | ano; chybějící `format` mlčí |
| `saves/*/world.json` (`WorldSaves`) | ano (`SafeFiles`) | ano, ale přepíše i dobrou `.bak` (PER-3) | odvozené údaje ze složky (záměr) | `.bak`, pak odvozené údaje | ano | ano, i chybějící |
| `saves/*/world.dat` (`WorldStorage`) | **NE** (PER-1) | **NE** (zdokumentovaná mezera) | nový svět se seedem z metadat | `null` + stderr, svět se nepřepíše | – (poziční binárka) | MCW4 = „cizí formát" (PER-11) |
| `saves/*/icon.png` (`Thumbnails`) | ano (vlastní tmp + move) | – (dá se vyrobit znovu) | ano | ano | – | – |
| `textures/atlas.png` (`AtlasImage`) | **NE** (PER-2) | **NE** (PER-2) | ano (procedurální) | ano | vestavěné doplní, lab ne (PER-4) | – |
| `textures/skin.png` (`AtlasImage`) | **NE** (PER-2) | **NE** | ano | ano | – | – |
| `sounds/*.wav` (čtení) | – | – | ano (placeholder) | **jen `IOException`** (SND-1) | **ne**: vypne se celý zvuk | – |

`Json` parser samotný je robustní: strop vnoření 64 (sonda s milionem `[`
skončí `IllegalArgumentException`, ne `StackOverflowError`), BOM, prázdný soubor,
`NaN`/`Infinity`, neukončený řetězec, koncové smetí i cp1250 končí čistou chybou.
Všech šest volajících chytá `RuntimeException`. Migrace starého `saves/world.dat`
je testovaná po každém kroku a `WorldSaves.delete` u symlinku smaže jen odkaz.
`items.json` neexistuje (0.1).

## Příloha B — Srovnání labů (odpověď na bod 3)

Item Lab neexistuje (0.1). „Block Lab" je formulář nového bloku uvnitř módu
Blocks.

| | Blocks | Skin | Block Lab (nový blok) | Recipes | Keys | Biomes |
|---|---|---|---|---|---|---|
| **hláška: kde, jak dlouho** | stavový řádek, **2,5 s** (LAB-4); „(unsaved)" | totéž | totéž | stavový řádek **5 s** + trvalý řádek problému | **5 s** + trvalé řádky kolizí, červená tlačítka | **5 s**, žádná trvalá chyba |
| **znění chyby zápisu** | „Save failed - see console" | totéž | „… - block not created, see console" | „Could not write …" | „Could not write …" | „Could not write …" |
| **validace** | import **odmítnout**, hex **odmítnout**, HSV **oříznout** | totéž | znaky **tiše zahodit**, délku **tiše useknout** (20), Create **odmítnout** | počet **oříznout** 1–64, vzor **normalizovat**, Save **odmítnout** | přiřadit jde i kolize, Save **odmítnout** | čísla **oříznout**, min a max se **tlačí** (lab), soubor je **prohodí** |
| **undo** | Ctrl+Z, 100 kroků; **po znovuotevření pryč** (LAB-3) | vlastní zásobník | přes undo atlasu | **ne** | **ne** | **ne** |
| **revert** | tlačítko, bez potvrzení | totéž | Cancel / Esc | **ne** (jen Clear) | jen nepřímo (přepnout mód) + Defaults | totéž |
| **Save = zapsat, pak aktivovat** | mění se živě; zápis **neatomický** (PER-2) | totéž | ano (atlas → blocks.json → activate) | ano | ano | ano (projeví se až v příštím světě) |
| **neuložené při přepnutí módu** | zůstane | zůstane | **tiše zahozeno** | **zůstane** | **tiše zahozeno** | **tiše zahozeno** |
| **neuložené při zavření labu** | pixely zůstanou, „(unsaved)" zmizí | totéž | zahozeno | zahozeno | zahozeno | zahozeno |
| **ukazatel neuloženého** | „(unsaved)" | „(unsaved)" | titulek „new block" | žádný | „built-in/custom" podle návrhu, ne podle souboru | totéž |
| **F3 měření** | přepíná (natvrdo F3) | přepíná | přepíná | **nejde přepnout, ale platí** | totéž | totéž |
| **kolečko** | nic | nic | nic | roluje přehledem | nic | přepíná biom |

Z tabulky vyplývají nálezy LAB-4, LAB-5, LAB-6 a LAB-18. Sjednotit by šlo znění
chyb, délku hlášky, ukazatel neuloženého, chování při přepnutí módu a F3.

## Příloha C — Mapa míst, kde je „hromádka = blok" zadrátované

Tato příloha je informativní a nepočítá se do nálezů. Je to podklad pro budoucí
zavedení předmětů (viz 0.1).

- **Datový model a slévání:** `ItemStack.java:13` (`record ItemStack(byte block, int count)`), `:17` (`EMPTY = (World.AIR, 0)`), `:21` (`of()`: AIR = prázdno), `:37` (`stacksWith`, mrtvé), `:55` (`toString`); `Container.java:97`, `:115`, `:119`, `:155` (`countOf(byte)`); `ContainerScreen.java:558`, `:588`, `:611`, `:682`, `:698`, `:728`; `CreativeInventory.java:43-91` (vyjmenovává jen id bloků).
- **Kreslení:** `BlockIcon.java:106` (`draw(…, byte block)` → `BlockAtlas.tile` + `BlockModels.of`); `Hud.java:188`, `ContainerScreen.java:884`, `:894`; `HeldItemRenderer.java:110`, `:158` (`isBareHand(block == AIR)`), `:167`, `:315`; `Main.java:1480`, `:1501`; `PlayerModelMesh.java:114`, `:139`, `:293`; `DroppedItemMesh.java:121`; `RecipeLab.java:455`, `TextureLab.java:2117`, `:2281`.
- **Pokládání a těžba:** `Main.java:638-647` (`placeBlock(selected.block())`, `afterPlace`, `Sound.placeOf`); `GameMode.java:174`; `World.java:925`; `Sound.java:144`; `Mining.java:138`, `:154` (vytěžený blok padá jako on sám, chybí tabulka „co padá").
- **Recepty:** `Recipes.java:24` (`record Recipe(… byte[] pattern, byte result …)`), `:105`, `:113`, `:132`, `:212`, `:243`, komentář `:82-86` (pochodeň z prkna, protože klacek neexistuje); `RecipeBook.java:197-225`, `:239-252`, `:449-460`, `:569-591`; `RecipeLab.java:129`, `:158`, `:249`, `:512`.
- **Serializace:** `WorldStorage.java:134-142` (`writeByte(block)` + `writeInt(count)`), `:237-241`, `:304-309`; `Main.java:1196`; `TextureLab.java:63`.

---

## Souhrn

| oblast | KRITICKÝ | STŘEDNÍ | NÍZKÝ |
|---|---|---|---|
| MAIN — stavový automat, ukládání, vstup | 2 | 5 | 8 |
| PER — persistence a soubory | 2 | 6 | 5 |
| INV — inventář, crafting, registry | 1 | 3 | 5 |
| LAB — laby | 3 | 7 | 8 |
| GEN — generování světa | 1 | 5 | 9 |
| WLD — jádro světa a souběžnost | 0 | 9 | 4 |
| REN — render | 0 | 5 | 8 |
| PLR — hráč, kamera, pohyb | 0 | 2 | 6 |
| SND — zvuk | 0 | 3 | 2 |
| UI — obrazovky mimo lab | 0 | 2 | 6 |
| DOC — ARCHITECTURE.md proti kódu | 0 | 0 | 3 |
| BLD — build | 0 | 0 | 1 |
| **celkem** | **9** | **47** | **65** |

**Celkem 121 nálezů: 9 KRITICKÝCH, 47 STŘEDNÍCH, 65 NÍZKÝCH.** Nálezy hlášené
víc průchody jsou sloučené. `mc.AllTests` prochází (1914/1914), takže žádný
z nálezů testy nezachytily. U většiny KRITICKÝCH je důvod v tom, že větev, ve
které leží, nemá test (MAIN-7, INV-4, LAB-10, WLD-9).

## Doporučené pořadí řešení (jako samostatné budoucí prompty)

1. **Ukládání při ukončení.** Doplnit `CONTAINER` a Options z pauzy do podmínky
   (včetně `returnItems`) a opravit `optionsReturnState` i kreslení světa za Options.
   Podmínku vytáhnout do čisté funkce s testem, stejně jako `mainMenuAction()`, a přidat
   `try/finally` kolem `loop()`. Nálezy MAIN-1, MAIN-6, MAIN-8, MAIN-9. Je to nejlevnější
   oprava s největším dopadem, protože dnes jde přijít o celou session.
2. **Bezpečný zápis všude.** `world.dat`, `atlas.png` a `skin.png` přes tmp + move, u PNG
   i `.bak`, a nepřepisovat dobrou `.bak` poškozeným souborem. Nálezy PER-1, PER-2, PER-3,
   PER-8. Jeden prompt, protože jde o tentýž vzor `SafeFiles`.
3. **Duplikace a ztráta předmětů.** `takeResult` s neatomickým `add()`, obsah mřížky při
   uložení, validace počtu a pozic z `world.dat`, a k tomu testy chybějících větví
   `ContainerScreen`. Nálezy INV-1, INV-2, INV-4, PER-6.
4. **Reset stavu mezi světy.** Let, noclip a kamera, a `MainStateTest` přepsat tak, aby
   chránil opravdu to, co slibuje. Nálezy MAIN-2, MAIN-7.
5. **Tři kritické chyby labů a jejich headless testy.** HSV v Skin, drop souboru mimo
   pixelové módy, ztráta „(unsaved)" a undo po znovuotevření, dvojí odečet hlášky, a testy
   hubu a `KeybindLab.key()`. Nálezy LAB-1, LAB-2, LAB-3, LAB-4, LAB-10.
6. **Generátor a tuning.** Koruna s poloměrem 0, přetečení `rarity()` a `integer()`,
   zaokrouhlení rud, uložení na dvě desetinná místa, náhled stromu na aktivním tuningu,
   a k tomu testy s natuněnými čísly. Nálezy GEN-1, GEN-2, GEN-3, GEN-5, GEN-6, PER-7,
   PER-10, LAB-9.
7. **Konzistence vstupu a zvuku.** Esc „vždycky", F11 v labu, `GLFW_REPEAT` na
   obrazovkách, zvuk kliknutí po akci, nápovědy z Keybinds, přebindovaný FULLSCREEN
   a chycený posuvník. Nálezy MAIN-3, MAIN-4, MAIN-5, MAIN-10, MAIN-11, SND-2, UI-1, UI-2.
8. **Robustnost zvuku.** Nálezy SND-1, SND-3.
9. **Přestavba meshů po změně (stará, změřená část, opatrně).** Diagonální sekce
   a slunce v šachtě, test úplnosti `dirtySections()`, výjimka ve workeru, loading
   a světlo. Nálezy WLD-1, WLD-2, WLD-4, WLD-5, WLD-9. Před opravou i po ní změřit A/B
   jako dřív, protože značení víc sekcí zvýší počet přestaveb.
10. **Výkon, vždy s měřením před a po.** Dávkování `BlockIcon` a počítání v `GlStats`,
    hash klíče sloupce a boxing, CPU kopie meshů. Nálezy REN-1, REN-2, WLD-3, WLD-6,
    REN-3. Až po opravě REN-2 bude vidět, co REN-1 stojí.
11. **Sjednocení labů (designové rozhodnutí, nejdřív se zeptat).** Neuložená práce při
    přepnutí módu a zavření, F3, rozhraní `LabMode` bez identity módů, jednotné hlášky.
    Nálezy LAB-5, LAB-6, LAB-7, LAB-18.
12. **Úklid na konec, jedním průchodem.** Mrtvý kód, zastaralé komentáře, duplicity,
    `.gitignore`, `release` v `pom.xml` a aktualizace `ARCHITECTURE.md` podle kapitoly
    DOC a tabulky porušených invariantů. Všechny zbývající NÍZKÉ nálezy a PER-5.
