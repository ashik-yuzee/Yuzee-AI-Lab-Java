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
 * SPA fallback: returns index.html for 404s, like the original's express catch-all.
 * Spring Boot's default static handler serves real assets with correct MIME types.
 */
@Controller
public class SpaController implements ErrorController {

    @RequestMapping("/error")
    @ResponseBody
    public ResponseEntity<Resource> handleError(HttpServletRequest request) throws IOException {
        Object statusAttr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusAttr != null ? Integer.parseInt(statusAttr.toString()) : 404;

        String uri = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (uri != null && uri.startsWith("/actuator/")) {
            return ResponseEntity.notFound().build();
        }

        // Every other unmatched request serves index.html, as the original's catch-all: an unknown
        // path (404) or a known path with no handler for the method (405), including /api paths
        // that passed the auth check.
        if (status == 404 || status == 405) {
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
