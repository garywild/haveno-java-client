package haveno.client;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class XmrUnitsTest {

    @Test
    void convertsBothWays() {
        assertEquals(1_000_000_000_000L, XmrUnits.toAtomicUnits("1"));
        assertEquals(250_000_000_000L, XmrUnits.toAtomicUnits("0.25"));
        assertEquals(1L, XmrUnits.toAtomicUnits("0.000000000001"));
        assertEquals(new BigDecimal("0.25"), XmrUnits.toXmr(250_000_000_000L));
        assertEquals(new BigDecimal("1.5"), XmrUnits.toXmr(1_500_000_000_000L));
    }

    @Test
    void rejectsMoreThanTwelveDecimals() {
        assertThrows(ArithmeticException.class, () -> XmrUnits.toAtomicUnits("0.0000000000001"));
    }
}
