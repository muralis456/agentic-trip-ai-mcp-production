package com.example.travel.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Configuration
@EnableConfigurationProperties(AppBrowserProperties.class)
@ConditionalOnProperty(prefix = "app.browser", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BrowserLaunchConfig implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(BrowserLaunchConfig.class);

    private final AppBrowserProperties browserProperties;
    private final Environment environment;

    public BrowserLaunchConfig(AppBrowserProperties browserProperties, Environment environment) {
        this.browserProperties = browserProperties;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!(event.getApplicationContext() instanceof WebServerApplicationContext)) {
            return;
        }
        String url = localUrl();
        try {
            if (browserProperties.isUseChrome()) {
                openChrome(url);
            } else {
                openDefaultBrowser(url);
            }
            log.info("Opened browser at {}", url);
        } catch (Exception ex) {
            log.warn("Could not open browser at {}: {}", url, ex.getMessage());
        }
    }

    private String localUrl() {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        return "http://localhost:" + port;
    }

    private void openChrome(String url) throws IOException {
        if (!browserProperties.getChromePath().isBlank()) {
            launch(browserProperties.getChromePath(), url);
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            if (launchQuietly("cmd", "/c", "start", "chrome", url)) {
                return;
            }
            for (String candidate : windowsChromeCandidates()) {
                if (candidate != null && Files.isRegularFile(Path.of(candidate))) {
                    launch(candidate, url);
                    return;
                }
            }
            throw new IOException("Google Chrome not found on PATH or standard install locations");
        }
        if (os.contains("mac")) {
            launch("open", "-a", "Google Chrome", url);
            return;
        }
        if (launchQuietly("google-chrome", url) || launchQuietly("google-chrome-stable", url)) {
            return;
        }
        throw new IOException("google-chrome not found on PATH");
    }

    private static String[] windowsChromeCandidates() {
        return new String[]{
                envPath("PROGRAMFILES", "Google", "Chrome", "Application", "chrome.exe"),
                envPath("PROGRAMFILES(X86)", "Google", "Chrome", "Application", "chrome.exe"),
                envPath("LOCALAPPDATA", "Google", "Chrome", "Application", "chrome.exe")
        };
    }

    private static String envPath(String envVar, String... parts) {
        String root = System.getenv(envVar);
        if (root == null || root.isBlank()) {
            return null;
        }
        Path path = Path.of(root, parts);
        return path.toString();
    }

    private void openDefaultBrowser(String url) throws IOException {
        if (!launchQuietly("cmd", "/c", "start", url)) {
            throw new IOException("Could not open default browser");
        }
    }

    private static void launch(String... command) throws IOException {
        new ProcessBuilder(command).start();
    }

    private static boolean launchQuietly(String... command) {
        try {
            launch(command);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
