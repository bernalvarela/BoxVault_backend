-- =====================================================================
-- BoxVault - facturas de las mensualidades
-- =====================================================================
--
-- Los trasteros llevan el 21 % de IVA, así que un inquilino que sea empresa
-- necesita una factura en regla. La emite la aplicación y la archiva como
-- documento del alquiler; el cobro se queda con tres datos de esa factura:
--
--   invoice_number      el número de la serie (A2026/0007). Correlativo por año
--                       y UNIQUE: una vez entregado no puede repetirse ni
--                       cambiar, que es lo que lo hace válido.
--   invoiced_at         el día en que se expidió.
--   invoice_document_id el PDF archivado. Tenerlo apuntado evita emitir dos
--                       veces la misma factura con números distintos.
--
-- ddl-auto=update añadiría estas columnas por su cuenta al arrancar, pero no el
-- UNIQUE ni el índice; este script deja la tabla como debe quedar. Se puede
-- lanzar con la aplicación en marcha: sólo añade columnas vacías.
--
--     docker compose exec -T postgres pg_dump -U boxvault -Fc boxvault > antes.dump
--     docker compose exec -T postgres psql -U boxvault -d boxvault < 2026-09-18-facturas.sql
-- =====================================================================

BEGIN;

ALTER TABLE payments ADD COLUMN IF NOT EXISTS invoice_number      VARCHAR(30);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS invoiced_at         DATE;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS invoice_document_id BIGINT;

-- El PDF vive en documents, como cualquier otro adjunto.
ALTER TABLE payments DROP CONSTRAINT IF EXISTS fk_payments_invoice_document;
ALTER TABLE payments ADD CONSTRAINT fk_payments_invoice_document
    FOREIGN KEY (invoice_document_id) REFERENCES documents (id);

-- Dos cobros no pueden compartir número de factura. El UNIQUE de PostgreSQL
-- admite varios NULL, así que los cobros sin facturar no molestan.
CREATE UNIQUE INDEX IF NOT EXISTS uk_payments_invoice_number ON payments (invoice_number);

COMMIT;
