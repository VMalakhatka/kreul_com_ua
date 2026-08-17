package org.example.folioruslab.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "folio.rus")
public class FolioRusProperties {

    public static final String EXPECTED_DATABASE = "Paint_Rus";
    public static final String EXPECTED_COLLATION = "SQL_Ukrainian_CP1251_CI_AS";

    @NotBlank
    @Pattern(
            regexp = "^(?!REPLACE_WITH_)[A-Za-z0-9._-]+$",
            message = "must be a real host name or IPv4 address without a URL scheme"
    )
    private String host;

    @Min(1)
    @Max(65535)
    private int port = 1433;

    @NotBlank
    @Pattern(regexp = "^(?!REPLACE_WITH_).+$", message = "must not be an example placeholder")
    private String username;

    @NotBlank
    @Pattern(regexp = "^(?!REPLACE_WITH_).+$", message = "must not be an example placeholder")
    private String password;

    @Min(1)
    @Max(60)
    private int loginTimeoutSeconds = 10;

    @Min(5)
    @Max(600)
    private int socketTimeoutSeconds = 90;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getLoginTimeoutSeconds() {
        return loginTimeoutSeconds;
    }

    public void setLoginTimeoutSeconds(int loginTimeoutSeconds) {
        this.loginTimeoutSeconds = loginTimeoutSeconds;
    }

    public int getSocketTimeoutSeconds() {
        return socketTimeoutSeconds;
    }

    public void setSocketTimeoutSeconds(int socketTimeoutSeconds) {
        this.socketTimeoutSeconds = socketTimeoutSeconds;
    }
}
