# Pong data maturity scan versie 2 — lokaal ontwikkelen

Voor het starten van de complete applicatie met Docker, configuratie en operations: zie de
[README in de root](../README.md).

## Onderdelen

- `backend/`: Quarkus REST API met Hibernate ORM/Panache, Flyway en PostgreSQL.
- `frontend/`: Angular-webapplicatie.

## Vereisten

- JDK 25 of nieuwer en Maven 3.9 of nieuwer
- Node.js 22.22+, 24.15+ of 26+ met npm
- PostgreSQL 17 (alleen voor `mvn quarkus:dev`; de tests gebruiken een in-memory H2-database)

## Database voorbereiden

Het ontwikkelprofiel verbindt standaard met `jdbc:postgresql://localhost:5432/app` als gebruiker `app` /
wachtwoord `app` (te overschrijven met `APP_DB_URL`, `APP_DB_USERNAME`, `APP_DB_PASSWORD`). Bijvoorbeeld:

```bash
docker run --rm -d --name pong-dev-db -p 5432:5432 -e POSTGRES_USER=app -e POSTGRES_PASSWORD=app -e POSTGRES_DB=app postgres:17-alpine
```

De migraties vormen een verse baseline vanaf V1. Een database van vóór de MVP-herziening moet opnieuw worden aangemaakt; er is geen upgradepad.

Flyway voert bij het starten het schema (`db/migration`) en in dev en test ook de demodata (`db/demo`) uit.
Demo-accounts: `demo@pong.example` (gebruiker) en `admin@pong.example` (beheerder), beide met wachtwoord `password`.

## Backend starten

```bash
cd backend
mvn quarkus:dev
```

In het productieprofiel staan OpenAPI en Swagger UI uit. `/q/*` (inclusief health en metrics) is met Docker alleen binnen het Docker-netwerk bereikbaar; nginx proxyt het niet.

De API draait op `http://localhost:8080`; de OpenAPI-beschrijving staat op `http://localhost:8080/q/openapi`.

## Frontend starten

```bash
cd frontend
npm ci
npm start
```

De webapplicatie draait op `http://localhost:4200` en proxyt `/v1` naar `http://localhost:8080`
(`proxy.conf.json`). Het toegangstoken staat alleen in het geheugen: herladen van de pagina betekent opnieuw
aanmelden; je komt daarna terug op de pagina waar je was.

## Controleren

```bash
cd backend
mvn test

cd ../frontend
npm test -- --no-watch
npm run lint
npm run build
```
