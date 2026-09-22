package com.yuzee.tokenlab.service;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit sanity check (no Spring context, no real DNS/network access) for the ported
 * contract.ts safeSourceUrl()/eligibleSource() SSRF boundary. DNS resolution is stubbed via
 * SafeUrlValidator.Resolver so the "resolves to a private IP" case is deterministic instead of
 * depending on live network access.
 */
class SafeUrlValidatorSanityTest {

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @Test
    void rejectsLoopbackLiteralHostBeforeEvenResolving() {
        assertFalse(SafeUrlValidator.safeSourceUrl("https://127.0.0.1/handbook"));
        assertFalse(SafeUrlValidator.safeSourceUrl("https://localhost/handbook"));
    }

    @Test
    void rejectsHostnameThatResolvesToLoopback() throws Exception {
        SafeUrlValidator.Resolver resolvesToLoopback = host -> new InetAddress[] { ip("127.0.0.1") };
        assertFalse(SafeUrlValidator.safeSourceUrl("https://looks-public.example.com/handbook", resolvesToLoopback));
    }

    @Test
    void rejectsHostnameThatResolvesToCloudMetadataAddress() throws Exception {
        // 169.254.169.254 is the well-known cloud-metadata IP (AWS/GCP/Azure instance metadata).
        SafeUrlValidator.Resolver resolvesToMetadata = host -> new InetAddress[] { ip("169.254.169.254") };
        assertFalse(SafeUrlValidator.safeSourceUrl("https://looks-public.example.com/handbook", resolvesToMetadata));
    }

    @Test
    void rejectsNonHttpsAndCredentialledUrls() {
        assertFalse(SafeUrlValidator.safeSourceUrl("http://university.edu.au/handbook"));
        assertFalse(SafeUrlValidator.safeSourceUrl("https://user:pass@university.edu.au/handbook"));
    }

    @Test
    void acceptsLegitimateEduAuUrlThatResolvesToAPublicAddress() throws Exception {
        SafeUrlValidator.Resolver resolvesPublic = host -> new InetAddress[] { ip("203.0.113.10") };
        String url = "https://handbook.university.edu.au/courses/123";
        assertTrue(SafeUrlValidator.safeSourceUrl(url, resolvesPublic));
        assertTrue(SafeUrlValidator.eligibleSource(url, "handbook.university.edu.au", List.of()));
    }

    @Test
    void eligibleSourceAcceptsGovAndAcDomainsAndAnExplicitTrustedAllowlistOnly() {
        assertTrue(SafeUrlValidator.eligibleSource("https://www.education.gov.au/page", "Dept of Education", List.of()));
        assertTrue(SafeUrlValidator.eligibleSource("https://www.example.ac.uk/page", "Example", List.of()));
        assertFalse(SafeUrlValidator.eligibleSource("https://randomblog.example.com/page", "Random Blog", List.of()));
        assertTrue(SafeUrlValidator.eligibleSource("https://jobs.trustedpartner.com/page", "Trusted Partner", List.of("trustedpartner.com")));
    }

    @Test
    void eligibleSourceUsesTitleAsHostForGoogleGroundingRedirect() {
        assertTrue(SafeUrlValidator.eligibleSource(
            "https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc123",
            "handbook.university.edu.au", List.of()));
        assertFalse(SafeUrlValidator.eligibleSource(
            "https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc123",
            "Not a hostname at all", List.of()));
    }
}
