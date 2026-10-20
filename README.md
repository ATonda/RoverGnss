# Rover GNSS – aplikace pro Android

Geodetická aplikace k roveru ZED-X20P + ESP32: spojení Bluetooth SPP, korekce NTRIP z internetu, živá poloha na mapě, záznam a export tras.

Specifikace: [docs/rover-app-specifikace.md](docs/rover-app-specifikace.md). Rover potřebuje firmware **0.8.0+** (protokol `$PRVR` a příkazy JSON).

## Etapa 1 (tato verze 0.1.0)
- **Připojení:** výběr spárovaného roveru, automatické obnovení spojení, údaje o roveru, přepnutí profilu, restart X20.
- **NTRIP:** profily casterů, seznam mountpointů, NTRIP 1.0/2.0, posílání GGA, RTCM do roveru po celých rámcích.
- **Živě:** mapa (OpenFreeMap, ortofoto a ZTM ČÚZK), poloha barevně podle kvality, stopa, σ / hAcc, ETRS89 (HAS přepočten z ITRF2020), S-JTSK (7 parametrů, ~0,4 m).
- **Satelity:** skyplot a C/N0.
- **Trasy:** záznam do databáze (i se zhasnutým displejem), export CSV a GPX s filtrem.

## Sestavení
Android Studio, JDK 17, `minSdk 26`, `compileSdk 35`. Otevřít složku projektu, Gradle stáhne závislosti (AGP 8.7, Kotlin 2.1, Compose, Room, MapLibre 11).

## Struktura
```
app/src/main/java/cz/antonin/rovergnss/
  RoverApp.kt, MainActivity.kt
  service/  RoverController (spojení, NTRIP, záznam), RoverService (služba na popředí)
  link/     BtLink (SPP), StreamParser, RoverProtocol ($PRVR)
  ntrip/    NtripClient, RtcmFramer
  geo/      Etrs (ITRF2020 → ETRS89), Krovak (S-JTSK)
  data/     Room (zakázky, trasy, body tras), Prefs
  export/   CSV, GPX
  ui/       obrazovky Compose, mapa
```

## Licence

GPL-3.0 – viz [LICENSE](LICENSE).

Mřížky v `app/src/main/assets` (`CR-2005_v1005.dat` – kvazigeoid ČR, `table_yx_3_v1710.dat` – opravná tabulka S-JTSK) © ČÚZK, převzato z dat ČÚZK.
