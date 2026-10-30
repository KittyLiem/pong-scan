-- Volledig schema voor de Pong datavolwassenheidsscan (MVP).
-- Moet draaien op PostgreSQL 17 en op H2 in PostgreSQL-modus (tests).

-- ---------------------------------------------------------------------------
-- Accounts en sessies
-- ---------------------------------------------------------------------------
CREATE TABLE account_members (
  id                       UUID PRIMARY KEY,
  account_identifier       VARCHAR(320) NOT NULL,
  credential_hash          VARCHAR(100) NOT NULL,
  role                     VARCHAR(16)  NOT NULL DEFAULT 'member',
  registration_key_hash    VARCHAR(64),
  registration_fingerprint VARCHAR(64),
  created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
  -- Mislukte aanmeldpogingen per account: persistent, zodat een aanvaller de teller niet kan wegdrukken.
  failed_login_count       INTEGER NOT NULL DEFAULT 0,
  failed_login_window_ends TIMESTAMP WITH TIME ZONE,
  version                  BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_account_members_identifier UNIQUE (account_identifier),
  CONSTRAINT ck_account_members_failed_logins CHECK (failed_login_count >= 0),
  CONSTRAINT uq_account_members_registration_key UNIQUE (registration_key_hash),
  CONSTRAINT ck_account_members_role CHECK (role IN ('member', 'admin'))
);

CREATE TABLE account_sessions (
  id                   UUID PRIMARY KEY,
  member_id            UUID NOT NULL,
  access_token_hash    VARCHAR(64) NOT NULL,
  idempotency_key_hash VARCHAR(64) NOT NULL,
  request_fingerprint  VARCHAR(64) NOT NULL,
  desired_route        VARCHAR(2048),
  issued_at            TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at           TIMESTAMP WITH TIME ZONE NOT NULL,
  revoked_at           TIMESTAMP WITH TIME ZONE,
  version              BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_account_sessions_member FOREIGN KEY (member_id) REFERENCES account_members (id),
  CONSTRAINT uq_account_sessions_token UNIQUE (access_token_hash),
  CONSTRAINT uq_account_sessions_idempotency UNIQUE (idempotency_key_hash)
);
CREATE INDEX ix_account_sessions_member ON account_sessions (member_id);

-- ---------------------------------------------------------------------------
-- Organisaties
-- ---------------------------------------------------------------------------
CREATE TABLE organizations (
  id                         UUID PRIMARY KEY,
  member_id                  UUID NOT NULL,
  name                       VARCHAR(255) NOT NULL,
  chamber_of_commerce_number VARCHAR(8),
  industry                   VARCHAR(255),
  additional_information     VARCHAR(4000),
  status                     VARCHAR(16) NOT NULL,
  idempotency_key_hash       VARCHAR(64) NOT NULL,
  request_fingerprint        VARCHAR(64) NOT NULL,
  created_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
  version                    BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_organizations_member FOREIGN KEY (member_id) REFERENCES account_members (id),
  CONSTRAINT uq_organizations_member_idempotency UNIQUE (member_id, idempotency_key_hash),
  CONSTRAINT ck_organizations_status CHECK (status IN ('active', 'inactive'))
);
CREATE INDEX ix_organizations_member ON organizations (member_id);

-- ---------------------------------------------------------------------------
-- Beheerde inhoud (bron voor nieuwe scans)
-- ---------------------------------------------------------------------------
CREATE TABLE domains (
  required BOOLEAN NOT NULL DEFAULT FALSE,
  id                   UUID PRIMARY KEY,
  name                 VARCHAR(255) NOT NULL,
  description          VARCHAR(2000),
  display_order        INTEGER NOT NULL CHECK (display_order >= 0),
  active               BOOLEAN NOT NULL,
  idempotency_key_hash VARCHAR(64),
  request_fingerprint  VARCHAR(64),
  version              BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_domains_idempotency UNIQUE (idempotency_key_hash)
);
CREATE INDEX ix_domains_order ON domains (display_order, id);

CREATE TABLE questions (
  id                   UUID PRIMARY KEY,
  domain_id            UUID NOT NULL,
  title                VARCHAR(255) NOT NULL,
  text                 VARCHAR(2000) NOT NULL,
  explanation          VARCHAR(4000),
  display_order        INTEGER NOT NULL CHECK (display_order >= 0),
  active               BOOLEAN NOT NULL,
  idempotency_key_hash VARCHAR(64),
  request_fingerprint  VARCHAR(64),
  version              BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_questions_domain FOREIGN KEY (domain_id) REFERENCES domains (id),
  CONSTRAINT uq_questions_idempotency UNIQUE (idempotency_key_hash)
);
CREATE INDEX ix_questions_domain ON questions (domain_id);

CREATE TABLE answer_options (
  id                   UUID PRIMARY KEY,
  question_id          UUID NOT NULL,
  score                INTEGER NOT NULL,
  label                VARCHAR(255) NOT NULL,
  description          VARCHAR(2000),
  display_order        INTEGER NOT NULL CHECK (display_order >= 0),
  available            BOOLEAN NOT NULL,
  idempotency_key_hash VARCHAR(64),
  request_fingerprint  VARCHAR(64),
  version              BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_answer_options_question FOREIGN KEY (question_id) REFERENCES questions (id),
  CONSTRAINT uq_answer_options_idempotency UNIQUE (idempotency_key_hash),
  CONSTRAINT ck_answer_options_score CHECK (score BETWEEN 0 AND 5)
);
CREATE INDEX ix_answer_options_question ON answer_options (question_id);

-- ---------------------------------------------------------------------------
-- Scans
-- ---------------------------------------------------------------------------
CREATE TABLE scans (
  id                              UUID PRIMARY KEY,
  organization_id                 UUID NOT NULL,
  name                            VARCHAR(120) NOT NULL,
  assessment_period               VARCHAR(16) NOT NULL,
  status                          VARCHAR(20) NOT NULL,
  progress                        INTEGER NOT NULL DEFAULT 0,
  revision                        INTEGER NOT NULL DEFAULT 1,
  revision_of                     UUID,
  general_comments                VARCHAR(4000),
  idempotency_key_hash            VARCHAR(64),
  request_fingerprint             VARCHAR(64),
  completion_idempotency_key_hash VARCHAR(64),
  completion_request_fingerprint  VARCHAR(64),
  created_at                      TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at                      TIMESTAMP WITH TIME ZONE NOT NULL,
  completed_at                    TIMESTAMP WITH TIME ZONE,
  version                         BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_scans_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
  CONSTRAINT fk_scans_revision_of FOREIGN KEY (revision_of) REFERENCES scans (id),
  CONSTRAINT uq_scans_organization_idempotency UNIQUE (organization_id, idempotency_key_hash),
  CONSTRAINT ck_scans_status CHECK (status IN ('concept', 'in-progress', 'content-complete', 'completed')),
  CONSTRAINT ck_scans_progress CHECK (progress BETWEEN 0 AND 100),
  CONSTRAINT ck_scans_revision CHECK (revision >= 1),
  CONSTRAINT ck_scans_completed_at CHECK (status <> 'completed' OR completed_at IS NOT NULL)
);
CREATE INDEX ix_scans_organization_updated ON scans (organization_id, updated_at);
CREATE INDEX ix_scans_organization_created ON scans (organization_id, created_at);
CREATE INDEX ix_scans_revision_of ON scans (revision_of);

-- Immutable kopie van de beheerde inhoud op het moment van aanmaken van de scan.
CREATE TABLE scan_snapshot_domains (
  id            UUID PRIMARY KEY,
  scan_id       UUID NOT NULL,
  source_id     UUID NOT NULL,
  name          VARCHAR(255) NOT NULL,
  description   VARCHAR(2000),
  display_order INTEGER NOT NULL CHECK (display_order >= 0),
  CONSTRAINT fk_snapshot_domains_scan FOREIGN KEY (scan_id) REFERENCES scans (id),
  CONSTRAINT uq_snapshot_domains_scan_id UNIQUE (scan_id, id),
  CONSTRAINT uq_snapshot_domains_scan_source UNIQUE (scan_id, source_id)
);
CREATE INDEX ix_snapshot_domains_scan ON scan_snapshot_domains (scan_id);

CREATE TABLE scan_snapshot_questions (
  id                 UUID PRIMARY KEY,
  scan_id            UUID NOT NULL,
  snapshot_domain_id UUID NOT NULL,
  source_id          UUID NOT NULL,
  title              VARCHAR(255) NOT NULL,
  text               VARCHAR(2000) NOT NULL,
  explanation        VARCHAR(4000),
  display_order      INTEGER NOT NULL CHECK (display_order >= 0),
  CONSTRAINT fk_snapshot_questions_domain FOREIGN KEY (scan_id, snapshot_domain_id)
    REFERENCES scan_snapshot_domains (scan_id, id),
  CONSTRAINT uq_snapshot_questions_scan_id UNIQUE (scan_id, id)
);
CREATE INDEX ix_snapshot_questions_scan ON scan_snapshot_questions (scan_id);
CREATE INDEX ix_snapshot_questions_domain ON scan_snapshot_questions (scan_id, snapshot_domain_id);

CREATE TABLE scan_snapshot_options (
  id                   UUID PRIMARY KEY,
  scan_id              UUID NOT NULL,
  snapshot_question_id UUID NOT NULL,
  source_id            UUID NOT NULL,
  score                INTEGER NOT NULL,
  label                VARCHAR(255) NOT NULL,
  description          VARCHAR(2000),
  display_order        INTEGER NOT NULL CHECK (display_order >= 0),
  CONSTRAINT fk_snapshot_options_question FOREIGN KEY (scan_id, snapshot_question_id)
    REFERENCES scan_snapshot_questions (scan_id, id),
  CONSTRAINT uq_snapshot_options_question_id UNIQUE (snapshot_question_id, id),
  CONSTRAINT ck_snapshot_options_score CHECK (score BETWEEN 0 AND 5)
);
CREATE INDEX ix_snapshot_options_scan ON scan_snapshot_options (scan_id);
CREATE INDEX ix_snapshot_options_question ON scan_snapshot_options (scan_id, snapshot_question_id);

CREATE TABLE scan_selected_domains (
  scan_id            UUID NOT NULL,
  snapshot_domain_id UUID NOT NULL,
  CONSTRAINT pk_scan_selected_domains PRIMARY KEY (scan_id, snapshot_domain_id),
  CONSTRAINT fk_scan_selected_domains_snapshot FOREIGN KEY (scan_id, snapshot_domain_id)
    REFERENCES scan_snapshot_domains (scan_id, id)
);

CREATE INDEX ix_selected_domains_snapshot ON scan_selected_domains (snapshot_domain_id);

CREATE TABLE scan_goals (
  scan_id     UUID NOT NULL,
  position    INTEGER NOT NULL CHECK (position >= 0),
  goal_name   VARCHAR(255) NOT NULL,
  priority    VARCHAR(8) NOT NULL,
  custom_text VARCHAR(2000),
  CONSTRAINT pk_scan_goals PRIMARY KEY (scan_id, position),
  CONSTRAINT fk_scan_goals_scan FOREIGN KEY (scan_id) REFERENCES scans (id),
  CONSTRAINT ck_scan_goals_priority CHECK (priority IN ('high', 'medium', 'low'))
);

-- Antwoorden verwijzen naar de snapshot: de vraag hoort bij de scan en de optie bij de vraag.
CREATE TABLE scan_answers (
  id          UUID PRIMARY KEY,
  scan_id     UUID NOT NULL,
  question_id UUID NOT NULL,
  option_id   UUID NOT NULL,
  explanation VARCHAR(4000),
  updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  version     BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_scan_answers_scan FOREIGN KEY (scan_id) REFERENCES scans (id),
  CONSTRAINT fk_scan_answers_question FOREIGN KEY (scan_id, question_id)
    REFERENCES scan_snapshot_questions (scan_id, id),
  CONSTRAINT fk_scan_answers_option FOREIGN KEY (question_id, option_id)
    REFERENCES scan_snapshot_options (snapshot_question_id, id),
  CONSTRAINT uq_scan_answers_scan_question UNIQUE (scan_id, question_id)
);
CREATE INDEX ix_scan_answers_option ON scan_answers (question_id, option_id);

CREATE TABLE scan_answer_evidence (
  answer_id          UUID NOT NULL,
  position           INTEGER NOT NULL CHECK (position >= 0),
  evidence_reference VARCHAR(2048) NOT NULL,
  CONSTRAINT pk_scan_answer_evidence PRIMARY KEY (answer_id, position),
  CONSTRAINT fk_scan_answer_evidence_answer FOREIGN KEY (answer_id) REFERENCES scan_answers (id)
);

-- Geversioneerde sets ontwikkelprioriteiten: opslaan voegt een nieuwe set toe. Een set-revisie kan per scan
-- maar één keer bestaan (UNIQUE), ook bij gelijktijdige verzoeken.
CREATE TABLE scan_priority_sets (
  id           UUID PRIMARY KEY,
  scan_id      UUID NOT NULL,
  set_revision INTEGER NOT NULL,
  created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
  created_by   UUID,
  CONSTRAINT fk_priority_sets_scan FOREIGN KEY (scan_id) REFERENCES scans (id),
  CONSTRAINT uq_priority_sets_scan_revision UNIQUE (scan_id, set_revision),
  CONSTRAINT ck_priority_sets_revision CHECK (set_revision >= 1)
);

CREATE TABLE scan_development_priorities (
  id                   UUID PRIMARY KEY,
  scan_id              UUID NOT NULL,
  set_revision         INTEGER NOT NULL,
  development_point_id UUID NOT NULL,
  impact               VARCHAR(8) NOT NULL,
  urgency              VARCHAR(8) NOT NULL,
  selected             BOOLEAN NOT NULL,
  created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
  created_by           UUID,
  CONSTRAINT fk_development_priorities_scan FOREIGN KEY (scan_id) REFERENCES scans (id),
  CONSTRAINT fk_development_priorities_set FOREIGN KEY (scan_id, set_revision)
    REFERENCES scan_priority_sets (scan_id, set_revision),
  CONSTRAINT uq_development_priorities_set_point UNIQUE (scan_id, set_revision, development_point_id),
  CONSTRAINT ck_development_priorities_impact CHECK (impact IN ('low', 'medium', 'high')),
  CONSTRAINT ck_development_priorities_urgency CHECK (urgency IN ('low', 'medium', 'high')),
  CONSTRAINT ck_development_priorities_revision CHECK (set_revision >= 1)
);
CREATE INDEX ix_development_priorities_scan_set ON scan_development_priorities (scan_id, set_revision);

-- ---------------------------------------------------------------------------
-- Audittrail (append-only; de applicatie voert alleen INSERTs uit)
-- ---------------------------------------------------------------------------
CREATE TABLE audit_events (
  id              UUID PRIMARY KEY,
  occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  actor_member_id UUID,
  action          VARCHAR(64) NOT NULL,
  resource_type   VARCHAR(64) NOT NULL,
  resource_id     UUID,
  organization_id UUID,
  correlation_id  VARCHAR(64),
  outcome         VARCHAR(16) NOT NULL,
  CONSTRAINT ck_audit_events_outcome CHECK (outcome IN ('success', 'failure'))
);
CREATE INDEX ix_audit_events_occurred ON audit_events (occurred_at);
CREATE INDEX ix_audit_events_actor ON audit_events (actor_member_id);
CREATE INDEX ix_audit_events_organization ON audit_events (organization_id);
