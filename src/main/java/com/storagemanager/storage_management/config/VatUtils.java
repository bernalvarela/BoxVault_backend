package com.storagemanager.storage_management.config;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 21% VAT helpers. Prices and payments are stored as the gross amount the tenant
 * pays; for VAT-bearing units (storage units) that amount includes 21% VAT, for
 * exempt units (apartments) the whole amount is the taxable base and the VAT
 * quota is zero.
 */
public final class VatUtils {

    public static final BigDecimal VAT_RATE = new BigDecimal("0.21"); // 21% IVA
    public static final BigDecimal VAT_DIVISOR = new BigDecimal("1.21");

    private VatUtils() {}

    /** Total, taxable base and VAT quota of an amount; add up with {@link #plus}. */
    public record Breakdown(BigDecimal total, BigDecimal base, BigDecimal vat) {
        public static final Breakdown ZERO = new Breakdown(zero(), zero(), zero());

        public Breakdown plus(Breakdown other) {
            return new Breakdown(total.add(other.total), base.add(other.base), vat.add(other.vat));
        }
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Splits a gross amount into base and VAT. When {@code vatApplicable} is false
     * (VAT exempt unit) the base equals the total and the VAT quota is zero.
     */
    public static Breakdown breakdown(BigDecimal total, boolean vatApplicable) {
        if (total == null) return Breakdown.ZERO;
        BigDecimal scaled = total.setScale(2, RoundingMode.HALF_UP);
        if (!vatApplicable) {
            return new Breakdown(scaled, scaled, zero());
        }
        BigDecimal base = scaled.divide(VAT_DIVISOR, 2, RoundingMode.HALF_UP);
        return new Breakdown(scaled, base, scaled.subtract(base).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Base and VAT of an aggregated gross amount (a quarter, a year), as they are
     * declared: the base comes from the summed total, not from adding up rounded
     * per-payment bases (1740 EUR of payments gave 1437.99 that way instead of
     * 1438.02), and the quota is 21% of that base, which is how the AEAT form
     * computes [03] from [01]. Per-invoice amounts keep using {@link #breakdown}.
     */
    public static Breakdown declared(BigDecimal total, boolean vatApplicable) {
        if (total == null) return Breakdown.ZERO;
        BigDecimal scaled = total.setScale(2, RoundingMode.HALF_UP);
        if (!vatApplicable) {
            return new Breakdown(scaled, scaled, zero());
        }
        BigDecimal base = scaled.divide(VAT_DIVISOR, 2, RoundingMode.HALF_UP);
        return new Breakdown(scaled, base, base.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Base and VAT of one payment with 4 decimals, for the lines that make up a
     * declared quarter: unrounded, they add up to the base that {@link #declared}
     * takes from the quarter total, and only that total is rounded to cents.
     * Not for invoices, which are always in cents ({@link #breakdown}).
     */
    public static Breakdown precise(BigDecimal total, boolean vatApplicable) {
        if (total == null) return Breakdown.ZERO;
        BigDecimal scaled = total.setScale(2, RoundingMode.HALF_UP);
        if (!vatApplicable) {
            return new Breakdown(scaled, scaled.setScale(4), BigDecimal.ZERO.setScale(4));
        }
        BigDecimal base = scaled.divide(VAT_DIVISOR, 4, RoundingMode.HALF_UP);
        return new Breakdown(scaled, base, base.multiply(VAT_RATE).setScale(4, RoundingMode.HALF_UP));
    }

    /** Base amount of a gross amount; the whole amount when VAT does not apply. */
    public static BigDecimal calculateBaseWithoutVat(BigDecimal total, boolean vatApplicable) {
        return breakdown(total, vatApplicable).base();
    }

    /** VAT quota of a gross amount; zero when VAT does not apply. */
    public static BigDecimal calculateVatAmount(BigDecimal total, boolean vatApplicable) {
        return breakdown(total, vatApplicable).vat();
    }

    /**
     * Calculates base amount without VAT from total gross amount with 21% VAT
     */
    public static BigDecimal calculateBaseWithoutVat(BigDecimal totalWithVat) {
        return calculateBaseWithoutVat(totalWithVat, true);
    }

    /**
     * Calculates VAT quota (21%) from total gross amount
     */
    public static BigDecimal calculateVatAmount(BigDecimal totalWithVat) {
        return calculateVatAmount(totalWithVat, true);
    }
}
