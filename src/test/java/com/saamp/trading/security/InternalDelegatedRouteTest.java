package com.saamp.trading.security;

import com.saamp.trading.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalDelegatedRouteTest.Controller.class)
@Import({SecurityConfig.class, InternalDelegatedRouteTest.Controller.class})
class InternalDelegatedRouteTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    @Test void delegatedReaderCanReadButCannotSubmit() throws Exception {
        mvc.perform(get("/api/v1/accounts/me").with(token("MYTRADING_ACCOUNT_READ"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/accounts/me/orders/1/submit").with(token("MYTRADING_ACCOUNT_READ")))
                .andExpect(status().isForbidden());
    }
    @Test void delegatedWriterReachesHandlerOnlyWithExplicitPermission() throws Exception {
        mvc.perform(post("/api/v1/accounts/me/orders/1/submit").with(token("MYTRADING_ORDER_WRITE")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/accounts/me").with(token("MYTRADING_ORDER_WRITE"))).andExpect(status().isForbidden());
    }
    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor token(String permission) {
        return jwt().jwt(j -> j.subject("6").claim("companyId", 3L).claim("tradingMode", "LIVE")
                .claim("identityType", "INTERNAL").claim("accessMode", "INTERNAL_DELEGATED"))
                .authorities(new SimpleGrantedAuthority("MYTRADING_ACCESS"), new SimpleGrantedAuthority(permission));
    }
    @RestController
    static class Controller {
        @GetMapping("/api/v1/accounts/me")
        @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_ACCOUNT_READ')")
        public String account() { return "read"; }
        @PostMapping("/api/v1/accounts/me/orders/1/submit")
        @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_ORDER_WRITE')")
        public String submit() { return "test handler only - no provider"; }
    }
}
