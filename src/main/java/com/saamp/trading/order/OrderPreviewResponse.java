package com.saamp.trading.order;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.OrderSide;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record OrderPreviewResponse(long orderId, Asset asset, OrderSide side, BigDecimal quantityOz,
                                   String pair, BigDecimal indicativeClientPrice, OffsetDateTime priceAsOf,
                                   OffsetDateTime expiresAt, BigDecimal reservedCash, BigDecimal reservedMetal,
                                   BigDecimal requestedQuantity, com.saamp.trading.domain.QuantityUnit requestedUnit,
                                   BigDecimal estimatedAmount) {
    /** Compatibilite des consommateurs Java historiques.
     * @param orderId ordre @param asset metal @param side sens @param quantityOz quantite canonique
     * @param pair paire @param indicativeClientPrice prix par once @param priceAsOf horodatage
     * @param expiresAt expiration @param reservedCash cash @param reservedMetal metal */
    public OrderPreviewResponse(long orderId, Asset asset, OrderSide side, BigDecimal quantityOz,
            String pair, BigDecimal indicativeClientPrice, OffsetDateTime priceAsOf,
            OffsetDateTime expiresAt, BigDecimal reservedCash, BigDecimal reservedMetal) {
        this(orderId,asset,side,quantityOz,pair,indicativeClientPrice,priceAsOf,expiresAt,reservedCash,reservedMetal,
                quantityOz,com.saamp.trading.domain.QuantityUnit.OZ,
                quantityOz.multiply(indicativeClientPrice).setScale(2,java.math.RoundingMode.HALF_UP));
    }
}
