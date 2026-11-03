-- De beheerder kiest welke kennisgebieden een nieuwe quickscan bevat; standaard geen.
ALTER TABLE domains ADD COLUMN in_quickscan BOOLEAN NOT NULL DEFAULT FALSE;
