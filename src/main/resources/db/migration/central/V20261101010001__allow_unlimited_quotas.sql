-- ==============================================================================
-- NextTurn Relational Database Migration (Flyway V20261101010001)
-- Schema: Central Management Schema (nextturn_master)
-- Description: Allow -1 (unlimited quota) in operational resource limits (REQ-TNT-02)
-- ==============================================================================

ALTER TABLE `central__tenant_quotas`
    DROP CHECK `chk_central__quotas_daily_ticket`,
    DROP CHECK `chk_central__quotas_max_services`,
    DROP CHECK `chk_central__quotas_max_appointments`;

ALTER TABLE `central__tenant_quotas`
    ADD CONSTRAINT `chk_central__quotas_daily_ticket` CHECK (`daily_ticket_quota` >= 1 OR `daily_ticket_quota` = -1),
    ADD CONSTRAINT `chk_central__quotas_max_services` CHECK (`max_services` >= 1 OR `max_services` = -1),
    ADD CONSTRAINT `chk_central__quotas_max_appointments` CHECK (`max_appointments` >= 1 OR `max_appointments` = -1);

