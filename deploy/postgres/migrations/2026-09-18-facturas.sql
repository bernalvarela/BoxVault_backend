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
-- Esto, al arrancar, lo hace ya ddl-auto=update: columnas nuevas, su clave
-- ajena y el índice único del número (con nombres suyos, del estilo
-- "uk4bgtvbut8seryy8kitym8sfgq"). Lo que ddl-auto NO hace nunca es tocar un
-- CHECK que ya existe, y por eso hace falta 2026-09-18-tipos-de-documento.sql:
-- sin él, guardar una FACTURA falla aunque estas columnas estén.
--
-- Este script es la red: deja la tabla como debe quedar en una base donde
-- ddl-auto no haya pasado (o esté desactivado). Es idempotente —comprueba antes
-- de crear— así que pasarlo por una base ya al día no duplica nada.
--
--     docker compose exec -T postgres pg_dump -U boxvault -Fc boxvault > antes.dump
--     docker compose exec -T postgres psql -U boxvault -d boxvault < 2026-09-18-facturas.sql
-- =====================================================================

BEGIN;

ALTER TABLE payments ADD COLUMN IF NOT EXISTS invoice_number      VARCHAR(30);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS invoiced_at         DATE;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS invoice_document_id BIGINT;

-- El PDF vive en documents, como cualquier otro adjunto. Sólo si no hay ya una
-- clave ajena sobre esa columna: dos que digan lo mismo no añaden nada.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'payments'::regclass
          AND contype = 'f'
          AND conkey = ARRAY[(SELECT attnum FROM pg_attribute
                              WHERE attrelid = 'payments'::regclass
                                AND attname = 'invoice_document_id')]
    ) THEN
        ALTER TABLE payments ADD CONSTRAINT fk_payments_invoice_document
            FOREIGN KEY (invoice_document_id) REFERENCES documents (id);
    END IF;
END $$;

-- Dos cobros no pueden compartir número de factura. El UNIQUE de PostgreSQL
-- admite varios NULL, así que los cobros sin facturar no molestan.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE tablename = 'payments' AND indexdef LIKE '%UNIQUE%(invoice_number)%'
    ) THEN
        CREATE UNIQUE INDEX uk_payments_invoice_number ON payments (invoice_number);
    END IF;
END $$;

COMMIT;
