# Testing Guide

Testing guidelines, test configurations, and execution practices for the NextTurn backend.

---

## 1. Test Environment & Configuration

Tests execute using an isolated in-memory H2 database configured in `src/test/resources/application.yml`:

- **Driver**: `org.h2.Driver` with `MODE=MySQL` compatibility.
- **DDL Mode**: `create-drop` to ensure a clean database schema for each test session.
- **Port**: Fixed or randomized test port without colliding with the local running development server.

---

## 2. Running Tests

Run all automated unit and integration tests using `make` or the Maven wrapper:

```bash
# Run the complete test suite using Makefile
make test
```

```powershell
# Run using the Maven wrapper directly
.\mvnw test

# Run a specific test class
.\mvnw test -Dtest=HealthCheckControllerTest

# Run a specific test method within a class
.\mvnw test -Dtest=HealthCheckControllerTest#healthCheckShouldReturnUpStatus

# Run tests with clean compile
.\mvnw clean test
```

---

## 3. Test Layers & Recommended Patterns

### A. Controller / Web Layer Slice Tests (Kiểm thử phân tầng)
- Annotate test classes with `@WebMvcTest(TargetController.class)`. Avoid `@SpringBootTest` for web layer tests as it is heavy and unnecessarily bootstraps JPA, Flyway, and the DataSource.
- Explicitly enable method security via `@EnableMethodSecurity(prePostEnabled = true)` (or include `TestSecurityConfig.class`) to ensure `@PreAuthorize` authorization rules are enforced in the slice test.
- Import the required security and CORS configurations via `@Import(...)`:
  ```java
  @WebMvcTest(HealthCheckController.class)
  @EnableMethodSecurity(prePostEnabled = true)
  @Import({
          SecurityConfig.class,
          TestSecurityConfig.class,
          CorsConfig.class,
          KeycloakJwtAuthenticationConverter.class,
          KeycloakJwtGrantedAuthoritiesConverter.class,
          CurrentUserArgumentResolver.class,
          SecurityContextHelper.class
  })
  class HealthCheckControllerTest {

      @Autowired
      private MockMvc mockMvc;

      @Test
      void healthCheckShouldReturnUpStatus() throws Exception {
          mockMvc.perform(get("/api/v1/health"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.success").value(true))
                  .andExpect(jsonPath("$.data.status").value("UP"));
      }
  }
  ```

### B. Service Layer Unit Tests
- Use Mockito for fast unit tests without bootstrapping the full Spring container.
- Annotate with `@ExtendWith(MockitoExtension.class)`.
- Use `@Mock` for dependent repositories and `@InjectMocks` for the service under test:
  ```java
  @ExtendWith(MockitoExtension.class)
  class QueueTicketServiceTest {

      @Mock
      private QueueTicketRepository ticketRepository;

      @InjectMocks
      private QueueTicketService ticketService;

      @Test
      void shouldCallNextTicketSuccessfully() {
          // Arrange, Act, Assert
      }
  }
  ```

### C. Repository Layer Tests
- Use `@DataJpaTest` when verifying complex custom JPQL or native SQL queries.
- Utilizes the in-memory H2 database and rolls back transactions automatically at the end of each test method.

---

## 4. Test Conventions

- **File Placement**: Mirror the main source package layout inside `src/test/java/com/nextturn/...`.
  - Main: `src/main/java/com/nextturn/controller/HealthCheckController.java`
  - Test: `src/test/java/com/nextturn/controller/HealthCheckControllerTest.java`
- **Naming**: Always suffix test class names with `Test` (e.g., `QueueTicketServiceTest`).
- **Structure**: Follow the **AAA Pattern** (Arrange, Act, Assert) within test bodies.
- **Determinism**: Never rely on execution order between tests. Each test must be completely isolated and idempotent.
