package tech.skullprogrammer.engine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import tech.skullprogrammer.model.FakerSuggestion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@ApplicationScoped
public class FakerSuggestionEngine {

    record CatalogEntry(String expression, String label, String embedText, List<String> types, List<String> formats) {}

    private static final List<CatalogEntry> FAKER_CATALOG_BACKEND = List.of(
        new CatalogEntry("Name.fullName",
            "Full name",
            "full name complete name nome completo nome e cognome",
            List.of("string"), List.of()),
        new CatalogEntry("Name.firstName",
            "First name",
            "first name given name nome prenome vorname prénom",
            List.of("string"), List.of()),
        new CatalogEntry("Name.lastName",
            "Last name",
            "last name surname family name cognome apellido familienname",
            List.of("string"), List.of()),
        new CatalogEntry("Internet.emailAddress",
            "Email address",
            "email e-mail posta elettronica electronic mail contact",
            List.of("string"), List.of("email")),
        new CatalogEntry("Internet.url",
            "URL",
            "URL web link website sito web hyperlink http endpoint",
            List.of("string"), List.of("uri", "url")),
        new CatalogEntry("Internet.ipV4Address",
            "IPv4 address",
            "IPv4 IP address network host server rete nodo",
            List.of("string"), List.of("ipv4")),
        new CatalogEntry("Internet.password",
            "Password",
            "password secret passphrase credentials parola chiave accesso",
            List.of("string"), List.of("password")),
        new CatalogEntry("Internet.uuid",
            "UUID",
            "UUID GUID universally unique identifier system key token",
            List.of("string"), List.of("uuid")),
        new CatalogEntry("Address.city",
            "City",
            "city town città comune località localita municipio village",
            List.of("string"), List.of()),
        new CatalogEntry("Address.country",
            "Country",
            "country nation nazione paese stato land pays nation",
            List.of("string"), List.of()),
        new CatalogEntry("Address.streetAddress",
            "Street address",
            "street address via indirizzo stradale road avenue domicilio residenza civic",
            List.of("string"), List.of()),
        new CatalogEntry("Address.zipCode",
            "ZIP code",
            "ZIP postal code CAP codice postale postcode PLZ avviamento postale",
            List.of("string"), List.of()),
        new CatalogEntry("PhoneNumber.phoneNumber",
            "Phone number",
            "phone telephone cellulare numero telefono mobile contatto telefonico",
            List.of("string"), List.of("phone")),
        new CatalogEntry("Company.name",
            "Company name",
            "company business azienda impresa società ditta empresa firm",
            List.of("string"), List.of()),
        new CatalogEntry("Company.industry",
            "Industry",
            "industry sector settore industria campo attività categoria merceologica",
            List.of("string"), List.of()),
        new CatalogEntry("Lorem.word",
            "Random word",
            "word term label tag keyword parola generica etichetta",
            List.of("string"), List.of()),
        new CatalogEntry("Lorem.sentence",
            "Sentence",
            "sentence phrase message description note commento testo messaggio descrizione",
            List.of("string"), List.of()),
        new CatalogEntry("Lorem.paragraph",
            "Paragraph",
            "paragraph long text body content notes testo lungo paragrafo contenuto",
            List.of("string"), List.of()),
        new CatalogEntry("date.birthday '18','65','yyyy-MM-dd'",
            "Date (birthday)",
            "birthday birth date data nascita compleanno born age",
            List.of("string"), List.of("date")),
        new CatalogEntry("date.future '365','yyyy-MM-dd'",
            "Date (future)",
            "future date expiration scadenza deadline appointment data futura",
            List.of("string"), List.of("date")),
        new CatalogEntry("date.past '365','yyyy-MM-dd\\'T\\'HH:mm:ssXXX'",
            "DateTime (past)",
            "datetime timestamp creation modified data creazione aggiornamento created updated",
            List.of("string"), List.of("date-time", "datetime")),
        new CatalogEntry("Number.randomDigit",
            "Single digit",
            "digit small number rank level priority index punteggio livello",
            List.of("integer", "number"), List.of()),
        new CatalogEntry("Number.numberBetween '1','1000'",
            "Number 1-1000",
            "count quantity amount total numeric value quantità numero contatore",
            List.of("integer", "number"), List.of()),
        new CatalogEntry("Number.numberBetween '1','100'",
            "Percentage 1-100",
            "percentage percent ratio score progress percentuale tasso proporzione",
            List.of("integer", "number"), List.of()),
        new CatalogEntry("Number.randomDouble '2','0','100'",
            "Decimal 0-100",
            "decimal float measure weight price amount valore decimale misura importo",
            List.of("number"), List.of("float", "double")),
        new CatalogEntry("Bool.bool",
            "Boolean",
            "boolean flag active enabled true false attivo abilitato stato",
            List.of("boolean"), List.of()),
        new CatalogEntry("Address.state",
            "State / Province",
            "state province region regione provincia stato territoriale federal",
            List.of("string"), List.of()),
        new CatalogEntry("Address.stateAbbr",
            "State abbreviation",
            "state abbreviation sigla codice provincia targa abbreviazione regionale",
            List.of("string"), List.of()),
        new CatalogEntry("Address.buildingNumber",
            "Building number",
            "building number numero civico house civico interno appartamento",
            List.of("string"), List.of()),
        new CatalogEntry("Address.latitude",
            "Latitude",
            "latitude latitudine lat coordinata verticale geographic nord south",
            List.of("string", "number"), List.of("float", "double")),
        new CatalogEntry("Address.longitude",
            "Longitude",
            "longitude longitudine lon coordinata orizzontale geographic east west",
            List.of("string", "number"), List.of("float", "double")),
        new CatalogEntry("Finance.iban",
            "IBAN",
            "IBAN bank account conto corrente codice bancario international",
            List.of("string"), List.of()),
        new CatalogEntry("Finance.bic",
            "BIC / SWIFT",
            "BIC SWIFT bank code codice banca istituto bancario routing",
            List.of("string"), List.of()),
        new CatalogEntry("Commerce.productName",
            "Product name",
            "product name nome prodotto articolo item merce codice articolo",
            List.of("string"), List.of()),
        new CatalogEntry("Number.randomDouble '2','0','9999'",
            "Amount / Price",
            "amount price cost importo prezzo costo valore monetario euro denaro",
            List.of("number"), List.of("float", "double"))
    );

    @Inject
    EmbeddingEngine embeddingEngine;

    private final Map<String, float[]> catalogEmbeddings = new HashMap<>();
    private final AtomicBoolean ready = new AtomicBoolean(false);

    float threshold = 0.35f;
    int defaultTopK = 3;

    public void initialize() {
        log.info("[FakerSuggestion] Pre-computing catalog embeddings ({} entries)...", FAKER_CATALOG_BACKEND.size());
        catalogEmbeddings.clear();
        for (CatalogEntry entry : FAKER_CATALOG_BACKEND) {
            float[] emb = embeddingEngine.embed(entry.embedText());
            catalogEmbeddings.put(entry.expression(), emb);
        }
        ready.set(true);
        log.info("[FakerSuggestion] FakerSuggestionEngine ready — {} catalog embeddings computed", catalogEmbeddings.size());
    }

    public boolean isReady() {
        return ready.get() && embeddingEngine.isReady();
    }

    public List<FakerSuggestion> suggest(String fieldName, String type, String format, int topK) {
        if (!isReady()) return List.of();

        List<CatalogEntry> candidates = filterByTypeAndFormat(type, format);
        if (candidates.isEmpty()) return List.of();

        float adaptiveThreshold = adaptiveThreshold(candidates.size());
        float[] fieldEmb = embeddingEngine.embed(fieldName);

        List<FakerSuggestion> scored = new ArrayList<>();
        for (CatalogEntry entry : candidates) {
            float[] catEmb = catalogEmbeddings.get(entry.expression());
            if (catEmb == null) continue;
            float score = cosineSimilarity(fieldEmb, catEmb);
            if (score >= adaptiveThreshold) {
                scored.add(FakerSuggestion.builder()
                        .expression(entry.expression())
                        .label(entry.label())
                        .score(score)
                        .autoSuggested(true)
                        .build());
            }
        }

        scored.sort(Comparator.comparingDouble(FakerSuggestion::getScore).reversed());
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    // Narrow pool (format-filtered): format already selected the right type, be permissive.
    // Wide pool (all strings, no format): stricter to avoid noise from weak semantic matches.
    private float adaptiveThreshold(int candidateCount) {
        if (candidateCount <= 3)  return threshold * 0.70f;
        if (candidateCount <= 10) return threshold;
        return threshold * 1.25f;
    }

    private List<CatalogEntry> filterByTypeAndFormat(String type, String format) {
        String fmt = format != null ? format.toLowerCase() : null;
        String typ = type != null ? type.toLowerCase() : "string";

        if (fmt != null && !fmt.isEmpty()) {
            List<CatalogEntry> byFormat = FAKER_CATALOG_BACKEND.stream()
                    .filter(e -> e.formats().stream().anyMatch(f -> f.equalsIgnoreCase(fmt)))
                    .toList();
            if (!byFormat.isEmpty()) return byFormat;
        }
        return FAKER_CATALOG_BACKEND.stream()
                .filter(e -> e.types().contains(typ))
                .toList();
    }

    private float cosineSimilarity(float[] a, float[] b) {
        // vectors are already L2-normalized, so dot product == cosine similarity
        float dot = 0f;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }
}
