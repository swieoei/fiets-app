# Fiets

Eigen fiets-app als PWA: GPS-fietscomputer, ritten- en onderhoudslog en accu-planner.

- Live: https://swieoei.github.io/fiets-app/
- Start automatisch een rit zodra de gekoppelde fiets via Bluetooth verbindt (Chrome op Android).
- Thema kleurt mee met de ondersteuningsstand (wit = uit, groen, blauw, oranje, paars = turbo).
- Totaal-scherm met statistieken over alle ritten.
- Stopt de rit na 5 minuten stilstand; die stilstand telt niet mee.
- Gegevens staan alleen lokaal op het apparaat (back-up via export/import).

Bij een update: verhoog de cachenaam in `sw.js` (`fiets-app-v1` → `v2`).
