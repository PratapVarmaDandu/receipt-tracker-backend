package com.receipttracker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.sql.SQLException;

/**
 * Produces client-safe error messages for controller catch blocks.
 *
 * <p>Service-layer domain errors carry curated, user-facing messages (e.g. "Access denied: ...",
 * "City is required") and are passed through unchanged. Database / persistence / transaction
 * failures — whose messages can leak raw SQL, schema names, driver internals, or stack details —
 * are replaced with a single generic message. The real cause is always logged server-side.
 *
 * <p>This guards the case where a {@link DataAccessException} (itself a {@code RuntimeException})
 * bubbles out of a repository call and is caught by a controller's own
 * {@code catch (RuntimeException e)} before {@link GlobalExceptionHandler} can sanitize it.
 */
public final class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    /** Generic fallback — matches the wording already used elsewhere in the controllers. */
    public static final String GENERIC = "An unexpected error occurred. Please try again.";

    private ApiErrors() {}

    /**
     * Returns a message safe to return to the UI. Infrastructure/SQL errors collapse to
     * {@link #GENERIC} (and are logged); curated domain messages are returned as-is.
     */
    public static String safeMessage(Throwable e) {
        if (e == null) {
            return GENERIC;
        }
        if (isInfrastructure(e) || looksLikeSql(e.getMessage())) {
            log.warn("Suppressed backend error from API response: {}", describe(e));
            return GENERIC;
        }
        String msg = e.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : GENERIC;
    }

    /** True if the throwable or any of its causes is a DB / persistence / transaction error. */
    private static boolean isInfrastructure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String className = t.getClass().getName();
            if (t instanceof DataAccessException
                    || t instanceof SQLException
                    || t instanceof TransactionException
                    || className.startsWith("org.hibernate.")
                    || className.startsWith("jakarta.persistence.")
                    || className.startsWith("javax.persistence.")) {
                return true;
            }
            if (t.getCause() == t) {
                break; // defensive: self-referential cause chain
            }
        }
        return false;
    }

    /** Belt-and-suspenders content check for messages that smuggle SQL/JDBC details as plain text. */
    private static boolean looksLikeSql(String msg) {
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase();
        return m.contains("could not execute statement")
                || m.contains("could not extract")
                || m.contains("sqlexception")
                || m.contains("sql [")
                || m.contains("constraint [")
                || m.contains("jdbc")
                || m.contains("org.hibernate")
                || m.contains("h2.jdbc")
                || m.contains("syntax error in sql");
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String head = e.getClass().getSimpleName() + ": " + e.getMessage();
        return (root == e)
                ? head
                : head + " | root=" + root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
