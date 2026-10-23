CREATE TABLE IF NOT EXISTS lavka_full_sync_job (
    job_id VARCHAR(36) NOT NULL PRIMARY KEY,
    active_slot INT NULL,
    status VARCHAR(32) NOT NULL,
    state_json LONGTEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY one_active_job (active_slot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS lavka_full_sync_chunk (
    job_id VARCHAR(36) NOT NULL,
    chunk_no INT NOT NULL,
    result_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (job_id, chunk_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
