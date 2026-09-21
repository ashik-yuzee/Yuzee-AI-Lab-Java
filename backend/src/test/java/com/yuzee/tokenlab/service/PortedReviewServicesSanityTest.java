package com.yuzee.tokenlab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit sanity check (no Spring context) for the services ported from
 * yuzee-ai-token-lab: TeachingAnswerReviewService, ReviewRetryService and
 * ProviderRecoveryService. Exercises the non-trivial branching in each (review-trigger
 * threshold, block-merge validation, usage summation, and the single-retry/no-retry-on-quota
 * rules) without hitting the network.
 */
class PortedReviewServicesSanityTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TeachingAnswerReviewService teachingReview = new TeachingAnswerReviewService();
    private final ReviewRetryService reviewRetry = new ReviewRetryService();
    private final ProviderRecoveryService providerRecovery = new ProviderRecoveryService();

    // -- TeachingAnswerReviewService.shouldReviewTeaching --------------------------------

    @Test
    void shortAnswerWithFewBlocksIsNotReviewed() throws Exception {
        JsonNode response = mapper.readTree("""
            {"response_intent":"GENERAL_DELIVERY","content_blocks":[{"title":"","text":"Short answer."}]}
            """);
        assertFalse(teachingReview.shouldReviewTeaching(response));
    }

    @Test
    void threeTitledBlocksTriggersReviewEvenIfShort() throws Exception {
        JsonNode response = mapper.readTree("""
            {"response_intent":"GENERAL_DELIVERY","content_blocks":[
              {"title":"One","text":"a"},{"title":"Two","text":"b"},{"title":"Three","text":"c"}
            ]}
            """);
        assertTrue(teachingReview.shouldReviewTeaching(response));
    }

    @Test
    void safetyIntentIsNeverReviewedEvenIfSubstantive() throws Exception {
        String longText = "word ".repeat(150);
        JsonNode response = mapper.readTree(mapper.writeValueAsString(Map.of(
            "response_intent", "SAFETY_PAUSE",
            "content_blocks", List.of(Map.of("title", "T", "text", longText))
        )));
        assertFalse(teachingReview.shouldReviewTeaching(response));
    }

    @Test
    void oneHundredTwentyWordsTriggersReview() throws Exception {
        String longText = "word ".repeat(120);
        JsonNode response = mapper.readTree(mapper.writeValueAsString(Map.of(
            "response_intent", "GENERAL_DELIVERY",
            "content_blocks", List.of(Map.of("title", "", "text", longText))
        )));
        assertTrue(teachingReview.shouldReviewTeaching(response));
    }

    // -- TeachingAnswerReviewService.applyReviewedBlocks ----------------------------------

    @Test
    void applyReviewedBlocksMergesAndPreservesOtherFields() throws Exception {
        JsonNode original = mapper.readTree("""
            {"response_intent":"GENERAL_DELIVERY","schema_version":"1.3","content_blocks":[{"title":"Old","text":"old"}]}
            """);
        JsonNode reviewed = mapper.readTree("""
            {"content_blocks":[{"title":"New","text":"new","items":[{"status":null}]}]}
            """);

        JsonNode result = teachingReview.applyReviewedBlocks(original, reviewed);

        assertEquals("1.3", result.path("schema_version").asText());
        assertEquals("GENERAL_DELIVERY", result.path("response_intent").asText());
        assertEquals("New", result.path("content_blocks").get(0).path("title").asText());
        assertEquals("", result.path("content_blocks").get(0).path("items").get(0).path("status").asText());
    }

    @Test
    void applyReviewedBlocksRejectsExtraTopLevelKeys() throws Exception {
        JsonNode original = mapper.readTree("{\"content_blocks\":[]}");
        JsonNode reviewed = mapper.readTree("""
            {"content_blocks":[{"text":"x"}],"extra_field":true}
            """);
        assertThrows(IllegalStateException.class, () -> teachingReview.applyReviewedBlocks(original, reviewed));
    }

    @Test
    void applyReviewedBlocksRejectsAllEmptyBlocks() throws Exception {
        JsonNode original = mapper.readTree("{\"content_blocks\":[]}");
        JsonNode reviewed = mapper.readTree("""
            {"content_blocks":[{"title":"has a title but no text/items/rows/steps"}]}
            """);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> teachingReview.applyReviewedBlocks(original, reviewed));
        assertEquals("Empty teaching review", ex.getMessage());
    }

    // -- TeachingAnswerReviewService.combineGenerationUsage -------------------------------

    @Test
    void combineGenerationUsageSumsTokenCounts() {
        Map<String, Object> original = Map.of("promptTokenCount", 100, "candidatesTokenCount", 50);
        Map<String, Object> review = Map.of("promptTokenCount", 20, "candidatesTokenCount", 5, "totalTokenCount", 25);

        Map<String, Object> combined = teachingReview.combineGenerationUsage(original, review);

        assertEquals(120L, combined.get("promptTokenCount"));
        assertEquals(55L, combined.get("candidatesTokenCount"));
        assertEquals(25L, combined.get("totalTokenCount"));
    }

    @Test
    void combineGenerationUsageReturnsOriginalWhenNoReviewUsage() {
        Map<String, Object> original = Map.of("promptTokenCount", 100);
        assertEquals(original, teachingReview.combineGenerationUsage(original, null));
    }

    // -- ReviewRetryService.runReview -----------------------------------------------------

    @Test
    void runReviewRetriesOnceThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String result = reviewRetry.runReview(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("Gemini error 503: overloaded");
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(2, calls.get());
    }

    @Test
    void runReviewFailsFastOnConfigurationError() {
        AtomicInteger calls = new AtomicInteger();
        ReviewFailure failure = assertThrows(ReviewFailure.class, () -> reviewRetry.runReview(() -> {
            calls.incrementAndGet();
            throw new RuntimeException("Gemini error 401: bad key");
        }));
        assertEquals(1, calls.get());
        assertEquals(1, failure.getFailures().size());
        assertEquals(ReviewFailureCode.CONFIGURATION, failure.getFailures().get(0));
    }

    @Test
    void runReviewExhaustsAfterTwoTransientFailures() {
        AtomicInteger calls = new AtomicInteger();
        ReviewFailure failure = assertThrows(ReviewFailure.class, () -> reviewRetry.runReview(() -> {
            calls.incrementAndGet();
            throw new RuntimeException("Gemini error 500: down");
        }));
        assertEquals(2, calls.get());
        assertEquals(2, failure.getFailures().size());
        assertTrue(failure.getFailures().stream().allMatch(c -> c == ReviewFailureCode.PROVIDER));
    }

    // -- ProviderRecoveryService.openWithRecovery ------------------------------------------

    @Test
    void openWithRecoveryRetriesOnceOnTransientStatus() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();
        String result = providerRecovery.openWithRecovery(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("Gemini error 503: overloaded");
            }
            return "opened";
        }, retries::incrementAndGet);
        assertEquals("opened", result);
        assertEquals(2, calls.get());
        assertEquals(1, retries.get());
    }

    @Test
    void openWithRecoveryDoesNotRetryDailyQuotaExceeded() {
        AtomicInteger calls = new AtomicInteger();
        Exception thrown = assertThrows(Exception.class, () -> providerRecovery.openWithRecovery(() -> {
            calls.incrementAndGet();
            throw new RuntimeException("Gemini error 429: requests_per_day quota exceeded");
        }, () -> { throw new AssertionError("should not retry a daily-quota error"); }));
        assertEquals(1, calls.get());
        assertTrue(thrown.getMessage().contains("429"));
    }
}
