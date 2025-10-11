package com.kneaf.core.performance.unified;

import java.util.Objects;

/**
 * Immutable metadata for performance plugins.
 * Contains essential information about a plugin that doesn't change at runtime.
 */
public final class PluginMetadata {
    private final String pluginId;
    private final String displayName;
    private final String version;
    private final String author;
    private final String description;
    private final String website;
    private final String minimumRequiredVersion;
    private final MonitoringLevel supportedLevel;

    /**
     * Create new plugin metadata.
     *
     * @param builder the builder to create metadata from
     */
    private PluginMetadata(Builder builder) {
        this.pluginId = Objects.requireNonNull(builder.pluginId, "Plugin ID must not be null");
        this.displayName = Objects.requireNonNull(builder.displayName, "Display name must not be null");
        this.version = Objects.requireNonNull(builder.version, "Version must not be null");
        this.author = Objects.requireNonNull(builder.author, "Author must not be null");
        this.description = builder.description;
        this.website = builder.website;
        this.minimumRequiredVersion = builder.minimumRequiredVersion;
        this.supportedLevel = builder.supportedLevel != null ? builder.supportedLevel : MonitoringLevel.BASIC;
    }

    /**
     * Get the unique plugin ID.
     *
     * @return plugin ID
     */
    public String getPluginId() {
        return pluginId;
    }

    /**
     * Get the display name for the plugin.
     *
     * @return display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Get the plugin version.
     *
     * @return version
     */
    public String getVersion() {
        return version;
    }

    /**
     * Get the plugin author.
     *
     * @return author
     */
    public String getAuthor() {
        return author;
    }

    /**
     * Get the plugin description.
     *
     * @return description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Get the plugin website URL.
     *
     * @return website URL
     */
    public String getWebsite() {
        return website;
    }

    /**
     * Get the minimum required system version for this plugin.
     *
     * @return minimum required version
     */
    public String getMinimumRequiredVersion() {
        return minimumRequiredVersion;
    }

    /**
     * Get the supported monitoring level.
     *
     * @return supported monitoring level
     */
    public MonitoringLevel getSupportedLevel() {
        return supportedLevel;
    }

    /**
     * Check if this plugin is compatible with the given system version.
     *
     * @param systemVersion the system version to check against
     * @return true if compatible, false otherwise
     */
    public boolean isCompatible(String systemVersion) {
        if (minimumRequiredVersion == null) {
            return true;
        }
        return compareVersions(systemVersion, minimumRequiredVersion) >= 0;
    }

    /**
     * Compare two version strings.
     * Format: major.minor.patch (e.g., "1.2.3")
     *
     * @param version1 first version to compare
     * @param version2 second version to compare
     * @return negative if version1 < version2, zero if equal, positive if version1 > version2
     */
    private static int compareVersions(String version1, String version2) {
        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");
        
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int v1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int v2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (v1 != v2) {
                return v1 - v2;
            }
        }
        return 0;
    }

    /**
     * Builder for PluginMetadata.
     */
    public static class Builder {
        private final String pluginId;
        private final String displayName;
        private final String version;
        private final String author;
        private String description;
        private String website;
        private String minimumRequiredVersion;
        private MonitoringLevel supportedLevel;

        /**
         * Create a new builder with required fields.
         *
         * @param pluginId    unique plugin ID
         * @param displayName user-friendly display name
         * @param version     plugin version
         * @param author      plugin author
         */
        public Builder(String pluginId, String displayName, String version, String author) {
            this.pluginId = pluginId;
            this.displayName = displayName;
            this.version = version;
            this.author = author;
        }

        /**
         * Set plugin description.
         *
         * @param description plugin description
         * @return builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Set plugin website URL.
         *
         * @param website website URL
         * @return builder
         */
        public Builder website(String website) {
            this.website = website;
            return this;
        }

        /**
         * Set minimum required system version.
         *
         * @param minimumRequiredVersion minimum required version
         * @return builder
         */
        public Builder minimumRequiredVersion(String minimumRequiredVersion) {
            this.minimumRequiredVersion = minimumRequiredVersion;
            return this;
        }

        /**
         * Set supported monitoring level.
         *
         * @param supportedLevel supported monitoring level
         * @return builder
         */
        public Builder supportedLevel(MonitoringLevel supportedLevel) {
            this.supportedLevel = supportedLevel;
            return this;
        }

        /**
         * Build the PluginMetadata instance.
         *
         * @return plugin metadata
         */
        public PluginMetadata build() {
            return new PluginMetadata(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PluginMetadata that = (PluginMetadata) o;
        return pluginId.equals(that.pluginId);
    }

    @Override
    public int hashCode() {
        return pluginId.hashCode();
    }

    @Override
    public String toString() {
        return "PluginMetadata{" +
                "pluginId='" + pluginId + '\'' +
                ", displayName='" + displayName + '\'' +
                ", version='" + version + '\'' +
                ", author='" + author + '\'' +
                '}';
    }
}