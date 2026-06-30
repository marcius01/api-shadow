package tech.skullprogrammer.admin;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.reactive.MultipartForm;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.skullprogrammer.config.MockProfileLoader;
import tech.skullprogrammer.engine.EntityStore;
import tech.skullprogrammer.engine.FakerSuggestionEngine;
import tech.skullprogrammer.engine.LocaleDetector;
import tech.skullprogrammer.engine.RouteRegistry;
import tech.skullprogrammer.engine.SchemaAnalyzer;
import tech.skullprogrammer.engine.SpecParser;
import tech.skullprogrammer.engine.SpecResourceAnalyzer;
import tech.skullprogrammer.model.DenormalizedDefinition;
import tech.skullprogrammer.model.RelationDefinition;
import tech.skullprogrammer.model.ResourceGraph;
import tech.skullprogrammer.model.FieldSchema;
import tech.skullprogrammer.model.MockProfile;
import tech.skullprogrammer.model.ParsedSpec;
import tech.skullprogrammer.model.SpecEntity;
import tech.skullprogrammer.model.SpecSchemaDTO;
import tech.skullprogrammer.engine.MockModeService;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Path("/admin")
@Produces(MediaType.APPLICATION_JSON)
public class AdminResource {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Inject
    SpecParser specParser;

    @Inject
    RouteRegistry routeRegistry;

    @Inject
    MockProfileLoader mockProfileLoader;

    @Inject
    SchemaAnalyzer schemaAnalyzer;

    @Inject
    FakerSuggestionEngine suggestionEngine;

    @Inject
    LocaleDetector localeDetector;

    @Inject
    SpecResourceAnalyzer specResourceAnalyzer;

    @Inject
    EntityStore entityStore;

    @Inject
    MockModeService mockModeService;

    @ConfigProperty(name = "mock.faker-suggestions.top-k", defaultValue = "3")
    int topK;

    @ConfigProperty(name = "mock.faker-suggestions.auto-generate-threshold", defaultValue = "0.50")
    double autoGenerateThreshold;

    @GET
    public Response dashboard() {
        return Response.seeOther(URI.create("/admin-ui/index.html")).build();
    }

    @GET
    @Path("/specs")
    @Transactional
    public Response listSpecs() {
        List<SpecEntity> specs = SpecEntity.findAllActive();
        List<Map<String, Object>> result = specs.stream()
                .map(s -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", s.name);
                    m.put("active", s.active);
                    m.put("profile", s.profileContent != null);
                    m.put("seeded", entityStore.hasAnyDataset(s.name));
                    m.put("semanticMode", s.semanticMode);
                    m.put("createdAt", s.createdAt != null ? s.createdAt.toString() : null);
                    m.put("updatedAt", s.updatedAt != null ? s.updatedAt.toString() : null);
                    return m;
                })
                .collect(Collectors.toList());
        return Response.ok(result).build();
    }

    @POST
    @Path("/specs/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    public Response uploadSpec(@MultipartForm SpecUploadForm form) throws IOException {
        if (form.name == null || form.name.isBlank()) {
            return Response.status(400).entity(Map.of("error", "name is required")).build();
        }
        if (form.file == null) {
            return Response.status(400).entity(Map.of("error", "file is required")).build();
        }

        String name = form.name.trim();

        // Check if an ACTIVE spec with this name already exists (inactive/orphan records are reused)
        SpecEntity existing = SpecEntity.findByName(name).orElse(null);
        if (existing != null && existing.active) {
            return Response.status(409)
                    .entity(Map.of("error", "spec '" + name + "' already exists — use reload to update it"))
                    .build();
        }
        // If an inactive (orphan) record exists, hard-delete it so we can insert fresh
        if (existing != null) {
            existing.delete();
            log.info("Removed orphan/inactive record for spec '{}' before re-upload", name);
        }

        String content = Files.readString(form.file.uploadedFile());

        ParsedSpec parsed;
        try {
            parsed = specParser.parseFromContent(content);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", "invalid spec: " + e.getMessage())).build();
        } catch (Exception e) {
            log.error("Unexpected error parsing uploaded spec '{}': {}", name, e.getMessage(), e);
            return Response.status(400).entity(Map.of("error", "spec parsing failed: " + e.getMessage())).build();
        }

        Locale locale = localeDetector.detect(content);
        String profileContent = null;
        MockProfile profile = null;
        if (form.profile != null) {
            try {
                profileContent = Files.readString(form.profile.uploadedFile());
                profile = mockProfileLoader.loadFromContent(profileContent);
                mockProfileLoader.registerByName(name, profile);
                if (profile.getLocale() != null) {
                    locale = localeDetector.fromCode(profile.getLocale());
                }
            } catch (Exception e) {
                return Response.status(400).entity(Map.of("error", "invalid profile: " + e.getMessage())).build();
            }
        }

        ResourceGraph graph;
        try {
            graph = specResourceAnalyzer.analyze(parsed, profile);
        } catch (Exception e) {
            log.error("Error analyzing resource graph for spec '{}': {}", name, e.getMessage(), e);
            return Response.status(500).entity(Map.of("error", "resource analysis failed: " + e.getMessage())).build();
        }

        // Auto-generate a starter profile if none was uploaded, so the user always has something to download/edit
        if (profileContent == null && !graph.getResources().isEmpty()) {
            MockProfile starter = buildStarterProfile(graph, locale, parsed);
            profileContent = mockProfileLoader.toYaml(starter);
            mockProfileLoader.registerByName(name, starter);
            log.info("Auto-generated starter profile for spec '{}' ({} resources, {} relations)",
                    name, graph.getResources().size(), graph.getRelations().size());
        }

        SpecEntity entity = new SpecEntity(name, content);
        entity.profileContent = profileContent;
        entity.semanticMode = form.semanticMode;
        entity.persist();

        routeRegistry.registerFromSpec(name, parsed, locale);
        log.info("Uploaded and registered spec '{}' — {} endpoint(s), locale: {}, profile: {}",
                name, parsed.getEndpoints().size(), locale.getLanguage(), profileContent != null ? "yes" : "no");

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("status", "created");
        result.put("name", name);
        result.put("endpoints", parsed.getEndpoints().size());
        result.put("profile", profileContent != null);
        return Response.created(URI.create("/admin/specs")).entity(result).build();
    }

    @POST
    @Path("/specs/{name}/reload")
    @Transactional
    public Response reloadSpec(@PathParam("name") String name) {
        SpecEntity entity = SpecEntity.findByName(name).orElse(null);
        if (entity == null) {
            return Response.status(404).entity(Map.of("error", "spec '" + name + "' not found")).build();
        }

        ParsedSpec parsed;
        try {
            parsed = specParser.parseFromContent(entity.content);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", "invalid spec: " + e.getMessage())).build();
        }

        Locale locale = localeDetector.detect(entity.content);
        MockProfile reloadProfile = null;
        if (entity.profileContent != null) {
            reloadProfile = mockProfileLoader.loadFromContent(entity.profileContent);
            mockProfileLoader.registerByName(name, reloadProfile);
            if (reloadProfile.getLocale() != null) {
                locale = localeDetector.fromCode(reloadProfile.getLocale());
            }
        } else {
            mockProfileLoader.removeByName(name);
        }

        specResourceAnalyzer.analyze(parsed, reloadProfile);
        entityStore.remove(name);
        routeRegistry.reload(name, parsed, locale);
        entity.updatedAt = LocalDateTime.now();
        log.info("Reloaded spec '{}' — locale: {}", name, locale.getLanguage());

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("status", "reloaded");
        result.put("name", name);
        result.put("endpoints", parsed.getEndpoints().size());
        result.put("profile", entity.profileContent != null);
        return Response.ok(result).build();
    }

    @POST
    @Path("/specs/{name}/set-mode")
    @Transactional
    public Response setSpecMode(@PathParam("name") String name, Map<String, Boolean> body) {
        SpecEntity entity = SpecEntity.findByName(name).orElse(null);
        if (entity == null) {
            return Response.status(404).entity(Map.of("error", "spec '" + name + "' not found")).build();
        }
        if (body == null || !body.containsKey("semantic")) {
            return Response.status(400).entity(Map.of("error", "body must contain {\"semantic\": true|false}")).build();
        }
        entity.semanticMode = body.get("semantic");
        entity.updatedAt = LocalDateTime.now();
        return Response.ok(Map.of("name", name, "semanticMode", entity.semanticMode,
                "modelReady", mockModeService.isModelReady())).build();
    }

    @POST
    @Path("/specs/{name}/reseed")
    @Transactional
    public Response reseedSpec(@PathParam("name") String name) {
        SpecEntity entity = SpecEntity.findByName(name).orElse(null);
        if (entity == null) {
            return Response.status(404).entity(Map.of("error", "spec '" + name + "' not found")).build();
        }

        ParsedSpec parsed;
        try {
            parsed = specParser.parseFromContent(entity.content);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", "invalid spec: " + e.getMessage())).build();
        }

        Locale locale = localeDetector.detect(entity.content);
        MockProfile profile = null;
        if (entity.profileContent != null) {
            profile = mockProfileLoader.loadFromContent(entity.profileContent);
            if (profile.getLocale() != null) {
                locale = localeDetector.fromCode(profile.getLocale());
            }
        }

        // Generation is always profile-based: fakers come from the profile, lorem for unconfigured fields.
        // The semantic model is NOT used during generation — run "fill-inferred" first to populate the profile.
        ResourceGraph graph = specResourceAnalyzer.analyze(parsed, profile, false);
        entityStore.remove(name);
        entityStore.seed(name, parsed, profile, graph, locale, false);
        log.info("Generated dataset for spec '{}' — {} relations, {} denormalized",
                name, graph.getRelations().size(), graph.getDenormalized().size());

        return Response.ok(Map.of(
                "status", "seeded",
                "name", name,
                "relations", graph.getRelations().size(),
                "denormalized", graph.getDenormalized().size()
        )).build();
    }

    @POST
    @Path("/specs/{name}/delete")
    @Transactional
    public Response deleteSpec(@PathParam("name") String name) {
        SpecEntity entity = SpecEntity.findByName(name).orElse(null);
        if (entity == null) {
            return Response.status(404).entity(Map.of("error", "spec '" + name + "' not found")).build();
        }

        entityStore.remove(name);
        routeRegistry.unregister(name);
        mockProfileLoader.removeByName(name);
        entity.delete();
        log.info("Deleted spec '{}'", name);

        return Response.ok(Map.of("status", "deleted", "name", name)).build();
    }

    @POST
    @Path("/specs/{name}/rename")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response renameSpec(@PathParam("name") String name, Map<String, String> body) {
        String newName = body == null ? null : body.get("newName");
        if (newName == null || newName.isBlank()) {
            return Response.status(400).entity(Map.of("error", "newName is required")).build();
        }
        newName = newName.trim();
        if (newName.equals(name)) {
            return Response.ok(Map.of("status", "unchanged", "name", name)).build();
        }
        if (SpecEntity.findByName(newName).isPresent()) {
            return Response.status(409).entity(Map.of("error", "spec '" + newName + "' already exists")).build();
        }

        SpecEntity entity = SpecEntity.findByName(name).orElse(null);
        if (entity == null) {
            return Response.status(404).entity(Map.of("error", "spec '" + name + "' not found")).build();
        }

        ParsedSpec parsed;
        try {
            parsed = specParser.parseFromContent(entity.content);
        } catch (Exception e) {
            return Response.status(400).entity(Map.of("error", "invalid spec: " + e.getMessage())).build();
        }

        Locale locale = localeDetector.detect(entity.content);
        MockProfile profile = null;
        if (entity.profileContent != null) {
            profile = mockProfileLoader.loadFromContent(entity.profileContent);
            if (profile.getLocale() != null) locale = localeDetector.fromCode(profile.getLocale());
        }

        // analyze() must run before registerFromSpec to set endpoint.resourceName / pathType
        specResourceAnalyzer.analyze(parsed, profile);

        // Deregister old routes, move in-memory state, register new routes
        routeRegistry.unregister(name);
        entityStore.rename(name, newName);
        mockProfileLoader.removeByName(name);
        if (profile != null) mockProfileLoader.registerByName(newName, profile);

        entity.name = newName;
        entity.updatedAt = LocalDateTime.now();

        routeRegistry.registerFromSpec(newName, parsed, locale);
        log.info("Renamed spec '{}' → '{}'", name, newName);

        return Response.ok(Map.of("status", "renamed", "oldName", name, "newName", newName)).build();
    }

    @POST
    @Path("/specs/upload-url")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response uploadSpecFromUrl(Map<String, String> body) {
        String name = body == null ? null : body.get("name");
        String url = body == null ? null : body.get("url");

        if (name == null || name.isBlank()) {
            return Response.status(400).entity(Map.of("error", "name is required")).build();
        }
        if (url == null || url.isBlank()) {
            return Response.status(400).entity(Map.of("error", "url is required")).build();
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Response.status(400).entity(Map.of("error", "url must start with http:// or https://")).build();
        }

        name = name.trim();
        SpecEntity existingUrl = SpecEntity.findByName(name).orElse(null);
        if (existingUrl != null && existingUrl.active) {
            return Response.status(409)
                    .entity(Map.of("error", "spec '" + name + "' already exists — use reload to update it"))
                    .build();
        }
        if (existingUrl != null) {
            existingUrl.delete();
            log.info("Removed orphan/inactive record for spec '{}' before re-upload", name);
        }

        // Fetch + resolve relative $ref → store resolved content so re-parses (schema, reload, startup) work correctly
        String content;
        ParsedSpec parsed;
        try {
            content = specParser.fetchAndResolveContent(url);
            parsed = specParser.parseFromContent(content);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", "invalid spec: " + e.getMessage())).build();
        } catch (IOException e) {
            return Response.status(400).entity(Map.of("error", "failed to fetch URL: " + e.getMessage())).build();
        } catch (Exception e) {
            log.error("Unexpected error parsing spec from URL {}: {}", url, e.getMessage(), e);
            return Response.status(400).entity(Map.of("error", "spec parsing failed: " + e.getMessage())).build();
        }

        Locale locale = localeDetector.detect(content);

        ResourceGraph graphUrl;
        try {
            graphUrl = specResourceAnalyzer.analyze(parsed, null);
        } catch (Exception e) {
            log.error("Error analyzing resource graph for spec '{}': {}", name, e.getMessage(), e);
            return Response.status(500).entity(Map.of("error", "resource analysis failed: " + e.getMessage())).build();
        }

        boolean semanticMode = body.containsKey("semanticMode") && Boolean.TRUE.equals(body.get("semanticMode"));
        SpecEntity entity = new SpecEntity(name, content);

        if (!graphUrl.getResources().isEmpty()) {
            MockProfile starter = buildStarterProfile(graphUrl, locale, parsed);
            entity.profileContent = mockProfileLoader.toYaml(starter);
            mockProfileLoader.registerByName(name, starter);
            log.info("Auto-generated starter profile for spec '{}' ({} resources, {} relations)",
                    name, graphUrl.getResources().size(), graphUrl.getRelations().size());
        }

        entity.semanticMode = semanticMode;
        entity.persist();

        int registeredEndpoints;
        try {
            routeRegistry.registerFromSpec(name, parsed, locale);
            registeredEndpoints = parsed.getEndpoints().size();
        } catch (Exception e) {
            log.error("Error registering routes for spec '{}': {}", name, e.getMessage(), e);
            return Response.status(500).entity(Map.of("error", "route registration failed: " + e.getMessage())).build();
        }
        log.info("Fetched and registered spec '{}' from {} — {} endpoint(s), locale: {}",
                name, url, registeredEndpoints, locale.getLanguage());

        Map<String, Object> result = new HashMap<>();
        result.put("status", "created");
        result.put("name", name);
        result.put("endpoints", registeredEndpoints);
        result.put("profile", entity.profileContent != null);
        return Response.created(URI.create("/admin/specs")).entity(result).build();
    }

    @POST
    @Path("/specs/{name}/profile")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    public Response updateProfile(@PathParam("name") String name, @MultipartForm ProfileUploadForm form) throws IOException {
        SpecEntity entity = SpecEntity.findByName(name).orElse(null);
        if (entity == null) {
            return Response.status(404).entity(Map.of("error", "spec '" + name + "' not found")).build();
        }
        if (form.profile == null) {
            return Response.status(400).entity(Map.of("error", "profile file is required")).build();
        }

        String profileContent;
        try {
            profileContent = Files.readString(form.profile.uploadedFile());
            MockProfile profile = mockProfileLoader.loadFromContent(profileContent);
            mockProfileLoader.registerByName(name, profile);
        } catch (Exception e) {
            return Response.status(400).entity(Map.of("error", "invalid profile: " + e.getMessage())).build();
        }

        entity.profileContent = profileContent;
        entity.updatedAt = LocalDateTime.now();
        log.info("Updated profile for spec '{}'", name);

        return Response.ok(Map.of("status", "updated", "name", name, "profile", true)).build();
    }

    @POST
    @Path("/specs/{name}/profile/delete")
    @Transactional
    public Response deleteProfile(@PathParam("name") String name) {
        SpecEntity entity = SpecEntity.findByName(name).orElse(null);
        if (entity == null) {
            return Response.status(404).entity(Map.of("error", "spec '" + name + "' not found")).build();
        }

        mockProfileLoader.removeByName(name);
        entity.profileContent = null;
        entity.updatedAt = LocalDateTime.now();
        log.info("Removed profile for spec '{}'", name);

        return Response.ok(Map.of("status", "deleted", "name", name, "profile", false)).build();
    }

    /**
     * Rebuilds only the inferred field-level fakers (overrides.*.*.fields) using the current engine,
     * without touching locale, dataset, relations, fixtures or any other per-endpoint settings the user configured.
     */
    @POST
    @Path("/specs/{name}/profile/rebuild-inferred")
    @Transactional
    public Response rebuildInferred(@PathParam("name") String name) {
        SpecEntity entity = SpecEntity.findByName(name).orElse(null);
        if (entity == null) {
            return Response.status(404).entity(Map.of("error", "spec '" + name + "' not found")).build();
        }

        ParsedSpec parsed;
        try {
            parsed = specParser.parseFromContent(entity.content);
        } catch (Exception e) {
            return Response.status(400).entity(Map.of("error", "invalid spec: " + e.getMessage())).build();
        }

        MockProfile existing = entity.profileContent != null
                ? mockProfileLoader.loadFromContent(entity.profileContent)
                : new MockProfile();

        Locale locale = localeDetector.detect(entity.content);
        if (existing.getLocale() != null) locale = localeDetector.fromCode(existing.getLocale());

        ResourceGraph graph = specResourceAnalyzer.analyze(parsed, existing);
        MockProfile fresh = buildStarterProfile(graph, locale, parsed);

        // Wipe only the fields section inside each endpoint override, keep everything else
        if (existing.getOverrides() == null) existing.setOverrides(new LinkedHashMap<>());
        if (fresh.getOverrides() != null) {
            fresh.getOverrides().forEach((path, methods) -> {
                existing.getOverrides().computeIfAbsent(path, k -> new LinkedHashMap<>());
                methods.forEach((method, freshOverride) -> {
                    MockProfile.EndpointOverride existingOverride = existing.getOverrides().get(path).get(method);
                    if (existingOverride == null) {
                        existing.getOverrides().get(path).put(method, freshOverride);
                    } else {
                        // Replace only fields; keep latency, count, seed, forceStatus, etc.
                        existingOverride.setFields(freshOverride.getFields());
                    }
                });
            });
        }
        // Also fill dataset/relations/denormalized gaps from fresh (without overwriting user values)
        if (fresh.getDataset() != null) {
            if (existing.getDataset() == null) existing.setDataset(new LinkedHashMap<>());
            fresh.getDataset().forEach(existing.getDataset()::putIfAbsent);
        }
        if (fresh.getRelations() != null) {
            if (existing.getRelations() == null) existing.setRelations(new LinkedHashMap<>());
            fresh.getRelations().forEach(existing.getRelations()::putIfAbsent);
        }
        if (fresh.getDenormalized() != null) {
            if (existing.getDenormalized() == null) existing.setDenormalized(new LinkedHashMap<>());
            fresh.getDenormalized().forEach(existing.getDenormalized()::putIfAbsent);
        }

        entity.profileContent = mockProfileLoader.toYaml(existing);
        entity.updatedAt = LocalDateTime.now();
        mockProfileLoader.registerByName(name, existing);
        log.info("Rebuilt inferred field fakers for spec '{}'", name);

        return Response.ok(Map.of("status", "rebuilt", "name", name)).build();
    }

    /**
     * Fills only the gaps in the profile: adds inferred faker assignments for fields that have
     * no configuration yet, without ever touching fields already set (by the user or by a
     * previous inference run). Safe to call at any time.
     */
    @POST
    @Path("/specs/{name}/profile/fill-inferred")
    @Transactional
    public Response fillInferred(@PathParam("name") String name) {
        SpecEntity entity = SpecEntity.findByName(name).orElse(null);
        if (entity == null) {
            return Response.status(404).entity(Map.of("error", "spec '" + name + "' not found")).build();
        }

        ParsedSpec parsed;
        try {
            parsed = specParser.parseFromContent(entity.content);
        } catch (Exception e) {
            return Response.status(400).entity(Map.of("error", "invalid spec: " + e.getMessage())).build();
        }

        Locale locale = localeDetector.detect(entity.content);
        MockProfile existing = entity.profileContent != null
                ? mockProfileLoader.loadFromContent(entity.profileContent)
                : new MockProfile();
        if (existing.getLocale() != null) locale = localeDetector.fromCode(existing.getLocale());

        boolean effectiveSemantic = entity.semanticMode && mockModeService.isModelReady();
        ResourceGraph graph = specResourceAnalyzer.analyze(parsed, existing, effectiveSemantic);
        MockProfile starter = buildStarterProfile(graph, locale, parsed);

        if (existing.getLocale() == null) existing.setLocale(starter.getLocale());
        mergeStarterIntoProfile(existing, starter);

        entity.profileContent = mockProfileLoader.toYaml(existing);
        entity.updatedAt = LocalDateTime.now();
        mockProfileLoader.registerByName(name, existing);

        long fieldsAdded = starter.getOverrides() == null ? 0 :
                starter.getOverrides().values().stream()
                        .flatMap(m -> m.values().stream())
                        .mapToLong(o -> o.getFields() == null ? 0 : o.getFields().size())
                        .sum();

        log.info("Filled inferred profile for spec '{}' — semantic={}, fields scanned={}",
                name, effectiveSemantic, fieldsAdded);

        return Response.ok(Map.of(
                "status", "filled",
                "name", name,
                "semantic", entity.semanticMode,
                "effectiveSemantic", effectiveSemantic,
                "modelReady", mockModeService.isModelReady()
        )).build();
    }

    @GET
    @Path("/specs/{name}/profile/download")
    @Produces("text/yaml")
    @Transactional
    public Response downloadProfile(@PathParam("name") String name) {
        SpecEntity entity = SpecEntity.findByName(name).orElse(null);
        if (entity == null) {
            return Response.status(404).entity("spec not found").build();
        }

        // Compute a fully-materialized profile: all inferred faker assignments visible in the UI,
        // merged with any explicit user overrides stored in the DB.
        ParsedSpec parsed;
        try {
            parsed = specParser.parseFromContent(entity.content);
        } catch (Exception e) {
            // Fallback: serve raw stored content if parsing fails
            if (entity.profileContent == null) return Response.status(404).entity("no profile").build();
            return Response.ok(entity.profileContent)
                    .header("Content-Disposition", "attachment; filename=\"" + name + "-profile.yaml\"").build();
        }

        Locale locale = localeDetector.detect(entity.content);
        MockProfile existing = entity.profileContent != null
                ? mockProfileLoader.loadFromContent(entity.profileContent)
                : new MockProfile();
        if (existing.getLocale() != null) locale = localeDetector.fromCode(existing.getLocale());

        ResourceGraph graph = specResourceAnalyzer.analyze(parsed, existing);
        MockProfile materialized = buildStarterProfile(graph, locale, parsed);

        // Merge: existing explicit user overrides win; gaps are filled with auto-detected values
        MockProfile result = new MockProfile();
        result.setLocale(existing.getLocale() != null ? existing.getLocale() : materialized.getLocale());
        result.setDataset(new LinkedHashMap<>(materialized.getDataset() != null ? materialized.getDataset() : Map.of()));
        if (existing.getDataset() != null) existing.getDataset().forEach((k, v) -> result.getDataset().put(k, v));
        result.setRelations(new LinkedHashMap<>(materialized.getRelations() != null ? materialized.getRelations() : Map.of()));
        if (existing.getRelations() != null) existing.getRelations().forEach((k, v) -> result.getRelations().put(k, v));
        result.setDenormalized(new LinkedHashMap<>(materialized.getDenormalized() != null ? materialized.getDenormalized() : Map.of()));
        if (existing.getDenormalized() != null) existing.getDenormalized().forEach((k, v) -> result.getDenormalized().put(k, v));
        result.setFixtures(existing.getFixtures() != null ? existing.getFixtures() : new HashMap<>());

        // Overrides: start from materialized (all auto-detected), then overwrite with explicit user values
        Map<String, Map<String, MockProfile.EndpointOverride>> mergedOverrides = new LinkedHashMap<>();
        if (materialized.getOverrides() != null) materialized.getOverrides().forEach((path, methods) -> {
            mergedOverrides.put(path, new LinkedHashMap<>(methods));
        });
        if (existing.getOverrides() != null) existing.getOverrides().forEach((path, methods) -> {
            mergedOverrides.computeIfAbsent(path, k -> new LinkedHashMap<>());
            methods.forEach((method, userOverride) -> {
                MockProfile.EndpointOverride auto = mergedOverrides.get(path).get(method);
                if (auto == null) {
                    mergedOverrides.get(path).put(method, userOverride);
                } else {
                    // User's latency/count/seed/forceStatus settings take precedence
                    if (userOverride.getLatency() != null) auto.setLatency(userOverride.getLatency());
                    if (userOverride.getCount() != null) auto.setCount(userOverride.getCount());
                    if (userOverride.getSeed() != null) auto.setSeed(userOverride.getSeed());
                    if (userOverride.getForceStatus() != null) auto.setForceStatus(userOverride.getForceStatus());
                    if (userOverride.getOptionalProbability() != null) auto.setOptionalProbability(userOverride.getOptionalProbability());
                    if (userOverride.getMaxSchemaDepth() != null) auto.setMaxSchemaDepth(userOverride.getMaxSchemaDepth());
                    // Field-level: user's explicit values overwrite auto-detected ones
                    if (userOverride.getFields() != null) {
                        if (auto.getFields() == null) auto.setFields(new LinkedHashMap<>());
                        auto.getFields().putAll(userOverride.getFields());
                    }
                }
            });
        });
        result.setOverrides(mergedOverrides);

        String yaml = mockProfileLoader.toYaml(result);
        return Response.ok(yaml)
                .header("Content-Disposition", "attachment; filename=\"" + name + "-profile.yaml\"")
                .build();
    }

    @GET
    @Path("/specs/{name}/resources")
    @Transactional
    public Response getResources(@PathParam("name") String name) {
        SpecEntity entity = SpecEntity.findByName(name).orElse(null);
        if (entity == null) {
            return Response.status(404).entity(Map.of("error", "spec '" + name + "' not found")).build();
        }

        MockProfile profile = null;
        if (entity.profileContent != null) {
            try { profile = mockProfileLoader.loadFromContent(entity.profileContent); } catch (Exception ignored) {}
        }
        final MockProfile fp = profile;

        // Try EntityStore graph first (already analyzed), fallback to re-analyze
        var graph = entityStore.getGraph(name).orElseGet(() -> {
            try {
                ParsedSpec parsed = specParser.parseFromContent(entity.content);
                return specResourceAnalyzer.analyze(parsed, fp, false);
            } catch (Exception e) {
                return null;
            }
        });

        if (graph == null || graph.getResources().isEmpty()) {
            return Response.ok(Map.of("resources", List.of(), "relations", List.of())).build();
        }

        int defaultCount = 20;
        Map<String, Integer> datasetOverrides = (fp != null && fp.getDataset() != null) ? fp.getDataset() : Map.of();

        List<Map<String, Object>> resources = graph.getResources().entrySet().stream()
                .map(e -> {
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("name", e.getKey());
                    r.put("listPath", e.getValue().getListPath());
                    r.put("count", datasetOverrides.getOrDefault(e.getKey(), defaultCount));
                    return r;
                })
                .collect(Collectors.toList());

        List<Map<String, String>> relations = graph.getRelations().stream()
                .map(rel -> {
                    Map<String, String> r = new java.util.LinkedHashMap<>();
                    r.put("source", rel.getSourceResource() + "." + rel.getSourceField());
                    r.put("target", rel.getTargetResource() + "." + rel.getTargetField());
                    return r;
                })
                .collect(Collectors.toList());

        List<Map<String, String>> denormalized = graph.getDenormalized().stream()
                .map(def -> {
                    Map<String, String> d = new java.util.LinkedHashMap<>();
                    d.put("source", def.getSourceResource() + "." + def.getSourceField());
                    d.put("keyField", def.getSourceKeyField());
                    d.put("target", def.getTargetResource() + "." + def.getTargetField());
                    return d;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("resources", resources);
        result.put("relations", relations);
        result.put("denormalized", denormalized);
        return Response.ok(result).build();
    }

    @GET
    @Path("/specs/{name}/schema")
    @Transactional
    public Response getSpecSchema(@PathParam("name") String name) {
        SpecEntity entity = SpecEntity.findByName(name).orElse(null);
        if (entity == null) {
            return Response.status(404).entity(Map.of("error", "spec '" + name + "' not found")).build();
        }

        ParsedSpec parsed;
        try {
            parsed = specParser.parseFromContent(entity.content);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", "invalid spec: " + e.getMessage())).build();
        }

        List<SpecSchemaDTO.EndpointSchemaDTO> endpointDTOs = parsed.getEndpoints().stream()
                .map(ep -> {
                    var schema = ep.getResponses().get(String.valueOf(ep.getDefaultStatusCode()));
                    boolean hasSchema = schema != null;
                    List<FieldSchema> rawFields = hasSchema ? schemaAnalyzer.flatten(schema) : List.of();
                    List<FieldSchema> fields = rawFields.stream()
                            .map(f -> FieldSchema.builder()
                                    .name(f.getName())
                                    .type(f.getType())
                                    .format(f.getFormat())
                                    .required(f.isRequired())
                                    .suggestions(suggestionEngine.isReady()
                                            ? suggestionEngine.suggest(f.getName(), f.getType(), f.getFormat(), topK)
                                            : List.of())
                                    .build())
                            .toList();
                    return SpecSchemaDTO.EndpointSchemaDTO.builder()
                            .path(ep.getPath())
                            .method(ep.getMethod())
                            .defaultStatusCode(ep.getDefaultStatusCode())
                            .hasSchema(hasSchema)
                            .fields(fields)
                            .build();
                })
                .collect(Collectors.toList());

        return Response.ok(SpecSchemaDTO.builder()
                .specName(name)
                .endpoints(endpointDTOs)
                .build()).build();
    }

    @GET
    @Path("/specs/{name}/profile/json")
    @Transactional
    public Response getProfileJson(@PathParam("name") String name) {
        SpecEntity entity = SpecEntity.findByName(name).orElse(null);
        if (entity == null) {
            return Response.status(404).entity(Map.of("error", "spec '" + name + "' not found")).build();
        }

        if (entity.profileContent == null) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("overrides", new HashMap<>());
            empty.put("fixtures", new HashMap<>());
            return Response.ok(empty).build();
        }

        try {
            MockProfile profile = mockProfileLoader.loadFromContent(entity.profileContent);
            return Response.ok(profile).build();
        } catch (Exception e) {
            return Response.status(500).entity(Map.of("error", "failed to parse profile: " + e.getMessage())).build();
        }
    }

    @PUT
    @Path("/specs/{name}/profile/json")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response putProfileJson(@PathParam("name") String name, MockProfile profileBody) {
        SpecEntity entity = SpecEntity.findByName(name).orElse(null);
        if (entity == null) {
            return Response.status(404).entity(Map.of("error", "spec '" + name + "' not found")).build();
        }

        if (profileBody == null) {
            return Response.status(400).entity(Map.of("error", "request body is required")).build();
        }

        String yamlContent;
        try {
            yamlContent = YAML_MAPPER.writeValueAsString(profileBody);
        } catch (Exception e) {
            return Response.status(400).entity(Map.of("error", "failed to serialize profile: " + e.getMessage())).build();
        }

        mockProfileLoader.registerByName(name, profileBody);
        entity.profileContent = yamlContent;
        entity.updatedAt = LocalDateTime.now();
        log.info("Updated profile for spec '{}' via JSON API", name);

        return Response.ok(Map.of("status", "saved", "name", name, "profile", true)).build();
    }

    @GET
    @Path("/endpoints")
    public Response listEndpoints() {
        Map<String, Integer> summary = routeRegistry.getActiveRoutes().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()));
        return Response.ok(summary).build();
    }

    @GET
    @Path("/api/status")
    public Response status() {
        int totalEndpoints = routeRegistry.getActiveRoutes().values().stream()
                .mapToInt(List::size).sum();
        return Response.ok(Map.of(
                "status", "ok",
                "specs", routeRegistry.getActiveRoutes().size(),
                "endpoints", totalEndpoints)).build();
    }

    @GET
    @Path("/api/mode")
    public Response getMode() {
        return Response.ok(Map.of(
                "semantic", mockModeService.isSemanticEnabled(),
                "modelReady", mockModeService.isModelReady()
        )).build();
    }

    /**
     * Generates a starter MockProfile from detected resource graph, locale, and per-endpoint field schemas.
     * Used to pre-populate profileContent so the user always has a useful YAML to download/edit.
     */
    private MockProfile buildStarterProfile(ResourceGraph graph, Locale locale, ParsedSpec parsed) {
        MockProfile starter = new MockProfile();
        starter.setLocale(locale.getLanguage());

        Map<String, Integer> dataset = new LinkedHashMap<>();
        for (String resourceName : graph.getResources().keySet()) {
            dataset.put(resourceName, 20);
        }
        starter.setDataset(dataset);

        Map<String, String> relations = new LinkedHashMap<>();
        for (RelationDefinition rel : graph.getRelations()) {
            relations.put(rel.getSourceResource() + "." + rel.getSourceField(),
                          rel.getTargetResource() + "." + rel.getTargetField());
        }
        starter.setRelations(relations);

        Map<String, String> denormalized = new LinkedHashMap<>();
        for (DenormalizedDefinition def : graph.getDenormalized()) {
            denormalized.put(def.getSourceResource() + "." + def.getSourceField(),
                    def.getSourceKeyField() + "->" + def.getTargetResource() + "." + def.getTargetField());
        }
        starter.setDenormalized(denormalized);

        // Per-endpoint field overrides: capture format-based and suggestion-based faker assignments
        Map<String, Map<String, MockProfile.EndpointOverride>> overrides = new LinkedHashMap<>();
        for (var ep : parsed.getEndpoints()) {
            var schema = ep.getResponses().get(String.valueOf(ep.getDefaultStatusCode()));
            if (schema == null) continue;
            List<FieldSchema> fields = schemaAnalyzer.flatten(schema);
            if (fields.isEmpty()) continue;

            Map<String, MockProfile.FieldConfig> fieldConfigs = new LinkedHashMap<>();
            for (FieldSchema field : fields) {
                MockProfile.FieldConfig cfg = resolveFieldConfig(field);
                if (cfg != null) fieldConfigs.put(field.getName(), cfg);
            }
            if (fieldConfigs.isEmpty()) continue;

            MockProfile.EndpointOverride override = new MockProfile.EndpointOverride();
            override.setFields(fieldConfigs);
            overrides.computeIfAbsent(ep.getPath(), k -> new LinkedHashMap<>())
                     .put(ep.getMethod(), override);
        }
        starter.setOverrides(overrides);

        return starter;
    }

    /**
     * Returns a FieldConfig for the given field. Format-based assignments are always included;
     * suggestion-based assignments use top-1 without threshold filtering (profile shows all auto-detected values).
     */
    private MockProfile.FieldConfig resolveFieldConfig(FieldSchema field) {
        // Format-based → known faker expressions
        String fakerExpr = formatToFakerExpr(field.getFormat());
        if (fakerExpr != null) {
            MockProfile.FieldConfig cfg = new MockProfile.FieldConfig();
            cfg.setFaker(fakerExpr);
            return cfg;
        }
        // Suggestion-based: same threshold used by SchemaDispatcher so the profile reflects what is actually generated
        if ("string".equals(field.getType()) && suggestionEngine.isReady() && !field.getName().isBlank()) {
            var suggestions = suggestionEngine.suggest(field.getName(), field.getType(), field.getFormat(), 1);
            if (!suggestions.isEmpty() && suggestions.get(0).getScore() >= autoGenerateThreshold) {
                MockProfile.FieldConfig cfg = new MockProfile.FieldConfig();
                cfg.setFaker(suggestions.get(0).getExpression());
                return cfg;
            }
        }
        return null;
    }

    /**
     * Merges auto-detected overrides into an existing profile without overwriting fields
     * the user has already configured explicitly.
     */
    private void mergeStarterIntoProfile(MockProfile existing, MockProfile starter) {
        // Merge dataset: add resources missing from existing
        if (starter.getDataset() != null) {
            if (existing.getDataset() == null) existing.setDataset(new LinkedHashMap<>());
            starter.getDataset().forEach(existing.getDataset()::putIfAbsent);
        }
        // Merge relations: add auto-detected relations missing from existing
        if (starter.getRelations() != null) {
            if (existing.getRelations() == null) existing.setRelations(new LinkedHashMap<>());
            starter.getRelations().forEach(existing.getRelations()::putIfAbsent);
        }
        // Merge denormalized: add auto-detected rules missing from existing
        if (starter.getDenormalized() != null) {
            if (existing.getDenormalized() == null) existing.setDenormalized(new LinkedHashMap<>());
            starter.getDenormalized().forEach(existing.getDenormalized()::putIfAbsent);
        }
        // Merge overrides: for each endpoint, for each field — only fill in if not already set
        if (starter.getOverrides() != null) {
            if (existing.getOverrides() == null) existing.setOverrides(new LinkedHashMap<>());
            starter.getOverrides().forEach((path, methods) -> {
                existing.getOverrides().computeIfAbsent(path, k -> new LinkedHashMap<>());
                methods.forEach((method, starterOverride) -> {
                    MockProfile.EndpointOverride existingOverride =
                            existing.getOverrides().get(path).get(method);
                    if (existingOverride == null) {
                        existing.getOverrides().get(path).put(method, starterOverride);
                    } else {
                        // Merge fields: keep existing values, add missing ones from auto-detection
                        if (starterOverride.getFields() != null) {
                            if (existingOverride.getFields() == null) existingOverride.setFields(new LinkedHashMap<>());
                            starterOverride.getFields().forEach(existingOverride.getFields()::putIfAbsent);
                        }
                    }
                });
            });
        }
    }

    private static String formatToFakerExpr(String format) {
        if (format == null) return null;
        return switch (format) {
            case "email" -> "Internet.emailAddress";
            case "password" -> "Internet.password";
            case "uri", "url" -> "Internet.url";
            default -> null;
        };
    }

    @POST
    @Path("/api/mode")
    public Response setMode(Map<String, Boolean> body) {
        if (body == null || !body.containsKey("semantic")) {
            return Response.status(400).entity(Map.of("error", "body must contain {\"semantic\": true|false}")).build();
        }
        mockModeService.setSemanticEnabled(body.get("semantic"));
        return Response.ok(Map.of(
                "semantic", mockModeService.isSemanticEnabled(),
                "modelReady", mockModeService.isModelReady()
        )).build();
    }
}
