package ai.openclaw.agents.tools;

import ai.openclaw.common.logging.StructuredLogger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Basic web request tools for fetching and searching web content.
 * Maps TypeScript: src/agents/tools/web.ts
 */
public class WebTools {

    private static final StructuredLogger logger = StructuredLogger.create("agents/web-tools");
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final long MAX_RESPONSE_BYTES = 5 * 1024 * 1024; // 5MB

    private final HttpClient httpClient;

    public WebTools() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public WebTools(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public record FetchParams(
        String url,
        String method,
        Map<String, String> headers,
        String body,
        Long timeoutSeconds
    ) {
        public FetchParams {
            method = method != null ? method.toUpperCase() : "GET";
            timeoutSeconds = timeoutSeconds != null ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        }

        public static FetchParams get(String url) {
            return new FetchParams(url, "GET", null, null, null);
        }

        public static FetchParams post(String url, String body) {
            return new FetchParams(url, "POST", null, body, null);
        }
    }

    public record FetchResult(
        boolean success,
        int statusCode,
        String body,
        Map<String, String> headers,
        String error,
        Long durationMs
    ) {
        public static FetchResult success(int statusCode, String body, Map<String, String> headers, long durationMs) {
            return new FetchResult(true, statusCode, body, headers, null, durationMs);
        }

        public static FetchResult error(String error) {
            return new FetchResult(false, 0, null, null, error, null);
        }

        public static FetchResult error(int statusCode, String error) {
            return new FetchResult(false, statusCode, null, null, error, null);
        }
    }

    public record SearchParams(
        String query,
        Integer limit,
        String lang
    ) {
        public SearchParams {
            limit = limit != null ? limit : 10;
            lang = lang != null ? lang : "en";
        }
    }

    public record SearchResult(
        boolean success,
        String query,
        java.util.List<SearchItem> results,
        String error
    ) {
        public static SearchResult success(String query, java.util.List<SearchItem> results) {
            return new SearchResult(true, query, results, null);
        }

        public static SearchResult error(String error) {
            return new SearchResult(false, null, null, error);
        }
    }

    public record SearchItem(
        String title,
        String url,
        String snippet,
        String source
    ) {}

    /**
     * Fetch content from a URL.
     */
    public FetchResult fetch(FetchParams params) {
        long startTime = System.currentTimeMillis();

        try {
            URI uri = new URI(params.url());

            // Validate URL scheme
            if (!uri.getScheme().equals("http") && !uri.getScheme().equals("https")) {
                return FetchResult.error("Only HTTP and HTTPS URLs are supported");
            }

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(params.timeoutSeconds()));

            // Set default headers
            requestBuilder.header("User-Agent", "OpenClaw-Agent/1.0");
            requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            requestBuilder.header("Accept-Language", "en-US,en;q=0.5");
            requestBuilder.header("Accept-Encoding", "identity");

            // Add custom headers
            if (params.headers() != null) {
                params.headers().forEach(requestBuilder::header);
            }

            // Set method and body
            HttpRequest.BodyPublisher bodyPublisher = params.body() != null
                ? HttpRequest.BodyPublishers.ofString(params.body(), StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody();

            requestBuilder.method(params.method(), bodyPublisher);

            HttpRequest request = requestBuilder.build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            long durationMs = System.currentTimeMillis() - startTime;

            // Truncate if too large
            String body = response.body();
            if (body.length() > MAX_RESPONSE_BYTES) {
                body = body.substring(0, (int) MAX_RESPONSE_BYTES) +
                    "\n[Truncated: response exceeded " + MAX_RESPONSE_BYTES + " bytes]";
            }

            Map<String, String> responseHeaders = new java.util.HashMap<>();
            response.headers().map().forEach((k, v) -> {
                if (!v.isEmpty()) {
                    responseHeaders.put(k, v.get(0));
                }
            });

            logger.debug("Fetch completed",
                Map.of("url", params.url(), "status", response.statusCode(), "durationMs", durationMs));

            return FetchResult.success(response.statusCode(), body, responseHeaders, durationMs);

        } catch (URISyntaxException e) {
            logger.error("Invalid URL", Map.of("url", params.url(), "error", e.getMessage()));
            return FetchResult.error("Invalid URL: " + e.getMessage());
        } catch (HttpTimeoutException e) {
            logger.error("Request timeout", Map.of("url", params.url(), "error", e.getMessage()));
            return FetchResult.error("Request timed out after " + params.timeoutSeconds() + " seconds");
        } catch (IOException e) {
            logger.error("IO error", Map.of("url", params.url(), "error", e.getMessage()));
            return FetchResult.error("IO error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Request interrupted", Map.of("url", params.url()));
            return FetchResult.error("Request was interrupted");
        }
    }

    /**
     * Simple GET request.
     */
    public FetchResult get(String url) {
        return fetch(FetchParams.get(url));
    }

    /**
     * Search the web.
     * Note: This is a stub implementation. Real implementation would use
     * a search API like Serper, Google Custom Search, or similar.
     */
    public SearchResult search(SearchParams params) {
        // This is a placeholder - real implementation would integrate with a search API
        logger.warn("Web search not implemented - stub returning empty results",
            Map.of("query", params.query()));

        return SearchResult.error(
            "Web search requires a search API integration (e.g., Serper, Google Custom Search). " +
            "Configure a search provider to enable this feature.");
    }

    /**
     * Extract text content from HTML (basic implementation).
     */
    public String extractText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        // Basic HTML tag removal - for production, use a proper HTML parser like jsoup
        String text = html
            .replaceAll("<script[^>]*>.*?</script>", " ")
            .replaceAll("<style[^>]*>.*?</style>", " ")
            .replaceAll("<[^>]+>", " ")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("&amp;", "&")
            .replaceAll("&quot;", "\"")
            .replaceAll("&#39;", "'")
            .replaceAll("&nbsp;", " ")
            .replaceAll("\\s+", " ")
            .trim();

        return text;
    }

    /**
     * Check if a URL is reachable.
     */
    public boolean isReachable(String url, long timeoutSeconds) {
        try {
            URI uri = new URI(url);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validate URL format.
     */
    public static boolean isValidUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(url);
            return uri.getScheme() != null && (uri.getScheme().equals("http") || uri.getScheme().equals("https"));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
