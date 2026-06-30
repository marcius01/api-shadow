package tech.skullprogrammer.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SpecSchemaDTO {
    private String specName;
    private List<EndpointSchemaDTO> endpoints;

    @Data
    @Builder
    public static class EndpointSchemaDTO {
        private String path;
        private String method;
        private int defaultStatusCode;
        private boolean hasSchema;
        private List<FieldSchema> fields;
    }
}
