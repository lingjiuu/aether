package io.github.lingjiuu.cli;

import io.github.lingjiuu.event.EventSink;
import io.github.lingjiuu.event.UiEvent;
import io.github.lingjiuu.message.MessageContents;

public class ConsoleRenderer implements EventSink {

    @Override
    public void onEvent(UiEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }

        switch (event.getType()) {
            case RUN_STARTED -> {
                System.out.println("[SESSION] id=" + event.getSessionId());
                System.out.println("[AGENT] run start");
            }
            case USER_MESSAGE -> {
                System.out.println("[USER] " + event.getText());
                System.out.println("[STATE] history+ user");
            }
            case TURN_STARTED -> {
                System.out.println();
                System.out.println("[AGENT] turn " + event.getTurn() + " start");
            }
            case ASSISTANT_TEXT_DELTA -> {
                if (event.getDelta() != null) {
                    System.out.print(event.getDelta());
                }
            }
            case REASONING_DELTA -> {
            }
            case TOKEN_USAGE -> {
                if (event.getContextTokenUsage() != null) {
                    String limit = event.getAutoCompactTokenLimit() == null
                            ? "off"
                            : event.getAutoCompactTokenLimit().toString();
                    System.out.println("[TOKENS] context=" + event.getContextTokenUsage() + " auto_compact_at=" + limit);
                }
            }
            case ASSISTANT_MESSAGE -> {
                System.out.println();
                if (event.getAssistantMessage() != null && event.getAssistantMessage().getStopReason() != null) {
                    System.out.println("[LLM] stop_reason=" + event.getAssistantMessage().getStopReason());
                }
                String thinking = event.getAssistantMessage() == null ? "" : MessageContents.thinking(event.getAssistantMessage());
                if (!thinking.isBlank()) {
                    System.out.println("[REASONING] summary=" + thinking);
                }
                System.out.println("[STATE] history+ assistant");
            }
            case TOOL_CALL -> {
                if (event.getToolCall() != null) {
                    System.out.println("[TOOL] call_id=" + event.getToolCall().getToolCallId());
                    System.out.println("[TOOL] " + event.getToolCall().getToolName()
                            + " " + event.getToolCall().getArgumentsJson());
                }
            }
            case TOOL_EXECUTION_STARTED -> {
                if (event.getToolCall() != null) {
                    System.out.println("[TOOL] executing " + event.getToolCall().getToolName());
                }
            }
            case TOOL_EXECUTION_UPDATE -> {
            }
            case TOOL_EXECUTION_FINISHED -> {
            }
            case TOOL_RESULT -> {
                if (event.getToolResult() != null) {
                    System.out.println("[TOOL] result=" + MessageContents.text(event.getToolResult()));
                }
                System.out.println("[STATE] history+ tool_result");
            }
            case APPROVAL_REQUESTED -> {
                if (event.getApprovalRequest() != null) {
                    System.out.println("[APPROVAL] requested for " + event.getApprovalRequest().toolName());
                    System.out.println("[APPROVAL] risk=" + event.getApprovalRequest().riskLevel());
                    if (event.getApprovalRequest().reason() != null
                            && !event.getApprovalRequest().reason().isBlank()) {
                        System.out.println("[APPROVAL] reason=" + event.getApprovalRequest().reason());
                    }
                    System.out.println("[APPROVAL] args=" + event.getApprovalRequest().arguments());
                }
            }
            case APPROVAL_RESOLVED -> {
                if (event.getApprovalResponse() != null) {
                    System.out.println("[APPROVAL] "
                            + (event.getApprovalResponse().approved() ? "approved" : "denied"));
                    if (event.getApprovalResponse().reason() != null
                            && !event.getApprovalResponse().reason().isBlank()) {
                        System.out.println("[APPROVAL] reason=" + event.getApprovalResponse().reason());
                    }
                }
            }
            case CONTEXT_MESSAGE -> {
                if (event.getText() != null && !event.getText().isBlank()) {
                    System.out.println("[CONTEXT] " + event.getText());
                }
            }
            case FINAL_ANSWER -> {
                System.out.println("[AGENT] final answer");
                if (event.getText() != null && !event.getText().isBlank()) {
                    System.out.println("[ASSISTANT] " + event.getText());
                }
            }
            case TURN_ABORTED -> System.out.println("[AGENT] turn interrupted");
            case COMPACT_STARTED -> {
                String trigger = event.getText() == null || event.getText().isBlank()
                        ? ""
                        : " (" + event.getText() + ")";
                System.out.println("[CONTEXT] compact start" + trigger);
                if (event.getOriginalMessageCount() != null) {
                    System.out.println("[CONTEXT] original messages=" + event.getOriginalMessageCount());
                }
            }
            case COMPACT_FINISHED -> {
                System.out.println("[CONTEXT] compact finished");
                if (event.getOriginalMessageCount() != null && event.getReplacementMessageCount() != null) {
                    System.out.println("[CONTEXT] messages "
                            + event.getOriginalMessageCount()
                            + " -> "
                            + event.getReplacementMessageCount());
                }
            }
            case COMPACT_SKIPPED -> {
                System.out.println("[CONTEXT] compact skipped");
                if (event.getText() != null && !event.getText().isBlank()) {
                    System.out.println("[CONTEXT] " + event.getText());
                }
            }
            case RUN_FINISHED -> System.out.println("[AGENT] run end");
            case SESSION_RESET -> System.out.println("[SESSION] reset");
            case ERROR -> {
                if (event.getErrorMessage() != null && !event.getErrorMessage().isBlank()) {
                    System.out.println("[ERROR] " + event.getErrorMessage());
                }
            }
        }
    }
}
