package com.yuzee.tokenlab.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Port of server.ts app.use(express.json({ limit: '500kb' })): JSON bodies over 500kb (512000 bytes)
 * get 413 before auth runs, as express.json is mounted before the /api auth middleware.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BodyLimitFilter extends OncePerRequestFilter {

    static final long LIMIT_BYTES = 500 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String type = request.getContentType();
        // ponytail: trusts Content-Length (the Angular client always sends it); count streamed bytes if chunked uploads appear
        if (type != null && type.toLowerCase().contains("json") && request.getContentLengthLong() > LIMIT_BYTES) {
            response.setStatus(413);
            response.setContentType("text/plain;charset=utf-8");
            response.getWriter().write("request entity too large");
            return;
        }
        chain.doFilter(request, response);
    }
}
