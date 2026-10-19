# NextTurn Backend (Booking and Online Queue System)
*Hệ thống đặt lịch và quản lý hàng đợi trực tuyến*

Backend REST API & Real-time WebSocket service for **NextTurn**, a multi-tenant platform (*nền tảng trung gian đa khách thuê*) engineered for online appointment scheduling, digital ticketing, and virtual queue orchestration (*điều phối hàng đợi ảo thời gian thực*).

---

## 🛠 Tech Stack

- **Framework**: Spring Boot 4.1.1
- **Language**: Java 25 (LTS)
- **Build Tool**: Maven 3.9+ (includes `mvnw` wrapper)
- **Database**: MySQL with Spring Data JPA & Hibernate
- **Real-time Engine**: Server Sent Event
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
│   │   ├── java/studyweb/cus/
│   │   │   ├── config/                  # @Configuration, @Bean definitions (CORS, WebSocket, etc.)
│   │   │   │   ├── CorsConfig.java
│   │   │   │   └── WebSocketConfig.java
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
│   │   │   ├── redis/                   # Redis keys, templates, cache config
│   │   │   ├── repository/              # Spring Data JPA interfaces
│   │   │   ├── scheduler/               # @Scheduled cron jobs & background tasks
│   │   │   ├── security/                # Auth filters, JWT utils, security chains
│   │   │   ├── service/                 # Business logic orchestration
│   │   │   ├── util/                    # Stateless utility/helper functions
│   │   │   └── BackendApplication.java  # Main application entry point
│   │   └── resources/
│   │       ├── application.yml          # Application & MySQL configuration
│   │       └── db/migration/            # Flyway SQL migration scripts (V1__, V2__, ...)
│   └── test/
│       ├── java/studyweb/cus/
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

Default settings are configured in `src/main/resources/application.yml`. You can override them using system environment variables:

| Variable | Description | Default |
| :--- | :--- | :--- |
| `SERVER_PORT` | HTTP port | `8080` |
| `SPRING_DATASOURCE_URL` | MySQL JDBC URL | `jdbc:mysql://localhost:3306/nextturn_db?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` |
| `SPRING_DATASOURCE_USERNAME` | MySQL user | `root` |
| `SPRING_DATASOURCE_PASSWORD` | MySQL password | *(empty)* |

---

## 🚀 Getting Started

### 1. Prerequisites
- **Java**: JDK 25 installed (`java -version`)
- **MySQL**: MySQL Server 8.x+ running on port `3306` (or configured via environment variables)

### 2. Run Locally
Using the Maven wrapper on Windows PowerShell:
```powershell
.\mvnw spring-boot:run
```
Or using global Maven:
```powershell
mvn spring-boot:run
```

### 3. Run Automated Tests
```powershell
mvn test
```

---

## 📡 Initial Endpoints

- **Health Check**: `GET http://localhost:8080/api/v1/health`
- **WebSocket STOMP Handshake**: `ws://localhost:8080/ws/queue`
  - Client subscription destinations: `/topic/**`, `/queue/**`
  - Application incoming destination: `/app/**`
