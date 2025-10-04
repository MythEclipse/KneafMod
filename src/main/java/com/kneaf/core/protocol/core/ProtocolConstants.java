package com.kneaf.core.protocol.core;

/** Constants for protocol operations, formats, and configurations. */
public final class ProtocolConstants {

  // Prevent instantiation
  private ProtocolConstants() {
    throw new UnsupportedOperationException("Utility class");
  }

  // Protocol formats
  public static final String FORMAT_BINARY = "binary";
  public static final String FORMAT_JSON = "json";
  public static final String FORMAT_XML = "xml";
  public static final String FORMAT_PROTOBUF = "protobuf";

  // Protocol versions
  public static final String VERSION_1_0 = "1.0";
  public static final String VERSION_2_0 = "2.0";
  public static final String CURRENT_VERSION = VERSION_2_0;

  // Size limits
  public static final int MAX_BINARY_SIZE = 50 * 1024 * 1024; // 50MB
  public static final int MAX_JSON_SIZE = 10 * 1024 * 1024; // 10MB
  public static final int MAX_STRING_LENGTH = 1000000;
  public static final int MAX_ARRAY_LENGTH = 100000;

  // Timeout values (milliseconds)
  public static final long DEFAULT_TIMEOUT_MS = 30000; // 30 seconds
  public static final long LONG_TIMEOUT_MS = 120000; // 2 minutes
  public static final int RETRY_ATTEMPTS = 3;
  public static final long RETRY_DELAY_MS = 1000; // 1 second

  // Error codes
  public static final int ERROR_CODE_SUCCESS = 0;
  public static final int ERROR_CODE_VALIDATION_FAILED = 1001;
  public static final int ERROR_CODE_FORMAT_UNSUPPORTED = 1002;
  public static final int ERROR_CODE_VERSION_MISMATCH = 1003;
  public static final int ERROR_CODE_SIZE_EXCEEDED = 1004;
  public static final int ERROR_CODE_INTEGRITY_FAILED = 1005;
  public static final int ERROR_CODE_TIMEOUT = 1006;
  public static final int ERROR_CODE_NATIVE_FAILED = 1007;
  public static final int ERROR_CODE_SERIALIZATION_FAILED = 1008;

  // Log levels
  public static final String LOG_LEVEL_DEBUG = "DEBUG";
  public static final String LOG_LEVEL_INFO = "INFO";
  public static final String LOG_LEVEL_WARN = "WARN";
  public static final String LOG_LEVEL_ERROR = "ERROR";

  // Content types
  public static final String CONTENT_TYPE_BINARY = "application/octet-stream";
  public static final String CONTENT_TYPE_JSON = "application/json";
  public static final String CONTENT_TYPE_XML = "application/xml";
  public static final String CONTENT_TYPE_PROTOBUF = "application/x-protobuf";

  // Encoding
  public static final String ENCODING_UTF8 = "UTF-8";
  public static final String ENCODING_UTF16 = "UTF-16";

  // Metadata keys
  public static final String META_TRACE_ID = "traceId";
  public static final String META_OPERATION = "operation";
  public static final String META_FORMAT = "format";
  public static final String META_VERSION = "version";
  public static final String META_SIZE = "size";
  public static final String META_DURATION = "duration";
  public static final String META_SUCCESS = "success";
  public static final String META_ERROR_CODE = "errorCode";
  public static final String META_ERROR_MESSAGE = "errorMessage";

  // Command constants
  public static final int COMMAND_PERMISSION_LEVEL_USER = 0;
  public static final int COMMAND_PERMISSION_LEVEL_MODERATOR = 2;
  public static final int COMMAND_PERMISSION_LEVEL_ADMIN = 4;

  // Validation rules
  public static final String VALIDATION_FORMAT = "format";
  public static final String VALIDATION_VERSION = "version";
  public static final String VALIDATION_SIZE = "size";
  public static final String VALIDATION_INTEGRITY = "integrity";
  public static final String VALIDATION_SCHEMA = "schema";
  public static final String VALIDATION_BUSINESS_RULES = "business_rules";
}
