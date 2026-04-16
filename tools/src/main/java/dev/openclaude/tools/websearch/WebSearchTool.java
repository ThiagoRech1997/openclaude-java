package dev.openclaude.tools.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openclaude.tools.SchemaBuilder;
import dev.openclaude.tools.Tool;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tool that searches the web using the Brave Search API and returns results
 * with title, URL, and snippet for each match.
 */
public class WebSearchTool implements Tool {

    private static final String API_KEY_ENV = "BRAVE_SEARCH_API_KEY";
    private static final String BASE_URL = "https://api.search.brave.com/res/v1/web/search";
    private static final int DEFAULT_NUM_RESULTS = 10;
    private static final int MAX_NUM_RESULTS = 20;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("query", "The search query to look up on the web", true)
            .intProp("num_results", "Number of results to return (default 10, max 20)", false)
            .build();

    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, CachedResult> cache = new ConcurrentHashMap<>();

    public WebSearchTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "WebSearch";
    }

    @Override
    public String description() {
        return "Searches the web using Brave Search API and returns results with title, URL, and snippet. "
                + "Use this to find up-to-date information, documentation, solutions, and references.";
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
    public boolean isEnabled() {
        String apiKey = System.getenv(API_KEY_ENV);
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        String apiKey = System.getenv(API_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            return ToolResult.error("BRAVE_SEARCH_API_KEY environment variable is not set. "
                    + "Get a free API key at https://brave.com/search/api/");
        }

        String query = input.path("query").asText("").strip();
        if (query.isEmpty()) {
            return ToolResult.error("Search query is required.");
        }

        int numResults = input.path("num_results").asInt(DEFAULT_NUM_RESULTS);
        if (numResults < 1) numResults = 1;
        if (numResults > MAX_NUM_RESULTS) numResults = MAX_NUM_RESULTS;

        // Check cache
        String cacheKey = query + "|" + numResults;
        CachedResult cached = cache.get(cacheKey);
        if (cached != null && cached.isValid()) {
            return ToolResult.success(cached.content());
        }

        // Build request URL
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = BASE_URL + "?q=" + encodedQuery + "&count=" + numResults;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "OpenClaude-Java/1.0 (WebSearch Tool)")
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", apiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            if (statusCode == 401 || statusCode == 403) {
                return ToolResult.error("Authentication failed. Check your BRAVE_SEARCH_API_KEY.");
            }
            if (statusCode == 429) {
                return ToolResult.error("Rate limit exceeded. Try again later.");
            }
            if (statusCode < 200 || statusCode >= 300) {
                return ToolResult.error("Brave Search API returned HTTP " + statusCode);
            }

            String resultText = parseResults(query, response.body());

            // Cache the result
            cache.put(cacheKey, new CachedResult(resultText, Instant.now()));
            if (cache.size() > 50) {
                evictExpired();
            }

            return ToolResult.success(resultText);

        } catch (HttpTimeoutException e) {
            return ToolResult.error("Search request timed out after " + REQUEST_TIMEOUT.toSeconds() + "s");
        } catch (IOException e) {
            return ToolResult.error("Failed to search: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Search request was interrupted.");
        }
    }

    String parseResults(String query, String responseBody) throws IOException {
        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode webResults = root.path("web").path("results");

        StringBuilder sb = new StringBuilder();
        sb.append("[Web search results for: \"").append(query).append("\"]\n");

        if (!webResults.isArray() || webResults.isEmpty()) {
            sb.append("\nNo results found.");
            return sb.toString();
        }

        int index = 1;
        for (JsonNode result : webResults) {
            String title = result.path("title").asText("(no title)");
            String resultUrl = result.path("url").asText("");
            String snippet = result.path("description").asText("");

            sb.append("\n").append(index).append(". ").append(title);
            if (!resultUrl.isEmpty()) {
                sb.append("\n   URL: ").append(resultUrl);
            }
            if (!snippet.isEmpty()) {
                sb.append("\n   ").append(snippet);
            }
            sb.append("\n");
            index++;
        }

        return sb.toString();
    }

    private void evictExpired() {
        cache.entrySet().removeIf(e -> !e.getValue().isValid());
    }

    private record CachedResult(String content, Instant fetchedAt) {
        boolean isValid() {
            return Instant.now().isBefore(fetchedAt.plus(CACHE_TTL));
        }
    }
}
