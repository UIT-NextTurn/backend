# NextTurn Backend (Booking and Online Queue System)

Backend REST API & Real-time Server-Sent Events (SSE) service for **NextTurn**, a multi-tenant platform engineered for online appointment scheduling, digital ticketing, and virtual queue orchestration.

---

## 🛠 Tech Stack

- **Framework**: Spring Boot 4.1.1
- **Language**: Java 25 (LTS)
- **Build Tool**: Maven 3.9+ (includes `mvnw` wrapper)
- **Database**: MySQL with Spring Data JPA & Hibernate
- **In-Memory Cache & Queue**: Valkey 8.0
- **Real-time Engine**: Server-Sent Events (SSE)
- **Validation**: Jakarta Bean Validation (`spring-boot-starter-validation`)
- **Productivity**: Lombok & Spring Boot DevTools
- **Testing**: JUnit 5, MockMvc, H2 in-memory test database

---

## 📂 Project Structure

```text
backend/
├── .mvn/                                # Maven wrapper configuration
├── src/
│   ├── main/
│   │   ├── java/com/nextturn/
│   │   │   ├── config/                  # @Configuration, @Bean definitions (CORS, security, etc.)
│   │   │   │   └── CorsConfig.java
│   │   │   ├── constant/                # Static final constants (API paths, error codes, etc.)
│   │   │   │   └── AppConstant.java
│   │   │   ├── controller/              # REST endpoints (@RestController)
│   │   │   │   └── HealthCheckController.java
│   │   │   ├── dto/                     # Request/Response DTOs
│   │   │   │   └── ApiResponse.java
│   │   │   ├── entity/                  # JPA entities mapped to DB tables
│   │   │   ├── enums/                   # Enum types used across the app
│   │   │   ├── event/                   # ApplicationEvent classes for pub/sub
│   │   │   ├── exception/               # Custom exceptions & @ControllerAdvice handlers
│   │   │   │   ├── AppException.java
│   │   │   │   └── GlobalExceptionHandler.java
│   │   │   ├── mapper/                  # Entity <-> DTO conversion logic
│   │   │   ├── valkey/                  # Valkey keys, templates, cache config
│   │   │   ├── repository/              # Spring Data JPA interfaces
│   │   │   ├── scheduler/               # @Scheduled cron jobs & background tasks
│   │   │   ├── security/                # Auth filters, JWT utils, security chains
│   │   │   ├── service/                 # Business logic orchestration
│   │   │   ├── util/                    # Stateless utility/helper functions
│   │   │   └── BackendApplication.java  # Main application entry point
│   │   └── resources/
│   │       ├── application.yml          # Application & MySQL configuration
│   │       └── db/migration/            # Flyway SQL migration scripts (V<timestamp>__*.sql, e.g., V20261021010001__*.sql)
│   └── test/
│       ├── java/com/nextturn/
│       │   ├── controller/
│       │   │   └── HealthCheckControllerTest.java
│       │   └── BackendApplicationTests.java
│       └── resources/
│           └── application.yml          # H2 in-memory test profile
├── mvnw                                 # Maven wrapper script (Unix)
├── mvnw.cmd                             # Maven wrapper script (Windows)
├── agent-docs/                          # Development & agent docs (structure, conventions, testing)
│   ├── folder_structure.md
│   ├── code_conventions.md
│   └── testing.md
├── AGENTS.md                            # Agent instructions & progressive disclosure
├── pom.xml                              # Project Object Model dependencies
└── README.md
```

---

## ⚙️ Configuration & Environment Variables

Create a `.env` file in the `backend/` directory by duplicating the template:
```bash
cp .env.example .env
```

Settings are automatically loaded from `.env` by `make run` or can be supplied as system environment variables:

| Variable | Description | Default |
| :--- | :--- | :--- |
| `SERVER_PORT` | HTTP port | `8080` |
| `SPRING_DATASOURCE_URL` | MySQL JDBC URL | `jdbc:mysql://localhost:3306/nextturn_master?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` |
| `SPRING_DATASOURCE_USERNAME` | MySQL user | `nextturn` |
| `SPRING_DATASOURCE_PASSWORD` | MySQL password | `nextturn_dev_secret` |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | Hibernate schema mode | `update` |
| `SPRING_JPA_SHOW_SQL` | Log SQL queries | `true` |
| `VALKEY_HOST` | Valkey cache & queue host | `localhost` |
| `VALKEY_PORT` | Valkey client port | `6379` |
| `VALKEY_PASSWORD` | Valkey password | *(empty)* |
| `APP_TIMEZONE` | Application timezone | `Asia/Ho_Chi_Minh` |

---

## 🚀 Getting Started

### 1. Prerequisites
- **Java**: JDK 25 installed (`java -version`)
- **MySQL & Valkey**: Started via `make up-mysql` and `make up-valkey` in `infrastructure/` (or running locally)

### 2. Run Locally
Using `make` (cross-platform for Windows & Linux):
```bash
make run
```
Or directly using the Maven wrapper:
- Windows PowerShell:
  ```powershell
  .\mvnw spring-boot:run
  ```
- Linux / macOS:
  ```bash
  ./mvnw spring-boot:run
  ```

### 3. Run Automated Tests
```powershell
mvn test
```

---

## 📡 Initial Endpoints

- **Health Check**: `GET http://localhost:8080/api/v1/health`
- **Real-time Queue Stream (SSE)**: `GET http://localhost:8080/api/v1/sse/queue/{queueId}` (`text/event-stream`)
- **Real-time Ticket Tracking (SSE)**: `GET http://localhost:8080/api/v1/sse/ticket/{ticketId}` (`text/event-stream`)
