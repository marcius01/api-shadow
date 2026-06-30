# api-shadow

Mock server intelligente che genera endpoint REST a partire da specifiche OpenAPI/Swagger,
con dati casuali configurabili e coerenza tra risorse collegate.

---

## Stack tecnico

- **Runtime**: Quarkus (preferito a Spring Boot per startup veloce e immagine Docker compatta)
- **Routing dinamico**: Vert.x Router (integrato in Quarkus) — le route vengono registrate programmaticamente a runtime, senza generazione di codice
- **Parsing spec**: `io.swagger.parser.v3:swagger-parser:2.1.22` — supporta OpenAPI 2.x e 3.x, YAML e JSON, file locale e URL remoto, risolve `$ref` automaticamente
- **Generazione dati**: DataFaker + vincoli estratti dallo schema OpenAPI (`format`, `minimum`, `maximum`, `pattern`, `enum`)
- **Boilerplate**: Lombok (`@Data`, `@Builder`, `@Slf4j`, ecc.) — usato su tutte le classi model e nei bean CDI
- **Templating Admin UI**: Alpine.js + Tailwind CSS (single-page, nessun framework SPA)
- **Build/deploy**: Docker — immagine JVM (`Dockerfile.jvm`) per sviluppo, native GraalVM opzionale per produzione

---

## Architettura a componenti

```
Input
├── Upload file YAML/JSON          (multipart POST /admin/specs/upload)
├── URL remoto Swagger             (POST /admin/specs/upload-url)
└── Mock Profile YAML              (configurazione per campo, fixture statiche, relazioni, denormalizzati)

Core Engine
├── SpecParser                     — legge e normalizza la spec OpenAPI
├── SpecResourceAnalyzer           — analisi path → ResourceGraph (risorse, FK, paginazione, denormalizzati)
├── SchemaDispatcher               — genera valori rispettando tipo/format/pattern/enum/profilo
├── EntityStore                    — dataset pre-generato in-memory; lookup O(1) per ID; FK + denorm resolution
├── RouteRegistry                  — registra/deregistra route Vert.x a caldo; routing entity-aware
├── EmbeddingEngine                — ONNX paraphrase-multilingual-MiniLM-L12-v2; cosine similarity
├── FakerSuggestionEngine          — catalogo 35 voci + threshold adattivo; suggerimenti per campo
└── LocaleDetector                 — rileva lingua dalla spec (8 lingue); override via profilo

Runtime HTTP
├── Mock API Server  :8080         — endpoint generati dalla spec (prefisso /api/<nome-spec>)
├── Admin UI         :8080/admin   — dashboard Alpine.js + Tailwind
└── Swagger UI       :8090         — SmallRye, visualizza la spec caricata
```

---

## Package Java

```
tech.skullprogrammer
├── admin
│   ├── AdminResource.java         — tutti gli endpoint /admin/*
│   ├── SpecUploadForm.java        — @MultipartForm per upload spec
│   └── ProfileUploadForm.java     — @MultipartForm per upload profilo
├── engine
│   ├── SpecParser.java            — wrappa swagger-parser, restituisce ParsedSpec
│   ├── SpecResourceAnalyzer.java  — path analysis → ResourceGraph (FK, paginazione, denormalizzati)
│   ├── SchemaDispatcher.java      — genera valori per ogni tipo OpenAPI
│   ├── SchemaAnalyzer.java        — flatten schema → List<FieldSchema> per il profile editor
│   ├── DataGenerator.java         — orchestratore generazione per-request (senza EntityStore)
│   ├── RouteRegistry.java         — gestisce Vert.x Router; routing entity-aware vs DataGenerator
│   ├── EntityStore.java           — dataset in-memory; FK resolution; denorm resolution
│   ├── EmbeddingEngine.java       — ONNX inference; embed(); cosineSimilarity()
│   ├── FakerSuggestionEngine.java — catalogo faker; suggest(fieldName, type, format, k)
│   ├── LocaleDetector.java        — lingua 1.2.2; detect(content); fromCode(code)
│   ├── MockModeService.java       — isSemanticEnabled(); isModelReady()
│   └── MockServerStartup.java     — StartupEvent → re-registra route dal DB
├── model
│   ├── ParsedSpec.java            — lista MockEndpoint + metadata spec
│   ├── MockEndpoint.java          — path, method, responses, PathType, resourceName
│   ├── MockProfile.java           — locale, dataset, relations, denormalized, overrides, fixtures
│   ├── ResourceGraph.java         — resources (Map), relations (List), denormalized (List)
│   ├── ResourceInfo.java          — listPath, singlePath, itemSchema, paginationShape, parentResource
│   ├── RelationDefinition.java    — sourceResource.sourceField → targetResource.targetField
│   ├── DenormalizedDefinition.java — sourceField ← sourceKeyField → targetResource.targetField
│   ├── PaginationShape.java       — arrayField, totalElementsField, totalPagesField, …
│   ├── FieldSchema.java           — name, type, format, required, suggestions
│   ├── FakerSuggestion.java       — expression, score
│   └── SpecSchemaDTO.java         — DTO per GET /schema
├── config
│   └── MockProfileLoader.java     — parse/serialize YAML profilo; registro in-memory per spec
└── db
    └── SpecEntity.java            — PanacheEntity: name, content, profileContent, semanticMode, …
```

---

## Formato Mock Profile YAML

Generato automaticamente all'upload della spec. Scaricabile dalla UI (↓ yaml). Modificabile via editor visuale o upload YAML.

```yaml
# mock-profile.yaml
locale: it                    # override lingua (auto-detect se assente)

dataset:
  puntiPrelievo: 20           # entità pre-generate per risorsa
  campioni: 50

relations:
  campioni.idPuntoPrelievo: puntiPrelievo.id   # FK: campo → risorsa.campo target

denormalized:
  campioni.nomePuntoPrelievo: idPuntoPrelievo->puntiPrelievo.nome  # copia campo da entità FK

overrides:
  /pazienti/{id}:
    GET:
      latency: 200ms          # latenza simulata
      count: 10               # elementi nell'array risposta (solo per endpoint lista)
      seed: 42                # seed DataFaker per riproducibilità
      forceStatus: 503        # forza HTTP status code (override X-Mock-Force-Status header)
      fields:
        data[].cognome:
          faker: "Name.lastName"
        data[].codice_fiscale:
          pattern: "[A-Z]{6}[0-9]{2}[A-Z][0-9]{2}[A-Z][0-9]{3}[A-Z]"
        data[].stato:
          enum: [ATTIVO, SOSPESO]
          distribution: [80, 20]    # 80% ATTIVO, 20% SOSPESO

fixtures:
  /auth/token:
    GET:
      static:                 # risposta sempre fissa, ignora EntityStore e DataGenerator
        access_token: "mock-token-123"
        expires_in: 3600
```

**Nota sui path nei fields**: i path usano la notazione dell'endpoint risposta completa (es. `data[].nome` per campi dentro un array wrapper). Durante il seed EntityStore il prefisso `data[].` viene normalizzato automaticamente.

---

## Endpoint Admin

```
# Spec
GET  /admin/specs                         — lista spec attive (con seeded, profile, semanticMode)
POST /admin/specs/upload                  — upload file YAML/JSON (multipart; campo opzionale: profile)
POST /admin/specs/upload-url              — carica spec da URL remoto (JSON body: name, url)
POST /admin/specs/{name}/reload           — ricarica route a caldo; svuota EntityStore; NON tocca il profilo
POST /admin/specs/{name}/delete           — rimuove spec, route e dataset in-memory
POST /admin/specs/{name}/rename           — rinomina spec mantenendo dati e profilo (JSON body: newName)
POST /admin/specs/{name}/set-mode        — imposta semanticMode (JSON body: {semantic: true|false})

# Dataset
POST /admin/specs/{name}/reseed           — genera il dataset usando il profilo corrente (puro, non tocca il profilo)

# Profilo
GET  /admin/specs/{name}/profile/json     — legge il profilo come JSON
PUT  /admin/specs/{name}/profile/json     — salva il profilo come JSON
POST /admin/specs/{name}/profile          — sostituisce il profilo con un file YAML (multipart)
POST /admin/specs/{name}/profile/delete   — rimuove il profilo
GET  /admin/specs/{name}/profile/download — scarica il profilo YAML materializzato (inferenza corrente + override espliciti)
POST /admin/specs/{name}/profile/fill-inferred    — aggiunge faker inferiti SOLO per i campi non ancora configurati (putIfAbsent)
POST /admin/specs/{name}/profile/rebuild-inferred — azzera tutti i fields e ricalcola l'inferenza da zero

# Schema e risorse
GET  /admin/specs/{name}/schema           — schema flat per endpoint (campi + suggestions)
GET  /admin/specs/{name}/resources        — ResourceGraph: resources, relations, denormalized

# Status
GET  /admin/api/status                    — health check JSON
GET  /admin/api/mode                      — semantic enabled + modelReady
POST /admin/api/mode                      — imposta semantic enabled globale
GET  /admin/endpoints                     — conteggio route attive per spec
```

---

## Configurazione porte (application.properties)

```properties
quarkus.http.port=8080
quarkus.management.port=8090
quarkus.management.enabled=true
```

---

## Docker

### Dockerfile.jvm

`src/main/docker/Dockerfile.jvm` — immagine JVM pronta per Harbor.

Rispetto al template Quarkus default:
- Base image: `ubi9/openjdk-21-runtime:1.20` (allineato a Java 21 LTS)
- `EXPOSE 8090` aggiunto (la porta admin mancava nel template)
- ENV vars per path configurabili:
  ```
  QUARKUS_DATASOURCE_JDBC_URL=jdbc:h2:file:/work/data/api-shadow;AUTO_SERVER=TRUE
  MOCK_FAKER_SUGGESTIONS_MODEL_DIR=/work/models
  ```

### docker-compose.yml (corrente)

```yaml
services:
  api-shadow:
    image: api-shadow:latest
    ports:
      - "8080:8080"     # porta mock API (usata dai client/colleghi)
      - "8090:8090"     # porta admin UI (uso interno)
    volumes:
      - ./data:/work/data       # H2 database file (api-shadow.mv.db)
      - ./models:/work/models   # ONNX model (457MB — NON nell'immagine)
    restart: unless-stopped
```

Volume `./data` e `./models` vengono creati automaticamente da Docker Compose nella working directory.

### build-image.sh

Script per build + tag + push a Harbor:
```bash
./build-image.sh harbor.company.com/team        # build + tag solo
./build-image.sh harbor.company.com/team push   # build + tag + push
```
Legge la versione da `build.gradle.kts` e produce tag `latest` e `<version>`.

### .dockerignore

Pattern whitelist che esclude tutto tranne il JAR Quarkus:
```
*
!build/quarkus-app/**
```
Esclude automaticamente `spec-kit-files/`, `.claude/`, `.specify/` e qualunque file locale.

### Dimensione immagine (~1.1GB)

La dimensione è attesa:
- UBI9 base + JRE 21: ~400MB
- `lingua` (rilevamento lingua): ~77MB con 75 modelli linguistici embedded
- `onnxruntime-extensions` (ONNX inference): ~85MB con binari nativi per tutte le piattaforme
- Classpath applicativo + swagger-parser + datafaker: ~150MB
- Il modello ONNX da 457MB è escluso dall'immagine (volume mount)

---

## Comportamento HTTP methods (EntityStore)

Il dataset EntityStore è **read-only** a runtime — le chiamate di modifica non persistono:
- **GET lista** → restituisce le entità pre-generate con paginazione coerente
- **GET singolo** → lookup O(1) per ID, 404 se non trovato
- **POST** → restituisce 201 con un'entità random dal dataset (o il body ricevuto se riconoscibile)
- **PUT** → restituisce 200 con un'entità random dal dataset
- **DELETE** → restituisce 204, non rimuove nulla dal dataset
- **Query string filtering** — i filtri passati come query param vengono ignorati, i dati non vengono filtrati (feature futura)

---

## Dipendenze Maven principali da aggiungere

```xml
<!-- Parsing OpenAPI -->
<dependency>
  <groupId>io.swagger.parser.v3</groupId>
  <artifactId>swagger-parser</artifactId>
  <version>2.1.22</version>
</dependency>

<!-- Generazione dati fake -->
<dependency>
  <groupId>net.datafaker</groupId>
  <artifactId>datafaker</artifactId>
  <version>2.2.2</version>
</dependency>

<!-- Qute templating (già incluso in Quarkus, verifica extension) -->
<!-- quarkus-qute -->

<!-- Swagger UI (SmallRye) -->
<!-- quarkus-smallrye-openapi -->

<!-- Lombok -->
<dependency>
  <groupId>org.projectlombok</groupId>
  <artifactId>lombok</artifactId>
  <version>1.18.36</version>
  <scope>provided</scope>
</dependency>
```

> Nota: usa `net.datafaker:datafaker` (fork attivo di JavaFaker) — JavaFaker originale
> non è più mantenuto attivamente.

---

## Extension Quarkus da selezionare su quarkus.io

Quando crei il progetto su https://code.quarkus.io seleziona:

- `quarkus-vertx` — Vert.x core per routing dinamico
- `quarkus-resteasy-reactive` — endpoint REST Admin
- `quarkus-resteasy-reactive-jackson` — serializzazione JSON
- `quarkus-qute` — templating Admin UI
- `quarkus-smallrye-openapi` — Swagger UI integrata
- `quarkus-container-image-docker` — build immagine Docker

---

## Convenzioni di sviluppo

- I mock endpoint vengono registrati sotto `/api/<nome-spec>/` per separazione netta da `/admin/`
- `SpecResourceAnalyzer.analyze()` **DEVE** essere chiamato prima di `RouteRegistry.registerFromSpec()` — muta in-place `endpoint.resourceName` e `endpoint.pathType`, che sono necessari per il routing entity-aware
- Il routing entity-aware si attiva solo se: `resourceName != null && pathType != OTHER && !hasFixture && entityStore.hasDataset(specName, resourceName)`
- Il flusso profilo è a tre responsabilità separate:
  1. **genera** (`reseed`) — usa il profilo come sorgente di verità, non lo tocca mai
  2. **inferisci** (`fill-inferred`) — aggiunge faker inferiti solo per campi mancanti (`putIfAbsent`)
  3. **reinferisci** (`rebuild-inferred`) — azzera tutti i `fields` e ricalcola da zero (perde override manuali)
- `EntityStore.resolveItemOverride()` normalizza i path del profilo togliendo il prefisso wrapper (`data[].nome` → `nome`) prima di passarli al `GenerationContext`
- `EntityStore.resolveDenormalized()` viene eseguito **dopo** `resolveForeignKeys()` — ha bisogno che i FK siano già risolti
- I field path nel profilo usano la notazione del response schema completo (es. `data[].nome`); il seed li normalizza, il DataGenerator live no (bug noto, non prioritario)
- `@JsonInclude(NON_NULL)` su tutte le classi `MockProfile.*` — il YAML scaricato non ha campi null
- **$ref resolution via URL**: `SpecParser.fetchAndResolveContent(url)` converte i `$ref` relativi (`./components/foo.yaml`) in URL assoluti prima di parsare, e il contenuto risolto viene salvato nel DB — così tutti i re-parse successivi (schema endpoint, startup, reload) funzionano correttamente senza ri-fetching
- **RouteRegistry path guard**: ogni registrazione route è in try-catch; path con `:` nel segmento (es. `/{boundingbox}:{gridOption}/`) vengono saltati con WARN — Vert.x lancia `IllegalArgumentException` su questi pattern

---

## Decisioni di design

- **Persistenza spec**: H2 in file mode (`api-shadow.mv.db` nella working dir) via Hibernate ORM + Panache. Il contenuto YAML/JSON della spec è salvato come TEXT nel DB — no file su disco, no volume aggiuntivo.
- **Entity**: `SpecEntity extends PanacheEntity` — campi: `name` (univoco), `content` (TEXT), `profileContent` (TEXT, nullable), `semanticMode` (boolean), `active`, `createdAt`, `updatedAt`.
- **Path mock API**: `/api/<nome-spec>/<path-dalla-spec>` — il nome è scelto dall'utente all'upload. Parametri path OpenAPI `{id}` → Vert.x `:id` (conversione automatica in `RouteRegistry`).
- **EntityStore**: `ConcurrentHashMap<specName, Map<resourceName, LinkedHashMap<Long, Map<String,Object>>>>`. Dataset generato esplicitamente dall'utente (non allo startup). Svuotato a ogni reload, conservato al rename.
- **Generazione vs inferenza** — responsabilità separate:
  - `reseed` usa il profilo come sorgente di verità, non inferisce nulla durante la generazione
  - `fill-inferred` riempie gap nel profilo con suggerimenti del modello (putIfAbsent)
  - `rebuild-inferred` azzera i fields del profilo e re-inferisce tutto
  - Il toggle ⚡/✨ per spec controlla la qualità dell'inferenza (non della generazione)
- **FK detection**: euristica camelCase stem (`idPuntoPrelievo` → `puntiPrelievo`); fallback cosine similarity con embeddings se `semanticMode=true` e modello pronto (threshold 0.82).
- **Denormalized detection**: pattern `{prefix}{TargetBase}` — se il target ha un campo `{prefix}`, viene marcato come denormalizzato. Risolto dopo FK resolution nel seed.
- **Profilo auto-generato**: all'upload di una spec senza profilo, viene creato un profilo starter con locale, dataset (20/risorsa), relations, denormalized, e faker inferiti per ogni campo. Questo serve come punto di partenza per il profile editor.
- **Java**: versione 21 LTS.

---

## Stato attuale

- [x] Struttura package e classi skeleton
- [x] Dipendenze: swagger-parser, datafaker, Lombok, SQLite + Panache, Vert.x, Qute
- [x] `SpecParser` — implementato (parsing spec, estrazione endpoint e schema risposta; `setResolveFully(true)` critico per Swagger 2.x)
- [x] `RouteRegistry` — implementato (registrazione/deregistrazione route Vert.x con prefisso `/api/<nome>`, required query param validation, X-Mock-Force-Status)
- [x] `MockServerStartup` — implementato (ricarica route + profili dal DB all'avvio)
- [x] `SpecEntity` — Panache entity con `findByName` e `findAllActive`
- [x] `DataGenerator` — implementato (SchemaDispatcher, ProfileResolver, GenerationContext, tutti i tipi OpenAPI)
- [x] `SchemaDispatcher` — implementato (tutti i tipi: primitivi, oggetti, array, allOf/oneOf/anyOf, enum, pattern, format, depth guard)
- [x] `AdminResource` — implementato (upload file, upload da URL, lista, reload, delete, gestione profilo)
- [x] `MockProfileLoader` — integrato nel flusso upload/reload/delete/startup
- [x] `MockProfile` — latency, count, seed, forceStatus, optionalProbability, maxSchemaDepth, field overrides (faker/pattern/enum+distribution), fixtures static
- [x] `docker-compose.yml` con volume per H2
- [x] `docs/user-guide.md` + `docs/user-guide-en.md`
- [x] **Admin UI** — Alpine.js + Tailwind CSS single-page dashboard (`admin-ui/index.html`)
- [x] **002-profile-editor** — visual profile editor: `SchemaAnalyzer`, `FieldSchema`, `SpecSchemaDTO`, `GET /schema`, `GET|PUT /profile/json`, multi-view UI con spec detail, endpoint config, field overrides (faker/pattern/enum), faker catalog 26 voci
- [x] **003-semantic-faker-suggestions** — suggerimenti semantici multilingua: `EmbeddingEngine` (ONNX, paraphrase-multilingual-MiniLM-L12-v2), `FakerSuggestionEngine` (catalog 35 voci + cosine sim + threshold adattivo), `ModelDownloader` (download lazy al primo avvio), `FieldSchema` esteso con `suggestions`, `GET /schema` restituisce top-3 suggestion per campo, UI dropdown con sezione `✦ auto` e pre-selezione. Ottimizzazioni applicate: embedText arricchiti con sinonimi multilingua, catalogo esteso (geographic, finance, product), threshold adattivo (×0.70 pool ≤3, ×1.25 pool >10)
- [x] **Faker locale automatico** — `LocaleDetector` (lingua 1.2.2, 8 lingue EN/IT/DE/FR/ES/PT/NL/PL, CPU-only, low-accuracy mode) rileva la lingua dal contenuto grezzo della spec; `MockProfile.locale` per override manuale (`locale: it`); `DataGenerator` usa `Faker(Locale)` con cache per-locale; rilevamento attivo su upload, reload e startup.
- [x] **Semantic auto-generation** — `SchemaDispatcher.generateString()` usa `FakerSuggestionEngine` per campi stringa senza format/pattern: se il top-1 suggestion supera `mock.faker-suggestions.auto-generate-threshold=0.50`, applica automaticamente l'espressione faker corrispondente (es. `nome`→nome italiano, `provincia`→provincia italiana, `latitudine`→coordinata). I campi di dominio puro senza match nel catalogo (es. `metodo`, `incertezza`) cadono sul fallback `lorem().word()`.

- [x] **004-relational-entity-store** — `SpecResourceAnalyzer` (path analysis → ResourceGraph + FK detection + PaginationShape detection), `EntityStore` (dataset pre-generato in-memory, seed con timing log, lookup O(1) per ID, FK resolution, findByParent per sub-resource), `RouteRegistry` entity-aware routing (LIST/paginated, SINGLE/404, SUB_RESOURCE_LIST, POST/PUT/DELETE read-only), `MockProfile` esteso con sezioni `dataset` e `relations`, `MockEndpoint` con `PathType` enum + `resourceName`/`pathType` fields, nuovi model: `ResourceInfo`, `ResourceGraph`, `RelationDefinition`, `PaginationShape`.
- [x] **004-post-impl fixes** — fix cast `String→Map` in `EntityStore.seed()` (schemi non-oggetto wrappati in `{value:...}`); fix FK detection per plurali italiani camelCase (stem matching `puntoPrelievo↔puntiPrelievo`); fix `uploadSpecFromUrl` che non seedava; aggiunto `POST /admin/specs/{name}/reseed`.
- [x] **Lazy seeding + seeding semantico** — il seed non avviene più automaticamente al caricamento/avvio: l'utente sceglie esplicitamente. `SpecResourceAnalyzer.analyze(spec, profile, semanticFk)`: se `semanticFk=true` e modello pronto, pre-calcola embedding dei nomi risorsa e usa cosine similarity (threshold 0.82) come fallback dopo l'euristica. `EmbeddingEngine` espone `cosineSimilarity()` pubblica. `EntityStore.hasAnyDataset(specName)` per status badge. Admin UI: colonna "Dati" con badge `⚠ no data` / `✓ generati`, bottoni **↻ semantico** e **↻ veloce**; toast 503 se modello non pronto con suggerimento di usare ↻ veloce.
- [x] **Docker + Harbor deploy** — `Dockerfile.jvm` aggiornato a `ubi9/openjdk-21-runtime:1.20` + EXPOSE 8090 + ENV vars per volume path; `docker-compose.yml` con volumi `./data` (H2) e `./models` (ONNX, escluso dall'immagine); `build-image.sh` per build/tag/push a Harbor; `.dockerignore` whitelist che esclude spec-kit-files e file Claude automaticamente.
- [x] **Bug fix: toast URL upload vuoto** — fetch+json in try-catch in `uploadFromUrl()`; fallback chain `data.error ?? data.title ?? data.details ?? HTTP ${r.status}` per gestire sia il formato SmallRye che quello custom.
- [x] **Bug fix: RouteRegistry path invalidi** — ogni `router.route()` in try-catch individuale; path con `:` nel segmento (es. `/{boundingbox}:{gridOption}/`) saltati con WARN invece di far crashare tutta la registrazione.
- [x] **Bug fix: SchemaDispatcher $ref non risolti** — guard `schema.get$ref() != null` in cima a `dispatch()` restituisce example o `{}` invece di propagare; catch-all finale restituisce example o null invece di `"unknown"` letterale.
- [x] **Bug fix: FakerSuggestionEngine date expressions** — DataFaker 2.x ha rotto l'API date; aggiornate le 3 entry del catalogo a sintassi valida (`date.past '365','yyyy-MM-dd\\'T\\'HH:mm:ssXXX'`, ecc.); aggiunto try-catch in `applyFieldConfig()` per espressioni faker/pattern non valide dal profilo.
- [x] **Bug fix: SpecParser external $ref** — `fetchAndResolveContent(url)` pre-processa il contenuto convertendo `./foo.yaml` in URL assoluti; `uploadSpecFromUrl` salva il contenuto risolto nel DB; tutti i re-parse successivi funzionano senza ri-fetching. Fix anche per spec Meteomatics: endpoint con schema geo mostrano ora 10-14 campi invece di 0.

## Backlog

- [ ] **T031** — validazione manuale scenari `specs/002-profile-editor/quickstart.md` (6 scenari curl + browser)
- [ ] **Query param → dati coerenti** — i dati generati rispettano i filtri passati come query param (date range, ecc.) — feature futura, out of scope per ora
