package com.saamp.trading.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.saamp.trading.common.TroyWeightConverter;
import com.saamp.trading.domain.*;
import com.saamp.trading.order.OrderPreviewResponse;
import com.saamp.trading.statement.StatementLine;
import java.math.BigDecimal;

/** Projections additives : les champs historiques et les montants financiers restent canoniques. */
public final class DisplayViews {
    private DisplayViews() { }

    /** @param pair paire canonique @return paire lisible sans inference frontend */
    public static String pair(String pair) { return pair == null ? null : pair.substring(0,3) + "-" + pair.substring(3); }
    /** @param currency devise effective @param unit unite d'affichage @return unite explicite du prix */
    public static String priceUnit(String currency, QuantityUnit unit) { return TroyWeightConverter.priceUnit(currency, unit); }
    private static String currency(String pair) { return pair == null ? null : pair.substring(pair.length()-3); }

    /** Prix canonique historique et prix d'affichage sont distincts pour eviter une reinterpretation. */
    public record Position(@JsonUnwrapped PositionView canonical, String pair, QuantityUnit displayUnit,
            BigDecimal displayQuantity, BigDecimal displayClientPrice, String priceUnit) { }
    /** La devise ne subit aucune conversion de poids ; displayUnit est null pour ces lignes. */
    public record Balance(@JsonUnwrapped BalanceView canonical, QuantityUnit displayUnit,
            BigDecimal displayQuantity, BigDecimal displayAvailable) { }
    /** Historique converti uniquement depuis les prix et quantites persistes. */
    public record Order(@JsonUnwrapped OrderView canonical, String displayPair, QuantityUnit displayUnit,
            BigDecimal displayQuantity, BigDecimal displayClientPrice, BigDecimal displayIndicativeClientPrice,
            String priceUnit) { }
    /** Preview fige : l'affichage ne change ni montant estime, ni expiration. */
    public record Preview(@JsonUnwrapped OrderPreviewResponse canonical, String displayPair,
            QuantityUnit displayUnit, BigDecimal displayQuantity, BigDecimal displayClientPrice, String priceUnit) { }
    /** Projection du ledger ; delta et balanceAfter historiques sont conserves. */
    public record Statement(@JsonUnwrapped StatementLine canonical, QuantityUnit displayUnit,
            BigDecimal displayDelta, BigDecimal displayBalanceAfter) { }
    /** La synthese monetaire est independante de l'unite. */
    public record Summary(@JsonUnwrapped AccountSummaryView canonical, QuantityUnit displayUnit) { }

    /** @param v position canonique @param currency devise du compte @param u unite @return vue additive */
    public static Position position(PositionView v, Asset currency, QuantityUnit u) {
        return new Position(v, v.asset()+"-"+currency, u, TroyWeightConverter.fromTroyOunces(v.quantityOz(),u),
                TroyWeightConverter.displayPrice(v.clientPrice(),u), priceUnit(currency.name(),u));
    }
    /** @param v solde canonique @param u unite @return vue sans conversion des devises */
    public static Balance balance(BalanceView v, QuantityUnit u) {
        return new Balance(v, v.asset().isMetal()?u:null,
                v.asset().isMetal()?TroyWeightConverter.fromTroyOunces(v.balance(),u):v.balance(),
                v.asset().isMetal()?TroyWeightConverter.fromTroyOunces(v.available(),u):v.available());
    }
    /** @param v ordre persiste @param u unite @return vue additive */
    public static Order order(OrderView v, QuantityUnit u) {
        return new Order(v,pair(v.pair()),u,TroyWeightConverter.fromTroyOunces(v.quantityOz(),u),
                TroyWeightConverter.displayPrice(v.clientPrice(),u),TroyWeightConverter.displayPrice(v.indicativeClientPrice(),u),priceUnit(currency(v.pair()),u));
    }
    /** @param v preview persiste @param u unite @return vue additive */
    public static Preview preview(OrderPreviewResponse v, QuantityUnit u) {
        return new Preview(v,pair(v.pair()),u,TroyWeightConverter.fromTroyOunces(v.quantityOz(),u),
                TroyWeightConverter.displayPrice(v.indicativeClientPrice(),u),priceUnit(currency(v.pair()),u));
    }
    /** @param v ligne immuable @param u unite @return vue sans conversion des devises */
    public static Statement statement(StatementLine v, QuantityUnit u) {
        return new Statement(v,v.asset().isMetal()?u:null,
                v.asset().isMetal()?TroyWeightConverter.fromTroyOunces(v.delta(),u):v.delta(),
                v.asset().isMetal()?TroyWeightConverter.fromTroyOunces(v.balanceAfter(),u):v.balanceAfter());
    }
}
