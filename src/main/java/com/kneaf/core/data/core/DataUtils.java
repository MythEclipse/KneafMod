package com.kneaf.core.data.core;

/** Utility methods for data processing and validation. */
public final class DataUtils {

  private DataUtils() {
    // Private constructor to prevent instantiation
  }

  /**
   * Validates that a coordinate is within valid bounds.
   *
   * @param coordinate the coordinate to validate
   * @param fieldName the name of the field for error reporting
   * @throws DataValidationException if the coordinate is invalid
   */
  public static void validateCoordinate(double coordinate, String fieldName) {
    if (coordinate < DataConstants.MIN_COORDINATE || coordinate > DataConstants.MAX_COORDINATE) {
      throw new DataValidationException(
          fieldName,
          coordinate,
          String.format(
              "Coordinate must be between %.1f and %.1f",
              DataConstants.MIN_COORDINATE, DataConstants.MAX_COORDINATE));
    }
  }

  /**
   * Validates that a chunk coordinate is within valid bounds.
   *
   * @param chunkCoordinate the chunk coordinate to validate
   * @param fieldName the name of the field for error reporting
   * @throws DataValidationException if the chunk coordinate is invalid
   */
  public static void validateChunkCoordinate(int chunkCoordinate, String fieldName) {
    if (chunkCoordinate < DataConstants.MIN_CHUNK_COORDINATE
        || chunkCoordinate > DataConstants.MAX_CHUNK_COORDINATE) {
      throw new DataValidationException(
          fieldName,
          chunkCoordinate,
          String.format(
              "Chunk coordinate must be between %d and %d",
              DataConstants.MIN_CHUNK_COORDINATE, DataConstants.MAX_CHUNK_COORDINATE));
    }
  }

  /**
   * Validates that a distance is within valid bounds.
   *
   * @param distance the distance to validate
   * @param fieldName the name of the field for error reporting
   * @throws DataValidationException if the distance is invalid
   */
  public static void validateDistance(double distance, String fieldName) {
    if (distance < DataConstants.MIN_DISTANCE || distance > DataConstants.MAX_DISTANCE) {
      throw new DataValidationException(
          fieldName,
          distance,
          String.format(
              "Distance must be between %.1f and %.1f",
              DataConstants.MIN_DISTANCE, DataConstants.MAX_DISTANCE));
    }
  }

  /**
   * Validates that an item count is within valid bounds.
   *
   * @param count the item count to validate
   * @param fieldName the name of the field for error reporting
   * @throws DataValidationException if the count is invalid
   */
  public static void validateItemCount(int count, String fieldName) {
    if (count < DataConstants.MIN_ITEM_COUNT || count > DataConstants.MAX_ITEM_COUNT) {
      throw new DataValidationException(
          fieldName,
          count,
          String.format(
              "Item count must be between %d and %d",
              DataConstants.MIN_ITEM_COUNT, DataConstants.MAX_ITEM_COUNT));
    }
  }

  /**
   * Validates that an item age is within valid bounds.
   *
   * @param age the item age to validate
   * @param fieldName the name of the field for error reporting
   * @throws DataValidationException if the age is invalid
   */
  public static void validateItemAge(int age, String fieldName) {
    if (age < DataConstants.MIN_ITEM_AGE || age > DataConstants.MAX_ITEM_AGE) {
      throw new DataValidationException(
          fieldName,
          age,
          String.format(
              "Item age must be between %d and %d",
              DataConstants.MIN_ITEM_AGE, DataConstants.MAX_ITEM_AGE));
    }
  }

  /**
   * Validates that a string is not null or empty.
   *
   * @param value the string to validate
   * @param fieldName the name of the field for error reporting
   * @throws DataValidationException if the string is invalid
   */
  public static void validateNotEmpty(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new DataValidationException(fieldName, value, "String must not be null or empty");
    }
  }

  /**
   * Validates that a value is not negative.
   *
   * @param value the value to validate
   * @param fieldName the name of the field for error reporting
   * @throws DataValidationException if the value is negative
   */
  public static void validateNonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new DataValidationException(fieldName, value, "Value must not be negative");
    }
  }

  /**
   * Validates that a value is not negative.
   *
   * @param value the value to validate
   * @param fieldName the name of the field for error reporting
   * @throws DataValidationException if the value is negative
   */
  public static void validateNonNegative(int value, String fieldName) {
    if (value < 0) {
      throw new DataValidationException(fieldName, value, "Value must not be negative");
    }
  }

  /**
   * Calculates the distance between two points.
   *
   * @param x1 the X coordinate of the first point
   * @param y1 the Y coordinate of the first point
   * @param z1 the Z coordinate of the first point
   * @param x2 the X coordinate of the second point
   * @param y2 the Y coordinate of the second point
   * @param z2 the Z coordinate of the second point
   * @return the distance between the two points
   */
  public static double calculateDistance(
      double x1, double y1, double z1, double x2, double y2, double z2) {
    double dx = x2 - x1;
    double dy = y2 - y1;
    double dz = z2 - z1;
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }
}
