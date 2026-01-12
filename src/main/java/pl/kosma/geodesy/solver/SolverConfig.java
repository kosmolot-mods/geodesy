package pl.kosma.geodesy.solver;

/**
 * Configuration parameters for the face solver algorithm.
 */
public class SolverConfig {

    public static final long DEFAULT_TIMEOUT_MS = 5_000L;
    public static final double DEFAULT_COST_THRESHOLD = 1.0;
    // Below 1.0, islands covering only 1 harvest cell would still be "profitable".
    public static final double MIN_COST_THRESHOLD = 1.0;
    // Above 12.0 (max island size), even a fully productive island would be penalized out.
    public static final double MAX_COST_THRESHOLD = 12.0;

    private final long timeoutMs;
    private final double costThreshold;

    private SolverConfig(Builder builder) {
        this.timeoutMs = builder.timeoutMs;
        this.costThreshold = builder.costThreshold;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public double getCostThreshold() {
        return costThreshold;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SolverConfig defaults() {
        return builder().build();
    }

    @Override
    public String toString() {
        return "SolverConfig[timeoutMs=" + timeoutMs + ", costThreshold=" + costThreshold + "]";
    }

    public static class Builder {
        private long timeoutMs = DEFAULT_TIMEOUT_MS;
        private double costThreshold = DEFAULT_COST_THRESHOLD;

        private Builder() {}

        public Builder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        // Value is clamped to [MIN_COST_THRESHOLD, MAX_COST_THRESHOLD].
        public Builder costThreshold(double costThreshold) {
            this.costThreshold = Math.max(MIN_COST_THRESHOLD, Math.min(MAX_COST_THRESHOLD, costThreshold));
            return this;
        }

        public SolverConfig build() {
            return new SolverConfig(this);
        }
    }
}
