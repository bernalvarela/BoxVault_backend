-- =====================================================================
-- V21 - la cuota de la hipoteca, con sus intereses dentro
-- =====================================================================
--
-- El 3º I está hipotecado: cada mes sale del banco una cuota que mezcla
-- amortización de capital e intereses. Se apunta entera como gasto de la
-- categoría HIPOTECA (la columna category es texto, no hace falta tocarla) y,
-- como el IVA soportado, el gasto guarda la parte que son intereses: la única
-- deducible en el IRPF. Nula = no se ha dicho, y no se deduce nada.
--
-- Sin BEGIN/COMMIT: la transacción la abre y la cierra Flyway.
-- =====================================================================

ALTER TABLE expenses ADD COLUMN IF NOT EXISTS interest_amount NUMERIC(10, 2);
