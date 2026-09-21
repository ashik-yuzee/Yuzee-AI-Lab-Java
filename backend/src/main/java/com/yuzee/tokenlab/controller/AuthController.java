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
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (!adminUsername.equals(username) || !adminPassword.equals(password)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
        String token = hmacFilter.generateToken(username, password);
        return ResponseEntity.ok(Map.of("token", token, "username", username));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/check")
    public ResponseEntity<?> check(HttpServletRequest request) {
        // Auth is verified by the filter; if we get here the token is valid
        return ResponseEntity.ok(Map.of("authenticated", true, "username", "admin"));
    }
}
