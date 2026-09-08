# Detector Goblen — Petit Point Vision

Aplicație Android offline pentru ghidaj vizual la executarea goblenurilor Petit Point.

## Ce face MVP-ul

- deschide camera telefonului în timp real;
- încarcă o imagine a diagramei de goblen;
- lucrează cu o grilă configurabilă (rânduri și coloane);
- alegi o singură căsuță din diagramă, iar aplicația găsește offline toate căsuțele cu același simbol;
- calibrezi 4 puncte pe pânza reală, în ordinea stânga-sus → dreapta-sus → dreapta-jos → stânga-jos;
- suprapune în timp real simbolul selectat peste pozițiile corespunzătoare de pe pânză;
- permite definirea unei regiuni locale din diagramă, utilă când camera vede doar o porțiune a goblenului;
- pinch-to-zoom pe camera Android; dacă schimbi zoom-ul după calibrare, aplicația cere recalibrare pentru a evita deplasarea suprapunerii;
- funcționează fără server și fără trimiterea imaginilor în cloud.

## Important pentru primul prototip

Imaginea diagramei trebuie să fie decupată cât mai aproape de grila propriu-zisă. Numărul de rânduri și coloane este introdus de utilizator. Identificarea simbolurilor se face prin compararea vizuală locală a celulelor, nu prin OCR clasic.

Telefonul trebuie să stea cât mai fix după calibrare. Urmărirea automată a pânzei în timp ce telefonul se mișcă va fi adăugată în etapa următoare.

## Limitarea fizică a acului

O singură cameră RGB aflată deasupra pânzei nu poate vedea literalmente un ac complet ascuns pe spatele materialului. Putem însă adăuga detectarea vârfului când devine vizibil, estimarea găurii țintă și săgeți de corecție înainte de intrare/ieșire.

## Tehnologii

- Kotlin
- Android SDK
- CameraX
- procesare locală de bitmap pentru gruparea simbolurilor
- transformare de perspectivă cu `android.graphics.Matrix`

## Build

Proiectul este configurat pentru Java 17, Android Gradle Plugin 8.7.x și compileSdk 35.

În Android Studio: deschide repository-ul și rulează modulul `app` pe un telefon Android real.
