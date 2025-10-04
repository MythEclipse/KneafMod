package com.kneaf.core.exceptions.core;

import com.kneaf.core.exceptions.utils.ExceptionConstants;
import com.kneaf.core.exceptions.utils.ExceptionContext;

/**
 * Enhanced base exception for all KneafCore operations. Provides unified exception hierarchy with
 * error codes, severity levels, and context information.
 */
public class KneafCoreException extends BaseKneafException {

  /** Error categories for better error classification and handling. */
  public enum ErrorCategory {
    DATABASE_OPERATION(ExceptionConstants.CODE_PREFIX_DB, "Database operation failed"),
    NATIVE_LIBRARY(ExceptionConstants.CODE_PREFIX_NATIVE, "Native library error"),
    ASYNC_PROCESSING(ExceptionConstants.CODE_PREFIX_ASYNC, "Async processing error"),
    CONFIGURATION(ExceptionConstants.CODE_PREFIX_CONFIG, "Configuration error"),
    RESOURCE_MANAGEMENT(ExceptionConstants.CODE_PREFIX_SYSTEM, "Resource management error"),
    PROTOCOL_ERROR(ExceptionConstants.CODE_PREFIX_SYSTEM, "Protocol error"),
    VALIDATION_ERROR(ExceptionConstants.CODE_PREFIX_VALIDATION, "Validation error"),
    SYSTEM_ERROR(ExceptionConstants.CODE_PREFIX_SYSTEM, "System error");

    private final String codePrefix;
    private final String description;

    ErrorCategory(String codePrefix, String description) {
      this.codePrefix = codePrefix;
      this.description = description;
    }

    public String getCodePrefix() {
      return codePrefix;
    }

    public String getDescription() {
      return description;
    }
  }

  private final ErrorCategory category;
  private final String operation;

  private KneafCoreException(Builder builder) {
    super(builder);
    this.category = builder.category;
    this.operation = builder.operation;
  }

  /** Gets the error category for this exception */
  public ErrorCategory getCategory() {
    return category;
  }

  /** Gets the operation name for this exception */
  public String getOperation() {
    return operation;
  }

  @Override
  public KneafCoreException withContext(ExceptionContext context) {
    return new Builder()
        .message(getMessage())
        .cause(getCause())
        .errorCode(getErrorCode())
        .severity(getSeverity())
        .context(context)
        .suggestion(getSuggestion())
        .logged(isLogged())
        .category(category)
        .operation(operation)
        .build();
  }

  @Override
  public KneafCoreException withSuggestion(String suggestion) {
    return new Builder()
        .message(getMessage())
        .cause(getCause())
        .errorCode(getErrorCode())
        .severity(getSeverity())
        .context(getContext())
        .suggestion(suggestion)
        .logged(isLogged())
        .category(category)
        .operation(operation)
        .build();
  }

  /** Creates a builder for KneafCoreException */
  public static Builder builder() {
    return new Builder();
  }

  /** Creates a simple exception with category and message */
  public static KneafCoreException of(ErrorCategory category, String message) {
    return builder()
        .category(category)
        .message(message)
        .errorCode(category.getCodePrefix() + "000")
        .build();
  }

  /** Creates an exception with category, operation, and message */
  public static KneafCoreException of(ErrorCategory category, String operation, String message) {
    return builder()
        .category(category)
        .operation(operation)
        .message(message)
        .errorCode(category.getCodePrefix() + "000")
        .build();
  }

  /** Creates an exception with category, operation, message, and cause */
  public static KneafCoreException of(
      ErrorCategory category, String operation, String message, Throwable cause) {
    return builder()
        .category(category)
        .operation(operation)
        .message(message)
        .cause(cause)
        .errorCode(category.getCodePrefix() + "000")
        .build();
  }

  /** Builder class for KneafCoreException */
  public static class Builder extends BaseKneafException.Builder<Builder> {
    private ErrorCategory category;
    private String operation;

    @Override
    protected Builder self() {
      return this;
    }

    public Builder category(ErrorCategory category) {
      this.category = category;
      return this;
    }

    public Builder operation(String operation) {
      this.operation = operation;
      return this;
    }

    @Override
    protected void validate() {
      super.validate();
      if (category == null) {
        throw new IllegalArgumentException("Error category cannot be null");
      }
    }

    public KneafCoreException build() {
      validate();

      // Auto-generate error code if not provided
      if (errorCode == null) {
        errorCode = category.getCodePrefix() + "000";
      }

      return new KneafCoreException(this);
    }
  }
}
