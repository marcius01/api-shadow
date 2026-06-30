package tech.skullprogrammer.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RelationDefinition {

    private String sourceResource;
    private String sourceField;
    private String targetResource;
    /** Target field on the referenced resource — always "id" for auto-detected relations. */
    private String targetField;
    /** true if defined in MockProfile.relations; false if auto-detected by naming convention. */
    private boolean explicit;
}
