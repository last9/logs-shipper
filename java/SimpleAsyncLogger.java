import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple Async Logger for Last9
 * Minimal implementation with core async logging features
 */
public class SimpleAsyncLogger {
    
    // Configuration
    private final String username;
    private final String password;
    private final String host;
    private final String serviceName;
    private final int batchSize;
    private final Duration flushInterval;
    
    // Internal state
    private final ConcurrentLinkedQueue<Map<String, Object>> logQueue;
    private final AtomicBoolean running;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService executor;
    
    // HTTP handling
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpointUrl;
    private final String authHeader;
    
    public SimpleAsyncLogger(String username, String password, String host, String serviceName) {
        this(username, password, host, serviceName, 50, Duration.ofSeconds(5));
    }
    
    public SimpleAsyncLogger(String username, String password, String host, String serviceName, 
                           int batchSize, Duration flushInterval) {
        this.username = username;
        this.password = password;
        this.host = host;
        this.serviceName = serviceName;
        this.batchSize = batchSize;
        this.flushInterval = flushInterval;
        
        // Initialize components
        this.logQueue = new ConcurrentLinkedQueue<>();
        this.running = new AtomicBoolean(true);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.executor = Executors.newCachedThreadPool();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
        
        // Build endpoint URL and auth header (same as Ruby version)
        this.endpointUrl = buildEndpointUrl();
        this.authHeader = buildAuthHeader();
        
        // Start background worker
        startWorker();
        
        System.out.println("SimpleAsyncLogger started for service: " + serviceName);
    }
    
    /**
     * Log a message asynchronously (non-blocking)
     */
    public void log(Object data) {
        if (!running.get()) {
            return;
        }
        
        try {
            Map<String, Object> logEntry = prepareLogEntry(data);
            logQueue.offer(logEntry);
            
            // Auto-flush if batch size reached
            if (logQueue.size() >= batchSize) {
                triggerFlush();
            }
        } catch (Exception e) {
            System.err.println("Failed to queue log entry: " + e.getMessage());
        }
    }
    
    /**
     * Force flush all pending logs
     */
    public void flush() {
        triggerFlush();
    }
    
    /**
     * Graceful shutdown
     */
    public void shutdown() {
        System.out.println("Shutting down SimpleAsyncLogger...");
        running.set(false);
        
        // Flush remaining logs
        if (!logQueue.isEmpty()) {
            flushLogs();
        }
        
        // Shutdown executors
        scheduler.shutdown();
        executor.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        System.out.println("SimpleAsyncLogger shutdown complete");
    }
    
    // Build endpoint URL exactly like Ruby version
    private String buildEndpointUrl() {
        String baseUrl = "https://" + host + ":443/json/v2";
        String encodedService = URLEncoder.encode(serviceName, StandardCharsets.UTF_8);
        return baseUrl + "?service=" + encodedService;
    }
    
    // Build auth header exactly like Ruby version
    private String buildAuthHeader() {
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }
    
    private Map<String, Object> prepareLogEntry(Object data) {
        Map<String, Object> entry = new HashMap<>();
        
        if (data instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dataMap = (Map<String, Object>) data;
            entry.putAll(dataMap);
        } else if (data instanceof String) {
            entry.put("message", data);
        } else {
            entry.put("data", data.toString());
        }
        
        // Add timestamp if not present
        if (!entry.containsKey("timestamp")) {
            entry.put("timestamp", Instant.now().toString());
        }
        
        return entry;
    }
    
    private void startWorker() {
        // Check for flush every second
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (shouldFlush()) {
                    flushLogs();
                }
            } catch (Exception e) {
                System.err.println("Worker error: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }
    
    private boolean shouldFlush() {
        return !logQueue.isEmpty() && 
               (logQueue.size() >= batchSize || timeSinceLastFlush() >= flushInterval.toMillis());
    }
    
    private long lastFlushTime = System.currentTimeMillis();
    
    private long timeSinceLastFlush() {
        return System.currentTimeMillis() - lastFlushTime;
    }
    
    private void triggerFlush() {
        // Run in background thread to avoid blocking
        executor.submit(this::flushLogs);
    }
    
    private void flushLogs() {
        if (logQueue.isEmpty()) {
            return;
        }
        
        // Get logs to send
        List<Map<String, Object>> logsToSend = new ArrayList<>();
        while (!logQueue.isEmpty() && logsToSend.size() < batchSize) {
            Map<String, Object> log = logQueue.poll();
            if (log != null) {
                logsToSend.add(log);
            }
        }
        
        if (logsToSend.isEmpty()) {
            return;
        }
        
        try {
            sendLogs(logsToSend);
            System.out.println("Successfully sent " + logsToSend.size() + " log entries");
        } catch (Exception e) {
            System.err.println("Failed to send " + logsToSend.size() + " log entries: " + e.getMessage());
        }
        
        lastFlushTime = System.currentTimeMillis();
    }
    
    private void sendLogs(List<Map<String, Object>> logs) throws IOException, InterruptedException {
        // Convert logs to JSON
        String jsonBody = objectMapper.writeValueAsString(logs);
        
        // Build HTTP request (same format as Ruby)
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpointUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", authHeader)
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
        
        System.out.println("Sending HTTP POST to: " + endpointUrl);
        System.out.println("Authorization: [PROTECTED]");
        System.out.println("Body: " + jsonBody);
        
        // Send request
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP Error " + response.statusCode() + ": " + response.body());
        }
    }
    
    // Demo usage
    public static void main(String[] args) throws InterruptedException {
        // Use environment variables or default values
        String username = System.getenv("LAST9_USERNAME");
        String password = System.getenv("LAST9_PASSWORD");
        String host = System.getenv("LAST9_HOST");
        
        if (username == null || password == null || host == null) {
            System.out.println("Using demo credentials (will get 401 errors - this is expected)");
            username = "demo_user";
            password = "demo_pass";
            host = "demo.last9.io";
        }
        
        // Create simple async logger
        SimpleAsyncLogger logger = new SimpleAsyncLogger(username, password, host, "java_simple_app");
        
        System.out.println("Starting simple async logging demo...");
        
        // Log some entries
        logger.log("Application started");
        logger.log(Map.of("level", "info", "message", "User logged in", "user_id", 123));
        logger.log(Map.of("level", "error", "message", "Database error", "error_code", 500));
        
        // Simulate some work with more logs
        for (int i = 0; i < 10; i++) {
            logger.log(Map.of(
                "level", "debug",
                "message", "Processing item " + i,
                "item_id", i,
                "timestamp", Instant.now().toString()
            ));
            Thread.sleep(100);
        }
        
        System.out.println("Forcing flush...");
        logger.flush();
        
        // Wait a bit for async operations
        Thread.sleep(2000);
        
        System.out.println("Shutting down...");
        logger.shutdown();
        
        System.out.println("Demo complete!");
    }
}