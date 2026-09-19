-- =====================================================================
-- V9 - el alquiler recuerda cuál es su contrato generado
-- =====================================================================
--
-- El contrato en PDF se compone desde la plantilla y es un borrador: mientras
-- no esté firmado se puede rehacer tantas veces como el inquilino pida un
-- cambio. Hasta ahora cada intento se quedaba archivado, y al cabo de tres
-- correcciones el alquiler tenía cuatro contratos parecidos sin forma de saber
-- cuál era el bueno; el riesgo real es firmar el que no era.
--
-- Con esta columna el alquiler apunta a su borrador vigente: al rehacerlo, el
-- anterior se borra y queda uno solo. La copia FIRMADA que se sube a mano es
-- otro documento y esto no la toca: ahí no apunta nadie y no se borra nunca.
--
-- ON DELETE SET NULL: si alguien borra el borrador desde la pantalla de
-- documentos, el alquiler se queda sin contrato generado, que es la verdad.
--
-- Sin BEGIN/COMMIT: la transacción la abre y la cierra Flyway.
-- =====================================================================

ALTER TABLE rental_agreements
    ADD COLUMN IF NOT EXISTS contract_document_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_rental_agreements_contract_document'
    ) THEN
        ALTER TABLE rental_agreements
            ADD CONSTRAINT fk_rental_agreements_contract_document
            FOREIGN KEY (contract_document_id) REFERENCES documents (id) ON DELETE SET NULL;
    END IF;
END $$;
