package com.nextturn.constant;

/**
 * Constants representing cache key prefixes and keys for Valkey/Redis
 * (*tiền tố và cấu trúc khóa bộ nhớ đệm Valkey*).
 */
public final class ValkeyKeyConstant {

    private ValkeyKeyConstant() {
        // Prevent instantiation
    }

    /**
     * Key prefix for active OTP verification codes.
     */
    public static final String OTP_CODE_PREFIX = "nextturn:otp:code:";

    /**
     * Key prefix for OTP verification failed attempt counters.
     */
    public static final String OTP_ATTEMPTS_PREFIX = "nextturn:otp:attempts:";

    /**
     * Initial attempt counter value.
     */
    public static final String INITIAL_ATTEMPTS_COUNT = "0";

    /**
     * Key prefix for OTP 60-second resend cooldown timer.
     */
    public static final String OTP_COOLDOWN_PREFIX = "nextturn:otp:cooldown:";

    /**
     * Key prefix for OTP 24-hour daily quota limit counters.
     */
    public static final String OTP_DAILY_PREFIX = "nextturn:otp:daily:";

    /**
     * Formats the Redis key for storing an OTP code.
     */
    public static String otpCodeKey(String email) {
        return OTP_CODE_PREFIX + (email != null ? email.trim().toLowerCase() : "");
    }

    /**
     * Formats the Redis key for tracking failed OTP verification attempts.
     */
    public static String otpAttemptsKey(String email) {
        return OTP_ATTEMPTS_PREFIX + (email != null ? email.trim().toLowerCase() : "");
    }

    /**
     * Formats the Redis key for tracking 60-second resend cooldown.
     */
    public static String otpCooldownKey(String contact) {
        return OTP_COOLDOWN_PREFIX + (contact != null ? contact.trim().toLowerCase() : "");
    }

    /**
     * Formats the Redis key for tracking 24-hour daily request count.
     */
    public static String otpDailyKey(String contact) {
        return OTP_DAILY_PREFIX + (contact != null ? contact.trim().toLowerCase() : "");
    }

    /**
     * Key prefix for tenant configuration and operational quotas.
     */
    public static final String TENANT_CONFIG_PREFIX = "nextturn:tenant:config:";

    /**
     * Key prefix for tenant daily tickets telemetry count.
     */
    public static final String TENANT_TODAY_TICKETS_PREFIX = "nextturn:tenant:telemetry:tickets:";

    /**
     * Key prefix for tenant active staff count telemetry.
     */
    public static final String TENANT_ACTIVE_STAFF_PREFIX = "nextturn:tenant:telemetry:staff:";

    /**
     * Standard cache TTL for tenant configurations (24 hours).
     */
    public static final int TENANT_PROFILE_TTL_HOURS = 24;

    /**
     * Formats the Redis key for caching tenant configuration and operational quota limits.
     *
     * @param tenantIdentifier tenant ID or tenant code
     * @return cache key
     */
    public static String tenantConfigKey(String tenantIdentifier) {
        return TENANT_CONFIG_PREFIX + (tenantIdentifier != null ? tenantIdentifier.trim().toLowerCase() : "");
    }

    /**
     * Formats the Redis key for caching a tenant's ticket count today.
     *
     * @param tenantCode tenant code
     * @return cache key
     */
    public static String tenantTodayTicketsKey(String tenantCode) {
        return TENANT_TODAY_TICKETS_PREFIX + (tenantCode != null ? tenantCode.trim().toLowerCase() : "");
    }

    /**
     * Formats the Redis key for caching a tenant's active staff count.
     *
     * @param tenantCode tenant code
     * @return cache key
     */
    public static String tenantActiveStaffKey(String tenantCode) {
        return TENANT_ACTIVE_STAFF_PREFIX + (tenantCode != null ? tenantCode.trim().toLowerCase() : "");
    }

    /**
     * Key prefix for tenant services catalog cache.
     */
    public static final String TENANT_SERVICES_PREFIX = "nextturn:tenant:services:";

    /**
     * Formats the Redis key for caching a tenant's service catalog list.
     *
     * @param tenantCode tenant code
     * @return cache key
     */
    public static String tenantServicesKey(String tenantCode) {
        return TENANT_SERVICES_PREFIX + (tenantCode != null ? tenantCode.trim().toLowerCase() : "");
    }

    /**
     * Key prefix for staff assigned to a specific service.
     */
    public static final String TENANT_SERVICE_STAFF_PREFIX = "nextturn:tenant:service_staff:";

    /**
     * Formats the Redis key for caching assigned staff members for a specific service.
     *
     * @param tenantCode tenant code
     * @param serviceId service unique ID
     * @return cache key
     */
    public static String tenantServiceStaffKey(String tenantCode, String serviceId) {
        String cleanCode = tenantCode != null ? tenantCode.trim().toLowerCase() : "";
        String cleanService = serviceId != null ? serviceId.trim() : "";
        return TENANT_SERVICE_STAFF_PREFIX + cleanCode + ":" + cleanService;
    }

    /**
     * Key prefix for tenant generated appointment booking time slots.
     */
    public static final String TENANT_SLOTS_PREFIX = "nextturn:tenant:slots:";

    /**
     * Formats the Redis key for caching available time slots for a service on a given date.
     *
     * @param tenantCode tenant code
     * @param serviceId service unique ID
     * @param workDate scheduled work date
     * @return cache key
     */
    public static String tenantSlotsKey(String tenantCode, String serviceId, java.time.LocalDate workDate) {
        String cleanCode = tenantCode != null ? tenantCode.trim().toLowerCase() : "";
        String cleanService = serviceId != null ? serviceId.trim() : "";
        String dateStr = workDate != null ? workDate.toString() : "";
        return TENANT_SLOTS_PREFIX + cleanCode + ":" + cleanService + ":" + dateStr;
    }

    /**
     * Key prefix for distributed locks on appointment booking time slots.
     */
    public static final String SLOT_LOCK_PREFIX = "nextturn:lock:slot:";

    /**
     * Formats the Redis key for distributed locking during slot holding.
     *
     * @param tenantCode tenant code
     * @param slotId time slot UUID
     * @return distributed lock key
     */
    public static String slotLockKey(String tenantCode, String slotId) {
        String cleanCode = tenantCode != null ? tenantCode.trim().toLowerCase() : "";
        String cleanSlot = slotId != null ? slotId.trim() : "";
        return SLOT_LOCK_PREFIX + cleanCode + ":" + cleanSlot;
    }

    /**
     * Key prefix for tenant calendar advance booking settings cache.
     */
    public static final String TENANT_SETTINGS_PREFIX = "nextturn:tenant:settings:";

    /**
     * Formats the Redis key for caching facility-level booking policies.
     *
     * @param tenantCode tenant code
     * @return cache key
     */
    public static String tenantSettingsKey(String tenantCode) {
        String cleanCode = tenantCode != null ? tenantCode.trim().toLowerCase() : "";
        return TENANT_SETTINGS_PREFIX + cleanCode;
    }

    /**
     * Key prefix for daily monotonic atomic ticket sequences per tenant and service.
     */
    public static final String TICKET_SEQUENCE_PREFIX = "nextturn:ticket:seq:";

    /**
     * Formats the Redis key for daily monotonic ticket sequence counters.
     *
     * @param tenantCode tenant code
     * @param serviceId  service UUID
     * @param workDate   operating work date (enables automatic reset at 00:00:00)
     * @return sequence counter key
     */
    public static String ticketSequenceKey(String tenantCode, String serviceId, java.time.LocalDate workDate) {
        String cleanCode = tenantCode != null ? tenantCode.trim().toLowerCase() : "";
        String cleanService = serviceId != null ? serviceId.trim() : "";
        String dateStr = workDate != null ? workDate.toString() : "";
        return TICKET_SEQUENCE_PREFIX + cleanCode + ":" + cleanService + ":" + dateStr;
    }

    /**
     * Key prefix for distributed locks during queue ticket issuance.
     */
    public static final String TICKET_LOCK_PREFIX = "nextturn:lock:ticket:";

    /**
     * Formats the Redis key for distributed mutex locking during queue ticket issuance.
     *
     * @param tenantCode tenant code
     * @param serviceId  service UUID
     * @param workDate   operating work date
     * @return distributed lock key
     */
    public static String ticketLockKey(String tenantCode, String serviceId, java.time.LocalDate workDate) {
        String cleanCode = tenantCode != null ? tenantCode.trim().toLowerCase() : "";
        String cleanService = serviceId != null ? serviceId.trim() : "";
        String dateStr = workDate != null ? workDate.toString() : "";
        return TICKET_LOCK_PREFIX + cleanCode + ":" + cleanService + ":" + dateStr;
    }

    /**
     * Key prefix for 60-second rotating dynamic reception point QR tokens (REQ-CHECK-IN-03).
     */
    public static final String RECEPTION_QR_PREFIX = "nextturn:checkin:qr:";

    /**
     * Formats the Redis key for an active rotating reception QR window.
     */
    public static String receptionQrKey(String tenantCode, String receptionPointId, long timeBucket) {
        String cleanCode = tenantCode != null ? tenantCode.trim().toLowerCase() : "";
        String cleanPoint = receptionPointId != null ? receptionPointId.trim() : "";
        return RECEPTION_QR_PREFIX + cleanCode + ":" + cleanPoint + ":" + timeBucket;
    }

    /**
     * Key prefix mapping a completed ticket's feedback token to {@code tenantCode:ticketId} (24h TTL) (REQ-FEEDBACK-01).
     */
    public static final String FEEDBACK_TOKEN_PREFIX = "nextturn:feedback:token:";

    /**
     * Key prefix storing pre-aggregated real-time CSAT summary statistics per tenant (REQ-FEEDBACK-03).
     */
    public static final String FEEDBACK_CSAT_PREFIX = "nextturn:feedback:csat:";

    /**
     * Formats the Redis key for validating a pending feedback token within its 24-hour window.
     */
    public static String feedbackTokenKey(String feedbackToken) {
        return FEEDBACK_TOKEN_PREFIX + (feedbackToken != null ? feedbackToken.trim() : "");
    }

    /**
     * Formats the Redis key for a tenant's cached CSAT summary rollup.
     */
    public static String feedbackCsatKey(String tenantCode) {
        return FEEDBACK_CSAT_PREFIX + (tenantCode != null ? tenantCode.trim().toLowerCase() : "");
    }

    /**
     * SRS-mandated Redis key storing pre-aggregated platform monitoring overview (REQ-DASHBOARD-05).
     */
    public static final String PLATFORM_OVERVIEW_CACHE_KEY = "cache:platform:overview";

    /**
     * Key prefix storing pre-aggregated real-time daily KPI overview per tenant (REQ-DASHBOARD-01).
     */
    public static final String DASHBOARD_OVERVIEW_PREFIX = "nextturn:dashboard:overview:";

    /**
     * Key prefix deduplicating critical daily ticket quota alerts sent per tenant per day (REQ-DASHBOARD-06).
     */
    public static final String DASHBOARD_QUOTA_ALERT_PREFIX = "nextturn:dashboard:quota-alert:";

    /**
     * Formats the Redis key for a tenant's real-time daily KPI overview snapshot.
     */
    public static String dashboardOverviewKey(String tenantCode, java.time.LocalDate workDate) {
        String cleanCode = tenantCode != null ? tenantCode.trim().toLowerCase() : "";
        String dateStr = workDate != null ? workDate.toString() : "";
        return DASHBOARD_OVERVIEW_PREFIX + cleanCode + ":" + dateStr;
    }

    /**
     * Formats the Redis key for deduplicating daily quota exceedance alerts per tenant.
     */
    public static String dashboardQuotaAlertKey(String tenantCode, java.time.LocalDate workDate) {
        String cleanCode = tenantCode != null ? tenantCode.trim().toLowerCase() : "";
        String dateStr = workDate != null ? workDate.toString() : "";
        return DASHBOARD_QUOTA_ALERT_PREFIX + cleanCode + ":" + dateStr;
    }
}
