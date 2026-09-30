-- =====================================================================
-- V17 - la fianza, con su depósito de garantía y sus justificantes del IGVS
-- =====================================================================
--
-- En un piso el inquilino entrega dos cosas al firmar: la fianza legal (una
-- mensualidad, que se deposita en el IGVS) y un depósito de garantía
-- adicional que guardan los propietarios. security_deposit sigue siendo el
-- total entregado -lo que se devuelve al terminar-, y guarantee_deposit dice
-- qué parte de ese total es garantía. La fianza legal es la diferencia.
--
-- NULL = no se ha separado. No se separa aquí sola: en el 3º izquierda la
-- renta estaba a 590 € (con la comunidad y el IBI dentro), y una separación
-- automática por "una mensualidad" daría 590 + 530 en vez de 560 + 560. La
-- pantalla de la fianza lo propone y se confirma a mano.
--
-- Y los justificantes del IGVS pasan a ser tipos de documento propios, para
-- poder enseñarlos en la pestaña de la fianza además de en la de documentos.
--
-- Sin BEGIN/COMMIT: la transacción la abre y la cierra Flyway.
-- =====================================================================

ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS guarantee_deposit NUMERIC(10,2);

ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_document_type_check;
ALTER TABLE documents ADD CONSTRAINT documents_document_type_check CHECK (document_type IN (
    'DNI', 'CONTRATO_TRABAJO', 'NOMINA', 'FOTO',
    'CONTRATO_ALQUILER', 'CONTRATO_SALIDA', 'ANEXO', 'FACTURA',
    'IGVS_ARRENDADOR', 'IGVS_ARRENDATARIO', 'IGVS_DEVOLUCION',
    'JUSTIFICANTE', 'DECLARACION', 'OTRO'));
