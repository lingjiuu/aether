package io.github.lingjiuu.tool.permission;

import io.github.lingjiuu.tool.ToolRiskLevel;

import java.util.Map;

public record ApprovalRequest(
        ApprovalId id,
        String toolName,
        String toolCallId,
        ToolRiskLevel riskLevel,
        Map<String, Object> arguments,
        String reason
) {

    public ApprovalRequest {
        if (id == null) {
            throw new IllegalArgumentException("approval id must not be null");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
