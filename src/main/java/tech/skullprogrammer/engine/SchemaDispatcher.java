package tech.skullprogrammer.engine;

import io.swagger.v3.oas.models.media.Schema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.skullprogrammer.model.FakerSuggestion;
import tech.skullprogrammer.model.MockProfile;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@ApplicationScoped
public class SchemaDispatcher {

    @Inject
    FakerSuggestionEngine suggestionEngine;

    @ConfigProperty(name = "mock.faker-suggestions.auto-generate-threshold", defaultValue = "0.50")
    double autoGenerateThreshold;

    // T004: entry point with depth guard
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object dispatch(Schema<?> schema, GenerationContext ctx) {
        if (schema == null) return null;
        if (ctx.depth() >= ctx.maxDepth()) return null;

        // Unresolved $ref: swagger-parser didn't inline it — use example if present, else empty object
        if (schema.get$ref() != null) {
            if (schema.getExample() != null) return schema.getExample();
            log.debug("Unresolved $ref '{}' at depth {} — returning empty object", schema.get$ref(), ctx.depth());
            return new LinkedHashMap<>();
        }

        // Enum takes priority over type dispatch (applies to any type)
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            List<Object> enums = (List<Object>) schema.getEnum();
            return enums.get(ctx.faker().random().nextInt(enums.size()));
        }

        // T010: allOf
        List<Schema> allOf = schema.getAllOf();
        if (allOf != null && !allOf.isEmpty()) {
            return mergeAllOf(allOf, ctx);
        }

        // T011: oneOf / anyOf
        List<Schema> oneOf = schema.getOneOf();
        if (oneOf != null && !oneOf.isEmpty()) {
            return dispatch(oneOf.get(0), ctx.nested(""));
        }
        List<Schema> anyOf = schema.getAnyOf();
        if (anyOf != null && !anyOf.isEmpty()) {
            return dispatch(anyOf.get(0), ctx.nested(""));
        }

        String type = schema.getType();

        // T009: object
        if ("object".equals(type) || (type == null && schema.getProperties() != null && !schema.getProperties().isEmpty())) {
            return generateObject(schema, ctx);
        }

        // T012: array
        if ("array".equals(type)) {
            return generateArray(schema, ctx);
        }

        // T005: string
        if ("string".equals(type)) {
            return generateString(schema, ctx);
        }

        // T006: integer
        if ("integer".equals(type)) {
            return generateInteger(schema, ctx);
        }

        // T007: number
        if ("number".equals(type)) {
            return generateNumber(schema, ctx);
        }

        // T008: boolean
        if ("boolean".equals(type)) {
            return ctx.faker().bool().bool();
        }

        // T013: catch-all — use example if present, otherwise log and return null
        if (schema.getExample() != null) return schema.getExample();
        log.debug("No type/properties/allOf/oneOf for schema (type={}), returning null", type);
        return null;
    }

    // T010: merge all sub-schemas into one map
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> mergeAllOf(List<Schema> schemas, GenerationContext ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Schema sub : schemas) {
            Object generated = dispatch((Schema<?>) sub, ctx);
            if (generated instanceof Map) {
                result.putAll((Map<String, Object>) generated);
            }
        }
        return result;
    }

    // T009: object generation with required/optional probability and field overrides
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> generateObject(Schema<?> schema, GenerationContext ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Schema> properties = (Map<String, Schema>) schema.getProperties();
        if (properties == null || properties.isEmpty()) {
            return result;
        }

        List<String> required = schema.getRequired() != null ? schema.getRequired() : Collections.emptyList();

        for (Map.Entry<String, Schema> entry : properties.entrySet()) {
            String fieldName = entry.getKey();
            Schema<?> fieldSchema = entry.getValue();
            boolean isRequired = required.contains(fieldName);

            if (!isRequired && ctx.faker().random().nextDouble() >= ctx.optionalProbability()) {
                continue;
            }

            result.put(fieldName, applyFieldConfig(fieldName, fieldSchema, ctx));
        }
        return result;
    }

    // T024: apply profile field-level overrides before dispatching
    private Object applyFieldConfig(String fieldName, Schema<?> fieldSchema, GenerationContext ctx) {
        if (ctx.override() != null && ctx.override().getFields() != null) {
            MockProfile.FieldConfig config = ctx.override().getFields().get(fieldName);
            if (config != null) {
                if (config.getFaker() != null) {
                    try {
                        return ctx.faker().expression("#{" + config.getFaker() + "}");
                    } catch (Exception e) {
                        log.debug("Profile faker expression '{}' failed for field '{}', falling back: {}",
                                config.getFaker(), fieldName, e.getMessage());
                    }
                }
                if (config.getPattern() != null) {
                    try {
                        return ctx.faker().regexify(config.getPattern());
                    } catch (Exception e) {
                        log.debug("Profile pattern '{}' failed for field '{}', falling back: {}",
                                config.getPattern(), fieldName, e.getMessage());
                    }
                }
                if (config.getEnumValues() != null && !config.getEnumValues().isEmpty()) {
                    // T025: weighted random
                    if (config.getDistribution() != null && !config.getDistribution().isEmpty()) {
                        return pickWeighted(config.getEnumValues(), config.getDistribution(),
                                ctx.faker().random().nextDouble());
                    }
                    return config.getEnumValues().get(ctx.faker().random().nextInt(config.getEnumValues().size()));
                }
            }
        }
        return dispatch(fieldSchema, ctx.nested(fieldName));
    }

    // T012: array generation with count from profile or default
    private List<Object> generateArray(Schema<?> schema, GenerationContext ctx) {
        int count = (ctx.override() != null && ctx.override().getCount() != null && ctx.override().getCount() > 0)
                ? ctx.override().getCount()
                : ctx.defaultArrayCount();

        List<Object> result = new ArrayList<>(count);
        Schema<?> itemSchema = schema.getItems();
        for (int i = 0; i < count; i++) {
            result.add(dispatch(itemSchema, ctx.nested("[]")));
        }
        return result;
    }

    // T005: string with format, pattern (already handled at dispatch level for enum)
    private String generateString(Schema<?> schema, GenerationContext ctx) {
        if (schema.getPattern() != null) {
            return ctx.faker().regexify(schema.getPattern());
        }
        String format = schema.getFormat();
        if (format != null) {
            switch (format) {
                case "date":
                    return LocalDate.now().minusDays(ctx.faker().random().nextInt(365)).toString();
                case "date-time":
                    return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                case "email":
                    return ctx.faker().internet().emailAddress();
                case "uuid":
                    return UUID.randomUUID().toString();
                case "password":
                    return ctx.faker().internet().password();
            }
        }
        // Semantic auto-generation: use field name to pick a meaningful faker expression
        if (ctx.useSemantic() && !ctx.fieldPath().isEmpty() && suggestionEngine.isReady()) {
            List<FakerSuggestion> suggestions = suggestionEngine.suggest(ctx.fieldPath(), "string", format, 1);
            if (!suggestions.isEmpty() && suggestions.get(0).getScore() >= autoGenerateThreshold) {
                try {
                    return ctx.faker().expression("#{" + suggestions.get(0).getExpression() + "}");
                } catch (Exception e) {
                    log.debug("Faker expression '{}' failed for field '{}', falling back to lorem: {}",
                            suggestions.get(0).getExpression(), ctx.fieldPath(), e.getMessage());
                }
            }
        }
        return ctx.faker().lorem().word();
    }

    // T006: integer with min/max, int32 vs int64
    private Number generateInteger(Schema<?> schema, GenerationContext ctx) {
        long min = schema.getMinimum() != null ? schema.getMinimum().longValue() : 0L;
        long max = schema.getMaximum() != null ? schema.getMaximum().longValue() : 1000L;
        if (max <= min) max = min + 1000L;
        long value = ctx.faker().number().numberBetween(min, max + 1);
        if ("int64".equals(schema.getFormat())) return value;
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    // T007: number (float/double) with min/max
    private Double generateNumber(Schema<?> schema, GenerationContext ctx) {
        double min = schema.getMinimum() != null ? schema.getMinimum().doubleValue() : 0.0;
        double max = schema.getMaximum() != null ? schema.getMaximum().doubleValue() : 1000.0;
        if (max <= min) max = min + 1000.0;
        return ctx.faker().number().randomDouble(2, (long) min, (long) max);
    }

    // T025: weighted random selection by cumulative threshold
    private String pickWeighted(List<String> values, List<Integer> weights, double random) {
        int total = weights.stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return values.get(0);
        double target = random * total;
        double cumulative = 0;
        for (int i = 0; i < values.size(); i++) {
            cumulative += weights.get(i);
            if (target < cumulative) return values.get(i);
        }
        return values.get(values.size() - 1);
    }
}
