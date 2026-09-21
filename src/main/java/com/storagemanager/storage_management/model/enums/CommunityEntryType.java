package com.storagemanager.storage_management.model.enums;

/** Qué es cada apunte del libro de una comunidad de propietarios. */
public enum CommunityEntryType {

    /** Lo que una unidad aporta cada mes, según su coeficiente. Entra dinero. */
    CUOTA(true),

    /** Un pago extraordinario aprobado en junta (una obra, el ascensor). Entra dinero. */
    DERRAMA(true),

    /**
     * Lo que la comunidad paga: el seguro del portal, la luz de la escalera, el
     * ascensor. Sale dinero.
     * <p>
     * Ojo: esto NO es gasto deducible de ningún propietario. Lo que él se
     * deduce en su IRPF es su cuota, no lo que la comunidad hace con ella; por
     * eso este libro vive fuera de la tabla de gastos.
     */
    GASTO(false),

    /** Cualquier otro ingreso: intereses, una indemnización del seguro. */
    OTRO_INGRESO(true),

    /** Cualquier otra salida que no encaje en los gastos corrientes. */
    OTRO_GASTO(false);

    private final boolean income;

    CommunityEntryType(boolean income) {
        this.income = income;
    }

    /** Si suma al saldo de la comunidad; si no, resta. */
    public boolean isIncome() {
        return income;
    }
}
