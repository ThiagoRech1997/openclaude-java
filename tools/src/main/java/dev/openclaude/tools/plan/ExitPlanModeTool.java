package dev.openclaude.tools.plan;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openclaude.core.permissions.PermissionManager;
import dev.openclaude.core.permissions.PermissionMode;
import dev.openclaude.tools.SchemaBuilder;
import dev.openclaude.tools.Tool;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;

/**
 * Lets the model signal "the plan is ready" while in plan mode. The plan is
 * shown to the user for approval; approving switches the session back to
 * {@link PermissionMode#DEFAULT} so the model can implement, rejecting keeps
 * plan mode active so the model revises.
 *
 * <p>The approval UI is injected by the interactive layer via
 * {@link #setApprovalHandler}; without one (print/serve mode) the tool refuses
 * to exit plan mode.
 */
public class ExitPlanModeTool implements Tool {

    /** Presents the plan to the user and returns whether it was approved. */
    public interface ApprovalHandler {
        boolean approvePlan(String plan);
    }

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("plan",
                    "The full implementation plan in markdown, to be shown to the user for approval.",
                    true)
            .build();

    private final PermissionManager permissions;
    private volatile ApprovalHandler approvalHandler;

    public ExitPlanModeTool(PermissionManager permissions) {
        this.permissions = permissions;
    }

    public void setApprovalHandler(ApprovalHandler handler) {
        this.approvalHandler = handler;
    }

    @Override
    public String name() {
        return "ExitPlanMode";
    }

    @Override
    public String description() {
        return "Signal that your plan is complete and ask the user to approve it. "
                + "Only usable in plan mode. If approved, plan mode ends and you may implement; "
                + "if rejected, revise the plan and try again.";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public boolean isReadOnly() {
        return true; // must be callable under the plan-mode permission gate
    }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        if (permissions == null || permissions.getMode() != PermissionMode.PLAN) {
            return ToolResult.error("Not in plan mode — ExitPlanMode only applies "
                    + "after the user enables it (/permissions plan).");
        }

        String plan = input.path("plan").asText("");
        if (plan.isBlank()) {
            return ToolResult.error("A non-empty plan is required.");
        }

        ApprovalHandler handler = approvalHandler;
        if (handler == null) {
            return ToolResult.error("Plan approval requires an interactive session. "
                    + "Staying in plan mode; present the plan as text instead.");
        }

        if (handler.approvePlan(plan)) {
            permissions.setMode(PermissionMode.DEFAULT);
            return ToolResult.success("User approved the plan. Plan mode is now off — "
                    + "proceed with the implementation.");
        }
        return ToolResult.error("User rejected the plan. Stay in plan mode and revise "
                + "the plan according to their feedback before trying again.");
    }
}
