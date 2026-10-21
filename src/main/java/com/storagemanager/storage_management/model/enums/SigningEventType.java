package com.storagemanager.storage_management.model.enums;

/** Lo que queda anotado en el registro de una firma (ver SigningEvent). */
public enum SigningEventType {
    /** Se congeló el PDF y se creó la lista de firmantes. */
    PREPARADA,
    /** Se pidió un enlace para que alguien firme desde su móvil. */
    ENLACE_CREADO,
    /** El firmante abrió su enlace. */
    ENLACE_ABIERTO,
    /** Tecleó un DNI que no es el suyo. Ya no se usa: queda por las firmas de antes. */
    NIF_INCORRECTO,
    /** Se mandó un código para firmar al correo del firmante. */
    CODIGO_ENVIADO,
    /** Tecleó un código que no es el que se le mandó. */
    CODIGO_INCORRECTO,
    /** Demasiados intentos incorrectos: el enlace deja de valer. */
    ENLACE_BLOQUEADO,
    /** Firmó. */
    FIRMADO,
    /** Se estamparon las firmas y se selló el PDF. */
    SELLADA,
    /** Se selló sin sello de tiempo, porque el servidor de sellos no respondió. */
    SELLO_SIN_TSA,
    /** No se pudo sellar; las firmas siguen guardadas y se puede reintentar. */
    SELLO_FALLIDO,
    /** Se canceló. */
    CANCELADA,
    /** Un firmante descargó su copia firmada. */
    COPIA_DESCARGADA,
    /** Se mandó por correo el contrato firmado a alguien. */
    CORREO_ENVIADO,
    /** No se pudo mandar por correo (o no hay servidor de correo configurado). */
    CORREO_FALLIDO
}
