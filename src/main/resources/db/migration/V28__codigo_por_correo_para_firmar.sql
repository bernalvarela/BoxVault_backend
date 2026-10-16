-- =====================================================================
-- V28 - firmar con enlace pide un código mandado al correo del firmante
-- =====================================================================
--
-- El DNI no comprobaba nada: va escrito en el propio contrato, que el enlace
-- enseña antes de firmar. Ahora, para ver el contrato y para firmarlo, hay que
-- teclear un código de seis cifras: o lo pide el firmante a su correo, o quien
-- gestiona lo genera y se lo hace llegar por otro medio (code_delivered_by).
-- Del código sólo se guarda la huella, como del enlace.
--
-- Sin BEGIN/COMMIT: la transacción la abre y la cierra Flyway.
-- =====================================================================

ALTER TABLE contract_signers ADD COLUMN IF NOT EXISTS code_hash        VARCHAR(64);
ALTER TABLE contract_signers ADD COLUMN IF NOT EXISTS code_sent_at     TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE contract_signers ADD COLUMN IF NOT EXISTS code_expires_at  TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE contract_signers ADD COLUMN IF NOT EXISTS code_sends       INTEGER NOT NULL DEFAULT 0;
ALTER TABLE contract_signers ADD COLUMN IF NOT EXISTS code_email       VARCHAR(150);
ALTER TABLE contract_signers ADD COLUMN IF NOT EXISTS code_delivered_by VARCHAR(60);
ALTER TABLE contract_signers ADD COLUMN IF NOT EXISTS code_verified_at TIMESTAMP(6) WITH TIME ZONE;
