package com.nextturn.enums;

/**
 * QR code mode configured for a physical reception point / kiosk (REQ-CHECK-IN-03).
 */
public enum ReceptionQrMode {
    /**
     * Rotates automatically every 60 seconds using HMAC-signed time buckets + Valkey TTL.
     */
    DYNAMIC,
    /**
     * Persistent token suitable for printed lobby posters until manually rotated by admin.
     */
    STATIC
}
