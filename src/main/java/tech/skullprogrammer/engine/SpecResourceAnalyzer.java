package tech.skullprogrammer.engine;

import io.swagger.v3.oas.models.media.Schema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import tech.skullprogrammer.model.DenormalizedDefinition;
import tech.skullprogrammer.model.MockEndpoint;
import tech.skullprogrammer.model.MockProfile;
import tech.skullprogrammer.model.PaginationShape;
import tech.skullprogrammer.model.ParsedSpec;
import tech.skullprogrammer.model.RelationDefinition;
import tech.skullprogrammer.model.ResourceGraph;
import tech.skullprogrammer.model.ResourceInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Analyzes a ParsedSpec to detect REST resources, foreign key relations,
 * and pagination shapes. Sets resourceName/pathType on each MockEndpoint in-place.
 */
@Slf4j
@ApplicationScoped
public class SpecResourceAnalyzer {

    private static final List<String> ARRAY_FIELD_NAMES =
            List.of("data", "content", "items", "elements", "results", "records", "list");
    private static final List<String> TOTAL_ELEMENTS_NAMES =
            List.of("totalElements", "total", "totalCount", "totalRecords", "count");
    private static final List<String> TOTAL_PAGES_NAMES =
            List.of("totalPages", "pageCount", "pages");
    private static final List<String> CURRENT_PAGE_NAMES =
            List.of("currentPage", "page", "pageNumber", "pageIndex");
    private static final List<String> PAGE_SIZE_NAMES =
            List.of("size", "pageSize", "limit", "perPage");

    private static final float FK_SEMANTIC_THRESHOLD = 0.82f;

    @Inject
    EmbeddingEngine embeddingEngine;

    /** Analyze with heuristic FK detection only (safe at startup, model not required). */
    public ResourceGraph analyze(ParsedSpec spec, MockProfile profile) {
        return analyze(spec, profile, false);
    }

    /** Analyze with optional semantic FK detection via embedding similarity. */
    public ResourceGraph analyze(ParsedSpec spec, MockProfile profile, boolean semanticFk) {
        Map<String, ResourceInfo.ResourceInfoBuilder> builders = new LinkedHashMap<>();

        // Pass 1: classify every endpoint, populate resource builders
        for (MockEndpoint endpoint : spec.getEndpoints()) {
            List<String> segments = splitPath(endpoint.getPath());
            if (segments.isEmpty()) {
                endpoint.setPathType(MockEndpoint.PathType.OTHER);
                continue;
            }

            MockEndpoint.PathType type = classifyPath(segments);
            String resourceName = extractResourceName(segments);

            if (resourceName == null) {
                endpoint.setPathType(MockEndpoint.PathType.OTHER);
                continue;
            }

            endpoint.setPathType(type);
            endpoint.setResourceName(resourceName);

            ResourceInfo.ResourceInfoBuilder builder =
                    builders.computeIfAbsent(resourceName, n -> ResourceInfo.builder().name(n));

            switch (type) {
                case LIST:
                    if ("GET".equalsIgnoreCase(endpoint.getMethod())) {
                        Schema<?> listSchema = resolveListSchema(endpoint);
                        builder.listPath(endpoint.getPath()).listSchema(listSchema);
                        if (listSchema != null) builder.itemSchema(extractItemSchema(listSchema));
                    }
                    break;
                case SINGLE:
                    builder.singlePath(endpoint.getPath());
                    break;
                case SUB_RESOURCE_LIST:
                    applySubResourceInfo(builder, segments);
                    if ("GET".equalsIgnoreCase(endpoint.getMethod())) {
                        Schema<?> listSchema = resolveListSchema(endpoint);
                        builder.listPath(endpoint.getPath()).listSchema(listSchema);
                        if (listSchema != null) builder.itemSchema(extractItemSchema(listSchema));
                    }
                    break;
                case SUB_RESOURCE_SINGLE:
                    applySubResourceInfo(builder, segments);
                    builder.singlePath(endpoint.getPath());
                    break;
                default:
                    break;
            }
        }

        // Build ResourceInfo objects
        Map<String, ResourceInfo> resources = new LinkedHashMap<>();
        builders.forEach((name, b) -> resources.put(name, b.build()));

        // Pass 2: detect pagination shapes and FK relations
        List<RelationDefinition> relations = new ArrayList<>();

        // Pre-compute resource name embeddings once if semantic mode is active
        Map<String, float[]> resourceEmbeddings = new HashMap<>();
        if (semanticFk && embeddingEngine.isReady()) {
            for (String resName : resources.keySet()) {
                resourceEmbeddings.put(resName, embeddingEngine.embed(resName));
            }
            log.debug("SpecResourceAnalyzer: pre-computed {} resource embeddings for semantic FK", resourceEmbeddings.size());
        }

        resources.forEach((name, info) -> {
            if (info.getListSchema() != null) {
                info.setPaginationShape(detectPaginationShape(info.getListSchema()));
            }
            if (info.getItemSchema() != null) {
                detectForeignKeys(name, info.getItemSchema(), resources.keySet(), relations, resourceEmbeddings);
            }
        });

        // Pass 3: merge explicit profile relations (override auto-detected)
        if (profile != null && profile.getRelations() != null) {
            profile.getRelations().forEach((key, value) -> {
                String[] kp = key.split("\\.", 2);
                String[] vp = value.split("\\.", 2);
                if (kp.length == 2 && vp.length == 2) {
                    relations.removeIf(r -> r.getSourceResource().equals(kp[0])
                            && r.getSourceField().equals(kp[1]));
                    relations.add(RelationDefinition.builder()
                            .sourceResource(kp[0]).sourceField(kp[1])
                            .targetResource(vp[0]).targetField(vp[1])
                            .explicit(true).build());
                } else {
                    log.warn("Ignoring malformed relation entry: '{}' → '{}'", key, value);
                }
            });
        }

        // Pass 4: detect denormalized fields (requires FK relations to be finalized first)
        List<DenormalizedDefinition> denormalized = new ArrayList<>();
        resources.forEach((name, info) -> {
            if (info.getItemSchema() != null) {
                detectDenormalizedFields(name, info.getItemSchema(), resources, relations, denormalized);
            }
        });

        // Pass 5: merge explicit profile denormalized overrides
        if (profile != null && profile.getDenormalized() != null) {
            profile.getDenormalized().forEach((key, value) -> {
                // key: "campioni.nomePuntoPrelievo"
                // value: "idPuntoPrelievo->puntiPrelievo.nome"
                String[] kp = key.split("\\.", 2);
                String[] arrow = value.split("->", 2);
                if (kp.length == 2 && arrow.length == 2) {
                    String sourceResource = kp[0];
                    String sourceField = kp[1];
                    String sourceKeyField = arrow[0].trim();
                    String[] targetParts = arrow[1].trim().split("\\.", 2);
                    if (targetParts.length == 2) {
                        denormalized.removeIf(d -> d.getSourceResource().equals(sourceResource)
                                && d.getSourceField().equals(sourceField));
                        denormalized.add(DenormalizedDefinition.builder()
                                .sourceResource(sourceResource).sourceField(sourceField)
                                .sourceKeyField(sourceKeyField)
                                .targetResource(targetParts[0]).targetField(targetParts[1])
                                .explicit(true).build());
                    }
                } else {
                    log.warn("Ignoring malformed denormalized entry: '{}' → '{}'", key, value);
                }
            });
        }

        log.info("SpecResourceAnalyzer: {} resources, {} relations, {} denormalized detected",
                resources.size(), relations.size(), denormalized.size());
        resources.forEach((name, info) ->
                log.debug("  Resource '{}': list={}, single={}, parent={}, itemSchema={}",
                        name, info.getListPath(), info.getSinglePath(),
                        info.getParentResourceName(), info.getItemSchema() != null ? "yes" : "no"));

        return ResourceGraph.builder().resources(resources).relations(relations).denormalized(denormalized).build();
    }

    // ── Path analysis ──────────────────────────────────────────────────────────

    private List<String> splitPath(String path) {
        return Arrays.stream(path.split("/"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private boolean isParam(String segment) {
        return segment.startsWith("{") && segment.endsWith("}");
    }

    private MockEndpoint.PathType classifyPath(List<String> segments) {
        String last = segments.get(segments.size() - 1);
        boolean lastIsParam = isParam(last);
        long paramsBeforeLast = segments.subList(0, segments.size() - 1).stream()
                .filter(this::isParam).count();

        if (!lastIsParam) {
            return paramsBeforeLast == 0
                    ? MockEndpoint.PathType.LIST
                    : MockEndpoint.PathType.SUB_RESOURCE_LIST;
        } else {
            return paramsBeforeLast == 0
                    ? MockEndpoint.PathType.SINGLE
                    : MockEndpoint.PathType.SUB_RESOURCE_SINGLE;
        }
    }

    private String extractResourceName(List<String> segments) {
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (!isParam(segments.get(i))) return segments.get(i);
        }
        return null;
    }

    private void applySubResourceInfo(ResourceInfo.ResourceInfoBuilder builder, List<String> segments) {
        // Parent resource: literal segment just before the first param
        // Parent id param: the first param segment (without braces)
        for (int i = 0; i < segments.size(); i++) {
            if (isParam(segments.get(i))) {
                if (i > 0) builder.parentResourceName(segments.get(i - 1));
                builder.parentIdParam(segments.get(i).substring(1, segments.get(i).length() - 1));
                break;
            }
        }
    }

    // ── Schema analysis ────────────────────────────────────────────────────────

    private Schema<?> resolveListSchema(MockEndpoint endpoint) {
        Schema<?> schema = endpoint.getResponses().get(String.valueOf(endpoint.getDefaultStatusCode()));
        if (schema == null) schema = endpoint.getResponses().get("200");
        return schema;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    Schema<?> extractItemSchema(Schema<?> schema) {
        if (schema == null) return null;
        if ("array".equals(schema.getType()) && schema.getItems() != null) {
            return schema.getItems();
        }
        if (schema.getProperties() != null) {
            Map<String, Schema> props = (Map<String, Schema>) (Map<?, ?>) schema.getProperties();
            for (String name : ARRAY_FIELD_NAMES) {
                Schema<?> prop = props.get(name);
                if (prop != null && "array".equals(prop.getType()) && prop.getItems() != null) {
                    return prop.getItems();
                }
            }
        }
        return schema;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private PaginationShape detectPaginationShape(Schema<?> schema) {
        if (schema == null) return null;
        if (!"object".equals(schema.getType()) && schema.getProperties() == null) return null;
        Map<String, Schema> props = (Map<String, Schema>) (Map<?, ?>) schema.getProperties();
        if (props == null || props.isEmpty()) return null;

        Set<String> keys = props.keySet();
        String arrayField       = findField(keys, ARRAY_FIELD_NAMES);
        String totalElements    = findField(keys, TOTAL_ELEMENTS_NAMES);
        String totalPages       = findField(keys, TOTAL_PAGES_NAMES);
        String currentPage      = findField(keys, CURRENT_PAGE_NAMES);
        String pageSize         = findField(keys, PAGE_SIZE_NAMES);

        if (arrayField == null && totalElements == null) return null;

        return PaginationShape.builder()
                .arrayField(arrayField)
                .totalElementsField(totalElements)
                .totalPagesField(totalPages)
                .currentPageField(currentPage)
                .pageSizeField(pageSize)
                .build();
    }

    private String findField(Set<String> keys, List<String> candidates) {
        for (String c : candidates) {
            if (keys.contains(c)) return c;
        }
        return null;
    }

    // ── FK detection ───────────────────────────────────────────────────────────

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void detectForeignKeys(String resourceName, Schema<?> itemSchema,
                                   Set<String> knownResources, List<RelationDefinition> relations,
                                   Map<String, float[]> resourceEmbeddings) {
        if (itemSchema == null) return;
        Map<String, Schema> props = (Map<String, Schema>) (Map<?, ?>) itemSchema.getProperties();
        if (props == null) return;

        for (String fieldName : props.keySet()) {
            String matched = null;

            // Pattern: idXxx → Xxx → match against known resources
            if (fieldName.length() > 2 && fieldName.toLowerCase().startsWith("id")) {
                matched = findMatchingResource(fieldName.substring(2), knownResources,
                        resourceName, resourceEmbeddings);
            }
            // Pattern: xxxId → xxx → match against known resources
            if (matched == null && fieldName.length() > 2 && fieldName.toLowerCase().endsWith("id")) {
                matched = findMatchingResource(fieldName.substring(0, fieldName.length() - 2),
                        knownResources, resourceName, resourceEmbeddings);
            }

            if (matched != null) {
                boolean alreadyExists = relations.stream().anyMatch(r ->
                        r.getSourceResource().equals(resourceName) && r.getSourceField().equals(fieldName));
                if (!alreadyExists) {
                    relations.add(RelationDefinition.builder()
                            .sourceResource(resourceName).sourceField(fieldName)
                            .targetResource(matched).targetField("id")
                            .explicit(false).build());
                    log.debug("  FK detected: {}.{} → {}.id", resourceName, fieldName, matched);
                }
            }
        }
    }

    private String findMatchingResource(String candidate, Set<String> knownResources,
                                        String selfResource, Map<String, float[]> resourceEmbeddings) {
        if (candidate.isEmpty()) return null;
        String nc = candidate.toLowerCase();

        // Phase 1: heuristic matching (always runs)
        String heuristicMatch = null;
        for (String resource : knownResources) {
            if (resource.equals(selfResource)) continue;
            String nr = resource.toLowerCase();

            if (nc.equals(nr)) return resource;

            if (nr.endsWith("es") && nc.equals(nr.substring(0, nr.length() - 2))) return resource;
            if (nr.endsWith("s")  && nc.equals(nr.substring(0, nr.length() - 1))) return resource;

            // Italian compound plurals: camelCase stem comparison (e.g. puntoPrelievo ↔ puntiPrelievo)
            List<String> ncWords = splitCamelCase(candidate);
            List<String> nrWords = splitCamelCase(resource);
            if (!ncWords.isEmpty() && ncWords.size() == nrWords.size()) {
                boolean allMatch = true;
                for (int i = 0; i < ncWords.size(); i++) {
                    if (!stemMatch(ncWords.get(i), nrWords.get(i))) { allMatch = false; break; }
                }
                if (allMatch) { heuristicMatch = resource; break; }
            }
        }
        if (heuristicMatch != null) return heuristicMatch;

        // Phase 2: semantic fallback — uses embedding similarity when available
        if (!resourceEmbeddings.isEmpty()) {
            float[] candidateEmb = embeddingEngine.embed(candidate);
            String bestResource = null;
            float bestScore = FK_SEMANTIC_THRESHOLD;
            for (Map.Entry<String, float[]> entry : resourceEmbeddings.entrySet()) {
                if (entry.getKey().equals(selfResource)) continue;
                float score = embeddingEngine.cosineSimilarity(candidateEmb, entry.getValue());
                if (score > bestScore) { bestScore = score; bestResource = entry.getKey(); }
            }
            if (bestResource != null) {
                log.debug("  FK semantic match: '{}' → '{}' (score={})", candidate, bestResource, bestScore);
                return bestResource;
            }
        }

        return null;
    }

    // ── Denormalized field detection ───────────────────────────────────────────

    /**
     * Detects fields that are denormalized copies from a referenced entity.
     * Example: campioni.nomePuntoPrelievo is the "nome" field copied from the puntiPrelievo entity
     * referenced by campioni.idPuntoPrelievo.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void detectDenormalizedFields(String resourceName, Schema<?> itemSchema,
                                          Map<String, ResourceInfo> resources,
                                          List<RelationDefinition> relations,
                                          List<DenormalizedDefinition> denormalized) {
        Map<String, Schema> props = (Map<String, Schema>) (Map<?, ?>) itemSchema.getProperties();
        if (props == null) return;

        for (RelationDefinition rel : relations) {
            if (!rel.getSourceResource().equals(resourceName)) continue;

            String fkField = rel.getSourceField();
            String targetResource = rel.getTargetResource();

            // Extract base name from FK field: idPuntoPrelievo → PuntoPrelievo
            String baseCamel;
            if (fkField.length() > 2 && fkField.toLowerCase().startsWith("id")) {
                baseCamel = fkField.substring(2);
            } else if (fkField.length() > 2 && fkField.toLowerCase().endsWith("id")) {
                String base = fkField.substring(0, fkField.length() - 2);
                baseCamel = Character.toUpperCase(base.charAt(0)) + base.substring(1);
            } else {
                continue;
            }

            // Get target resource fields
            ResourceInfo targetInfo = resources.get(targetResource);
            if (targetInfo == null || targetInfo.getItemSchema() == null) continue;
            Map<String, Schema> targetProps = (Map<String, Schema>) (Map<?, ?>) targetInfo.getItemSchema().getProperties();
            if (targetProps == null) continue;

            // Look for fields named {prefix}{BaseCamel} where the target has a field named {prefix}
            for (String fieldName : props.keySet()) {
                if (fieldName.equals(fkField)) continue;
                if (!fieldName.endsWith(baseCamel)) continue;

                String prefix = fieldName.substring(0, fieldName.length() - baseCamel.length());
                if (prefix.isEmpty()) continue;

                // candidate target field: lowercase first char of prefix
                String targetFieldCandidate = Character.toLowerCase(prefix.charAt(0)) + prefix.substring(1);
                if (!targetProps.containsKey(targetFieldCandidate)) continue;

                boolean alreadyExists = denormalized.stream().anyMatch(d ->
                        d.getSourceResource().equals(resourceName) && d.getSourceField().equals(fieldName));
                if (!alreadyExists) {
                    denormalized.add(DenormalizedDefinition.builder()
                            .sourceResource(resourceName).sourceField(fieldName)
                            .sourceKeyField(fkField)
                            .targetResource(targetResource).targetField(targetFieldCandidate)
                            .explicit(false).build());
                    log.debug("  Denormalized detected: {}.{} ← ({}.{} → {}.{})",
                            resourceName, fieldName, resourceName, fkField, targetResource, targetFieldCandidate);
                }
            }
        }
    }

    /** True if two words share the same consonantal stem (strip trailing vowel from each). */
    private boolean stemMatch(String a, String b) {
        if (a.equals(b)) return true;
        String sa = stripTrailingVowel(a);
        String sb = stripTrailingVowel(b);
        return sa.length() > 1 && sa.equals(sb);
    }

    private String stripTrailingVowel(String s) {
        if (s.isEmpty()) return s;
        return "aeiou".indexOf(s.charAt(s.length() - 1)) >= 0 ? s.substring(0, s.length() - 1) : s;
    }

    private List<String> splitCamelCase(String s) {
        String[] parts = s.split("(?=[A-Z])");
        List<String> words = new ArrayList<>();
        for (String p : parts) { if (!p.isEmpty()) words.add(p.toLowerCase()); }
        return words;
    }
}
