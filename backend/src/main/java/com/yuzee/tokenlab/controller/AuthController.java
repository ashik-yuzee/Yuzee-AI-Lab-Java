package com.yuzee.tokenlab.controller;

import com.yuzee.tokenlab.auth.HmacTokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Value("${auth.admin-username}")
    private String adminUsername;

    @Value("${auth.admin-password}")
    private String adminPassword;

    private final HmacTokenFilter hmacFilter;

    public AuthController(HmacTokenFilter hmacFilter) {
        this.hmacFilter = hmacFilter;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) Map<String, Object> body) {
        Object username = body != null ? body.get("username") : null;
        Object password = body != null ? body.get("password") : null;
        if (!adminUsername.equals(username) || !adminPassword.equals(password)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
        String token = hmacFilter.generateToken(adminUsername, adminPassword);
        return ResponseEntity.ok(Map.of("token", token));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/check")
    public ResponseEntity<?> check(HttpServletRequest request) {
        // Unauthenticated route, as the original: reports whether the bearer token is valid.
        String auth = request.getHeader("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
        return ResponseEntity.ok(Map.of("authenticated", hmacFilter.validateToken(token)));
    }
}
