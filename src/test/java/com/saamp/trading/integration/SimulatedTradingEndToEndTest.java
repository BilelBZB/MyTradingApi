package com.saamp.trading.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saamp.trading.account.AccountRepository;
import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.common.ClientOrderIdFactory;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.LedgerEntryType;
import com.saamp.trading.domain.OrderStatus;
import com.saamp.trading.ledger.LedgerService;
import com.saamp.trading.order.ExecutionEventHandler;
import com.saamp.trading.order.OrderRepository;
import com.saamp.trading.order.PendingUnknownResolver;
import com.saamp.trading.pricing.MarketPrice;
import com.saamp.trading.pricing.PricingRepository;
import com.saamp.trading.pricing.PricingService;
import com.saamp.trading.provider.AcknowledgementState;
import com.saamp.trading.provider.ExecutionReport;
import com.saamp.trading.provider.ExecutionState;
import com.saamp.trading.provider.OrderAcknowledgement;
import com.saamp.trading.provider.SimulatedTradingProvider;
import com.saamp.trading.provider.SpotOrderRequest;
import com.saamp.trading.provider.TradingProvider;
import com.saamp.trading.reservation.ReservationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Vérifie le parcours Trading V1 complet avec PostgreSQL locale et le fournisseur simulé. */
@SpringBootTest(properties={
        "trading.provider.mode=SIMULATED",
        "trading.as400.enabled=false",
        "trading.as400.jdbc-url=",
        "trading.reconciliation.enabled=false"
})
@AutoConfigureMockMvc
@Import({SimulatedTradingEndToEndTest.ProviderConfiguration.class,com.saamp.trading.support.LocalPostgres.Context.class})
class SimulatedTradingEndToEndTest {
    // Empêche tout batch automatique de modifier l'environnement local pendant ces scénarios explicites.
    @org.springframework.test.context.bean.override.mockito.MockitoBean(
            name="org.springframework.context.annotation.internalScheduledAnnotationProcessor")
    org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor scheduling;


    private static final AtomicLong COMPANY_IDS = new AtomicLong(System.currentTimeMillis());
    private static final long USER_ID = 7001L;
    private static final String ORDER_WRITE = "MYTRADING_ORDER_WRITE";
    private static final String ACCOUNT_READ = "MYTRADING_ACCOUNT_READ";
    private static final String HISTORY_READ = "MYTRADING_HISTORY_READ";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerService ledger;
    @Autowired BalanceRepository balances;
    @Autowired ReservationRepository reservations;
    @Autowired OrderRepository orders;
    @Autowired AccountRepository accounts;
    @Autowired PricingService pricing;
    @Autowired ExecutionEventHandler events;
    @Autowired ScenarioSimulatedTradingProvider provider;

    @MockitoBean PendingUnknownResolver scheduledResolver;

    private long companyId;
    private long accountId;
    private PendingUnknownResolver resolver;
    private Optional<MarketPrice> previousXauEur;

    @BeforeEach
    void createIsolatedAccount() {
        previousXauEur = readXauEur();
        companyId = COMPANY_IDS.incrementAndGet();
        accountId = jdbc.queryForObject("""
                INSERT INTO trading_account(company_id,base_currency,status)
                VALUES (?,'EUR','ACTIVE') RETURNING id
                """, Long.class, companyId);
        jdbc.update("""
                INSERT INTO trading_spread(company_id,asset,spread_buy,spread_sell,config_version)
                VALUES (?,'XAU',0.010000,0.010000,1)
                """, companyId);
        setMarketPrice("99.000000", "100.000000");
        ledger.post(accountId, Asset.EUR, new BigDecimal("100000.000000"), LedgerEntryType.ADJUSTMENT,
                null, null, "test:e2e-fixture");
        ledger.post(accountId, Asset.XAU, new BigDecimal("100.000000"), LedgerEntryType.ADJUSTMENT,
                null, null, "test:e2e-fixture");
        provider.reset();
        // Le batch du scénario ne doit ni traiter ni être affamé par la file historique locale.
        var scenarioOrders = new OrderRepository(jdbc) {
            @Override public java.util.List<com.saamp.trading.order.TradingOrder> findPendingUnknownDue(int limit) {
                return jdbc.queryForList("""
                        SELECT id FROM trading_order WHERE account_id=? AND
                        ((status='PENDING_UNKNOWN' AND next_resolution_at<=NOW()) OR
                         (status='PENDING' AND submitted_at<=NOW()-INTERVAL '10 seconds'))
                        ORDER BY COALESCE(next_resolution_at,submitted_at) LIMIT ?
                        """, Long.class, accountId, limit).stream()
                        .map(id -> orders.findById(id).orElseThrow()).toList();
            }
        };
        resolver = new PendingUnknownResolver(scenarioOrders, accounts, provider, pricing, events);
    }

    @AfterEach
    void restoreXauEurMarketPrice() {
        if (previousXauEur == null) {
            return;
        }
        if (previousXauEur.isEmpty()) {
            jdbc.update("DELETE FROM trading_market_price WHERE pair='XAUEUR'");
            assertThat(readXauEur()).isEmpty();
            return;
        }
        MarketPrice price = previousXauEur.orElseThrow();
        jdbc.update("""
                INSERT INTO trading_market_price(pair,bid,ask,mid,price_as_of,source,updated_at)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (pair) DO UPDATE SET bid=EXCLUDED.bid,ask=EXCLUDED.ask,mid=EXCLUDED.mid,
                  price_as_of=EXCLUDED.price_as_of,source=EXCLUDED.source,updated_at=EXCLUDED.updated_at
                """, price.pair(), price.bid(), price.ask(), price.mid(), price.priceAsOf(), price.source(),
                price.updatedAt());
        assertThat(readXauEur()).contains(price);
    }

    @Test
    void completeTradingScenarioPreservesRuleBIdempotenceAndLedgerProjection() throws Exception {
        assertThat(accounts.findById(accountId).orElseThrow().status().name()).isEqualTo("ACTIVE");
        assertBalance(Asset.EUR, "100000.000000");
        assertBalance(Asset.XAU, "100.000000");
        assertThat(ledgerCount(null)).isEqualTo(2);
        getWithPermissions("/api/v1/accounts/me/balances", ACCOUNT_READ)
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
        getWithPermissions("/api/v1/accounts/me/summary", ACCOUNT_READ)
                .andExpect(status().isOk()).andExpect(jsonPath("$.risk.totalFunds").value(100000.00));

        long acceptedBuy = preview("BUY", "10", "buy-accepted");
        assertThat(activeReservation(acceptedBuy, Asset.EUR)).isEqualByComparingTo("1012.020000");
        submit(acceptedBuy).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FILLED"));
        assertSettled(acceptedBuy, "CONSUMED", 2);
        assertThat(outboxCount(acceptedBuy)).isEqualTo(1);
        assertBalance(Asset.EUR, "98990.000000");
        assertBalance(Asset.XAU, "110.000000");
        assertThat(provider.submissions()).isEqualTo(1);
        getWithPermissions("/api/v1/accounts/me/summary", ACCOUNT_READ)
                .andExpect(status().isOk()).andExpect(jsonPath("$.risk.totalFunds").value(98990.00));

        int ledgerBeforeRejectedBuy = ledgerCount(null);
        previewRejected("BUY", "2000", "buy-insufficient", "INSUFFICIENT_AVAILABLE_BALANCE");
        assertThat(provider.submissions()).isEqualTo(1);
        assertThat(ledgerCount(null)).isEqualTo(ledgerBeforeRejectedBuy);
        assertThat(activeReservations()).isZero();
        assertThat(balance(Asset.EUR)).isNotNegative();

        long coveredSell = preview("SELL", "10", "sell-covered");
        assertThat(activeReservation(coveredSell, Asset.XAU)).isEqualByComparingTo("10.000000");
        assertThat(activeReservation(coveredSell, Asset.EUR)).isZero();
        submit(coveredSell).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FILLED"));
        assertSettled(coveredSell, "CONSUMED", 2);
        assertBalance(Asset.EUR, "99970.100000");
        assertBalance(Asset.XAU, "100.000000");

        long acceptedShort = preview("SELL", "110", "sell-short-accepted");
        assertThat(activeReservation(acceptedShort, Asset.XAU)).isEqualByComparingTo("100.000000");
        assertThat(activeReservation(acceptedShort, Asset.EUR)).isEqualByComparingTo("0");
        submit(acceptedShort).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FILLED"));
        assertBalance(Asset.EUR, "110751.200000");
        assertBalance(Asset.XAU, "-10.000000");

        int ledgerBeforeRejectedShort = ledgerCount(null);
        previewRejected("SELL", "20000", "sell-short-insufficient", "INSUFFICIENT_FREE_EQUITY");
        assertThat(provider.submissions()).isEqualTo(3);
        assertThat(ledgerCount(null)).isEqualTo(ledgerBeforeRejectedShort);
        assertThat(activeReservations()).isZero();

        long priceMoved = preview("BUY", "1", "price-moved");
        int ledgerBeforePriceMoved = ledgerCount(null);
        setMarketPrice("101.000000", "102.000000");
        submit(priceMoved).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_MOVED"))
                .andExpect(jsonPath("$.currentClientPrice").isNumber())
                .andExpect(jsonPath("$.priceAsOf").isString())
                .andExpect(jsonPath("$.pair").value("XAUEUR"))
                .andExpect(jsonPath("$.marketAsk").doesNotExist())
                .andExpect(jsonPath("$.marketBid").doesNotExist());
        assertThat(provider.submissions()).isEqualTo(3);
        assertThat(ledgerCount(priceMoved)).isZero();
        assertThat(ledgerCount(null)).isEqualTo(ledgerBeforePriceMoved);
        assertThat(activeReservationCount(priceMoved)).isZero();

        JsonNode replay = previewResponse("BUY", "10", "buy-accepted");
        assertThat(replay.get("orderId").asLong()).isEqualTo(acceptedBuy);
        assertThat(orders.findById(acceptedBuy).orElseThrow().clOrdId())
                .isEqualTo(ClientOrderIdFactory.fromIdempotencyKey(key("buy-accepted")));
        submit(acceptedBuy).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FILLED"));
        assertThat(provider.submissions()).isEqualTo(3);
        assertThat(outboxCount(acceptedBuy)).isEqualTo(1);
        assertThat(ledgerCount(acceptedBuy)).isEqualTo(2);
        assertBalance(Asset.EUR, "110751.200000");
        assertBalance(Asset.XAU, "-10.000000");

        setMarketPrice("99.000000", "100.000000");
        provider.nextSubmissionUnknown();
        long pendingProcessed = preview("BUY", "1", "pending-processed");
        submit(pendingProcessed).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_UNKNOWN"));
        getWithPermissions("/api/v1/accounts/me/orders/" + pendingProcessed, HISTORY_READ)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_UNKNOWN"));
        assertThat(activeReservationCount(pendingProcessed)).isEqualTo(2);
        assertThat(ledgerCount(pendingProcessed)).isZero();
        assertThat(outboxCount(pendingProcessed)).isZero();
        int callsAfterUnknown = provider.submissions();
        submit(pendingProcessed).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_UNKNOWN"));
        assertThat(provider.submissions()).isEqualTo(callsAfterUnknown);
        provider.resolveProcessed(orders.findById(pendingProcessed).orElseThrow().clOrdId(), "SIM-RESOLVED-1");
        makeResolutionDue(pendingProcessed);
        resolver.resolveDue();
        assertThat(orders.findById(pendingProcessed).orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(ledgerCount(pendingProcessed)).isEqualTo(2);
        assertThat(outboxCount(pendingProcessed)).isEqualTo(1);
        assertThat(activeReservationCount(pendingProcessed)).isZero();
        resolver.resolveDue();
        assertThat(ledgerCount(pendingProcessed)).isEqualTo(2);

        provider.nextSubmissionUnknown();
        long pending631 = preview("BUY", "1", "pending-631");
        submit(pending631).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_UNKNOWN"));
        int callsBefore631Resolution = provider.submissions();
        provider.resolveRejected631(orders.findById(pending631).orElseThrow().clOrdId());
        makeResolutionDue(pending631);
        resolver.resolveDue();
        assertThat(orders.findById(pending631).orElseThrow().status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(activeReservationCount(pending631)).isZero();
        assertThat(ledgerCount(pending631)).isZero();
        assertThat(outboxCount(pending631)).isZero();
        assertThat(provider.submissions()).isEqualTo(callsBefore631Resolution);
        assertThat(provider.submissions()).isEqualTo(5);

        assertBalancesEqualLedger();
        getWithPermissions("/api/v1/accounts/me/balances", ACCOUNT_READ)
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
        getWithPermissions("/api/v1/accounts/me/positions", ACCOUNT_READ)
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].asset").value("XAU"));
        getWithPermissions("/api/v1/accounts/me/summary", ACCOUNT_READ)
                .andExpect(status().isOk()).andExpect(jsonPath("$.risk.totalFunds").value(110650.20));
        getWithPermissions("/api/v1/accounts/me/orders", HISTORY_READ)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(6)))
                .andExpect(jsonPath("$.hasMore").value(false));
        getWithPermissions("/api/v1/accounts/me/statement", HISTORY_READ)
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines", hasSize(10)));
    }


    @Test
    void dashboardPositionsAndSummaryShareClientLiquidationAndMargin() throws Exception {
        ledger.post(accountId,Asset.EUR,new BigDecimal("-3040.10"),LedgerEntryType.ADJUSTMENT,
                null,null,"test:dashboard-fixture");
        ledger.post(accountId,Asset.XAU,new BigDecimal("-98.99"),LedgerEntryType.ADJUSTMENT,
                null,null,"test:dashboard-fixture");
        setMarketPrice("3000.000000","3010.000000");
        jdbc.update("UPDATE trading_spread SET spread_buy=0.003000,spread_sell=0.003000 WHERE company_id=?",companyId);
        jdbc.update("INSERT INTO trading_margin_rate(account_id,asset,rate) VALUES (?,'XAU',0.0500)",accountId);

        var line=json.readTree(getWithPermissions("/api/v1/accounts/me/positions",ACCOUNT_READ)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0);
        var summary=json.readTree(getWithPermissions("/api/v1/accounts/me/summary",ACCOUNT_READ)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var risk=summary.get("risk");
        assertThat(line.get("quantityOz").decimalValue()).isEqualByComparingTo("1.01");
        assertThat(line.get("clientPrice").decimalValue()).isEqualByComparingTo("2991");
        assertThat(line.get("valuation").decimalValue()).isEqualByComparingTo("3020.91");
        assertThat(line.get("marginRatePct").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(line.get("marginRequirement").decimalValue()).isEqualByComparingTo("151.05");
        assertThat(risk.get("positionValuation").decimalValue()).isEqualByComparingTo(line.get("valuation").decimalValue());
        assertThat(risk.get("grossPosition").decimalValue()).isEqualByComparingTo("3020.91");
        assertThat(risk.get("marginRequirement").decimalValue()).isEqualByComparingTo(line.get("marginRequirement").decimalValue());
        assertThat(risk.get("totalFunds").decimalValue()).isEqualByComparingTo("96959.90");
        assertThat(risk.get("netEquity").decimalValue()).isEqualByComparingTo("99980.81");
        assertThat(risk.get("freeEquity").decimalValue()).isEqualByComparingTo("99829.76");
        assertThat(risk.get("coveragePct").decimalValue()).isEqualByComparingTo("3409.63");
        assertThat(summary.get("dealLimit").isNull()).isTrue();
        assertThat(summary.get("positionLimit").isNull()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT margin_requirement FROM trading_risk_snapshot
                WHERE account_id=? ORDER BY computed_at DESC,id DESC LIMIT 1
                """,BigDecimal.class,accountId)).isEqualByComparingTo("151.05");
    }

    private long preview(String side, String quantity, String suffix) throws Exception {
        return previewResponse(side, quantity, suffix).get("orderId").asLong();
    }

    @Test
    void estimateDoesNotCreateAnOrderOrReservationAndRejectsUnknownUnit() throws Exception {
        JsonNode first=null;
        for(String unit:java.util.List.of("KG","G","OZ")) {
            var response=json.readTree(mvc.perform(post("/api/v1/accounts/me/orders/estimate").param("displayUnit",unit)
                    .with(jwtFor(ORDER_WRITE)).contentType("application/json")
                    .content("{\"asset\":\"XAU\",\"side\":\"BUY\",\"quantity\":1,\"unit\":\"G\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if(first!=null) assertThat(response.get("estimatedAmount")).isEqualTo(first.get("estimatedAmount"));
            first=response;
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading_order WHERE account_id=?",Integer.class,accountId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading_reservation WHERE account_id=?",Integer.class,accountId)).isZero();
        assertThat(provider.submissions()).isZero();
        getWithPermissions("/api/v1/accounts/me/positions?displayUnit=LB",ACCOUNT_READ).andExpect(status().isBadRequest());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"EUR","USD"})
    void pricesUseAccountCurrencyAndServerConversion(String currency) throws Exception {
        jdbc.update("UPDATE trading_account SET base_currency=? WHERE id=?",currency,accountId);
        String pair="XAU"+currency;
        jdbc.update("INSERT INTO trading_market_price(pair,bid,ask,mid,price_as_of,source) VALUES (?,99,100,99.5,NOW(),'TEST') ON CONFLICT(pair) DO UPDATE SET bid=99,ask=100,mid=99.5,price_as_of=NOW()",pair);
        var canonical=json.readTree(getWithPermissions("/api/v1/accounts/me/prices?assets=XAU",ACCOUNT_READ)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0);
        for(var unit:com.saamp.trading.domain.QuantityUnit.values()) {
            var row=json.readTree(getWithPermissions("/api/v1/accounts/me/prices?assets=XAU&displayUnit="+unit,ACCOUNT_READ)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0);
            assertThat(row.get("displayPair").asText()).isEqualTo("XAU-"+currency);
            assertThat(row.get("buyPrice").decimalValue()).isEqualByComparingTo(com.saamp.trading.common.TroyWeightConverter.displayPrice(canonical.get("buyPrice").decimalValue(),unit));
            assertThat(row.get("sellPrice").decimalValue()).isEqualByComparingTo(com.saamp.trading.common.TroyWeightConverter.displayPrice(canonical.get("sellPrice").decimalValue(),unit));
        }
        getWithPermissions("/api/v1/accounts/me/prices?assets=EUR,USD",ACCOUNT_READ).andExpect(status().isOk()).andExpect(jsonPath("$",hasSize(0)));
    }

    @Test
    void fourMetalPreviewsKeepIndependentExpiryAndPartialResultsWithoutRetry() throws Exception {
        var ids=new java.util.ArrayList<Long>();
        for(String metal:java.util.List.of("XAU","XAG","XPT","XPD")) {
            jdbc.update("INSERT INTO trading_market_price(pair,bid,ask,mid,price_as_of,source) VALUES (?,99,100,99.5,NOW(),'TEST') ON CONFLICT(pair) DO UPDATE SET bid=99,ask=100,mid=99.5,price_as_of=NOW()",metal+"EUR");
            jdbc.update("INSERT INTO trading_spread(company_id,asset,spread_buy,spread_sell,config_version) VALUES (?,?,0.01,0.01,2)",companyId,metal);
            var response=json.readTree(mvc.perform(post("/api/v1/accounts/me/orders/preview").with(jwtFor(ORDER_WRITE)).contentType("application/json")
                    .content("{\"asset\":\""+metal+"\",\"side\":\"BUY\",\"quantity\":1,\"unit\":\"OZ\",\"idempotencyKey\":\""+key(metal)+"\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            ids.add(response.get("orderId").asLong());
        }
        jdbc.update("UPDATE trading_order_preview_snapshot SET expires_at=NOW()-INTERVAL '1 second' WHERE order_id=?",ids.get(0));
        submit(ids.get(0)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));
        submit(ids.get(1)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FILLED"));
        submit(ids.get(1)).andExpect(status().isOk());
        assertThat(provider.submissions()).isEqualTo(1);
        assertThat(orders.findById(ids.get(2)).orElseThrow().status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(orders.findById(ids.get(3)).orElseThrow().status()).isEqualTo(OrderStatus.DRAFT);
    }

    @Test
    void openApiDocumentsDisplayUnitAndUnwrappedPreview() throws Exception {
        var document=json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var operation=document.path("paths").path("/api/v1/accounts/me/orders/preview").path("post");
        boolean found=false;
        for(var parameter:operation.path("parameters")) if("displayUnit".equals(parameter.path("name").asText())) {
            found=true;
            assertThat(parameter.path("schema").path("default").asText()).isEqualTo("OZ");
            assertThat(parameter.path("schema").path("enum").toString()).contains("KG","G","OZ");
        }
        assertThat(found).isTrue();
        String ref=operation.path("responses").path("200").path("content").path("*/*").path("schema").path("$ref").asText();
        var schema=document.at(ref.substring(1)).path("properties");
        assertThat(schema.has("orderId")).isTrue();
        assertThat(schema.has("expiresAt")).isTrue();
        assertThat(schema.has("estimatedAmount")).isTrue();
        assertThat(schema.has("displayClientPrice")).isTrue();
    }

    @Test
    void statementConvertsMetalsOnlyAndHistoryDoesNotUseCurrentPrice() throws Exception {
        long id=preview("BUY","1","history-price");
        submit(id).andExpect(status().isOk());
        var before=getWithPermissions("/api/v1/accounts/me/orders/"+id+"?displayUnit=KG",HISTORY_READ)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        setMarketPrice("999","1000");
        assertThat(getWithPermissions("/api/v1/accounts/me/orders/"+id+"?displayUnit=KG",HISTORY_READ)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(before);
        for(String unit:java.util.List.of("KG","G","OZ")) {
            var response=json.readTree(getWithPermissions("/api/v1/accounts/me/statement?displayUnit="+unit,HISTORY_READ)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            for(var line:response.path("lines")) {
                var delta=line.path("delta").decimalValue();
                if("EUR".equals(line.path("asset").asText())) {
                    assertThat(line.path("displayUnit").isNull()).isTrue();
                    assertThat(line.path("displayDelta").decimalValue()).isEqualByComparingTo(delta);
                } else {
                    assertThat(line.path("displayDelta").decimalValue()).isEqualByComparingTo(
                            com.saamp.trading.common.TroyWeightConverter.fromTroyOunces(delta,com.saamp.trading.domain.QuantityUnit.valueOf(unit)));
                }
            }
        }
    }

    @Test
    void globalDisplayUnitNeverChangesRiskOrCanonicalHistory() throws Exception {
        var baseline=json.readTree(getWithPermissions("/api/v1/accounts/me/summary",ACCOUNT_READ).andReturn().getResponse().getContentAsString()).get("risk");
        for(String unit:java.util.List.of("KG","G","OZ")) {
            var summary=json.readTree(getWithPermissions("/api/v1/accounts/me/summary?displayUnit="+unit,ACCOUNT_READ)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("risk");
            for(String field:java.util.List.of("totalFunds","positionValuation","netEquity","marginRequirement","freeEquity","grossPosition"))
                assertThat(summary.get(field)).as(field).isEqualTo(baseline.get(field));
            getWithPermissions("/api/v1/accounts/me/positions?displayUnit="+unit,ACCOUNT_READ)
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].quantityOz").value(100))
                    .andExpect(jsonPath("$[0].displayUnit").value(unit));
            var balancesJson=json.readTree(getWithPermissions("/api/v1/accounts/me/balances?displayUnit="+unit,ACCOUNT_READ)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            for(var row:balancesJson) if ("EUR".equals(row.get("asset").asText())) {
                assertThat(row.get("displayUnit").isNull()).isTrue();
                assertThat(row.get("displayQuantity").decimalValue()).isEqualByComparingTo(row.get("balance").decimalValue());
            }
        }
    }

    @Test
    void previewReplayRetainsSnapshotAndExpiresAfterTenSeconds() throws Exception {
        var first=previewResponse("BUY","1","snapshot");
        long id=first.get("orderId").asLong();
        var persisted=orders.findById(id).orElseThrow();
        assertThat(OffsetDateTime.parse(first.get("expiresAt").asText()))
                .isEqualTo(persisted.createdAt().plusSeconds(10));
        setMarketPrice("199","200");
        var replay=previewResponse("BUY","1","snapshot");
        assertThat(replay).isEqualTo(first);
        assertThat(provider.submissions()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading_order_preview_snapshot WHERE order_id=?",Integer.class,id)).isEqualTo(1);
        // Horloge du scenario : expiration persistante deja passee, sans attendre en temps reel.
        jdbc.update("UPDATE trading_order_preview_snapshot SET expires_at=NOW()-INTERVAL '1 second' WHERE order_id=?",id);
        submit(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));
        assertThat(provider.submissions()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"BUY,G", "SELL,G", "BUY,KG", "SELL,KG", "BUY,OZ", "SELL,OZ"})
    void previewProvidesBackendAmountAndPreservesRequestedUnit(String side,String unit) throws Exception {
        String body="{\"asset\":\"XAU\",\"side\":\""+side+"\",\"quantity\":1,\"unit\":\""+unit+"\",\"idempotencyKey\":\""+key("units")+"\"}";
        var result=json.readTree(mvc.perform(post("/api/v1/accounts/me/orders/preview").param("displayUnit",unit)
                .with(jwtFor(ORDER_WRITE)).contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.get("requestedQuantity").decimalValue()).isEqualByComparingTo("1");
        assertThat(result.get("requestedUnit").asText()).isEqualTo(unit);
        assertThat(result.get("priceUnit").asText()).isEqualTo("EUR/"+unit.toLowerCase(java.util.Locale.ROOT));
        assertThat(result.get("estimatedAmount").decimalValue()).isEqualByComparingTo(result.get("quantityOz").decimalValue()
                .multiply(result.get("indicativeClientPrice").decimalValue()).setScale(2,java.math.RoundingMode.HALF_UP));
        long id=result.get("orderId").asLong();
        for(String display:java.util.List.of("KG","G","OZ")) {
            getWithPermissions("/api/v1/accounts/me/orders/"+id+"?displayUnit="+display,HISTORY_READ)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.requestedUnit").value(unit))
                    .andExpect(jsonPath("$.displayUnit").value(display));
        }
    }

    private JsonNode previewResponse(String side, String quantity, String suffix) throws Exception {
        String body = mvc.perform(post("/api/v1/accounts/me/orders/preview")
                        .with(jwtFor(ORDER_WRITE)).contentType("application/json")
                        .content(orderJson(side, quantity, suffix)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private void previewRejected(String side, String quantity, String suffix, String code) throws Exception {
        mvc.perform(post("/api/v1/accounts/me/orders/preview")
                        .with(jwtFor(ORDER_WRITE)).contentType("application/json")
                        .content(orderJson(side, quantity, suffix)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
    }

    private org.springframework.test.web.servlet.ResultActions submit(long orderId) throws Exception {
        return mvc.perform(post("/api/v1/accounts/me/orders/{orderId}/submit", orderId).with(jwtFor(ORDER_WRITE)));
    }

    private org.springframework.test.web.servlet.ResultActions getWithPermissions(String path, String permission) throws Exception {
        return mvc.perform(get(path).with(jwtFor(permission)));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(String permission) {
        return jwt().jwt(token -> token.subject(Long.toString(USER_ID)).claim("companyId", companyId)
                        .claim("companyCode", "E2E").claim("username", "e2e")
                        .claim("permissions", java.util.List.of("MYTRADING_ACCESS", permission)))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("MYTRADING_ACCESS"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority(permission));
    }

    private String orderJson(String side, String quantity, String suffix) {
        return """
                {"asset":"XAU","side":"%s","quantity":%s,"unit":"OZ","idempotencyKey":"%s"}
                """.formatted(side, quantity, key(suffix));
    }

    private String key(String suffix) {
        return "e2e-" + companyId + "-" + suffix;
    }

    private void setMarketPrice(String bid, String ask) {
        jdbc.update("""
                INSERT INTO trading_market_price(pair,bid,ask,mid,price_as_of,source)
                VALUES ('XAUEUR',?,?,?,NOW(),'SIMULATED')
                ON CONFLICT (pair) DO UPDATE SET bid=EXCLUDED.bid,ask=EXCLUDED.ask,mid=EXCLUDED.mid,
                  price_as_of=NOW(),source='SIMULATED',updated_at=NOW()
                """, new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(bid).add(new BigDecimal(ask))
                .divide(new BigDecimal("2")));
    }

    private Optional<MarketPrice> readXauEur() {
        return jdbc.query("SELECT * FROM trading_market_price WHERE pair='XAUEUR'", (rs, row) -> new MarketPrice(
                rs.getString("pair"), rs.getBigDecimal("bid"), rs.getBigDecimal("ask"), rs.getBigDecimal("mid"),
                rs.getObject("price_as_of", OffsetDateTime.class), rs.getString("source"),
                rs.getObject("updated_at", OffsetDateTime.class))).stream().findFirst();
    }

    private BigDecimal balance(Asset asset) {
        return balances.find(accountId, asset).orElseThrow().quantity();
    }

    private void assertBalance(Asset asset, String expected) {
        assertThat(balance(asset)).isEqualByComparingTo(expected);
    }

    private BigDecimal activeReservation(long orderId, Asset asset) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity),0) FROM trading_reservation
                WHERE order_id=? AND asset=? AND status='ACTIVE' AND reservation_kind IN ('CASH','POSITION_CLOSE')
                """, BigDecimal.class, orderId, asset.name());
        return value == null ? BigDecimal.ZERO : value;
    }

    private int activeReservationCount(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trading_reservation WHERE order_id=? AND status='ACTIVE'",
                Integer.class, orderId);
    }

    private int activeReservations() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trading_reservation WHERE account_id=? AND status='ACTIVE'",
                Integer.class, accountId);
    }

    private int ledgerCount(Long orderId) {
        if (orderId == null) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM trading_ledger_entry WHERE account_id=?", Integer.class, accountId);
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM trading_ledger_entry WHERE account_id=? AND order_id=?",
                Integer.class, accountId, orderId);
    }

    private int outboxCount(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trading_as400_sync_outbox WHERE order_id=?", Integer.class, orderId);
    }

    private void assertSettled(long orderId, String reservationStatus, int expectedLedgerEntries) {
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM trading_reservation WHERE order_id=?", String.class, orderId))
                .containsExactly(reservationStatus);
        assertThat(ledgerCount(orderId)).isEqualTo(expectedLedgerEntries);
    }

    private void makeResolutionDue(long orderId) {
        jdbc.update("UPDATE trading_order SET next_resolution_at=NOW()-INTERVAL '1 second' WHERE id=?", orderId);
    }

    private void assertBalancesEqualLedger() {
        for (Asset asset : java.util.List.of(Asset.EUR, Asset.XAU)) {
            BigDecimal ledgerSum = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(delta),0) FROM trading_ledger_entry WHERE account_id=? AND asset=?
                    """, BigDecimal.class, accountId, asset.name());
            assertThat(balance(asset)).as("projection %s", asset).isEqualByComparingTo(ledgerSum);
        }
    }

    /** Fournit au test un simulateur pilotable sans reproduire de payload StoneX. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        /**
         * Remplace uniquement la frontière fournisseur par son implémentation simulée pilotable.
         *
         * @param pricing accès aux cotations locales simulées
         * @return fournisseur utilisé par le parcours d'intégration
         */
        @Bean
        @Primary
        ScenarioSimulatedTradingProvider scenarioProvider(PricingRepository pricing) {
            return new ScenarioSimulatedTradingProvider(pricing);
        }
    }

    /** Simulateur compté qui permet de piloter les seuls résultats externes utiles au scénario. */
    static final class ScenarioSimulatedTradingProvider extends SimulatedTradingProvider implements TradingProvider {
        private final AtomicInteger submissionCount = new AtomicInteger();
        private final Map<String, ExecutionReport> resolutions = new ConcurrentHashMap<>();
        private volatile boolean unknownNext;

        ScenarioSimulatedTradingProvider(PricingRepository pricing) {
            super(pricing);
        }

        @Override
        public OrderAcknowledgement submitSpotOrder(SpotOrderRequest request) {
            submissionCount.incrementAndGet();
            if (unknownNext) {
                unknownNext = false;
                return new OrderAcknowledgement(AcknowledgementState.IN_PROCESS, request.clientOrderId(),
                        null, null, "IN_PROCESS", "Résultat indéterminé simulé");
            }
            return super.submitSpotOrder(request);
        }

        @Override
        public Optional<ExecutionReport> queryRequestStatus(String clientOrderId) {
            return Optional.ofNullable(resolutions.get(clientOrderId));
        }

        void reset() {
            submissionCount.set(0);
            resolutions.clear();
            unknownNext = false;
        }

        void nextSubmissionUnknown() {
            unknownNext = true;
        }

        void resolveProcessed(String clOrdId, String executionId) {
            resolutions.put(clOrdId, new ExecutionReport(ExecutionState.PROCESSED, clOrdId, executionId,
                    new BigDecimal("100.000000"), null, null));
        }

        void resolveRejected631(String clOrdId) {
            resolutions.put(clOrdId, new ExecutionReport(ExecutionState.FAILED, clOrdId, null, null,
                    "631", "Client Order ID does not exist"));
        }

        int submissions() {
            return submissionCount.get();
        }
    }
}
