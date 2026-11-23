CREATE TABLE oauth_authorization_requests (
  nonce_hash TEXT PRIMARY KEY,
  redirect_uri TEXT NOT NULL,
  state TEXT NOT NULL,
  code_challenge TEXT NOT NULL,
  scopes TEXT NOT NULL,
  app_code_hash TEXT UNIQUE,
  approved_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX oauth_authorization_requests_expiry ON oauth_authorization_requests(expires_at);
CREATE INDEX oauth_authorization_requests_approved_user ON oauth_authorization_requests(approved_user_id);

CREATE TABLE oauth_authorization_codes (
  code_hash TEXT PRIMARY KEY,
  redirect_uri TEXT NOT NULL,
  code_challenge TEXT NOT NULL,
  scopes TEXT NOT NULL,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  client_id TEXT NOT NULL,
  resource TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX oauth_authorization_codes_expiry ON oauth_authorization_codes(expires_at);
CREATE INDEX oauth_authorization_codes_user ON oauth_authorization_codes(user_id);
