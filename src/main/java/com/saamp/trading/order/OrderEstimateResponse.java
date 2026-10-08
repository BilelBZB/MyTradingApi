package com.saamp.trading.order;

import com.saamp.trading.domain.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Estimation indicative sans execution ni reservation ; le montant est calcule sur la quantite canonique. */
public record OrderEstimateResponse(Asset asset, String pair, OrderSide side, BigDecimal requestedQuantity,
        QuantityUnit requestedUnit, BigDecimal quantityOz, QuantityUnit displayUnit, BigDecimal displayQuantity,
        BigDecimal displayClientPrice, String priceUnit, BigDecimal estimatedAmount, OffsetDateTime priceAsOf) { }
