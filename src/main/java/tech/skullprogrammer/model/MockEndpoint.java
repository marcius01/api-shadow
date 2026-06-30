package tech.skullprogrammer.model;

import io.swagger.v3.oas.models.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class MockEndpoint {

    private String path;
    private String method;
    private int defaultStatusCode;
    /**
     * Keyed by status code string as defined in OpenAPI ("200", "404", "default", ...).
     * Value is the response schema, or null if the response has no body.
     */
    private Map<String, Schema<?>> responses = new LinkedHashMap<>();
    private List<MockParameter> parameters = new ArrayList<>();

    /** Set by SpecResourceAnalyzer after parsing — null for non-REST endpoints. */
    private String resourceName;
    /** Set by SpecResourceAnalyzer after parsing — defaults to OTHER. */
    private PathType pathType = PathType.OTHER;

    /** Backward-compatible constructor used by SpecParser. */
    public MockEndpoint(String path, String method, int defaultStatusCode,
                        Map<String, Schema<?>> responses, List<MockParameter> parameters) {
        this.path = path;
        this.method = method;
        this.defaultStatusCode = defaultStatusCode;
        this.responses = responses;
        this.parameters = parameters;
        this.pathType = PathType.OTHER;
    }

    public enum PathType {
        LIST, SINGLE, SUB_RESOURCE_LIST, SUB_RESOURCE_SINGLE, OTHER
    }
}
