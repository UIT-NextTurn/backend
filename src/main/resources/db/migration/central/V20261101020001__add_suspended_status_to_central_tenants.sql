-- ==============================================================================
-- NextTurn Relational Database Migration (Flyway V20261101020001)
-- Schema: Central Management Schema (nextturn_master)
-- Description: Align central__tenants status check constraint with SRS statuses (REQ-TNT-01, REQ-TNT-03, REQ-TNT-05, REQ-TNT-06)
-- ==============================================================================

ALTER TABLE `central__tenants`
    DROP CHECK `chk_central__tenants_status`;

ALTER TABLE `central__tenants`
    ADD CONSTRAINT `chk_central__tenants_status` CHECK (`status` IN ('ACTIVE', 'SUSPENDED', 'PENDING_APPROVAL', 'REJECTED'));
