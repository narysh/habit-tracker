CREATE TABLE social_login_attempts (
  state_hash TEXT PRIMARY KEY,
  provider TEXT NOT NULL CHECK (provider IN ('google', 'vk', 'yandex')),
  client_challenge TEXT NOT NULL,
  provider_verifier_ciphertext BYTEA NOT NULL,
  link_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  link_session_hash TEXT,
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'READY_SUCCESS', 'READY_LINKED', 'READY_FAILED')),
  callback_lease_until TIMESTAMPTZ,
  callback_claim_hash TEXT,
  result_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  result_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  CHECK ((link_user_id IS NULL) = (link_session_hash IS NULL)),
  CHECK (status <> 'READY_SUCCESS' OR result_user_id IS NOT NULL),
  CHECK (status <> 'READY_LINKED' OR result_user_id IS NOT NULL),
  CHECK (status <> 'READY_FAILED' OR result_message IS NOT NULL)
);
CREATE INDEX social_login_attempts_expiry ON social_login_attempts(expires_at);
CREATE INDEX social_login_attempts_link_user ON social_login_attempts(link_user_id);
CREATE INDEX social_login_attempts_result_user ON social_login_attempts(result_user_id);
