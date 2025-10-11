package com.kneaf.core.config.core;

import com.kneaf.core.config.exception.ConfigurationException;

/**
 * Interface for validating configuration objects.
 */
public interface ConfigValidator {

    /**
     * Validate the given configuration object.
     *
     * @param configuration the configuration to validate
     * @throws ConfigurationException if validation fails
     */
    void validate(Object configuration) throws ConfigurationException;

    /**
     * Check if this validator supports the given configuration type.
     *
     * @param configurationType the configuration type to check
     * @return true if this validator can validate the given type, false otherwise
     */
    boolean supports(Class<?> configurationType);
}