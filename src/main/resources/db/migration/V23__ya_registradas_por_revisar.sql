-- =====================================================================
-- V23 - las filas ya registradas que conviene mirar
-- =====================================================================
--
-- Una fila del extracto que ya estaba apuntada como cobro no hay que revisarla:
-- ya tiene su entrada. Salvo cuando se casó solo por el importe y la fecha, sin
-- nada en el concepto que diga quién paga: esas quedan en «Para revisar» hasta
-- que alguien confirma que el cobro es el bueno.
--
-- Sin BEGIN/COMMIT: la transacción la abre y la cierra Flyway.
-- =====================================================================

ALTER TABLE bank_import_lines ADD COLUMN IF NOT EXISTS review_suggested BOOLEAN NOT NULL DEFAULT FALSE;

-- Las que ya se importaron así: su motivo pedía comprobarlas.
UPDATE bank_import_lines
   SET review_suggested = TRUE
 WHERE already_recorded = TRUE
   AND status <> 'APPLIED'
   AND reason LIKE '%compruébalo%';
