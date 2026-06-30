package tech.skullprogrammer;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.skullprogrammer.config.MockProfileLoader;
import tech.skullprogrammer.engine.EmbeddingEngine;
import tech.skullprogrammer.engine.EntityStore;
import tech.skullprogrammer.engine.FakerSuggestionEngine;
import tech.skullprogrammer.engine.LocaleDetector;
import tech.skullprogrammer.engine.ModelDownloader;
import tech.skullprogrammer.engine.RouteRegistry;
import tech.skullprogrammer.engine.SpecParser;
import tech.skullprogrammer.engine.SpecResourceAnalyzer;
import tech.skullprogrammer.model.MockProfile;
import tech.skullprogrammer.model.ParsedSpec;
import tech.skullprogrammer.model.ResourceGraph;
import tech.skullprogrammer.model.SpecEntity;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

@Slf4j
@ApplicationScoped
public class MockServerStartup {

    final SpecParser specParser;
    final RouteRegistry routeRegistry;
    final MockProfileLoader mockProfileLoader;

    @Inject
    LocaleDetector localeDetector;

    @Inject
    SpecResourceAnalyzer specResourceAnalyzer;

    @Inject
    EntityStore entityStore;

    @Inject
    EmbeddingEngine embeddingEngine;

    @Inject
    FakerSuggestionEngine suggestionEngine;

    @ConfigProperty(name = "mock.faker-suggestions.enabled", defaultValue = "true")
    boolean suggestionsEnabled;

    @ConfigProperty(name = "mock.faker-suggestions.model-dir", defaultValue = "./models")
    String modelDir;

    @ConfigProperty(name = "mock.faker-suggestions.model-base-url",
            defaultValue = "https://huggingface.co/optimum/paraphrase-multilingual-MiniLM-L12-v2/resolve/main")
    String modelBaseUrl;

    MockServerStartup(SpecParser specParser, RouteRegistry routeRegistry, MockProfileLoader mockProfileLoader) {
        this.specParser = specParser;
        this.routeRegistry = routeRegistry;
        this.mockProfileLoader = mockProfileLoader;
    }

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        loadSpecs();
        if (suggestionsEnabled) {
            startModelLoadingAsync();
        }
    }

    private void loadSpecs() {
        List<SpecEntity> activeSpecs = SpecEntity.findAllActive();
        if (activeSpecs.isEmpty()) {
            log.info("No specs found in DB, mock API ready and waiting for uploads");
            return;
        }
        log.info("Loading {} spec(s) from DB...", activeSpecs.size());
        for (SpecEntity spec : activeSpecs) {
            try {
                ParsedSpec parsed = specParser.parseFromContent(spec.content);
                Locale locale = localeDetector.detect(spec.content);
                MockProfile profile = null;
                if (spec.profileContent != null) {
                    profile = mockProfileLoader.loadFromContent(spec.profileContent);
                    mockProfileLoader.registerByName(spec.name, profile);
                    if (profile.getLocale() != null) {
                        locale = localeDetector.fromCode(profile.getLocale());
                    }
                    log.info("Loaded profile for spec '{}'", spec.name);
                }
                specResourceAnalyzer.analyze(parsed, profile);
                routeRegistry.registerFromSpec(spec.name, parsed, locale);
                log.info("Spec '{}' locale: {} — use UI to generate entity data", spec.name, locale.getLanguage());
            } catch (Exception e) {
                log.error("Failed to load spec '{}' on startup: {}", spec.name, e.getMessage());
            }
        }
    }

    private void startModelLoadingAsync() {
        Path dir = Path.of(modelDir);
        CompletableFuture.runAsync(() -> {
            try {
                ModelDownloader.downloadIfNeeded(dir, modelBaseUrl);
                embeddingEngine.initialize(dir);
                suggestionEngine.initialize();
            } catch (Exception e) {
                log.warn("[FakerSuggestion] Model loading failed — faker suggestions will be unavailable: {}", e.getMessage());
            }
        });
    }
}
