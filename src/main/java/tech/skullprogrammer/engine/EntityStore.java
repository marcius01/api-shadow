package tech.skullprogrammer.engine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.skullprogrammer.model.DenormalizedDefinition;
import tech.skullprogrammer.model.MockProfile;
import tech.skullprogrammer.model.ParsedSpec;
import tech.skullprogrammer.model.RelationDefinition;
import tech.skullprogrammer.model.ResourceGraph;
import tech.skullprogrammer.model.ResourceInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Pre-seeded in-memory entity store.
 * Structure: specName → resourceName → (entityId → entityObject).
 */
@Slf4j
@ApplicationScoped
public class EntityStore {

    private static final int DEFAULT_COUNT = 20;
    private static final int MAX_TOTAL = 1000;

    @Inject
    SchemaDispatcher schemaDispatcher;

    @ConfigProperty(name = "mock.optional-field-probability", defaultValue = "0.70")
    double optionalProbability;

    @ConfigProperty(name = "mock.max-schema-depth", defaultValue = "5")
    int maxSchemaDepth;

    @ConfigProperty(name = "mock.default-array-count", defaultValue = "3")
    int defaultArrayCount;

    /** specName → resourceName → (id → entity) */
    private final ConcurrentHashMap<String, Map<String, LinkedHashMap<Long, Map<String, Object>>>> datasets =
            new ConcurrentHashMap<>();

    /** specName → ResourceGraph */
    private final ConcurrentHashMap<String, ResourceGraph> graphs = new ConcurrentHashMap<>();

    // ── Public API ─────────────────────────────────────────────────────────────

    public void seed(String specName, ParsedSpec spec, MockProfile profile,
                     ResourceGraph graph, Locale locale, boolean useSemantic) {
        long start = System.currentTimeMillis();
        Faker faker = new Faker(locale != null ? locale : Locale.ENGLISH);

        Map<String, LinkedHashMap<Long, Map<String, Object>>> specDatasets = new LinkedHashMap<>();
        int totalSeeded = 0;

        for (Map.Entry<String, ResourceInfo> entry : graph.getResources().entrySet()) {
            String resourceName = entry.getKey();
            ResourceInfo info = entry.getValue();

            if (info.getItemSchema() == null) {
                specDatasets.put(resourceName, new LinkedHashMap<>());
                log.debug("EntityStore: no item schema for '{}' — empty dataset", resourceName);
                continue;
            }

            int count = resolveCount(resourceName, profile, totalSeeded);
            if (count == 0) {
                specDatasets.put(resourceName, new LinkedHashMap<>());
                log.debug("EntityStore: count=0 for '{}' (profile or cap)", resourceName);
                continue;
            }

            // Profile field overrides for this resource: normalize wrapper prefix (data[].nome → nome)
            MockProfile.EndpointOverride itemOverride = resolveItemOverride(profile, info.getListPath());

            LinkedHashMap<Long, Map<String, Object>> dataset = new LinkedHashMap<>();
            for (long i = 1; i <= count; i++) {
                // optionalProbability=1.0: entity store entities are always complete
                GenerationContext ctx = new GenerationContext(
                        0, faker, info.getListPath() != null ? info.getListPath() : "",
                        "GET", "", itemOverride, 1.0, maxSchemaDepth, defaultArrayCount, useSemantic);
                Object raw = schemaDispatcher.dispatch(info.getItemSchema(), ctx);
                Map<String, Object> entity = new LinkedHashMap<>();
                entity.put("id", i);  // id always first
                if (raw instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) raw;
                    cast.forEach((k, v) -> { if (!"id".equals(k)) entity.put(k, v); });
                } else if (raw != null) {
                    log.debug("EntityStore: item schema for '{}' produced non-Map ({}), wrapping in {{value: ...}}",
                            resourceName, raw.getClass().getSimpleName());
                    entity.put("value", raw);
                }
                dataset.put(i, entity);
            }
            specDatasets.put(resourceName, dataset);
            totalSeeded += count;
            log.debug("EntityStore: seeded {} entities for '{}'", count, resourceName);
        }

        // Resolve FK fields after all base datasets are populated
        resolveForeignKeys(specDatasets, graph.getRelations(), faker);

        // Resolve denormalized fields after FK resolution (needs FK values to be set first)
        if (graph.getDenormalized() != null && !graph.getDenormalized().isEmpty()) {
            resolveDenormalized(specDatasets, graph.getDenormalized());
        }

        datasets.put(specName, specDatasets);
        graphs.put(specName, graph);

        long elapsed = System.currentTimeMillis() - start;
        log.info("EntityStore seeded spec '{}': {} resources, {} entities total in {}ms",
                specName, specDatasets.size(), totalSeeded, elapsed);
        if (elapsed > 2000) {
            log.warn("EntityStore seeding for '{}' exceeded 2s budget ({}ms) — consider reducing dataset size",
                    specName, elapsed);
        }
    }

    public void remove(String specName) {
        datasets.remove(specName);
        graphs.remove(specName);
        log.debug("EntityStore: removed dataset for spec '{}'", specName);
    }

    public void rename(String oldName, String newName) {
        Map<String, LinkedHashMap<Long, Map<String, Object>>> data = datasets.remove(oldName);
        if (data != null) datasets.put(newName, data);
        ResourceGraph graph = graphs.remove(oldName);
        if (graph != null) graphs.put(newName, graph);
        log.debug("EntityStore: renamed dataset '{}' → '{}'", oldName, newName);
    }

    public boolean hasAnyDataset(String specName) {
        Map<String, LinkedHashMap<Long, Map<String, Object>>> spec = datasets.get(specName);
        return spec != null && !spec.isEmpty();
    }

    public boolean hasDataset(String specName, String resourceName) {
        Map<String, LinkedHashMap<Long, Map<String, Object>>> spec = datasets.get(specName);
        return spec != null && spec.containsKey(resourceName);
    }

    public Optional<Map<String, Object>> findById(String specName, String resourceName, long id) {
        Map<String, LinkedHashMap<Long, Map<String, Object>>> spec = datasets.get(specName);
        if (spec == null) return Optional.empty();
        LinkedHashMap<Long, Map<String, Object>> dataset = spec.get(resourceName);
        if (dataset == null) return Optional.empty();
        return Optional.ofNullable(dataset.get(id));
    }

    public List<Map<String, Object>> findAll(String specName, String resourceName) {
        Map<String, LinkedHashMap<Long, Map<String, Object>>> spec = datasets.get(specName);
        if (spec == null) return List.of();
        LinkedHashMap<Long, Map<String, Object>> dataset = spec.get(resourceName);
        if (dataset == null) return List.of();
        return new ArrayList<>(dataset.values());
    }

    public List<Map<String, Object>> findByParent(String specName, String resourceName,
                                                   String parentField, long parentId) {
        return findAll(specName, resourceName).stream()
                .filter(entity -> {
                    Object val = entity.get(parentField);
                    if (val == null) return false;
                    try {
                        return Long.parseLong(val.toString()) == parentId;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .collect(Collectors.toList());
    }

    public Optional<ResourceInfo> getResourceInfo(String specName, String resourceName) {
        ResourceGraph graph = graphs.get(specName);
        if (graph == null) return Optional.empty();
        return Optional.ofNullable(graph.getResources().get(resourceName));
    }

    public Optional<ResourceGraph> getGraph(String specName) {
        return Optional.ofNullable(graphs.get(specName));
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private int resolveCount(String resourceName, MockProfile profile, int totalSoFar) {
        int count = DEFAULT_COUNT;
        if (profile != null && profile.getDataset() != null
                && profile.getDataset().containsKey(resourceName)) {
            Integer override = profile.getDataset().get(resourceName);
            count = override != null ? Math.max(0, override) : DEFAULT_COUNT;
        }
        int remaining = MAX_TOTAL - totalSoFar;
        if (count > remaining) {
            log.warn("EntityStore: capping '{}' from {} to {} (total cap {})",
                    resourceName, count, remaining, MAX_TOTAL);
            count = remaining;
        }
        return count;
    }

    /**
     * Extracts the EndpointOverride for a resource's list GET endpoint from the profile,
     * normalizing field keys by stripping array wrapper prefixes.
     * e.g. "data[].nome" → "nome" so that item-level generation can find the override.
     */
    private MockProfile.EndpointOverride resolveItemOverride(MockProfile profile, String listPath) {
        if (profile == null || profile.getOverrides() == null || listPath == null) return null;
        Map<String, MockProfile.EndpointOverride> methodMap = profile.getOverrides().get(listPath);
        if (methodMap == null) return null;
        MockProfile.EndpointOverride override = methodMap.get("GET");
        if (override == null) return null;
        if (override.getFields() == null || override.getFields().isEmpty()) return override;

        // Normalize: strip "arrayField[]." prefix from keys
        MockProfile.EndpointOverride normalized = new MockProfile.EndpointOverride();
        normalized.setLatency(override.getLatency());
        normalized.setCount(override.getCount());
        normalized.setSeed(override.getSeed());
        normalized.setForceStatus(override.getForceStatus());
        normalized.setOptionalProbability(override.getOptionalProbability());
        normalized.setMaxSchemaDepth(override.getMaxSchemaDepth());

        Map<String, MockProfile.FieldConfig> normalizedFields = new LinkedHashMap<>();
        override.getFields().forEach((key, config) -> {
            // "data[].nome" → "nome", "nome" → "nome", "nested.field" → "nested.field"
            String stripped = key.replaceFirst("^[^.\\[]+\\[]\\.", "");
            normalizedFields.put(stripped, config);
        });
        normalized.setFields(normalizedFields);
        return normalized;
    }

    /** Copies denormalized field values from referenced target entities. */
    private void resolveDenormalized(Map<String, LinkedHashMap<Long, Map<String, Object>>> specDatasets,
                                     List<DenormalizedDefinition> denormalized) {
        for (DenormalizedDefinition def : denormalized) {
            LinkedHashMap<Long, Map<String, Object>> sourceDataset = specDatasets.get(def.getSourceResource());
            LinkedHashMap<Long, Map<String, Object>> targetDataset = specDatasets.get(def.getTargetResource());

            if (sourceDataset == null || targetDataset == null || targetDataset.isEmpty()) {
                log.debug("EntityStore: skipping denormalized {}.{} (missing dataset)",
                        def.getSourceResource(), def.getSourceField());
                continue;
            }

            int resolved = 0;
            for (Map<String, Object> entity : sourceDataset.values()) {
                Object fkValue = entity.get(def.getSourceKeyField());
                if (fkValue == null) continue;
                try {
                    long targetId = Long.parseLong(fkValue.toString());
                    Map<String, Object> targetEntity = targetDataset.get(targetId);
                    if (targetEntity != null) {
                        Object targetValue = targetEntity.get(def.getTargetField());
                        if (targetValue != null) {
                            entity.put(def.getSourceField(), targetValue);
                            resolved++;
                        }
                    }
                } catch (NumberFormatException e) {
                    // FK value not numeric, skip
                }
            }
            log.debug("EntityStore: resolved denormalized {}.{} ← {}.{} ({}/{})",
                    def.getSourceResource(), def.getSourceField(),
                    def.getTargetResource(), def.getTargetField(),
                    resolved, sourceDataset.size());
        }
    }

    private void resolveForeignKeys(Map<String, LinkedHashMap<Long, Map<String, Object>>> specDatasets,
                                    List<RelationDefinition> relations, Faker faker) {
        for (RelationDefinition rel : relations) {
            LinkedHashMap<Long, Map<String, Object>> sourceDataset = specDatasets.get(rel.getSourceResource());
            LinkedHashMap<Long, Map<String, Object>> targetDataset = specDatasets.get(rel.getTargetResource());

            if (sourceDataset == null || targetDataset == null || targetDataset.isEmpty()) {
                log.debug("EntityStore: skipping FK {}.{} → {}.{} (missing target dataset)",
                        rel.getSourceResource(), rel.getSourceField(),
                        rel.getTargetResource(), rel.getTargetField());
                continue;
            }

            List<Long> targetIds = new ArrayList<>(targetDataset.keySet());
            for (Map<String, Object> entity : sourceDataset.values()) {
                long randomTargetId = targetIds.get(faker.random().nextInt(targetIds.size()));
                entity.put(rel.getSourceField(), randomTargetId);
            }
            log.debug("EntityStore: resolved FK {}.{} → {} (target dataset size={})",
                    rel.getSourceResource(), rel.getSourceField(),
                    rel.getTargetResource(), targetIds.size());
        }
    }
}
