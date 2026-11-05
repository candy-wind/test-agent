package vip.mate.agent.platform.model.dto;

public record TestResult(
        boolean ok,
        long latencyMs,
        Integer httpStatus,
        String message
) {
}
