package com.receipttracker.immigration.service;

import com.receipttracker.immigration.model.CaseType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Named functions that compute canonical answer values from case data instead
 * of collecting them. A question opts in via {@code "derivation": "<name>"} in
 * canonical-questions.json; DataResolver routes such questions here.
 *
 * <p>Functions operate only on the supplied {@link DataResolver.ResolutionContext}
 * — no database calls (same rule as DataResolver). A function returns null when
 * it has no answer for the case (e.g. a case type outside its mapping); the
 * caller treats that as ResolvedValue.none().</p>
 *
 * <p>UPL guardrail: derived values are factual classification data taken from
 * the case record — nothing here recommends a form or gives legal advice.</p>
 */
@Service
public class DerivationRegistry {

    private static final Logger log = LoggerFactory.getLogger(DerivationRegistry.class);

    private final Map<String, Function<DataResolver.ResolutionContext, String>> functions = Map.of(
            "classificationSymbol",    DerivationRegistry::classificationSymbol,
            "basisForClassification",  DerivationRegistry::basisForClassification,
            "requestedAction",         DerivationRegistry::requestedAction,
            "totalWorkers",            ctx -> "1"
    );

    /** True when a derivation function with this name is registered. */
    public boolean knows(String name) {
        return name != null && functions.containsKey(name);
    }

    /** Registered function names — for startup validation messages. */
    public Set<String> names() {
        return functions.keySet();
    }

    /**
     * Runs the named derivation against the context.
     * Returns null when the name is unknown, the function has no answer for
     * this case, or the function throws — never propagates exceptions.
     */
    public String derive(String name, DataResolver.ResolutionContext ctx) {
        Function<DataResolver.ResolutionContext, String> fn = functions.get(name);
        if (fn == null) {
            log.warn("Unknown derivation '{}' — expected one of {}", name, functions.keySet());
            return null;
        }
        try {
            return fn.apply(ctx);
        } catch (Exception e) {
            log.warn("Derivation '{}' failed: {}", name, e.getMessage());
            return null;
        }
    }

    // ── Functions ─────────────────────────────────────────────────────────────

    private static CaseType caseType(DataResolver.ResolutionContext ctx) {
        return ctx.immigrationCase() != null ? ctx.immigrationCase().getCaseType() : null;
    }

    /** Nonimmigrant classification symbol from the case type. */
    private static String classificationSymbol(DataResolver.ResolutionContext ctx) {
        CaseType t = caseType(ctx);
        if (t == null) return null;
        return switch (t) {
            case H1B_INITIAL, H1B_EXTENSION, H1B_TRANSFER, H1B_AMENDMENT -> "H-1B";
            case H4, H4_EAD -> "H-4";
            default -> null;
        };
    }

    /** Factual basis of the petition from the case type. */
    private static String basisForClassification(DataResolver.ResolutionContext ctx) {
        CaseType t = caseType(ctx);
        if (t == null) return null;
        return switch (t) {
            case H1B_INITIAL   -> "new employment";
            case H1B_EXTENSION -> "continuation";
            case H1B_TRANSFER  -> "change of employer";
            case H1B_AMENDMENT -> "amended petition";
            default -> null;
        };
    }

    /** Requested action from the case type. */
    private static String requestedAction(DataResolver.ResolutionContext ctx) {
        CaseType t = caseType(ctx);
        if (t == null) return null;
        return switch (t) {
            case H1B_INITIAL   -> "notify office";
            case H1B_EXTENSION -> "extend";
            case H1B_TRANSFER  -> "change status";
            default -> null;
        };
    }
}
