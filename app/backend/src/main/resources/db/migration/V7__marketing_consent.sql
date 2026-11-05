-- Optionele toestemming voor marketing per e-mail, gevraagd bij het aanmaken van een quickscan-account.
-- Tijdstip en versie van de tekst zijn alleen gevuld als de toestemming is gegeven.
ALTER TABLE account_members ADD COLUMN marketing_consent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE account_members ADD COLUMN marketing_consent_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE account_members ADD COLUMN marketing_consent_version VARCHAR(32);
