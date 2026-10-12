# Rover GNSS – geodetická aplikace pro Android a protokol rover ↔ aplikace (12. 10. 2026)

Samostatný projekt. Rover (ZED-X20P + ESP32) měří a řídí časově kritické věci, aplikace v telefonu
zajišťuje ovládání, NTRIP z internetu, zpracování, ukládání a export. Navazuje na `rover-firmware-specifikace.md`.

## 1. Rozdělení práce

| Rover (ESP32) | Aplikace (Android) |
|---|---|
| Čtení X20: NAV-PVT, NAV-HPPOSLLH, NAV-SAT, NAV-COV, NAV-DOP; volitelně RXM-RAWX/SFRBX | Spojení BT s roverem, automatické obnovení |
| IMU, detekce pohybu, později EKF GNSS + IMU | Klient NTRIP (RTCM do roveru, GGA zpět do casteru) |
| Měření bodu (odpočet, ustálení, sběr, souhrn) – běží i bez telefonu | Řízení měření, zobrazení průběhu, stažení výsledků a vzorků |
| Uložené nastavení, profily a **úloha po startu** (LittleFS) | Nastavení roveru (profily, X20, úlohy po startu, kalibrace) |
| Výstup polohy 5 Hz + NMEA | Mapa, body, trasy, vytyčení, transformace, export, záznam pro PPK |
| BT Classic SPP; Wi-Fi jen servisní režim | Databáze v telefonu |

- Rover bez telefonu: po zapnutí provede uloženou úlohu (např. změř bod / zaznamenávej / čekej). RTK z internetu jen s telefonem.
- **Wi-Fi v roveru:** jen servisní režim (podržení tlačítka při zapnutí, BT vypnuto) – nouzové nastavení a diagnostika. Běžně vše přes aplikaci. Uvolní RAM (~64 kB) a program (~0,5 MB).

## 2. Technologie aplikace

- **Kotlin, Jetpack Compose** (UI), **MapLibre Native** (mapy – vektorové i rastrové vrstvy, offline balíčky), **Room** (SQLite), **Kotlin Coroutines**.
- **Služba na popředí** (foreground service): BT, NTRIP, záznam běží se zhasnutým displejem.
- Vlastní implementace: Křovák a transformace ČÚZK s mřížkou oprav, kvazigeoid ČR (mřížka), ITRF2020 → ETRS89 s epochou, klient NTRIP (HTTP/TCP, NTRIP 1/2), parser protokolu roveru.
- Mapové podklady: OSM (vektor), ČÚZK WMS/WMTS (ortofoto, ZABAGED, katastrální mapa), offline dlaždice pro terén.

## 3. Funkce a obrazovky

| Obrazovka | Obsah |
|---|---|
| **Připojení** | výběr roveru (spárovaná BT zařízení), stav spojení, profily NTRIP (VESOG, CZEPOS, vlastní báze; caster, port, mountpoint, jméno/heslo, odesílání GGA), stáří a přenos RTCM |
| **Živě / mapa** | poloha na mapě, fix (RTK fix / float / HAS / SBAS / 3D / DR), σ N/E/U, hAcc/vAcc, PDOP, počet SV, stáří korekcí, rychlost, kurz, pohyb/klid, baterie roveru; souřadnice ETRS89 / S-JTSK / Bpv |
| **Satelity** | skyplot, C/N0 podle konstelací a pásem, použité / sledované |
| **Body** | seznam bodů zakázky, import (CSV, geodetické údaje ČÚZK – ruční zadání, S-JTSK + Bpv), kvalita polohy (přesná / přibližná / nevyhodnocovat), poznámka, foto |
| **Měření bodu** | cíl (existující / nový bod / bez bodu), odpočet, ustálení (stabilita polohy, hAcc, limit), doba sběru, volitelně jen RTK fix; průběh živě; souhrn (průměr, σ, CEP, počty fix/float/HAS, Δ od bodu); opakovaná měření a opakovatelnost |
| **Trasy** | záznam 1–10 Hz s σ a kvalitou, za chůze; zobrazení, statistika, filtrace podle σ / kvality |
| **Vytyčení** | navigace k bodu: vzdálenost, směr, ΔN/ΔE/ΔU, zvuková signalizace přiblížení |
| **Export** | CSV (ETRS89 / S-JTSK / Bpv, volba sloupců), GPX, KML, GeoJSON, DXF; vzorky měření; sdílení souboru |
| **PPK** | záznam RAWX/SFRBX do souboru `.ubx` (RTKLIB), metadata (výška antény, čas, bod) |
| **Rover** | profily (frekvence, dynamický model, elevační maska, C/N0 maska, SBAS/HAS, výstupy NMEA), výška antény a PCO, úloha po startu, bzučák, kalibrace baterie a gyra, verze FW, restart X20 (horký/teplý/studený) |
| **Diagnostika** | statistika komunikace (UBX, RTCM, BT), PPS, IMU, rušení / AGC, stav antény, OSNMA, paměť roveru, konzole |

### Zakázky a data
- **Zakázka** (projekt) → body, měření, trasy, soubory PPK, poznámky.
- Bod: název, druh, ETRS89 φ/λ/h, S-JTSK Y/X, Bpv, kvalita polohy, zdroj (zadaný / změřený), poznámka, foto.
- Měření: bod, čas, nastavení, souhrn, vzorky (1 Hz), příznaky (RTCM, HAS, pohyb, vyřazení).
- Trasa: body s časem GNSS, polohou, σ, kvalitou, rychlostí, kurzem.

## 4. Souřadnice a výšky

- Poloha z roveru: s RTCM ze sítě v ČR = ETRS89; bez RTCM (HAS, SBAS) = ITRF2020 → přepočet do ETRS89 (rotace Eurasie s epochou; ČR 2026 ≈ N −0,57 m, E −0,74 m).
- **ETRS89 ↔ S-JTSK:** postup ČÚZK s opravnou mřížkou (cm úroveň); záloha 7 parametrů (~0,4 m, ověřeno na TB 10 Holý kopec: 0,35 m).
- **Výška:** elipsoidická h → normální Bpv přes kvazigeoid ČR (mřížka ČÚZK); h_msl z X20 (geoid u-blox) jen orientačně.
- Výška antény a PCO z profilu roveru; odečítá se při ukládání bodů.

## 5. Protokol rover ↔ aplikace (BT SPP)

Jeden obousměrný proud bajtů. Druhy dat se rozlišují podle prvního znaku:

| Směr | Začátek | Obsah |
|---|---|---|
| rover → app | `$G` | NMEA (GGA, RMC, GST, GSA, GSV) – kompatibilita s jinými aplikacemi |
| rover → app | `$PRVR,` | vlastní věty s kontrolním součtem (živá data) |
| rover → app | `{` | JSON na jednom řádku: odpovědi na příkazy, události, souhrny |
| rover → app | `0xB5 0x62` | rámce UBX (RAWX/SFRBX) při záznamu pro PPK |
| app → rover | `0xD3` | RTCM3 z NTRIP → X20 |
| app → rover | `{` | JSON příkaz na jednom řádku |

### Živá data `$PRVR` (protokol 1, FW 0.8.0)
Kontrolní součet jako NMEA (XOR bajtů UTF-8 mezi `$` a `*`). Prázdné pole = hodnota není k dispozici.

`$PRVR,POS,` – každá epocha (frekvence profilu, 5 Hz):

| # | pole | jednotka / význam |
|---|---|---|
| 1 | čas UTC | `hhmmss.sss` |
| 2 | datum | `ddmmyy` |
| 3, 4 | φ, λ | ° (9 des. míst, z NAV-HPPOSLLH) |
| 5, 6 | h_ell, h_msl | m |
| 7–9 | σN, σE, σU | m (NAV-COV; prázdné, když neplatná) |
| 10, 11 | hAcc, vAcc | m |
| 12 | kvalita | 0 žádná, 1 GNSS, 2 SBAS/DGNSS, 3 HAS (bez RTCM, ITRF2020), 4 RTK fix, 5 RTK float, 6 dopočet |
| 13 | carrSoln | 0 / 1 float / 2 fix (u HAS stav konvergence) |
| 14 | RTCM | 1 = z mobilu chodí RTCM (posledních 30 s) |
| 15 | stáří korekcí | s (horní mez) |
| 16 | SV | počet v řešení |
| 17 | PDOP | |
| 18, 19 | rychlost, kurz | m/s, ° |
| 20 | pohyb | 1 = IMU hlásí pohyb (15 °/s nebo 0,2 g, drží 1,5 s) |
| 21 | hp | 1 = poloha z NAV-HPPOSLLH |

`$PRVR,STA,` – 1 Hz: FW, baterie V, baterie %, aktivní profil (od 0), počet profilů, název profilu, RTCM přijato, RTCM chybné, stáří RTCM [s], typ poslední RTCM, chyby UBX, volná RAM [B], fáze měření bodu (0 nic, 1 odpočet, 2 ustálení, 3 sběr, 4 hotovo, 5 selhalo), čas ve fázi [s], vzorků, zahozené dávky BT.

`$PRVR,SAT,` – 1 Hz, po 12 družicích: část, počet částí, celkem sledovaných, pak opakovaně `gnssId,svId,C/N0,elevace,azimut,použit` (jen C/N0 > 0).

Příkazy z mobilu: řádek JSON začínající `{` a ukončený `\n`. Rover ho rozpozná jen mimo rámec RTCM – aplikace proto posílá RTCM po celých rámcích. Etapa 1 (FW 0.8.0): `get info`, `get profiles`, `profile`, `x20 reset`.

### Příkazy (JSON, `id` pro spárování odpovědi)
- `{"id":1,"cmd":"get","what":"info|profiles|device|points|meas|task"}`
- `{"id":2,"cmd":"set","what":"profiles|device|task","data":{…}}`
- `{"id":3,"cmd":"profile","i":2}` – aktivní profil
- `{"id":4,"cmd":"meas","op":"start","cfg":{…}}` / `"op":"finish|cancel"`
- `{"id":5,"cmd":"raw","meas":12,"from":0,"n":200}` – vzorky měření po dávkách
- `{"id":6,"cmd":"log","what":"rawx","on":true}` – UBX RAWX/SFRBX do proudu
- `{"id":7,"cmd":"x20","op":"reset","kind":"hot|warm|cold"}`, `"op":"cfg"` (vybrané klíče)
- `{"id":8,"cmd":"cal","what":"battery|gyro",…}`
- Odpověď: `{"id":N,"ok":true,…}` / `{"id":N,"ok":false,"err":"…"}`; události bez `id`: `{"ev":"meas_done",…}`, `{"ev":"moved",…}`.

### Úloha po startu (uložená v roveru)
- `none` – jen výstup polohy, čeká na příkazy.
- `meas` – změř bod (bod, ustálení, doba, opakování); výsledek uloží, bzučák signalizuje.
- `track` – záznam (v roveru jen souhrn / značky; vzorky do telefonu, když je připojen).
- Platí po každém zapnutí, dokud ji aplikace nezmění.

## 6. Úpravy roveru (ESP)

1. Protokol `$PRVR` + JSON příkazy přes SPP (místo webového API); NMEA zůstává.
2. Wi-Fi a web jen jako servisní režim (podržení tlačítka při zapnutí, bez BT).
3. Úloha po startu, ukládání v LittleFS.
4. Předávání RAWX/SFRBX do SPP na povel.
5. EKF GNSS + IMU, rozptyl v klidu (modul `motion`) – podle plánu ve specifikaci firmwaru.

## 7. Etapy

1. **Základ:** protokol v roveru, spojení BT, NTRIP, živé zobrazení + mapa, záznam trasy, export CSV/GPX.
2. **Body a měření:** zakázky, body, řízení měření bodu, stažení vzorků, souhrny a porovnání, ETRS89 ↔ S-JTSK, kvazigeoid.
3. **Vytyčení, PPK, nastavení roveru:** vytyčení, záznam RAWX, profily a úloha po startu, diagnostika.
4. **Rover:** EKF, rozptyl v klidu, servisní režim Wi-Fi.

## 8. Otevřené otázky

- Zdroj a formát mřížek ČÚZK (transformace S-JTSK, kvazigeoid) – ověřit aktuální soubory a licenci.
- NTRIP: dostupné castery (VESOG, CZEPOS) a vzdálenost referenčních stanic od místa měření (fix spolehlivě do ~20–30 km).
- Mock location pro ostatní aplikace v telefonu – volitelně.
