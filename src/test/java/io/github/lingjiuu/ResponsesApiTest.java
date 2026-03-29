package io.github.lingjiuu;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.helpers.ResponseAccumulator;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextConfig;
import junit.framework.TestCase;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一个“渐进式”的 Responses API 教程，专门演示 openai-java 4.30.0 的新接口。
 *
 * 为什么单独写一个类，而不是直接覆盖之前的 Chat Completions 教程？
 * 目的是让你能同时保留两套心智模型：
 * 1. Chat Completions：更像“messages in, one assistant message out”
 * 2. Responses API：更像“input in, output items out”
 *
 * OpenAI 官方现在对新项目更推荐 Responses API，因为它更适合：
 * - reasoning
 * - tool use
 * - 流式事件
 * - 基于 previous_response_id 的连续对话
 *
 * 这份测试类默认只自动跑最基础的 3 步：
 * - 第 1 步：建立全局认知
 * - 第 2 步：创建 client
 * - 第 3 步：发出最简单的一次 responses.create()
 *
 * 更进阶的步骤默认不自动跑，避免每次 test 都打太多真实请求。
 *
 * 运行前建议准备这些环境变量：
 * - OPENAI_API_KEY：必填
 * - OPENAI_BASE_URL：可选。如果你要接的是兼容平台，并且它支持 /v1/responses，可在这里覆盖
 * - OPENAI_RESPONSES_MODEL：可选，默认 gpt-4.1
 * - OPENAI_REASONING_MODEL：可选，专门给 reasoning demo 用
 * - RUN_OPENAI_RESPONSES_OPTIONAL_DEMOS=true：开启第 4~6 步
 * - RUN_OPENAI_RESPONSES_STREAMING_DEMO=true：开启第 7 步
 *
 * 官方参考：
 * - https://platform.openai.com/docs/guides/responses-vs-chat-completions
 * - https://platform.openai.com/docs/api-reference/responses
 * - https://platform.openai.com/docs/libraries/java
 * - https://platform.openai.com/docs/guides/function-calling
 */
public class ResponsesApiTest extends TestCase {

    private static final String OPENAI_API_KEY = "OPENAI_API_KEY";
    private static final String RESPONSES_MODEL_ENV = "OPENAI_RESPONSES_MODEL";
    private static final String REASONING_MODEL_ENV = "OPENAI_REASONING_MODEL";
    private static final String DEFAULT_RESPONSES_MODEL = "gpt-4.1";

    public void test01_readMeFirst() {
        /*
         * 第 1 步：先建立一个最重要的认知。
         *
         * Chat Completions 的核心输入是 messages[]，
         * 所以你会天然把它理解成“聊天记录 + assistant message”。
         *
         * Responses API 的核心输入则更宽一点：
         * - 可以直接给 input("纯文本")
         * - 也可以给 inputOfResponse(List<ResponseInputItem>)
         * - 输出不是单纯一条 assistant message，而是 output items 列表
         *
         * 所以你在读返回值时，也要换一个脑子：
         * - 不再只盯着 "message.content"
         * - 而是要看 response.output() 里面每个 item 是什么类型
         *
         * 常见 item 可能有：
         * - message
         * - function_call
         * - reasoning
         * - function_call_output
         *
         * 这也是为什么 Responses API 更贴近“agent runtime”。
         */
        assertTrue(true);
    }

    public void test02_createClientFromEnv() {
        /*
         * 第 2 步：先只学“如何创建 client”。
         *
         * 这次我们直接用官方最省事的写法：
         * OpenAIOkHttpClient.fromEnv()
         *
         * 它会读取：
         * - OPENAI_API_KEY
         * - OPENAI_BASE_URL（如果你设置了）
         *
         * 这也是为什么这里改用官方环境变量命名。
         * 对 Responses API 教程来说，直接贴近官方写法会更好记。
         */
        String apiKey = requireOpenAIApiKeyOrSkip();
        if (apiKey == null) {
            return;
        }

        OpenAIClient client = createClientFromEnv();
        assertNotNull("client should be created successfully.", client);
    }

    public void test03_simplestResponsesCall() {
        /*
         * 第 3 步：最简单的 Responses 调用。
         *
         * 这一步只保留 3 个概念：
         * 1. client
         * 2. model
         * 3. input
         *
         * 你可以把它理解成：
         * “给模型一段输入文本，拿到一个 Response 对象，再自己从 output items 里提取文本。”
         */
        String apiKey = requireOpenAIApiKeyOrSkip();
        if (apiKey == null) {
            return;
        }

        OpenAIClient client = createClientFromEnv();
        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(resolveResponsesModel())
                .input("用一句话解释什么是以太。")
                .build();

        Response response = client.responses().create(params);
        String answer = extractOutputText(response);

        System.out.println("=== Responses Step 3: simplest create() ===");
        printResponseMeta(response);
        System.out.println(answer);
        assertFalse("The response text should not be blank.", answer.isBlank());
    }

    public void test04_responseWithInstructionsAndVerbosity() {
        /*
         * 第 4 步：加入 instructions 和 text 配置。
         *
         * 在 Responses API 里，一个很自然的写法是：
         * - input 负责“我要问什么”
         * - instructions 负责“你要怎么回答”
         *
         * 这和 Chat Completions 里的 system message 有点像，
         * 但在代码层面更直接，也更贴近“给这次 response 一份说明”。
         *
         * text.verbosity 则可以理解成：
         * “如果这个模型支持，请让它更简短或更展开一点。”
         */
        if (!isDemoEnabled("RUN_OPENAI_RESPONSES_OPTIONAL_DEMOS")) {
            System.out.println("Skipping optional demo: test04_responseWithInstructionsAndVerbosity");
            return;
        }

        String apiKey = requireOpenAIApiKeyOrSkip();
        if (apiKey == null) {
            return;
        }

        OpenAIClient client = createClientFromEnv();
        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(resolveResponsesModel())
                .instructions("你是一名面向初学者的老师，回答要清楚、短小、像课堂讲解。")
                .text(ResponseTextConfig.builder()
                        .verbosity(ResponseTextConfig.Verbosity.LOW)
                        .build())
                .input("以太和以太坊是什么关系？")
                .build();

        Response response = client.responses().create(params);
        String answer = extractOutputText(response);

        System.out.println("=== Responses Step 4: instructions + verbosity ===");
        printResponseMeta(response);
        System.out.println(answer);
        assertFalse("The response text should not be blank.", answer.isBlank());
    }

    public void test05_continueWithPreviousResponseId() {
        /*
         * 第 5 步：体验 Responses API 最有代表性的一个能力：
         * 用 previous_response_id 继续上一次响应。
         *
         * 这里和 Chat Completions 最大的区别是：
         * - Chat Completions：你自己把 messages 全量带回去
         * - Responses API：你可以引用上一条 response 的 id，继续往下说
         *
         * 为了让这条链路可靠，这里会把第一条 response 存起来：
         * - store(true)
         *
         * 然后第二次调用只需要带：
         * - previousResponseId(first.id())
         * - 新的 input
         */
        if (!isDemoEnabled("RUN_OPENAI_RESPONSES_OPTIONAL_DEMOS")) {
            System.out.println("Skipping optional demo: test05_continueWithPreviousResponseId");
            return;
        }

        String apiKey = requireOpenAIApiKeyOrSkip();
        if (apiKey == null) {
            return;
        }

        OpenAIClient client = createClientFromEnv();
        Response firstResponse = client.responses().create(ResponseCreateParams.builder()
                .model(resolveResponsesModel())
                .store(true)
                .input("先用一句话解释什么是以太坊。")
                .build());

        Response secondResponse = client.responses().create(ResponseCreateParams.builder()
                .model(resolveResponsesModel())
                .store(true)
                .previousResponseId(firstResponse.id())
                .input("很好，再补充一句说明它和以太币的关系。")
                .build());

        String firstAnswer = extractOutputText(firstResponse);
        String secondAnswer = extractOutputText(secondResponse);

        System.out.println("=== Responses Step 5: previous_response_id ===");
        System.out.println("first_response_id = " + firstResponse.id());
        System.out.println("first_answer = " + firstAnswer);
        System.out.println("second_response_id = " + secondResponse.id());
        System.out.println("second_answer = " + secondAnswer);

        assertFalse("The first response should not be blank.", firstAnswer.isBlank());
        assertFalse("The second response should not be blank.", secondAnswer.isBlank());
    }

    public void test06_functionToolCall() {
        /*
         * 第 6 步：Responses API 里的函数工具调用。
         *
         * 这是你以后做 agent 最该看懂的一步。
         *
         * 整个流程还是那个经典闭环，只是协议换成了 Responses 的形状：
         * 1. 第一次 create() 把工具 schema 声明给模型
         * 2. 模型返回 output items，其中可能包含 function_call
         * 3. 你的 Java 代码自己执行本地工具
         * 4. 把工具结果包装成 function_call_output
         * 5. 再用 previous_response_id + inputOfResponse(...) 发第二次 create()
         *
         * 注意：
         * SDK 不会自动替你执行 get_time()。
         * 模型只能“请求调用工具”，真正的 Java 方法还是你自己执行。
         */
        if (!isDemoEnabled("RUN_OPENAI_RESPONSES_OPTIONAL_DEMOS")) {
            System.out.println("Skipping optional demo: test06_functionToolCall");
            return;
        }

        String apiKey = requireOpenAIApiKeyOrSkip();
        if (apiKey == null) {
            return;
        }

        OpenAIClient client = createClientFromEnv();
        Response firstResponse = client.responses().create(ResponseCreateParams.builder()
                .model(resolveResponsesModel())
                .store(true)
                .instructions("如果用户在问时间，请优先调用 get_time 工具。")
                .input("现在几点了？")
                .addTool(buildGetTimeTool())
                .build());

        List<com.openai.models.responses.ResponseFunctionToolCall> functionCalls = extractFunctionCalls(firstResponse);
        System.out.println("=== Responses Step 6: first response with tool call ===");
        printResponseMeta(firstResponse);
        printFunctionCalls(functionCalls);

        assertFalse("The model should return at least one function call in this demo.", functionCalls.isEmpty());

        List<ResponseInputItem> toolOutputs = new ArrayList<>();
        for (com.openai.models.responses.ResponseFunctionToolCall functionCall : functionCalls) {
            if (!"get_time".equals(functionCall.name())) {
                throw new IllegalStateException("Only get_time is supported in this demo, but got: " + functionCall.name());
            }

            String toolResult = currentShanghaiTime();
            System.out.println("tool_output = " + toolResult);

            ResponseInputItem toolOutputItem = ResponseInputItem.ofFunctionCallOutput(
                    ResponseInputItem.FunctionCallOutput.builder()
                            .callId(functionCall.callId())
                            .output(toolResult)
                            .build()
            );
            toolOutputs.add(toolOutputItem);
        }

        Response secondResponse = client.responses().create(ResponseCreateParams.builder()
                .model(resolveResponsesModel())
                .store(true)
                .previousResponseId(firstResponse.id())
                .inputOfResponse(toolOutputs)
                .build());

        String finalAnswer = extractOutputText(secondResponse);
        System.out.println("=== Responses Step 6: final answer after tool output ===");
        printResponseMeta(secondResponse);
        System.out.println(finalAnswer);

        assertFalse("The final answer after tool execution should not be blank.", finalAnswer.isBlank());
    }

    public void test07_reasoningSummary() {
        /*
         * 第 7 步：reasoning 配置。
         *
         * 这一步不是让你拿到“完整思维链”，官方也不鼓励把 raw chain-of-thought
         * 当成一个稳定接口来依赖。
         *
         * 这里更适合学的是：
         * - reasoning effort：让模型推理得更轻一点还是更深一点
         * - generateSummary：如果模型支持，让它返回 reasoning summary
         *
         * 注意这一步对模型能力要求更高，所以单独用 OPENAI_REASONING_MODEL 控制。
         * 如果你没配，就先跳过，不影响前面的基础学习。
         */
        if (!isDemoEnabled("RUN_OPENAI_RESPONSES_OPTIONAL_DEMOS")) {
            System.out.println("Skipping optional demo: test07_reasoningSummary");
            return;
        }

        String reasoningModel = System.getenv(REASONING_MODEL_ENV);
        if (reasoningModel == null || reasoningModel.isBlank()) {
            System.out.println("Skipping reasoning demo because OPENAI_REASONING_MODEL is not set.");
            return;
        }

        String apiKey = requireOpenAIApiKeyOrSkip();
        if (apiKey == null) {
            return;
        }

        OpenAIClient client = createClientFromEnv();
        Response response = client.responses().create(ResponseCreateParams.builder()
                .model(reasoningModel)
                .reasoning(Reasoning.builder()
                        .effort(ReasoningEffort.LOW)
                        .generateSummary(Reasoning.GenerateSummary.CONCISE)
                        .build())
                .input("简要分析一下：为什么 agent runtime 往往比单次问答更复杂？")
                .build());

        String answer = extractOutputText(response);
        String reasoningSummary = extractReasoningSummary(response);

        System.out.println("=== Responses Step 7: reasoning summary ===");
        printResponseMeta(response);
        System.out.println("reasoning_summary = " + reasoningSummary);
        System.out.println("final_answer = " + answer);

        assertFalse("The final answer should not be blank.", answer.isBlank());
    }

    public void test08_streamingResponses() {
        /*
         * 第 8 步：Responses API 的流式输出。
         *
         * 这一步和 Chat Completions streaming 最大的不同是：
         * - Responses 的流不是只有 text delta
         * - 它是一连串 typed events
         *
         * 所以你会看到：
         * - output_text.delta
         * - reasoning_summary_text.delta
         * - output_item.added
         * - completed
         * 等等事件
         *
         * 为了让你既能看到“增量事件”，又能拿到“最终完整 response”，
         * 这里同时用了 ResponseAccumulator。
         */
        if (!isDemoEnabled("RUN_OPENAI_RESPONSES_STREAMING_DEMO")) {
            System.out.println("Skipping optional demo: test08_streamingResponses");
            return;
        }

        String apiKey = requireOpenAIApiKeyOrSkip();
        if (apiKey == null) {
            return;
        }

        OpenAIClient client = createClientFromEnv();
        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(resolveResponsesModel())
                .input("分 3 点解释为什么 Responses API 的 streaming 更像事件流。")
                .build();

        ResponseAccumulator accumulator = ResponseAccumulator.create();
        StringBuilder textDelta = new StringBuilder();
        StringBuilder reasoningDelta = new StringBuilder();

        System.out.println("=== Responses Step 8: streaming ===");
        try (StreamResponse<ResponseStreamEvent> stream = client.responses().createStreaming(params)) {
            stream.stream().forEach(event -> {
                accumulator.accumulate(event);

                if (event.outputTextDelta().isPresent()) {
                    String delta = event.outputTextDelta().get().delta();
                    textDelta.append(delta);
                    System.out.print(delta);
                }

                if (event.reasoningSummaryTextDelta().isPresent()) {
                    String delta = event.reasoningSummaryTextDelta().get().delta();
                    reasoningDelta.append(delta);
                }
            });
        }
        System.out.println();

        Response finalResponse = accumulator.response();
        String finalAnswer = extractOutputText(finalResponse);

        if (!reasoningDelta.isEmpty()) {
            System.out.println("reasoning_summary_delta = " + reasoningDelta);
        }
        System.out.println("final_answer = " + finalAnswer);
        assertFalse("The streamed text should not be blank.", finalAnswer.isBlank());
    }

    private static String requireOpenAIApiKeyOrSkip() {
        String apiKey = System.getenv(OPENAI_API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping Responses API tests because OPENAI_API_KEY is not set.");
            return null;
        }
        return apiKey;
    }

    private static OpenAIClient createClientFromEnv() {
        /*
         * 这里故意不手动 builder。
         *
         * 原因不是 builder 不好，而是这份教程想优先展示：
         * “如果你走官方命名的环境变量，Responses API 最简单的初始化方式是什么？”
         *
         * 答案就是：
         * OpenAIOkHttpClient.fromEnv()
         */
        return OpenAIOkHttpClient.fromEnv();
    }

    private static String resolveResponsesModel() {
        String model = System.getenv(RESPONSES_MODEL_ENV);
        if (model == null || model.isBlank()) {
            return DEFAULT_RESPONSES_MODEL;
        }
        return model;
    }

    private static boolean isDemoEnabled(String envName) {
        String value = System.getenv(envName);
        return value != null && value.equalsIgnoreCase("true");
    }

    private static void printResponseMeta(Response response) {
        if (response == null) {
            System.out.println("response = null");
            return;
        }
        System.out.println("response_id = " + response.id());
        System.out.println("model = " + response.model());
        System.out.println("status = " + response.status().map(Object::toString).orElse("unknown"));
        System.out.println("output_item_count = " + response.output().size());
    }

    private static String extractOutputText(Response response) {
        if (response == null) {
            return "";
        }

        StringBuilder answer = new StringBuilder();
        for (ResponseOutputItem outputItem : response.output()) {
            if (!outputItem.isMessage()) {
                continue;
            }

            outputItem.asMessage().content().forEach(content -> {
                if (content.isOutputText()) {
                    answer.append(content.asOutputText().text());
                }
            });
        }
        return answer.toString().trim();
    }

    private static String extractReasoningSummary(Response response) {
        if (response == null) {
            return "";
        }

        StringBuilder summary = new StringBuilder();
        for (ResponseOutputItem outputItem : response.output()) {
            if (!outputItem.isReasoning()) {
                continue;
            }

            outputItem.asReasoning().summary().forEach(item -> {
                if (!summary.isEmpty()) {
                    summary.append('\n');
                }
                summary.append(item.text());
            });
        }
        return summary.toString().trim();
    }

    private static List<com.openai.models.responses.ResponseFunctionToolCall> extractFunctionCalls(Response response) {
        List<com.openai.models.responses.ResponseFunctionToolCall> functionCalls = new ArrayList<>();
        if (response == null) {
            return functionCalls;
        }

        for (ResponseOutputItem outputItem : response.output()) {
            if (outputItem.isFunctionCall()) {
                functionCalls.add(outputItem.asFunctionCall());
            }
        }
        return functionCalls;
    }

    private static void printFunctionCalls(List<com.openai.models.responses.ResponseFunctionToolCall> functionCalls) {
        System.out.println("function_call_count = " + functionCalls.size());
        functionCalls.forEach(functionCall -> {
            System.out.println("call_id = " + functionCall.callId());
            System.out.println("name = " + functionCall.name());
            System.out.println("arguments = " + functionCall.arguments());
        });
    }

    private static FunctionTool buildGetTimeTool() {
        /*
         * 这个 schema 看着稍长，但本质上就是 JSON Schema。
         *
         * 我们这里故意做成“无参数工具”，是为了让你先把工具调用流程看明白。
         * 真正复杂的不是 schema 本身，而是：
         * - 模型返回 function_call
         * - 你本地执行工具
         * - 再把 function_call_output 回填给模型
         */
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

    private static String currentShanghaiTime() {
        return ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toString();
    }
}
