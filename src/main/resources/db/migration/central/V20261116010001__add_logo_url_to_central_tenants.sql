-- ==============================================================================
-- Migration: V20261116010001__add_logo_url_to_central_tenants.sql
-- Description: Add logo_url column to central__tenants table for tenant branding (REQ-AUTH-01)
-- ==============================================================================

ALTER TABLE `central__tenants`
    ADD COLUMN `logo_url` VARCHAR(512) NULL DEFAULT NULL AFTER `organisation_id`;
