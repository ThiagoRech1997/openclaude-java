package dev.openclaude.core.permissions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PermissionManagerTest {

    @Test
    void default_readOnlyAllowed_mutatingAsks() {
        PermissionManager pm = new PermissionManager(PermissionMode.DEFAULT);
        assertEquals(PermissionManager.PermissionDecision.ALLOWED, pm.check("Read", true));
        assertEquals(PermissionManager.PermissionDecision.ASK, pm.check("Bash", false));
    }

    @Test
    void autoApprove_alwaysAllowed() {
        PermissionManager pm = new PermissionManager(PermissionMode.AUTO_APPROVE);
        assertEquals(PermissionManager.PermissionDecision.ALLOWED, pm.check("Bash", false));
        assertEquals(PermissionManager.PermissionDecision.ALLOWED, pm.check("Read", true));
    }

    @Test
    void autoDeny_deniesMutatingButAllowsReadOnly() {
        PermissionManager pm = new PermissionManager(PermissionMode.AUTO_DENY);
        assertEquals(PermissionManager.PermissionDecision.ALLOWED, pm.check("Read", true));
        assertEquals(PermissionManager.PermissionDecision.DENIED, pm.check("Bash", false));
    }

    @Test
    void plan_sameAsAutoDenyForMutations() {
        PermissionManager pm = new PermissionManager(PermissionMode.PLAN);
        assertEquals(PermissionManager.PermissionDecision.ALLOWED, pm.check("Read", true));
        assertEquals(PermissionManager.PermissionDecision.DENIED, pm.check("FileWrite", false));
    }

    @Test
    void alwaysAllow_bypassesMode() {
        PermissionManager pm = new PermissionManager(PermissionMode.AUTO_DENY);
        pm.addAlwaysAllow("Bash");
        assertEquals(PermissionManager.PermissionDecision.ALLOWED, pm.check("Bash", false));
    }

    @Test
    void alwaysDeny_takesPriorityOverAutoApprove() {
        PermissionManager pm = new PermissionManager(PermissionMode.AUTO_APPROVE);
        pm.addAlwaysDeny("Bash");
        assertEquals(PermissionManager.PermissionDecision.DENIED, pm.check("Bash", false));
    }

    @Test
    void addAlwaysAllow_clearsPriorAlwaysDeny() {
        PermissionManager pm = new PermissionManager(PermissionMode.DEFAULT);
        pm.addAlwaysDeny("Bash");
        assertEquals(PermissionManager.PermissionDecision.DENIED, pm.check("Bash", false));
        pm.addAlwaysAllow("Bash");
        assertEquals(PermissionManager.PermissionDecision.ALLOWED, pm.check("Bash", false));
        assertFalse(pm.getDeniedTools().contains("Bash"));
    }

    @Test
    void addAlwaysDeny_clearsPriorAlwaysAllow() {
        PermissionManager pm = new PermissionManager(PermissionMode.DEFAULT);
        pm.addAlwaysAllow("Bash");
        pm.addAlwaysDeny("Bash");
        assertEquals(PermissionManager.PermissionDecision.DENIED, pm.check("Bash", false));
        assertFalse(pm.getAllowedTools().contains("Bash"));
    }

    @Test
    void recordDenial_incrementsCount() {
        PermissionManager pm = new PermissionManager();
        pm.recordDenial("Bash");
        pm.recordDenial("Bash");
        pm.recordDenial("Edit");
        assertEquals(2, pm.getDenialCount("Bash"));
        assertEquals(1, pm.getDenialCount("Edit"));
        assertEquals(0, pm.getDenialCount("Missing"));
    }

    @Test
    void setMode_switchesBehavior() {
        PermissionManager pm = new PermissionManager(PermissionMode.DEFAULT);
        assertEquals(PermissionManager.PermissionDecision.ASK, pm.check("Bash", false));
        pm.setMode(PermissionMode.AUTO_APPROVE);
        assertEquals(PermissionManager.PermissionDecision.ALLOWED, pm.check("Bash", false));
    }
}
