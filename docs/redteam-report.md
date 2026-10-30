# Red-team-rapport — Pong data maturity scan (branch `mvp`)

Datum: 20-10-2026 · Scope: commit `e638522` (branch `mvp`, PR #2) · Opdrachtgever: eigenaar van de applicatie

## Aanpak

1. **White-box**: broncode-analyse van backend, frontend, nginx en compose door een onafhankelijke reviewer (Codex),
   vanuit vier aanvallersprofielen: geen account, gewone gebruiker in organisatie A, gebruiker die beheerder wil
   worden, en iemand met toegang tot logs of databaseback-ups.
2. **Black-box**: gerichte requests tegen de lokaal draaiende stack (`docker compose up`, http://localhost:8080)
   om de runtime-afhankelijke hypotheses te bevestigen of te weerleggen. Geen tests tegen andere systemen.

## Samenvatting

| ID | Ernst | Bevinding | Bevestigd |
|---|---|---|---|
| RT-01 | **Hoog** | Wachtwoord-afgeleide idempotency-fingerprints maken offline kraken met SHA-256 mogelijk (omzeilt bcrypt) | code |
| RT-02 | Middel | Geen rate limiting op aanmelden/registreren: online wachtwoord raden en CPU-belasting via bcrypt | runtime |
| RT-03 | Middel | `APP_FLYWAY_LOCATIONS=db` omzeilt het verbod op demodata in productie (demo-admin met bekend wachtwoord) | code |
| RT-04 | Middel | Geen quotum op scans: elke lege scan kopieert ~155 snapshotrijen (opslagversterking) | code |
| RT-05 | Laag | Wachtwoorden langer dan 72 bytes worden door bcrypt afgekapt; alles daarna telt niet mee | runtime |
| RT-06 | Info | `OPTIONS` op beschermde paden antwoordt zonder token met 200 en een `Allow`-header (geen data) | runtime |

Geen enkele bevinding geeft een anonieme of gewone gebruiker toegang tot gegevens van een andere organisatie of
tot beheerfuncties in een correct geconfigureerde omgeving. RT-01 vereist toegang tot de database of een back-up;
RT-03 vereist een foutieve deploymentconfiguratie.

## Bevindingen

### RT-01 — Hoog — Wachtwoord-afgeleide fingerprints omzeilen bcrypt

**Status:** Verholpen: fingerprints bevatten geen wachtwoord meer; sessiereplays verifiëren opnieuw met bcrypt. Registratiereplays blijven gekoppeld aan sleutel en e-mailadres. Er is nog geen uitgerolde database met oude fingerprints.

- **Locatie**: `app/backend/src/main/java/com/example/account/application/AccountService.java` (registratie- en
  sessie-fingerprints), `app/backend/src/main/java/com/example/common/Hashing.java`.
- **Aanvaller**: iemand met leestoegang tot de database of een back-up.
- **Probleem**: voor idempotency wordt naast de bcrypt-hash een fingerprint opgeslagen van de vorm
  `SHA256(e-mail ‖ SHA256(wachtwoord) ‖ …)` in `account_members.registration_fingerprint` en
  `account_sessions.request_fingerprint`. Alle overige invoer staat in dezelfde tabellen, dus een wachtwoordgok
  kost twee snelle SHA-256-berekeningen in plaats van een bcrypt-verificatie.
- **Impact**: praktische offline woordenboekaanvallen op alle accounts, inclusief beheerders, zodra een back-up
  uitlekt. Uitloggen of verlopen van sessies verwijdert de fingerprints niet.
- **Aanbeveling**: geen ongesleutelde wachtwoord-afgeleide waarden opslaan. Gebruik voor fingerprints een HMAC met
  een servergeheim dat niet in de database staat (bijv. afgeleid van `ACCOUNT_TOKEN_SECRET`), of laat het wachtwoord
  helemaal uit de fingerprint en verifieer bij een replay opnieuw via bcrypt. Ruim bestaande fingerprints op via een
  migratie.

### RT-02 — Middel — Geen rate limiting op authenticatie

**Status:** Verholpen: nginx begrenst /v1/auth/ per IP (10/min, burst 5); de backend begrenst mislukte pogingen per bekend of onbekend e-mailadres (standaard 5 per 15 minuten, 429 met Retry-After) en maximaal 8 gelijktijdige bcrypt-verificaties (anders 503). Succes wist de teller; backendtellers zijn proceslokaal.

- **Locatie**: `AccountResource` (`POST /v1/auth/sessions`, `/registrations`), `app/frontend/nginx.conf`.
- **Aanvaller**: zonder account.
- **Runtime-bevestiging**: 15 foute aanmeldpogingen achter elkaar op `demo@pong.example` gaven 15× `401`, nooit
  `429` of vertraging.
- **Impact**: online wachtwoord raden (ook op beheerdersaccounts) en CPU-belasting, omdat elke poging een
  bcrypt-verificatie kost, ook voor onbekende accounts. Elke mislukte poging schrijft bovendien een auditregel.
- **Aanbeveling**: `limit_req` in nginx op `/v1/auth/` (bijv. 5 r/min per IP met burst), plus in de applicatie een
  oplopende vertraging per account. Begrens het aantal gelijktijdige bcrypt-verificaties.

### RT-03 — Middel — Ouderlocatie in Flyway omzeilt verbod op demodata

**Status:** Verholpen: productie accepteert uitsluitend exact db/migration of classpath:db/migration. De customizer controleert genormaliseerde scriptpaden en staat db/demo uitsluitend toe bij expliciete vermelding én lokale modus.

- **Locatie**: `app/backend/src/main/java/com/example/security/ProductionConfigValidator.java`,
  `app/backend/src/main/java/com/example/common/FlywayLocationCustomizer.java`.
- **Aanvaller**: zonder account, maar alleen als de beheerder `APP_FLYWAY_LOCATIONS=db` (of `classpath:db`) zet.
- **Probleem**: de validator weigert alleen exact `db/demo`; de customizer laat elke migratie toe waarvan het pad
  begint met een toegestane locatie. `db` laat dus `db/demo/V1000__demo_data.sql` toe, inclusief
  `admin@pong.example` met wachtwoord `password`.
- **Aanbeveling**: in productie uitsluitend `db/migration` accepteren (allowlist) en migraties op hun opgeloste pad
  controleren; weiger elk script onder `db/demo` als lokale modus uit staat.

### RT-04 — Middel — Opslagversterking via onbeperkt scans aanmaken

**Status:** Verholpen: organisatiequota van standaard 50 scans totaal en 20 nieuwe scans per voortschrijdende 24 uur, inclusief revisies; 429 bij overschrijding. Een organisatielock voorkomt gelijktijdige overschrijding; idempotente replays blijven mogelijk.

- **Locatie**: `app/backend/src/main/java/com/example/scan/application/ScanService.java`,
  `app/backend/src/main/java/com/example/content/application/ActiveContentService.java`.
- **Aanvaller**: gewone gebruiker (kan zichzelf registreren en een organisatie aanmaken).
- **Probleem**: elke nieuwe scan kopieert het volledige actieve model (1 scan + 7 domeinen + 21 vragen + 126 opties
  ≈ 155 rijen), ook zonder geselecteerde domeinen. Er is geen quotum of limiet per tijdseenheid.
- **Impact**: databasegroei en schrijfbelasting vanuit kleine requests; grotere back-ups.
- **Aanbeveling**: quotum per organisatie (bijv. max. scans per dag en totaal) en rate limiting op aanmaak-endpoints.
  Op termijn: gedeelde, versie-gebonden modelsnapshots in plaats van een kopie per scan.

### RT-05 — Laag — bcrypt kapt wachtwoorden af op 72 bytes

**Status:** Verholpen: registratie weigert meer dan 72 UTF-8-bytes met een Nederlandse veldfout; aanmelden weigert zulke credentials met 401. De frontend hanteert maximaal 72 tekens met dezelfde melding.

- **Locatie**: `AccountDtos` (credential tot 128 tekens), `AccountService` (`BcryptUtil`).
- **Runtime-bevestiging**: een account geregistreerd met 72× `A` + `eerste` kon aanmelden met 72× `A` + `tweede`
  (`201`).
- **Impact**: bij lange wachtwoorden (bijv. passphrases met een gedeeld voorvoegsel) draagt alles na byte 72 niet bij
  aan de sterkte. Beperkt risico, maar onverwacht voor gebruikers.
- **Aanbeveling**: maximaal 72 UTF-8-bytes afdwingen met een duidelijke foutmelding, of het wachtwoord eerst
  pre-hashen (HMAC-SHA-256, base64) vóór bcrypt.

### RT-06 — Info — `OPTIONS` zonder authenticatie

**Status:** Ongewijzigd zoals afgesproken; OPTIONS retourneert geen beschermde gegevens.

- `OPTIONS /v1/organizations` zonder token geeft `200` met alleen `Allow: HEAD, POST, GET, OPTIONS`, zonder body.
  Geen datalek; wel lichte informatie over beschikbare methoden. Optioneel: CORS-preflight alleen beantwoorden waar
  CORS aan staat.

## Hertest na fixes (20-10-2026, lokale stack)

| ID | Hertest | Resultaat |
|---|---|---|
| RT-01 | Oude formule `SHA256(e-mail ‖ SHA256(wachtwoord) ‖ …)` herberekend op `account_sessions.request_fingerprint` na aanmelden | komt niet overeen ✓ |
| RT-02 | Herhaalde foute aanmeldingen via nginx en rechtstreeks op de backend | nginx: 429 na de burst; backend: 5× 401, daarna 429 met `Retry-After: 900` en Nederlandse melding ✓ |
| RT-03 | Productie gestart met `APP_FLYWAY_LOCATIONS=db` en verder geldige secrets | startup geweigerd met Nederlandse melding ✓ |
| RT-04 | Integratietests `ScanQuotaTest` (totaal, per 24 uur, revisies, gelijktijdig) | groen ✓ |
| RT-05 | Registratie met 73-byte wachtwoord | 400 met veldfout "Een wachtwoord mag maximaal 72 bytes zijn." ✓ |

Backend 88 tests, frontend 68 tests, lint en build groen.

### Restrisico's (bewust geaccepteerd voor de pilot)

- **Gerichte lockout:** iemand kan een bekend e-mailadres 15 minuten blokkeren met 5 foute pogingen. De nginx-limiet
  per IP dempt dit; bij misbruik de drempel of het venster aanpassen (`ACCOUNT_LOGIN_MAX_FAILURES`,
  `ACCOUNT_LOGIN_WINDOW`).
- **Aanmeldteller:** voor bestaande accounts staat de teller van mislukte pogingen in de database
  (`account_members.failed_login_count`/`failed_login_window_ends`); een aanvaller kan die niet wegdrukken met
  pogingen op andere adressen, en hij overleeft een herstart. Alleen onbekende e-mailadressen gebruiken een begrensde
  tabel in het geheugen (oudste item verdwijnt bij een volle tabel); daar valt niets te raden. Iedere poging wordt
  vóór de wachtwoordcontrole atomair gereserveerd (lock + ophogen in één transactie) en bij succes teruggezet, zodat
  gelijktijdige pogingen de limiet niet kunnen omzeilen; de teller wordt bijgewerkt zonder versieophoging, zodat een
  correcte aanmelding nooit in een versieconflict loopt. Ontstaan na vier rondes automatische security-review op
  eerdere varianten (globale 503, fail-open, wegdrukbare teller, race tussen controle en registratie).
- **Lockoutgedrag bekend vs. onbekend adres:** de teller van onbekende adressen staat in het geheugen en die van
  bestaande accounts in de database; onder zware belasting of na een herstart kan dat verschil een bestaand adres
  verraden. Geaccepteerd: registratie verraadt al openlijk of een adres bestaat (zie boven), dus dit voegt geen nieuw
  lek toe.
- **Frontend telt tekens:** de wachtwoordcontrole in de frontend telt tekens, geen bytes; de backend blijft leidend
  voor de 72-bytegrens.

## Getest en niet exploiteerbaar

Runtime (tegen de lokale stack):
- Zonder token op `/v1/organizations` en `/v1/admin/domains`: `401` voor GET, HEAD, met trailing slash, matrix-parameter
  (`;x=1`), percent-encoding (`%6f`), dubbele slash en `/v1/%2e%2e/v1/...`.
- Path traversal naar Quarkus-beheerendpoints via nginx (`/v1/../q/health`, `/v1/%2e%2e/q/metrics`,
  `/v1/..%2fq/health`, `/q/metrics`): levert alleen de SPA-indexpagina, nooit health- of metricsdata.
- `/v1/q/openapi`: `404`.

Code-review (Codex), geen exploit gevonden:
- **IDOR tussen organisaties:** geen toegang tot scans, antwoorden, afronden, revisies, resultaten, export of prioriteiten. Alles loopt via `ScanAccess`.
- **Vreemde id's:** vragen of opties uit de snapshot van een andere scan worden geweigerd, net als vreemde domein-id's in de selectie en vreemde ontwikkelpunt-id's.
- **Rechtenescalatie naar beheerder:** niet mogelijk. Registratie geeft altijd `member`, rollen komen uit de database en `@AdminOnly` staat op klasseniveau.
- **Tokens:**
  - `alg: none`, algoritmeverwarring en het strippen van de handtekening worden geweigerd.
  - Padding-varianten, dubbele JSON-sleutels, een gewijzigde `aud` en een extreme `exp` werken niet, mede doordat de token-hash in de sessietabel moet staan.
  - Hergebruik van een token na uitloggen werkt niet.
- **Idempotency-replay tussen gebruikers:** voorkomen door de scoping per eigenaar en de fingerprints.
- **Manipulatie van versie en afronden:** geblokkeerd door de scan-lock en de controles op versie en volledigheid. Revisies van revisies blijven netjes binnen dezelfde eigenaar.
- **Injectie:**
  - Geen HQL/JPQL-injectie; queries zijn geparametriseerd.
  - Geen PDF-operatorinjectie; haakjes, backslashes en control bytes worden geëscaped.
  - Geen header-injectie via de bestandsnaam: daarin staat alleen de UUID.
  - Geen log-injectie via het correlation-id: alleen `[A-Za-z0-9-]{1,64}` wordt geaccepteerd.
- **Frontend:**
  - Geen `innerHTML`- of trust-bypass-sinks gevonden.
  - Het token staat alleen in het geheugen en de bearer gaat alleen mee naar de eigen origin.
  - `desiredRoute`/`returnUrl` geven geen open redirect.
- **Infrastructuur:** PostgreSQL en de backend worden niet gepubliceerd in compose, en de runtime-images draaien als non-root.

Niet onderzocht (buiten scope of vraagt een andere testopzet): request smuggling tussen nginx en Quarkus,
timing-metingen voor PDF-generatie onder belasting, TLS-configuratie (hoort op de reverse proxy).

## Aanbevolen volgorde

1. RT-01 (hoog): fingerprints zonder wachtwoord of met HMAC + opruimmigratie.
2. RT-02 en RT-04: rate limiting in nginx en applicatie, quotum op scans.
3. RT-03: allowlist voor Flyway-locaties in productie.
4. RT-05: limiet van 72 bytes of pre-hash.
