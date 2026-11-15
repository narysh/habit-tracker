ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
CREATE TABLE social_identities (
  provider TEXT NOT NULL,
  subject TEXT NOT NULL,
  user_id UUID NOT NULL REFERENCES users(id),
  PRIMARY KEY (provider,subject),
  UNIQUE (user_id,provider)
);
