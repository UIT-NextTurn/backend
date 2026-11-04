package com.nextturn.service.service;

import com.nextturn.constant.ServiceConstant;
import com.nextturn.repository.service.TenantServiceRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Generates unique sequential service identification codes (SVC-xxxx).
 */
@Component
public class TenantServiceCodeGenerator {

    private final TenantServiceRepository serviceRepository;

    public TenantServiceCodeGenerator(TenantServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    /**
     * Generates an auto-incremented service code in the format SVC-xxxx (e.g. SVC-0001, SVC-0002).
     *
     * @return unique service code
     */
    public String generateNextServiceCode() {
        Optional<String> maxCodeOpt = serviceRepository.findMaxServiceCode();
        int nextSequence = 1;

        if (maxCodeOpt.isPresent() && maxCodeOpt.get() != null) {
            String maxCode = maxCodeOpt.get().trim();
            if (maxCode.startsWith(ServiceConstant.SERVICE_CODE_PREFIX)) {
                String numPart = maxCode.substring(ServiceConstant.SERVICE_CODE_PREFIX.length());
                try {
                    nextSequence = Integer.parseInt(numPart) + 1;
                } catch (NumberFormatException ignored) {
                    nextSequence = (int) serviceRepository.countByIsDeletedFalse() + 1;
                }
            }
        }

        for (int i = 0; i < 20; i++) {
            String candidate = String.format("%s%04d", ServiceConstant.SERVICE_CODE_PREFIX, nextSequence + i);
            if (!serviceRepository.existsByServiceCodeAndIsDeletedFalse(candidate)) {
                return candidate;
            }
        }

        return ServiceConstant.SERVICE_CODE_PREFIX + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}

