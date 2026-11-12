CREATE TABLE users (
  id UUID PRIMARY KEY,
  email TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE sessions (
  token_hash TEXT PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id),
  scopes TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX sessions_user ON sessions(user_id);
CREATE TABLE items (
  user_id UUID NOT NULL REFERENCES users(id),
  id UUID NOT NULL,
  version BIGINT NOT NULL,
  body JSONB NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT false,
  updated_at TIMESTAMPTZ NOT NULL,
  sequence BIGINT NOT NULL,
  PRIMARY KEY(user_id,id)
);
CREATE INDEX items_changes ON items(user_id,sequence);
CREATE TABLE operations (
  user_id UUID NOT NULL REFERENCES users(id),
  id UUID NOT NULL,
  request TEXT NOT NULL,
  result TEXT NOT NULL,
  PRIMARY KEY(user_id,id)
);
CREATE TABLE attachments (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id),
  note_id UUID NOT NULL,
  filename TEXT NOT NULL,
  media_type TEXT NOT NULL,
  size BIGINT NOT NULL,
  storage_key TEXT NOT NULL UNIQUE,
  sha256 TEXT NOT NULL,
  FOREIGN KEY(user_id,note_id) REFERENCES items(user_id,id)
);
