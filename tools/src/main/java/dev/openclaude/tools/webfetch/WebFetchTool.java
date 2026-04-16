package dev.openclaude.tools.webfetch;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.tools.SchemaBuilder;
import dev.openclaude.tools.Tool;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool that fetches content from a URL and returns it as readable text.
 * HTML pages are converted to markdown-like text for easier consumption by the LLM.
 */
public class WebFetchTool implements Tool {

    private static final int MAX_BODY_BYTES = 5 * 1024 * 1024;   // 5 MB
    private static final int MAX_TEXT_CHARS = 80_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("url", "The URL to fetch content from (HTTP or HTTPS)", true)
            .stringProp("prompt", "Describes what information to extract from the page", true)
            .build();

    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, CachedResponse> cache = new ConcurrentHashMap<>();

    public WebFetchTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "WebFetch";
    }

    @Override
    public String description() {
        return "Fetches content from a URL, converts HTML to readable text, and returns it. "
                + "Use this to retrieve documentation, API references, or other web content. "
                + "The prompt parameter describes what information to extract from the page.";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        String url = input.path("url").asText("").strip();
        String prompt = input.path("prompt").asText("").strip();

        // Validate URL
        if (url.isEmpty()) {
            return ToolResult.error("URL is required.");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // Auto-upgrade to https if no scheme provided
            if (!url.contains("://")) {
                url = "https://" + url;
            } else {
                return ToolResult.error("Only HTTP and HTTPS URLs are supported.");
            }
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid URL: " + e.getMessage());
        }

        // Check cache
        CachedResponse cached = cache.get(url);
        if (cached != null && cached.isValid()) {
            return formatResult(url, prompt, cached.content());
        }

        // Fetch
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "OpenClaude-Java/1.0 (WebFetch Tool)")
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                return ToolResult.error("HTTP " + statusCode + " for " + url);
            }

            // Read body up to MAX_BODY_BYTES
            byte[] bodyBytes;
            try (InputStream is = response.body()) {
                bodyBytes = is.readNBytes(MAX_BODY_BYTES);
            }

            // Detect charset from Content-Type header
            Charset charset = detectCharset(response);
            String body = new String(bodyBytes, charset);

            // Convert based on content type
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String text;
            if (contentType.contains("text/html") || contentType.contains("application/xhtml+xml") || body.stripLeading().startsWith("<!") || body.stripLeading().startsWith("<html")) {
                text = HtmlToText.convert(body);
            } else {
                text = body;
            }

            // Truncate if needed
            if (text.length() > MAX_TEXT_CHARS) {
                text = text.substring(0, MAX_TEXT_CHARS) + "\n\n[Content truncated at " + MAX_TEXT_CHARS + " characters]";
            }

            // Cache the result
            cache.put(url, new CachedResponse(text, Instant.now()));

            // Evict expired entries periodically
            if (cache.size() > 50) {
                evictExpired();
            }

            return formatResult(url, prompt, text);

        } catch (HttpTimeoutException e) {
            return ToolResult.error("Request timed out after " + REQUEST_TIMEOUT.toSeconds() + "s for " + url);
        } catch (IOException e) {
            return ToolResult.error("Failed to fetch " + url + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Request interrupted for " + url);
        }
    }

    private ToolResult formatResult(String url, String prompt, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Fetched content from ").append(url);
        if (!prompt.isEmpty()) {
            sb.append(" for: ").append(prompt);
        }
        sb.append("]\n\n");
        sb.append(content);
        return ToolResult.success(sb.toString());
    }

    private Charset detectCharset(HttpResponse<?> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        Matcher m = CHARSET_PATTERN.matcher(contentType);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (Exception ignored) {
                // Fall through to default
            }
        }
        return StandardCharsets.UTF_8;
    }

    private void evictExpired() {
        cache.entrySet().removeIf(e -> !e.getValue().isValid());
    }

    private record CachedResponse(String content, Instant fetchedAt) {
        boolean isValid() {
            return Instant.now().isBefore(fetchedAt.plus(CACHE_TTL));
        }
    }
}
