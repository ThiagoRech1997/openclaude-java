package dev.openclaude.core.session;

import dev.openclaude.core.model.Usage;

import java.util.Map;

/**
 * Tracks token usage and estimates cost per provider/model.
 * Prices are per million tokens as of 2025.
 */
public final class CostTracker {

    private CostTracker() {}

    // Pricing: { model_prefix -> [input_per_M, output_per_M, cache_read_per_M] }
    private static final Map<String, double[]> PRICING = Map.ofEntries(
            // Anthropic
            Map.entry("claude-opus", new double[]{15.0, 75.0, 1.50}),
            Map.entry("claude-sonnet", new double[]{3.0, 15.0, 0.30}),
            Map.entry("claude-haiku", new double[]{0.80, 4.0, 0.08}),
            // OpenAI
            Map.entry("gpt-4o", new double[]{2.50, 10.0, 0.0}),
            Map.entry("gpt-4o-mini", new double[]{0.15, 0.60, 0.0}),
            Map.entry("gpt-4.1", new double[]{2.0, 8.0, 0.0}),
            Map.entry("gpt-4.1-mini", new double[]{0.40, 1.60, 0.0}),
            Map.entry("gpt-4.1-nano", new double[]{0.10, 0.40, 0.0}),
            Map.entry("o3", new double[]{2.0, 8.0, 0.0}),
            Map.entry("o3-mini", new double[]{1.10, 4.40, 0.0}),
            Map.entry("o4-mini", new double[]{1.10, 4.40, 0.0}),
            // DeepSeek
            Map.entry("deepseek", new double[]{0.27, 1.10, 0.0}),
            // Gemini
            Map.entry("gemini-2.5", new double[]{1.25, 10.0, 0.0}),
            Map.entry("gemini-2.0", new double[]{0.10, 0.40, 0.0}),
            // Local/free
            Map.entry("llama", new double[]{0.0, 0.0, 0.0}),
            Map.entry("qwen", new double[]{0.0, 0.0, 0.0}),
            Map.entry("mistral", new double[]{0.25, 0.25, 0.0})
    );

    /**
     * Estimate cost in USD for a given usage and model.
     */
    public static double estimateCost(Usage usage, String model) {
        double[] prices = findPricing(model);
        double inputCost = usage.inputTokens() * prices[0] / 1_000_000;
        double outputCost = usage.outputTokens() * prices[1] / 1_000_000;
        double cacheCost = usage.cacheReadInputTokens() * prices[2] / 1_000_000;
        return inputCost + outputCost + cacheCost;
    }

    /**
     * Format a cost breakdown string.
     */
    public static String formatBreakdown(Usage usage, String model) {
        double[] prices = findPricing(model);
        double inputCost = usage.inputTokens() * prices[0] / 1_000_000;
        double outputCost = usage.outputTokens() * prices[1] / 1_000_000;
        double cacheCost = usage.cacheReadInputTokens() * prices[2] / 1_000_000;
        double total = inputCost + outputCost + cacheCost;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  Input:  %,8d tokens × $%.2f/M = $%.4f%n",
                usage.inputTokens(), prices[0], inputCost));
        sb.append(String.format("  Output: %,8d tokens × $%.2f/M = $%.4f%n",
                usage.outputTokens(), prices[1], outputCost));
        if (usage.cacheReadInputTokens() > 0) {
            sb.append(String.format("  Cache:  %,8d tokens × $%.2f/M = $%.4f%n",
                    usage.cacheReadInputTokens(), prices[2], cacheCost));
        }
        sb.append(String.format("  Total:  $%.4f", total));
        return sb.toString();
    }

    private static double[] findPricing(String model) {
        if (model == null) return new double[]{3.0, 15.0, 0.30}; // default sonnet

        String lower = model.toLowerCase();
        for (var entry : PRICING.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        // Default: Claude Sonnet pricing
        return new double[]{3.0, 15.0, 0.30};
    }
}
