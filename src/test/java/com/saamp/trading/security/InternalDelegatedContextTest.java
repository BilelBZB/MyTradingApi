package com.saamp.trading.security;

import com.saamp.trading.common.TradingException;
import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.TradingAccessMode;
import com.saamp.trading.domain.TradingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import static org.assertj.core.api.Assertions.*;

class InternalDelegatedContextTest {
    private final TradingDemoGuard guard = new TradingDemoGuard(new TradingProperties());
    private final DemoRouteAuthorization routes = new DemoRouteAuthorization(new MockEnvironment());

    @Test void resolvesRealInternalUserAndSignedCompanyWithoutGrantingOrderPermission() {
        var auth = authentication(claims());
        var trader = new CurrentTraderService(guard).current(auth);
        assertThat(trader.companyId()).isEqualTo(3);
        assertThat(trader.identityType()).isEqualTo("INTERNAL");
        assertThat(trader.accessMode()).isEqualTo(TradingAccessMode.INTERNAL_DELEGATED);
        assertThat(trader.tradingMode()).isEqualTo(TradingMode.LIVE);
        assertThat(trader.permissions()).containsExactly("MYTRADING_ACCOUNT_READ");
        assertThat(routes.check(() -> auth, request()).isGranted()).isTrue();
    }

    @Test void preservesExplicitOrderPermission() {
        var claims = claims();
        claims.put("permissions", List.of("MYTRADING_ORDER_WRITE"));
        assertThat(new CurrentTraderService(guard).current(authentication(claims)).permissions())
                .containsExactly("MYTRADING_ORDER_WRITE");
    }

    @ParameterizedTest
    @CsvSource({"tradingMode,DEMO", "tradingMode,NULL", "identityType,CLIENT", "identityType,NULL",
            "companyId,NULL", "companyId,0", "companyId,-1", "companyId,1.5", "sub,NULL", "sub,invalid"})
    void bothGuardsRejectIncompleteOrInconsistentDelegation(String key, String value) {
        var claims = claims();
        if ("NULL".equals(value)) claims.remove(key);
        else claims.put(key, "companyId".equals(key) ? new java.math.BigDecimal(value) : value);
        var auth = authentication(claims);
        assertThatThrownBy(() -> guard.resolveMode(auth)).isInstanceOf(TradingException.class);
        assertThat(routes.check(() -> auth, request()).isGranted()).isFalse();
    }

    private Map<String,Object> claims() {
        return new LinkedHashMap<>(Map.of("sub", "6", "companyId", 3L,
                "tradingMode", "LIVE", "identityType", "INTERNAL", "accessMode", "INTERNAL_DELEGATED",
                "permissions", List.of("MYTRADING_ACCOUNT_READ")));
    }
    private JwtAuthenticationToken authentication(Map<String,Object> claims) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("test-only").header("alg", "none")
                .claims(c -> c.putAll(claims)).build(), List.of());
    }
    private RequestAuthorizationContext request() {
        return new RequestAuthorizationContext(new MockHttpServletRequest("GET", "/api/v1/accounts/me"));
    }
}
