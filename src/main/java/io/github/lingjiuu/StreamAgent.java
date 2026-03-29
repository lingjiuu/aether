package io.github.lingjiuu;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.helpers.ResponseAccumulator;
import com.openai.models.responses.*;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StreamAgent {

    private static final String BAILIAN_API_KEY = "BAILIAN_API_KEY";
    private static final String BASE_URL = "https://dashscope.aliyuncs.com/api/v2/apps/protocols/compatible-mode/v1";
    private static final String MODEL = "qwen3.5-plus-2026-02-15";

    private final OpenAIClient client = OpenAIOkHttpClient.builder()
            .apiKey(System.getenv(BAILIAN_API_KEY))
            .baseUrl(BASE_URL)
            .build();

    private final List<ResponseInputItem> messages = new ArrayList<>();

    private final List<Tool> tools = new ArrayList<>();

    public StreamAgent(){
        tools.add(Tool.ofFunction(get_time()));
        // system message
        messages.add(ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.DEVELOPER)
                        .content("You are a helpful assistant")
                        .build()
        ));
    }


    public void run_agentloop(String content){
        System.out.println("[AGENT] session start");
        System.out.println("[USER] " + content);

        // user message
        messages.add(ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .content(content)
                        .build()
        ));
        System.out.println("[STATE] history+ user");

        int turn = 1;

        while(true){
            System.out.println();
            System.out.println("[AGENT] turn " + turn + " start");
            System.out.println("[STATE] message_count=" + messages.size() + ", tool_count=" + tools.size());

            ResponseCreateParams params = ResponseCreateParams.builder()
                    .model(MODEL)
                    .tools(tools)
                    .store(false)
                    .inputOfResponse(messages)
                    .build();

            // 流式事件收集器
            ResponseAccumulator accumulator = ResponseAccumulator.create();
            // 工具调用结果收集
            List<ResponseInputItem.FunctionCallOutput> functionCallOutputs = new ArrayList<>();
            StringBuilder streamedText = new StringBuilder();
            StringBuilder reasoningSummaryText = new StringBuilder();

            try(StreamResponse<ResponseStreamEvent> streaming = client.responses().createStreaming(params)) {

                streaming.stream().forEach( event -> {
                    // 事件收集
                    accumulator.accumulate(event);

                    if (event.reasoningSummaryTextDelta().isPresent()) {
                        String delta = event.reasoningSummaryTextDelta().get().delta();
                        reasoningSummaryText.append(delta);
                    }

                    // 文本流
                    if (event.outputTextDelta().isPresent()){
                        String delta = event.outputTextDelta().get().delta();
                        streamedText.append(delta);
                        System.out.print(delta);
                    }

                    // 工具调用
                    if (event.outputItemDone().isPresent()){
                        ResponseOutputItem item = event.outputItemDone().get().item();
                        if (item.isFunctionCall()){
                            ResponseFunctionToolCall functionCall = item.asFunctionCall();
                            System.out.println();
                            System.out.println("[TOOL] call_id=" + functionCall.callId());
                            System.out.println("[TOOL] name=" + functionCall.name());
                            System.out.println("[TOOL] arguments=" + functionCall.arguments());
                            ResponseInputItem.FunctionCallOutput functionCallOutput = execute_functionCall(functionCall);
                            // 收集结果
                            functionCallOutputs.add(functionCallOutput);
                        }

                    }
                });

            }
            System.out.println();

            boolean isContinue = false;
            // 更新记忆 assistant
            Response response = accumulator.response();
            System.out.println("[LLM] response_id=" + response.id());
            System.out.println("[LLM] output_item_count=" + response.output().size());
            if (!streamedText.isEmpty()) {
                System.out.println("[ASSISTANT] " + streamedText);
            }
            if (!reasoningSummaryText.isEmpty()) {
                System.out.println("[REASONING] summary_delta=" + reasoningSummaryText);
            }
            for (int i = 0; i < response.output().size(); i++) {
                ResponseOutputItem outputItem = response.output().get(i);
                System.out.println("[OUTPUT] item[" + i + "]=" + describeOutputItem(outputItem));
                if (outputItem.isReasoning()) {
                    String summary = extractReasoningSummary(outputItem.asReasoning());
                    if (!summary.isBlank()) {
                        System.out.println("[REASONING] summary=" + summary);
                    }
                }
                if (outputItem.isMessage()) {
                    messages.add(ResponseInputItem.ofResponseOutputMessage(outputItem.asMessage()));
                    System.out.println("[STATE] history+ assistant");
                }
                if (outputItem.isFunctionCall()) {
                    messages.add(ResponseInputItem.ofFunctionCall(outputItem.asFunctionCall()));
                    isContinue = true;
                    System.out.println("[STATE] history+ function_call");
                }
            }

            // 更新记忆 functionCall output
            for (ResponseInputItem.FunctionCallOutput functionCallOutput : functionCallOutputs) {
                messages.add(ResponseInputItem.ofFunctionCallOutput(functionCallOutput));
                System.out.println("[STATE] history+ function_call_output");
            }

            if (!isContinue){
                System.out.println("[AGENT] final answer");
                if (!streamedText.isEmpty()) {
                    System.out.println("[ASSISTANT] " + streamedText);
                }
                System.out.println("[AGENT] session end");
                break;
            }
            System.out.println("[AGENT] continue after tool call");
            turn++;
        }

    }

    public ResponseInputItem.FunctionCallOutput execute_functionCall(ResponseFunctionToolCall functionCall){

        String toolName = functionCall.name();
        String output = "null";
        System.out.println("[TOOL] executing " + toolName);
        if ("get_time".equals(toolName)) {
            output = ZonedDateTime.now().toString();
        }
        System.out.println("[TOOL] result=" + output);

        return ResponseInputItem.FunctionCallOutput.builder()
                .callId(functionCall.callId())
                .output(output)
                .status(ResponseInputItem.FunctionCallOutput.Status.COMPLETED)
                .build();
    }

    private String extractReasoningSummary(ResponseReasoningItem reasoningItem) {
        StringBuilder summary = new StringBuilder();
        reasoningItem.summary().forEach(item -> {
            if (!summary.isEmpty()) {
                summary.append('\n');
            }
            summary.append(item.text());
        });
        return summary.toString().trim();
    }

    private String describeOutputItem(ResponseOutputItem outputItem) {
        if (outputItem.isMessage()) {
            return "message";
        }
        if (outputItem.isFunctionCall()) {
            return "function_call";
        }
        if (outputItem.isFunctionCallOutput()) {
            return "function_call_output";
        }
        if (outputItem.isReasoning()) {
            return "reasoning";
        }
        if (outputItem.isWebSearchCall()) {
            return "web_search_call";
        }
        if (outputItem.isFileSearchCall()) {
            return "file_search_call";
        }
        if (outputItem.isToolSearchCall()) {
            return "tool_search_call";
        }
        if (outputItem.isToolSearchOutput()) {
            return "tool_search_output";
        }
        if (outputItem.isComputerCall()) {
            return "computer_call";
        }
        if (outputItem.isComputerCallOutput()) {
            return "computer_call_output";
        }
        if (outputItem.isCodeInterpreterCall()) {
            return "code_interpreter_call";
        }
        if (outputItem.isImageGenerationCall()) {
            return "image_generation_call";
        }
        if (outputItem.isLocalShellCall()) {
            return "local_shell_call";
        }
        if (outputItem.isLocalShellCallOutput()) {
            return "local_shell_call_output";
        }
        if (outputItem.isShellCall()) {
            return "shell_call";
        }
        if (outputItem.isShellCallOutput()) {
            return "shell_call_output";
        }
        if (outputItem.isApplyPatchCall()) {
            return "apply_patch_call";
        }
        if (outputItem.isApplyPatchCallOutput()) {
            return "apply_patch_call_output";
        }
        if (outputItem.isCompaction()) {
            return "compaction";
        }
        if (outputItem.isMcpCall()) {
            return "mcp_call";
        }
        if (outputItem.isMcpListTools()) {
            return "mcp_list_tools";
        }
        if (outputItem.isMcpApprovalRequest()) {
            return "mcp_approval_request";
        }
        if (outputItem.isMcpApprovalResponse()) {
            return "mcp_approval_response";
        }
        if (outputItem.isCustomToolCall()) {
            return "custom_tool_call";
        }
        if (outputItem.isCustomToolCallOutput()) {
            return "custom_tool_call_output";
        }
        return "unknown";
    }

    public FunctionTool get_time(){
        return FunctionTool.builder()
                .name("get_time")
                .description("Get the current time in Asia/Shanghai.")
                .strict(true)
                .parameters(FunctionTool.Parameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(Map.of()))
                        .putAdditionalProperty("required", JsonValue.from(List.of()))
                        .build())
                .build();
    }
}
