package io.github.lingjiuu;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import junit.framework.TestCase;

/**
 * 一个“渐进式”的 openai-java SDK 教程，直接放在测试类里，方便边看边跑。
 *
 * 这份示例基于 OpenAI 官方 Java SDK 文档整理：
 * 1. 官方 SDK README 说明：
 *    - Responses API 是官方当前主推的文本生成 API。
 *    - Chat Completions API 会长期支持，语义也更接近很多 OpenAI 兼容平台。
 * 2. 官方 README 还说明：
 *    - OpenAIOkHttpClient.fromEnv() 只会读取官方约定的环境变量，
 *      比如 OPENAI_API_KEY 和 OPENAI_BASE_URL。
 * 3. 你这个项目当前接的是 SiliconFlow 这种 OpenAI 兼容接口，并且只想从
 *    SILICONFLOW_API_KEY 读取密钥，所以这里不用 fromEnv()，而是手动 builder。
 *
 * 官方参考：
 * - https://github.com/openai/openai-java
 * - https://platform.openai.com/docs/api-reference/chat
 */
public class AppTest extends TestCase {

    private static final String SILICONFLOW_API_KEY = "SILICONFLOW_API_KEY";
    private static final String SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1";
    private static final String MODEL = "Pro/zai-org/GLM-5";

    public void test01_readMeFirst() {
        /*
         * 第 1 步：先建立“怎么选写法”的全局认知。
         *
         * 官方文档里的两种常见初始化方式：
         *
         * 写法 A：最省事，适合你已经使用官方环境变量命名时。
         * OpenAIClient client = OpenAIOkHttpClient.fromEnv();
         *
         * 它要求环境变量名是官方约定：
         * - OPENAI_API_KEY
         * - OPENAI_BASE_URL（可选，不填时默认是官方地址）
         *
         * 写法 B：手动 builder，适合你要接兼容平台、或者环境变量名称不是官方默认值时。
         * 这也是我们这个项目最合适的方式。
         */
        assertTrue(true);
    }

    public void test02_createClientStepByStep() {
        /*
         * 第 2 步：只演示“如何创建客户端”，不真正发请求。
         *
         * 这样先把最关键的配置概念理顺：
         * 1. API key 从环境变量读取。
         * 2. baseUrl 写死成兼容平台地址。
         * 3. client 只创建一次，后面多个请求都复用它。
         */
        String apiKey = requireApiKeyOrSkip();
        if (apiKey == null) {
            return;
        }
        OpenAIClient client = createCompatibleClient(apiKey);

        assertNotNull("client should be created successfully.", client);
    }

    public void test03_simplestChatCompletionCall() {
        /*
         * 第 3 步：最简单的可运行请求。
         *
         * 这一版只保留 3 个最小必要元素：
         * 1. client
         * 2. model
         * 3. user message
         *
         * 这就是“能跑通”的最小闭环。
         */
        String apiKey = requireApiKeyOrSkip();
        if (apiKey == null) {
            return;
        }
        OpenAIClient client = createCompatibleClient(apiKey);

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(MODEL)
                .addUserMessage("用一句话解释什么是以太。")
                .build();

        ChatCompletion completion = client.chat().completions().create(params);
        String answer = extractFirstText(completion);

        System.out.println("=== Step 3: simplest call ===");
        System.out.println(answer);
        assertFalse("The model response should not be blank.", answer.isBlank());
    }

    public void test04_chatCompletionWithSystemPrompt() {
        /*
         * 第 4 步：在“最简单调用”的基础上，加入 system message。
         *
         * system message 的作用：
         * - 给模型一个稳定的身份或输出风格
         * - 比如要求“讲给初学者听”“只用 3 句话”“输出 Markdown”
         *
         * 一般来说：
         * - user message 负责“我想问什么”
         * - system message 负责“你要怎么回答”
         *
         * 这个示例默认不自动运行，避免每次测试都多打一笔真实请求。
         * 如果你想亲自跑：
         * - 先设置环境变量 RUN_OPENAI_OPTIONAL_DEMOS=true
         */
        if (!isDemoEnabled("RUN_OPENAI_OPTIONAL_DEMOS")) {
            System.out.println("Skipping optional demo: test04_chatCompletionWithSystemPrompt");
            return;
        }
        String apiKey = requireApiKeyOrSkip();
        if (apiKey == null) {
            return;
        }
        OpenAIClient client = createCompatibleClient(apiKey);

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(MODEL)
                .addSystemMessage("你是一名面向初学者的 Java SDK 讲师，回答要简短、清楚。")
                .addUserMessage("什么是以太？顺便说明它和以太坊里的以太币有什么关系。")
                .build();

        ChatCompletion completion = client.chat().completions().create(params);
        String answer = extractFirstText(completion);

        System.out.println("=== Step 4: with system prompt ===");
        System.out.println(answer);
        assertFalse("The model response should not be blank.", answer.isBlank());
    }

    public void test05_multiTurnConversation() {
        /*
         * 第 5 步：多轮对话。
         *
         * 很多人一开始会以为 SDK 会“自动记住上下文”，其实不会。
         * 对 Chat Completions 来说，你要把历史消息一起带回去，模型才知道前文。
         *
         * 所以多轮对话的核心不是“调用另一个 API”，而是：
         * - 继续使用同一个接口
         * - 但把之前的 user / assistant 消息一起放进 messages
         *
         * 这个示例默认不自动运行，避免测试默认流程过慢。
         */
        if (!isDemoEnabled("RUN_OPENAI_OPTIONAL_DEMOS")) {
            System.out.println("Skipping optional demo: test05_multiTurnConversation");
            return;
        }
        String apiKey = requireApiKeyOrSkip();
        if (apiKey == null) {
            return;
        }
        OpenAIClient client = createCompatibleClient(apiKey);

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(MODEL)
                .addSystemMessage("你是一名区块链入门老师，回答要通俗。")
                .addUserMessage("我刚开始学区块链，你先用一句话解释一下什么是以太坊。")
                .addAssistantMessage("以太坊是一个既能转账、又能运行智能合约的区块链平台。")
                .addUserMessage("那以太币在这个平台里主要是干什么的？")
                .build();


        ChatCompletion completion = client.chat().completions().create(params);
        String answer = extractFirstText(completion);

        System.out.println("=== Step 5: multi-turn conversation ===");
        System.out.println(answer);
        assertFalse("The multi-turn response should not be blank.", answer.isBlank());
    }

    public void test06_streamingChatCompletion() {
        /*
         * 第 6 步：流式输出（streaming）。
         *
         * 普通 create() 的特点：
         * - 等模型全部生成完，再一次性把完整结果返回给你
         *
         * streaming 的特点：
         * - 模型边生成，你边收到增量片段
         * - 很适合聊天 UI、打字机效果、长文本逐步展示
         *
         * 这里我们做两件事：
         * 1. 一边消费流式分片，一边把增量文本实时打印出来
         * 2. 把增量文本手动累加成最终结果
         *
         * 这里我没有使用 SDK 提供的 ChatCompletionAccumulator。
         * 原因不是它不好，而是某些 OpenAI 兼容平台返回的 stream chunk 字段不完整，
         * 例如缺少官方 SDK 期望的 id，这时累加器可能会报错。
         *
         * 所以在“兼容平台教程”里，手动拼接 delta 文本反而更稳、更容易理解。
         *
         * 另外，streaming 示例默认不自动运行：
         * - 它通常更慢
         * - 不同兼容平台对流式协议的实现细节也可能不一样
         *
         * 如果你要手动体验：
         * - 先设置环境变量 RUN_OPENAI_STREAMING_DEMO=true
         */
        if (!isDemoEnabled("RUN_OPENAI_STREAMING_DEMO")) {
            System.out.println("Skipping optional demo: test06_streamingChatCompletion");
            return;
        }
        String apiKey = requireApiKeyOrSkip();
        if (apiKey == null) {
            return;
        }
        OpenAIClient client = createCompatibleClient(apiKey);

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(MODEL)
                .addSystemMessage("你是一名耐心的老师，回答要层次清楚。")
                .addUserMessage("分 3 点解释为什么流式输出对聊天产品有帮助。")
                .build();

        StringBuilder streamedText = new StringBuilder();

        System.out.println("=== Step 6: streaming output ===");
        try (StreamResponse<ChatCompletionChunk> streamResponse =
                     client.chat().completions().createStreaming(params)) {
            streamResponse.stream().forEach(chunk -> {
                chunk.choices().forEach(choice ->
                        choice.delta().content().ifPresent(deltaText -> {
                            streamedText.append(deltaText);
                            System.out.print(deltaText);
                        })
                );
            });
        }
        System.out.println();

        String answer = streamedText.toString().trim();
        assertFalse("The streaming response should not be blank.", answer.isBlank());
    }

    private static boolean isDemoEnabled(String envName) {
        String value = System.getenv(envName);
        return value != null && value.equalsIgnoreCase("true");
    }

    private static String requireApiKeyOrSkip() {
        /*
         * 这里故意只认环境变量，不读 .env 文件。
         *
         * 这样项目配置来源就只有一个：
         * “运行环境里的 SILICONFLOW_API_KEY”
         *
         * 好处是很明确：
         * - 不会出现 IDE、命令行、.env 三套配置互相打架
         * - CI/CD 里也更容易统一
         */
        String apiKey = System.getenv(SILICONFLOW_API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping test because SILICONFLOW_API_KEY is not set.");
            return null;
        }
        return apiKey;
    }

    private static OpenAIClient createCompatibleClient(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        /*
         * 这里是本类最重要的一段初始化代码。
         *
         * 为什么不用 OpenAIOkHttpClient.fromEnv()？
         * - 因为 fromEnv() 只认 OPENAI_API_KEY / OPENAI_BASE_URL
         * - 我们当前项目约定的是 SILICONFLOW_API_KEY
         * - 所以最稳妥的方式是显式 builder，把 key 和 baseUrl 都传进去
         *
         * 这也是官方文档推荐的一种标准写法：
         * OpenAIOkHttpClient.builder().apiKey("...").build();
         */
        return OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(SILICONFLOW_BASE_URL)
                .build();
    }

    private static String extractFirstText(ChatCompletion completion) {
        /*
         * SDK 返回的是完整响应对象，不是直接返回字符串。
         *
         * 这是因为一个响应里可能包含：
         * - 多个 choice
         * - message
         * - usage
         * - finish reason
         * 等等元信息
         *
         * 所以我们通常会自己封装一个“取第一段文本”的小工具方法，
         * 让业务代码读起来更像“拿答案”。
         */
        if (completion == null) {
            return "";
        }

        return completion.choices().stream()
                .flatMap(choice -> choice.message().content().stream())
                .findFirst()
                .orElse("")
                .trim();
    }
}
