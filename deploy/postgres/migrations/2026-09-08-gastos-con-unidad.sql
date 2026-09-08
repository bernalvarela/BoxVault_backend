-- =====================================================================
-- BoxVault - todo gasto va contra una unidad
-- =====================================================================
--
-- Hasta ahora expenses.storage_unit_id admitía nulos: un gasto "general", sin
-- unidad. Eso deja de valer, por dos razones: un gasto sin unidad no se puede
-- repartir por inmueble ni imputar en el IRPF, y con el ámbito por unidades
-- tampoco se sabría a quién le toca verlo (no cuelga de ninguna, así que no lo
-- vería nadie salvo quien lo ve todo).
--
-- La aplicación ya lo exige al crear y al editar. Esto es para la base de datos
-- que ya está en marcha; hay que lanzarlo a mano, con la aplicación parada y una
-- copia reciente hecha:
--
--     docker compose exec -T postgres pg_dump -U boxvault -Fc boxvault > antes.dump
--     docker compose stop boxvault
--     docker compose exec -T postgres psql -U boxvault -d boxvault < 2026-09-08-gastos-con-unidad.sql
--     docker compose start boxvault
--
-- Si hay gastos sin unidad, esto NO los inventa: se para y los enseña, para que
-- se decida a qué unidad va cada uno. Que un script reparta gasto por su cuenta
-- sería peor que el problema.
-- =====================================================================

BEGIN;

DO $$
DECLARE
    huerfanos BIGINT;
BEGIN
    SELECT count(*) INTO huerfanos FROM expenses WHERE storage_unit_id IS NULL;
    IF huerfanos > 0 THEN
        RAISE EXCEPTION 'Hay % gasto(s) sin unidad. Asígnales una antes de seguir: %',
            huerfanos,
            (SELECT string_agg(format('#%s %s (%s, %s €)', id, description, expense_date, amount), '; ')
             FROM expenses WHERE storage_unit_id IS NULL);
    END IF;
END $$;

ALTER TABLE expenses ALTER COLUMN storage_unit_id SET NOT NULL;

COMMIT;

-- Para ver qué gastos habría que arreglar antes de lanzarlo:
--     SELECT id, expense_date, amount, category, description
--     FROM expenses WHERE storage_unit_id IS NULL ORDER BY expense_date;
--
-- Y para asignarlos:
--     UPDATE expenses SET storage_unit_id = <id de la unidad> WHERE id = <id del gasto>;
