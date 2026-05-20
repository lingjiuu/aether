package io.github.lingjiuu.event;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.llm.TokenUsageInfo;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.permission.ApprovalRequest;
import io.github.lingjiuu.tool.permission.ApprovalResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiEvent {

    private UiEventType type;

    private String sessionId;

    private Integer turn;

    private String delta;

    private String text;

    private UserMessage userMessage;

    private AssistantMessage assistantMessage;

    private ToolCallContent toolCall;

    private ToolResultMessage toolResult;

    private ContextMessage contextMessage;

    private ToolExecutionResult partialToolResult;

    private ApprovalRequest approvalRequest;

    private ApprovalResponse approvalResponse;

    private String errorMessage;

    private Integer originalMessageCount;

    private Integer replacementMessageCount;

    private TokenUsageInfo tokenUsageInfo;

    private Long contextTokenUsage;

    private Long autoCompactTokenLimit;
}
