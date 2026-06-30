package tech.skullprogrammer.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DenormalizedDefinition {

    private String sourceResource;
    /** Field in sourceResource whose value is copied from the target entity. */
    private String sourceField;
    /** FK field in sourceResource that points to the target entity's id. */
    private String sourceKeyField;
    private String targetResource;
    /** Field in the target entity to copy from (e.g. "nome"). */
    private String targetField;
    /** true if declared explicitly in MockProfile.denormalized; false if auto-detected. */
    private boolean explicit;
}
