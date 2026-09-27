package com.example.travel.dev;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Development-only launcher for the native Windows Mailpit executable.
 *
 * Docker is intentionally not required. If Mailpit is already running on the
 * configured SMTP port, this component leaves it alone. Otherwise it starts
 * the configured executable and waits briefly for SMTP to become available.
 */
@Component
@Profile("dev")
public class NativeMailpitManager {

    private static final Logger log = LoggerFactory.getLogger(NativeMailpitManager.class);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(10);

    private final boolean enabled;
    private final String executable;
    private final String host;
    private final int smtpPort;
    private final int uiPort;
    private final Path database;
    private final int maxMessages;

    /** The Mailpit process started by this application, if any. */
    private volatile Process managedProcess;

    public NativeMailpitManager(
            @Value("${travel.mailpit.enabled:true}") boolean enabled,
            @Value("${MAILPIT_EXE:C:/softwares/mailpit-windows-amd64/mailpit.exe}") String executable,
            @Value("${travel.mailpit.host:localhost}") String host,
            @Value("${travel.mailpit.smtp-port:1025}") int smtpPort,
            @Value("${travel.mailpit.ui-port:8025}") int uiPort,
            @Value("${travel.mailpit.database:./data/mailpit/mailpit.db}") String database,
            @Value("${travel.mailpit.max-messages:0}") int maxMessages) {
        this.enabled = enabled;
        this.executable = executable;
        this.host = host;
        this.smtpPort = smtpPort;
        this.uiPort = uiPort;
        this.database = Path.of(database).toAbsolutePath().normalize();
        this.maxMessages = maxMessages;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startMailpitIfNeeded() {
        if (!enabled) {
            log.info("Native Mailpit auto-start is disabled.");
            return;
        }

        if (isPortOpen(smtpPort)) {
            log.info("Mailpit is already running on {}:{}; reusing the existing process. Inbox: http://{}:{}",
                    host, smtpPort, host, uiPort);
            return;
        }

        Path executablePath = Path.of(executable);
        if (!Files.isRegularFile(executablePath)) {
            log.warn("Mailpit executable not found at '{}'. Set MAILPIT_EXE to the full path of mailpit.exe "
                    + "or start Mailpit manually. Expected SMTP: {}:{}, UI: http://{}:{}",
                    executablePath, host, smtpPort, host, uiPort);
            return;
        }

        try {
            Path databaseParent = database.getParent();
            if (databaseParent != null) {
                Files.createDirectories(databaseParent);
            }

            ProcessBuilder processBuilder = new ProcessBuilder(
                    executablePath.toString(),
                    "--database", database.toString(),
                    "--max", Integer.toString(maxMessages));
            processBuilder
                    .directory(executablePath.getParent().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT);

            log.info("Starting native Mailpit with persistent database '{}' and max-messages={}",
                    database, maxMessages == 0 ? "unlimited" : maxMessages);

            Process process = processBuilder.start();

            // Only this process is owned by the application. If Mailpit was already
            // running before startup, managedProcess remains null and shutdown will
            // not touch the existing process.
            managedProcess = process;

            if (waitForPort(smtpPort, STARTUP_TIMEOUT)) {
                log.info("Native Mailpit started successfully. SMTP: {}:{}, Inbox: http://{}:{}",
                        host, smtpPort, host, uiPort);
            } else if (process.isAlive()) {
                log.warn("Mailpit process started but SMTP port {} was not ready within {} seconds. "
                        + "Check Mailpit output for details.", smtpPort, STARTUP_TIMEOUT.toSeconds());
            } else {
                int exitCode = process.exitValue();
                log.warn("Mailpit exited immediately with code {}. Start it manually with '{}'.",
                        exitCode, executablePath);
            }
        } catch (IOException ex) {
            log.warn("Unable to start native Mailpit from '{}'. Start it manually or set MAILPIT_EXE. "
                    + "Persistent database path: {}",
                    executablePath, database, ex);
        }
    }

    /**
     * Stops only the Mailpit process that this application started.
     *
     * This is invoked during a normal Spring application shutdown, for example
     * when Spring Boot Dashboard stops the application. A Mailpit process that
     * was started manually is deliberately left running.
     */
    @PreDestroy
    public void stopManagedMailpit() {
        Process process = managedProcess;
        managedProcess = null;

        if (process == null) {
            return;
        }

        if (!process.isAlive()) {
            log.info("Native Mailpit process has already stopped.");
            return;
        }

        log.info("Stopping native Mailpit process started by AgenticTripAI...");
        process.destroy();

        try {
            if (!process.waitFor(5, TimeUnit.SECONDS) && process.isAlive()) {
                log.warn("Mailpit did not stop gracefully within 5 seconds; forcing termination.");
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private boolean waitForPort(int port, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (isPortOpen(port)) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isPortOpen(port);
    }

    private boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 250);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
