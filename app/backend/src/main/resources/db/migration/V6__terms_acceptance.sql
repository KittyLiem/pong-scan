-- Akkoord met de voorwaarden en het privacybeleid, vastgelegd bij het aanmaken van een quickscan-account.
-- Leeg voor bestaande accounts en gewone registraties; de verplichting wordt in de service bewaakt.
ALTER TABLE account_members ADD COLUMN terms_accepted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE account_members ADD COLUMN terms_version VARCHAR(32);
