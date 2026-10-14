-- Existing snapshots contain historical PRD payments, not outstanding preorder invoices.
-- Keep them for audit; only version 2 generations can be served by the new application.
ALTER TABLE folio_balance_snapshot_generation ADD COLUMN calculation_version INT NOT NULL DEFAULT 1;
ALTER TABLE folio_balance_snapshot_live_client ADD COLUMN calculation_version INT NOT NULL DEFAULT 1;
