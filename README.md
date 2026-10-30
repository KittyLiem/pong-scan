# Pong data maturity scan versie 2

CI: [GitHub Actions](.github/workflows/ci.yml) controleert backend (inclusief PostgreSQL via Testcontainers), frontend, startscript en Compose bij pull requests naar en pushes op `mvp`, `fix/**`, `ci/**` en `redteam/**`.

Webapplicatie waarmee organisaties hun datavolwassenheid gestructureerd beoordelen over zeven domeinen
(datastrategie, datakwaliteit, governance, technologie, processen, vaardigheden en datagedreven besluitvorming)
en de uitkomsten vertalen naar ontwikkelpunten en prioriteiten.

- `app/backend` — Quarkus REST API (Java 25, Hibernate/Panache, Flyway, PostgreSQL)
- `app/frontend` — Angular-webapplicatie
- `docs/mvp-plan.md` — MVP-werkplan, ontwerpbesluiten en API-contract
- `locks/contracts/openapi.yaml` — vastgelegd API-contract

## Snel starten met Docker

Vereist: Docker met Docker Compose v2. Het startscript maakt alleen als `.env` ontbreekt een bestand met willekeurige geheimen (openssl of `/dev/urandom`) en lokale demo-instellingen aan. Een bestaande `.env` blijft ongewijzigd.

```bash
./start-lokaal.sh
```

Open daarna <http://localhost:8080>. De eerste build duurt enkele minuten.

De lokale compose-omgeving laadt demodata:

| Account | Wachtwoord | Rol |
|---|---|---|
| `demo@pong.example` | `password` | gebruiker (met demo-organisatie en voorbeeldscans) |
| `admin@pong.example` | `password` | beheerder (toegang tot **Beheer**) |

Je kunt ook zelf een account registreren.

Stoppen met `docker compose down`. Met `docker compose down -v` verwijder je ook de database.

### Services

| Service | Rol | Poort |
|---|---|---|
| `frontend` | nginx: serveert de Angular-build, proxyt `/v1` naar de backend, zet security-headers | `8080` (host, instelbaar via `APP_PORT`) |
| `backend` | Quarkus in productieprofiel; voert databasemigraties uit bij het starten | intern `8080` |
| `db` | PostgreSQL 17 met volume `db-data` | intern `5432` |

De backend wordt pas als gezond gemeld wanneer `/q/health/ready` (inclusief databaseverbinding) `UP` is; de
frontend start daarna.

### Configuratie

Compose vereist expliciete geheimen via `.env`; zonder die waarden start de omgeving niet. De standaardinstellingen zijn `APP_ALLOW_INSECURE_LOCAL_DEFAULTS=false` en `APP_FLYWAY_LOCATIONS=db/migration`. Het startscript kiest bij een nieuwe `.env` voor lokale modus met demo-accounts. Gebruik die modus nooit voor een gedeelde omgeving. Voor iedere gedeelde of pilotomgeving:

```bash
cp .env.example .env
```

en vul eigen willekeurige waarden in (databasewachtwoord minimaal 12 tekens, tokengeheim minimaal 32 tekens). Start daarna met `docker compose up --build -d`. `.env.example` zet de lokale modus uit; de backend weigert dan te starten zolang er nog
standaard- of voorbeeldwaarden in staan of demodata is ingeschakeld.

| Variabele | Betekenis |
|---|---|
| `APP_ALLOW_INSECURE_LOCAL_DEFAULTS` | `true` alleen voor lokaal gebruik; `false` voor iedere gedeelde omgeving |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Database en credentials |
| `ACCOUNT_LOGIN_MAX_FAILURES` | Maximaal mislukte aanmeldpogingen per e-mailadres per venster (standaard `5`; `account.login.max-failures`) |
| `ACCOUNT_LOGIN_WINDOW` | Aanmeldvenster als ISO-8601-duur (standaard `PT15M`; `account.login.window`); een geslaagde aanmelding wist de teller. Tellers voor bestaande accounts staan in de database (`account_members.failed_login_count`) en blijven bij herstart behouden. Alleen onbekende e-mailadressen gebruiken een tabel in het geheugen. |
| `SCAN_QUOTA_MAX_PER_ORGANIZATION` | Maximaal aantal scans inclusief revisies per organisatie (standaard `50`; `scan.quota.max-per-organization`) |
| `SCAN_QUOTA_MAX_PER_DAY` | Maximaal aantal nieuwe scans inclusief revisies per organisatie in de afgelopen 24 uur (standaard `20`; `scan.quota.max-per-day`) |
| `ACCOUNT_TOKEN_SECRET` | Signeersleutel voor toegangstokens, minimaal 32 tekens (`openssl rand -base64 48`) |
| `ACCOUNT_BOOTSTRAP_ADMIN_IDENTIFIER`, `ACCOUNT_BOOTSTRAP_ADMIN_PASSWORD` | Optioneel: maakt bij het starten een beheerdersaccount aan als dat e-mailadres nog niet bestaat (wachtwoord ≥ 12 tekens). Een bestaand gewoon account wordt nooit automatisch beheerder. |
| `APP_FLYWAY_LOCATIONS` | `db/migration` (zonder demodata) of `db/migration,db/demo` (met demodata, alleen in lokale modus) |
| `APP_PORT` | Poort op de host |

Beheerdersrechten worden nooit via registratie of aanmelden toegekend. Gebruik de bootstrap-variabelen voor het
eerste beheerdersaccount en haal het wachtwoord daarna weer uit `.env`.

Bekende beperking voor de pilot: registratie meldt het wanneer een e-mailadres al bestaat (account-enumeratie).
Voor een pilot met een bekende gebruikersgroep is dat geaccepteerd; voor bredere uitrol hoort er een
uitnodigings- of verificatieflow bij.

## Lokaal ontwikkelen

Vereisten: JDK 25+, Maven 3.9+, Node.js 22.22+/24.15+ met npm, en PostgreSQL (of alleen de database uit compose:
`docker compose up db` — publiceer daarvoor tijdelijk poort 5432).

Zie [`app/README.md`](app/README.md) voor het starten van backend (`mvn quarkus:dev`) en frontend (`npm start`,
met proxy van `/v1` naar `http://localhost:8080`) en het draaien van de tests.

## Operations

- Liveness: `/q/health/live`; readiness (incl. database): `/q/health/ready`; metrics (Prometheus): `/q/metrics`.
  `/q/*` is alleen bereikbaar binnen het Docker-netwerk: nginx proxyt deze paden niet. OpenAPI en Swagger UI staan uit in het productieprofiel; in dev/test blijven ze beschikbaar.
- Iedere response bevat `X-Correlation-Id`; logs zijn in productie gestructureerd (JSON) en bevatten het
  correlation-id. Tokens, wachtwoorden en Authorization-headers worden niet gelogd.
- Beveiligingsrelevante acties (aanmelden, beheerwijzigingen, scans afronden, revisies) worden vastgelegd in de
  append-only tabel `audit_events`.
- Databasemigraties (Flyway) draaien automatisch bij het starten van de backend. Dit is een verse baseline vanaf V1. Een bestaande database van vóór de MVP-herziening moet opnieuw worden aangemaakt; er is geen upgradepad. Maak zo nodig eerst een back-up.
- TLS hoort te termineren op een reverse proxy vóór de frontend-container; zet daar ook HSTS aan.
