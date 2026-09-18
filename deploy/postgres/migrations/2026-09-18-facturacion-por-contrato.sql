-- =====================================================================
-- BoxVault - quién factura y qué contratos facturan
-- =====================================================================
--
-- Dos columnas nuevas, las dos las añade ya ddl-auto=update al arrancar; este
-- script es la red para una base donde no esté activado.
--
--   owners.address                     El domicilio fiscal del propietario. Las
--                                      facturas y los contratos los emite la
--                                      comunidad de bienes, así que sus datos
--                                      -nombre, NIF, IBAN, domicilio- salen de
--                                      su ficha y no de la configuración del
--                                      servidor: los mantiene quien lleva la
--                                      casa, desde la propia aplicación.
--
--   rental_agreements.generates_invoices  Si de ese contrato se emite factura al
--                                      cobrar. Se deja NULL (= no) en todos los
--                                      contratos que ya existen: una factura
--                                      consume un número de serie y no se puede
--                                      deshacer, así que empiezan todos sin
--                                      facturar y se marcan uno a uno.
--
--     docker compose exec -T postgres psql -U boxvault -d boxvault < 2026-09-18-facturacion-por-contrato.sql
-- =====================================================================

BEGIN;

ALTER TABLE owners ADD COLUMN IF NOT EXISTS address VARCHAR(255);
ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS generates_invoices BOOLEAN;

COMMIT;
