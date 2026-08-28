package com.storagemanager.storage_management.model.enums;

/**
 * What kind of rentable unit a {@code StorageUnit} row represents. Storage units
 * (trasteros) are rented with 21% VAT included in the price; residential
 * apartments are VAT exempt. Apartments will gain their own attributes over time.
 */
public enum UnitKind {
    STORAGE_UNIT(true),
    APARTMENT(false);

    private final boolean vatApplicable;

    UnitKind(boolean vatApplicable) {
        this.vatApplicable = vatApplicable;
    }

    /** Whether prices of this kind of unit carry VAT. */
    public boolean isVatApplicable() {
        return vatApplicable;
    }
}
