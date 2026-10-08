package com.saamp.trading.security;

import org.springframework.security.oauth2.jwt.Jwt;

/** Valide le contexte delegue signe par MyPortal sans accorder de permission metier. */
final class DelegatedTradingContext {
    private DelegatedTradingContext() { }

    static boolean isValid(Jwt jwt) {
        if (!"LIVE".equals(jwt.getClaimAsString("tradingMode"))
                || !"INTERNAL".equals(jwt.getClaimAsString("identityType"))
                || !"INTERNAL_DELEGATED".equals(jwt.getClaimAsString("accessMode"))) return false;
        Object company = jwt.getClaims().get("companyId");
        try {
            return company instanceof Number
                    && new java.math.BigDecimal(company.toString()).longValueExact() > 0
                    && Long.parseLong(jwt.getSubject()) > 0;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return false;
        }
    }
}
