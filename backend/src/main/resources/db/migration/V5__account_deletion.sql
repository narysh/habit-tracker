ALTER TABLE sessions ADD COLUMN issued_at TIMESTAMPTZ;
ALTER TABLE sessions ALTER COLUMN issued_at SET DEFAULT clock_timestamp();

CREATE TABLE account_file_purge (
  storage_key TEXT PRIMARY KEY,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX account_file_purge_ready ON account_file_purge(next_attempt_at);
