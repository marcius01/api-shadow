package tech.skullprogrammer.model;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class ResourceGraph {

    /** All detected REST resources, keyed by resource name. */
    @Builder.Default
    private Map<String, ResourceInfo> resources = new LinkedHashMap<>();

    /** All FK relations: auto-detected + explicit profile overrides. */
    @Builder.Default
    private List<RelationDefinition> relations = new ArrayList<>();

    /** Denormalized field copy rules: auto-detected + explicit profile overrides. */
    @Builder.Default
    private List<DenormalizedDefinition> denormalized = new ArrayList<>();
}
