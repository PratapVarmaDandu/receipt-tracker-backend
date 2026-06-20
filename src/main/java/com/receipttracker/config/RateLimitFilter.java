package com.receipttracker.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window IP-based rate limiter.
 * Registered manually in SecurityConfig for non-local profiles only.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    // Requests allowed per IP per window
    private static final int SENSITIVE_LIMIT = 20;
    private static final int GENERAL_LIMIT   = 100;
    private static final long WINDOW_MS      = 60_000L;

    // Public token-lookup paths where enumeration is the primary risk
    private static final List<String> SENSITIVE_PREFIXES = List.of(
            "/api/shares/token/",
            "/api/groups/join/",
            "/api/documents/shared/",
            "/api/vehicles/access/join/",
            "/api/org/join/",
            "/api/immigration/cases/join/",
            "/api/immigration/partnerships/onboard/",
            "/api/immigration/data-requests/",
            "/api/immigration/packages/questionnaires/"
    );

    // long[0] = window start epoch ms, long[1] = request count in window
    private final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Never rate-limit OAuth/login flow itself
        if (path.startsWith("/oauth2/") || path.startsWith("/login/") || path.equals("/error")) {
            chain.doFilter(request, response);
            return;
        }

        String ip = clientIp(request);
        boolean sensitive = SENSITIVE_PREFIXES.stream().anyMatch(path::startsWith);
        int limit = sensitive ? SENSITIVE_LIMIT : GENERAL_LIMIT;
        String key = ip + (sensitive ? ":s" : ":g");

        if (limited(key, limit)) {
            log.warn("Rate limit exceeded: ip={} path={}", ip, path);
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too many requests. Please try again later.\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean limited(String key, int limit) {
        long now = System.currentTimeMillis();
        long[] bucket = buckets.compute(key, (k, v) -> {
            if (v == null || now - v[0] >= WINDOW_MS) return new long[]{now, 1L};
            v[1]++;
            return v;
        });
        return bucket[1] > limit;
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    // Evict windows that closed more than 2 minutes ago to prevent unbounded growth
    @Scheduled(fixedDelay = 120_000)
    public void evictStale() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS * 2;
        int removed = 0;
        var it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue()[0] < cutoff) { it.remove(); removed++; }
        }
        if (removed > 0) log.debug("Rate limiter evicted {} stale buckets", removed);
    }
}
