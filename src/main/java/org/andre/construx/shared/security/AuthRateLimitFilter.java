package org.andre.construx.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window, per-client limit for the unauthenticated auth endpoints that can be abused for
 * credential stuffing, mail flooding or invitation token guessing. State is in memory, so it is per application instance.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {
    private static final Set<String> LIMITED_PATHS = Set.of("/api/auth/login", "/api/auth/register",
            "/api/auth/resend", "/api/auth/verify", "/api/auth/refresh",
            "/api/auth/password/forgot", "/api/auth/password/reset",
            "/api/invitations/accept");
    private static final int PRUNE_THRESHOLD = 10_000;

    private record Window(long startMillis, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final long windowMillis;
    private final Clock clock;

    public AuthRateLimitFilter(int maxRequests, Duration window, Clock clock) {
        this.maxRequests = maxRequests;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = clock.millis();
        if (windows.size() > PRUNE_THRESHOLD) windows.values().removeIf(w -> expired(w, now));
        Window window = windows.merge(request.getRemoteAddr() + "|" + request.getRequestURI(), new Window(now, 1),
                (current, fresh) -> expired(current, now) ? fresh : new Window(current.startMillis(), current.count() + 1));
        if (window.count() <= maxRequests) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = Math.max(1, (window.startMillis() + windowMillis - now + 999) / 1000);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"title\":\"Too many requests\",\"status\":429,\"code\":\"TOO_MANY_REQUESTS\"}");
    }

    private boolean expired(Window window, long now) {
        return now - window.startMillis() >= windowMillis;
    }
}
