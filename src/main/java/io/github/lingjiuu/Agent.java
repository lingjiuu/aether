package io.github.lingjiuu;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.FunctionDefinition;
import com.openai.models.chat.completions.*;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public class Agent {

    private static final String SILICONFLOW_API_KEY = "SILICONFLOW_API_KEY";
    private static final String SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1";
    private static final String MODEL = "Pro/zai-org/GLM-5";

    private final String apiKey = System.getenv(SILICONFLOW_API_KEY);

    private final OpenAIClient client = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl(SILICONFLOW_BASE_URL)
            .build();

    private List<ChatCompletionMessageParam> messages = new ArrayList<>();
    private List<ChatCompletionTool> tools = new ArrayList<>();

    Agent(){
        addMessage(ChatCompletionSystemMessageParam.builder()
                .content("You are a helpful assistant")
                .build());
        tools.add(ChatCompletionTool.ofFunction(get_time()));
    }

    public void run_agentloop(String text){
        System.out.println("[AGENT] session start");
        System.out.println("[USER] " + text);

        // 加入user消息
        addMessage(ChatCompletionUserMessageParam.builder()
                .content(text)
                .build());

        int turn = 1;
        while(true){
            System.out.println();
            System.out.println("[AGENT] turn " + turn + " start");
            System.out.println("[STATE] message_count=" + messages.size() + ", tool_count=" + tools.size());

            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(MODEL)
                    .messages(messages)
                    .tools(tools)
                    .build();

            ChatCompletion chatCompletion = client.chat().completions().create(params);

            ChatCompletion.Choice choice = chatCompletion.choices().getFirst();
            ChatCompletionMessage message = choice.message();
            String assistantText = message.content().orElse("").trim();

            System.out.println("[LLM] response_id=" + chatCompletion.id());
            System.out.println("[LLM] finish_reason=" + choice.finishReason().asString());
            if (!assistantText.isBlank()) {
                System.out.println("[ASSISTANT] " + assistantText);
            }


            if (message.toolCalls().isPresent() && !message.toolCalls().get().isEmpty()){
                // 工具调用
                List<ChatCompletionMessageToolCall> chatCompletionMessageToolCalls = message.toolCalls().get();
                System.out.println("[TOOL] tool_call_count=" + chatCompletionMessageToolCalls.size());

                // 先加入assistant消息
                addMessage(message.toParam());

                for (ChatCompletionMessageToolCall toolCall : chatCompletionMessageToolCalls) {
                    ChatCompletionMessageFunctionToolCall functionToolCall = toolCall.asFunction();
                    System.out.println("[TOOL] call id=" + functionToolCall.id()
                            + ", name=" + functionToolCall.function().name()
                            + ", arguments=" + functionToolCall.function().arguments());

                    String result = execute_tool(toolCall);
                    System.out.println("[TOOL] result=" + result);

                    // 构造tool消息
                    ChatCompletionToolMessageParam chatCompletionToolMessageParam = ChatCompletionToolMessageParam.builder()
                            .toolCallId(toolCall.asFunction().id())
                            .content(result)
                            .build();

                    // 加入tool消息
                    addMessage(chatCompletionToolMessageParam);
                }
            }else if(message.content().isPresent() && !message.content().get().isBlank()){
                // 加入assistant消息
                addMessage(message.toParam());
                System.out.println("[AGENT] final answer");
                System.out.println("[ASSISTANT] " + assistantText);
                System.out.println("[AGENT] session end");

                break;
            } else {
                System.out.println("[AGENT] assistant returned no text and no tool call, stopping");
                System.out.println("[AGENT] session end");
                break;
            }

            turn++;
        }

    }


    public String execute_tool(ChatCompletionMessageToolCall toolCall){
        if (toolCall == null || !toolCall.isFunction()) {
            throw new RuntimeException("tool call must be a function call");
        }

        ChatCompletionMessageFunctionToolCall functionToolCall = toolCall.asFunction();
        String toolName = functionToolCall.function().name();
        System.out.println("[TOOL] executing " + toolName);

        if ("get_time".equals(toolName)) {
            String result = ZonedDateTime.now().toString();
            System.out.println("[TOOL] execute_result=" + result);
            return result;
        }

        throw new RuntimeException("unsupported tool: " + toolName);
    }


    public ChatCompletionFunctionTool get_time(){
        return ChatCompletionFunctionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("get_time")
                        .description("Get the current server time")
                        .build())
                .build();
    }


    public void addMessage(Object message){
        if(message instanceof ChatCompletionSystemMessageParam system){
            messages.add(ChatCompletionMessageParam.ofSystem(system));
            System.out.println("[STATE] history+ system");
            return;
        }else  if(message instanceof ChatCompletionUserMessageParam user){
            messages.add(ChatCompletionMessageParam.ofUser(user));
            System.out.println("[STATE] history+ user");
            return;
        }else if (message instanceof ChatCompletionAssistantMessageParam assistant){
            messages.add(ChatCompletionMessageParam.ofAssistant(assistant));
            System.out.println("[STATE] history+ assistant");
            return;
        }else if (message instanceof ChatCompletionToolMessageParam tool){
            messages.add(ChatCompletionMessageParam.ofTool(tool));
            System.out.println("[STATE] history+ tool");
            return;
        }

        throw new RuntimeException("message type not found");
    }

    public String getContent(ChatCompletion completion){
        if (completion == null) {
            throw new RuntimeException("completion is null");
        }

        return completion.choices().stream()
                .flatMap(choice -> choice.message().content().stream())
                .findFirst()
                .orElse("")
                .trim();
    }

}
