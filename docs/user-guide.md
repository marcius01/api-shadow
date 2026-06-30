# api-shadow — Guida completa

Mock server che genera risposte REST realistiche a partire da spec OpenAPI/Swagger, con dati casuali configurabili per campo.

---

## Indice

1. [Avvio del server](#1-avvio-del-server)
2. [Caricare una spec](#2-caricare-una-spec)
3. [Gestire le spec caricate](#3-gestire-le-spec-caricate)
4. [Fare richieste mock](#4-fare-richieste-mock)
5. [Forzare un codice di errore](#5-forzare-un-codice-di-errore)
6. [MockProfile — configurazione completa](#6-mockprofile--configurazione-completa)
7. [application.properties — parametri globali](#7-applicationproperties--parametri-globali)
8. [Docker e persistenza](#8-docker-e-persistenza)
9. [Cosa c'è ancora da fare](#9-cosa-cè-ancora-da-fare)

---

## 1. Avvio del server

### Sviluppo locale (Quarkus dev mode)

```bash
./gradlew quarkusDev
```

Il server parte su due porte:
- **`:8080`** — Mock API + Admin API
- **`:8090`** — Quarkus management (health, metrics)

Verifica che sia su:
```bash
curl http://localhost:8080/admin/api/status
# → {"status":"ok","specs":0,"endpoints":0}
```

### Docker

```bash
# Build immagine
./gradlew build -Dquarkus.container-image.build=true

# Avvia con docker-compose (persistenza inclusa)
docker-compose up
```

---

## 2. Caricare una spec

### Via file YAML/JSON (senza profilo)

```bash
curl -X POST http://localhost:8080/admin/specs/upload \
  -F "name=myapi" \
  -F "file=@openapi.yaml"
```

Il campo `name` è il nome che scegli tu — viene usato come prefisso nell'URL mock.

Risposta:
```json
{
  "status": "created",
  "name": "myapi",
  "endpoints": 12,
  "profile": false
}
```

### Via file con MockProfile

```bash
curl -X POST http://localhost:8080/admin/specs/upload \
  -F "name=myapi" \
  -F "file=@openapi.yaml" \
  -F "profile=@mock-profile.yaml"
```

Il profilo è opzionale. Se caricato, viene salvato nel DB insieme alla spec e ricaricato automaticamente al riavvio del server.

### Da URL remoto

```bash
curl -X POST http://localhost:8080/admin/specs/upload-url \
  -H "Content-Type: application/json" \
  -d '{"name": "myapi", "url": "https://example.com/openapi.yaml"}'
```

Supporta qualsiasi URL raggiungibile che restituisce una spec OpenAPI/Swagger valida.

### Formati supportati

| Formato | Esempio |
|---------|---------|
| OpenAPI 3.x YAML | `openapi: "3.0.0"` |
| OpenAPI 3.x JSON | `{"openapi": "3.0.0", ...}` |
| Swagger 2.x YAML | `swagger: "2.0"` |
| Swagger 2.x JSON | `{"swagger": "2.0", ...}` |

### Cosa succede dopo il caricamento

1. `SpecParser` legge il file e risolve tutti i `$ref` interni
2. Vengono estratti tutti gli endpoint (path + metodo + schema risposta per ogni status code)
3. `RouteRegistry` registra una route Vert.x per ogni endpoint sotto `/api/<name>/<path>`
4. La spec viene salvata nel DB (H2) — sopravvive ai riavvii
5. Se era allegato un profilo, viene caricato in memoria per quella spec

---

## 3. Gestire le spec caricate

### Lista spec attive

```bash
curl http://localhost:8080/admin/specs
```

```json
[
  { "name": "myapi", "active": true, "createdAt": "2026-06-26T10:00:00", "updatedAt": "..." }
]
```

### Lista endpoint attivi

```bash
curl http://localhost:8080/admin/endpoints
# → {"myapi": 12}
```

### Aggiornare o rimuovere il profilo

Per aggiornare il profilo di una spec già caricata senza toccare la spec stessa:

```bash
# Carica o sostituisce il profilo
curl -X POST http://localhost:8080/admin/specs/myapi/profile \
  -F "profile=@mock-profile.yaml"

# Rimuove il profilo (torna alla generazione automatica)
curl -X POST http://localhost:8080/admin/specs/myapi/profile/delete
```

### Reload a caldo

Ricarica la spec dal DB senza riavviare il server (utile dopo un fix alla spec):

```bash
curl -X POST http://localhost:8080/admin/specs/myapi/reload
```

Il reload: deregistra le route vecchie → riparse la spec dal DB → registra le route nuove.
Se la spec aveva un profilo salvato, viene ricaricato anche quello.

### Delete spec

Deregistra le route e segna la spec come inattiva nel DB:

```bash
curl -X POST http://localhost:8080/admin/specs/myapi/delete
```

---

## 4. Fare richieste mock

### Pattern URL

```
http://localhost:8080/api/<nome-spec>/<path-dalla-spec>
```

Esempio: spec caricata come `shop`, endpoint `/orders`:
```bash
curl http://localhost:8080/api/shop/orders
```

I parametri path OpenAPI `{id}` vengono convertiti automaticamente in `:id` di Vert.x:
```bash
# spec ha GET /orders/{id}
curl http://localhost:8080/api/shop/orders/42
```

### Cosa genera il server

Per ogni chiamata il server:
1. Risolve lo schema della risposta per il codice di default (il più basso 2xx nella spec)
2. Genera dati casuali rispettando il tipo OpenAPI di ogni campo
3. Restituisce il JSON con `Content-Type: application/json`

Ogni campo opzionale (non in `required:`) ha il 70% di probabilità di essere incluso.
Puoi portare la probabilità al 100% via profilo o `application.properties`.

### Parametri query required

Se la spec dichiara un query parameter come `required: true` e non viene passato,
il server risponde:

```json
HTTP 400
{"error": "missing required query parameter(s): dataInizio, dataFine"}
```

---

## 5. Forzare un codice di errore

### Via header HTTP (per-request)

Aggiungi l'header `X-Mock-Force-Status` alla richiesta. Il server risponde con
quel codice e lo schema definito nella spec per quel codice (se esiste).

```bash
# Forza risposta 404
curl -H "X-Mock-Force-Status: 404" http://localhost:8080/api/shop/orders/42

# Forza risposta 500
curl -H "X-Mock-Force-Status: 500" http://localhost:8080/api/shop/orders/42
```

Se lo status code richiesto non è definito nella spec:
```json
HTTP 404
{"error": "status 404 not defined in spec"}
```

### Via MockProfile (permanente per quell'endpoint)

Nel profilo, imposta `forceStatus` nell'override dell'endpoint:
```yaml
overrides:
  /orders/{id}:
    GET:
      forceStatus: 503
```

### Priorità

```
X-Mock-Force-Status header  →  vince sempre
  ↓ (se assente)
forceStatus nel profilo     →  vince sul default
  ↓ (se assente)
default della spec          →  il 2xx più basso definito
```

---

## 6. MockProfile — configurazione completa

Il profilo è un file YAML opzionale che puoi caricare insieme alla spec.
Permette di controllare precisamente il comportamento per ogni endpoint.

### Struttura completa

```yaml
# ── SEZIONE OVERRIDES ─────────────────────────────────────────────
# Modifica comportamento per endpoint specifici.
# Il path deve corrispondere ESATTAMENTE a come appare nella spec OpenAPI.
overrides:

  /orders:
    GET:
      # Ritardo simulato prima di rispondere (in ms)
      latency: 200ms

      # Numero di elementi nell'array della risposta
      count: 5

      # Seed per risultati riproducibili — stessa risposta ad ogni chiamata
      seed: 42

      # Forza sempre questo status code per questo endpoint (vedi sezione 5)
      forceStatus: 503

      # Probabilità di includere i campi opzionali (0.0 = mai, 1.0 = sempre)
      # Sovrascrive il valore globale in application.properties
      optionalProbability: 1.0

      # Limite di profondità ricorsiva per questo endpoint
      # Utile se la spec ha nesting molto profondo
      maxSchemaDepth: 15

      # Overrides per campo specifico
      fields:
        status:
          # Scegli da un insieme fisso di valori con pesi
          enum: [PENDING, CONFIRMED, SHIPPED, CANCELLED]
          distribution: [30, 40, 20, 10]

        city:
          # Usa un'espressione DataFaker
          faker: "Address.city"

        orderCode:
          # Genera valore che rispetta questa regex
          pattern: "[A-Z]{3}-[0-9]{4}"

  /orders/{id}:
    GET:
      fields:
        customerLastName:
          faker: "Name.lastName"
        trackingCode:
          pattern: "[A-Z]{2}[0-9]{9}[A-Z]{2}"


# ── SEZIONE FIXTURES ──────────────────────────────────────────────
# Risposta SEMPRE fissa — ignora completamente la generazione.
# Utile per endpoint di autenticazione o dati di riferimento fissi.
fixtures:

  /auth/token:
    POST:
      static:
        access_token: "dev-token-abc123"
        token_type: "Bearer"
        expires_in: 3600

  /config:
    GET:
      static:
        environment: "MOCK"
        version: "1.0.0"
        features: [ORDERS, PRODUCTS, INVOICES]
```

### Espressioni faker disponibili

Qualsiasi provider DataFaker nella forma `"Category.method"`:

| Espressione | Esempio output |
|-------------|----------------|
| `"Name.fullName"` | `"Mario Rossi"` |
| `"Name.lastName"` | `"Bianchi"` |
| `"Address.city"` | `"Milano"` |
| `"Address.streetAddress"` | `"Via Roma 42"` |
| `"Internet.emailAddress"` | `"mario@example.com"` |
| `"Internet.ipV4Address"` | `"192.168.1.1"` |
| `"PhoneNumber.phoneNumber"` | `"+39 02 1234567"` |
| `"Lorem.word"` | `"accusantium"` |
| `"Lorem.sentence"` | `"Lorem ipsum dolor..."` |
| `"Number.numberBetween '1','100'"` | `"57"` |
| `"Date.birthday"` | `"1985-03-14"` |

Lista completa: https://www.datafaker.net/documentation/providers/

### Priorità di applicazione

```
1. fixtures.static        → risposta fissa, nessuna generazione
2. forceStatus            → determina il codice HTTP di risposta
3. latency                → aspetta N millisecondi
4. seed                   → Faker riproducibile per questa request
5. optionalProbability    → percentuale campi opzionali inclusi
6. maxSchemaDepth         → limite profondità ricorsione
7. fields.*               → override per singolo campo
8. schema OpenAPI         → generazione automatica del resto
```

---

## 7. application.properties — parametri globali

```properties
# Porta Mock API e Admin API
quarkus.http.port=8080

# Porta Quarkus management (health, metrics) — NON la Admin UI
quarkus.management.port=8090
quarkus.management.enabled=true

# Database H2 file-mode — persistenza spec tra riavvii
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:file:./api-shadow;AUTO_SERVER=TRUE
quarkus.hibernate-orm.database.generation=update

# ── Parametri generazione dati ────────────────────────────────────

# Probabilità di includere un campo opzionale (non in required:) nella risposta
# 0.70 = 70% dei campi opzionali vengono inclusi, 30% sono omessi
# Imposta 1.0 per includere sempre tutti i campi
mock.optional-field-probability=0.70

# Profondità massima di ricorsione per schemi annidati
# Un valore troppo basso causa null per i campi nei livelli profondi
# La catena tipica: ResponseWrapper → array → Object → array → Object → campo
# richiede almeno depth=6, quindi 10 è il valore consigliato
mock.max-schema-depth=10

# Numero di elementi generati negli array (quando non specificato nel profilo)
mock.default-array-count=3
```

---

## 8. Docker e persistenza

### Cosa viene persistito

Il DB H2 (`api-shadow.mv.db`) contiene:
- Il contenuto YAML/JSON di ogni spec caricata
- Il contenuto del MockProfile associato (se caricato)
- Lo stato attivo/inattivo di ogni spec

**Non** vengono salvati i file originali su disco — tutto è nel DB.

### Comportamento con Docker

| Operazione | Dati spec |
|------------|-----------|
| `docker stop` → `docker start` | **Preservati** — container non rimosso |
| `docker-compose down` → `up` | **Persi** — container rimosso, filesystem resettato |
| `docker-compose down -v` | **Persi** — volumi esplicitamente rimossi |

### Configurazione volume (docker-compose.yml)

```yaml
services:
  api-shadow:
    image: api-shadow:latest
    ports:
      - "8080:8080"
      - "8090:8090"
    volumes:
      - ./data:/work/data        # DB H2 — spec persistenti
      - ./specs:/work/specs      # spec files (opzionale)
      - ./profiles:/work/profiles # profili (opzionale)
    environment:
      QUARKUS_DATASOURCE_JDBC_URL: jdbc:h2:file:/work/data/api-shadow;AUTO_SERVER=TRUE
```

Con questo compose, `docker-compose down` + `docker-compose up` ricarica tutte
le spec automaticamente perché il DB sopravvive nel volume `./data/`.

---

## 9. Cosa c'è ancora da fare

### Funzionalità completate

- Parsing spec OpenAPI 3.x e Swagger 2.x (YAML e JSON)
- Generazione dati da schema: tutti i tipi primitivi, oggetti, array, allOf/oneOf/anyOf, enum, pattern, format
- Registrazione/deregistrazione/reload route Vert.x a caldo
- Persistenza spec nel DB H2 con ricarica all'avvio
- Admin API: upload, list, reload, delete, status
- MockProfile: latency, count, seed, forceStatus, optionalProbability, maxSchemaDepth, field overrides, fixtures

### Da implementare

| Feature | Nota |
|---------|------|
| Admin UI HTML | Al momento l'Admin è solo JSON API — nessuna interfaccia web |
| Upload spec da URL remoto | `SpecParser.parseFromLocation()` è pronta, manca l'endpoint Admin |
| Aggiornamento profilo su spec esistente | Oggi per aggiornare il profilo bisogna eliminare e ricaricare la spec |
| Query param → dati coerenti | Date range, filtri: i dati generati rispettano i valori passati come query param |
