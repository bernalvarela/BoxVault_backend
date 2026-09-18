-- =====================================================================
-- BoxVault - los tipos de documento que admite la base de datos
-- =====================================================================
--
-- Hibernate crea, para cada columna @Enumerated(STRING), un CHECK con los
-- valores que el enum tenía EL DÍA EN QUE CREÓ LA TABLA. Y ddl-auto=update no
-- vuelve a tocarlo nunca: añade columnas y tablas, pero no altera restricciones.
-- Así que cada valor nuevo del enum funciona en desarrollo (base en memoria,
-- creada de cero en cada arranque) y revienta en el servidor con un
--
--     new row for relation "documents" violates check constraint
--     "documents_document_type_check"
--
-- Le pasó a FACTURA (las facturas de las mensualidades) y ya le había pasado a
-- DECLARACION (la declaración archivada en una presentación), que estaba en el
-- código desde septiembre y habría fallado igual la primera vez que alguien
-- subiera una. Este script pone el CHECK al día con el enum entero.
--
-- Si mañana se añade otro tipo, hay que volver a pasar por aquí: es el precio de
-- que el esquema del servidor no se genere solo.
--
--     docker compose exec -T postgres pg_dump -U boxvault -Fc boxvault > antes.dump
--     docker compose exec -T postgres psql -U boxvault -d boxvault < 2026-09-18-tipos-de-documento.sql
-- =====================================================================

BEGIN;

ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_document_type_check;
ALTER TABLE documents ADD CONSTRAINT documents_document_type_check
    CHECK (document_type IN (
        'DNI', 'CONTRATO_TRABAJO', 'NOMINA', 'FOTO',
        'CONTRATO_ALQUILER', 'ANEXO', 'FACTURA',
        'JUSTIFICANTE', 'DECLARACION', 'OTRO'));

COMMIT;

-- ---------------------------------------------------------------------
-- Aparte: client_documents se quedó a medias
-- ---------------------------------------------------------------------
-- La migración 2026-09-08-documents.sql movía la ficha del fichero a la tabla
-- "documents" y dejaba client_documents como pura relación (client_id +
-- document_id). En este servidor no llegó a lanzarse: la tabla tenía las dos
-- cosas a la vez —las columnas viejas, todavía NOT NULL, y las nuevas—, así que
-- guardar un documento de un cliente fallaba al insertar (document_type, file_name
-- y storage_key no se rellenan). rental_documents sí quedó bien, y por eso los
-- documentos del alquiler funcionan.
--
-- La tabla estaba vacía, de modo que no había nada que trasladar: basta con
-- quitarle lo que sobra y queda como la describe la entidad ClientDocument
-- (id, client_id, document_id). El bloque comprueba antes que sigue vacía y se
-- para si no: unas columnas NOT NULL con filas dentro no se tiran a ciegas.
--
-- Aplicado en el servidor el 2026-09-18. Es idempotente (IF EXISTS), así que
-- pasarlo de nuevo no hace nada.

BEGIN;

DO $$
DECLARE filas BIGINT;
BEGIN
    SELECT count(*) INTO filas FROM client_documents;
    IF filas > 0 THEN
        RAISE EXCEPTION 'client_documents tiene % filas: no se tocan sus columnas', filas;
    END IF;
END $$;

ALTER TABLE client_documents DROP CONSTRAINT IF EXISTS client_documents_document_type_check;
ALTER TABLE client_documents DROP COLUMN IF EXISTS document_type;
ALTER TABLE client_documents DROP COLUMN IF EXISTS file_name;
ALTER TABLE client_documents DROP COLUMN IF EXISTS content_type;
ALTER TABLE client_documents DROP COLUMN IF EXISTS size_bytes;
ALTER TABLE client_documents DROP COLUMN IF EXISTS storage_key;
ALTER TABLE client_documents DROP COLUMN IF EXISTS description;
ALTER TABLE client_documents DROP COLUMN IF EXISTS uploaded_at;

COMMIT;
