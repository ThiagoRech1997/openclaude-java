package dev.openclaude.core.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Loads {@link HookConfig} from {@code settings.json} files.
 *
 * <p>Merge order (later overrides earlier):
 * <ol>
 *   <li>{@code ~/.claude/settings.json} — user-level</li>
 *   <li>{@code <cwd>/.claude/settings.json} — project-local</li>
 * </ol>
 */
public final class HooksConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HooksConfigLoader() {}

    public static HookConfig load(Path workingDirectory) {
        Map<HookEvent, List<HookConfig.HookMatcher>> merged = new EnumMap<>(HookEvent.class);

        Path userSettings = userSettingsPath();
        if (userSettings != null) {
            mergeInto(merged, loadFromFile(userSettings));
        }

        Path projectSettings = workingDirectory.resolve(".claude").resolve("settings.json");
        mergeInto(merged, loadFromFile(projectSettings));

        return new HookConfig(merged);
    }

    /**
     * Parse a single settings.json file. Returns an empty config if the file doesn't
     * exist, is unreadable, or has no {@code hooks} key. Never throws.
     */
    static HookConfig loadFromFile(Path path) {
        if (path == null || !Files.exists(path)) return HookConfig.empty();

        try {
            String content = Files.readString(path);
            JsonNode root = MAPPER.readTree(content);
            JsonNode hooks = root.get("hooks");
            if (hooks == null || !hooks.isObject()) return HookConfig.empty();

            Map<HookEvent, List<HookConfig.HookMatcher>> byEvent = new EnumMap<>(HookEvent.class);
            Iterator<String> eventNames = hooks.fieldNames();
            while (eventNames.hasNext()) {
                String eventName = eventNames.next();
                HookEvent event = HookEvent.fromJsonName(eventName);
                if (event == null) continue;

                JsonNode matchersNode = hooks.get(eventName);
                if (matchersNode == null || !matchersNode.isArray()) continue;

                List<HookConfig.HookMatcher> matchers = new ArrayList<>();
                for (JsonNode entry : matchersNode) {
                    HookConfig.HookMatcher parsed = parseMatcher(entry);
                    if (parsed != null) matchers.add(parsed);
                }
                if (!matchers.isEmpty()) {
                    byEvent.put(event, matchers);
                }
            }

            return new HookConfig(byEvent);
        } catch (IOException e) {
            return HookConfig.empty();
        }
    }

    private static HookConfig.HookMatcher parseMatcher(JsonNode entry) {
        if (entry == null || !entry.isObject()) return null;

        String matcherStr = entry.path("matcher").asText(null);
        Pattern pattern = null;
        if (matcherStr != null && !matcherStr.isEmpty()) {
            try {
                pattern = Pattern.compile(matcherStr);
            } catch (PatternSyntaxException e) {
                return null;
            }
        }

        JsonNode hooksArr = entry.get("hooks");
        if (hooksArr == null || !hooksArr.isArray()) return null;

        List<HookConfig.HookCommand> commands = new ArrayList<>();
        for (JsonNode h : hooksArr) {
            String type = h.path("type").asText("command");
            String command = h.path("command").asText(null);
            if (command == null || command.isBlank()) continue;
            int timeout = h.path("timeout").asInt(HookConfig.DEFAULT_TIMEOUT_SECONDS);
            commands.add(new HookConfig.HookCommand(type, command, timeout));
        }
        if (commands.isEmpty()) return null;

        return new HookConfig.HookMatcher(pattern, commands);
    }

    private static void mergeInto(
            Map<HookEvent, List<HookConfig.HookMatcher>> target,
            HookConfig source
    ) {
        for (var entry : source.byEvent().entrySet()) {
            target.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
        }
    }

    private static Path userSettingsPath() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return null;
        return Path.of(home, ".claude", "settings.json");
    }
}
