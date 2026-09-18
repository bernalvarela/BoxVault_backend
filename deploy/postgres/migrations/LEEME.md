# Migraciones antiguas, las de lanzar a mano

Los `.sql` de esta carpeta son **históricos**: se lanzaron uno a uno con `psql`
contra el servidor, en su día, y ya están aplicados. Se quedan aquí porque
cuentan por qué la base de datos es como es; no hace falta volver a pasarlos.

**Las migraciones nuevas ya no van aquí.** Van en

    src/main/resources/db/migration/V{n}__{descripcion}.sql

y las lanza **Flyway** al arrancar la aplicación: en orden, una sola vez cada
una, y anotando en la tabla `flyway_schema_history` cuáles ha aplicado. Un
despliegue ya no depende de que alguien se acuerde de pasar un fichero.

Tres cosas que conviene saber al escribir una:

- **Sin `BEGIN` / `COMMIT`**: la transacción la abre y la cierra Flyway.
- **Una migración aplicada no se toca nunca.** Flyway guarda una suma de
  comprobación de cada fichero; cambiar uno ya aplicado hace que la aplicación
  se niegue a arrancar. Lo que haya que corregir va en una migración nueva.
- **El número no se reutiliza**, aunque la migración se quede en nada.

La versión 1 no existe como fichero: es la línea de salida. `01-schema.sql` (con
`02-seed-data.sql`) crea la base la primera vez que nace su volumen, y Flyway
marca esa versión como aplicada —`baseline-on-migrate`— para seguir a partir de
la V2. Por eso una base de datos vacía de verdad, sin esos dos scripts, no
arranca: el esquema inicial no lo pone Flyway.
