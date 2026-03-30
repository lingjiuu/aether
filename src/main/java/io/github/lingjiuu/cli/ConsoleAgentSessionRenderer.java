package io.github.lingjiuu.cli;

import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.session.AgentSessionEvent;
import io.github.lingjiuu.session.AgentSessionEventListener;

public class ConsoleAgentSessionRenderer implements AgentSessionEventListener {

    @Override
    public void onEvent(AgentSessionEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }

        switch (event.getType()) {
            case RUN_START -> {
                System.out.println("[SESSION] id=" + event.getSessionId());
                System.out.println("[AGENT] run start");
            }
            case USER_MESSAGE -> {
                System.out.println("[USER] " + event.getText());
                System.out.println("[STATE] history+ user");
            }
            case TURN_START -> {
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
                    System.out.println("[TOOL] name=" + event.getToolCall().getToolName());
                    System.out.println("[TOOL] arguments=" + event.getToolCall().getArgumentsJson());
                }
            }
            case TOOL_RESULT -> {
                if (event.getToolResult() != null) {
                    System.out.println("[TOOL] result=" + MessageContents.text(event.getToolResult()));
                }
                System.out.println("[STATE] history+ tool_result");
            }
            case FINAL_ANSWER -> {
                System.out.println("[AGENT] final answer");
                if (event.getText() != null && !event.getText().isBlank()) {
                    System.out.println("[ASSISTANT] " + event.getText());
                }
            }
            case RUN_END -> System.out.println("[AGENT] run end");
            case SESSION_RESET -> System.out.println("[SESSION] reset");
            case ERROR -> {
                if (event.getErrorMessage() != null && !event.getErrorMessage().isBlank()) {
                    System.out.println("[ERROR] " + event.getErrorMessage());
                }
            }
        }
    }
}
