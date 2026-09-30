-- =====================================================================
-- V16 - los gastos que paga el inquilino, al mes y dentro de la mensualidad
-- =====================================================================
--
-- El contrato guardaba la cuota de comunidad al mes y el IBI al año, pero
-- ninguno de los dos se cobraba: la mensualidad era solo la renta. Para cobrar
-- 560 € de renta + 20 € de comunidad + 10 € de IBI había que poner 590 € de
-- renta, y entonces el contrato generado decía una renta que no se pactó y la
-- fianza del IGVS se calculaba sobre ella.
--
-- Desde ahora los dos gastos son mensuales y cada mensualidad es la renta más
-- los gastos (RentalAgreement.getMonthlyCharge). El IBI que hubiera guardado
-- estaba en euros al año -así lo pedía el formulario-, y aquí pasa a euros al
-- mes. Los cobros ya registrados no se tocan: llevan su propio importe.
--
-- Sin BEGIN/COMMIT: la transacción la abre y la cierra Flyway.
-- =====================================================================

UPDATE rental_agreements
SET property_tax = ROUND(property_tax / 12, 2)
WHERE property_tax IS NOT NULL;
