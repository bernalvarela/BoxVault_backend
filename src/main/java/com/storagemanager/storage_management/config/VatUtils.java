package com.storagemanager.storage_management.config;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class VatUtils {

    public static final BigDecimal VAT_RATE = new BigDecimal("0.21"); // 21% IVA
    public static final BigDecimal VAT_DIVISOR = new BigDecimal("1.21");

    private VatUtils() {}

    /**
     * Calculates base amount without VAT from total gross amount with 21% VAT
     */
    public static BigDecimal calculateBaseWithoutVat(BigDecimal totalWithVat) {
        if (totalWithVat == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return totalWithVat.divide(VAT_DIVISOR, 2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates VAT quota (21%) from total gross amount
     */
    public static BigDecimal calculateVatAmount(BigDecimal totalWithVat) {
        if (totalWithVat == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal base = calculateBaseWithoutVat(totalWithVat);
        return totalWithVat.subtract(base).setScale(2, RoundingMode.HALF_UP);
    }
}
