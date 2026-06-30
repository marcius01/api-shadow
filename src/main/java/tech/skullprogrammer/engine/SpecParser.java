package tech.skullprogrammer.engine;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.converter.SwaggerConverter;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import tech.skullprogrammer.model.MockEndpoint;
import tech.skullprogrammer.model.MockParameter;
import tech.skullprogrammer.model.ParsedSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@ApplicationScoped
public class SpecParser {

    public ParsedSpec parseFromLocation(String location) {
        try {
            String content = location.startsWith("http://") || location.startsWith("https://")
                    ? fetchUrl(location)
                    : Files.readString(Path.of(location));
            return parseFromContent(content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read spec from: " + location, e);
        }
    }

    public ParsedSpec parseFromContent(String content) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);

        // In Quarkus, ServiceLoader non carica SwaggerConverter automaticamente:
        // lo invochiamo direttamente per le spec Swagger 2.0
        SwaggerParseResult result = isSwagger2(content)
                ? new SwaggerConverter().readContents(content, null, options)
                : new OpenAPIV3Parser().readContents(content, null, options);

        return toParseSpec(result);
    }

    /**
     * Fetches a spec from URL and resolves all relative $ref to absolute URLs.
     * Returns the processed content string — store this in DB so that any future
     * parseFromContent() call (on reload, startup, schema endpoint) also has resolved refs.
     */
    public String fetchAndResolveContent(String url) throws IOException {
        String content = fetchUrl(url);
        String baseUrl = url.contains("/") ? url.substring(0, url.lastIndexOf('/') + 1) : url + "/";
        String resolved = resolveRelativeRefs(content, baseUrl);
        int count = (content.length() - resolved.length()); // rough check
        log.debug("Resolved relative $ref in spec from {} (content length: {} → {})", url, content.length(), resolved.length());
        return resolved;
    }

    /** Parses a spec from URL with fully resolved $ref references. */
    public ParsedSpec parseFromUrl(String url) throws IOException {
        return parseFromContent(fetchAndResolveContent(url));
    }

    /**
     * Replaces relative $ref values (./foo or ../foo) with absolute URLs.
     * Handles YAML (single/double quoted) and JSON (double quoted) $ref values.
     */
    private String resolveRelativeRefs(String content, String baseUrl) {
        // Matches: $ref: './path/to/file.yaml#/anchor' (single or double quoted)
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "\\$ref:\\s*(['\"])(\\.{1,2}/[^'\"]+)\\1"
        );
        java.util.regex.Matcher matcher = pattern.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String quote = matcher.group(1);
            String relativePath = matcher.group(2);
            String absoluteUrl;
            if (relativePath.startsWith("./")) {
                absoluteUrl = baseUrl + relativePath.substring(2);
            } else if (relativePath.startsWith("../")) {
                // Simple parent resolution: strip last segment from baseUrl
                String parent = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
                parent = parent.contains("/") ? parent.substring(0, parent.lastIndexOf('/') + 1) : parent + "/";
                absoluteUrl = parent + relativePath.substring(3);
            } else {
                absoluteUrl = baseUrl + relativePath;
            }
            matcher.appendReplacement(sb, "\\$ref: " + quote + absoluteUrl + quote);
            log.debug("Resolved relative $ref '{}' → '{}'", relativePath, absoluteUrl);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private boolean isSwagger2(String content) {
        return content.contains("swagger:") || content.contains("\"swagger\":");
    }

    public String fetchContent(String url) throws IOException {
        return fetchUrl(url);
    }

    private String fetchUrl(String url) throws IOException {
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .build();
        try {
            return java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                    .body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching: " + url, e);
        }
    }

    private ParsedSpec toParseSpec(SwaggerParseResult result) {
        OpenAPI openAPI = result.getOpenAPI();
        if (openAPI == null) {
            throw new IllegalArgumentException("Invalid OpenAPI spec: " + result.getMessages());
        }
        if (result.getMessages() != null && !result.getMessages().isEmpty()) {
            log.warn("Spec parsed with warnings: {}", result.getMessages());
        }
        return toOpenApiSpec(openAPI);
    }

    private ParsedSpec toOpenApiSpec(OpenAPI openAPI) {
        String title = openAPI.getInfo() != null ? openAPI.getInfo().getTitle() : "unknown";
        String version = openAPI.getInfo() != null ? openAPI.getInfo().getVersion() : "unknown";
        String id = sanitizeId(title);
        List<MockEndpoint> endpoints = extractEndpoints(openAPI);

        log.info("Parsed spec '{}' v{} — {} endpoint(s)", title, version, endpoints.size());
        return new ParsedSpec(id, title, version, endpoints);
    }

    private List<MockEndpoint> extractEndpoints(OpenAPI openAPI) {
        List<MockEndpoint> endpoints = new ArrayList<>();
        if (openAPI.getPaths() == null) return endpoints;

        openAPI.getPaths().forEach((path, pathItem) -> {
            if (pathItem == null) return;
            pathItem.readOperationsMap().forEach((httpMethod, operation) -> {
                Map<String, Schema<?>> responses = extractAllResponses(operation);
                int defaultStatusCode = findDefaultStatusCode(responses);
                List<MockParameter> parameters = extractParameters(pathItem.getParameters(), operation.getParameters());

                endpoints.add(new MockEndpoint(path, httpMethod.name(), defaultStatusCode, responses, parameters));
                log.debug("  {} {} → default:{} codes:{} params:{}", httpMethod.name(), path, defaultStatusCode,
                        responses.keySet(), parameters.stream().map(MockParameter::getName).toList());
            });
        });
        return endpoints;
    }

    private List<MockParameter> extractParameters(List<Parameter> pathLevelParams, List<Parameter> operationParams) {
        // path-level params first, operation-level override by name
        Map<String, MockParameter> merged = new LinkedHashMap<>();

        if (pathLevelParams != null) {
            pathLevelParams.forEach(p -> merged.put(p.getName(), toMockParameter(p)));
        }
        if (operationParams != null) {
            operationParams.forEach(p -> merged.put(p.getName(), toMockParameter(p)));
        }

        return new ArrayList<>(merged.values());
    }

    private MockParameter toMockParameter(Parameter p) {
        String type = null;
        String format = null;
        if (p.getSchema() != null) {
            type = p.getSchema().getType();
            format = p.getSchema().getFormat();
        }
        return new MockParameter(
                p.getName(),
                p.getIn(),
                type,
                format,
                Boolean.TRUE.equals(p.getRequired()),
                p.getDescription());
    }

    private Map<String, Schema<?>> extractAllResponses(Operation operation) {
        Map<String, Schema<?>> result = new LinkedHashMap<>();
        if (operation.getResponses() == null) return result;

        operation.getResponses().forEach((code, apiResponse) ->
                result.put(code, extractSchemaFromResponse(apiResponse)));

        return result;
    }

    private Schema<?> extractSchemaFromResponse(ApiResponse response) {
        if (response == null || response.getContent() == null) return null;

        MediaType mediaType = response.getContent().get("application/json");
        if (mediaType == null) mediaType = response.getContent().values().stream().findFirst().orElse(null);
        if (mediaType == null) return null;

        return mediaType.getSchema();
    }

    private int findDefaultStatusCode(Map<String, Schema<?>> responses) {
        return responses.keySet().stream()
                .filter(code -> code.startsWith("2"))
                .mapToInt(code -> {
                    try { return Integer.parseInt(code); } catch (NumberFormatException e) { return 200; }
                })
                .min()
                .orElse(200);
    }

    private String sanitizeId(String title) {
        return title.toLowerCase().replaceAll("[^a-z0-9]", "-");
    }
}
