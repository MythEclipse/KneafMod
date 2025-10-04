package com.kneaf.core.exceptions.utils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable context information for exceptions. Provides structured context data for better error
 * analysis and debugging.
 */
public final class ExceptionContext {

  private final Map<String, Object> contextData;
  private final String operation;
  private final String component;
  private final String method;
  private final int lineNumber;
  private final long timestamp;

  private ExceptionContext(Builder builder) {
    this.contextData = Collections.unmodifiableMap(new HashMap<>(builder.contextData));
    this.operation = builder.operation;
    this.component = builder.component;
    this.method = builder.method;
    this.lineNumber = builder.lineNumber;
    this.timestamp = builder.timestamp;
  }

  /** Gets the context data map */
  public Map<String, Object> getContextData() {
    return contextData;
  }

  /** Gets a specific context value by key */
  public Object getValue(String key) {
    return contextData.get(key);
  }

  /** Gets the operation name */
  public String getOperation() {
    return operation;
  }

  /** Gets the component name */
  public String getComponent() {
    return component;
  }

  /** Gets the method name */
  public String getMethod() {
    return method;
  }

  /** Gets the line number */
  public int getLineNumber() {
    return lineNumber;
  }

  /** Gets the timestamp when the exception occurred */
  public long getTimestamp() {
    return timestamp;
  }

  /** Creates a builder for ExceptionContext */
  public static Builder builder() {
    return new Builder();
  }

  /** Creates a basic context with operation and component */
  public static ExceptionContext basic(String operation, String component) {
    return builder().operation(operation).component(component).build();
  }

  /** Creates a context for a specific method */
  public static ExceptionContext forMethod(
      String operation, String component, String method, int lineNumber) {
    return builder()
        .operation(operation)
        .component(component)
        .method(method)
        .lineNumber(lineNumber)
        .build();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("ExceptionContext{");

    if (operation != null) {
      sb.append("operation='").append(operation).append('\'');
    }
    if (component != null) {
      sb.append(", component='").append(component).append('\'');
    }
    if (method != null) {
      sb.append(", method='").append(method).append('\'');
    }
    if (lineNumber > 0) {
      sb.append(", lineNumber=").append(lineNumber);
    }
    if (!contextData.isEmpty()) {
      sb.append(", contextData=").append(contextData);
    }
    sb.append(", timestamp=").append(timestamp);
    sb.append('}');

    return sb.toString();
  }

  /** Builder class for ExceptionContext */
  public static class Builder {
    private Map<String, Object> contextData = new HashMap<>();
    private String operation;
    private String component;
    private String method;
    private int lineNumber = -1;
    private long timestamp = System.currentTimeMillis();

    public Builder operation(String operation) {
      this.operation = operation;
      return this;
    }

    public Builder component(String component) {
      this.component = component;
      return this;
    }

    public Builder method(String method) {
      this.method = method;
      return this;
    }

    public Builder lineNumber(int lineNumber) {
      this.lineNumber = lineNumber;
      return this;
    }

    public Builder timestamp(long timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public Builder addContext(String key, Object value) {
      this.contextData.put(key, value);
      return this;
    }

    public Builder addAllContext(Map<String, Object> context) {
      this.contextData.putAll(context);
      return this;
    }

    public ExceptionContext build() {
      return new ExceptionContext(this);
    }
  }
}
