#!/bin/sh
#
# Copia de seguridad de BoxVault: la base de datos y los ficheros adjuntos.
#
# Las dos cosas hacen falta. El volcado de PostgreSQL lleva clientes, contratos,
# cobros, gastos y declaraciones; los adjuntos —DNI escaneados, contratos
# firmados, justificantes de la AEAT— viven en el almacén de objetos y no salen
# en ese volcado. Una copia sin la otra deja la aplicación coja.
#
# Lo lanza cron cada noche (ver el servicio "backup" del docker-compose.yml) y
# se puede lanzar a mano cuando haga falta:
#
#     docker compose exec backup sh /opt/backup/backup.sh
#
set -eu

DIR="${BACKUP_DIR:-/backups}"
KEEP="${BACKUP_KEEP_DAYS:-14}"
STAMP="$(date +%F_%H%M)"
DB_FILE="$DIR/boxvault-db-$STAMP.dump"
FILES_FILE="$DIR/boxvault-files-$STAMP.tar.gz"

log() { echo "[$(date '+%F %T')] $*"; }

log "Copia de seguridad en $DIR (se conservan $KEEP días)"
mkdir -p "$DIR"

# --- La base de datos -------------------------------------------------------
# Se escribe con un nombre temporal y sólo al terminar bien se le pone el
# definitivo: así una copia a medias —el disco se llenó, se paró el contenedor—
# nunca se confunde con una buena.
log "Volcando la base de datos $POSTGRES_DB..."
PGPASSWORD="$POSTGRES_PASSWORD" pg_dump \
    --host="${POSTGRES_HOST:-postgres}" \
    --username="$POSTGRES_USER" \
    --format=custom \
    --file="$DB_FILE.parcial" \
    "$POSTGRES_DB"
mv "$DB_FILE.parcial" "$DB_FILE"
log "  $(du -h "$DB_FILE" | cut -f1) en $(basename "$DB_FILE")"

# --- Los ficheros adjuntos --------------------------------------------------
# El volumen se monta de sólo lectura, así que esto no puede estropear nada.
# No es atómico con el volcado de arriba: un fichero subido justo entre los dos
# puede quedar en una copia y no en la otra. Es un hueco de segundos y la
# alternativa —parar la aplicación cada noche— cuesta más de lo que arregla.
log "Empaquetando los ficheros adjuntos..."
tar czf "$FILES_FILE.parcial" -C /data .
mv "$FILES_FILE.parcial" "$FILES_FILE"
log "  $(du -h "$FILES_FILE" | cut -f1) en $(basename "$FILES_FILE")"

# --- Limpieza ---------------------------------------------------------------
# Sólo se borra lo viejo cuando las dos copias de hoy han salido bien: si algo
# falla, se acumulan copias en vez de quedarse sin ninguna. Los ".parcial" que
# haya dejado un intento fallido sí se limpian.
log "Borrando copias de más de $KEEP días..."
find "$DIR" -name 'boxvault-db-*.dump' -mtime "+$KEEP" -print -delete
find "$DIR" -name 'boxvault-files-*.tar.gz' -mtime "+$KEEP" -print -delete
find "$DIR" -name '*.parcial' -mtime +1 -print -delete

log "Hecho. Copias guardadas: $(find "$DIR" -name 'boxvault-db-*.dump' | wc -l)"
