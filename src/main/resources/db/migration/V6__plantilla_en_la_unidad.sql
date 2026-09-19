-- =====================================================================
-- V6 - cada unidad puede fijar con qué plantilla se hacen sus contratos
-- =====================================================================
--
-- Un piso se alquila con el contrato de vivienda y un trastero con el suyo, y
-- eso no cambia de un inquilino a otro: elegir la plantilla en cada alta era
-- acordarse de algo que la unidad ya sabe.
--
-- La resolución es, por orden: la plantilla que diga el CONTRATO, la que diga su
-- UNIDAD, la del LOCAL que la contiene -se hereda por el árbol, igual que los
-- propietarios: marcarla en el "Bajo delantero" vale para sus nueve trasteros- y
-- por último la marcada por defecto. NULL en todos = la de por defecto.
-- =====================================================================

ALTER TABLE storage_units ADD COLUMN IF NOT EXISTS contract_template_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_storage_units_template') THEN
        ALTER TABLE storage_units ADD CONSTRAINT fk_storage_units_template
            FOREIGN KEY (contract_template_id) REFERENCES contract_templates (id);
    END IF;
END $$;
