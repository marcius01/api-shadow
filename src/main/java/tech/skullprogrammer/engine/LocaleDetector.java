package tech.skullprogrammer.engine;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.Map;

@Slf4j
@ApplicationScoped
public class LocaleDetector {

    private static final Map<Language, Locale> LANGUAGE_LOCALE_MAP = Map.of(
            Language.ENGLISH,    Locale.ENGLISH,
            Language.ITALIAN,    Locale.ITALIAN,
            Language.GERMAN,     Locale.GERMAN,
            Language.FRENCH,     Locale.FRENCH,
            Language.SPANISH,    new Locale("es"),
            Language.PORTUGUESE, new Locale("pt"),
            Language.DUTCH,      new Locale("nl"),
            Language.POLISH,     new Locale("pl")
    );

    private final LanguageDetector detector;

    public LocaleDetector() {
        this.detector = LanguageDetectorBuilder
                .fromLanguages(
                        Language.ENGLISH, Language.ITALIAN, Language.GERMAN,
                        Language.FRENCH, Language.SPANISH, Language.PORTUGUESE,
                        Language.DUTCH, Language.POLISH)
                .withLowAccuracyMode()
                .build();
        log.info("[LocaleDetector] Initialized (EN, IT, DE, FR, ES, PT, NL, PL)");
    }

    public Locale detect(String text) {
        if (text == null || text.isBlank()) return Locale.ENGLISH;
        try {
            Language lang = detector.detectLanguageOf(text);
            Locale locale = LANGUAGE_LOCALE_MAP.getOrDefault(lang, Locale.ENGLISH);
            log.debug("[LocaleDetect] Detected language {} → locale '{}'", lang, locale.getLanguage());
            return locale;
        } catch (Exception e) {
            log.warn("[LocaleDetect] Detection failed, defaulting to English: {}", e.getMessage());
            return Locale.ENGLISH;
        }
    }

    public Locale fromCode(String code) {
        if (code == null || code.isBlank()) return Locale.ENGLISH;
        return new Locale(code.toLowerCase().trim());
    }
}
