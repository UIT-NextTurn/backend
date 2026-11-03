package com.nextturn.constant;

/**
 * Standard constants for tenant services and catalog configuration.
 */
public final class ServiceConstant {

    private ServiceConstant() {
        // Prevent instantiation
    }

    /**
     * Standard prefix for service identification codes.
     * Example: SVC-0001, SVC-0002.
     */
    public static final String SERVICE_CODE_PREFIX = "SVC-";

    /**
     * Default standard service duration in minutes (T_standard).
     */
    public static final int DEFAULT_STANDARD_DURATION = 15;
}
