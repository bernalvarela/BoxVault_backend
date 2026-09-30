-- =====================================================================
-- V14 - la fianza depositada en el IGVS
-- =====================================================================
--
-- En Galicia el arrendador tiene que depositar la fianza en metálico en el
-- Instituto Galego da Vivenda e Solo en el plazo de un mes desde la firma
-- (Ley 8/2012 de vivienda de Galicia y Decreto 42/2011), tanto en vivienda
-- como en uso distinto de vivienda. Al terminar el contrato se pide la
-- devolución y el IGVS la reintegra.
--
-- El trámite se hace en la sede de la Xunta (VI436A para depositar, VI436B
-- para recuperar) y no tiene API: aquí sólo se apunta lo que se hizo, para
-- que la aplicación pueda avisar de lo que falta. El justificante se archiva
-- como un documento más del alquiler.
--
-- Sin BEGIN/COMMIT: la transacción la abre y la cierra Flyway.
-- =====================================================================

-- NULL = no consta depositada.
ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS deposit_lodged_on DATE;
ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS deposit_lodged_amount NUMERIC(10,2);
-- El número de expediente o de justificante que da el IGVS.
ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS deposit_lodging_reference VARCHAR(60);
-- El IGVS emite dos justificantes y uno es para el inquilino.
ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS deposit_receipt_delivered BOOLEAN;
-- La devolución: cuándo se pidió y cuándo la reintegró el IGVS.
ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS deposit_refund_requested_on DATE;
ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS deposit_refunded_on DATE;
