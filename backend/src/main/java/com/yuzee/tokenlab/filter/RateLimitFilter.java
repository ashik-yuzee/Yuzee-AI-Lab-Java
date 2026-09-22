package com.yuzee.tokenlab.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Per-IP fixed-window rate limiter for the LLM-calling endpoints, a Java port of the old Node
 * app's per-route {@code makeRateLimit(n)} (express-rate-limit, n requests/minute per IP) --
 * same routes, same limits (server.ts lines 260-1607). Returns 429 with
 * {"error":"Too many requests"} once a group's limit is exceeded.
 * <p>
 * // ponytail: one 60s fixed window per (ip, path-group) rather than a true sliding window/token
 * // bucket -- allows a small double-burst right at the window boundary, which is fine at this
 * // app's scale. Swap for a real token bucket only if that boundary burst becomes a problem.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = 60_000L;

    private static final class Group {
        final Pattern path;
        final int limitPerMinute;

        Group(String regex, int limitPerMinute) {
            this.path = Pattern.compile(regex);
            this.limitPerMinute = limitPerMinute;
        }
    }

    private final List<Group> groups = List.of(
        new Group("^/api/conversations/[^/]+/objectives/[^/]+$", 12),
        new Group("^/api/conversations/[^/]+/mini-pathway$", 6),
        new Group("^/api/conversations/[^/]+/details$", 6),
        new Group("^/api/routing/llm$", 30),
        new Group("^/api/extract-profile-facts$", 30),
        new Group("^/api/detect-contradictions$", 20),
        new Group("^/api/pre-check$", 30),
        new Group("^/api/pathway/generate$", 20),
        new Group("^/api/pathway/recommend$", 30),
        new Group("^/api/pathway/explain$", 30),
        new Group("^/api/conversations/[^/]+/generate-title$", 10),
        new Group("^/api/tokens/count$", 30),
        new Group("^/api/benchmark$", 10),
        new Group("^/api/conversations/[^/]+/messages$", 20)
    );

    private static final class Counter {
        long windowStart = System.currentTimeMillis();
        final AtomicInteger count = new AtomicInteger(0);
    }

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        Group matched = null;
        for (Group g : groups) {
            if (g.path.matcher(path).matches()) {
                matched = g;
                break;
            }
        }
        if (matched == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = clientIp(request) + "|" + matched.path.pattern();
        Counter counter = counters.computeIfAbsent(key, k -> new Counter());
        boolean allowed;
        synchronized (counter) {
            long now = System.currentTimeMillis();
            if (now - counter.windowStart >= WINDOW_MILLIS) {
                counter.windowStart = now;
                counter.count.set(0);
            }
            allowed = counter.count.incrementAndGet() <= matched.limitPerMinute;
        }

        if (!allowed) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too many requests\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
