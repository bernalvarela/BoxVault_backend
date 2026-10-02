-- =====================================================================
-- V19 - la importación bancaria, ajustada a un extracto real de BBVA
-- =====================================================================
--
-- Con el primer csv de verdad de la cuenta de los trasteros salieron tres
-- cosas que el diseño no recogía:
--
--   1. Cada movimiento trae su identificador único (la columna "Remesa":
--      ES0182...). Es mejor que la huella para saber si un movimiento ya se
--      importó, y no depende de que el concepto se escriba igual.
--   2. Hay pagos de varias mensualidades de una vez: "Mes de septiembre y
--      octubre trastero 6" por 110 €. Una fila tiene que poder cobrar varios
--      meses seguidos.
--   3. Quien paga casi nunca pone su nombre, sino el número del trastero; eso
--      se resuelve en el código, sin tocar la base.
--
-- Sin BEGIN/COMMIT: la transacción la abre y la cierra Flyway.
-- =====================================================================

-- La columna del identificador del movimiento, si el banco lo da. NULL = no lo da.
ALTER TABLE bank_import_profiles ADD COLUMN IF NOT EXISTS reference_column INTEGER;

-- El identificador del movimiento, para enseñarlo y para no importarlo dos veces.
ALTER TABLE bank_import_lines ADD COLUMN IF NOT EXISTS bank_reference VARCHAR(60);

-- Cuántas mensualidades seguidas cubre un cobro, desde period_year/period_month.
ALTER TABLE bank_import_lines ADD COLUMN IF NOT EXISTS period_count INTEGER NOT NULL DEFAULT 1;
