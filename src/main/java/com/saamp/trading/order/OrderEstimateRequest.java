package com.saamp.trading.order;

import com.saamp.trading.domain.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Intention d'affichage avant preview : aucune reservation ni cle d'ordre n'est creee. */
public record OrderEstimateRequest(@NotNull Asset asset, @NotNull OrderSide side,
        @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal quantity, @NotNull QuantityUnit unit) { }
