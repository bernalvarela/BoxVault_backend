-- =====================================================================
-- V27 - el servidor de correo saliente, configurado desde la aplicación
-- =====================================================================
--
-- Una sola fila (id = 1). La contraseña va cifrada con una clave que sale de
-- BOXVAULT_JWT_SECRET (ver SecretCipher), nunca en claro. Si no existe la
-- fila, el correo está apagado.
--
-- Sin BEGIN/COMMIT: la transacción la abre y la cierra Flyway.
-- =====================================================================

CREATE TABLE IF NOT EXISTS mail_settings (
    id                  BIGINT       PRIMARY KEY,
    enabled             BOOLEAN      NOT NULL DEFAULT FALSE,
    host                VARCHAR(150),
    port                INTEGER      NOT NULL DEFAULT 587,
    security            VARCHAR(10)  NOT NULL DEFAULT 'STARTTLS',
    username            VARCHAR(150),
    password_encrypted  TEXT,
    from_address        VARCHAR(150),
    from_name           VARCHAR(150),
    copy_to             VARCHAR(500),
    updated_at          TIMESTAMP(6),
    updated_by          VARCHAR(60),
    CONSTRAINT mail_settings_security_check CHECK (security IN ('STARTTLS', 'SSL', 'NINGUNA')),
    CONSTRAINT mail_settings_singleton CHECK (id = 1)
);
