-- Quickscan: een quickscan-account mag één scan van het soort 'quick' doen (alleen op het centrale platform).
ALTER TABLE account_members ADD COLUMN plan VARCHAR(16) NOT NULL DEFAULT 'full';
ALTER TABLE account_members ADD CONSTRAINT ck_account_members_plan CHECK (plan IN ('quickscan', 'full'));

ALTER TABLE scans ADD COLUMN kind VARCHAR(8) NOT NULL DEFAULT 'full';
ALTER TABLE scans ADD CONSTRAINT ck_scans_kind CHECK (kind IN ('full', 'quick'));
