package io.github.lingjiuu.compact.snip;

public class MessageIdTagger {

    public String shortId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return "";
        }
        String compact = messageId.replace("-", "");
        return compact.length() <= 8 ? compact : compact.substring(0, 8);
    }
}
