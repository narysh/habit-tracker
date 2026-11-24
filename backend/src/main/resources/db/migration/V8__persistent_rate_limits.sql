CREATE TABLE rate_limit_windows (
  key_hash TEXT PRIMARY KEY,
  window_started_at TIMESTAMPTZ NOT NULL,
  attempts INTEGER NOT NULL CHECK (attempts > 0)
);
CREATE INDEX rate_limit_windows_expiry ON rate_limit_windows(window_started_at);
