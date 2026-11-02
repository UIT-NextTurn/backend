package com.nextturn.service.tenant;

import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.enums.StaffStatus;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.auth.TenantStaffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Service providing tenant-level telemetry, active staff count, today's queue ticket volume,
 * and historical operational data verification (REQ-TNT-03, REQ-TNT-04).
 */
@Service
public class TenantTelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TenantTelemetryService.class);

    private final TenantCacheService cacheService;
    private final TenantStaffRepository staffRepository;
    private final DataSource dataSource;

    @Autowired
    public TenantTelemetryService(
            TenantCacheService cacheService,
            @Autowired(required = false) TenantStaffRepository staffRepository,
            @Autowired(required = false) DataSource dataSource
    ) {
        this.cacheService = cacheService;
        this.staffRepository = staffRepository;
        this.dataSource = dataSource;
    }

    /**
     * Counts active staff personnel in the specified tenant schema (cached for 5 minutes).
     */
    public long countActiveStaff(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return 0L;
        }
        String cacheKey = ValkeyKeyConstant.tenantActiveStaffKey(tenantCode);
        Long cached = cacheService.getCachedCount(cacheKey);
        if (cached != null) {
            return cached;
        }

        if (staffRepository == null) {
            return 0L;
        }
        long count = 0L;
        String previousTenant = TenantContextHolder.getTenantCode();
        try {
            TenantContextHolder.setTenantCode(tenantCode.trim());
            count = staffRepository.countByStatusAndIsDeletedFalse(StaffStatus.ACTIVE);
        } catch (Exception ex) {
            log.warn("Could not query staff count in tenant schema [{}] (table might not exist yet): {}",
                    tenantCode, ex.getMessage());
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantCode(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }

        cacheService.setCachedCount(cacheKey, count, Duration.ofMinutes(5));
        return count;
    }

    /**
     * Counts queue tickets issued today in the specified tenant schema (cached for 60 seconds) (REQ-TNT-04).
     */
    public long countTodayTickets(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return 0L;
        }
        String cacheKey = ValkeyKeyConstant.tenantTodayTicketsKey(tenantCode);
        Long cached = cacheService.getCachedCount(cacheKey);
        if (cached != null) {
            return cached;
        }

        if (dataSource == null) {
            return 0L;
        }
        long count = 0L;
        String previousTenant = TenantContextHolder.getTenantCode();
        try {
            TenantContextHolder.setTenantCode(tenantCode.trim());
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT COUNT(*) FROM tenant__queue_tickets WHERE created_at >= CURDATE() AND is_deleted = false";
                try (PreparedStatement stmt = conn.prepareStatement(sql);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        count = rs.getLong(1);
                    }
                }
            }
        } catch (Exception ex) {
            log.trace("Today tickets count query noted for tenant [{}]: {}", tenantCode, ex.getMessage());
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantCode(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }

        cacheService.setCachedCount(cacheKey, count, Duration.ofSeconds(60));
        return count;
    }

    /**
     * Verifies if the tenant has accumulated historical operational records
     * (queue tickets, appointments, customer feedback) to enforce soft-deletion (REQ-TNT-03).
     */
    public boolean checkTenantHistoricalData(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank() || dataSource == null) {
            return false;
        }
        String previousTenant = TenantContextHolder.getTenantCode();
        try {
            TenantContextHolder.setTenantCode(tenantCode.trim());
            try (Connection conn = dataSource.getConnection()) {
                return countTableRows(conn, "tenant__queue_tickets") > 0
                        || countTableRows(conn, "tenant__appointments") > 0
                        || countTableRows(conn, "tenant__feedbacks") > 0;
            }
        } catch (Exception ex) {
            log.debug("Historical operational data check noted for schema [tenant_{}]: {}", tenantCode, ex.getMessage());
            return false;
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantCode(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    private long countTableRows(Connection conn, String tableName) {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException ex) {
            log.trace("Table [{}] not found or query failed in tenant catalog: {}", tableName, ex.getMessage());
        }
        return 0L;
    }
}
