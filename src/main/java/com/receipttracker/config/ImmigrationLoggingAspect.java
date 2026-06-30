package com.receipttracker.config;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Cross-cutting request/exception logging for the Immigration feature only.
 *
 * <p>Existing per-method controller logs capture the entry line but never the response time, and on
 * error they log only {@code e.getMessage()} — so the stack trace is lost. Service failures are
 * collapsed to a generic message by {@link ApiErrors#safeMessage} before they ever reach a log.
 * This aspect closes both gaps uniformly without touching any of the ~25 controllers / ~37 services.
 *
 * <p><b>No PII is ever logged.</b> Immigration data includes SSN, passport, A-number, I-94, EAD
 * (encrypted at rest). Only the method name, HTTP method, request URI, status, and duration are
 * logged — never argument values, request bodies, or response bodies.
 *
 * <p>Logger category is {@code com.receipttracker.immigration} so verbosity follows the existing
 * {@code logging.level.com.receipttracker} / {@code APP_LOG_LEVEL} knob (default INFO).
 */
@Aspect
@Component
public class ImmigrationLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger("com.receipttracker.immigration.RequestLog");

    /** Calls slower than this are logged at WARN so they stand out. */
    private static final long SLOW_CALL_MS = 2000L;

    @Pointcut("within(com.receipttracker.immigration.controller..*)")
    public void immigrationController() {}

    @Pointcut("within(com.receipttracker.immigration.service..*)")
    public void immigrationService() {}

    /**
     * Times every immigration API call and logs the outcome (HTTP status + duration). Rethrows
     * unchanged — purely observational, no behavior change to responses.
     */
    @Around("immigrationController()")
    public Object logApiCall(ProceedingJoinPoint pjp) throws Throwable {
        String handler = pjp.getSignature().getDeclaringType().getSimpleName()
                + "#" + pjp.getSignature().getName();
        String http = "?";
        String uri = handler;
        HttpServletRequest request = currentRequest();
        if (request != null) {
            http = request.getMethod();
            uri = request.getRequestURI();
        }

        long start = System.currentTimeMillis();
        log.info("→ {} {} [{}]", http, uri, handler);
        try {
            Object result = pjp.proceed();
            long elapsed = System.currentTimeMillis() - start;
            String status = statusOf(result);
            if (elapsed >= SLOW_CALL_MS) {
                log.warn("← {} {} {} ({}ms) SLOW", http, uri, status, elapsed);
            } else {
                log.info("← {} {} {} ({}ms)", http, uri, status, elapsed);
            }
            return result;
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            // Exceptions that propagate out of a handler are unusual (most are caught and turned
            // into a ResponseEntity) — log the full stack trace when one does.
            log.error("!!! {} {} failed after {}ms in {}", http, uri, elapsed, handler, t);
            throw t;
        }
    }

    /**
     * Logs the real cause of any immigration service failure with a full stack trace, at the point
     * it is thrown — before the controller's catch block collapses it into a safe message. This is
     * the key fix for "exceptions/errors not captured".
     *
     * <p>Expected ReBAC denials ("Access denied: ...") from {@code PermissionService} are logged at
     * WARN without a stack trace to avoid flooding the log on every 403.
     */
    @AfterThrowing(pointcut = "immigrationService()", throwing = "ex")
    public void logServiceFailure(org.aspectj.lang.JoinPoint jp, Throwable ex) {
        String where = jp.getSignature().getDeclaringType().getSimpleName()
                + "#" + jp.getSignature().getName();
        String msg = ex.getMessage();
        if (msg != null && msg.startsWith("Access denied")) {
            log.warn("!!! {} denied: {}", where, msg);
        } else {
            log.error("!!! {} threw {}", where, ex.getClass().getSimpleName(), ex);
        }
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private static String statusOf(Object result) {
        if (result instanceof ResponseEntity<?> re) {
            return String.valueOf(re.getStatusCode().value());
        }
        return "OK";
    }
}
