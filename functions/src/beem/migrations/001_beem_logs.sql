-- BEEM-OTP-001 · Module 3 — for applications that store Beem logs in PostgreSQL (e.g. Mabala).
-- SeloNaChipa keeps the same columns as Firestore collections otp_logs / sms_logs (see firebase/firestore.indexes.json).
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS otp_logs (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  msisdn          VARCHAR(20) NOT NULL,
  pin_id          VARCHAR(100),
  event_type      VARCHAR(50) NOT NULL,  -- 'OTP_REQUESTED' | 'OTP_VERIFIED' | 'OTP_FAILED'
  beem_code       INTEGER,
  beem_message    VARCHAR(255),
  app_context     VARCHAR(50),           -- 'signup' | 'phone_change' | etc.
  ip_address      VARCHAR(45),
  user_agent      TEXT,
  success         BOOLEAN NOT NULL DEFAULT false,
  error_detail    TEXT,
  created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_otp_logs_msisdn ON otp_logs(msisdn);
CREATE INDEX IF NOT EXISTS idx_otp_logs_created_at ON otp_logs(created_at);

CREATE TABLE IF NOT EXISTS sms_logs (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  msisdn          VARCHAR(20) NOT NULL,
  event_type      VARCHAR(100) NOT NULL,
  message_body    TEXT NOT NULL,
  beem_code       INTEGER,
  beem_message    VARCHAR(255),
  reference_id    VARCHAR(100),          -- order_id, parcel_id, etc.
  application     VARCHAR(50) NOT NULL,  -- 'selonachipa' | 'mabala'
  success         BOOLEAN NOT NULL DEFAULT false,
  error_detail    TEXT,
  created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sms_logs_msisdn ON sms_logs(msisdn);
CREATE INDEX IF NOT EXISTS idx_sms_logs_event_type ON sms_logs(event_type);
CREATE INDEX IF NOT EXISTS idx_sms_logs_created_at ON sms_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_sms_logs_reference_id ON sms_logs(reference_id);
