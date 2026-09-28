-- =====================================================================
-- V13 - la baja dada con antelación
-- =====================================================================
--
-- El inquilino avisa hoy de que se va dentro de dos meses. El contrato se
-- finaliza ya -la fecha, la fianza, el contrato de salida- pero hasta ese día
-- sigue en vigor: se le cobra y la unidad sigue ocupada. Lo que lo distingue
-- de uno en vigor sin más es esta fecha, la del aviso. Cuando llega su fecha de
-- fin, la aplicación lo pasa a terminado y libera la unidad
-- (RentalClosingService).
--
-- Sin BEGIN/COMMIT: la transacción la abre y la cierra Flyway.
-- =====================================================================

ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS termination_notice_date DATE;

-- Los que ya se finalizaron con una fecha de fin por venir vuelven a estar en
-- vigor, con la baja dada. Se toma como fecha del aviso la de su última
-- modificación, que es cuando se finalizaron. Sólo si la unidad no tiene otro
-- contrato en vigor: dos a la vez sobre la misma unidad no puede ser.
UPDATE rental_agreements r
SET status = 'ACTIVE',
    termination_notice_date = COALESCE(CAST(r.updated_at AS DATE), CURRENT_DATE)
WHERE r.status = 'TERMINATED'
  AND r.end_date > CURRENT_DATE
  AND NOT EXISTS (
      SELECT 1 FROM rental_agreements o
      WHERE o.storage_unit_id = r.storage_unit_id AND o.id <> r.id AND o.status = 'ACTIVE');

UPDATE storage_units u
SET status = 'OCCUPIED'
WHERE u.status = 'AVAILABLE'
  AND EXISTS (
      SELECT 1 FROM rental_agreements r
      WHERE r.storage_unit_id = u.id AND r.status = 'ACTIVE' AND r.termination_notice_date IS NOT NULL);
