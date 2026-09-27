-- =====================================================================
-- V12 - al cerrar un contrato: qué pasó con la fianza y el contrato de salida
-- =====================================================================
--
-- Cerrar un alquiler era poner una fecha de fin. Faltaba lo que se habla ese
-- día con el inquilino: si se le devuelve la fianza, cuánto, y por qué no
-- entera cuando no lo es. Y faltaba el papel que lo recoge -el contrato de
-- salida o finiquito-, que se compone desde una plantilla igual que el de
-- entrada.
--
-- La plantilla de salida se elige por unidad y se hereda por el árbol, como la
-- de entrada: marcarla en el "Bajo delantero" vale para sus nueve trasteros. No
-- hay una "de salida por defecto": una unidad que no diga nada usa la que trae
-- la aplicación dentro.
--
-- Sin BEGIN/COMMIT: la transacción la abre y la cierra Flyway.
-- =====================================================================

-- NULL = todavía no se ha decidido (el contrato sigue vivo, o se cerró antes de
-- que esto existiera). FALSE = se retiene entera. TRUE = se devuelve, toda o en
-- parte: cuánto lo dice deposit_returned_amount.
ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS deposit_returned BOOLEAN;
ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS deposit_returned_amount NUMERIC(10,2);
-- Por qué no se devuelve entera: desperfectos, limpieza, mensualidades pendientes.
ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS deposit_return_notes TEXT;

-- El contrato de salida generado, para poder rehacerlo sin apilar borradores.
-- ON DELETE SET NULL por lo mismo que contract_document_id (V9).
ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS exit_contract_document_id BIGINT;

ALTER TABLE storage_units ADD COLUMN IF NOT EXISTS exit_contract_template_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_rental_agreements_exit_document'
    ) THEN
        ALTER TABLE rental_agreements
            ADD CONSTRAINT fk_rental_agreements_exit_document
            FOREIGN KEY (exit_contract_document_id) REFERENCES documents (id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_storage_units_exit_template') THEN
        ALTER TABLE storage_units ADD CONSTRAINT fk_storage_units_exit_template
            FOREIGN KEY (exit_contract_template_id) REFERENCES contract_templates (id);
    END IF;
END $$;

-- El contrato de salida es un tipo de documento nuevo. La lista de la
-- comprobación es la de 01-schema.sql más CONTRATO_SALIDA.
ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_document_type_check;
ALTER TABLE documents ADD CONSTRAINT documents_document_type_check CHECK (document_type IN (
    'DNI', 'CONTRATO_TRABAJO', 'NOMINA', 'FOTO',
    'CONTRATO_ALQUILER', 'CONTRATO_SALIDA', 'ANEXO', 'FACTURA',
    'JUSTIFICANTE', 'DECLARACION', 'OTRO'));

-- ---------------------------------------------------------------------
-- La plantilla de ejemplo, puesta en el Bajo delantero
-- ---------------------------------------------------------------------
-- Aquí sólo nace la ficha. El texto va al almacén de ficheros, que desde SQL no
-- se alcanza: lo escribe la aplicación al arrancar
-- (ContractTemplateService.storeBundledExitTemplate) si en esa clave no hay
-- nada, y no lo vuelve a tocar. Así esto se hace UNA vez: si luego se desmarca
-- en el local o se borra la plantilla, no reaparece.
INSERT INTO contract_templates (name, description, storage_key, default_template, created_at, updated_at)
SELECT 'Contrato de salida (ejemplo)',
       'Finiquito del alquiler: fecha de salida, estado del trastero y devolución de la fianza.',
       'plantillas/contrato-salida-ejemplo.txt', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM contract_templates WHERE storage_key = 'plantillas/contrato-salida-ejemplo.txt');

UPDATE storage_units
SET exit_contract_template_id = (
    SELECT id FROM contract_templates WHERE storage_key = 'plantillas/contrato-salida-ejemplo.txt')
WHERE unit_number = 'BD' AND exit_contract_template_id IS NULL;
