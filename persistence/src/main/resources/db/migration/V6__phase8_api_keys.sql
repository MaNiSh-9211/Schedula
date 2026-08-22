-- Phase 8 security: per-tenant API credentials. We store only a SHA-256 hash of the
-- secret plus a short display prefix; the plaintext key is shown exactly once at creation.

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS api_key_hash TEXT;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS api_key_prefix TEXT;

CREATE INDEX IF NOT EXISTS idx_tenants_key_prefix ON tenants (api_key_prefix);
