# Oplevering fixes — 21 oktober 2026

De twaalf gevraagde verbeteringen zijn doorgevoerd: verplichte referentiedomeinen met domein- en vraaglocks,
veilige compose-instellingen en lokale opstart, credentiallimieten, database-CHECKs en index, Nederlandse
gebruikersteksten, historische prioriteitensets in resultaten en PDF, veldfoutkoppeling, `finalize` bij
antwoordopslag, lege schermstatus bij routewisselingen, productie-instellingen voor `/q/*` en documentatie.

## Keuzes

- Verplicht betekent dat een referentiedomein bruikbare actieve inhoud moet houden; de gebruiker blijft zelf kiezen welke domeinen in een scan worden beoordeeld. `required` is uitsluitend leesbaar in beheer en het actieve model.
- De bestaande vraaglocks blijven behouden; een voorafgaande domeinlock beschermt ook de laatste bruikbare vraag bij gelijktijdige wijzigingen. Een intussen verplaatste vraag geeft een versieconflict.
- Versies worden uit `scan_priority_sets` gelezen, zodat ook lege opgeslagen sets historisch opvraagbaar zijn. Zonder opgeslagen set blijven `setRevision: 0` en een lege versielijst gelden. Export gebruikt de gekozen versie.
- Oude composegeheimen blijven geweigerd: er bestaan nog verwijzingen in regressietests. Voorbeeldwaarden uit `.env.example` blijven eveneens geweigerd.
- V1/V2 zijn rechtstreeks aangepast. De bestaande demo-data past bij het schema en de verplichte referentiedomeinen; de immutable snapshots hebben geen nieuw `required`-veld nodig.
- nginx is ongewijzigd: het proxyt al uitsluitend `/v1` naar de backend. Een productieprofieltest en een volledige Docker-start zijn niet uitgevoerd; de interne bereikbaarheid en productie-instellingen zijn gedocumenteerd.

## Verificatie

| Controle | Resultaat |
|---|---|
| `cd app/backend && mvn -q test` | 119 tests: 46 geslaagd, 0 assertion-fouten, 73 errors door geblokkeerde HTTP-sockets (`java.net.SocketException: Operation not permitted`). De API-integratietests moeten in de omgeving van de eigenaar opnieuw draaien. |
| Nieuwe/relevante directe backendtests | 33 geslaagd: RequiredDomainTest (9), OrderingConstraintsTest (9), ProductionConfigValidatorTest (10), CredentialLimitsTest (3), ResultsHistoryTest (2). Ook de aangepaste validator is na de laatste vereenvoudiging opnieuw getest: 10/10. |
| `cd app/frontend && npm test -- --no-watch` | 77/77 geslaagd, 10 testbestanden. |
| `cd app/frontend && npm run lint` | Geslaagd. |
| `cd app/frontend && npm run build` | Geslaagd. |
| `sh tests/start-lokaal.test.sh` | 4/4 geslaagd: willekeurige geheimen, bestaande lokale `.env`, bestaande gedeelde `.env`, `/dev/urandom`-fallback. Docker wordt in deze tests vervangen door een stub. |
| Compose-configuratie | Geldig met expliciete geheimen; ontbrekend databasewachtwoord en ontbrekend tokengeheim worden geweigerd. Standaard lokale modus `false` en alleen `db/migration`. Gecontroleerd met de beschikbare zelfstandige `docker-compose`-CLI. |
| OpenAPI YAML | Geldig, zonder dubbele sleutels. |
| `git diff --check` en `sh -n` | Geslaagd. |

## Gewijzigde bestanden

- [.env.example](../.env.example)
- [README.md](../README.md)
- [app/README.md](../app/README.md)
- [app/backend/src/main/java/com/example/account/api/AccountDtos.java](../app/backend/src/main/java/com/example/account/api/AccountDtos.java)
- [app/backend/src/main/java/com/example/admin/api/AdminDtos.java](../app/backend/src/main/java/com/example/admin/api/AdminDtos.java)
- [app/backend/src/main/java/com/example/admin/application/AdminContentService.java](../app/backend/src/main/java/com/example/admin/application/AdminContentService.java)
- [app/backend/src/main/java/com/example/content/api/ActiveModelResource.java](../app/backend/src/main/java/com/example/content/api/ActiveModelResource.java)
- [app/backend/src/main/java/com/example/content/domain/DomainEntity.java](../app/backend/src/main/java/com/example/content/domain/DomainEntity.java)
- [app/backend/src/main/java/com/example/results/api/ResultsDtos.java](../app/backend/src/main/java/com/example/results/api/ResultsDtos.java)
- [app/backend/src/main/java/com/example/results/api/ResultsResource.java](../app/backend/src/main/java/com/example/results/api/ResultsResource.java)
- [app/backend/src/main/java/com/example/results/application/Recommendations.java](../app/backend/src/main/java/com/example/results/application/Recommendations.java)
- [app/backend/src/main/java/com/example/results/application/ResultsExportService.java](../app/backend/src/main/java/com/example/results/application/ResultsExportService.java)
- [app/backend/src/main/java/com/example/results/application/ResultsService.java](../app/backend/src/main/java/com/example/results/application/ResultsService.java)
- [app/backend/src/main/java/com/example/scan/application/ScanService.java](../app/backend/src/main/java/com/example/scan/application/ScanService.java)
- [app/backend/src/main/java/com/example/security/ProductionConfigValidator.java](../app/backend/src/main/java/com/example/security/ProductionConfigValidator.java)
- [app/backend/src/main/resources/application.properties](../app/backend/src/main/resources/application.properties)
- [app/backend/src/main/resources/db/migration/V1__schema.sql](../app/backend/src/main/resources/db/migration/V1__schema.sql)
- [app/backend/src/main/resources/db/migration/V2__reference_content.sql](../app/backend/src/main/resources/db/migration/V2__reference_content.sql)
- [app/backend/src/test/java/com/example/account/AccountApiTest.java](../app/backend/src/test/java/com/example/account/AccountApiTest.java)
- [app/backend/src/test/java/com/example/account/CredentialLimitsTest.java](../app/backend/src/test/java/com/example/account/CredentialLimitsTest.java)
- [app/backend/src/test/java/com/example/admin/AdminContentTest.java](../app/backend/src/test/java/com/example/admin/AdminContentTest.java)
- [app/backend/src/test/java/com/example/admin/RequiredDomainTest.java](../app/backend/src/test/java/com/example/admin/RequiredDomainTest.java)
- [app/backend/src/test/java/com/example/common/OrderingConstraintsTest.java](../app/backend/src/test/java/com/example/common/OrderingConstraintsTest.java)
- [app/backend/src/test/java/com/example/results/ResultsApiTest.java](../app/backend/src/test/java/com/example/results/ResultsApiTest.java)
- [app/backend/src/test/java/com/example/results/ResultsHistoryTest.java](../app/backend/src/test/java/com/example/results/ResultsHistoryTest.java)
- [app/backend/src/test/java/com/example/security/ProductionConfigValidatorTest.java](../app/backend/src/test/java/com/example/security/ProductionConfigValidatorTest.java)
- [app/frontend/src/app/account-access/account-access.component.html](../app/frontend/src/app/account-access/account-access.component.html)
- [app/frontend/src/app/admin/admin.component.html](../app/frontend/src/app/admin/admin.component.html)
- [app/frontend/src/app/admin/admin.component.spec.ts](../app/frontend/src/app/admin/admin.component.spec.ts)
- [app/frontend/src/app/admin/admin.component.ts](../app/frontend/src/app/admin/admin.component.ts)
- [app/frontend/src/app/app.routes.ts](../app/frontend/src/app/app.routes.ts)
- [app/frontend/src/app/core/http/problem.spec.ts](../app/frontend/src/app/core/http/problem.spec.ts)
- [app/frontend/src/app/core/http/problem.ts](../app/frontend/src/app/core/http/problem.ts)
- [app/frontend/src/app/core/model/admin.ts](../app/frontend/src/app/core/model/admin.ts)
- [app/frontend/src/app/core/model/results.ts](../app/frontend/src/app/core/model/results.ts)
- [app/frontend/src/app/core/scans/scan-results.service.ts](../app/frontend/src/app/core/scans/scan-results.service.ts)
- [app/frontend/src/app/maturity-assessment/maturity-assessment.component.html](../app/frontend/src/app/maturity-assessment/maturity-assessment.component.html)
- [app/frontend/src/app/maturity-assessment/maturity-assessment.component.spec.ts](../app/frontend/src/app/maturity-assessment/maturity-assessment.component.spec.ts)
- [app/frontend/src/app/maturity-assessment/maturity-assessment.component.ts](../app/frontend/src/app/maturity-assessment/maturity-assessment.component.ts)
- [app/frontend/src/app/scan-overview/scan-overview.component.html](../app/frontend/src/app/scan-overview/scan-overview.component.html)
- [app/frontend/src/app/scan-results/scan-results.component.html](../app/frontend/src/app/scan-results/scan-results.component.html)
- [app/frontend/src/app/scan-results/scan-results.component.spec.ts](../app/frontend/src/app/scan-results/scan-results.component.spec.ts)
- [app/frontend/src/app/scan-results/scan-results.component.ts](../app/frontend/src/app/scan-results/scan-results.component.ts)
- [app/frontend/src/app/shared/labels.ts](../app/frontend/src/app/shared/labels.ts)
- [docker-compose.yml](../docker-compose.yml)
- [docs/fix-overzicht.md](../docs/fix-overzicht.md)
- [docs/mvp-plan.md](../docs/mvp-plan.md)
- [locks/contracts/openapi.yaml](../locks/contracts/openapi.yaml)
- [start-lokaal.sh](../start-lokaal.sh)
- [tests/start-lokaal.test.sh](../tests/start-lokaal.test.sh)
