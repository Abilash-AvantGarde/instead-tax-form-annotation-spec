package com.instead.annotation.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode data() throws Exception {
        return mapper.readTree("""
                {
                  "income": { "w2": [ { "box1Wages": 100 }, { "box1Wages": 50 } ] },
                  "taxpayer": { "firstName": "Jordan" }
                }
                """);
    }

    @Test
    void resolvesANestedScalarField() throws Exception {
        JsonNode result = PathResolver.resolveOne(data(), "$.taxpayer.firstName");
        assertEquals("Jordan", result.asText());
    }

    @Test
    void resolvesAFixedArrayIndex() throws Exception {
        JsonNode result = PathResolver.resolveOne(data(), "$.income.w2[1].box1Wages");
        assertEquals(50, result.asInt());
    }

    @Test
    void wildcardResolvesEveryMatchingLeaf() throws Exception {
        List<JsonNode> results = PathResolver.resolveAll(data(), "$.income.w2[*].box1Wages");
        assertEquals(2, results.size());
        assertEquals(100, results.get(0).asInt());
        assertEquals(50, results.get(1).asInt());
    }

    @Test
    void missingFieldResolvesToNoMatchesRatherThanThrowing() throws Exception {
        List<JsonNode> results = PathResolver.resolveAll(data(), "$.taxpayer.middleName");
        assertEquals(0, results.size());
    }

    @Test
    void pathNotStartingWithDollarSignIsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> PathResolver.resolveAll(data(), "taxpayer.firstName"));
    }

    @Test
    void malformedSegmentIsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> PathResolver.resolveAll(data(), "$.income..w2"));
    }
}
