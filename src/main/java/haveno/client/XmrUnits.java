package haveno.client;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Conversion between XMR and atomic units ("piconero").
 *
 * <p>The Haveno API expresses XMR amounts (offer amounts, balances, fees) as
 * atomic units in a {@code long}: 1 XMR = 10<sup>12</sup> atomic units.
 */
public final class XmrUnits {

    /** Atomic units per XMR. */
    public static final long ATOMIC_UNITS_PER_XMR = 1_000_000_000_000L;

    private static final BigDecimal PER_XMR = BigDecimal.valueOf(ATOMIC_UNITS_PER_XMR);

    private XmrUnits() {
    }

    /**
     * Converts an XMR amount to atomic units.
     *
     * @throws ArithmeticException if the amount has more than 12 decimals or doesn't fit in a long
     */
    public static long toAtomicUnits(BigDecimal xmr) {
        return xmr.multiply(PER_XMR).longValueExact();
    }

    /** Converts an XMR amount given as text, e.g. {@code "0.25"}, to atomic units. */
    public static long toAtomicUnits(String xmr) {
        return toAtomicUnits(new BigDecimal(xmr));
    }

    /** Converts atomic units to XMR, without trailing zeros. */
    public static BigDecimal toXmr(long atomicUnits) {
        return BigDecimal.valueOf(atomicUnits)
                .divide(PER_XMR, 12, RoundingMode.UNNECESSARY)
                .stripTrailingZeros();
    }
}
