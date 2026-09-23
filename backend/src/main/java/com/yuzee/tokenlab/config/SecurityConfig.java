package com.yuzee.tokenlab.config;

import com.yuzee.tokenlab.auth.HmacTokenFilter;
import com.yuzee.tokenlab.filter.RateLimitFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final HmacTokenFilter hmacTokenFilter;
    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(HmacTokenFilter hmacTokenFilter, RateLimitFilter rateLimitFilter) {
        this.hmacTokenFilter = hmacTokenFilter;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                // Same bypass as the original: /api/auth/* and /api/db-status are public.
                .requestMatchers("/api/auth/**", "/api/db-status", "/actuator/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            // Original requireAuth: 401 {"error":"Unauthorized"}.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> writeUnauthorized(res))
                .accessDeniedHandler((req, res, e) -> writeUnauthorized(res)))
            .addFilterBefore(hmacTokenFilter, UsernamePasswordAuthenticationFilter.class)
            // Route-level makeRateLimit runs after the global auth check, as in the original.
            .addFilterAfter(rateLimitFilter, AuthorizationFilter.class);
        return http.build();
    }

    private static void writeUnauthorized(jakarta.servlet.http.HttpServletResponse res) throws java.io.IOException {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"Unauthorized\"}");
    }
}
