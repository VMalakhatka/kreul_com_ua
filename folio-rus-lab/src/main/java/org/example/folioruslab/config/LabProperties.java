package org.example.folioruslab.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "folio.lab")
public class LabProperties {

    @NotBlank
    @Size(min = 32, max = 512)
    @Pattern(
            regexp = "^(?!REPLACE_WITH_)[\\x21-\\x7E]+$",
            message = "must be a real token containing only printable non-space ASCII characters"
    )
    private String token;

    @Min(1)
    @Max(300)
    private int defaultTimeoutSeconds = 30;

    @Min(1)
    @Max(300)
    private int maximumTimeoutSeconds = 60;

    @Min(1)
    @Max(100000)
    private int defaultMaxRows = 5000;

    @Min(1)
    @Max(100000)
    private int maximumMaxRows = 20000;

    @Min(1024)
    @Max(52428800)
    private long defaultMaxBytes = 5_242_880;

    @Min(1024)
    @Max(52428800)
    private long maximumMaxBytes = 10_485_760;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public int getMaximumTimeoutSeconds() {
        return maximumTimeoutSeconds;
    }

    public void setMaximumTimeoutSeconds(int maximumTimeoutSeconds) {
        this.maximumTimeoutSeconds = maximumTimeoutSeconds;
    }

    public int getDefaultMaxRows() {
        return defaultMaxRows;
    }

    public void setDefaultMaxRows(int defaultMaxRows) {
        this.defaultMaxRows = defaultMaxRows;
    }

    public int getMaximumMaxRows() {
        return maximumMaxRows;
    }

    public void setMaximumMaxRows(int maximumMaxRows) {
        this.maximumMaxRows = maximumMaxRows;
    }

    public long getDefaultMaxBytes() {
        return defaultMaxBytes;
    }

    public void setDefaultMaxBytes(long defaultMaxBytes) {
        this.defaultMaxBytes = defaultMaxBytes;
    }

    public long getMaximumMaxBytes() {
        return maximumMaxBytes;
    }

    public void setMaximumMaxBytes(long maximumMaxBytes) {
        this.maximumMaxBytes = maximumMaxBytes;
    }
}
