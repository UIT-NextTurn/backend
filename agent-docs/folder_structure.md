# Folder Structure

```
src/main/java/com/nextturn/
├── config/        → @Configuration, @Bean definitions (security, DB, etc.)
├── constant/      → Static final constants (API paths, error codes, etc.)
├── controller/    → REST endpoints (@RestController), request validation, response shaping
├── dto/           → Request/Response objects for API layer
├── entity/        → JPA entities mapped to DB tables
├── enums/         → Enum types used across the app
├── event/         → ApplicationEvent classes for pub/sub within the app
├── exception/     → Custom exception classes and @ControllerAdvice handlers
├── mapper/        → Entity ↔ DTO conversion logic
├── valkey/        → Valkey keys, repository templates, cache config
├── repository/    → Spring Data JPA interfaces (extends JpaRepository)
├── scheduler/     → @Scheduled cron jobs and background tasks
├── security/      → Auth filters, JWT utils, password encoders, security chains
├── service/       → Business logic, @Service classes orchestrate repositories & clients
└── util/          → Stateless utility/helper functions

src/main/resources/
├── application.yml          → App config for DB and env-specific properties
└── db/migration/
    ├── central/             → Flyway SQL migrations for nextturn_master (V<timestamp>__*.sql)
    └── tenant/              → Flyway SQL migrations for tenant_{code} schemas (V<timestamp>__*.sql)

src/test/java/com/nextturn/  → Unit & integration tests mirroring main packages
```
