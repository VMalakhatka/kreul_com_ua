ALTER TABLE folio_balance_snapshot_generation
    ADD COLUMN processed_clients INT NOT NULL DEFAULT 0 AFTER total_clients,
    ADD COLUMN last_heartbeat_at DATETIME(3) NULL AFTER processed_clients;

UPDATE folio_balance_snapshot_generation g
LEFT JOIN (
    SELECT generation_id, COUNT(*) AS client_count
    FROM folio_balance_snapshot_client
    GROUP BY generation_id
) c ON c.generation_id = g.id
SET g.processed_clients = COALESCE(c.client_count, 0),
    g.last_heartbeat_at = COALESCE(g.completed_at, g.started_at);
