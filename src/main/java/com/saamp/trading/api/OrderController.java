package com.saamp.trading.api;

import com.saamp.trading.account.AccountService;
import com.saamp.trading.order.*;
import com.saamp.trading.security.CurrentTraderService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.saamp.trading.domain.QuantityUnit;

@RestController
@RequestMapping("/api/v1/accounts/me/orders")
public class OrderController {
    private final CurrentTraderService traders; private final AccountService accounts; private final OrderExecutionService execution; private final OrderQueryService queries;
    public OrderController(CurrentTraderService traders, AccountService accounts, OrderExecutionService execution, OrderQueryService queries) {
        this.traders=traders; this.accounts=accounts; this.execution=execution; this.queries=queries;
    }

    @PostMapping("/preview")
    @Operation(summary = "Prévisualiser un ordre SPOT", description = "Permissions : MYTRADING_ACCESS + MYTRADING_ORDER_WRITE. Crée un brouillon et ses réservations avant validation explicite par l'utilisateur.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_ORDER_WRITE')")
    public DisplayViews.Preview preview(Authentication authentication, @Valid @RequestBody OrderPreviewRequest request,
            @RequestParam(defaultValue="OZ") QuantityUnit displayUnit) {
        var trader=traders.current(authentication); return DisplayViews.preview(execution.preview(trader.companyId(),trader.userId(),request,trader.tradingMode()),displayUnit);
    }

    @PostMapping("/{orderId}/submit")
    @Operation(summary = "Soumettre un ordre prévisualisé", description = "Permissions : MYTRADING_ACCESS + MYTRADING_ORDER_WRITE. Ne jamais retransmettre automatiquement un ordre PENDING_UNKNOWN.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_ORDER_WRITE')")
    public DisplayViews.Order submit(Authentication authentication,@PathVariable long orderId,
            @RequestParam(defaultValue="OZ") QuantityUnit displayUnit) {
        var trader=traders.current(authentication);
        var order=execution.submit(orderId,trader.companyId(),trader.userId(),trader.tradingMode());
        return DisplayViews.order(OrderView.from(order),displayUnit);
    }

    @GetMapping
    @Operation(summary = "Lire l'historique paginé des ordres", description = "Permissions : MYTRADING_ACCESS + MYTRADING_HISTORY_READ. Pagination keyset par id décroissant, curseur exclusif, sans OFFSET.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_HISTORY_READ')")
    public DisplayOrderPage history(Authentication authentication,
                                 @RequestParam(required = false) Long cursor,
                                 @RequestParam(required = false) Integer limit,
                                 @RequestParam(defaultValue="OZ") QuantityUnit displayUnit) {
        var trader=traders.current(authentication); var account=accounts.requireByCompany(trader.companyId(), trader.tradingMode());
        var page=queries.page(account, cursor, limit, trader.tradingMode());
        return new DisplayOrderPage(page.items().stream().map(v->DisplayViews.order(v,displayUnit)).toList(),page.nextCursor(),page.hasMore());
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Suivre un ordre", description = "Permissions : MYTRADING_ACCESS + MYTRADING_HISTORY_READ. Route canonique de polling d'un ordre PENDING ou PENDING_UNKNOWN ; ne transmet aucun ordre.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_HISTORY_READ')")
    public DisplayViews.Order detail(Authentication authentication, @PathVariable long orderId,
            @RequestParam(defaultValue="OZ") QuantityUnit displayUnit) {
        var trader=traders.current(authentication); var account=accounts.requireByCompany(trader.companyId(), trader.tradingMode());
        return DisplayViews.order(queries.detail(account, orderId, trader.tradingMode()),displayUnit);
    }

    /** @param authentication identite validee @param request intention sans reservation @param displayUnit unite visuelle
     * @return estimation indicative avant demande de cotation */
    @PostMapping("/estimate")
    @Operation(summary="Estimer sans creer d'ordre",description="Indicatif uniquement. Aucun ordre ni reservation ; ne remplace pas le preview et ses controles.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_ORDER_WRITE')")
    public OrderEstimateResponse estimate(Authentication authentication,@Valid @RequestBody OrderEstimateRequest request,
            @RequestParam(defaultValue="OZ") QuantityUnit displayUnit) {
        var trader=traders.current(authentication);
        return execution.estimate(trader.companyId(),request,trader.tradingMode(),displayUnit);
    }
    /** Page additive conservant la pagination historique. */
    public record DisplayOrderPage(java.util.List<DisplayViews.Order> items, Long nextCursor, boolean hasMore) { }
}
