package com.yuzee.tokenlab.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;

/**
 * SPA fallback: returns index.html for 404s that are NOT API routes.
 * Spring Boot's default static handler serves real assets with correct MIME types.
 */
@Controller
public class SpaController implements ErrorController {

    @RequestMapping("/error")
    @ResponseBody
    public ResponseEntity<Resource> handleError(HttpServletRequest request) throws IOException {
        Object statusAttr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusAttr != null ? Integer.parseInt(statusAttr.toString()) : 404;

        // For API 404s return a plain JSON 404
        String uri = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (uri != null && (uri.startsWith("/api/") || uri.startsWith("/actuator/"))) {
            return ResponseEntity.notFound().build();
        }

        // For all other 404s (SPA routes), serve index.html
        if (status == 404) {
            Resource index = new ClassPathResource("static/index.html");
            if (index.exists()) {
                return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(index);
            }
        }
        return ResponseEntity.status(status).build();
    }
}
