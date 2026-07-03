package dev.openclaude.plugins;

import dev.openclaude.tools.Tool;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Discovers and loads plugins from JAR files.
 *
 * Plugin discovery locations:
 *   1. Java ServiceLoader (classpath)
 *   2. ~/.claude/plugins/ directory (JAR scanning)
 */
public final class PluginLoader {

    private final List<Plugin> loadedPlugins = new ArrayList<>();
    private final List<URLClassLoader> pluginClassLoaders = new ArrayList<>();

    /**
     * Load all plugins from ServiceLoader and the plugins directory.
     */
    public List<Plugin> loadAll() {
        // 1. ServiceLoader (classpath plugins)
        loadFromServiceLoader(ServiceLoader.load(Plugin.class));

        // 2. JAR plugins from ~/.claude/plugins/
        Path pluginDir = getPluginDirectory();
        if (Files.isDirectory(pluginDir)) {
            loadFromDirectory(pluginDir);
        }

        return Collections.unmodifiableList(loadedPlugins);
    }

    /**
     * Get all tools from all loaded plugins.
     */
    public List<Tool> allTools() {
        List<Tool> tools = new ArrayList<>();
        for (Plugin plugin : loadedPlugins) {
            try {
                tools.addAll(plugin.tools());
            } catch (Exception e) {
                System.err.println("Warning: Plugin " + plugin.name() + " failed to provide tools: " + e.getMessage());
            }
        }
        return tools;
    }

    /**
     * Get all loaded plugins.
     */
    public List<Plugin> getPlugins() {
        return Collections.unmodifiableList(loadedPlugins);
    }

    /**
     * Unload all plugins.
     */
    public void unloadAll() {
        for (Plugin plugin : loadedPlugins) {
            try {
                plugin.onUnload();
            } catch (Exception ignored) {}
        }
        loadedPlugins.clear();
        for (URLClassLoader loader : pluginClassLoaders) {
            try {
                loader.close();
            } catch (Exception ignored) {}
        }
        pluginClassLoaders.clear();
    }

    /**
     * Iterate a ServiceLoader defensively: a single malformed provider throws
     * {@link ServiceConfigurationError} (an Error, not Exception) and must not
     * abort CLI startup.
     */
    private void loadFromServiceLoader(ServiceLoader<Plugin> serviceLoader) {
        Iterator<Plugin> it = serviceLoader.iterator();
        while (true) {
            boolean hasNext;
            try {
                hasNext = it.hasNext();
            } catch (ServiceConfigurationError e) {
                System.err.println("Warning: plugin discovery aborted: " + e.getMessage());
                return;
            }
            if (!hasNext) return;
            try {
                loadPlugin(it.next());
            } catch (ServiceConfigurationError e) {
                System.err.println("Warning: skipping broken plugin provider: " + e.getMessage());
            }
        }
    }

    private void loadPlugin(Plugin plugin) {
        try {
            plugin.onLoad();
            loadedPlugins.add(plugin);
        } catch (Exception e) {
            System.err.println("Warning: Failed to load plugin " + plugin.name() + ": " + e.getMessage());
        }
    }

    private void loadFromDirectory(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            List<URL> jarUrls = files
                    .filter(p -> p.toString().endsWith(".jar"))
                    .map(p -> {
                        try {
                            return p.toUri().toURL();
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();

            if (jarUrls.isEmpty()) return;

            URLClassLoader classLoader = new URLClassLoader(
                    jarUrls.toArray(new URL[0]),
                    getClass().getClassLoader()
            );
            pluginClassLoaders.add(classLoader);

            loadFromServiceLoader(ServiceLoader.load(Plugin.class, classLoader));
        } catch (Exception e) {
            // Silently skip plugin loading errors
        }
    }

    private static Path getPluginDirectory() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".claude", "plugins");
    }
}
