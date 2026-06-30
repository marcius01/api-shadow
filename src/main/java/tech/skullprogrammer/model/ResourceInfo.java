package tech.skullprogrammer.model;

import io.swagger.v3.oas.models.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ResourceInfo {

    private String name;
    private String listPath;
    private String singlePath;
    /** Set for sub-resources: name of the parent resource. */
    private String parentResourceName;
    /** Set for sub-resources: the path param name that identifies the parent, e.g. "id". */
    private String parentIdParam;
    /** Raw 200 response schema for the list endpoint (before unwrapping). */
    private Schema<?> listSchema;
    /** Unwrapped item schema used for entity generation. */
    private Schema<?> itemSchema;
    /** Detected pagination fields, or null if the response has no pagination wrapper. */
    private PaginationShape paginationShape;
}
