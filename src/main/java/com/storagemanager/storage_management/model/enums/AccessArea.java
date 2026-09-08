package com.storagemanager.storage_management.model.enums;

/**
 * Las partes de la aplicación sobre las que se dan permisos. Se corresponden una
 * a una con las entradas del menú, de modo que "no tiene permiso de lectura" y
 * "no le sale el menú" son la misma cosa (ver AuthController.me y Navbar.jsx).
 * <p>
 * PANEL no está: el panel enseña los totales de lo que ya se puede ver, así que
 * no se concede aparte.
 */
public enum AccessArea {
    /** Unidades: trasteros, apartamentos y los locales que los contienen. */
    UNIDADES,
    /** Clientes y los documentos de su ficha. */
    CLIENTES,
    /** Alquileres (contratos) y sus documentos. */
    ALQUILERES,
    /** Mensualidades: cargos y cobros. */
    PAGOS,
    /** Gastos imputados a una unidad. */
    GASTOS,
    /** Impuestos: 303, 184 e IRPF, y las declaraciones registradas. */
    IMPUESTOS,
    /** Propietarios y sus participaciones en las unidades. */
    PROPIETARIOS,
    /** Los propios usuarios de la aplicación, sus perfiles y sus permisos. */
    USUARIOS
}
