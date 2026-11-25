package com.nextturn.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextturn.entity.CentralTenant;
import com.nextturn.entity.CentralTenantQuota;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantSettings;
import com.nextturn.entity.TenantShift;
import com.nextturn.entity.TenantStaff;
import com.nextturn.entity.TenantStaffServiceAssignment;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.ServiceStatus;
import com.nextturn.enums.ShiftStatus;
import com.nextturn.enums.StaffRole;
import com.nextturn.enums.StaffStatus;
import com.nextturn.enums.TenantStatus;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.multitenancy.TenantRoutingDataSource;
import com.nextturn.infrastructure.security.keycloak.KeycloakTokenService;
import com.nextturn.repository.auth.CentralTenantRepository;
import com.nextturn.repository.calendar.TenantSettingsRepository;
import com.nextturn.repository.calendar.TenantShiftRepository;
import com.nextturn.repository.calendar.TenantTimeSlotRepository;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.repository.staff.TenantStaffServiceRepository;
import com.nextturn.repository.tenant.CentralTenantQuotaRepository;
import com.nextturn.service.tenant.TenantDatabaseProvisioningService;
import com.nextturn.service.tenant.impl.TenantDatabaseProvisioningServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.mockito.Mockito.when;

/**
 * Shared Testcontainers integration base class bootstrapping singleton MySQL 8.4 and Valkey 8.0
 * containers across all full-stack integration test suites, provisioning multi-tenant Flyway schemas
 * once, and truncating all non-Flyway tables plus Valkey keys quickly before each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@ExtendWith(DockerAvailableCondition.class)
public abstract class BaseIntegrationTest {

    protected static final String DEFAULT_TENANT_CODE = "clinic-alpha";
    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    protected static final MySQLContainer MYSQL_CONTAINER = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName(TenantRoutingDataSource.CENTRAL_SCHEMA)
            .withUsername("root")
            .withPassword("nextturn_test");

    @SuppressWarnings("resource")
    protected static final GenericContainer<?> VALKEY_CONTAINER = new GenericContainer<>(
            DockerImageName.parse("valkey/valkey:8.0-alpine"))
            .withExposedPorts(6379);

    private static final Set<String> PROVISIONED_TENANTS = ConcurrentHashMap.newKeySet();
    private static final Map<String, List<String>> CACHED_SCHEMA_TABLES = new ConcurrentHashMap<>();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    @Qualifier("masterDataSource")
    protected DataSource masterDataSource;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected TenantDatabaseProvisioningService tenantProvisioningService;

    @Autowired
    protected CentralTenantRepository centralTenantRepository;

    @Autowired
    protected CentralTenantQuotaRepository quotaRepository;

    @Autowired
    protected TenantServiceRepository serviceRepository;

    @Autowired
    protected TenantStaffRepository staffRepository;

    @Autowired
    protected TenantStaffServiceRepository staffServiceRepository;

    @Autowired
    protected TenantShiftRepository shiftRepository;

    @Autowired
    protected TenantTimeSlotRepository timeSlotRepository;

    @Autowired
    protected TenantSettingsRepository settingsRepository;

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @MockitoBean
    protected JavaMailSender javaMailSender;

    @MockitoBean
    protected KeycloakTokenService keycloakTokenService;

    protected final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private static void ensureContainersStarted() {
        if (!MYSQL_CONTAINER.isRunning() || !VALKEY_CONTAINER.isRunning()) {
            Startables.deepStart(MYSQL_CONTAINER, VALKEY_CONTAINER).join();
        }
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        ensureContainersStarted();
        registry.add("spring.datasource.url", () -> buildMysqlJdbcUrl());
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("valkey.host", VALKEY_CONTAINER::getHost);
        registry.add("valkey.port", () -> VALKEY_CONTAINER.getMappedPort(6379));
        registry.add("valkey.password", () -> "");
    }

    private static String buildMysqlJdbcUrl() {
        String baseUrl = MYSQL_CONTAINER.getJdbcUrl();
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "createDatabaseIfNotExist=true&serverTimezone=UTC";
    }

    @BeforeEach
    void resetIntegrationEnvironment() {
        TenantContextHolder.clear();
        ensureTenantSchemaProvisioned(DEFAULT_TENANT_CODE);
        truncateAllTablesFast();
        flushValkey();
    }

    @AfterEach
    void cleanupTenantContext() {
        TenantContextHolder.clear();
    }

    /**
     * Provisions the target tenant schema via Flyway at most once per JVM run, avoiding repeated
     * schema DDL cost before every test method.
     */
    protected void ensureTenantSchemaProvisioned(String tenantCode) {
        String normalized = tenantCode.trim().toLowerCase();
        if (PROVISIONED_TENANTS.contains(normalized)) {
            return;
        }
        synchronized (PROVISIONED_TENANTS) {
            if (!PROVISIONED_TENANTS.contains(normalized)) {
                if (!tenantProvisioningService.tenantDatabaseExists(normalized)) {
                    tenantProvisioningService.provisionTenantDatabase(normalized, null, null, null, null);
                }
                PROVISIONED_TENANTS.add(normalized);
                CACHED_SCHEMA_TABLES.remove(TenantDatabaseProvisioningServiceImpl.resolveSchemaName(normalized));
            }
        }
    }

    /**
     * Quickly truncates all non-Flyway base tables across {@code nextturn_master} and every
     * provisioned {@code tenant_*} schema in a single connection batch with FK checks temporarily disabled.
     */
    protected void truncateAllTablesFast() {
        List<String> targetSchemas = new ArrayList<>();
        targetSchemas.add(TenantRoutingDataSource.CENTRAL_SCHEMA);
        for (String tenant : PROVISIONED_TENANTS) {
            targetSchemas.add(TenantDatabaseProvisioningServiceImpl.resolveSchemaName(tenant));
        }

        try (Connection conn = masterDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                for (String schema : targetSchemas) {
                    for (String table : resolveTablesForSchema(conn, schema)) {
                        stmt.execute("TRUNCATE TABLE `" + schema + "`.`" + table + "`");
                    }
                }
            } finally {
                stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to truncate integration test schemas", ex);
        }
    }

    private List<String> resolveTablesForSchema(Connection conn, String schema) throws SQLException {
        List<String> cached = CACHED_SCHEMA_TABLES.get(schema);
        if (cached != null) {
            return cached;
        }
        List<String> tables = new ArrayList<>();
        String sql = """
                SELECT TABLE_NAME
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_TYPE = 'BASE TABLE'
                  AND TABLE_NAME <> ?
                ORDER BY TABLE_NAME
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, FLYWAY_HISTORY_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        List<String> immutable = List.copyOf(tables);
        CACHED_SCHEMA_TABLES.put(schema, immutable);
        return immutable;
    }

    protected void flushValkey() {
        if (redisTemplate == null || redisTemplate.getConnectionFactory() == null) {
            return;
        }
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    protected <T> T executeInTenant(String tenantCode, Supplier<T> action) {
        String previous = TenantContextHolder.getTenantCode();
        TenantContextHolder.setTenantCode(tenantCode);
        try {
            return action.get();
        } finally {
            if (previous != null && !previous.isBlank()) {
                TenantContextHolder.setTenantCode(previous);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    protected String registerStaffBearerToken(
            String role,
            String subject,
            String email,
            String tenantCode
    ) {
        String rawToken = "it-jwt-" + UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue(rawToken)
                .header("alg", "RS256")
                .issuer("http://localhost:8180/realms/nextturn")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("azp", "nextturn-backend")
                .claim("email", email)
                .claim("tenant_code", tenantCode)
                .claim("scope", "openid organization")
                .claim("resource_access", Map.of(
                        "nextturn-backend", Map.of("roles", List.of(role))
                ))
                .build();
        when(jwtDecoder.decode(rawToken)).thenReturn(jwt);
        return "Bearer " + rawToken;
    }

    protected ClinicFixture seedStandardClinicFixture(String tenantCode) {
        ensureTenantSchemaProvisioned(tenantCode);

        String tenantId = UUID.randomUUID().toString();
        CentralTenant tenant = centralTenantRepository.save(CentralTenant.builder()
                .id(tenantId)
                .tenantCode(tenantCode)
                .tenantName("Phòng khám Đa khoa " + tenantCode)
                .adminEmail("admin@" + tenantCode + ".internal")
                .phoneNumber("0900000001")
                .status(TenantStatus.ACTIVE)
                .isDeleted(false)
                .build());

        quotaRepository.save(CentralTenantQuota.builder()
                .id(UUID.randomUUID().toString())
                .tenantId(tenant.getId())
                .maxStaffs(10)
                .dailyTicketQuota(200)
                .maxServices(20)
                .maxAppointments(500)
                .build());

        return executeInTenant(tenantCode, () -> {
            LocalDate today = LocalDate.now();
            LocalTime now = LocalTime.now().truncatedTo(ChronoUnit.SECONDS);
            // Guarantee slotStart >= now (minLeadTimeHours = 0) AND within check-in [-15m, +10m] window
            LocalTime slotStart = now.isBefore(LocalTime.of(23, 56))
                    ? now.plusMinutes(3)
                    : (now.isBefore(LocalTime.of(23, 59, 30)) ? now.plusSeconds(20) : LocalTime.of(23, 59, 58));
            LocalTime slotEnd = slotStart.isBefore(LocalTime.of(23, 44))
                    ? slotStart.plusMinutes(15)
                    : LocalTime.of(23, 59, 59);

            settingsRepository.save(TenantSettings.builder()
                    .id(UUID.randomUUID().toString())
                    .minLeadTimeHours(0)
                    .maxAdvanceBookingDays(30)
                    .cancellationDeadlineHours(12)
                    .build());

            TenantService service = serviceRepository.save(TenantService.builder()
                    .id(UUID.randomUUID().toString())
                    .serviceCode("SVC-001")
                    .serviceName("Khám Nội Tổng Quát")
                    .standardDuration(15)
                    .price(new BigDecimal("150000.00"))
                    .status(ServiceStatus.ACTIVE)
                    .isDeleted(false)
                    .build());

            String counterSub = "counter-sub-" + UUID.randomUUID();
            TenantStaff counterStaff = staffRepository.save(TenantStaff.builder()
                    .id(UUID.randomUUID().toString())
                    .staffCode("STAFF-001")
                    .keycloakUserId(counterSub)
                    .fullName("Nhân viên Quầy 01")
                    .email("counter@" + tenantCode + ".internal")
                    .phoneNumber("0900000101")
                    .role(StaffRole.COUNTER)
                    .status(StaffStatus.ACTIVE)
                    .isDeleted(false)
                    .build());

            String adminSub = "admin-sub-" + UUID.randomUUID();
            TenantStaff adminStaff = staffRepository.save(TenantStaff.builder()
                    .id(UUID.randomUUID().toString())
                    .staffCode("STAFF-ADMIN-001")
                    .keycloakUserId(adminSub)
                    .fullName("Quản trị viên Cơ sở")
                    .email("admin@" + tenantCode + ".internal")
                    .phoneNumber("0900000100")
                    .role(StaffRole.ADMIN)
                    .status(StaffStatus.ACTIVE)
                    .isDeleted(false)
                    .build());

            TenantStaff specialistStaff = staffRepository.save(TenantStaff.builder()
                    .id(UUID.randomUUID().toString())
                    .staffCode("STAFF-002")
                    .keycloakUserId("spec-sub-" + UUID.randomUUID())
                    .fullName("BS. Nguyễn Chuyên Viên")
                    .email("specialist@" + tenantCode + ".internal")
                    .phoneNumber("0900000102")
                    .role(StaffRole.SPECIALIST)
                    .status(StaffStatus.ACTIVE)
                    .isDeleted(false)
                    .build());

            staffServiceRepository.save(TenantStaffServiceAssignment.builder()
                    .id(UUID.randomUUID().toString())
                    .staff(specialistStaff)
                    .service(service)
                    .build());

            shiftRepository.save(TenantShift.builder()
                    .id(UUID.randomUUID().toString())
                    .staff(specialistStaff)
                    .service(service)
                    .workDate(today)
                    .shiftStart(LocalTime.of(0, 0, 0))
                    .shiftEnd(LocalTime.of(23, 59, 59))
                    .status(ShiftStatus.SCHEDULED)
                    .build());

            TenantTimeSlot slot = timeSlotRepository.save(TenantTimeSlot.builder()
                    .id(UUID.randomUUID().toString())
                    .service(service)
                    .workDate(today)
                    .slotStart(slotStart)
                    .slotEnd(slotEnd)
                    .capacityPerSlot(1)
                    .bookedCount(0)
                    .status(TimeSlotStatus.AVAILABLE)
                    .build());

            return new ClinicFixture(
                    tenant.getId(),
                    tenantCode,
                    service.getId(),
                    service.getServiceCode(),
                    adminStaff.getId(),
                    adminSub,
                    counterStaff.getId(),
                    counterSub,
                    specialistStaff.getId(),
                    slot.getId(),
                    today
            );
        });
    }

    protected JsonNode parseJsonData(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        return root.path("data");
    }

    protected record ClinicFixture(
            String tenantId,
            String tenantCode,
            String serviceId,
            String serviceCode,
            String adminStaffId,
            String adminSub,
            String counterStaffId,
            String counterSub,
            String specialistStaffId,
            String slotId,
            LocalDate workDate
    ) {
    }
}
