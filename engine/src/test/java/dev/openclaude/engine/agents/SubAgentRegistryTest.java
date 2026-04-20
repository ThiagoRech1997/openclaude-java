package dev.openclaude.engine.agents;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentRegistryTest {

    @Test
    void constructor_registersBuiltins() {
        SubAgentRegistry registry = new SubAgentRegistry();

        SubAgentDefinition gp = registry.get("general-purpose");
        SubAgentDefinition explore = registry.get("Explore");
        SubAgentDefinition plan = registry.get("Plan");

        assertNotNull(gp);
        assertNotNull(explore);
        assertNotNull(plan);
        assertEquals("built-in", gp.source());
        assertEquals("built-in", explore.source());
        assertEquals("built-in", plan.source());
        assertNotNull(explore.toolFilter(), "built-ins must carry a predicate-based tool filter");
    }

    @Test
    void get_unknownName_returnsNull_fallbackIsGeneralPurpose() {
        SubAgentRegistry registry = new SubAgentRegistry();

        assertNull(registry.get("nonexistent"));
        assertEquals("general-purpose", registry.fallback().name());
    }

    @Test
    void get_nullName_returnsGeneralPurpose() {
        SubAgentRegistry registry = new SubAgentRegistry();
        SubAgentDefinition def = registry.get(null);
        assertNotNull(def);
        assertEquals("general-purpose", def.name());
    }

    @Test
    void register_overwritesByName() {
        SubAgentRegistry registry = new SubAgentRegistry();
        SubAgentDefinition custom = new SubAgentDefinition(
                "Explore", "custom", "You are a custom explorer.",
                List.of("FileRead"), null, null, "user");
        registry.register(custom);

        SubAgentDefinition got = registry.get("Explore");
        assertEquals("user", got.source());
        assertEquals("custom", got.description());
        assertEquals(List.of("FileRead"), got.toolWhitelist());
    }

    @Test
    void names_includesBuiltinsInOrder() {
        List<String> names = new SubAgentRegistry().names();
        assertTrue(names.contains("general-purpose"));
        assertTrue(names.contains("Explore"));
        assertTrue(names.contains("Plan"));
    }
}
