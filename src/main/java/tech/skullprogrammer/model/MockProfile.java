package tech.skullprogrammer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MockProfile {

    private String locale;
    private Map<String, Map<String, EndpointOverride>> overrides = new HashMap<>();
    private Map<String, Map<String, FixtureResponse>> fixtures = new HashMap<>();
    /** Explicit FK definitions: "sourceResource.sourceField" → "targetResource.targetField" */
    private Map<String, String> relations = new HashMap<>();
    /** Entity count override per resource name. Default is 20 when absent. */
    private Map<String, Integer> dataset = new HashMap<>();

    /**
     * Denormalized field copy rules.
     * Key: "sourceResource.sourceField" (e.g. "campioni.nomePuntoPrelievo")
     * Value: "sourceKeyField->targetResource.targetField" (e.g. "idPuntoPrelievo->puntiPrelievo.nome")
     */
    private Map<String, String> denormalized = new HashMap<>();

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EndpointOverride {
        private String latency;
        private Integer count;
        private Long seed;
        private Integer forceStatus;
        private Double optionalProbability;
        private Integer maxSchemaDepth;
        private Map<String, FieldConfig> fields = new HashMap<>();
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldConfig {
        private String faker;
        private String pattern;
        @JsonProperty("enum")
        private List<String> enumValues;
        private List<Integer> distribution;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FixtureResponse {
        @JsonProperty("static")
        private Map<String, Object> staticData = new HashMap<>();
    }
}