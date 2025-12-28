package com.kneaf.core.performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Minecraft-specific imports commented out for test compatibility
// import com.mojang.logging.LogUtils;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Cross-component event bus for coordinating performance events between
 * different system components.
 * Provides pub/sub messaging with thread-safe event delivery and filtering.
 */
public final class CrossComponentEventBus {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrossComponentEventBus.class);

    // Event subscribers
    private final ConcurrentHashMap<String, List<EventSubscriber>> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Consumer<CrossComponentEvent>>> consumers = new ConcurrentHashMap<>();

    // Event queue for async processing
    private final BlockingQueue<CrossComponentEvent> eventQueue = new LinkedBlockingQueue<>(10000);
    // Use centralized WorkerThreadPool for event processing

    // Event processing state
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicBoolean isEnabled = new AtomicBoolean(true);
    private final AtomicLong eventsPublished = new AtomicLong(0);
    private final AtomicLong eventsDelivered = new AtomicLong(0);
    private final AtomicLong eventsDropped = new AtomicLong(0);

    // Health monitoring
    private final AtomicBoolean isHealthy = new AtomicBoolean(true);
    private final AtomicLong lastSuccessfulDelivery = new AtomicLong(System.currentTimeMillis());

    public CrossComponentEventBus() {
        LOGGER.info("Initializing CrossComponentEventBus");
        startEventProcessing();
    }

    /**
     * Publish an event to the bus
     */
    public void publishEvent(CrossComponentEvent event) {
        if (!isEnabled.get()) {
            return;
        }

        try {
            // Add to queue for async processing
            if (eventQueue.offer(event)) {
                eventsPublished.incrementAndGet();
                updateHealthStatus();
            } else {
                eventsDropped.incrementAndGet();
                LOGGER.warn("Event queue full, dropping event: {}", event.getEventType());
                isHealthy.set(false);
            }
        } catch (Exception e) {
            LOGGER.error("Error publishing event: " + event.getEventType(), e);
            eventsDropped.incrementAndGet();
            isHealthy.set(false);
        }
    }

    /**
     * Subscribe to events by component and event type
     */
    public void subscribe(String component, String eventType, EventSubscriber subscriber) {
        if (!isEnabled.get()) {
            return;
        }

        try {
            String key = createSubscriptionKey(component, eventType);
            subscribers.computeIfAbsent(key, k -> new ArrayList<>()).add(subscriber);
            LOGGER.debug("Subscribed {} to {} events from {}", subscriber, eventType, component);
        } catch (Exception e) {
            LOGGER.error("Error subscribing to events", e);
            isHealthy.set(false);
        }
    }

    /**
     * Subscribe to events with lambda consumer
     */
    public void subscribe(String component, String eventType, Consumer<CrossComponentEvent> consumer) {
        if (!isEnabled.get()) {
            return;
        }

        try {
            String key = createSubscriptionKey(component, eventType);
            consumers.computeIfAbsent(key, k -> new ArrayList<>()).add(consumer);
            LOGGER.debug("Subscribed consumer to {} events from {}", eventType, component);
        } catch (Exception e) {
            LOGGER.error("Error subscribing to events", e);
            isHealthy.set(false);
        }
    }

    /**
     * Unsubscribe from events
     */
    public void unsubscribe(String component, String eventType, EventSubscriber subscriber) {
        try {
            String key = createSubscriptionKey(component, eventType);
            List<EventSubscriber> subscriberList = subscribers.get(key);
            if (subscriberList != null) {
                subscriberList.remove(subscriber);
            }
        } catch (Exception e) {
            LOGGER.error("Error unsubscribing from events", e);
        }
    }

    /**
     * Unsubscribe consumer from events
     */
    public void unsubscribe(String component, String eventType, Consumer<CrossComponentEvent> consumer) {
        try {
            String key = createSubscriptionKey(component, eventType);
            List<Consumer<CrossComponentEvent>> consumerList = consumers.get(key);
            if (consumerList != null) {
                consumerList.remove(consumer);
            }
        } catch (Exception e) {
            LOGGER.error("Error unsubscribing from events", e);
        }
    }

    /**
     * Start event processing
     */
    private void startEventProcessing() {
        if (isProcessing.compareAndSet(false, true)) {
            com.kneaf.core.WorkerThreadPool.getIOPool().submit(this::processEvents);
            com.kneaf.core.WorkerThreadPool.getIOPool().submit(this::processEvents); // Second thread for parallel
                                                                                     // processing
            LOGGER.info("Event processing started");
        }
    }

    /**
     * Process events from the queue
     */
    private void processEvents() {
        while (isProcessing.get()) {
            try {
                CrossComponentEvent event = eventQueue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    deliverEvent(event);
                    updateHealthStatus();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.error("Error processing events", e);
                isHealthy.set(false);
            }
        }
    }

    /**
     * Deliver an event to all matching subscribers
     */
    private void deliverEvent(CrossComponentEvent event) {
        String component = event.getComponent();
        String eventType = event.getEventType();

        // Deliver to specific subscribers
        String specificKey = createSubscriptionKey(component, eventType);
        deliverToSubscribers(event, specificKey);

        // Deliver to wildcard subscribers (all components, specific event type)
        String wildcardComponentKey = createSubscriptionKey("*", eventType);
        deliverToSubscribers(event, wildcardComponentKey);

        // Deliver to wildcard subscribers (specific component, all event types)
        String wildcardEventKey = createSubscriptionKey(component, "*");
        deliverToSubscribers(event, wildcardEventKey);

        // Deliver to global wildcard subscribers
        String globalWildcardKey = createSubscriptionKey("*", "*");
        deliverToSubscribers(event, globalWildcardKey);

        eventsDelivered.incrementAndGet();
        lastSuccessfulDelivery.set(System.currentTimeMillis());
    }

    /**
     * Deliver event to subscribers for a specific key
     */
    private void deliverToSubscribers(CrossComponentEvent event, String key) {
        // Deliver to EventSubscriber instances
        List<EventSubscriber> subscriberList = subscribers.get(key);
        if (subscriberList != null) {
            for (EventSubscriber subscriber : subscriberList) {
                try {
                    subscriber.onEvent(event);
                } catch (Exception e) {
                    LOGGER.error("Error delivering event to subscriber: " + subscriber, e);
                }
            }
        }

        // Deliver to lambda consumers
        List<Consumer<CrossComponentEvent>> consumerList = consumers.get(key);
        if (consumerList != null) {
            for (Consumer<CrossComponentEvent> consumer : consumerList) {
                try {
                    consumer.accept(event);
                } catch (Exception e) {
                    LOGGER.error("Error delivering event to consumer", e);
                }
            }
        }
    }

    /**
     * Create subscription key from component and event type
     */
    private String createSubscriptionKey(String component, String eventType) {
        return component + ":" + eventType;
    }

    /**
     * Get event statistics
     */
    public EventStatistics getStatistics() {
        return new EventStatistics(
                eventsPublished.get(),
                eventsDelivered.get(),
                eventsDropped.get(),
                eventQueue.size(),
                subscribers.size(),
                consumers.size());
    }

    /**
     * Get current event queue size
     */
    public int getQueueSize() {
        return eventQueue.size();
    }

    /**
     * Clear all subscribers
     */
    public void clearSubscribers() {
        subscribers.clear();
        consumers.clear();
        LOGGER.info("All subscribers cleared");
    }

    /**
     * Enable/disable event bus
     */
    public void setEnabled(boolean enabled) {
        this.isEnabled.set(enabled);
        if (!enabled) {
            clearSubscribers();
        }
        LOGGER.info("CrossComponentEventBus {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Check if event bus is healthy
     */
    public boolean isHealthy() {
        return isHealthy.get() &&
                (System.currentTimeMillis() - lastSuccessfulDelivery.get()) < 30000; // 30 second timeout
    }

    /**
     * Shutdown the event bus
     */
    public void shutdown() {
        LOGGER.info("Shutting down CrossComponentEventBus");

        isProcessing.set(false);
        setEnabled(false);

        // Clear remaining events
        eventQueue.clear();
        clearSubscribers();

        // Pool managed by WorkerThreadPool

        LOGGER.info("CrossComponentEventBus shutdown completed");
    }

    /**
     * Update health status
     */
    private void updateHealthStatus() {
        boolean healthy = eventsDropped.get() < 100 && // Less than 100 dropped events
                (System.currentTimeMillis() - lastSuccessfulDelivery.get()) < 60000; // Within 1 minute
        isHealthy.set(healthy);
    }

    /**
     * Event statistics
     */
    public static class EventStatistics {
        private final long eventsPublished;
        private final long eventsDelivered;
        private final long eventsDropped;
        private final int queueSize;
        private final int subscriberCount;
        private final int consumerCount;

        public EventStatistics(long eventsPublished, long eventsDelivered, long eventsDropped,
                int queueSize, int subscriberCount, int consumerCount) {
            this.eventsPublished = eventsPublished;
            this.eventsDelivered = eventsDelivered;
            this.eventsDropped = eventsDropped;
            this.queueSize = queueSize;
            this.subscriberCount = subscriberCount;
            this.consumerCount = consumerCount;
        }

        public long getEventsPublished() {
            return eventsPublished;
        }

        public long getEventsDelivered() {
            return eventsDelivered;
        }

        public long getEventsDropped() {
            return eventsDropped;
        }

        public int getQueueSize() {
            return queueSize;
        }

        public int getSubscriberCount() {
            return subscriberCount;
        }

        public int getConsumerCount() {
            return consumerCount;
        }

        public double getDeliveryRate() {
            return eventsPublished > 0 ? (double) eventsDelivered / eventsPublished : 0.0;
        }

        public double getDropRate() {
            return eventsPublished > 0 ? (double) eventsDropped / eventsPublished : 0.0;
        }
    }

    /**
     * Event subscriber interface
     */
    public interface EventSubscriber {
        void onEvent(CrossComponentEvent event);
    }
}