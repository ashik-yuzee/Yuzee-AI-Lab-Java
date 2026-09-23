package com.yuzee.tokenlab.controller;

import com.yuzee.tokenlab.service.ChatTurnService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Plain-JUnit check that Gemini failures map to server.ts's typed error codes. */
class ProviderErrorCodeSanityTest {

    private static String code(String message) {
        return ChatTurnService.classifyProviderError(message, null)[0];
    }

    @Test
    void mapsProviderFailuresToTypedCodes() {
        assertEquals("AUTH_ERROR", code("Gemini error 400: {\"reason\":\"API_KEY_INVALID\"}"));
        assertEquals("AUTH_ERROR", code("Gemini error 403: {\"status\":\"PERMISSION_DENIED\"}"));
        assertEquals("QUOTA_EXHAUSTED", code("Gemini error 429: Quota exceeded for requests_per_day"));
        assertEquals("RATE_LIMIT", code("Gemini error 429: RESOURCE_EXHAUSTED"));
        assertEquals("RATE_LIMIT", code("Gemini error 429: slow down"));
        assertEquals("PROVIDER_BUSY", code("Gemini error 503: overloaded"));
        assertEquals("PROVIDER_ERROR", code("Gemini error 400: bad request"));
        assertEquals("PROVIDER_ERROR", code(null));
    }
}
