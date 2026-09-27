package com.example.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.browser")
public class AppBrowserProperties {

    private boolean enabled = true;
    private boolean useChrome = true;
    /** Optional full path to chrome.exe when not on PATH. */
    private String chromePath = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isUseChrome() {
        return useChrome;
    }

    public void setUseChrome(boolean useChrome) {
        this.useChrome = useChrome;
    }

    public String getChromePath() {
        return chromePath;
    }

    public void setChromePath(String chromePath) {
        this.chromePath = chromePath == null ? "" : chromePath.trim();
    }
}
