package io.github.lingjiuu.tool;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.hook.ToolHookChain;
import io.github.lingjiuu.tool.permission.DefaultToolPermissionService;
import io.github.lingjiuu.tool.permission.PermissionDecision;
import io.github.lingjiuu.tool.permission.PermissionMode;
import io.github.lingjiuu.tool.permission.ToolPermissionService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolRunner {

    private final ToolRegistry toolRegistry;
    private final ToolArgumentValidator argumentValidator;
    private final ToolPermissionService permissionService;
    private final ToolHookChain hookChain;
    private final ToolResultMapper resultMapper;

    public ToolRunner(ToolRegistry toolRegistry) {
        this(
                toolRegistry,
                new ToolArgumentValidator(),
                new DefaultToolPermissionService(),
                ToolHookChain.empty(),
                new ToolResultMapper()
        );
    }

    public ToolRunner(
            ToolRegistry toolRegistry,
            ToolPermissionService permissionService,
            ToolHookChain hookChain
    ) {
        this(
                toolRegistry,
                new ToolArgumentValidator(),
                permissionService,
                hookChain,
                new ToolResultMapper()
        );
    }

    public ToolRunner(
            ToolRegistry toolRegistry,
            ToolArgumentValidator argumentValidator,
            ToolPermissionService permissionService,
            ToolHookChain hookChain,
            ToolResultMapper resultMapper
    ) {
        if (toolRegistry == null) {
            throw new IllegalArgumentException("toolRegistry must not be null");
        }
        if (argumentValidator == null) {
            throw new IllegalArgumentException("argumentValidator must not be null");
        }
        if (permissionService == null) {
            throw new IllegalArgumentException("permissionService must not be null");
        }
        if (hookChain == null) {
            throw new IllegalArgumentException("hookChain must not be null");
        }
        if (resultMapper == null) {
            throw new IllegalArgumentException("resultMapper must not be null");
        }
        this.toolRegistry = toolRegistry;
        this.argumentValidator = argumentValidator;
        this.permissionService = permissionService;
        this.hookChain = hookChain;
        this.resultMapper = resultMapper;
    }

    public ToolResultMessage run(
            AssistantMessage assistantMessage,
            ToolCallContent toolCall,
            ToolUpdateCallback onUpdate
    ) {
        if (toolCall == null) {
            throw new IllegalArgumentException("toolCall must not be null");
        }

        ToolDefinition definition = toolRegistry.findDefinition(toolCall.getToolName());
        ToolInvocation invocation = ToolInvocation.of(assistantMessage, toolCall, definition);
        if (definition == null) {
            return errorMessage(toolCall, "Unsupported tool: " + safeToolName(toolCall));
        }

        Map<String, Object> arguments;
        try {
            arguments = argumentValidator.validate(definition, toolCall.getArgumentsJson());
        } catch (IllegalArgumentException e) {
            return errorMessage(toolCall, "Invalid tool arguments for " + safeToolName(toolCall) + ": " + e.getMessage());
        }

        ToolExecutionContext context = buildContext(assistantMessage, toolCall, arguments);

        PermissionDecision permissionDecision = safePermissionDecision(
                permissionService.decide(invocation, context)
        );
        if (!permissionDecision.allowed()) {
            return errorMessage(toolCall, renderPermissionRejection(permissionDecision));
        }
        if (permissionDecision.updatedArguments() != null) {
            arguments = permissionDecision.updatedArguments();
            context = buildContext(assistantMessage, toolCall, arguments);
        }

        PermissionDecision hookDecision = safePermissionDecision(hookChain.beforeToolCall(invocation, context));
        if (!hookDecision.allowed()) {
            return errorMessage(toolCall, renderPermissionRejection(hookDecision));
        }

        try {
            definition.beforeExecute(context);
            if (context.isBlocked()) {
                return errorMessage(toolCall, context.getBlockedReason() == null || context.getBlockedReason().isBlank()
                        ? "Tool execution was blocked."
                        : context.getBlockedReason());
            }

            ToolExecutionResult result = definition.execute(context, onUpdate);
            result = definition.afterExecute(context.toBuilder()
                    .result(result)
                    .build());
            result = hookChain.afterToolCall(invocation, context.toBuilder()
                    .result(result)
                    .build(), result);
            result = applyResultPolicy(definition.resultPolicy(), result);
            return resultMapper.toMessage(toolCall, result);
        } catch (RuntimeException e) {
            ToolExecutionResult hookResult = hookChain.onToolFailure(invocation, context, e);
            if (hookResult != null) {
                return resultMapper.toMessage(toolCall, applyResultPolicy(definition.resultPolicy(), hookResult));
            }
            return errorMessage(toolCall, "Tool execution failed: " + e.getMessage());
        }
    }

    private ToolExecutionContext buildContext(
            AssistantMessage assistantMessage,
            ToolCallContent toolCall,
            Map<String, Object> arguments
    ) {
        return ToolExecutionContext.builder()
                .assistantMessage(assistantMessage)
                .toolCall(toolCall)
                .toolCallId(toolCall.getToolCallId())
                .toolName(toolCall.getToolName())
                .argumentsJson(toolCall.getArgumentsJson())
                .arguments(arguments == null ? Map.of() : arguments)
                .build();
    }

    private ToolResultMessage errorMessage(ToolCallContent toolCall, String text) {
        return resultMapper.toMessage(toolCall, ToolExecutionResult.errorText(text));
    }

    private String safeToolName(ToolCallContent toolCall) {
        return toolCall.getToolName() == null || toolCall.getToolName().isBlank()
                ? "<unknown>"
                : toolCall.getToolName();
    }

    private PermissionDecision safePermissionDecision(PermissionDecision decision) {
        return decision == null ? PermissionDecision.allow() : decision;
    }

    private String renderPermissionRejection(PermissionDecision decision) {
        String reason = decision.reason() == null || decision.reason().isBlank()
                ? "Tool permission was not granted."
                : decision.reason();
        if (decision.mode() == PermissionMode.ASK) {
            return "Tool permission requires approval: " + reason;
        }
        return "Tool permission denied: " + reason;
    }

    private ToolExecutionResult applyResultPolicy(ToolResultPolicy policy, ToolExecutionResult result) {
        ToolExecutionResult safeResult = result == null
                ? ToolExecutionResult.errorText("Tool returned no result.")
                : result;
        ToolResultPolicy safePolicy = policy == null ? ToolResultPolicy.defaults() : policy;
        if (!safePolicy.truncate()) {
            return safeResult;
        }

        int originalTextChars = totalTextChars(safeResult.getContents());
        if (originalTextChars <= safePolicy.maxTextChars()) {
            return safeResult;
        }

        List<MessageContent> truncatedContents = truncateContents(
                safeResult.getContents(),
                safePolicy.maxTextChars(),
                safePolicy.truncationNotice()
        );
        return ToolExecutionResult.builder()
                .contents(truncatedContents)
                .details(truncatedDetails(safeResult.getDetails(), originalTextChars, safePolicy.maxTextChars()))
                .error(safeResult.isError())
                .build();
    }

    private int totalTextChars(List<MessageContent> contents) {
        if (contents == null) {
            return 0;
        }
        int total = 0;
        for (MessageContent content : contents) {
            if (content instanceof TextContent textContent && textContent.getText() != null) {
                total += textContent.getText().length();
            }
        }
        return total;
    }

    private List<MessageContent> truncateContents(
            List<MessageContent> contents,
            int maxTextChars,
            String notice
    ) {
        if (contents == null) {
            return List.of(TextContent.builder().text(notice).build());
        }

        List<MessageContent> output = new ArrayList<>();
        int remaining = maxTextChars;
        boolean noticeAdded = false;
        for (MessageContent content : contents) {
            if (!(content instanceof TextContent textContent) || textContent.getText() == null) {
                output.add(content);
                continue;
            }

            String text = textContent.getText();
            if (remaining >= text.length()) {
                output.add(content);
                remaining -= text.length();
                continue;
            }

            String truncated = remaining <= 0 ? "" : text.substring(0, remaining);
            String rendered = truncated.isBlank()
                    ? notice
                    : truncated + "\n\n" + notice;
            output.add(TextContent.builder().text(rendered).build());
            remaining = 0;
            noticeAdded = true;
            break;
        }

        if (!noticeAdded) {
            output.add(TextContent.builder().text(notice).build());
        }
        return List.copyOf(output);
    }

    private Object truncatedDetails(Object originalDetails, int originalTextChars, int maxTextChars) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (originalDetails instanceof Map<?, ?> originalMap) {
            for (Map.Entry<?, ?> entry : originalMap.entrySet()) {
                if (entry.getKey() != null) {
                    details.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        } else if (originalDetails != null) {
            details.put("originalDetails", originalDetails);
        }
        details.put("truncated", true);
        details.put("originalTextChars", originalTextChars);
        details.put("maxTextChars", maxTextChars);
        return details;
    }
}
