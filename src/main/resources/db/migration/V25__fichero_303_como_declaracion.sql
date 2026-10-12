-- =====================================================================
-- V25 - el fichero .303 generado es la declaración, no "otro" documento
-- =====================================================================
--
-- Al presentar un trimestre, la aplicación genera el fichero para importar en
-- la Sede y lo archiva en la declaración. Se guardaba como OTRO; es la
-- declaración tal como se presentó, así que va como DECLARACION.
--
-- Sólo los que generó la aplicación: los que cuelgan de una declaración y se
-- llaman como ella los nombra (303-2026-1T.303, 303-2026-1T-rectificativa.303).
-- Lo que se subiera a mano como "otro" se queda como estaba.
--
-- Sin BEGIN/COMMIT: la transacción la abre y la cierra Flyway.
-- =====================================================================

UPDATE documents
   SET document_type = 'DECLARACION'
 WHERE document_type = 'OTRO'
   AND file_name LIKE '303-%.303'
   AND id IN (SELECT document_id FROM tax_filing_documents);
