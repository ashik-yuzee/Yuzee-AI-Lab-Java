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
                .requestMatchers("/api/auth/login", "/api/db-status", "/actuator/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(hmacTokenFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, HmacTokenFilter.class);
        return http.build();
    }
}
