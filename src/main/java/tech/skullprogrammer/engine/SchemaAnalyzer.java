package tech.skullprogrammer.engine;

import io.swagger.v3.oas.models.media.Schema;
import jakarta.enterprise.context.ApplicationScoped;
import tech.skullprogrammer.model.FieldSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class SchemaAnalyzer {

    private static final int MAX_DISPLAY_DEPTH = 5;

    public List<FieldSchema> flatten(Schema<?> schema) {
        return flatten(schema, MAX_DISPLAY_DEPTH);
    }

    public List<FieldSchema> flatten(Schema<?> schema, int maxDepth) {
        List<FieldSchema> result = new ArrayList<>();
        flattenRecursive(schema, "", Set.of(), result, 0, maxDepth);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void flattenRecursive(Schema<?> schema, String prefix, Set<String> requiredByParent,
                                  List<FieldSchema> result, int depth, int maxDepth) {
        if (schema == null || depth >= maxDepth) return;

        String type = schema.getType();

        if ("object".equals(type) || (type == null && schema.getProperties() != null)) {
            Map<String, Schema> props = (Map<String, Schema>) (Map<?, ?>) schema.getProperties();
            if (props == null || props.isEmpty()) return;

            Set<String> required = schema.getRequired() != null
                    ? Set.copyOf(schema.getRequired())
                    : Set.of();

            props.forEach((name, propSchema) -> {
                String fullName = prefix.isEmpty() ? name : prefix + "." + name;
                String propType = propSchema.getType();
                boolean isRequired = requiredByParent.contains(prefix.isEmpty() ? name : name)
                        || required.contains(name);

                if ("object".equals(propType) && propSchema.getProperties() != null) {
                    flattenRecursive(propSchema, fullName, required, result, depth + 1, maxDepth);
                } else if ("array".equals(propType)) {
                    Schema<?> items = propSchema.getItems();
                    if (items != null) {
                        String itemType = items.getType();
                        if ("object".equals(itemType) && items.getProperties() != null) {
                            flattenRecursive(items, fullName + "[]", Set.of(), result, depth + 1, maxDepth);
                        } else {
                            result.add(FieldSchema.builder()
                                    .name(fullName + "[]")
                                    .type(itemType != null ? itemType : "string")
                                    .format(items.getFormat())
                                    .required(isRequired)
                                    .build());
                        }
                    }
                } else {
                    result.add(FieldSchema.builder()
                            .name(fullName)
                            .type(propType != null ? propType : "string")
                            .format(propSchema.getFormat())
                            .required(isRequired)
                            .build());
                }
            });

        } else if ("array".equals(type)) {
            Schema<?> items = schema.getItems();
            if (items != null) {
                String itemsPrefix = prefix.isEmpty() ? "[]" : prefix + "[]";
                flattenRecursive(items, itemsPrefix, Set.of(), result, depth + 1, maxDepth);
            }
        }
    }
}
