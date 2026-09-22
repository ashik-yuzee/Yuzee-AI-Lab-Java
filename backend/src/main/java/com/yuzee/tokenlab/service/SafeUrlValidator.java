package com.yuzee.tokenlab.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Port of yuzee-ai-token-lab/src/research/contract.ts's safeSourceUrl()/eligibleSource(). This is
 * a real SSRF-safety boundary for URLs Gemini's search grounding hands back -- not a nice-to-have.
 * <p>
 * safeSourceUrl() rejects anything that isn't a plain https URL with a dotted public hostname,
 * AND resolves the hostname (a hostname can point at a private IP even when it doesn't look like
 * one, e.g. a wildcard-DNS service mapping a public-looking name to 169.254.169.254) and rejects
 * loopback/link-local/site-local/multicast/any-local addresses.
 */
public final class SafeUrlValidator {

    private SafeUrlValidator() {
    }

    /** Resolves a hostname to its IP addresses. Swappable so tests never depend on real DNS/network access. */
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final Resolver DEFAULT_RESOLVER = InetAddress::getAllByName;

    private static final Pattern LITERAL_PRIVATE_HOST = Pattern.compile(
        "^(localhost|127\\.|0\\.|10\\.|192\\.168\\.|169\\.254\\.|172\\.(1[6-9]|2\\d|3[01])\\.)");
    private static final Pattern EDU_GOV_AC = Pattern.compile("\\.(edu|gov)(\\.[a-z]{2})?$|\\.ac\\.[a-z]{2}$");
    private static final Pattern LABELLED_HOST = Pattern.compile("^[a-z0-9.-]+\\.[a-z]{2,}$", Pattern.CASE_INSENSITIVE);

    /** Public API, matching contract.ts's safeSourceUrl(value) signature exactly. */
    public static boolean safeSourceUrl(String value) {
        return safeSourceUrl(value, DEFAULT_RESOLVER);
    }

    static boolean safeSourceUrl(String value, Resolver resolver) {
        if (value == null) return false;
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return false;
        }
        if (!"https".equals(uri.getScheme())) return false;
        if (uri.getUserInfo() != null) return false;
        String host = uri.getHost();
        if (host == null || !host.contains(".")) return false;
        String lowerHost = host.toLowerCase();
        if (LITERAL_PRIVATE_HOST.matcher(lowerHost).find()) return false;
        if (lowerHost.endsWith(".local") || lowerHost.endsWith(".internal")) return false;
        return !resolvesToPrivateAddress(lowerHost, resolver);
    }

    private static boolean resolvesToPrivateAddress(String host, Resolver resolver) {
        try {
            InetAddress[] addresses = resolver.resolve(host);
            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                    || addr.isAnyLocalAddress() || addr.isMulticastAddress() || isUniqueLocalIPv6(addr)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            // Can't verify where it points -- treat as unsafe rather than let it through unchecked.
            return true;
        }
    }

    /** java.net.InetAddress has no isUniqueLocalAddress(); fc00::/7 is the IPv6 private range. */
    private static boolean isUniqueLocalIPv6(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /**
     * Eligible source domain: .edu / .gov (optionally .gov.xx / .edu.xx) / .ac.xx, or an explicit
     * trusted allowlist. Google's grounding redirect (vertexaisearch.cloud.google.com) carries the
     * real source hostname in the chunk's title instead of its URL -- use that when present.
     */
    public static boolean eligibleSource(String url, String title, List<String> trustedDomains) {
        String host;
        try {
            host = new URI(url).getHost();
        } catch (URISyntaxException e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase();
        String trimmedTitle = title == null ? "" : title.trim();
        String labelledHost = LABELLED_HOST.matcher(trimmedTitle).matches() ? trimmedTitle.toLowerCase() : "";
        String domain = "vertexaisearch.cloud.google.com".equals(host) ? labelledHost : host;
        if (domain.isEmpty()) return false;
        if (EDU_GOV_AC.matcher(domain).find()) return true;
        if (trustedDomains == null) return false;
        for (String trusted : trustedDomains) {
            if (trusted == null || trusted.isBlank()) continue;
            String t = trusted.toLowerCase();
            if (domain.equals(t) || domain.endsWith("." + t)) return true;
        }
        return false;
    }
}
