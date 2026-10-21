-- ==============================================================================
-- NextTurn Relational Database Migration (Flyway V20261021012500)
-- Description: Add CHECK constraints to enforce ENUM valid ranges, business rules,
--              and positive numerical boundaries across Central and Tenant schemas.
-- Target Engine: MySQL 8.4 LTS (Oracle HeatWave Compatible) / InnoDB
-- ==============================================================================

-- ==============================================================================
-- PART 1: CENTRAL MANAGEMENT SCHEMA CONSTRAINTS (nextturn_master)
-- ==============================================================================

-- 1. central__tenants: Status ENUM check ('ACTIVE', 'PENDING', 'BLOCKED', 'DELETED')
ALTER TABLE `central__tenants`
    ADD CONSTRAINT `chk_central__tenants_status`
    CHECK (`status` IN ('ACTIVE', 'PENDING', 'BLOCKED', 'DELETED'));

-- 2. central__users: Status ENUM check ('ACTIVE', 'BLOCKED', 'DELETED')
ALTER TABLE `central__users`
    ADD CONSTRAINT `chk_central__users_status`
    CHECK (`status` IN ('ACTIVE', 'BLOCKED', 'DELETED'));

-- 3. central__tenant_quotas: Positive capacity and threshold checks
ALTER TABLE `central__tenant_quotas`
    ADD CONSTRAINT `chk_central__quotas_max_staffs`
        CHECK (`max_staffs` >= 1),
    ADD CONSTRAINT `chk_central__quotas_daily_ticket`
        CHECK (`daily_ticket_quota` >= 1),
    ADD CONSTRAINT `chk_central__quotas_max_services`
        CHECK (`max_services` >= 1),
    ADD CONSTRAINT `chk_central__quotas_max_appointments`
        CHECK (`max_appointments` >= 1);


-- ==============================================================================
-- PART 2: TENANT DEDICATED SCHEMA CONSTRAINTS (tenant_{code})
-- ==============================================================================

-- 4. tenant__services: Status ENUM, positive duration, and non-negative price
ALTER TABLE `tenant__services`
    ADD CONSTRAINT `chk_tenant__services_status`
        CHECK (`status` IN ('ACTIVE', 'INACTIVE')),
    ADD CONSTRAINT `chk_tenant__services_duration`
        CHECK (`standard_duration` > 0),
    ADD CONSTRAINT `chk_tenant__services_price`
        CHECK (`price` >= 0.00);

-- 5. tenant__staffs: Role and Status ENUM checks
ALTER TABLE `tenant__staffs`
    ADD CONSTRAINT `chk_tenant__staffs_role`
        CHECK (`role` IN ('ADMIN', 'COUNTER', 'SPECIALIST')),
    ADD CONSTRAINT `chk_tenant__staffs_status`
        CHECK (`status` IN ('ACTIVE', 'INACTIVE'));

-- 6. tenant__shifts: Shift Status ENUM and chronological time window check
ALTER TABLE `tenant__shifts`
    ADD CONSTRAINT `chk_tenant__shifts_status`
        CHECK (`status` IN ('SCHEDULED', 'COMPLETED', 'CANCELLED')),
    ADD CONSTRAINT `chk_tenant__shifts_time_window`
        CHECK (`shift_end` > `shift_start`);

-- 7. tenant__time_slots: Slot Status ENUM, time window, capacity, and booking count bounds
ALTER TABLE `tenant__time_slots`
    ADD CONSTRAINT `chk_tenant__time_slots_status`
        CHECK (`status` IN ('AVAILABLE', 'HOLDING', 'BOOKED')),
    ADD CONSTRAINT `chk_tenant__time_slots_time_window`
        CHECK (`slot_end` > `slot_start`),
    ADD CONSTRAINT `chk_tenant__time_slots_capacity`
        CHECK (`capacity_per_slot` >= 1),
    ADD CONSTRAINT `chk_tenant__time_slots_booked_count`
        CHECK (`booked_count` >= 0 AND `booked_count` <= `capacity_per_slot`);

-- 8. tenant__appointments: Lifecycle Status ENUM check
ALTER TABLE `tenant__appointments`
    ADD CONSTRAINT `chk_tenant__appointments_status`
    CHECK (`status` IN ('PENDING', 'CONFIRMED', 'CHECKED_IN', 'CANCELLED', 'NO_SHOW'));

-- 9. tenant__queue_tickets: Customer Type & Queue Status ENUMs, counters, and version locks
ALTER TABLE `tenant__queue_tickets`
    ADD CONSTRAINT `chk_tenant__tickets_customer_type`
        CHECK (`customer_type` IN ('APPOINTMENT', 'WALK_IN')),
    ADD CONSTRAINT `chk_tenant__tickets_status`
        CHECK (`status` IN ('WAITING', 'CALLING', 'SERVING', 'COMPLETED', 'NO_SHOW')),
    ADD CONSTRAINT `chk_tenant__tickets_recall_count`
        CHECK (`recall_count` >= 0),
    ADD CONSTRAINT `chk_tenant__tickets_version`
        CHECK (`version` >= 0),
    ADD CONSTRAINT `chk_tenant__tickets_actual_duration`
        CHECK (`actual_duration` IS NULL OR `actual_duration` >= 0);

-- 10. tenant__ticket_audit_logs: Previous and new state validation
ALTER TABLE `tenant__ticket_audit_logs`
    ADD CONSTRAINT `chk_tenant__audit_prev_state`
        CHECK (`previous_state` IN ('WAITING', 'CALLING', 'SERVING', 'COMPLETED', 'NO_SHOW')),
    ADD CONSTRAINT `chk_tenant__audit_new_state`
        CHECK (`new_state` IN ('WAITING', 'CALLING', 'SERVING', 'COMPLETED', 'NO_SHOW'));

-- 11. tenant__feedbacks: Rating score between 1 and 5 stars
ALTER TABLE `tenant__feedbacks`
    ADD CONSTRAINT `chk_tenant__feedbacks_rating`
    CHECK (`rating_score` BETWEEN 1 AND 5);

-- 12. tenant__notification_logs: Dispatch Channel and Delivery Status ENUMs
ALTER TABLE `tenant__notification_logs`
    ADD CONSTRAINT `chk_tenant__notifications_channel`
        CHECK (`channel` IN ('EMAIL', 'PUSH', 'SMS')),
    ADD CONSTRAINT `chk_tenant__notifications_status`
        CHECK (`status` IN ('SUCCESS', 'FAILED'));

-- 13. tenant__tenant_settings: Non-negative hour and day limits
ALTER TABLE `tenant__tenant_settings`
    ADD CONSTRAINT `chk_tenant__settings_lead_time`
        CHECK (`min_lead_time_hours` >= 0),
    ADD CONSTRAINT `chk_tenant__settings_advance_days`
        CHECK (`max_advance_booking_days` >= 1),
    ADD CONSTRAINT `chk_tenant__settings_cancellation`
        CHECK (`cancellation_deadline_hours` >= 0);
