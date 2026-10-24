# AGENTS.md

## WHY

NextTurn is a multi-tenant platform engineered for online appointment scheduling, digital ticketing, and real-time virtual queue orchestration. Multi-tenancy is handled via embeddable widgets, not subdomains. This backend provides resilient REST APIs and real-time event distribution across service counters, clinics, and guest tracking views.

## Tech Stack

- Java 25 (LTS) & Spring Boot 4.x.
- Maven (`mvnw`) for dependency management and builds.
- Spring Data JPA & Hibernate with MySQL (runtime) and H2 (testing).
- Server-Sent Events (SSE) for real-time virtual queue event streaming.
- Valkey 8.0 for in-memory caching and real-time state management.
- Jakarta Validation (`spring-boot-starter-validation`) for incoming payload validation.
- Lombok for cleaner data models and reduced boilerplate.
- Layout specified in [agent-docs/folder_structure.md](./agent-docs/folder_structure.md) as the working standard.

## WHAT

Deliver secure, low-latency APIs and real-time queues:
- Manage multi-tenant appointments and queue ticket lifecycles (Call, Hold, Serve, Skip, Transfer).
- Broadcast queue position updates and wait estimates over Server-Sent Events (SSE).
- Wrap all REST responses uniformly using `ApiResponse<T>`.
- Handle errors centrally through `GlobalExceptionHandler` and `AppException`.

## HOW

Strictly adhere to the package structure defined in [agent-docs/folder_structure.md](./agent-docs/folder_structure.md) under `src/main/java/com/nextturn/`:

- `config/` - `@Configuration` and `@Bean` definitions (CORS, security, async).
- `constant/` - Static immutable constants (API route prefixes, default pagination).
- `controller/` - `@RestController` endpoints; validate requests and return `ResponseEntity<ApiResponse<T>>`.
- `dto/` - Request and response payload transfer objects.
- `entity/` - JPA entities mapped to database tables.
- `enums/` - Status, queue state, and role enumerations.
- `event/` - Spring `ApplicationEvent` classes for decoupled pub/sub.
- `exception/` - Custom domain exceptions and `@RestControllerAdvice` global handlers.
- `helper/` - Specialized helper beans and URL generators bridging services with external protocols.
- `infrastructure/` - Low-level infrastructure adapters and cross-cutting frameworks (`multitenancy/`, `security/`, `valkey/`).
- `mapper/` - Entity ↔ DTO transformation logic.
- `repository/` - Spring Data JPA interfaces extending `JpaRepository`.
- `scheduler/` - `@Scheduled` cron jobs and background queue cleanup tasks.
- `service/` - Business logic orchestration across repositories and services.
- `util/` - Pure, stateless helper functions.
- `resources/application.yml` - Environment properties; Flyway SQL migrations go into `db/migration/`.

Keep controllers thin, delegate business logic to services, and encapsulate database operations in repositories.

Always verify your changes before completion by running:
```powershell
.\mvnw test
```

## Progressive Disclosure

Read only the `agent-docs` file that is relevant to the work you are doing.

- `agent-docs/folder_structure.md` - Directory layout and package responsibilities. Read this before adding new packages or reorganizing files.
- `agent-docs/code_conventions.md` - Coding rules, naming standards, entity/DTO patterns, and architectural constraints. Read this before adding new features, refactoring, or creating models.
- `agent-docs/testing.md` - Testing guide with MockMvc, Mockito, H2 in-memory configuration, and test execution commands. Read this before writing or updating tests.

If more files are added under `agent-docs`, add one short line for each file here so the next agent can quickly decide what to open and what to skip.
