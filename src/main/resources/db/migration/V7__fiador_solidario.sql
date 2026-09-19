-- =====================================================================
-- V7 - el fiador solidario del contrato
-- =====================================================================
--
-- Algunos contratos de vivienda llevan quien avale a los inquilinos. Es una
-- ficha de cliente como las demás -nombre, NIF, teléfono, y suele repetirse
-- entre contratos- pero NO alquila nada: no se le generan mensualidades ni
-- aparece como titular. Sólo responde si los inquilinos no pagan, y por eso
-- sale en su cláusula del contrato ({{fiador}}).
--
-- NULL = el contrato no lleva fiador, y entonces su cláusula desaparece del PDF:
-- la plantilla la envuelve en [[si:fiador]] ... [[fin]].
-- =====================================================================

ALTER TABLE rental_agreements ADD COLUMN IF NOT EXISTS guarantor_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_rental_agreements_guarantor') THEN
        ALTER TABLE rental_agreements ADD CONSTRAINT fk_rental_agreements_guarantor
            FOREIGN KEY (guarantor_id) REFERENCES clients (id);
    END IF;
END $$;
