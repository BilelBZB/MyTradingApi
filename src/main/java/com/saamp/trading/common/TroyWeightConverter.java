package com.saamp.trading.common;

import com.saamp.trading.domain.QuantityUnit;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Converts user-facing weights to the canonical troy-ounce representation. */
public final class TroyWeightConverter {

    public static final BigDecimal GRAMS_PER_TROY_OUNCE = new BigDecimal("31.1034768");
    private static final int CANONICAL_SCALE = 6;

    private TroyWeightConverter() {
    }

    /** @param currency devise effective @param unit unite visuelle @return libelle du prix */
    public static String priceUnit(String currency, QuantityUnit unit) {
        return currency == null ? null : currency + "/" + unit.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Convertit une quantite signee pour affichage, sans modifier la quantite canonique.
     * @param ounces quantite canonique, eventuellement nulle @param unit unite d'affichage
     * @return quantite a huit decimales, arrondie HALF_UP */
    public static BigDecimal fromTroyOunces(BigDecimal ounces, QuantityUnit unit) {
        if (ounces == null) return null;
        return ounces.multiply(unitsPerOunce(unit)).setScale(8, RoundingMode.HALF_UP);
    }

    /** Convertit un prix publie par once, exclusivement pour presentation.
     * @param pricePerOz prix canonique nullable @param unit unite d'affichage
     * @return prix a huit decimales HALF_UP, ou prix historique intact en OZ */
    public static BigDecimal displayPrice(BigDecimal pricePerOz, QuantityUnit unit) {
        if (pricePerOz == null || unit == QuantityUnit.OZ) return pricePerOz;
        return pricePerOz.divide(unitsPerOunce(unit), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal unitsPerOunce(QuantityUnit unit) {
        return switch (java.util.Objects.requireNonNull(unit)) {
            case OZ -> BigDecimal.ONE;
            case G -> GRAMS_PER_TROY_OUNCE;
            case KG -> GRAMS_PER_TROY_OUNCE.movePointLeft(3);
        };
    }

    public static BigDecimal toTroyOunces(BigDecimal quantity, QuantityUnit unit) {
        if (quantity == null || unit == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Quantity and unit are required and quantity must be positive");
        }
        return switch (unit) {
            case OZ -> quantity.setScale(CANONICAL_SCALE, RoundingMode.HALF_UP);
            case G -> quantity.divide(GRAMS_PER_TROY_OUNCE, CANONICAL_SCALE, RoundingMode.HALF_UP);
            case KG -> quantity.multiply(new BigDecimal("1000"))
                    .divide(GRAMS_PER_TROY_OUNCE, CANONICAL_SCALE, RoundingMode.HALF_UP);
        };
    }
}
