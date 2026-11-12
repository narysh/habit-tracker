CREATE TABLE oauth_refresh_tokens (
  token_hash TEXT PRIMARY KEY,
  access_hash TEXT NOT NULL REFERENCES sessions(token_hash) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id),
  client_id TEXT NOT NULL,
  resource TEXT NOT NULL,
  scopes TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
);
