# Radari CG — nativna Android aplikacija (telefon + Android Auto)

Nativna verzija tvoje radar PWA: 88 fiksiranih lokacija + žive patrole ekipe,
glasovna upozorenja kroz auto (TTS + audio fokus) i prikaz na Android Auto ekranu.

## Šta je unutra

- `alerts/AlertEngine.kt` — čista logika 1:1 iz `index.html` (konus 55°, domet `speed*20` clamp 350–1200 m, stanja radara, sekcijsko mjerenje)
- `alerts/TrackingService.kt` — foreground servis (type=location): GPS + TTS radi i uz ugašen ekran, sinkronizira patrole ekipe svakih ~20 s
- `alerts/AlertSpeaker.kt` — TTS + AudioFocusRequest (`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`); fokus se traži kad krene prvi dojav i pušta tek kad se red isprazni. Beep je pravi ton (AudioTrack, 880/1320/660 Hz kao u PWA), ne izgovoren tekst
- `auto/` — Android Auto: `RadariCarService` (kategorija NAVIGATION) → `RadariSession` (MapboxCarMap) → `RadariMapScreen`. Uvijek `NavigationTemplate` (jedini template koji host dozvoljava da se često osvježava): kad ima Mapbox surface, ispod se crta mapa s markerima, konusom i kamerom koja prati vozilo; kad je nema, isti ekran radi kao ikona + udaljenost
- `team/TeamRepo.kt` — patrole ekipe preko Supabase REST-a, offline red čekanja u SharedPreferences; URL i ključ iz `local.properties`, bez njih se modul tiho isključi
- `src/test/.../AlertEngineTest.kt` — JVM testovi logike konusa i dionica (`Run 'Tests in app'` u Studiju ili `gradlew test`)
- `MainActivity.kt` — telefon UI (Compose): start/stop, najbliža pretnja, sekcijski prosjek, „Patrola ovdje", proba zvuka

## Prije builda

1. Kopiraj `android/local.properties.example` → `android/local.properties` i upiši:
   - `MAPBOX_PUBLIC_TOKEN` — javni token (pk...) sa <https://account.mapbox.com/access-tokens/>
   - `MAPBOX_DOWNLOADS_TOKEN` — token sa scopeom `DOWNLOADS:READ` (isti sajt, "Downloads:read" checkbox)
2. Opciono, za patrole ekipe u istom fajlu:
   - `SUPABASE_URL` i `SUPABASE_ANON_KEY`
3. **Bez tokena se projekat i dalje gradi** — mapa neće raditi, ali Auto ekran i sva zvučna upozorenja rade.

## Build

1. Otvori folder `android/` u **Android Studio** (Ladybug ili noviji) — AGP 8.7.2, Gradle 8.9, compileSdk 35
2. Sačekaj Gradle sync (skidanje Mapbox SDK-a zahtijeva download token). Wrapper jar nije u repozitorijumu; Studio ga napravi sam iz `gradle/wrapper/gradle-wrapper.properties`
3. `Build → Build APK(s)` → debug APK
4. Sideload: `adb install app-debug.apk` ili kopiraj APK na telefon i instaliraj

## Android Auto

1. Na telefonu: Android Auto → podešavanja → ** verzija i informacije o aplikaciji** → tapni 10× na verziju → razvojni podešavanja → uključi **Nepoznati izvori**
2. Poveži telefon kablom s kolima — "Radari CG" se pojavljuje među navigacijskim aplikacijama
3. Test bez auta: instaliraj **Android Auto Desktop Head Unit (DHU)** — see <https://developer.android.com/training/cars/testing>

## Patrole ekipe (Supabase)

1. Napravi besplatni projekat na <https://supabase.com>, u SQL editoru:
   ```sql
   create table patrols (
     id bigint generated always as identity primary key,
     lat double precision not null,
     lon double precision not null,
     note text,
     created_at timestamptz default now()
   );
   ```
2. Upiši `SUPABASE_URL` i `SUPABASE_ANON_KEY` u `android/local.properties` (ne u kod)
3. Patrole starije od 2 h automatski ispadaju; kad nema interneta poruka ide u red i šalje se kasnije

## Logika upozorenja (ista kao u PWA)

- Domet: `speed*20 s`, min 350 m, max 1200 m
- Ispred = ugao do radara < 55° ili udaljenost < 200 m
- Ulazak u zonu → beep + „radar za 400 metara, ograničenje 80" (+ „Usporite" ako si preko limita+5)
- 180 m → drugi beep
- Sekcijsko: ulaz 150 m, upozorenje ako prosjek > limit poslije 20 s, rezime na izlazu
