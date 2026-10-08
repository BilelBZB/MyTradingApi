package com.saamp.trading.pricing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;

/** Memoire bornee par societe/paire ; ne participe jamais au prix, risque ou execution. */
public final class PriceDirectionTracker {
    public enum Direction { UP, DOWN, UNCHANGED }
    public record Directions(Direction buyDirection, Direction sellDirection) { }
    private record Key(long company, String pair) { }
    private record Observation(BigDecimal buy, BigDecimal sell, OffsetDateTime at, Directions directions) { }
    private final LinkedHashMap<Key,Observation> observations = new LinkedHashMap<>(16,0.75f,true);
    /** @param company societe qui determine le spread @param quote prix canoniques observes
     * @return mouvements independants, stables pour un meme snapshot ; premier snapshot neutre */
    public synchronized Directions observe(long company, ClientQuote quote) {
        var key = new Key(company,quote.pair());
        var old = observations.get(key);
        if (old != null && quote.priceAsOf().isBefore(old.at())) {
            return new Directions(Direction.UNCHANGED,Direction.UNCHANGED);
        }
        if (old != null && quote.priceAsOf().isEqual(old.at())
                && quote.clientBuyPrice().compareTo(old.buy())==0 && quote.clientSellPrice().compareTo(old.sell())==0) {
            return old.directions();
        }
        var directions = old == null ? new Directions(Direction.UNCHANGED,Direction.UNCHANGED)
                : new Directions(compare(quote.clientBuyPrice(),old.buy()),compare(quote.clientSellPrice(),old.sell()));
        observations.put(key,new Observation(quote.clientBuyPrice(),quote.clientSellPrice(),quote.priceAsOf(),directions));
        if (observations.size()>4096) observations.remove(observations.keySet().iterator().next());
        return directions;
    }
    private static Direction compare(BigDecimal now, BigDecimal before) {
        int result=now.compareTo(before);
        return result>0?Direction.UP:result<0?Direction.DOWN:Direction.UNCHANGED;
    }
}
