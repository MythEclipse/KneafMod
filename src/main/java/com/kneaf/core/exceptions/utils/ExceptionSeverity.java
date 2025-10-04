package com.kneaf.core.exceptions.utils;

/** Enum representing exception severity levels for better error classification and handling. */
public enum ExceptionSeverity {
  /** Critical errors that prevent system operation */
  CRITICAL(1, "CRITICAL", "System critical error - immediate attention required"),

  /** Errors that prevent specific operations but system can continue */
  ERROR(2, "ERROR", "Operation failed - requires attention"),

  /** Warnings that indicate potential issues but don't prevent operation */
  WARNING(3, "WARNING", "Potential issue detected - monitor required"),

  /** Informational messages for debugging and monitoring */
  INFO(4, "INFO", "Informational message - for debugging purposes"),

  /** Debug level messages for detailed troubleshooting */
  DEBUG(5, "DEBUG", "Debug information - detailed troubleshooting");

  private final int level;
  private final String name;
  private final String description;

  ExceptionSeverity(int level, String name, String description) {
    this.level = level;
    this.name = name;
    this.description = description;
  }

  public int getLevel() {
    return level;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  /** Checks if this severity is more severe than the given severity */
  public boolean isMoreSevereThan(ExceptionSeverity other) {
    return this.level < other.level;
  }

  /** Checks if this severity is at least as severe as the given severity */
  public boolean isAtLeast(ExceptionSeverity other) {
    return this.level <= other.level;
  }

  /** Gets the most severe severity from the given severities */
  public static ExceptionSeverity getMostSevere(ExceptionSeverity... severities) {
    if (severities == null || severities.length == 0) {
      return INFO;
    }

    ExceptionSeverity mostSevere = severities[0];
    for (int i = 1; i < severities.length; i++) {
      if (severities[i].isMoreSevereThan(mostSevere)) {
        mostSevere = severities[i];
      }
    }
    return mostSevere;
  }
}
