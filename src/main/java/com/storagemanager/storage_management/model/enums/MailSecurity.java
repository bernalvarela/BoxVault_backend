package com.storagemanager.storage_management.model.enums;

/**
 * Cómo se protege la conexión con el servidor de correo.
 * STARTTLS: se conecta en claro y se cifra en seguida (puerto 587; Gmail, Outlook).
 * SSL: cifrada desde el principio (puerto 465).
 * NINGUNA: sin cifrar; sólo para un servidor de la propia red.
 */
public enum MailSecurity {
    STARTTLS,
    SSL,
    NINGUNA
}
