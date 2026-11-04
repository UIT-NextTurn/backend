package com.nextturn.service.service;

/**
 * Contract for generating unique sequential service identification codes (SVC-xxxx).
 */
public interface TenantServiceCodeGenerator {

    /**
     * Generates an auto-incremented service code in the format SVC-xxxx (e.g. SVC-0001, SVC-0002).
     *
     * @return unique service code
     */
    String generateNextServiceCode();
}
