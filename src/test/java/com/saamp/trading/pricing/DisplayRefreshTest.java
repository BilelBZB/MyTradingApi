package com.saamp.trading.pricing;

import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.provider.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisplayRefreshTest {
    @Test void concurrentRequestsFetchOnceAndNeverReturnAnOldSnapshot() throws Exception {
        var repository=mock(PricingRepository.class);var provider=mock(TradingProvider.class);
        var state=new AtomicReference<>(new MarketPrice("XAGUSD",BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ONE,
                OffsetDateTime.now().minusSeconds(11),"test",OffsetDateTime.now()));
        when(repository.findMarketPrice("XAGUSD")).thenAnswer(call->Optional.of(state.get()));
        doAnswer(call->{state.set(call.getArgument(0));return null;}).when(repository).upsertMarketPrice(any());
        when(provider.sourceName()).thenReturn("TEST_ONLY");
        when(provider.fetchSpotRates(Set.of("XAGUSD"))).thenAnswer(call->List.of(new MarketQuote("XAGUSD",BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ONE,OffsetDateTime.now())));
        var config=new TradingProperties();config.getPricing().setDisplayMaxAge(java.time.Duration.ofMinutes(5));
        var refresh=new MarketDataRefreshService(provider,new MarketPriceService(repository,config));
        try(var pool=Executors.newFixedThreadPool(8)) {
            var tasks=java.util.stream.IntStream.range(0,24).<Callable<MarketPrice>>mapToObj(i->()->refresh.freshForDisplay("XAGUSD")).toList();
            for(var future:pool.invokeAll(tasks)) assertThat(future.get().priceAsOf()).isAfter(OffsetDateTime.now().minusSeconds(10));
        }
        verify(provider,times(1)).fetchSpotRates(Set.of("XAGUSD"));
        verify(provider,never()).submitSpotOrder(any());
    }
    @Test void failedRefreshIsCoalescedWithoutReturningStalePrices() {
        var repository=mock(PricingRepository.class);var provider=mock(TradingProvider.class);
        when(repository.findMarketPrice(anyString())).thenReturn(Optional.empty());
        when(provider.fetchSpotRates(anySet())).thenThrow(new IllegalStateException("TEST_UNAVAILABLE"));
        var refresh=new MarketDataRefreshService(provider,new MarketPriceService(repository,new TradingProperties()));
        for(int i=0;i<10;i++) assertThatThrownBy(()->refresh.freshForDisplay("XAGUSD")).hasMessage("TEST_UNAVAILABLE");
        verify(provider,times(1)).fetchSpotRates(Set.of("XAGUSD"));
    }
}
