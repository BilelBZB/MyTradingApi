package com.saamp.trading.api;

import com.saamp.trading.account.AccountService;
import com.saamp.trading.security.CurrentTraderService;
import com.saamp.trading.statement.AccountStatement;
import com.saamp.trading.statement.StatementService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.saamp.trading.domain.QuantityUnit;

/** Expose le relevé paginé du compte résolu depuis l'identité authentifiée. */
@RestController
@RequestMapping("/api/v1/accounts/me/statement")
public class StatementController {
    private final CurrentTraderService traders; private final AccountService accounts; private final StatementService statements;

    /**
     * Assemble les services qui résolvent le compte courant et lisent son ledger.
     *
     * @param traders résolution de l'identité authentifiée
     * @param accounts résolution du compte appartenant à la société courante
     * @param statements construction paginée du relevé
     */
    public StatementController(CurrentTraderService traders, AccountService accounts, StatementService statements) {
        this.traders=traders;this.accounts=accounts;this.statements=statements;
    }
    /**
     * Retourne une page stable du ledger sans accepter d'identifiant de compte fourni par le client.
     *
     * @param authentication identité authentifiée
     * @param cursor identifiant exclusif de reprise, ou {@code null} pour la première page
     * @param limit taille demandée, plafonnée par le service
     * @param displayUnit unite visuelle des metaux, OZ par defaut, sans conversion des devises
     * @return page du relevé du compte de la société courante
     */
    @GetMapping
    @Operation(summary = "Lire le relevé du compte", description = "Permissions : MYTRADING_ACCESS + MYTRADING_HISTORY_READ. Pagination descendante par curseur exclusif.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_HISTORY_READ')")
    public DisplayStatement statement(Authentication authentication,
                                      @RequestParam(required = false) Long cursor,
                                      @RequestParam(required = false) Integer limit,
                                      @RequestParam(defaultValue="OZ") QuantityUnit displayUnit) {
        var trader=traders.current(authentication);
        var statement=statements.build(accounts.requireByCompany(trader.companyId(), trader.tradingMode()), cursor, limit, trader.tradingMode());
        return new DisplayStatement(statement.accountId(),statement.baseCurrency(),statement.generatedAt(),
                statement.lines().stream().map(v->DisplayViews.statement(v,displayUnit)).toList(),statement.nextCursor(),statement.hasMore());
    }
    /** Releve avec conversions purement visuelles des lignes metal. */
    public record DisplayStatement(long accountId, com.saamp.trading.domain.Asset baseCurrency,
            java.time.Instant generatedAt, java.util.List<DisplayViews.Statement> lines, Long nextCursor, boolean hasMore) { }
}
