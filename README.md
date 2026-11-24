# Fiets

Eigen fiets-app als PWA: GPS-fietscomputer, ritten- en onderhoudslog en accu-planner.

- Live: https://swieoei.github.io/fiets-app/
- Start automatisch een rit zodra de gekoppelde fiets via Bluetooth verbindt (Chrome op Android).
- Thema kleurt mee met de ondersteuningsstand (Uit wit, Breeze groen, River blauw, Rocket roze-paars).
- Totaal-scherm met statistieken over alle ritten.
- Stopt de rit na 5 minuten stilstand; die stilstand telt niet mee.
- Gegevens staan alleen lokaal op het apparaat (back-up via export/import).

Bij een update: verhoog de cachenaam in `sw.js` (`fiets-app-v1` → `v2`).
- Weergave kiezen in het tabblad Accu: Display (zwart kleurendisplay) of Neon (neonkleuren, lichtgevende meter). Lettertype Kanit, SIL Open Font License 1.1 (zie `fonts/OFL.txt`).
- Weergave Lockscreen: grote cijfers en glazen ringen; achtergrond naar keuze (eigen foto blijft alleen op het toestel, in IndexedDB) of de standaard roze palmen. Lettertype Antonio, SIL Open Font License 1.1 (zie `fonts/OFL-Antonio.txt`).

## Android-app (meten op de achtergrond)

In `android/` staat een kleine Android-app (Kotlin, geen extra bibliotheken) die dezelfde pagina toont in een WebView en zelf meet, ook met het scherm uit of de app dicht:

- Android meldt wanneer de gekoppelde fiets in de buurt komt (Companion Device Manager, `BikePresenceService`). De app start dan `RideService`, verbindt via Bluetooth met de Fazua-aandrijving (stand via dienst 0xFD6D, accu via 0x180F) en start de rit.
- GPS-afstand, rijtijd en de stop na 5 minuten stilstand werken zoals in de web-app (`RideEngine`, met tests in `app/src/test`). Blijft de fiets aan na zo'n stop, dan start een nieuwe rit zodra je weer rijdt.
- Afgeronde ritten wachten in de app tot de pagina ze overneemt in het logboek (`window.FietsNative`).
- Laadt de telefoon op terwijl de fiets verbindt (houder op de fiets), dan opent de app vanzelf.

Bouwen en installeren (telefoon via USB):

```bash
cd android && ./gradlew testDebugUnitTest assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Eerste keer: tik op het Bluetooth-icoon om de fiets te koppelen (fiets aan) en kies bij locatie "Altijd toestaan".
