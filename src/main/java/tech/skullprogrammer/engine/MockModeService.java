package tech.skullprogrammer.engine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MockModeService {

    @Inject
    FakerSuggestionEngine suggestionEngine;

    private volatile boolean semanticEnabled = false;

    public boolean isSemanticEnabled() {
        return semanticEnabled;
    }

    public void setSemanticEnabled(boolean enabled) {
        this.semanticEnabled = enabled;
    }

    /** True if semantic mode is on AND the model is actually ready. */
    public boolean isSemanticReady() {
        return semanticEnabled && suggestionEngine.isReady();
    }

    public boolean isModelReady() {
        return suggestionEngine.isReady();
    }
}
