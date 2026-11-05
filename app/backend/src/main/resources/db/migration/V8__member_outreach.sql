-- Contactmomenten per account en campagne. Eén rij per (account, campagne): wie al benaderd is, wordt dat niet
-- nogmaals, ongeacht of het contact handmatig (beheerder) of later automatisch is verlopen.
CREATE TABLE member_outreach (
  id                  UUID PRIMARY KEY,
  member_id           UUID NOT NULL,
  campaign            VARCHAR(64) NOT NULL,
  channel             VARCHAR(16) NOT NULL,
  status              VARCHAR(16) NOT NULL,
  -- Versie van de marketingtoestemming waaronder het bericht is verstuurd.
  consent_version     VARCHAR(32),
  -- Bericht-id van de e-mailprovider (alleen bij automatische verzending).
  provider_message_id VARCHAR(255),
  -- Beheerder die het contact vastlegde; leeg bij automatische verzending.
  created_by          UUID,
  created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
  sent_at             TIMESTAMP WITH TIME ZONE,
  CONSTRAINT fk_member_outreach_member FOREIGN KEY (member_id) REFERENCES account_members (id),
  CONSTRAINT fk_member_outreach_created_by FOREIGN KEY (created_by) REFERENCES account_members (id),
  CONSTRAINT uq_member_outreach_campaign UNIQUE (member_id, campaign),
  CONSTRAINT ck_member_outreach_channel CHECK (channel IN ('manual', 'automatic')),
  CONSTRAINT ck_member_outreach_status CHECK (status IN ('queued', 'sent', 'failed', 'bounced'))
);
CREATE INDEX ix_member_outreach_campaign_created ON member_outreach (campaign, created_at);

-- Intrekken van de marketingtoestemming (afmeldlink in een marketingmail). Leeg zolang de toestemming geldt.
ALTER TABLE account_members ADD COLUMN marketing_consent_withdrawn_at TIMESTAMP WITH TIME ZONE;
