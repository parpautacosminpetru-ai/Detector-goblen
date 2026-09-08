# Detector Goblen — Petit Point Vision

Aplicație Android offline pentru ghidaj vizual la executarea goblenurilor Petit Point.

## Versiunea 0.2

- cameră live CameraX;
- încărcare locală a diagramei;
- grilă configurabilă pe rânduri și coloane;
- selectarea vizuală a unui simbol și găsirea offline a tuturor căsuțelor similare;
- calibrare în 4 colțuri: stânga-sus → dreapta-sus → dreapta-jos → stânga-jos;
- simbolurile selectate sunt suprapuse peste pânza reală cu transformare de perspectivă;
- zoom cu două degete;
- **AUTO tracking offline**: după calibrare, aplicația memorează textura din jurul celor patru repere și urmărește mici deplasări ale pânzei/gherhefului în fluxul camerei;
- la zoom, punctele sunt scalate instantaneu, apoi trackerul live rafinează alinierea;
- **detector experimental de progres**: după calibrare, aplicația memorează aspectul pozițiilor țintă; o schimbare locală care rămâne stabilă mai multe cadre poate fi marcată ca executată, iar acel simbol dispare din ghidaj;
- protecție de bază contra falselor detecții produse de mână, umbre sau schimbări mari simultane în cadru;
- buton `Reînvață progres` pentru refacerea referinței vizuale fără a pierde pozițiile deja marcate;
- funcționare locală, fără server și fără trimiterea imaginilor în cloud.

## Folosire

1. Pune telefonul deasupra gherghefului, cât mai stabil.
2. Încarcă fotografia diagramei și introdu numărul corect de rânduri/coloane.
3. Definește regiunea din diagramă pe care o vede camera.
4. Alege o căsuță cu simbolul/codul pe care îl lucrezi.
5. Apasă `Calibrează` și atinge cele patru colțuri ale regiunii pe pânza văzută prin cameră.
6. După calibrare, ține mâna în afara cadrului o clipă pentru ca detectorul de progres să învețe referința.
7. Lucrează normal. `AUTO` încearcă să mențină suprapunerea, iar pozițiile detectate ca executate dispar.

## Precizie Petit Point

Pentru Petit Point, iluminarea bună, camera fixată și un zoom suficient de mare sunt importante. Trackerul actual este un tracker local de textură, optimizat pentru mici deplasări; nu este încă o localizare globală completă a întregului goblen dacă telefonul este mutat mult sau scos din cadru.

Detectorul de progres este experimental. Un fir foarte apropiat ca luminanță de pânză sau o zonă cu reflexii poate necesita `Reînvață progres` ori recalibrare. Pragurile vor fi rafinate pe fotografii reale de Petit Point.

## Acul

O singură cameră RGB aflată deasupra pânzei nu poate vedea literalmente un ac complet ascuns pe spatele materialului. Etapa următoare poate detecta vârful acului când este vizibil, estima gaura țintă și afișa săgeți de corecție înainte de intrare/ieșire.

## Tehnologii

- Kotlin
- Android SDK
- CameraX Preview + ImageAnalysis
- procesare locală a canalului de luminanță pentru tracking și progres
- transformare de perspectivă cu `android.graphics.Matrix`

## Build

Proiectul folosește Java 17, compileSdk 35 și are GitHub Actions configurat să producă automat APK-ul debug la fiecare push pe `main`.
