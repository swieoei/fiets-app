# Fiets

Eigen fiets-app als PWA: GPS-fietscomputer, ritten- en onderhoudslog en accu-planner.

- Live: https://swieoei.github.io/fiets-app/
- Start automatisch een rit zodra de gekoppelde fiets via Bluetooth verbindt (Chrome op Android).
- Thema kleurt mee met de ondersteuningsstand (Uit wit, Breeze groen, River blauw, Rocket roze-paars).
- Totaal-scherm met statistieken over alle ritten.
- Stopt de rit na 5 minuten stilstand; die stilstand telt niet mee.
- Gegevens staan alleen lokaal op het apparaat (back-up via export/import).

Bij een update: verhoog de cachenaam in `sw.js` (`fiets-app-v1` → `v2`).
- Weergave kiezen in het tabblad Accu: Display (zwart kleurendisplay) of Neon (zonsondergang met palmbomen). Lettertype Kanit, SIL Open Font License 1.1 (zie `fonts/OFL.txt`).
- Weergave Lockscreen: grote cijfers en glazen ringen; achtergrond naar keuze (eigen foto blijft alleen op het toestel, in IndexedDB) of de standaard roze palmen. Lettertype Antonio, SIL Open Font License 1.1 (zie `fonts/OFL-Antonio.txt`).
