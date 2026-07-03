package dev.openclaude.tools.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.core.permissions.PermissionManager;
import dev.openclaude.core.permissions.PermissionMode;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ExitPlanModeToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolUseContext context = new ToolUseContext(Path.of("."));

    private static ObjectNode input(String plan) {
        return MAPPER.createObjectNode().put("plan", plan);
    }

    @Test
    void outsidePlanMode_isError() {
        PermissionManager pm = new PermissionManager(PermissionMode.DEFAULT);
        ExitPlanModeTool tool = new ExitPlanModeTool(pm);
        tool.setApprovalHandler(plan -> true);

        ToolResult result = tool.execute(input("do things"), context);

        assertTrue(result.isError());
        assertEquals(PermissionMode.DEFAULT, pm.getMode());
    }

    @Test
    void blankPlan_isError() {
        PermissionManager pm = new PermissionManager(PermissionMode.PLAN);
        ExitPlanModeTool tool = new ExitPlanModeTool(pm);
        tool.setApprovalHandler(plan -> true);

        assertTrue(tool.execute(input(""), context).isError());
        assertEquals(PermissionMode.PLAN, pm.getMode());
    }

    @Test
    void withoutApprovalHandler_staysInPlanMode() {
        PermissionManager pm = new PermissionManager(PermissionMode.PLAN);
        ExitPlanModeTool tool = new ExitPlanModeTool(pm);

        ToolResult result = tool.execute(input("the plan"), context);

        assertTrue(result.isError());
        assertTrue(result.textContent().contains("interactive"));
        assertEquals(PermissionMode.PLAN, pm.getMode());
    }

    @Test
    void approvedPlan_switchesModeToDefault() {
        PermissionManager pm = new PermissionManager(PermissionMode.PLAN);
        ExitPlanModeTool tool = new ExitPlanModeTool(pm);
        AtomicReference<String> shownPlan = new AtomicReference<>();
        tool.setApprovalHandler(plan -> {
            shownPlan.set(plan);
            return true;
        });

        ToolResult result = tool.execute(input("1. do x\n2. do y"), context);

        assertFalse(result.isError());
        assertTrue(result.textContent().contains("approved"));
        assertEquals("1. do x\n2. do y", shownPlan.get(),
                "the handler must receive the full plan text");
        assertEquals(PermissionMode.DEFAULT, pm.getMode());
    }

    @Test
    void rejectedPlan_staysInPlanMode() {
        PermissionManager pm = new PermissionManager(PermissionMode.PLAN);
        ExitPlanModeTool tool = new ExitPlanModeTool(pm);
        tool.setApprovalHandler(plan -> false);

        ToolResult result = tool.execute(input("the plan"), context);

        assertTrue(result.isError());
        assertTrue(result.textContent().contains("rejected"));
        assertEquals(PermissionMode.PLAN, pm.getMode());
    }
}
