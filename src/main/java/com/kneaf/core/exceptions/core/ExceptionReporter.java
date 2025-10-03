package com.kneaf.core.exceptions.core;

import com.kneaf.core.exceptions.utils.ExceptionSeverity;
import java.util.Map;

/**
 * Interface for reporting exceptions to external systems.
 * Can be used for monitoring, alerting, or analytics purposes.
 */
public interface ExceptionReporter {
    
    /**
     * Reports an exception with the specified severity
     */
    void reportException(Throwable exception, ExceptionSeverity severity);
    
    /**
     * Reports an exception with the specified severity and context
     */
    void reportException(Throwable exception, ExceptionSeverity severity, String context);
    
    /**
     * Reports an exception with the specified severity, context, and operation
     */
    void reportException(Throwable exception, ExceptionSeverity severity, String context, String operation);
    
    /**
     * Reports an exception with additional metadata
     */
    void reportException(Throwable exception, ExceptionSeverity severity, String context, 
                        String operation, Map<String, Object> metadata);
    
    /**
     * Determines if the reporter is enabled and available
     */
    boolean isEnabled();
    
    /**
     * Gets the reporter name for identification
     */
    String getReporterName();
    
    /**
     * Flushes any buffered reports
     */
    void flush();
}