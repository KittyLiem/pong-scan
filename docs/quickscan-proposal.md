# Proposal: quickscan

## Scope

| In scope | Out of scope |
|---|---|
| Quickscan: one scan per quickscan account, entered via `/quickscan` (part A) | Setting up the tenant platform: provisioning tenant databases, routing, billing |
| Admin chooses the quickscan domains (part B) | Actually moving an account to a tenant database (the upgrade itself) |
| Scans and account management designed so they work unchanged in a database per tenant later (part C) | Tenant-specific reference content (each tenant managing its own domains/questions) |

**Tenants have no quickscan.** The quickscan only exists on the central Pong platform. After an upgrade the customer
works in its own database with full scans only; its quickscan comes along as read-only history.

---

## C: design principles for a database per tenant later

These choices keep the work below portable. None of them require a tenant platform now.

### C1. One schema, the database is the tenant boundary

The same application and the same Flyway migrations run against every database:

- **Central database** (now): Pong admins, reference content, quickscan accounts.
- **Tenant database** (later): one per customer, with its own members, organizations and scans.

Because the database itself separates tenants, **no `tenant_id` columns and no `tenants` table** are needed. Queries
stay as they are; the future platform only has to pick the right datasource per request.

### C2. Quickscan is a deployment feature

```properties
app.quickscan.enabled=true          # central platform
app.quickscan.max-per-account=1
```

Tenant deployments set `app.quickscan.enabled=false`: the quickscan endpoints return 404, `GET /v1/me` reports
`features.quickscan = false` and the frontend hides `/quickscan`. The `in_quickscan` column exists in every database
(same schema) but is only used centrally.

### C3. Account roles mean "within this database"

`account_members.role` keeps its values `member` / `admin`, with one meaning everywhere: *administrator of this
database*. Centrally that is Pong staff; in a tenant database it is the customer's own administrator, who manages
that tenant's member accounts. No rename or second role column is needed.

### C4. Organization access via membership, not ownership

Today an organization belongs to exactly one member (`organizations.member_id`). In a tenant, several members work
on the same organizations. So access moves to a membership table now:

```sql
CREATE TABLE organization_members (
  organization_id UUID NOT NULL,
  member_id       UUID NOT NULL,
  role            VARCHAR(16) NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_organization_members PRIMARY KEY (organization_id, member_id),
  CONSTRAINT fk_organization_members_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
  CONSTRAINT fk_organization_members_member FOREIGN KEY (member_id) REFERENCES account_members (id),
  CONSTRAINT ck_organization_members_role CHECK (role IN ('owner', 'contributor', 'viewer'))
);
CREATE INDEX ix_organization_members_member ON organization_members (member_id);
-- Backfill: every existing organization gets its member_id as 'owner'.
```

- Access is checked in two places today: `OrganizationEntity.accessible` and `ScanAccess.load`. Both become
  "member has a membership for this organization". The 404-instead-of-403 behaviour stays.
- `organizations.member_id` stays as "created by".
- Now: every organization has exactly one `owner` (behaviour unchanged). Later, a tenant admin can add contributors and
  viewers without schema changes. Inviting and managing those members is a tenant feature, not part of this work.

### C5. Data that can be moved

- All IDs are UUIDs, so rows copied into a new tenant database never collide.
- Scans copy their content into snapshot tables and do not depend on the reference content at that time.
- No references from account data to scan data (the earlier `trail_scan_id` idea is dropped).
- Audit events have no foreign keys.

The future upgrade is therefore "copy this member, its organizations (via `organization_members`) and their scans to
the new database". It needs no extra columns now.

---

## A: one quickscan per account, via `/quickscan`

### Data model (`V3__quickscan.sql`, together with C4)

```sql
ALTER TABLE account_members ADD COLUMN plan VARCHAR(16) NOT NULL DEFAULT 'full';
ALTER TABLE account_members ADD CONSTRAINT ck_account_members_plan CHECK (plan IN ('quickscan', 'full'));

ALTER TABLE scans ADD COLUMN kind VARCHAR(8) NOT NULL DEFAULT 'full';
ALTER TABLE scans ADD CONSTRAINT ck_scans_kind CHECK (kind IN ('full', 'quick'));
```

A per-account plan is fine here: quickscan accounts are single-person and only exist centrally (C2). In a tenant
database every account is `full`.

### User flow

| Route | What happens |
|---|---|
| `/quickscan` | Landing page: which domains the quickscan covers (B), sign-up / sign-in |
| | Existing organization step of the sign-up screen (`AccountAccessComponent`), reused as is |
| `/quickscan/start` | Create-or-open the quickscan; open → assessment, completed → results |
| `/scans/:id/assessment` | Existing screen; domain picker hidden for `kind = 'quick'` |
| `/scans/:id/results` | Existing screen + "Quickscan" label and a call-to-action to upgrade |

- A quickscan account opening `/scans` is sent to `/quickscan/start`; "Nieuwe scan" and "Revisie maken" are hidden.
- A `full` account or an admin who signs in through `/quickscan` is sent to `/scans`: a full account already covers
  the quickscan.

### Backend

- **Sign-up**: `POST /v1/auth/registrations` accepts an optional `entry: "quickscan"` → `plan = 'quickscan'`. The
  value is part of the registration fingerprint, so a replay with a different `entry` is an idempotency conflict.
  Only allowed when `app.quickscan.enabled`. `BootstrapAdmin` keeps creating `full` admins.
- **Organization**: the existing `POST /v1/organizations`. For quickscan accounts `OrganizationService.create` refuses a
  second organization (403). It also creates the `owner` row in `organization_members` (C4).
- **`PUT /v1/quickscan`** (idempotent create-or-get) in `QuickscanResource` / `QuickscanService`:
  1. Refuse with 403 for `plan = 'full'` and 404 when the feature is off.
  2. Lock the member row (`PESSIMISTIC_WRITE`, same pattern as `lockOrganization`).
  3. Existing quick scan for the member's organization → return it (200), open or completed.
  4. No organization yet → 409 "Organisatie ontbreekt" (the frontend then shows the organization step).
  5. Otherwise run the existing `checkQuota` and create the scan with `kind = 'quick'` via the `ScanService` snapshot
     logic (201).
  The member row lock makes concurrent double clicks produce one scan; `app.quickscan.max-per-account` makes the
  limit configurable.
- **Guards** for `plan = 'quickscan'` (403, "Een quickscan-account heeft één scan."): `ScanService.create` and
  `ScanService.createRevision`.
- **Revisions of a quick scan** are refused for every plan (409): a quick scan only has the quickscan domains. After an
  upgrade the customer starts a full scan.
- `ScanDtos.Scan` gets `kind`; `AccountDtos.Member` gets `plan` and `features`.
- Audit: `quickscan.create`, `quickscan.denied`.

### Demo data

`V1000__demo_data.sql` runs after `V3` in dev/test, so the V3 backfill does not cover it. It must insert an
`organization_members` row for its demo organization. The new columns have defaults, so the rest is unaffected.

### Known limit

One person can create several accounts with different e-mail addresses. Existing protection: unique e-mail plus
registration/login throttling. Add e-mail verification if this becomes a problem.

---

## B: admin chooses the quickscan domains

### Data model

```sql
ALTER TABLE domains ADD COLUMN in_quickscan BOOLEAN NOT NULL DEFAULT FALSE;
```

One boolean is enough for one quickscan variant. Several variants would mean a `scan_profiles` table; the snapshot
filter stays the same.

### Backend

- `AdminDtos.DomainWrite` / `AdminDtos.Domain` get `inQuickscan`; `AdminContentService` stores and audits it.
- `ActiveContentService.loadForQuickscan()` returns only active domains with `in_quickscan = true`.
- Quickscan creation snapshots only those domains and selects all of them; `ScanService.update` rejects changes to
  `selectedDomainIds` for `kind = 'quick'`.
- No quickscan domain with active content → 422 "Quickscan niet beschikbaar".
- The `required` flag keeps applying to full scans only.
- `GET /v1/quickscan/domains` with `@PermitAll`: names and descriptions for the landing page.

Because of the snapshot, changing `in_quickscan` never alters existing quickscans.

### Admin screen (only shown when `features.quickscan`)

- Domain editor: checkbox "Opnemen in quickscan" – "Geldt alleen voor nieuwe quickscans."
- Domain list: "Quickscan" badge per included domain, and a summary "Quickscan: 3 van 9 kennisgebieden" with a warning
  when none are selected or a selected domain has no active content.

### Results / PDF

Calculation unchanged (already per selected snapshot domain). Add a "Quickscan" label to the results header and PDF
title, and list the domains a full scan adds.

---

## Later: upgrade to the tenant platform (out of scope, for reference)

1. Provision a tenant database (same migrations, `app.quickscan.enabled=false`).
2. Copy the member (as `admin`, `plan = 'full'`), its organizations, `organization_members` rows and scans.
3. Mark the central account as moved; the customer signs in to its tenant from then on.
4. The quick scan stays readable in the tenant as history (`kind = 'quick'`, no revisions).

---

## Tests

- **Membership (C4)**: backfill gives every existing organization an owner; access via membership;
  `CrossOrganizationAccessTest` and `AuthorizationTest` keep passing.
- **Feature switch (C2)**: with `app.quickscan.enabled=false` the endpoints return 404, `entry: "quickscan"` is refused,
  `features.quickscan` is false.
- **Quickscan (A)**: create-or-get returns the same scan twice; concurrent calls create one scan; 409 without
  organization; second organization, `POST /scans` and revisions refused for quickscan accounts; revisions of quick
  scans refused for everyone; full accounts unaffected; registration replay with a different `entry` conflicts.
- **Domains (B)**: snapshot only contains `in_quickscan` domains; changing the flag leaves existing quickscans as they
  are; 422 with no quickscan domains; domain selection cannot be changed.
- **Frontend**: `/quickscan` routing, redirects for quickscan and full accounts, completed → results, hidden actions,
  admin checkbox/badge/summary behind the feature flag.

## Suggested order

1. C4: `organization_members`, backfill, access queries, demo data. No visible change.
2. A: `plan`, `kind`, feature flag, registration `entry`, `PUT /v1/quickscan`, guards.
3. B: `in_quickscan`, admin UI, snapshot filter.
4. Frontend `/quickscan` flow and result labels.
