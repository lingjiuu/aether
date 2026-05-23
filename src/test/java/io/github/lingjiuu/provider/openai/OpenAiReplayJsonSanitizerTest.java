package io.github.lingjiuu.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import junit.framework.TestCase;

public class OpenAiReplayJsonSanitizerTest extends TestCase {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public void testSanitizeRemovesSdkValidityFieldsRecursively() throws Exception {
        String json = """
                {
                  "id": "msg_1",
                  "type": "message",
                  "phase": "final_answer",
                  "isValid": true,
                  "content": [
                    {
                      "type": "output_text",
                      "text": "hello",
                      "logprobs": [],
                      "isValid": true
                    }
                  ]
                }
                """;

        JsonNode sanitized = objectMapper.readTree(OpenAiReplayJsonSanitizer.sanitize(json, objectMapper));

        assertFalse(sanitized.has("isValid"));
        assertFalse(sanitized.get("content").get(0).has("isValid"));
        assertEquals("final_answer", sanitized.get("phase").asText());
        assertTrue(sanitized.get("content").get(0).has("logprobs"));
    }
}
