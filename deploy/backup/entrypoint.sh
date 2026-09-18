#!/bin/sh
#
# Arranque del servicio de copias: deja programada la tarea nocturna y se queda
# esperando. No hay nada que compilar ni que desplegar; es el cron de siempre,
# dentro del propio compose, para que la copia no dependa de que alguien se
# acuerde de lanzarla.
#
set -eu

CRON="${BACKUP_CRON:-30 3 * * *}"

# La salida de la tarea va al stdout del contenedor (el PID 1 es este crond),
# así que se lee con "docker compose logs backup" como la de cualquier servicio.
echo "$CRON sh /opt/backup/backup.sh > /proc/1/fd/1 2>&1" > /etc/crontabs/root
chmod 0600 /etc/crontabs/root

echo "[$(date '+%F %T')] Copias programadas: $CRON (TZ=${TZ:-UTC}), se conservan ${BACKUP_KEEP_DAYS:-14} días en ${BACKUP_DIR:-/backups}"

# La primera copia, al arrancar, cuando se pide expresamente: sirve para
# comprobar que todo está bien montado sin esperar a la madrugada.
if [ "${BACKUP_ON_START:-false}" = "true" ]; then
    echo "[$(date '+%F %T')] BACKUP_ON_START=true: copia inicial"
    sh /opt/backup/backup.sh || echo "[$(date '+%F %T')] La copia inicial falló; el cron lo reintentará esta noche"
fi

exec crond -f -l 8
