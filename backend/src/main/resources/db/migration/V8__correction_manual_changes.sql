-- A figure entered by hand in a correction is not in the ledger, so later corrections must be able
-- to carry it forward or they would reverse it.
ALTER TABLE correction_case ADD COLUMN manual_changes jsonb NOT NULL DEFAULT '[]';
