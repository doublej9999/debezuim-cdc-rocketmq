package com.example.cdc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Shared message key extraction logic for both incremental CDC and snapshot events.
 */
@Component
public class CdcMessageKeyExtractor {

    private final ObjectMapper objectMapper;

    public CdcMessageKeyExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String extractPrimaryKey(String value, String key) {
        try {
            if (key != null && !key.isEmpty()) {
                JsonNode keyNode = objectMapper.readTree(key);
                if (keyNode.isObject()) {
                    var fields = keyNode.fields();
                    if (fields.hasNext()) {
                        return fields.next().getValue().asText();
                    }
                } else {
                    return keyNode.asText();
                }
            }

            JsonNode jsonNode = objectMapper.readTree(value);
            JsonNode after = jsonNode.get("after");
            if (after != null && after.has("id")) {
                return after.get("id").asText();
            }
            JsonNode before = jsonNode.get("before");
            if (before != null && before.has("id")) {
                return before.get("id").asText();
            }
        } catch (Exception ignored) {
            // Fall through to UNKNOWN to keep behavior aligned with previous logic.
        }
        return "UNKNOWN";
    }
}
