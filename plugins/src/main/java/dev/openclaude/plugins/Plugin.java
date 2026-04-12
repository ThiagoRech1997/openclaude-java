package dev.openclaude.plugins;

import dev.openclaude.tools.Tool;

import java.util.List;

/**
 * Plugin SPI (Service Provider Interface) for extending openclaude-java.
 *
 * Plugins can provide:
 *   - Additional tools
 *   - Additional commands (future)
 *
 * To create a plugin:
 *   1. Implement this interface
 *   2. Create META-INF/services/dev.openclaude.plugins.Plugin
 *   3. Package as a JAR and place in ~/.claude/plugins/
 *
 * Example META-INF/services/dev.openclaude.plugins.Plugin:
 *   com.example.MyPlugin
 */
public interface Plugin {

    /** Unique plugin name. */
    String name();

    /** Plugin version string. */
    String version();

    /** Short description. */
    String description();

    /**
     * Provide additional tools to the agent.
     *
     * @return list of tools, or empty list
     */
    List<Tool> tools();

    /**
     * Called when the plugin is loaded. Use for initialization.
     */
    default void onLoad() {}

    /**
     * Called when the plugin is unloaded. Use for cleanup.
     */
    default void onUnload() {}
}
