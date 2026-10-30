# MVP-werkplan — Pong data maturity scan versie 2

Bron: *Quality Report · Pong data maturity scan versie 2* (build `e6c512ea`, 13-10-2026), oordeel **Not ready**:
4 security-blockers, 15 major findings, completeness 60%. Dit plan beschrijft hoe de branch `mvp` de app naar
de lat "MVP voor een beperkte pilot" brengt. De werkpakketten (WP-01 … WP-14) volgen het rapport.

## Aanvullende bevindingen bij code-inspectie

Naast het rapport zijn deze functionele gaten gevonden:

1. **Twee losse inhoudsmodellen.** Scans en antwoorden verwijzen naar `maturity_model_*` (domeinen zónder naam,
   vragen zónder opties), terwijl namen, uitleg, antwoordopties en scores in `domains`/`admin_*` staan. Er is geen
   koppeling; `optionId` wordt nergens tegen de vraag gevalideerd en resultaten combineren beide toevallig.
2. **Terugkerende gebruiker vindt zijn organisatie niet terug.** Er is geen `GET` voor organisaties; de frontend
   kent de organisatie alleen in de sessie waarin hij is aangemaakt.
3. **De beoordeling toont maar één vraag** (de eerste van het eerste geselecteerde domein).
4. **Demo-account met wachtwoord `password` wordt in elke omgeving (ook productie) gemigreerd.**

## Ontwerpbesluiten

| # | Besluit | Rapport |
|---|---------|---------|
| D1 | **Eén beheerd inhoudsmodel** (`domains`, `questions`, `answer_options`) is de bron. Bij het aanmaken van een scan wordt alle *actieve* inhoud gekopieerd naar **immutable snapshottabellen** (`scan_snapshot_domains/questions/options`). Selectie, antwoorden, voortgang, resultaten en export gebruiken uitsluitend de snapshot, met echte foreign keys. Beheerwijzigingen raken bestaande scans nooit. | WP-03, WP-04, WP-05, DAT-01 |
| D2 | **Afgeronde scans zijn onveranderlijk.** "Heropenen" maakt een nieuwe revisie-scan (`revision_of`, `revision`) via `POST /v1/scans/{id}/revisions`; het origineel blijft `completed` met dezelfde resultaten. | DAT-04, WP-03 |
| D3 | **Ontwikkelprioriteiten worden geversioneerd** (`set_revision`); opslaan voegt een nieuwe set toe, de vorige blijft bewaard. | DAT-04 |
| D4 | **Deny-by-default authenticatie**: een globale JAX-RS filter beschermt iedere resource; alleen methoden met `@PermitAll` (registratie, sessie aanmaken) zijn publiek. **Rollen** (`member`/`admin`) staan in `account_members.role`; `@AdminOnly` dwingt 403 af. Beheerrechten worden nooit via registratie of aanmelden toegekend; het eerste beheerdersaccount wordt bij het starten aangemaakt via `ACCOUNT_BOOTSTRAP_ADMIN_IDENTIFIER`/`_PASSWORD` (of de demo-seed). | WP-01, SEC-01, SEC-02 |
| D5 | **Strikte tokens**: HS256 met `iss`, `aud`, `sub`, `sid`, `iat`, `exp`; header-`alg` wordt gecontroleerd. Productie start niet zonder sterk geheim en databasecredentials (geen fallbacks, bekende dev-waarden geweigerd). | WP-02, SEC-03, SEC-04 |
| D6 | **Migraties opnieuw opgezet** (er is nog geen productie-data): `V1__schema.sql` (volledig schema met FK's, CHECKs, indexen), `V2__reference_content.sql` (7 domeinen met vragen en opties 0–5). Demo-data staat apart in `db/demo` en wordt alleen in dev/test en de lokale docker-compose geladen. | DAT-01, SCA-03, demo-wachtwoord |
| D7 | **Frontend**: één `HttpInterceptor` voor bearer + 401-afhandeling, één ProblemDetails-normalisatie, aparte `/admin`-feature achter een admin-guard, correcte portfolio-navigatie, veldvalidatie in het Nederlands, `lang="nl"`. Token blijft in geheugen. | WP-07, WP-08, WP-10, WP-12 |
| D8 | **Lokaal draaien met `./start-lokaal.sh`**: PostgreSQL + Quarkus (prod-profiel) + nginx die de Angular-build serveert, `/v1` naar de backend proxyt en security-headers zet. | WP-09 |

## API-contract (MVP)

Alle endpoints onder `/v1`, JSON. Fouten: `application/problem+json`
`{ type, title, status, detail, instance, errors?: [{ field, message }] }` — titels/details in het Nederlands.
Iedere response bevat `X-Correlation-Id`. Mutaties die resources aanmaken vereisen `X-Idempotency-Key`.

### Publiek (`@PermitAll`)
| Methode | Pad | Body → Response |
|---|---|---|
| POST | `/auth/registrations` | `{accountIdentifier (e-mail, ≤320), credential (8–128)}` → 201 `Member{id, accountIdentifier, role}` |
| POST | `/auth/sessions` | `{accountIdentifier, credential, desiredRoute? (begint met "/", niet "//", ≤2048)}` → 201 `Session{accessToken, tokenType:"Bearer", expiresAt, desiredRoute, member: Member}` |

### Aangemeld (iedere rol)
| Methode | Pad | Body → Response |
|---|---|---|
| GET | `/me` | → `Member` |
| DELETE | `/auth/sessions/current` | → 204 (sessie server-side ingetrokken) |
| GET | `/organizations` | → `{items: Organization[]}` (alleen eigen organisaties) |
| POST | `/organizations` | `{name (1–255), chamberOfCommerceNumber? (^\d{8}$), industry? (≤255), additionalInformation? (≤4000)}` → 201 `Organization{id, name, chamberOfCommerceNumber, industry, additionalInformation, status, version}` |
| GET | `/models/active` | → `{domains: [{id, name, description, order, required, questionCount}]}`; 404 als geen actieve inhoud |
| GET | `/organizations/{orgId}/scans?cursor&pageSize(1–100, standaard 20)` | → `{items: Scan[], nextCursor}` gesorteerd op `updatedAt desc` |
| POST | `/organizations/{orgId}/scans` | `{name (1–120), assessmentPeriod, initialSelectedDomainIds?: uuid[]}` → 201 `Scan` (status `concept`, progress 0). `assessmentPeriod` matcht `^(\d{4}|Q[1-4] \d{4}|H[12] \d{4})$`. 422 "Beoordelingsmodel onvolledig" als een verplicht domein geen actieve vraag met beschikbare optie heeft; ook 422 zonder actieve inhoud. `initialSelectedDomainIds` verwijzen naar *beheerde* domein-id's en worden naar snapshot-id's vertaald. |
| GET | `/scans/{scanId}` | → `Scan` |
| PATCH | `/scans/{scanId}` | `{version, name?, assessmentPeriod?, selectedDomainIds? (≥1, snapshot-domein-id's), goals?: [{name (1–255), priority: high\|medium\|low, customText? (≤2000)}] (≤10)}` → `Scan`. 409 als de scan `completed` is. |
| GET | `/scans/{scanId}/content` | → `ScanContent` (snapshot; zie onder) |
| PUT | `/scans/{scanId}/answers/{questionId}` | `{optionId, explanation? (≤4000), evidenceReferences? (≤10 × ≤2048), version}` → `SavedAnswer{questionId, optionId, explanation, evidenceReferences, version, progress, scanStatus}`. `version` = 0 voor een nieuw antwoord, daarna de teruggegeven versie. 409 bij afgeronde scan of versieconflict; 422 als de vraag niet in een geselecteerd domein valt of de optie niet bij de vraag hoort. |
| POST | `/scans/{scanId}/completion` | `{version, generalComments? (≤4000)}` → `Scan` met status `completed`. 422 als niet iedere vraag van de geselecteerde domeinen een optie én toelichting heeft. |
| POST | `/scans/{scanId}/revisions` | `{mode: "copy" \| "new-revision"}` → 201 `Scan` (nieuwe scan; `copy` neemt antwoorden over, `new-revision` niet). Bron moet `completed` zijn. |
| GET | `/scans/{scanId}/results?prioritySetRevision` | → `ScanResults` (409 als niet afgerond) |
| PUT | `/scans/{scanId}/development-priorities` | `{items: [{developmentPointId, impact, urgency, selected}] (≤50)}` → `ActionOverview{setRevision, items}`. 422 voor een onbekend ontwikkelpunt. |
| GET | `/scans/{scanId}/export?prioritySetRevision` | → `application/pdf` (Nederlandstalig; zelfde gegevens als `ScanResults`) |

`Scan = {id, organizationId, name, assessmentPeriod, status: concept|in-progress|content-complete|completed,
progress (geheel 0–100), revision, revisionOf, selectedDomainIds, version, createdAt, updatedAt, completedAt,
generalComments, goals}`. Status: `concept` (geen selectie of geen antwoorden) → `in-progress` (≥1 antwoord) →
`content-complete` (alle geselecteerde vragen volledig) → `completed`.

`ScanContent = {scanId, status, editable, domains: [{id, name, description, order, selected, questionCount,
answeredCount, questions: [{id, title, text, explanation, order, options: [{id, label, description, score, order}],
answer: {optionId, explanation, evidenceReferences, version} | null}]}]}`.

`ScanResults = {scanId, scanName, assessmentPeriod, completedAt, totalScore (0–5, 2 decimalen),
maturityLevel: {level 0–5, label}, domainScores: [{domainId, domainName, score, maturityLevel}],
developmentPoints: [{id, domainId, domainName, classification: strength|development-point|gap, text,
recommendation}], goals, priorities: {setRevision, items}, availablePrioritySetRevisions: number[]}`. Niveaulabels: 0 Niet ingericht, 1 Ad hoc,
2 Herhaalbaar, 3 Gedefinieerd, 4 Beheerst, 5 Optimaliserend (niveau = afgeronde score naar beneden).
Doelen en prioriteiten beïnvloeden scores nooit.

### Beheer (`@AdminOnly`, anders 403)
| Methode | Pad | Opmerking |
|---|---|---|
| GET | `/admin/domains?cursor&pageSize` | gepagineerd in de database; domeinen met vragen en opties |
| POST / PATCH | `/admin/domains`, `/admin/domains/{id}` | `{name, description?, order, active, version}` |
| POST | `/admin/domains/{domainId}/questions` | `{title, text, explanation?, order, active, version: 0}` |
| PATCH | `/admin/questions/{id}` | `{domainId, title, text, explanation?, order, active, version}` |
| POST / PATCH | `/admin/questions/{id}/options`, `/admin/options/{id}` | `{score 0–5, label, description?, order, available, version}` |

Geen DELETE (historische inhoud wordt niet fysiek verwijderd). Een actieve vraag moet ≥1 beschikbare optie
hebben (422 bij activeren zonder optie of het buiten gebruik stellen van de laatste optie).
De zeven referentiedomeinen zijn verplicht (`domains.required`, read-only in beheer en actief model). Een verplicht domein mag niet worden gedeactiveerd en moet minimaal één actieve vraag met beschikbare optie houden. De laatste bruikbare vraag deactiveren/verplaatsen of de laatste optie uitschakelen geeft 422. De controles vergrendelen domein en vraag; scancreatie controleert het model nogmaals.

Resultaten en PDF accepteren `prioritySetRevision` (geheel getal ≥1), standaard de nieuwste set. Een niet-bestaande versie voor de scan geeft 404 problem+json. Ook lege opgeslagen sets zijn beschikbaar; `availablePrioritySetRevisions` bevat alle versies oplopend.

### Operations
`/q/health/live`, `/q/health/ready` (incl. datasource), `/q/metrics`. OpenAPI en Swagger UI staan in `%prod` uit en blijven in dev/test beschikbaar. nginx proxyt `/q/*` niet; deze paden zijn in Docker alleen intern bereikbaar. Request-body max 1 MB.
Audit-events (append-only tabel `audit_events`) voor registratie, aanmelding (gelukt/mislukt), adminmutaties,
scan afronden en revisies: actor, actie, resource, organisatie, tijd, correlation-id, uitkomst — nooit
credentials of tokens.

## Uitvoering

| Stap | Inhoud | WP's |
|---|---|---|
| 1 | Backend security: globale auth-filter, rollen, admin-autorisatie, strikte tokens, veilige prod-config, input-limieten, correlation-id/logging, audittrail, /smoke verwijderen | 01, 02, 09 (backend), 14 |
| 2 | Backend data: nieuw schema + snapshots, lifecycle/revisies, antwoordvalidatie, resultaten/niveaus/aanbevelingen, geversioneerde prioriteiten, Nederlandstalige PDF, admin-uitbreiding, paginering/N+1, contracttests | 03–06, 11, 13 |
| 3 | Frontend (parallel aan 1–2, tegen dit contract): interceptor, organisatie herstellen, portfolio-navigatie, volledige beoordelingsflow, resultaten met radar, admin-feature, validatie/a11y, vaste dependencyversies | 07, 08, 10, 12 |
| 4 | Docker: Dockerfiles, `docker-compose.yml`, nginx met security-headers, README | 09 |
| 5 | Integratie: alles via `docker compose up` doorlopen in de browser, onafhankelijke review, fixes | — |

### Werkstromen en eigenaarschap

Stap 1+2 (backend) en stap 3 (frontend) lopen parallel tegen het contract hierboven; stap 4 is al als concept
voorbereid. Werkstromen raken elkaars bestanden niet:

| Werkstroom | Eigenaar van | Mag niet aanraken |
|---|---|---|
| Backend | `app/backend/**` (behalve `Dockerfile`), `locks/contracts/openapi.yaml` | frontend, root-bestanden |
| Frontend | `app/frontend/**` (behalve `Dockerfile`, `nginx.conf`) | backend, root-bestanden |
| Docker/ops | `docker-compose.yml`, `.env.example`, `app/*/Dockerfile`, `app/*/.dockerignore`, `app/frontend/nginx.conf`, `README.md` | applicatiecode |

Werkstromen committen niet zelf; integratie en commits gebeuren centraal op `mvp`.

### Opleveringscriteria per werkstroom

**Backend** — `mvn test` groen, met integratietests die aantonen:
- zonder token 401 op iedere niet-publieke endpoint; niet-admin 403 op iedere admin-endpoint;
- cross-organisatie 404 op scan ophalen/wijzigen/beantwoorden/afronden/resultaten/export/prioriteiten;
- tokens met verkeerde `iss`/`aud`/`alg` of verlopen → 401; prod-configvalidator weigert ontbrekende/zwakke geheimen;
- antwoord op afgeronde scan → 409; optie van andere vraag → 422; vraag uit niet-geselecteerd domein → 422;
- afronden slaagt terwijl niet-geselecteerde domeinen leeg zijn;
- regressie: scan afronden, beheerinhoud wijzigen (domein hernoemen, optiescore wijzigen, vraag deactiveren) → resultaten identiek;
- revisie laat origineel ongewijzigd; prioriteitenhistorie blijft bewaard; doelen/prioriteiten wijzigen scores niet;
- PDF bevat `Organisatiedoelen en prioriteiten`; contracttests op status, content type en verplichte velden.
- Migraties draaien op zowel H2 (tests) als PostgreSQL 17. Demo-accounts: `demo@pong.example` (member) en
  `admin@pong.example` (admin), alleen via `db/demo`.

**Frontend** — `npm test -- --no-watch`, `npm run lint` en `npm run build` groen, met tests voor:
- interceptor (bearer alleen op `/v1`, 401 → afmelden + `/start?returnUrl=…`) en ProblemDetails-normalisatie;
- inloggen herstelt `desiredRoute` en laadt organisaties (terugkerende gebruiker);
- portfolio: afgerond → resultaten, overige → beoordeling; validatie scan aanmaken;
- kennisgebiedselectie toont exact `Selecteer minimaal één kennisgebied om verder te gaan.`;
- antwoord opslaan stuurt de juiste versie en behandelt 409; afronden pas mogelijk als alles compleet is;
- resultaten tonen radar (inline SVG + tabel-fallback) en slaan prioriteiten op;
- admin-guard weigert niet-beheerders; adminformulier valideert score 0–5.
- Alle dependencies op exacte versies, `lang="nl"`, `:focus-visible` op alle interactieve elementen, dev-proxy `/v1` → `:8080`.

**Docker/ops** — `docker compose up --build` start db, backend (prod-profiel) en frontend; app op
`http://localhost:8080`; backend pas "healthy" als `/q/health/ready` UP is; README beschrijft vereisten,
omgevingsvariabelen, demo vs. zonder demodata, health/readiness, lokaal ontwikkelen en productie-aandachtspunten.

### Integratie en verificatie (stap 5)

1. `docker compose up --build`; controleer health van alle drie de services.
2. In de browser doorlopen: registreren → organisatie vastleggen → scan aanmaken → kennisgebieden selecteren →
   alle vragen beantwoorden → afronden → resultaten + radar → prioriteiten opslaan → PDF exporteren →
   revisie maken; opnieuw inloggen herstelt organisatie en scans; als `admin@pong.example` beheer gebruiken en
   controleren dat een afgeronde scan ongewijzigd blijft; als gewone gebruiker is `/admin` niet bereikbaar.
3. Response-headers (CSP, nosniff, frame-options) en `X-Correlation-Id` controleren; productieprofiel weigert te
   starten zonder `ACCOUNT_TOKEN_SECRET`.
4. Onafhankelijke review (bijv. via agent-relay `review`) tegen de rapportbevindingen; fixes doorvoeren.
5. Commits op `mvp`; niets naar `main`.

### Status uitvoering (20-10-2026)

Alle stappen zijn uitgevoerd op `mvp`:

- Backend: 75 tests groen (security, autorisatie, cross-organisatie, tokens, lifecycle, snapshots, revisies,
  prioriteiten, PDF, contract, concurrency). Getest tegen PostgreSQL 17.
- Frontend: 67 tests, lint en productiebuild groen.
- `docker compose up --build` start de volledige stack; de flow uit stap 5 is in de browser doorlopen
  (registreren → organisatie → scan → selectie → 6 vragen → afronden → resultaten → prioriteiten → PDF → revisie,
  beheer als admin, beheerwijziging laat afgeronde scan ongewijzigd, 403/404/409 waar verwacht).
- Onafhankelijke review (Codex): 1 blocker en 6 majors gevonden en opgelost (admin-overname via registratie,
  races rond afronden/prioriteiten/beheer, optimistic-lock → 409, meegeleverde standaardwaarden in productie,
  idempotency-key bij retries) plus limieten beheerformulieren gelijkgetrokken.

Bekende, geaccepteerde beperkingen voor de pilot:
- Registratie meldt een bestaand e-mailadres (account-enumeratie); acceptabel voor een bekende gebruikersgroep.
- Token staat alleen in het geheugen: herladen betekent opnieuw aanmelden (met terugkeer naar dezelfde pagina).
- Bodies > 1 MB krijgen een kale 413 van de HTTP-laag, geen problem+json.
- Append-only van `audit_events` wordt in de applicatie afgedwongen, niet met een databasetrigger.
- TLS en HSTS horen op de reverse proxy vóór de frontend-container.

Bewust buiten scope voor de pilot: mockup-parity-gate (UX-06, niet te draaien buiten de oorspronkelijke
pipeline), meerdere gebruikers per organisatie, wachtwoordherstel.

### Aanvullende fixes (21-10-2026)

- Compose vereist expliciete geheimen; lokale opstart via `start-lokaal.sh` maakt uitsluitend bij ontbrekende `.env` willekeurige geheimen en demo-instellingen aan. Gedeelde omgevingen gebruiken `.env.example`, zonder demo en met databasewachtwoord ≥12 tekens.
- Credentials zijn begrensd op 128 tekens, registratie blijft daarnaast 8 tekens minimaal en 72 UTF-8-bytes maximaal.
- Alle positie- en sorteerkolommen hebben database-CHECKs ≥0; `scan_selected_domains(snapshot_domain_id)` heeft een index.
- De beoordeling koppelt ook geïndexeerde serverveldfouten aan controls. Antwoordopslag herstelt de opslagstatus via `finalize`. Routewisselingen wissen oude scaninhoud/resultaten en annuleren oude laadrequests.
- Gebruikersteksten spreken over volwassenheid en knelpunten; productnaam en API-enums blijven gelijk.
- Dit schema is een verse V1-baseline: databases van vóór de MVP-herziening moeten opnieuw worden aangemaakt, zonder upgradepad.

### Bewijstests en CI (21-10-2026)

- Bewijstests toegevoegd voor database-uitval/readiness met PostgreSQL 17 via een geïsoleerd Testcontainers-profiel (WP-09), runtime-OpenAPI tegenover het vastgelegde contract (WP-11), API/PDF-inhoudspariteit met echte tekstextractie (WP-06) en begrensde queryaantallen bij één versus zeven geselecteerde domeinen (WP-13).
- GitHub Actions toegevoegd voor backendtests (Java 25), frontendtests/lint/build (Node 24), startscript en Compose-configuratie. Het PostgreSQL-bewijs vereist Docker; de overige backendtests blijven H2 gebruiken. De testuitvoering in CI/lokaal moet de nieuwe bewijzen nog bevestigen.
- Verificatie in de agentsandbox: backendtests compileren; frontend 77 tests, lint en productiebuild groen; vier opstartscript-tests groen. Backend-HTTP-tests worden door de sandbox geblokkeerd (`SocketException: Operation not permitted`); PostgreSQL-test en Compose-validatie vereisen lokale/CI-uitvoering met Docker Compose v2.
