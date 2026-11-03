-- Toegang tot een organisatie via lidmaatschap in plaats van eigendom (organizations.member_id blijft als
-- "aangemaakt door"). Voorbereiding op meerdere leden per organisatie in een eigen database per tenant.
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

-- Iedere bestaande organisatie krijgt haar aanmaker als eigenaar.
INSERT INTO organization_members (organization_id, member_id, role, created_at)
SELECT id, member_id, 'owner', created_at FROM organizations;
