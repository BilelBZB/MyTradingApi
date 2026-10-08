package com.saamp.trading.common;

import com.saamp.trading.domain.QuantityUnit;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class DisplayConversionTest {
    @Test void exactTroyDefinitionAndSignedPositions() {
        assertThat(TroyWeightConverter.fromTroyOunces(BigDecimal.ONE,QuantityUnit.G)).isEqualByComparingTo("31.1034768");
        assertThat(TroyWeightConverter.fromTroyOunces(BigDecimal.ONE.negate(),QuantityUnit.G)).isEqualByComparingTo("-31.1034768");
    }
    @ParameterizedTest @CsvSource({"KG,1", "G,1", "OZ,1", "G,0.001", "KG,1000000", "G,1000000"})
    void canonicalRoundTripHasOnlySixDecimalOunceQuantization(QuantityUnit unit, BigDecimal quantity) {
        BigDecimal roundTrip=TroyWeightConverter.fromTroyOunces(TroyWeightConverter.toTroyOunces(quantity,unit),unit);
        BigDecimal tolerance=unit==QuantityUnit.G?new BigDecimal("0.000016"):new BigDecimal("0.00000002");
        assertThat(roundTrip.subtract(quantity).abs()).isLessThanOrEqualTo(tolerance);
    }
    @ParameterizedTest @CsvSource({"G,1", "KG,1000", "OZ,31.1034768"})
    void pricesConvertWithoutChangingNotional(QuantityUnit unit, BigDecimal expected) {
        BigDecimal price=TroyWeightConverter.displayPrice(new BigDecimal("31.1034768"),unit);
        assertThat(price).isEqualByComparingTo(expected);
        BigDecimal quantity=TroyWeightConverter.fromTroyOunces(BigDecimal.ONE,unit);
        assertThat(price.multiply(quantity).subtract(new BigDecimal("31.1034768")).abs()).isLessThan(new BigDecimal("0.00001"));
    }
    @Test void roundingAndNullPricesAreExplicit() {
        assertThat(TroyWeightConverter.displayPrice(BigDecimal.ONE,QuantityUnit.G)).isEqualByComparingTo("0.03215075");
        assertThat(TroyWeightConverter.displayPrice(null,QuantityUnit.KG)).isNull();
        assertThat(TroyWeightConverter.fromTroyOunces(BigDecimal.ZERO,QuantityUnit.G)).isEqualByComparingTo("0");
    }
}
