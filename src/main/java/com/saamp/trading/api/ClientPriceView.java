package com.saamp.trading.api;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Client-safe price view; raw provider Bid/Ask is deliberately absent. */
public record ClientPriceView(Asset asset, String pair, BigDecimal buyPrice, BigDecimal sellPrice, OffsetDateTime priceAsOf,
        String displayPair, com.saamp.trading.domain.QuantityUnit displayUnit, String priceUnit,
        com.saamp.trading.pricing.PriceDirectionTracker.Direction buyDirection,
        com.saamp.trading.pricing.PriceDirectionTracker.Direction sellDirection) {}
