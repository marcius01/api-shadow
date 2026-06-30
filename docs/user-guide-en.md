# api-shadow — Complete User Guide

Mock server that generates realistic REST responses from OpenAPI/Swagger specs, with per-field configurable fake data.

---

## Table of Contents

1. [Starting the server](#1-starting-the-server)
2. [Loading a spec](#2-loading-a-spec)
3. [Managing loaded specs](#3-managing-loaded-specs)
4. [Making mock requests](#4-making-mock-requests)
5. [Forcing an error status code](#5-forcing-an-error-status-code)
6. [MockProfile — full configuration reference](#6-mockprofile--full-configuration-reference)
7. [application.properties — global settings](#7-applicationproperties--global-settings)
8. [Docker and persistence](#8-docker-and-persistence)
9. [Roadmap](#9-roadmap)

---

## 1. Starting the server

### Local development (Quarkus dev mode)

```bash
./gradlew quarkusDev
```

The server starts on two ports:
- **`:8080`** — Mock API + Admin API
- **`:8090`** — Quarkus management (health, metrics)

Verify it is running:
```bash
curl http://localhost:8080/admin/api/status
# → {"status":"ok","specs":0,"endpoints":0}
```

### Docker

```bash
# Build the image
./gradlew build -Dquarkus.container-image.build=true

# Start with docker-compose (persistence included)
docker-compose up
```

---

## 2. Loading a spec

### From a YAML/JSON file (without profile)

```bash
curl -X POST http://localhost:8080/admin/specs/upload \
  -F "name=myapi" \
  -F "file=@openapi.yaml"
```

The `name` field is chosen by you — it becomes the URL prefix for all mock endpoints.

Response:
```json
{
  "status": "created",
  "name": "myapi",
  "endpoints": 12,
  "profile": false
}
```

### From a file with a MockProfile

```bash
curl -X POST http://localhost:8080/admin/specs/upload \
  -F "name=myapi" \
  -F "file=@openapi.yaml" \
  -F "profile=@mock-profile.yaml"
```

The profile is optional. When provided, it is saved in the DB alongside the spec and reloaded automatically on server restart.

### From a remote URL

```bash
curl -X POST http://localhost:8080/admin/specs/upload-url \
  -H "Content-Type: application/json" \
  -d '{"name": "myapi", "url": "https://example.com/openapi.yaml"}'
```

Supports any reachable URL returning a valid OpenAPI/Swagger spec.

### Supported formats

| Format | Example |
|--------|---------|
| OpenAPI 3.x YAML | `openapi: "3.0.0"` |
| OpenAPI 3.x JSON | `{"openapi": "3.0.0", ...}` |
| Swagger 2.x YAML | `swagger: "2.0"` |
| Swagger 2.x JSON | `{"swagger": "2.0", ...}` |

### What happens after upload

1. `SpecParser` reads the file and resolves all internal `$ref` references
2. All endpoints are extracted (path + method + response schema per status code)
3. `RouteRegistry` registers a Vert.x route for each endpoint under `/api/<name>/<path>`
4. The spec is saved to the H2 DB — it survives server restarts
5. If a profile was attached, it is loaded into memory for that spec

---

## 3. Managing loaded specs

### List active specs

```bash
curl http://localhost:8080/admin/specs
```

```json
[
  { "name": "myapi", "active": true, "createdAt": "2026-06-26T10:00:00", "updatedAt": "..." }
]
```

### List active endpoints

```bash
curl http://localhost:8080/admin/endpoints
# → {"myapi": 12}
```

### Updating or removing the profile

To update the profile of an already-loaded spec without touching the spec itself:

```bash
# Upload or replace the profile
curl -X POST http://localhost:8080/admin/specs/myapi/profile \
  -F "profile=@mock-profile.yaml"

# Remove the profile (reverts to automatic generation)
curl -X POST http://localhost:8080/admin/specs/myapi/profile/delete
```

### Hot reload

Reloads the spec from the DB without restarting the server (useful after updating the spec):

```bash
curl -X POST http://localhost:8080/admin/specs/myapi/reload
```

Reload sequence: unregister old routes → re-parse spec from DB → register new routes.
If the spec had a saved profile, it is also reloaded.

### Delete a spec

Unregisters the routes and marks the spec as inactive in the DB:

```bash
curl -X POST http://localhost:8080/admin/specs/myapi/delete
```

---

## 4. Making mock requests

### URL pattern

```
http://localhost:8080/api/<spec-name>/<path-from-spec>
```

Example: spec loaded as `shop`, endpoint `/orders`:
```bash
curl http://localhost:8080/api/shop/orders
```

OpenAPI path parameters `{id}` are automatically converted to Vert.x `:id` syntax:
```bash
# spec defines GET /orders/{id}
curl http://localhost:8080/api/shop/orders/42
```

### What the server generates

For each request the server:
1. Resolves the response schema for the default status code (lowest 2xx defined in the spec)
2. Generates random data respecting the OpenAPI type of each field
3. Returns the JSON body with `Content-Type: application/json`

Each optional field (not listed in `required:`) has a 70% chance of being included.
You can raise this to 100% via the profile or `application.properties`.

### Required query parameters

If the spec declares a query parameter as `required: true` and it is missing from the request, the server responds:

```json
HTTP 400
{"error": "missing required query parameter(s): startDate, endDate"}
```

---

## 5. Forcing an error status code

### Via HTTP header (per-request)

Add the `X-Mock-Force-Status` header to the request. The server responds with that status code and the schema defined in the spec for that code (if it exists).

```bash
# Force a 404 response
curl -H "X-Mock-Force-Status: 404" http://localhost:8080/api/shop/orders/42

# Force a 500 response
curl -H "X-Mock-Force-Status: 500" http://localhost:8080/api/shop/orders/42
```

If the requested status code is not defined in the spec:
```json
HTTP 404
{"error": "status 404 not defined in spec"}
```

### Via MockProfile (permanent for that endpoint)

In the profile, set `forceStatus` in the endpoint override:
```yaml
overrides:
  /orders/{id}:
    GET:
      forceStatus: 503
```

### Priority

```
X-Mock-Force-Status header  →  always wins
  ↓ (if absent)
forceStatus in profile      →  overrides spec default
  ↓ (if absent)
spec default                →  lowest 2xx defined in the spec
```

---

## 6. MockProfile — full configuration reference

The profile is an optional YAML file you can upload alongside the spec.
It lets you precisely control the server behaviour per endpoint.

### Full structure

```yaml
# ── OVERRIDES SECTION ─────────────────────────────────────────────
# Override behaviour for specific endpoints.
# The path must match EXACTLY as it appears in the OpenAPI spec.
overrides:

  /orders:
    GET:
      # Simulated delay before responding (in ms)
      latency: 200ms

      # Number of items in the array response
      count: 5

      # Seed for reproducible results — same response on every call
      seed: 42

      # Always force this status code for this endpoint (see section 5)
      forceStatus: 503

      # Probability of including optional fields (0.0 = never, 1.0 = always)
      # Overrides the global value in application.properties
      optionalProbability: 1.0

      # Recursion depth limit for this endpoint
      # Increase if the spec has deeply nested schemas
      maxSchemaDepth: 15

      # Per-field overrides
      fields:
        status:
          # Pick from a fixed set of values with weighted probability
          enum: [PENDING, CONFIRMED, SHIPPED, CANCELLED]
          distribution: [30, 40, 20, 10]

        city:
          # Use a DataFaker expression
          faker: "Address.city"

        orderCode:
          # Generate a value matching this regex
          pattern: "[A-Z]{3}-[0-9]{4}"

  /orders/{id}:
    GET:
      fields:
        customerLastName:
          faker: "Name.lastName"
        trackingCode:
          pattern: "[A-Z]{2}[0-9]{9}[A-Z]{2}"


# ── FIXTURES SECTION ──────────────────────────────────────────────
# ALWAYS return a fixed response — skips data generation entirely.
# Useful for auth endpoints or static reference data.
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

### Available faker expressions

Any DataFaker provider in the form `"Category.method"`:

| Expression | Example output |
|------------|----------------|
| `"Name.fullName"` | `"John Smith"` |
| `"Name.lastName"` | `"Johnson"` |
| `"Address.city"` | `"New York"` |
| `"Address.streetAddress"` | `"123 Main St"` |
| `"Internet.emailAddress"` | `"john@example.com"` |
| `"Internet.ipV4Address"` | `"192.168.1.1"` |
| `"PhoneNumber.phoneNumber"` | `"+1 555-0123"` |
| `"Lorem.word"` | `"accusantium"` |
| `"Lorem.sentence"` | `"Lorem ipsum dolor..."` |
| `"Number.numberBetween '1','100'"` | `"57"` |
| `"Date.birthday"` | `"1985-03-14"` |

Full list: https://www.datafaker.net/documentation/providers/

### Application order

```
1. fixtures.static        → fixed response, no generation
2. forceStatus            → determines the HTTP response status code
3. latency                → waits N milliseconds
4. seed                   → reproducible Faker instance for this request
5. optionalProbability    → fraction of optional fields to include
6. maxSchemaDepth         → recursion depth limit
7. fields.*               → per-field overrides
8. OpenAPI schema         → automatic generation for everything else
```

---

## 7. application.properties — global settings

```properties
# Mock API and Admin API port
quarkus.http.port=8080

# Quarkus management port (health, metrics) — NOT the Admin API
quarkus.management.port=8090
quarkus.management.enabled=true

# H2 file-mode database — specs persist across restarts
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:file:./api-shadow;AUTO_SERVER=TRUE
quarkus.hibernate-orm.database.generation=update

# ── Data generation settings ──────────────────────────────────────

# Probability of including an optional field (not in required:) in the response.
# 0.70 = 70% of optional fields are included, 30% are omitted.
# Set to 1.0 to always include every field.
mock.optional-field-probability=0.70

# Maximum recursion depth for nested schemas.
# A value too low causes null values for fields in deep nesting levels.
# Typical chain: ResponseWrapper → array → Object → array → Object → field
# requires at least depth 6, so 10 is the recommended value.
mock.max-schema-depth=10

# Number of items generated in arrays (when not specified in the profile)
mock.default-array-count=3
```

---

## 8. Docker and persistence

### What is persisted

The H2 DB (`api-shadow.mv.db`) stores:
- The YAML/JSON content of every loaded spec
- The associated MockProfile content (if one was uploaded)
- The active/inactive state of every spec

The original files are **not** kept on disk — everything lives in the DB.

### Docker behaviour

| Operation | Spec data |
|-----------|-----------|
| `docker stop` → `docker start` | **Preserved** — container not removed |
| `docker-compose down` → `up` | **Lost** — container removed, filesystem reset |
| `docker-compose down -v` | **Lost** — volumes explicitly removed |

### Volume configuration (docker-compose.yml)

```yaml
services:
  api-shadow:
    image: api-shadow:latest
    ports:
      - "8080:8080"
      - "8090:8090"
    volumes:
      - ./data:/work/data         # H2 DB — persistent specs
      - ./specs:/work/specs       # spec files (optional)
      - ./profiles:/work/profiles # profiles (optional)
    environment:
      QUARKUS_DATASOURCE_JDBC_URL: jdbc:h2:file:/work/data/api-shadow;AUTO_SERVER=TRUE
```

With this setup, `docker-compose down` + `docker-compose up` reloads all specs automatically because the DB survives in the `./data/` volume.

---

## 9. Roadmap

### Implemented

- OpenAPI 3.x and Swagger 2.x parsing (YAML and JSON)
- Data generation from schema: all primitive types, objects, arrays, allOf/oneOf/anyOf, enum, pattern, format
- Vert.x route registration/deregistration/hot-reload
- Spec persistence in H2 DB with reload on startup
- Admin API: upload, list, reload, delete, status
- MockProfile: latency, count, seed, forceStatus, optionalProbability, maxSchemaDepth, field overrides, fixtures

### Planned

| Feature | Notes |
|---------|-------|
| Admin UI | Currently Admin is a JSON-only API — no web interface yet |
| Load spec from remote URL | `SpecParser.parseFromLocation()` is ready; Admin endpoint missing |
| Update profile on existing spec | Today you must delete and re-upload to change the profile |
| Query param → coherent data | Date ranges, filters: generated data should respect values passed as query params |
