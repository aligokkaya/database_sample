# TASK 2 – Java Spring Boot Conversion

## How I Used Claude to Convert the Python FastAPI Project to Java Spring Boot

This document describes the process I followed to convert the existing Python FastAPI application to a Java 17 / Spring Boot 3.2.3 service, using Claude as my primary AI assistant throughout.

---

## Overview

The original project is a FastAPI application that:
- Authenticates users via a static username/password and issues JWT tokens
- Connects to arbitrary PostgreSQL databases, introspects the public schema, and stores the results
- Classifies individual database columns for PII using an OpenAI-compatible LLM
- Stores everything in a PostgreSQL database managed by Alembic migrations

My goal was to replicate every feature faithfully in Java while keeping the same REST contract, environment variable names, and database schema.

---

## Step 1 – Understanding the existing codebase

Before writing a single line of Java, I asked Claude to help me map the Python project structure to its Spring Boot equivalents.

**Prompt I used:**

> I have a Python FastAPI project with the following files: `app/main.py`, `app/auth/router.py`, `app/metadata/router.py`, `app/metadata/service.py`, `app/classify/service.py`, and `app/models.py`. I want to convert it to Java 17 + Spring Boot 3.2.3. Can you give me a file-by-file mapping of what each Python file becomes in the Java world, and list all the dependencies I'll need in `pom.xml`?

Claude produced a clear table:

| Python file | Java equivalent |
|---|---|
| `app/main.py` | `DiscoveryApplication.java` + `OpenApiConfig.java` |
| `app/auth/router.py` | `AuthController.java` + `AuthService.java` |
| `app/metadata/router.py` | `MetadataController.java` |
| `app/metadata/service.py` | `MetadataService.java` |
| `app/classify/service.py` | `ClassifyService.java` |
| `app/classify/router.py` | `ClassifyController.java` |
| `app/models.py` | Four `@Entity` classes + four JPA repositories |
| Pydantic schemas | DTO classes under `dto/` |

It also recommended the exact dependency list: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-security`, `spring-boot-starter-validation`, `postgresql`, `flyway-core`, `flyway-database-postgresql`, `springdoc-openapi-starter-webmvc-ui`, and the three `jjwt` artifacts (`jjwt-api`, `jjwt-impl`, `jjwt-jackson`) at version `0.12.3`.

---

## Step 2 – Setting up the project skeleton and pom.xml

**Prompt:**

> Generate a complete `pom.xml` for a Spring Boot 3.2.3 project using Java 17, with these dependencies: [list from step 1]. Group ID `com.kafein`, artifact ID `discovery`, version `1.0.0`.

Claude generated the full `pom.xml`. I reviewed the version numbers – the `jjwt` version needed to be pinned to `0.12.3` explicitly because Spring Boot's BOM does not manage it.

---

## Step 3 – JPA entities and Flyway migration

I pasted `app/models.py` into the conversation:

**Prompt:**

> Here is my SQLAlchemy model file. Convert each model to a JPA `@Entity` class. Use `UUID` primary keys with `@GeneratedValue(strategy = GenerationType.UUID)`. Map all relationships (`@OneToMany`, `@ManyToOne`, `@OneToOne`) with the correct cascade settings. Also generate the matching Flyway `V1__init.sql` from the SQLAlchemy table definitions.

Claude produced `MetadataRecord.java`, `DbConnection.java`, `TableInfo.java`, `ColumnInfo.java`, and the `V1__init.sql`. I noticed it initially used `GenerationType.AUTO` for the UUID, which doesn't work cleanly with PostgreSQL's `uuid-ossp`. I prompted:

> The `@GeneratedValue` for UUID primary keys should use `GenerationType.UUID` (Hibernate 6 feature) and the SQL should use `uuid_generate_v4()` requiring the `uuid-ossp` extension.

Claude revised both the entities and the SQL accordingly.

---

## Step 4 – JPA repositories

**Prompt:**

> Generate the four Spring Data JPA repository interfaces for `MetadataRecord`, `DbConnection`, `TableInfo`, and `ColumnInfo`. Include a derived query method to find all metadata records ordered by `created_at` descending, a `@Query` that counts tables grouped by `metadata_id`, and finder methods for foreign-key lookups.

The generated code was correct. The only thing I added manually was the `findByTableIdOrderByOrdinalPositionAsc` method on `ColumnInfoRepository` since Claude initially omitted it.

---

## Step 5 – Security layer: JWT with JJWT 0.12.x

This was the trickiest part because JJWT 0.12.x changed its API significantly compared to 0.11.x (many tutorials still show the old API).

**Prompt:**

> I need to implement JWT generation and validation using JJWT 0.12.3. Show me `JwtUtil.java` that generates a signed HS256 token for a given username with a configurable expiry, and validates/parses tokens. The secret can be an arbitrary string so derive a 256-bit key from it using SHA-256.

Claude generated the `JwtUtil` using the new fluent API: `Jwts.builder().subject(...).issuedAt(...).expiration(...).signWith(key).compact()` and `Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload()`. This was exactly right for 0.12.x and would have been wrong syntax for 0.11.x.

Then I asked:

> Now generate `JwtAuthFilter.java` – a Spring Security `OncePerRequestFilter` that extracts the `Authorization: Bearer <token>` header, validates it with `JwtUtil`, and populates the `SecurityContextHolder`.

**Prompt for SecurityConfig:**

> Generate `SecurityConfig.java` that: disables CSRF, uses stateless session management, registers the `JwtAuthFilter` before `UsernamePasswordAuthenticationFilter`, allows unauthenticated access to `POST /auth`, `GET /health`, and all Swagger endpoints, and requires authentication for everything else.

---

## Step 6 – AES password encryption

The Python service uses `cryptography.fernet` for AES encryption. Fernet isn't available in Java so I needed an equivalent.

**Prompt:**

> The Python service encrypts DB passwords with `cryptography.Fernet`. I need a Java equivalent that: derives a 256-bit AES key from an arbitrary string using SHA-256, encrypts with AES/CBC/PKCS5Padding, prepends the 16-byte IV to the ciphertext, and Base64-encodes the result. Implement `encryptPassword` and `decryptPassword` in `MetadataService.java`.

Claude produced exactly that approach. I kept the Fernet note in a comment to make clear the stored values are **not** compatible with the Python service (different format), which would matter for a migration scenario.

---

## Step 7 – Metadata service and JDBC introspection

**Prompt:**

> Convert `app/metadata/service.py` to a Java `@Service` class. Replace `psycopg2` with JDBC (`DriverManager.getConnection`). The service must: connect to the target database, query `information_schema.columns` for the public schema, persist `MetadataRecord`, `DbConnection`, `TableInfo`, and `ColumnInfo`, and return a `ConnectResponse` DTO. Also implement `listMetadata`, `getMetadataDetail`, and `deleteMetadata`.

Claude's initial version used `@Autowired` constructor injection, which I asked it to change to explicit constructor injection to keep things consistent.

---

## Step 8 – Classify service and LLM integration

**Prompt:**

> Convert `app/classify/service.py` to `ClassifyService.java`. Use `RestTemplate` instead of the OpenAI Python SDK to call the chat completions endpoint. The system prompt must start with `/no_think` and list all 13 PII categories. Implement robust JSON extraction that handles: direct parse, markdown code fences (```json ... ```), and bare `{...}` blocks. Also strip `<think>...</think>` tags before parsing (DeepSeek-R1 emits these). Normalise probabilities to sum exactly to 1.0.

The first draft was missing the think-tag stripping. I followed up:

> The `stripThinkTags` method needs to handle multi-line think blocks using `(?s)` (DOTALL) mode in the regex, and fall back to the original text if stripping leaves an empty string.

Claude fixed it immediately. I also asked for the `response_format: json_object` to only be sent to official OpenAI endpoints (not local Ollama) to avoid a 400 error from Ollama – Claude added the `openai.com` URL check.

---

## Step 9 – Controllers and exception handling

**Prompt:**

> Generate `AuthController.java`, `MetadataController.java`, `ClassifyController.java`, and `HealthController.java` using Spring MVC `@RestController`. Map the same paths as the FastAPI routes. Add SpringDoc `@Operation` and `@ApiResponse` annotations for Swagger docs. All protected controllers should declare `@SecurityRequirement(name = "BearerAuth")`.

**Prompt for exception handling:**

> Generate a `@RestControllerAdvice` class `GlobalExceptionHandler` that maps `ResponseStatusException`, `EntityNotFoundException`, `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `IllegalArgumentException`, and a fallback `Exception` handler. Return a consistent JSON body with `status`, `error`, `message`, and `timestamp` fields.

---

## Step 10 – application.properties and Dockerfile

**Prompt:**

> Generate `application.properties` that reads all env vars: `DATABASE_URL`, `JWT_SECRET_KEY`, `JWT_EXPIRY_HOURS`, `BASIC_AUTH_USERNAME`, `BASIC_AUTH_PASSWORD`, `ENCRYPTION_KEY`, `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_MODEL`. Use Spring's `${VAR:default}` syntax for optional ones. Enable Flyway, set `ddl-auto=validate`, configure SpringDoc to serve Swagger at `/swagger-ui.html`.

**Prompt for Dockerfile:**

> Generate a multi-stage Dockerfile for this Spring Boot project. Stage 1: Maven build using `maven:3.9.6-eclipse-temurin-17`. Stage 2: Runtime using `eclipse-temurin:17-jre-jammy`. Create a non-root user. Add JVM container-awareness flags (`-XX:+UseContainerSupport`, `-XX:MaxRAMPercentage=75.0`).

---

## Step 11 – Review and corrections

After assembling all files I did a final review pass with Claude:

**Prompt:**

> Review this Spring Boot project for these specific issues: (1) Is there a circular dependency between any beans? (2) Does the `ClassifyService` have all necessary constructor args injected? (3) Is `ObjectMapper` declared as a `@Bean` so it can be injected? (4) Are all Swagger security schemes properly registered?

Claude flagged that `ObjectMapper` was not declared as a `@Bean` in `AppConfig` – the Spring Boot auto-configured one wouldn't be injected by constructor, it needed to be explicit. I added it. It also suggested adding `OpenApiConfig.java` with the `@SecurityScheme` annotation for `BearerAuth`, which I had missed.

---

## Key Decisions and Tradeoffs

| Decision | Rationale |
|---|---|
| AES/CBC instead of Fernet | Fernet is a Python library; AES/CBC with prepended IV gives equivalent security and is available in the Java standard library |
| SHA-256 key derivation for both JWT and AES | Allows arbitrary-length env var secrets without requiring the user to pre-generate a 32-byte key |
| `RestTemplate` for LLM calls instead of an OpenAI SDK | Keeps the dependency list small; the OpenAI chat completions endpoint is a simple JSON POST |
| `GenerationType.UUID` (Hibernate 6) | Cleaner than using a `@PrePersist` hook with `UUID.randomUUID()` |
| Flyway `validate` mode | Prevents accidental schema drift; schema is defined once in `V1__init.sql` |
| Think-tag stripping | DeepSeek-R1 and Qwen-QwQ emit `<think>...</think>` blocks before the actual answer when not suppressed; the `/no_think` prefix helps but is not universally supported |

---

## Time Breakdown

| Activity | Approximate time |
|---|---|
| Initial planning and mapping Python → Java with Claude | 20 min |
| Generating and reviewing pom.xml, entities, repositories | 25 min |
| JWT security layer (JwtUtil, JwtAuthFilter, SecurityConfig) | 30 min |
| MetadataService (AES encryption, JDBC introspection, CRUD) | 30 min |
| ClassifyService (LLM call, JSON extraction, normalisation) | 35 min |
| Controllers, exception handler, OpenAPI config | 20 min |
| Dockerfile and application.properties | 15 min |
| Final review, corrections, documentation | 25 min |
| **Total** | **~3 hours** |

Without AI assistance this conversion would have taken roughly two full working days (schema + boilerplate alone tends to consume most of the time). Using Claude reduced it to a focused three-hour session, with the main value being: instant generation of repetitive boilerplate (entities, DTOs, repositories), correct JJWT 0.12.x API usage without consulting changelogs, and rapid iteration on the LLM response parser.
