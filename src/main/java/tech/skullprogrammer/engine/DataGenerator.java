package tech.skullprogrammer.engine;

import io.swagger.v3.oas.models.media.Schema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.skullprogrammer.model.MockProfile;

import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@ApplicationScoped
public class DataGenerator {

    @Inject
    SchemaDispatcher schemaDispatcher;

    @Inject
    ProfileResolver profileResolver;

    @Inject
    MockModeService mockModeService;

    @ConfigProperty(name = "mock.optional-field-probability", defaultValue = "0.70")
    double optionalProbability;

    @ConfigProperty(name = "mock.max-schema-depth", defaultValue = "5")
    int maxSchemaDepth;

    @ConfigProperty(name = "mock.default-array-count", defaultValue = "3")
    int defaultArrayCount;

    private final Map<Locale, Faker> fakerCache = new ConcurrentHashMap<>();

    // T014: entry point — fixture bypass → latency → seeded/locale faker → dispatch
    public Object generate(Schema<?> schema, String specName, String endpointPath, String method, MockProfile profile, Locale locale) {
        if (schema == null) return null;

        // T022: static fixture bypasses all generation
        Object fixture = profileResolver.resolveFixture(endpointPath, method, profile);
        if (fixture != null) return fixture;

        // T022: resolve override for latency, count, seed, optionalProbability
        MockProfile.EndpointOverride override = profileResolver.resolve(endpointPath, method, profile);

        // T022: apply latency before generating
        if (override != null && override.getLatency() != null) {
            applyLatency(override.getLatency());
        }

        Locale effectiveLocale = locale != null ? locale : Locale.ENGLISH;
        // T026: seeded Faker — new per-request instance for reproducibility; locale-cached otherwise
        Faker faker = (override != null && override.getSeed() != null)
                ? new Faker(effectiveLocale, new Random(override.getSeed()))
                : fakerCache.computeIfAbsent(effectiveLocale, Faker::new);

        double effectiveProbability = (override != null && override.getOptionalProbability() != null)
                ? override.getOptionalProbability()
                : optionalProbability;

        int effectiveMaxDepth = (override != null && override.getMaxSchemaDepth() != null)
                ? override.getMaxSchemaDepth()
                : maxSchemaDepth;

        GenerationContext ctx = new GenerationContext(
                0, faker, endpointPath, method, "", override,
                effectiveProbability, effectiveMaxDepth, defaultArrayCount, mockModeService.isSemanticReady());

        return schemaDispatcher.dispatch(schema, ctx);
    }

    private void applyLatency(String latency) {
        try {
            int ms = Integer.parseInt(latency.replace("ms", "").trim());
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (NumberFormatException e) {
            log.warn("Invalid latency format: '{}'", latency);
        }
    }
}
