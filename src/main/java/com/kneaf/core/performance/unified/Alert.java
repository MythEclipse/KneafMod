package com.kneaf.core.performance.unified;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable alert object representing a performance issue or threshold violation.
 */
public final class Alert {
    private final Instant timestamp;
    private final AlertHandler.AlertType alertType;
    private final String message;
    private final double severity;
    private final String source;
    private final MetricSnapshot relatedMetrics;

    /**
     * Create a new alert.
     *
     * @param builder the builder to create alert from
     */
    private Alert(Builder builder) {
        this.timestamp = Instant.now();
        this.alertType = Objects.requireNonNull(builder.alertType, "Alert type must not be null");
        this.message = Objects.requireNonNull(builder.message, "Message must not be null");
        this.severity = builder.severity;
        this.source = builder.source;
        this.relatedMetrics = builder.relatedMetrics;
    }

    /**
     * Get the timestamp when this alert was created.
     *
     * @return alert timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Get the alert type.
     *
     * @return alert type
     */
    public AlertHandler.AlertType getAlertType() {
        return alertType;
    }

    /**
     * Get the alert message.
     *
     * @return alert message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Get the alert severity (0.0 to 1.0, where 1.0 is most severe).
     *
     * @return alert severity
     */
    public double getSeverity() {
        return severity;
    }

    /**
     * Get the source of this alert (e.g., plugin ID, system component).
     *
     * @return source identifier
     */
    public String getSource() {
        return source;
    }

    /**
     * Get the related metric snapshot that triggered this alert.
     *
     * @return related metric snapshot, or null if none
     */
    public MetricSnapshot getRelatedMetrics() {
        return relatedMetrics;
    }

    /**
     * Builder for Alert.
     */
    public static class Builder {
        private final AlertHandler.AlertType alertType;
        private final String message;
        private double severity = 0.5; // Default severity
        private String source;
        private MetricSnapshot relatedMetrics;

        /**
         * Create a new alert builder.
         *
         * @param alertType the alert type
         * @param message   the alert message
         */
        public Builder(AlertHandler.AlertType alertType, String message) {
            this.alertType = alertType;
            this.message = message;
        }

        /**
         * Set the alert severity (0.0 to 1.0).
         *
         * @param severity the severity level
         * @return builder
         */
        public Builder severity(double severity) {
            if (severity < 0.0 || severity > 1.0) {
                throw new IllegalArgumentException("Severity must be between 0.0 and 1.0");
            }
            this.severity = severity;
            return this;
        }

        /**
         * Set the source of this alert.
         *
         * @param source the source identifier
         * @return builder
         */
        public Builder source(String source) {
            this.source = source;
            return this;
        }

        /**
         * Set related metric snapshot that triggered this alert.
         *
         * @param relatedMetrics the related metric snapshot
         * @return builder
         */
        public Builder relatedMetrics(MetricSnapshot relatedMetrics) {
            this.relatedMetrics = relatedMetrics;
            return this;
        }

        /**
         * Build the Alert instance.
         *
         * @return alert
         */
        public Alert build() {
            return new Alert(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Alert alert = (Alert) o;
        return Double.compare(alert.severity, severity) == 0 &&
                timestamp.equals(alert.timestamp) &&
                alertType == alert.alertType &&
                message.equals(alert.message) &&
                Objects.equals(source, alert.source) &&
                Objects.equals(relatedMetrics, alert.relatedMetrics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, alertType, message, severity, source, relatedMetrics);
    }

    @Override
    public String toString() {
        return "Alert{" +
                "timestamp=" + timestamp +
                ", type=" + alertType +
                ", severity=" + severity +
                ", message='" + message + '\'' +
                ", source='" + source + '\'' +
                '}';
    }
}