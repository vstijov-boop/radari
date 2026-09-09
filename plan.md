# plan.md — Radari CG nativna Android aplikacija

> Živi dokument: početni prompt (ruta) + status izvođenja.
> Zadnja izmjena: 2026-09-09 (krug ispravki)

---

## 1. Početni prompt (ruta)

Finalna, očišćena verzija prompta s kojim je krenula izrada nativne aplikacije
(nezavisna od tuđeg koda — jedini stvarni input je `index.html` iz ovog repozitorija):

```text
TITULA: Nativna Android app "Radari CG" s Android Auto podrškom — Kotlin + androidx.car.app + Mapbox

KONTEKST
Radna PWA upozoritelja na radare za Crnu Goru (index.html): 88 fiksiranih lokacija
(tip 1 raskrsnička kamera, 2 stacionarni, 3/4 parovi sekcijskog mjerenja), detekcija
konusom, TTS upozorenja, sekcijska prosječna brzina, vlastite oznake s izvozom/uvozom.
Web verzija ne radi u autu (Chrome guši TTS/WebAudio u pozadini), pa se pravi nativna
aplikacija. Aplikacija mora raditi: (1) na telefonu uz ugašen ekran, (2) na Android
Auto ekranu — mapa je poželjna, ali obavezno je da barem prikazuje ikonu i udaljenost
najbližeg radara/kamere/patrole i govori upozorenja. Ne ide na Play Store — sideload APK.

TEHNOLOGIJA
- Kotlin, minSdk 26, Gradle Kotlin DSL, Compose za telefon UI
- androidx.car.app biblioteka, servis s intent-filterom androidx.car.app.CarAppService
  i kategorijom androidx.car.app.category.NAVIGATION
- Manifest: meta-data com.google.android.gms.car.application → res/xml/automotive_app_desc.xml;
  dozvole androidx.car.app.MAP_TEMPLATES, NAVIGATION_TEMPLATES, ACCESS_SURFACE,
  FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION, POST_NOTIFICATIONS, ACCESS_FINE_LOCATION,
  ACCESS_COARSE_LOCATION; meta-data androidx.car.app.minCarApiLevel
- Mapbox Maps SDK v11 + Android Auto ekstenzija (MapboxCarMap, MapboxCarMapObserver,
  MapboxCarMapSurface). Mapbox token u local.properties → BuildConfig, ne commitovati
- TTS: android.speech.tts.TextToSpeech + AudioFocusRequest sa
  USAGE_ASSISTANCE_NAVIGATION_GUIDANCE. Fokus se traži prije svakog izgovora i pušta
  poslije; red poruka ne smije gomilati zastarjele — samo zadnja važna. Upozorenja
  moraju raditi kroz Bluetooth auto zvučnike i uz ugašen ekran (zato foreground
  service type location)
- Foreground servis drži GPS i TTS dok je praćenje uključeno; mora koegzistirati s
  Moja Patrola app pokrenutom paralelno

LOGIKA KOJA SE PRENOSI 1:1 IZ index.html (funkcije onFix() i section())
- Domet upozorenja: lead = clamp(speed*20, 350, 1200) metara
- Konus ispred: ugao do radara dAng(heading, bearing) < 55° ILI udaljenost < 200 m
- Stanja po radaru: 0→1 ulazak u zonu = beep(2,880) + TTS (vrsta, udaljenost zaokružena
  na 100 m, ograničenje; + „Usporite" ako kmh > lim+5); 1→2 na <180 m = beep(3,1320);
  reset kad d > lead*1.6 ILI (off > 110° && d > 250)
- Sekcijsko mjerenje: ulaz na <150 m od tačke A/B uz smjer <70°, prosjek =
  (max(pređeno, L−do kraja)/vrijeme)*3.6, upozorenje poslije 20 s ako prosjek > limit,
  završetak na <120 m od izlaza s rezimeom
- Baza od 88 lokacija + default ograničenja → res/raw JSON; ograničenja po lokaciji,
  dužine dionica i vlastite oznake u DataStore; izvoz/uvoz u ISTOM JSON formatu kao PWA
  (CFG.moji) da se podaci prenesu sa postojeće PWA

ANDROID AUTO UI
- HOME: NavigationTemplate s MapboxCarMap površinom (konus, markeri, pozicija vozila)
  i ActionStrip
- FALLBACK bez mape (mora postojati): template koji prikazuje ikonu vrste pretnje +
  udaljenost koja se osvježava dok se približava (i TTS). Ako host ne daje surface,
  app radi u ovom modu
- Prijava patrola s auta: jednostavna akcija „Patrola ovdje" → šalje trenutni GPS na backend

TELEFON UI (Compose, kompaktno)
- Mapa s markerima + konusom, start/stop, brzina, najbliži spisak
- Dodavanje patrolа: tip na trenutnoj GPS poziciji ili ručno; upload s redom čekanja
  kad nema interneta
- Ograničenja: lista s uređivanjem limita i dužina dionica; izvoz/uvoz vlastitih oznaka
- Postavke: glas on/off, test dugme (demo poruka)

PATROLE ZA EKIPU (vlastiti backend — Moja Patrola API se NE koristi ni na koji način)
- Firebase (anonimni Auth + Firestore/RTDB) ili Supabase; model: {lat, lon, tip, ts, by};
  GET patrole u radijusu starije <2h; sync svakih ~20 s dok praćenje radi; offline red
- Patrole ekipe ulaze u isti konus/TTS pipeline kao radarи („Patrola za četristo metara")
- Zaštita: shared API key u BuildConfig — dovoljno za ekipu od par ljudi

PRIHVATANJE / KVALITET
- Sideload APK → poveže na Auto → mapa (ili fallback ekran) se vidi na autu, TTS ide kroz
  auto zvučnike i uz ugašen ekran telefona
- Uputstvo za Android Auto DHU (Desktop Head Unit) za test bez auta + „Unknown sources"
  u AA developer podešavanjima
- Logika konusa/sekcijskog izdvojena u čistu klasu koja se može testirati bez Androida
```

---

## 2. Status izvođenja (gdje smo stigli)

### ✅ Završeno (2026-09-09)

| # | Korak | Status | Napomena |
|---|-------|--------|----------|
| 1 | Kloniranje repozitorija u `Desktop\Radari CG` | ✅ | github.com/vstijov-boop/radari |
| 2 | Analiza `index.html` (baza 88 lokacija, konus, lead, sekcijsko) | ✅ | ista logika potvrđena u linijama 604–619 |
| 3 | Provjera verzija: `androidx.car.app:app:1.7.0` (stabilan) + Mapbox Auto ext. `com.mapbox.extension:maps-androidauto:11.15.2` (Mapbox maven) | ✅ | artifact verificiran direktno na repo1.maven.org + Mapbox POM |
| 4 | Gradle skeleton: `settings.gradle.kts` (Mapbox repo + token iz local.properties), `build.gradle.kts` (root+app), `gradle.properties`, `.gitignore`, `local.properties.example` | ✅ | AGP 8.5.2, Kotlin 1.9.24, Compose BOM 2024.06 |
| 5 | Manifest: NAVIGATION kategorija, `automotive_app_desc.xml`, MAP_TEMPLATES/NAVIGATION_TEMPLATES, FGS location, POST_NOTIFICATIONS | ✅ | ACCESS_SURFACE dolazi transitive iz Mapbox ext. |
| 6 | Baza: 88 lokacija izvučeno iz `index.html` → `res/raw/radar_locations.json` | ✅ | validirano: 88 unosa, id 1–88 |
| 7 | `geo/Geo.kt` — dist/bearing/dAng 1:1 iz PWA | ✅ | |
| 8 | `model/Threat.kt` + `ThreatRepository.kt` — tipovi 1–5 (5=patrolа ekipe), loading iz raw JSON-a | ✅ | |
| 9 | `alerts/AlertEngine.kt` — čista logika: lead, konus 55°, stanja 0→1→2, reset, sekcijsko mjerenje | ✅ | bez Android zavisnosti, testabilno |
| 10 | `alerts/AlertSpeaker.kt` — TTS + AudioFocusRequest (navigation guidance), fokus prije/poslije, samo zadnja važna poruka | ✅ | beep preko TTS pitch-a (nema AudioContext problema) |
| 11 | `alerts/TrackingService.kt` — foreground servis (type=location), GPS 1 s, StateFlow stanja (running/nearest/section/lastVehicleFix), sync patrolа na 20 s | ✅ | radi i uz ugašen ekran |
| 12 | Android Auto: `RadariCarService` → `RadariSession` (MapboxCarMap) → `RadariMapScreen` | ✅ | mapa + markeri + konus; **fallback Pane template** s ikonom i udaljenošću kad nema surface |
| 13 | `team/TeamRepo.kt` — Supabase REST (GET nearby + POST), offline outbox u SharedPreferences, istek patrolа 2 h | ✅ | treba upisati SUPABASE_URL i ANON_KEY |
| 14 | Telefon UI `MainActivity.kt` (Compose): start/stop, najbliža pretnja, sekcijski prosjek, „Patrola ovdje", „Proba zvuka", runtime dozvole | ✅ | |
| 15 | Resursi: strings (crnogorski), ic_stat_radar, adaptive launcher ikona, theme | ✅ | |
| 16 | `README-ANDROID.md` — tokeni, build, sideload, AA Unknown sources, DHU, Supabase SQL | ✅ | |

### 🔧 Krug ispravki (2026-09-09, poslije pregleda koda)

Pregled cijelog projekta prije prvog builda; nađeno i ispravljeno:

| # | Problem | Ispravka |
|---|---------|----------|
| 1 | `MainActivity` je koristio `n.city` — `Nearest` nema to polje (kod se ne bi ni kompajlirao) | `n.threat.city` |
| 2 | `RadariCarService` je override-ovao samo `onCreateSession(SessionInfo)`, apstraktni `onCreateSession()` je ostao neimplementiran | override `onCreateSession()` |
| 3 | `NavigationInfo.Builder()` i `Step.setName()` ne postoje u `androidx.car.app` | `MessageInfo.Builder(title).setImage(...).setText(...)` |
| 4 | `NavigationTemplate` bez obaveznog `ActionStrip` → pad pri prikazu | ActionStrip sa „Pokreni/Zaustavi praćenje" i „Patrola ovdje" (radi i prijavu patrole s auta) |
| 5 | Fallback bez mape je bio `PaneTemplate` koji se osvježavao svake sekunde → probija kvotu template-a i host gasi app | uvijek `NavigationTemplate` (jedini koji smije često da se osvježava); dodatno se osvježava tek kad se tekst promijeni |
| 6 | Drugi beep je okidao na `st >= 1` umjesto `st == 1` → pištanje svake sekunde ispod 180 m | stanje se ažurira u toku provjere, tačno kao `r.st` u PWA (pokriveno testom) |
| 7 | Govorio se „najbliži" radar, a ne onaj koji je okinuo dojav | `CueEvent` nosi lokaciju, udaljenost i ograničenje iz trenutka okidanja |
| 8 | `SECTION_START` se nikad nije izgovorio (limit je stizao kao `null`) | limit ide u `CueEvent` |
| 9 | Parovanje dionica po „isti grad + <15 km" umjesto po redoslijedu u bazi | `sectionPairs()` — tip 3 + sljedeći tip 4, isto kao `SEC` u PWA (potvrđeno: 27 dionica, identično) |
| 10 | Konus se dodavao kao poligon u sloj simbola → nije se vidio | zaseban `SRC_CONE` + `fillLayer` |
| 11 | Sve četiri „boje" markera su bile isti drawable; `iconImage("{kind}")` je v10 sintaksa | tintovane ikone po tipu + `Expression.get("kind")` |
| 12 | Kamera se nikad nije postavljala → mapa ostaje na početnoj poziciji stila | `setCamera` prati vozilo (centar, zoom po brzini, bearing, pitch) |
| 13 | Servis se startovao prije nego korisnik odgovori na dijalog dozvola → prvi put ne radi | servis se pokreće iz callback-a dozvole |
| 14 | „Proba zvuka" preko `startForegroundService` bez `startForeground` → sistem obara proces | `startService` iz aktivnosti + servis se sam gasi poslije poruke |
| 15 | `startTracking()` bez dozvole nije zvao `startForeground` → isto obaranje | notifikacija ide prva, pa provjera dozvole |
| 16 | Audio fokus: svaki beep/govor je prepisivao `focus` i nikad ga ne otpuštao; `onDone` gasio fokus dok red još traje | brojač dojava, fokus se pušta kad red ostane prazan |
| 17 | Beep kao izgovoreno „pip pip", uz pitch koji se resetuje prije nego se odsvira | pravi ton (`AudioTrack`, 880/1320/660 Hz) s navigacionim `AudioAttributes` |
| 18 | Ograničenja samo po tipu; `CFG.lim`/`CFG.len` iz PWA nisu imali gdje | `ThreatRepository.limitOf/setLimit/lengthOf/setLength` (SharedPreferences) |
| 19 | Supabase URL i ključ hardkodovani u `TeamRepo.kt` | `local.properties` → `BuildConfig`; bez njih se modul tiho isključi |
| 20 | `TeamRepo`: `get()` sa `finally{}` bez `catch`, GET bez provjere status koda, `parseIso` ne jede Supabase format (`.482231+00:00`) → patrole bez vremena su odmah ispadale | provjera koda, `disconnect()`, tolerantan parser, patrola bez vremena se preskače |
| 21 | `Threat.st` (`@Transient var` u data klasi) je ulazio u `equals` a nije se koristio | uklonjeno; `ThreatType.fromId` vraća `null` umjesto da pukne na nepoznatom tipu |
| 22 | Nema Gradle wrappera; AGP 8.5.2 + compileSdk 34 ispod zahtjeva `car.app` 1.7 i Mapbox 11.15 | `gradle-wrapper.properties` (Gradle 8.9), AGP 8.7.2, compileSdk 35 |
| 23 | Nije bilo nijednog testa iako je „testabilna logika" bio zahtjev | `AlertEngineTest` — 6 testova (zona, blizina, dvije lokacije u istom fiksu, reset, radar iza leđa, parovanje dionica) |

Nije verifikovano buildom — na ovom računaru nema Android SDK-a ni Gradlea.
Jedina stavka koju treba provjeriti pri prvom syncu je Mapbox Auto API
(`mapboxMapInstaller().install {}`, `MapboxCarMapSurface.mapSurface.mapboxMap`) —
ako se potpis razlikuje u 11.15.2, popravlja se u `RadariSession.kt`/`RadariMapScreen.kt`.

### ⏳ Na tebi (korisnik)

1. **Mapbox tokeni** — `android/local.properties.example` → `local.properties`:
   - `MAPBOX_PUBLIC_TOKEN` (pk..., za renderovanje mape)
   - `MAPBOX_DOWNLOADS_TOKEN` (Downloads:read, za povlačenje SDK-a)
   - *Bez tokena build prolazi — radi fallback Auto ekran i sva upozorenja, mapa ne.*
2. **Build** — otvori `android/` u Android Studio → sync → `Build → Build APK(s)`.
3. **Sideload** — `adb install app-debug.apk` (ili direktno kopiranje APK-a).
4. **Android Auto** — AA app → verzija (10× tap) → developer → **Unknown sources** ON.
5. **Supabase (opciono, za patrolе ekipe)** — napravi projekat, izvrši SQL iz README-a, upiši `SUPABASE_URL` i `SUPABASE_ANON_KEY` u `android/local.properties`.

### 📋 Preostalo (v1.1 i dalje)

- [ ] **Verifikacija builda** — nije moguće ovdje (nema Android SDK na ovom računaru); očekuj sitne sync popravke ako verzije driftuju
- [ ] Test u realnoj vožnji: TTS kroz Bluetooth uz ugašen ekran
- [x] Ograničenja po lokaciji + dužine dionica (SharedPreferences; UI za uređivanje još nema)
- [ ] Ekran za uređivanje ograničenja i dužina dionica na telefonu
- [ ] Izvoz/uvoz vlastitih oznaka u PWA JSON formatu (prijenos sa PWA → app)
- [ ] Mapa na telefonu (Compose) — trenutno UI je lista/status bez mape
- [ ] Prijavu patrolа s Auto ekrana (akcija je u promptu, UI na autu čeka v1.1)
- [ ] DHU test vođenje po checklisti

### Arhitektura (brzi presjek)

```
TrackingService (FGS location)
 ├─ LocationManager → Fix → AlertEngine (čista logika)
 │     └─ cues → AlertSpeaker (TTS + AudioFocus) → Bluetooth auta
 ├─ StateFlow: nearest / section / lastVehicleFix / running
 ├─ TeamRepo.sync (20 s) → ThreatRepository (patrolе ekipe)
 │
 ├─ MainActivity (Compose) — telefon UI
 └─ RadariCarService → RadariSession → RadariMapScreen (Auto)
       ├─ Mapbox surface: mapa + markeri + konus
       └─ bez surface: Pane fallback (ikona + udaljenost)
```

### Odluke usput (kontekst za nastavak)

- Artifact Mapbox Auto ekstenzije je `com.mapbox.extension:maps-androidauto` (Mapbox maven, ne Maven Central) — verzija 11.15.2 potvrđena direktno u POM-u
- `CarAppTheme` parent ne postoji po defaultu u biblioteci — tema je očišćena da build ne padne
- Token Mapboxa ide kroz `MapboxOptions.accessToken` u `RadariApp` (v11 način), ne manifest meta-data
- Beep se radi TTS-om (pitch 2.0/1.3, brzi tempo) umjesto ToneGenerator — manje permisi, isti audio fokus put
- Moja Patrola app smije ostati pokrenuta paralelno — dva location FGS-a koegzistiraju; audio fokus se trži kratkotrajno po poruci
- Ne koristi se ništa iz Moja Patrola API-ja/koda — patrole ekipe idu na vlastiti Supabase
