package com.nextturn.constant;

/**
 * Endpoint route constants for Service Catalog management.
 */
public final class ServiceEndpointConstant {

    private ServiceEndpointConstant() {
        // Prevent instantiation
    }

    public static final String SERVICES_BASE = "/api/v1/services";
    public static final String SERVICE_BY_ID = "/{serviceId}";

    public static final String PUBLIC_SERVICES_BASE = "/api/v1/public/services";
}
