package com.kneaf.core.performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import java.time.Instant;

/**
 * Configurable alerting system for performance thresholds.
 * Provides real-time alerting with multiple notification channels and
 * escalation.
 */
public final class AlertingSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger(AlertingSystem.class);

    // Alert configuration
    private final ConcurrentHashMap<String, AlertRule> alertRules = new ConcurrentHashMap<>();

    // Alert state tracking
    private final ConcurrentHashMap<String, AlertState> alertStates = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Alert> activeAlerts = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, Alert> alertHistory = new ConcurrentHashMap<>();

    // Notification channels
    private final List<NotificationChannel> notificationChannels = new ArrayList<>();
    // Use centralized WorkerThreadPool for notifications and alert processing

    // Alert processing
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicBoolean isEnabled = new AtomicBoolean(true);

    // Statistics
    private final AtomicLong alertsTriggered = new AtomicLong(0);
    private final AtomicLong alertsResolved = new AtomicLong(0);
    private final AtomicLong notificationsSent = new AtomicLong(0);
    private final AtomicLong notificationFailures = new AtomicLong(0);

    // Health monitoring
    private final AtomicBoolean isHealthy = new AtomicBoolean(true);
    private final AtomicLong lastSuccessfulAlert = new AtomicLong(System.currentTimeMillis());

    // Default thresholds
    private static final double DEFAULT_LATENCY_THRESHOLD = 50.0; // 50ms
    private static final double DEFAULT_ERROR_RATE_THRESHOLD = 0.05; // 5%
    private static final double DEFAULT_THROUGHPUT_THRESHOLD = 1000.0; // 1000 ops/sec
    private static final double DEFAULT_SUCCESS_RATE_THRESHOLD = 0.95; // 95%
    private static final double DEFAULT_RESOURCE_UTILIZATION_THRESHOLD = 0.9; // 90%

    public AlertingSystem() {
        LOGGER.info("Initializing AlertingSystem");
        initializeDefaultRules();
        startAlertProcessing();
    }

    /**
     * Initialize default alert rules
     */
    private void initializeDefaultRules() {
        // High latency alert
        addAlertRule("HIGH_LATENCY", "Average latency exceeds threshold",
                metrics -> metrics.getOrDefault("system.avg_latency_ms", 0.0) > DEFAULT_LATENCY_THRESHOLD,
                "CRITICAL", 30, 300); // 30 second cooldown, 5 minute escalation

        // High error rate alert
        addAlertRule("HIGH_ERROR_RATE", "Error rate exceeds threshold",
                metrics -> metrics.getOrDefault("system.error_rate", 0.0) > DEFAULT_ERROR_RATE_THRESHOLD,
                "HIGH", 60, 600); // 1 minute cooldown, 10 minute escalation

        // Low throughput alert
        addAlertRule("LOW_THROUGHPUT", "Throughput below threshold",
                metrics -> metrics.getOrDefault("system.throughput_ops_per_sec", 0.0) < DEFAULT_THROUGHPUT_THRESHOLD,
                "MEDIUM", 120, 1200); // 2 minute cooldown, 20 minute escalation

        // Low success rate alert
        addAlertRule("LOW_SUCCESS_RATE", "Success rate below threshold",
                metrics -> metrics.getOrDefault("system.success_rate", 1.0) < DEFAULT_SUCCESS_RATE_THRESHOLD,
                "HIGH", 60, 600); // 1 minute cooldown, 10 minute escalation

        // High resource utilization alert
        addAlertRule("HIGH_RESOURCE_UTILIZATION", "Resource utilization exceeds threshold",
                metrics -> metrics.getOrDefault("system.resource_utilization",
                        0.0) > DEFAULT_RESOURCE_UTILIZATION_THRESHOLD,
                "WARNING", 180, 1800); // 3 minute cooldown, 30 minute escalation

        // Component-specific alerts
        addAlertRule("OPTIMIZATION_INJECTOR_FAILURE", "Optimization injector failure rate high",
                metrics -> metrics.getOrDefault("optimization_injector.async_errors", 0.0) > 10,
                "HIGH", 60, 600);

        addAlertRule("RUST_LIBRARY_NOT_LOADED", "Rust library not loaded",
                metrics -> metrics.getOrDefault("rust_vector_library.pending_operations", 0.0) < 0,
                "CRITICAL", 30, 300);

        addAlertRule("ENTITY_PROCESSING_QUEUE_OVERFLOW", "Entity processing queue overflow",
                metrics -> metrics.getOrDefault("entity_processing.queue_size", 0.0) > 1000,
                "WARNING", 120, 1200);

        LOGGER.info("Default alert rules initialized");
    }

    /**
     * Add custom alert rule
     */
    public void addAlertRule(String ruleId, String description, AlertCondition condition,
            String severity, long cooldownSeconds, long escalationSeconds) {
        AlertRule rule = new AlertRule(ruleId, description, condition, severity,
                cooldownSeconds, escalationSeconds);
        alertRules.put(ruleId, rule);
        LOGGER.info("Added alert rule: {}", ruleId);
    }

    /**
     * Remove alert rule
     */
    public void removeAlertRule(String ruleId) {
        alertRules.remove(ruleId);
        LOGGER.info("Removed alert rule: {}", ruleId);
    }

    /**
     * Trigger an alert
     */
    public void triggerAlert(String ruleId, String message) {
        if (!isEnabled.get()) {
            return;
        }

        try {
            AlertRule rule = alertRules.get(ruleId);
            if (rule == null) {
                LOGGER.warn("Alert rule '{}' not found", ruleId);
                return;
            }

            // Check cooldown
            AlertState state = alertStates.get(ruleId);
            if (state != null && state.isInCooldown()) {
                LOGGER.debug("Alert '{}' in cooldown, skipping", ruleId);
                return;
            }

            // Create alert
            Alert alert = new Alert(ruleId, rule.getSeverity(), message,
                    Instant.now(), rule.getEscalationSeconds());

            // Add to active alerts
            activeAlerts.offer(alert);

            // Update state
            if (state == null) {
                state = new AlertState(ruleId);
                alertStates.put(ruleId, state);
            }
            state.triggerAlert(alert);

            alertsTriggered.incrementAndGet();
            lastSuccessfulAlert.set(System.currentTimeMillis());

            // Send notifications
            sendNotifications(alert);

            LOGGER.info("Alert triggered: {} - {}", ruleId, message);

        } catch (Exception e) {
            LOGGER.error("Error triggering alert '{}'", ruleId, e);
            notificationFailures.incrementAndGet();
            isHealthy.set(false);
        }
    }

    /**
     * Resolve an alert
     */
    public void resolveAlert(String ruleId, String resolutionMessage) {
        try {
            AlertState state = alertStates.get(ruleId);
            if (state == null || !state.isActive()) {
                LOGGER.debug("Alert '{}' not active, cannot resolve", ruleId);
                return;
            }

            // Get the active alert
            Alert alert = findActiveAlert(ruleId);
            if (alert != null) {
                alert.resolve(resolutionMessage, Instant.now());

                // Move to history
                alertHistory.put(alert.getId(), alert);

                // Update state
                state.resolveAlert();

                alertsResolved.incrementAndGet();

                // Send resolution notification
                sendResolutionNotification(alert);

                LOGGER.info("Alert resolved: {} - {}", ruleId, resolutionMessage);
            }

        } catch (Exception e) {
            LOGGER.error("Error resolving alert '{}'", ruleId, e);
        }
    }

    /**
     * Check metrics against alert rules
     */
    public void checkMetrics(Map<String, Double> metrics) {
        if (!isEnabled.get()) {
            return;
        }

        for (AlertRule rule : alertRules.values()) {
            try {
                if (rule.getCondition().evaluate(metrics)) {
                    String message = generateAlertMessage(rule, metrics);
                    triggerAlert(rule.getRuleId(), message);
                }
            } catch (Exception e) {
                LOGGER.error("Error checking alert rule '{}'", rule.getRuleId(), e);
            }
        }
    }

    /**
     * Generate alert message based on metrics
     */
    private String generateAlertMessage(AlertRule rule, Map<String, Double> metrics) {
        StringBuilder message = new StringBuilder();
        message.append(rule.getDescription()).append(": ");

        // Add specific metric values
        switch (rule.getRuleId()) {
            case "HIGH_LATENCY":
                message.append(String.format("Current latency: %.2fms",
                        metrics.getOrDefault("system.avg_latency_ms", 0.0)));
                break;
            case "HIGH_ERROR_RATE":
                message.append(String.format("Current error rate: %.2f%%",
                        metrics.getOrDefault("system.error_rate", 0.0) * 100));
                break;
            case "LOW_THROUGHPUT":
                message.append(String.format("Current throughput: %.2f ops/sec",
                        metrics.getOrDefault("system.throughput_ops_per_sec", 0.0)));
                break;
            case "LOW_SUCCESS_RATE":
                message.append(String.format("Current success rate: %.2f%%",
                        metrics.getOrDefault("system.success_rate", 1.0) * 100));
                break;
            case "HIGH_RESOURCE_UTILIZATION":
                message.append(String.format("Current utilization: %.2f%%",
                        metrics.getOrDefault("system.resource_utilization", 0.0) * 100));
                break;
            default:
                message.append("Threshold exceeded");
        }

        return message.toString();
    }

    /**
     * Find active alert by rule ID
     */
    private Alert findActiveAlert(String ruleId) {
        for (Alert alert : activeAlerts) {
            if (alert.getRuleId().equals(ruleId) && alert.isActive()) {
                return alert;
            }
        }
        return null;
    }

    /**
     * Send notifications for alert
     */
    private void sendNotifications(Alert alert) {
        for (NotificationChannel channel : notificationChannels) {
            com.kneaf.core.WorkerThreadPool.getIOPool().submit(() -> {
                try {
                    channel.sendNotification(alert);
                    notificationsSent.incrementAndGet();
                } catch (Exception e) {
                    LOGGER.error("Error sending notification via channel '{}'", channel.getName(), e);
                    notificationFailures.incrementAndGet();
                }
            });
        }
    }

    /**
     * Send resolution notification
     */
    private void sendResolutionNotification(Alert alert) {
        for (NotificationChannel channel : notificationChannels) {
            com.kneaf.core.WorkerThreadPool.getIOPool().submit(() -> {
                try {
                    channel.sendResolutionNotification(alert);
                } catch (Exception e) {
                    LOGGER.error("Error sending resolution notification via channel '{}'", channel.getName(), e);
                }
            });
        }
    }

    /**
     * Add notification channel
     */
    public void addNotificationChannel(NotificationChannel channel) {
        notificationChannels.add(channel);
        LOGGER.info("Added notification channel: {}", channel.getName());
    }

    /**
     * Remove notification channel
     */
    public void removeNotificationChannel(NotificationChannel channel) {
        notificationChannels.remove(channel);
        LOGGER.info("Removed notification channel: {}", channel.getName());
    }

    /**
     * Start alert processing
     */
    private void startAlertProcessing() {
        if (isProcessing.compareAndSet(false, true)) {
            // Process alerts every 30 seconds
            com.kneaf.core.WorkerThreadPool.getScheduledPool().scheduleAtFixedRate(this::processAlerts, 0, 30,
                    TimeUnit.SECONDS);

            // Escalate alerts every minute
            com.kneaf.core.WorkerThreadPool.getScheduledPool().scheduleAtFixedRate(this::escalateAlerts, 0, 60,
                    TimeUnit.SECONDS);

            LOGGER.info("Alert processing started");
        }
    }

    /**
     * Process active alerts
     */
    private void processAlerts() {
        if (!isEnabled.get()) {
            return;
        }

        try {
            Iterator<Alert> iterator = activeAlerts.iterator();
            while (iterator.hasNext()) {
                Alert alert = iterator.next();

                if (!alert.isActive()) {
                    // Move to history
                    alertHistory.put(alert.getId(), alert);
                    iterator.remove();
                    continue;
                }

                // Check for escalation
                if (alert.shouldEscalate()) {
                    escalateAlert(alert);
                }

                // Update health status
                updateHealthStatus();
            }

        } catch (Exception e) {
            LOGGER.error("Error processing alerts", e);
            isHealthy.set(false);
        }
    }

    /**
     * Escalate alerts
     */
    private void escalateAlerts() {
        for (Alert alert : activeAlerts) {
            if (alert.shouldEscalate()) {
                escalateAlert(alert);
            }
        }
    }

    /**
     * Escalate specific alert
     */
    private void escalateAlert(Alert alert) {
        LOGGER.warn("Escalating alert: {} - {}", alert.getRuleId(), alert.getMessage());

        // Update alert severity
        alert.escalate();

        // Send escalation notification
        sendEscalationNotification(alert);

        // Reset escalation timer
        alert.resetEscalationTimer();
    }

    /**
     * Send escalation notification
     */
    private void sendEscalationNotification(Alert alert) {
        for (NotificationChannel channel : notificationChannels) {
            com.kneaf.core.WorkerThreadPool.getIOPool().submit(() -> {
                try {
                    channel.sendEscalationNotification(alert);
                } catch (Exception e) {
                    LOGGER.error("Error sending escalation notification via channel '{}'", channel.getName(), e);
                }
            });
        }
    }

    /**
     * Get active alerts
     */
    public List<Alert> getActiveAlerts() {
        return new ArrayList<>(activeAlerts);
    }

    /**
     * Get alert history
     */
    public List<Alert> getAlertHistory(int maxCount) {
        List<Alert> history = new ArrayList<>(alertHistory.values());
        history.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        if (history.size() > maxCount) {
            history = history.subList(0, maxCount);
        }

        return history;
    }

    /**
     * Get alert statistics
     */
    public AlertStatistics getStatistics() {
        return new AlertStatistics(
                alertsTriggered.get(),
                alertsResolved.get(),
                activeAlerts.size(),
                alertHistory.size(),
                notificationsSent.get(),
                notificationFailures.get());
    }

    /**
     * Get alert rules
     */
    public List<AlertRule> getAlertRules() {
        return new ArrayList<>(alertRules.values());
    }

    /**
     * Get alert rule by ID
     */
    public AlertRule getAlertRule(String ruleId) {
        return alertRules.get(ruleId);
    }

    /**
     * Clear all alerts
     */
    public void clearAllAlerts() {
        activeAlerts.clear();
        alertHistory.clear();
        alertStates.clear();
        alertsTriggered.set(0);
        alertsResolved.set(0);
        LOGGER.info("All alerts cleared");
    }

    /**
     * Enable/disable alerting
     */
    public void setEnabled(boolean enabled) {
        this.isEnabled.set(enabled);
        if (!enabled) {
            clearAllAlerts();
        }
        LOGGER.info("AlertingSystem {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Check if alerting system is healthy
     */
    public boolean isHealthy() {
        return isHealthy.get() &&
                (System.currentTimeMillis() - lastSuccessfulAlert.get()) < 30000; // 30 second timeout
    }

    /**
     * Shutdown the alerting system
     */
    public void shutdown() {
        LOGGER.info("Shutting down AlertingSystem");

        isProcessing.set(false);
        setEnabled(false);
        clearAllAlerts();

        // Pools managed by WorkerThreadPool

        LOGGER.info("AlertingSystem shutdown completed");
    }

    /**
     * Update health status
     */
    private void updateHealthStatus() {
        boolean healthy = notificationFailures.get() < 10 && // Less than 10 notification failures
                (System.currentTimeMillis() - lastSuccessfulAlert.get()) < 60000; // Within 1 minute
        isHealthy.set(healthy);
    }

    /**
     * Alert rule
     */
    public static class AlertRule {
        private final String ruleId;
        private final String description;
        private final AlertCondition condition;
        private final String severity;
        private final long cooldownSeconds;
        private final long escalationSeconds;

        public AlertRule(String ruleId, String description, AlertCondition condition,
                String severity, long cooldownSeconds, long escalationSeconds) {
            this.ruleId = ruleId;
            this.description = description;
            this.condition = condition;
            this.severity = severity;
            this.cooldownSeconds = cooldownSeconds;
            this.escalationSeconds = escalationSeconds;
        }

        public String getRuleId() {
            return ruleId;
        }

        public String getDescription() {
            return description;
        }

        public AlertCondition getCondition() {
            return condition;
        }

        public String getSeverity() {
            return severity;
        }

        public long getCooldownSeconds() {
            return cooldownSeconds;
        }

        public long getEscalationSeconds() {
            return escalationSeconds;
        }
    }

    /**
     * Alert condition interface
     */
    public interface AlertCondition {
        boolean evaluate(Map<String, Double> metrics);
    }

    /**
     * Alert state
     */
    private static class AlertState {
        private final AtomicLong lastTriggered = new AtomicLong(0);
        private final AtomicBoolean isActive = new AtomicBoolean(false);

        public AlertState(String ruleId) {
        }

        public void triggerAlert(Alert alert) {
            lastTriggered.set(System.currentTimeMillis());
            isActive.set(true);
        }

        public void resolveAlert() {
            isActive.set(false);
        }

        public boolean isInCooldown() {
            long cooldownEnd = lastTriggered.get() + (getCooldownSeconds() * 1000);
            return System.currentTimeMillis() < cooldownEnd;
        }

        public boolean isActive() {
            return isActive.get();
        }

        private long getCooldownSeconds() {
            // This would typically come from the alert rule
            return 60; // Default 1 minute
        }
    }

    /**
     * Alert information
     */
    public static class Alert {
        private final String id;
        private final String ruleId;
        private final String severity;
        private final String message;
        private final Instant createdAt;
        private volatile Instant resolvedAt;
        private volatile String resolutionMessage;
        private final long escalationSeconds;
        private final AtomicInteger escalationLevel = new AtomicInteger(0);
        private final AtomicLong lastEscalation = new AtomicLong(System.currentTimeMillis());
        private volatile boolean active = true;

        public Alert(String ruleId, String severity, String message, Instant createdAt, long escalationSeconds) {
            this.id = UUID.randomUUID().toString();
            this.ruleId = ruleId;
            this.severity = severity;
            this.message = message;
            this.createdAt = createdAt;
            this.resolvedAt = null;
            this.resolutionMessage = null;
            this.escalationSeconds = escalationSeconds;
        }

        public void resolve(String resolutionMessage, Instant resolvedAt) {
            this.resolutionMessage = resolutionMessage;
            this.resolvedAt = resolvedAt;
            this.active = false;
        }

        public void escalate() {
            escalationLevel.incrementAndGet();
            lastEscalation.set(System.currentTimeMillis());
        }

        public void resetEscalationTimer() {
            lastEscalation.set(System.currentTimeMillis());
        }

        public boolean shouldEscalate() {
            long escalationTime = lastEscalation.get() + (escalationSeconds * 1000);
            return System.currentTimeMillis() > escalationTime;
        }

        public boolean isActive() {
            return active;
        }

        public boolean isResolved() {
            return !active;
        }

        public String getId() {
            return id;
        }

        public String getRuleId() {
            return ruleId;
        }

        public String getSeverity() {
            return severity;
        }

        public String getMessage() {
            return message;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public Instant getResolvedAt() {
            return resolvedAt;
        }

        public String getResolutionMessage() {
            return resolutionMessage;
        }

        public int getEscalationLevel() {
            return escalationLevel.get();
        }

        public long getAgeSeconds() {
            return java.time.Duration.between(createdAt, Instant.now()).getSeconds();
        }

        @Override
        public String toString() {
            return String.format("Alert{id='%s', ruleId='%s', severity='%s', message='%s', active=%b, createdAt=%s}",
                    id, ruleId, severity, message, active, createdAt);
        }
    }

    /**
     * Alert threshold
     */
    public static class AlertThreshold {
        private final String metricName;
        private final double thresholdValue;
        private final ThresholdType type;

        public AlertThreshold(String metricName, double thresholdValue, ThresholdType type) {
            this.metricName = metricName;
            this.thresholdValue = thresholdValue;
            this.type = type;
        }

        public boolean isExceeded(double value) {
            switch (type) {
                case GREATER_THAN:
                    return value > thresholdValue;
                case LESS_THAN:
                    return value < thresholdValue;
                case EQUALS:
                    return value == thresholdValue;
                default:
                    return false;
            }
        }

        public String getMetricName() {
            return metricName;
        }

        public double getThresholdValue() {
            return thresholdValue;
        }

        public ThresholdType getType() {
            return type;
        }
    }

    /**
     * Threshold type
     */
    public enum ThresholdType {
        GREATER_THAN, LESS_THAN, EQUALS
    }

    /**
     * Notification channel interface
     */
    public interface NotificationChannel {
        String getName();

        void sendNotification(Alert alert);

        void sendResolutionNotification(Alert alert);

        void sendEscalationNotification(Alert alert);
    }

    /**
     * Alert statistics
     */
    public static class AlertStatistics {
        private final long alertsTriggered;
        private final long alertsResolved;
        private final int activeAlerts;
        private final int alertHistory;
        private final long notificationsSent;
        private final long notificationFailures;

        public AlertStatistics(long alertsTriggered, long alertsResolved, int activeAlerts,
                int alertHistory, long notificationsSent, long notificationFailures) {
            this.alertsTriggered = alertsTriggered;
            this.alertsResolved = alertsResolved;
            this.activeAlerts = activeAlerts;
            this.alertHistory = alertHistory;
            this.notificationsSent = notificationsSent;
            this.notificationFailures = notificationFailures;
        }

        public long getAlertsTriggered() {
            return alertsTriggered;
        }

        public long getAlertsResolved() {
            return alertsResolved;
        }

        public int getActiveAlerts() {
            return activeAlerts;
        }

        public int getAlertHistory() {
            return alertHistory;
        }

        public long getNotificationsSent() {
            return notificationsSent;
        }

        public long getNotificationFailures() {
            return notificationFailures;
        }

        public double getResolutionRate() {
            return alertsTriggered > 0 ? (double) alertsResolved / alertsTriggered : 0.0;
        }

        public double getNotificationFailureRate() {
            return notificationsSent > 0 ? (double) notificationFailures / notificationsSent : 0.0;
        }
    }
}