package io.github.lingjiuu.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiApprovalRequest {

    private String approvalId;

    private String toolCallId;

    private String toolName;

    private String riskLevel;

    private Map<String, Object> arguments;

    private String reason;
}
