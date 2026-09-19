-- =====================================================================
-- V5 - el inventario del piso y los gastos que asume el inquilino
-- =====================================================================
--
-- Los contratos de vivienda dicen dos cosas que hasta ahora había que escribir a
-- mano en cada plantilla, y por tanto repetir y desactualizar:
--
--   storage_units.inventory          Los muebles y enseres que hay dentro, uno
--                                    por línea. Es del PISO: el sofá sigue ahí
--                                    cuando cambia el inquilino.
--
--   rental_agreements.community_fee  La cuota de comunidad (al mes) y el IBI (al
--   rental_agreements.property_tax   año) que asume ESTE inquilino. Van en el
--                                    CONTRATO porque son una cláusula, no un
--                                    hecho del piso: el mismo piso puede
--                                    alquilarse con los gastos incluidos o con
--                                    ellos aparte. NULL = no se pactó nada.
--
-- Las plantillas los colocan con {{inventario}}, {{gastos_comunidad}} y
-- {{gastos_ibi}}.
-- =====================================================================

ALTER TABLE storage_units     ADD COLUMN IF NOT EXISTS inventory     TEXT;
ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS community_fee NUMERIC(10,2);
ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS property_tax  NUMERIC(10,2);
