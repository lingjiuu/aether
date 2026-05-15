package io.github.lingjiuu.tool.runtime;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolCancelledException;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionMode;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.ToolResultPolicy;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import io.github.lingjiuu.tool.hook.ToolHookChain;
import io.github.lingjiuu.tool.permission.DefaultToolPermissionService;
import io.github.lingjiuu.tool.permission.PermissionDecision;
import io.github.lingjiuu.tool.permission.PermissionMode;
import io.github.lingjiuu.tool.permission.ToolPermissionService;
import io.github.lingjiuu.tool.validation.ToolSchemaValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class ToolRunner {

    private final Supplier<ActiveToolSet> activeToolSetSupplier;
    private final ToolSchemaValidator argumentValidator;
    private final ToolPermissionService permissionService;
    private final ToolHookChain hookChain;
    private final ToolResultMessageMapper resultMapper;

    public ToolRunner(ToolRegistry toolRegistry) {
        this(
                activeSetSupplier(toolRegistry),
                new ToolSchemaValidator(),
                new DefaultToolPermissionService(),
                ToolHookChain.empty(),
                new ToolResultMessageMapper()
        );
    }

    public ToolRunner(
            ToolRegistry toolRegistry,
            ToolPermissionService permissionService,
            ToolHookChain hookChain
    ) {
        this(
                activeSetSupplier(toolRegistry),
                new ToolSchemaValidator(),
                permissionService,
                hookChain,
                new ToolResultMessageMapper()
        );
    }

    public ToolRunner(
            Supplier<ActiveToolSet> activeToolSetSupplier,
            ToolPermissionService permissionService,
            ToolHookChain hookChain
    ) {
        this(
                activeToolSetSupplier,
                new ToolSchemaValidator(),
                permissionService,
                hookChain,
                new ToolResultMessageMapper()
        );
    }

    public ToolRunner(
            Supplier<ActiveToolSet> activeToolSetSupplier,
            ToolSchemaValidator argumentValidator,
            ToolPermissionService permissionService,
            ToolHookChain hookChain,
            ToolResultMessageMapper resultMapper
    ) {
        if (activeToolSetSupplier == null) {
            throw new IllegalArgumentException("activeToolSetSupplier must not be null");
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
        this.activeToolSetSupplier = activeToolSetSupplier;
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
        return run(assistantMessage, toolCall, onUpdate, ToolRunOptions.defaults());
    }

    public ToolResultMessage run(
            AssistantMessage assistantMessage,
            ToolCallContent toolCall,
            ToolUpdateCallback onUpdate,
            ToolRunOptions options
    ) {
        if (toolCall == null) {
            throw new IllegalArgumentException("toolCall must not be null");
        }
        ToolRunOptions safeOptions = options == null ? ToolRunOptions.defaults() : options;

        ActiveToolSet activeToolSet = activeToolSetSupplier.get();
        ToolDefinition definition = activeToolSet == null ? null : activeToolSet.findActive(toolCall.getToolName());
        ToolInvocation invocation = ToolInvocation.of(assistantMessage, toolCall, definition);
        if (definition == null) {
            ToolDefinition registeredDefinition = activeToolSet == null ? null : activeToolSet.findRegistered(toolCall.getToolName());
            if (registeredDefinition != null) {
                return errorMessage(toolCall, "Inactive tool: " + safeToolName(toolCall));
            }
            return errorMessage(toolCall, "Unsupported tool: " + safeToolName(toolCall));
        }

        Map<String, Object> arguments;
        try {
            arguments = argumentValidator.validate(definition, toolCall.getArgumentsJson());
        } catch (IllegalArgumentException e) {
            return errorMessage(toolCall, "Invalid tool arguments for " + safeToolName(toolCall) + ": " + e.getMessage());
        }

        ToolExecutionContext context = buildContext(assistantMessage, toolCall, arguments, safeOptions);

        try {
            checkRuntimeBoundary(context);
            PermissionDecision permissionDecision = safePermissionDecision(
                    permissionService.decide(invocation, context)
            );
            checkRuntimeBoundary(context);
            if (!permissionDecision.allowed()) {
                return errorMessage(toolCall, renderPermissionRejection(permissionDecision));
            }
            if (permissionDecision.updatedArguments() != null) {
                arguments = permissionDecision.updatedArguments();
                context = buildContext(assistantMessage, toolCall, arguments, safeOptions);
            }

            checkRuntimeBoundary(context);
            PermissionDecision hookDecision = safePermissionDecision(hookChain.beforeToolCall(invocation, context));
            checkRuntimeBoundary(context);
            if (!hookDecision.allowed()) {
                return errorMessage(toolCall, renderPermissionRejection(hookDecision));
            }

            checkRuntimeBoundary(context);
            definition.beforeExecute(context);
            checkRuntimeBoundary(context);
            if (context.isBlocked()) {
                return errorMessage(toolCall, context.getBlockedReason() == null || context.getBlockedReason().isBlank()
                        ? "Tool execution was blocked."
                        : context.getBlockedReason());
            }

            checkRuntimeBoundary(context);
            ToolExecutionResult result = definition.execute(context, onUpdate);
            checkRuntimeBoundary(context);
            result = definition.afterExecute(context.toBuilder()
                    .result(result)
                    .build());
            checkRuntimeBoundary(context);
            result = hookChain.afterToolCall(invocation, context.toBuilder()
                    .result(result)
                    .build(), result);
            checkRuntimeBoundary(context);
            result = applyResultPolicy(definition.resultPolicy(), result);
            return resultMapper.toMessage(toolCall, result);
        } catch (ToolCancelledException e) {
            return errorMessage(toolCall, e.getMessage());
        } catch (ToolTimedOutException e) {
            return errorMessage(toolCall, e.getMessage());
        } catch (RuntimeException e) {
            ToolExecutionResult hookResult = hookChain.onToolFailure(invocation, context, e);
            if (hookResult != null) {
                return resultMapper.toMessage(toolCall, applyResultPolicy(definition.resultPolicy(), hookResult));
            }
            return errorMessage(toolCall, "Tool execution failed: " + e.getMessage());
        }
    }

    public ToolExecutionMode executionModeFor(String toolName) {
        ActiveToolSet activeToolSet = activeToolSetSupplier.get();
        ToolDefinition definition = activeToolSet == null ? null : activeToolSet.findActive(toolName);
        return definition == null ? null : definition.executionMode();
    }

    private ToolExecutionContext buildContext(
            AssistantMessage assistantMessage,
            ToolCallContent toolCall,
            Map<String, Object> arguments,
            ToolRunOptions options
    ) {
        ToolRunOptions safeOptions = options == null ? ToolRunOptions.defaults() : options;
        return ToolExecutionContext.builder()
                .assistantMessage(assistantMessage)
                .toolCall(toolCall)
                .toolCallId(toolCall.getToolCallId())
                .toolName(toolCall.getToolName())
                .argumentsJson(toolCall.getArgumentsJson())
                .arguments(arguments == null ? Map.of() : arguments)
                .cancellationToken(safeOptions.cancellationToken())
                .deadline(safeOptions.deadline())
                .build();
    }

    private void checkRuntimeBoundary(ToolExecutionContext context) {
        context.throwIfCancellationRequested();
        if (context.remainingTimeout().isPresent() && context.remainingTimeout().get().isZero()) {
            throw new ToolTimedOutException();
        }
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

    private static Supplier<ActiveToolSet> activeSetSupplier(ToolRegistry toolRegistry) {
        if (toolRegistry == null) {
            throw new IllegalArgumentException("toolRegistry must not be null");
        }
        ActiveToolSetResolver compiler = new ActiveToolSetResolver();
        return () -> compiler.compile(toolRegistry, null);
    }

    private static final class ToolTimedOutException extends RuntimeException {
        private ToolTimedOutException() {
            super("Tool execution timed out.");
        }
    }
}
