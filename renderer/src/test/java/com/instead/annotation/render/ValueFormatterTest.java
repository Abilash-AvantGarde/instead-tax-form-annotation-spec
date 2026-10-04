package com.instead.annotation.render;

import com.instead.annotation.model.FieldType;
import com.instead.annotation.model.FormatSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValueFormatterTest {

    private FormatSpec currency() {
        FormatSpec f = new FormatSpec();
        f.kind = FormatSpec.Kind.currency;
        return f;
    }

    @Test
    void roundsHalfUpToWholeDollarsByDefault() {
        String result = ValueFormatter.format(new BigDecimal("140370.50"), currency(), FieldType.currency);
        assertEquals("140,371", result);
    }

    @Test
    void parenthesizesNegativeAmountsByDefault() {
        String result = ValueFormatter.format(new BigDecimal("-1250.00"), currency(), FieldType.currency);
        assertEquals("(1,250)", result);
    }

    @Test
    void usesMinusSignWhenNegativeStyleIsMinus() {
        FormatSpec f = currency();
        f.negativeStyle = "minus";
        assertEquals("-1,250", ValueFormatter.format(new BigDecimal("-1250.00"), f, FieldType.currency));
    }

    @Test
    void halfDownRoundingModeIsHonored() {
        FormatSpec f = currency();
        f.roundingMode = "half-down";
        // 0.5 rounds down to 0 under half-down, vs. 1 under the half-up default.
        assertEquals("140,370", ValueFormatter.format(new BigDecimal("140370.50"), f, FieldType.currency));
    }

    @Test
    void unknownRoundingModeFailsLoudRatherThanSilentlyFallingBack() {
        FormatSpec f = currency();
        f.roundingMode = "nearest-even-ish";
        assertThrows(IllegalArgumentException.class,
                () -> ValueFormatter.format(new BigDecimal("1.5"), f, FieldType.currency));
    }

    @Test
    void ssnMaskInsertsDashesAtTheConfiguredPositions() {
        FormatSpec f = new FormatSpec();
        f.kind = FormatSpec.Kind.ssn_mask;
        assertEquals("123-45-6789", ValueFormatter.format("123456789", f, FieldType.ssn));
    }
}
