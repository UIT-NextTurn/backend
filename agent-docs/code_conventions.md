# Code Conventions

Coding standards, architecture rules, and implementation patterns for the NextTurn backend.

---

## 1. Package & Layer Architecture

All code lives in `com.nextturn.*` adhering to [folder_structure.md](./folder_structure.md):

- **`controller/`**: Thin REST endpoints. Do not place business logic here. Validate incoming payloads using `@Valid` and return standard `ResponseEntity<ApiResponse<T>>`.
- **`service/`**: Domain and workflow orchestration. Handles database transactions (`@Transactional`), security checks, and cross-repository coordination.
- **`repository/`**: Spring Data JPA interfaces extending `JpaRepository<Entity, ID>`. Use Spring Data derived queries or JPQL with `@Query`.
- **`entity/`**: JPA entities mapped to relational database tables.
- **`dto/`**: Data Transfer Objects for API request and response bodies. Never expose JPA entities directly in API endpoints.
- **`exception/`**: Domain exceptions extending `AppException` and handled by `GlobalExceptionHandler`.
- **`infrastructure/`**: Low-level infrastructure adapters and cross-cutting frameworks:
  - `infrastructure/multitenancy/`: Multi-tenant request filters, tenant context, dynamic schema routing.
  - `infrastructure/security/`: Keycloak OIDC configuration, JWT authentication/authorities converters, role annotations.
  - `infrastructure/valkey/`: Valkey cache keys, templates, and connection configurations.
- **`mapper/`**: Static conversion methods or mapping components transforming between Entity and DTO.
- **`constant/`**: Immutable `public static final` definitions (e.g. route prefixes, defaults).
- **`enums/`**: Fixed domain enumerations (e.g. queue statuses, roles, ticket priorities).
- **`config/`**: Spring `@Configuration` beans for general configuration (e.g. CORS).
- **`util/`**: Pure, stateless helper functions without Spring bean dependencies.

---

## 2. Naming Conventions

- **Classes & Interfaces**: `PascalCase` (e.g., `HealthCheckController`, `QueueTicketService`, `TicketStatus`).
- **Methods & Variables**: `camelCase` (e.g., `calculateEstimatedWaitTime`, `tenantId`).
- **Constants & Enum Values**: `UPPER_SNAKE_CASE` (e.g., `API_V1_PREFIX`, `DEFAULT_PAGE_SIZE`, `STATUS_IN_SERVICE`).
- **REST Endpoints**: Lowercase with hyphens / kebab-case for multi-word paths (e.g., `/api/v1/queue-tickets`, `/api/v1/service-counters`).
- **Database Tables & Columns**: `snake_case` (e.g., `table_name = "queue_tickets"`, `column_name = "tenant_id"`).

---

## 3. Entity & JPA Best Practices

- **Lombok Usage**: Prefer `@Getter`, `@Setter`, and `@NoArgsConstructor` on entities.
- **Avoid `@Data` and `@EqualsAndHashCode` on JPA Entities**: They generate automatic `toString()` and `hashCode()` that can trigger unintended lazy-loading or infinite recursion on bidirectional relationships.
- **Auditing**: Always include timestamp tracking (`createdAt`, `updatedAt`) using `@CreationTimestamp` and `@UpdateTimestamp` or `@EntityListeners(AuditingEntityListener.class)`.
- **Foreign Keys**: Explicitly define `@ManyToOne(fetch = FetchType.LAZY)` for relational mappings to avoid N+1 query performance hits.

---

## 4. DTO & Validation Conventions

- Keep request DTOs distinct from response DTOs (e.g., `CreateTicketRequest` vs `TicketResponse`).
- Use Jakarta Bean Validation annotations to enforce constraints at the API threshold:
  - `@NotNull` for required objects / non-string values.
  - `@NotBlank` for required non-empty string fields.
  - `@Size(min = ..., max = ...)` for bounded strings or collections.
  - `@Min`, `@Max` for numeric boundaries.
- Wrap all controller responses in `ApiResponse<T>`:
  - Success: `ApiResponse.ok(data)` or `ApiResponse.ok("Message", data)`
  - Failure: `ApiResponse.error(statusCode, "Error message")`

---

## 5. Exception Handling

- Throw `AppException` (or specific subclasses) with appropriate HTTP status codes:
  ```java
  throw new AppException(HttpStatus.NOT_FOUND.value(), "Queue ticket not found with id: " + id);
  ```
- Do not return manual error JSON maps in controllers; allow `GlobalExceptionHandler` to translate exceptions into standardized `ApiResponse<T>` payloads.
- Catch specific checked exceptions rather than generic `Exception` or `Throwable`.
- Never silently swallow exceptions.

---

## 6. Transactions & Concurrency

- Annotate service mutation methods with `@Transactional`.
- Use read-only transactions for query-only service methods:
  ```java
  @Transactional(readOnly = true)
  public TicketResponse getTicketById(Long id) { ... }
  ```
- For concurrent queue number increments or counter assignments, use optimistic locking (`@Version`) or pessimistic database locks to prevent race conditions.
