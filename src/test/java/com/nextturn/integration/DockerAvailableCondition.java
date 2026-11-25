package com.nextturn.integration;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * JUnit 6-safe execution condition that checks once per JVM whether Docker is reachable
 * via {@link DockerClientFactory} without relying on Testcontainers' {@code getRequiredTestClass()}.
 */
final class DockerAvailableCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult CACHED_RESULT = evaluateDockerAvailability();

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return CACHED_RESULT;
    }

    private static ConditionEvaluationResult evaluateDockerAvailability() {
        try {
            DockerClientFactory.instance().client();
            return ConditionEvaluationResult.enabled("Docker daemon is available");
        } catch (Throwable ex) {
            return ConditionEvaluationResult.disabled("Docker daemon is unavailable: " + ex.getMessage());
        }
    }
}
