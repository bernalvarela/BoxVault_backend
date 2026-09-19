-- =====================================================================
-- V8 - el municipio del propietario
-- =====================================================================
--
-- Un contrato empieza por "En A Coruña, a 1 de agosto de 2023", y para eso hace
-- falta el municipio suelto, no la dirección entera. Hasta ahora {{lugar}} sólo
-- se podía rellenar por variable de entorno, así que en la práctica salía como
-- un hueco (..........) y había que escribir el municipio a mano en cada
-- plantilla; con dos propiedades en pueblos distintos eso no se sostiene.
--
-- Ahora sale de la ficha del propietario, como su NIF y su IBAN. La variable de
-- entorno se queda de respaldo para una instalación sin propietarios cargados.
-- =====================================================================

ALTER TABLE owners ADD COLUMN IF NOT EXISTS city VARCHAR(120);
