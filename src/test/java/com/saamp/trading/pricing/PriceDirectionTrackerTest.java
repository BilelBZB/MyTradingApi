package com.saamp.trading.pricing;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.saamp.trading.pricing.PriceDirectionTracker.Direction.*;

class PriceDirectionTrackerTest {
    private final OffsetDateTime at=OffsetDateTime.now();
    @Test void changedClientSpreadStillChangesVisualDirectionOnSameMarketSnapshot() {
        var tracker=new PriceDirectionTracker();tracker.observe(1,q("10","9",0));
        assertThat(tracker.observe(1,q("11","8",0))).isEqualTo(new PriceDirectionTracker.Directions(UP,DOWN));
    }
    @Test void sidesMoveIndependentlyAndSameSnapshotKeepsItsDirection() {
        var tracker=new PriceDirectionTracker();
        assertThat(tracker.observe(1,q("10","9",0))).isEqualTo(new PriceDirectionTracker.Directions(UNCHANGED,UNCHANGED));
        var second=q("11","8",10);
        assertThat(tracker.observe(1,second)).isEqualTo(new PriceDirectionTracker.Directions(UP,DOWN));
        assertThat(tracker.observe(1,second)).isEqualTo(new PriceDirectionTracker.Directions(UP,DOWN));
        assertThat(tracker.observe(1,q("11.0","8.00",20))).isEqualTo(new PriceDirectionTracker.Directions(UNCHANGED,UNCHANGED));
        assertThat(tracker.observe(2,second)).isEqualTo(new PriceDirectionTracker.Directions(UNCHANGED,UNCHANGED));
    }
    @Test void concurrentReadersAndLateSnapshotsCannotOverwriteLatest() throws Exception {
        var tracker=new PriceDirectionTracker();tracker.observe(1,q("10","9",0));
        try(var executor=Executors.newFixedThreadPool(8)) {
            var tasks=java.util.stream.IntStream.range(0,40).<Callable<PriceDirectionTracker.Directions>>mapToObj(i->()->tracker.observe(1,q("11","8",10))).toList();
            for(var result:executor.invokeAll(tasks)) assertThat(result.get()).isEqualTo(new PriceDirectionTracker.Directions(UP,DOWN));
        }
        tracker.observe(1,q("1","1",-1));
        assertThat(tracker.observe(1,q("11","8",20))).isEqualTo(new PriceDirectionTracker.Directions(UNCHANGED,UNCHANGED));
    }
    private ClientQuote q(String buy,String sell,int seconds) {
        var b=new BigDecimal(buy);var s=new BigDecimal(sell);
        return new ClientQuote(Asset.XAG,"XAGUSD",s,b,b,s,b,s,BigDecimal.ZERO,BigDecimal.ZERO,1,at.plusSeconds(seconds));
    }
}
