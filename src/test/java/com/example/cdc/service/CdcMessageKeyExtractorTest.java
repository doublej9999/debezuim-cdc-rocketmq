package com.example.cdc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CdcMessageKeyExtractorTest {

    private CdcMessageKeyExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new CdcMessageKeyExtractor(new ObjectMapper());
    }

    @Test
    void shouldUseFirstFieldFromObjectKey() {
        String value = "{\"after\":{\"id\":10}}";
        String key = "{\"id\":123,\"tenant_id\":2}";
        assertEquals("123", extractor.extractPrimaryKey(value, key));
    }

    @Test
    void shouldUseScalarKeyWhenProvided() {
        String value = "{\"after\":{\"id\":10}}";
        String key = "\"A-1001\"";
        assertEquals("A-1001", extractor.extractPrimaryKey(value, key));
    }

    @Test
    void shouldFallbackToAfterIdWhenKeyMissing() {
        String value = "{\"after\":{\"id\":456}}";
        assertEquals("456", extractor.extractPrimaryKey(value, null));
    }

    @Test
    void shouldFallbackToBeforeIdWhenAfterMissing() {
        String value = "{\"before\":{\"id\":789}}";
        assertEquals("789", extractor.extractPrimaryKey(value, null));
    }

    @Test
    void shouldReturnUnknownWhenNoKeyAndNoId() {
        String value = "{\"after\":{\"name\":\"x\"}}";
        assertEquals("UNKNOWN", extractor.extractPrimaryKey(value, null));
    }
}
