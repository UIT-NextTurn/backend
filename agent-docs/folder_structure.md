# Folder Structure

```
src/main/java/studyweb/cus/
├── config/        → @Configuration, @Bean definitions (security, DB, etc.)
├── constant/      → Static final constants (API paths, error codes, etc.)
├── controller/    → REST endpoints (@RestController), request validation, response shaping
├── dto/           → Request/Response objects for API layer
├── entity/        → JPA entities mapped to DB tables
├── enums/         → Enum types used across the app
├── event/         → ApplicationEvent classes for pub/sub within the app
├── exception/     → Custom exception classes and @ControllerAdvice handlers
├── mapper/        → Entity ↔ DTO conversion logic
├── redis/         → Redis keys, repository templates, cache config
├── repository/    → Spring Data JPA interfaces (extends JpaRepository)
├── scheduler/     → @Scheduled cron jobs and background tasks
├── security/      → Auth filters, JWT utils, password encoders, security chains
├── service/       → Business logic, @Service classes orchestrate repositories & clients
└── util/          → Stateless utility/helper functions

src/main/resources/
├── application.yml       → App config (DB, env-specific properties)
├── db/migration/         → Flyway SQL migration scripts (V1__, V2__, ...)

src/test/java/studyweb/cus/  → Unit & integration tests mirroring main packages
```
