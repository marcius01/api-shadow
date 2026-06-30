package tech.skullprogrammer.engine;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import tech.skullprogrammer.config.MockProfileLoader;
import tech.skullprogrammer.model.MockEndpoint;
import tech.skullprogrammer.model.MockParameter;
import tech.skullprogrammer.model.MockProfile;
import tech.skullprogrammer.model.PaginationShape;
import tech.skullprogrammer.model.ParsedSpec;
import tech.skullprogrammer.model.RelationDefinition;
import tech.skullprogrammer.model.ResourceGraph;
import tech.skullprogrammer.model.ResourceInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class RouteRegistry {

    static final String FORCE_STATUS_HEADER = "X-Mock-Force-Status";

    @Inject
    Router router;

    @Inject
    DataGenerator dataGenerator;

    @Inject
    MockProfileLoader mockProfileLoader;

    @Inject
    EntityStore entityStore;

    private final Map<String, List<Route>> activeRoutes = new HashMap<>();

    public void registerFromSpec(String specName, ParsedSpec spec, Locale locale) {
        List<Route> routes = new ArrayList<>();
        for (MockEndpoint endpoint : spec.getEndpoints()) {
            String vertxPath = "/api/" + specName + toVertxPath(endpoint.getPath());
            HttpMethod httpMethod = HttpMethod.valueOf(endpoint.getMethod());

            Route route;
            try {
                route = router.route(httpMethod, vertxPath);
            } catch (IllegalArgumentException e) {
                log.warn("Skipping route {} {} — Vert.x rejected path ({}): {}",
                        endpoint.getMethod(), vertxPath, e.getMessage(), endpoint.getPath());
                continue;
            }
            route.blockingHandler(ctx -> {
                try {
                    // T029: validate required query parameters (FR-023)
                    List<String> missing = endpoint.getParameters().stream()
                            .filter(p -> "query".equals(p.getIn()) && p.isRequired())
                            .filter(p -> {
                                String v = ctx.request().getParam(p.getName());
                                return v == null || v.isBlank();
                            })
                            .map(MockParameter::getName)
                            .collect(Collectors.toList());

                    if (!missing.isEmpty()) {
                        ctx.response()
                                .putHeader("Content-Type", "application/json")
                                .setStatusCode(400)
                                .end("{\"error\":\"missing required query parameter(s): " + String.join(", ", missing) + "\"}");
                        return;
                    }

                    // T021: get profile for this spec
                    MockProfile profile = mockProfileLoader.getByName(specName);

                    // T016/T023: resolve target status code — header > profile forceStatus > default
                    int targetCode = resolveTargetCode(ctx, endpoint, profile);
                    boolean wasForced = targetCode != endpoint.getDefaultStatusCode();

                    // T017: resolve schema — exact match → "default" fallback → null
                    io.swagger.v3.oas.models.media.Schema<?> schema =
                            endpoint.getResponses().get(String.valueOf(targetCode));
                    if (schema == null) {
                        schema = endpoint.getResponses().get("default");
                    }

                    // T017: unknown forced status with no schema → informative error body
                    if (schema == null && wasForced) {
                        ctx.response()
                                .putHeader("Content-Type", "application/json")
                                .setStatusCode(targetCode)
                                .end("{\"error\":\"status " + targetCode + " not defined in spec\"}");
                        return;
                    }

                    // Entity-aware routing: intercept GET/write operations for dataset-backed resources
                    String resourceName = endpoint.getResourceName();
                    MockEndpoint.PathType pathType = endpoint.getPathType();
                    String reqMethod = endpoint.getMethod().toUpperCase();

                    if (resourceName != null && pathType != null
                            && pathType != MockEndpoint.PathType.OTHER
                            && !hasFixture(profile, endpoint.getPath(), reqMethod)
                            && entityStore.hasDataset(specName, resourceName)) {

                        if ("GET".equals(reqMethod)) {
                            handleEntityGet(ctx, specName, resourceName, pathType, targetCode);
                        } else {
                            handleEntityWrite(ctx, specName, resourceName, reqMethod, targetCode);
                        }
                        return;
                    }

                    // T015/T018: generate body and send response (fallback path)
                    Object data = dataGenerator.generate(schema, specName, endpoint.getPath(), endpoint.getMethod(), profile, locale);
                    if (data == null) {
                        ctx.response().setStatusCode(targetCode).end();
                    } else {
                        ctx.response()
                                .putHeader("Content-Type", "application/json")
                                .setStatusCode(targetCode)
                                .end(Json.encode(data));
                    }

                } catch (Exception e) {
                    log.error("Error handling request for {}", vertxPath, e);
                    ctx.response()
                            .putHeader("Content-Type", "application/json")
                            .setStatusCode(500)
                            .end("{\"error\":\"internal server error\"}");
                }
            });

            routes.add(route);
            log.info("Registered: {} {}", endpoint.getMethod(), vertxPath);
        }
        activeRoutes.put(specName, routes);
    }

    public void unregister(String specName) {
        List<Route> routes = activeRoutes.remove(specName);
        if (routes != null) {
            routes.forEach(Route::remove);
            log.info("Unregistered spec '{}'", specName);
        }
    }

    public void reload(String specName, ParsedSpec spec, Locale locale) {
        unregister(specName);
        registerFromSpec(specName, spec, locale);
    }

    public Map<String, List<Route>> getActiveRoutes() {
        return Collections.unmodifiableMap(activeRoutes);
    }

    // T016/T023: header wins > profile forceStatus > spec default
    private int resolveTargetCode(RoutingContext ctx, MockEndpoint endpoint, MockProfile profile) {
        String forceHeader = ctx.request().getHeader(FORCE_STATUS_HEADER);
        if (forceHeader != null) {
            try {
                return Integer.parseInt(forceHeader.trim());
            } catch (NumberFormatException e) {
                log.warn("Invalid {} header value: '{}'", FORCE_STATUS_HEADER, forceHeader);
            }
        }
        if (profile != null && profile.getOverrides() != null) {
            Map<String, MockProfile.EndpointOverride> methodMap =
                    profile.getOverrides().get(endpoint.getPath());
            if (methodMap != null) {
                MockProfile.EndpointOverride override = methodMap.get(endpoint.getMethod().toUpperCase());
                if (override != null && override.getForceStatus() != null && override.getForceStatus() > 0) {
                    return override.getForceStatus();
                }
            }
        }
        return endpoint.getDefaultStatusCode();
    }

    private String toVertxPath(String openApiPath) {
        return openApiPath.replaceAll("\\{([^}]+)\\}", ":$1");
    }

    // ── Entity routing helpers ─────────────────────────────────────────────────

    private void handleEntityGet(RoutingContext ctx, String specName, String resourceName,
                                  MockEndpoint.PathType pathType, int statusCode) {
        switch (pathType) {
            case LIST:
                handleListGet(ctx, specName, resourceName, statusCode);
                break;
            case SINGLE:
            case SUB_RESOURCE_SINGLE:
                handleSingleGet(ctx, specName, resourceName, statusCode, ctx);
                break;
            case SUB_RESOURCE_LIST:
                handleSubResourceListGet(ctx, specName, resourceName, statusCode);
                break;
            default:
                ctx.response().putHeader("Content-Type", "application/json")
                        .setStatusCode(statusCode).end("[]");
        }
    }

    private void handleListGet(RoutingContext ctx, String specName, String resourceName, int statusCode) {
        List<Map<String, Object>> all = entityStore.findAll(specName, resourceName);
        Optional<ResourceInfo> infoOpt = entityStore.getResourceInfo(specName, resourceName);

        // Pagination params
        int page = parseIntParam(ctx, "page", "pageNumber", "pageIndex", 0);
        int size = parseIntParam(ctx, "size", "pageSize", "limit", 20);
        if (size <= 0) size = 20;

        int total = all.size();
        int fromIdx = Math.min(page * size, total);
        int toIdx   = Math.min(fromIdx + size, total);
        List<Map<String, Object>> slice = all.subList(fromIdx, toIdx);

        PaginationShape shape = infoOpt.map(ResourceInfo::getPaginationShape).orElse(null);
        Object responseBody;
        if (shape != null && (shape.getArrayField() != null || shape.getTotalElementsField() != null)) {
            int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 1;
            Map<String, Object> wrapper = new LinkedHashMap<>();
            if (shape.getArrayField() != null)       wrapper.put(shape.getArrayField(), slice);
            if (shape.getTotalElementsField() != null) wrapper.put(shape.getTotalElementsField(), total);
            if (shape.getTotalPagesField() != null)    wrapper.put(shape.getTotalPagesField(), totalPages);
            if (shape.getCurrentPageField() != null)   wrapper.put(shape.getCurrentPageField(), page);
            if (shape.getPageSizeField() != null)      wrapper.put(shape.getPageSizeField(), size);
            responseBody = wrapper;
        } else {
            responseBody = slice;
        }

        ctx.response().putHeader("Content-Type", "application/json")
                .setStatusCode(statusCode).end(Json.encode(responseBody));
    }

    private void handleSingleGet(RoutingContext ctx, String specName, String resourceName,
                                  int statusCode, RoutingContext routingContext) {
        // Find the first non-null path param that parses as a long
        String rawId = null;
        for (Map.Entry<String, String> entry : ctx.pathParams().entrySet()) {
            try {
                Long.parseLong(entry.getValue());
                rawId = entry.getValue();
            } catch (NumberFormatException ignored) { }
        }
        if (rawId == null) {
            ctx.response().putHeader("Content-Type", "application/json")
                    .setStatusCode(400).end("{\"error\":\"missing numeric id in path\"}");
            return;
        }
        long id = Long.parseLong(rawId);
        Optional<Map<String, Object>> entity = entityStore.findById(specName, resourceName, id);
        if (entity.isPresent()) {
            ctx.response().putHeader("Content-Type", "application/json")
                    .setStatusCode(statusCode).end(Json.encode(entity.get()));
        } else {
            ctx.response().putHeader("Content-Type", "application/json")
                    .setStatusCode(404).end("{\"error\":\"entity not found: " + id + "\"}");
        }
    }

    private void handleSubResourceListGet(RoutingContext ctx, String specName, String resourceName, int statusCode) {
        Optional<ResourceInfo> infoOpt = entityStore.getResourceInfo(specName, resourceName);
        if (infoOpt.isEmpty()) {
            ctx.response().putHeader("Content-Type", "application/json")
                    .setStatusCode(statusCode).end("[]");
            return;
        }
        ResourceInfo info = infoOpt.get();
        String parentIdParam = info.getParentIdParam() != null ? info.getParentIdParam() : "id";
        String parentIdStr = ctx.pathParam(parentIdParam);
        if (parentIdStr == null) {
            ctx.response().putHeader("Content-Type", "application/json")
                    .setStatusCode(statusCode).end("[]");
            return;
        }
        long parentId;
        try {
            parentId = Long.parseLong(parentIdStr);
        } catch (NumberFormatException e) {
            ctx.response().putHeader("Content-Type", "application/json")
                    .setStatusCode(400).end("{\"error\":\"invalid parent id: " + parentIdStr + "\"}");
            return;
        }

        // Find FK field linking sub-resource to parent
        Optional<ResourceGraph> graphOpt = entityStore.getGraph(specName);
        String fkField = graphOpt.flatMap(g -> g.getRelations().stream()
                        .filter(r -> r.getSourceResource().equals(resourceName)
                                  && r.getTargetResource().equals(info.getParentResourceName()))
                        .findFirst())
                .map(RelationDefinition::getSourceField)
                .orElse(null);

        List<Map<String, Object>> results = fkField != null
                ? entityStore.findByParent(specName, resourceName, fkField, parentId)
                : entityStore.findAll(specName, resourceName);

        ctx.response().putHeader("Content-Type", "application/json")
                .setStatusCode(statusCode).end(Json.encode(results));
    }

    private void handleEntityWrite(RoutingContext ctx, String specName, String resourceName,
                                    String method, int defaultStatus) {
        switch (method) {
            case "POST":
                Object bodyPost = ctx.body().asJsonObject() != null
                        ? ctx.body().asJsonObject().getMap()
                        : entityStore.findAll(specName, resourceName).stream().findAny().orElse(Map.of());
                ctx.response().putHeader("Content-Type", "application/json")
                        .setStatusCode(201).end(Json.encode(bodyPost));
                break;
            case "PUT": case "PATCH":
                Object bodyPut = ctx.body().asJsonObject() != null
                        ? ctx.body().asJsonObject().getMap()
                        : entityStore.findAll(specName, resourceName).stream().findAny().orElse(Map.of());
                ctx.response().putHeader("Content-Type", "application/json")
                        .setStatusCode(200).end(Json.encode(bodyPut));
                break;
            case "DELETE":
                ctx.response().setStatusCode(204).end();
                break;
            default:
                ctx.response().putHeader("Content-Type", "application/json")
                        .setStatusCode(defaultStatus).end("{}");
        }
    }

    private boolean hasFixture(MockProfile profile, String path, String method) {
        if (profile == null || profile.getFixtures() == null) return false;
        Map<String, MockProfile.FixtureResponse> methodMap = profile.getFixtures().get(path);
        return methodMap != null && methodMap.containsKey(method.toUpperCase());
    }

    private int parseIntParam(RoutingContext ctx, String name1, String name2, String name3, int defaultValue) {
        for (String name : new String[]{name1, name2, name3}) {
            String val = ctx.request().getParam(name);
            if (val != null) {
                try { return Integer.parseInt(val); } catch (NumberFormatException ignored) { }
            }
        }
        return defaultValue;
    }
}
