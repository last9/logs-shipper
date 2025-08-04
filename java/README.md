# Simple Async Logger for Java

Lightweight, asynchronous Java library for shipping logs to Last9 with minimal dependencies and core functionality.

## Features

- Asynchronous processing with batching
- Thread-safe operations using Java concurrency utilities
- Flexible input (strings, maps, objects)
- Simple constructor-based configuration
- Minimal dependencies and lightweight design
- Production ready with graceful shutdown

## Requirements

- Java 11 or higher
- Dependencies:
  - Jackson (for JSON serialization)
  - Java HTTP Client (built-in since Java 11)

## Installation

### Maven

Add to your `pom.xml`:

```xml
<dependencies>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.15.2</version>
    </dependency>
</dependencies>
```

### Gradle

Add to your `build.gradle`:

```gradle
dependencies {
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.15.2'
}
```

Include `SimpleAsyncLogger.java` in your project.

## Quick Start

```java
import java.util.Map;

// Initialize the logger
SimpleAsyncLogger logger = new SimpleAsyncLogger(
    "your_username",
    "your_password", 
    "your_endpoint",
    "my_app"
);

// Log simple messages
logger.log("Simple message");
logger.log(Map.of("level", "info", "message", "Structured log", "user_id", 123));

// Graceful shutdown
logger.shutdown();
```

## Configuration

**Required:** `username`, `password`, `host`, `serviceName`

| Constructor Parameter | Default | Description |
|----------------------|---------|-------------|
| `username` | Required | Last9 username |
| `password` | Required | Last9 password |
| `host` | Required | Last9 host endpoint |
| `serviceName` | Required | Service identifier |
| `batchSize` | 50 | Logs per batch |
| `flushInterval` | 5s | Max duration before flush |

### Example with All Options

```java
import java.time.Duration;

// Default configuration
SimpleAsyncLogger logger = new SimpleAsyncLogger(
    "your_username",
    "your_password",
    "logs.last9.io", 
    "my_service"
);

// Custom batch size and flush interval
SimpleAsyncLogger logger = new SimpleAsyncLogger(
    "your_username",
    "your_password", 
    "logs.last9.io",
    "my_service",
    30,                        // batch size
    Duration.ofSeconds(3)      // flush interval
);
```

## Usage

```java
// Basic logging
logger.log("String message");
logger.log(Map.of("level", "error", "message", "Error occurred"));

// Multiple data types supported
logger.log(Map.of("user_id", 123, "action", "login", "success", true));

// Force flush and shutdown
logger.flush();
logger.shutdown(); // Graceful shutdown with built-in timeout
```

## Verification

Visit [Logs Explorer](https://app.last9.io/logs) to see the logs for `<serviceName>`.

## API

- `log(Object data)` - Queue log entry (non-blocking)
- `flush()` - Force immediate flush of pending logs
- `shutdown()` - Graceful shutdown with automatic timeout

## Thread Safety

This library uses Java's concurrent utilities (`ConcurrentLinkedQueue`, `AtomicBoolean`, `ScheduledExecutorService`) for thread-safe operations. You can safely use the same logger instance across multiple threads.

## Error Handling

- **Failed Requests**: Basic error logging to console
- **Network Issues**: Timeout handling with built-in HTTP client
- **Internal Errors**: Graceful degradation - failed logs are reported but don't crash the application
- **Graceful Degradation**: Application continues normally even if logging fails

## Debugging

The logger automatically outputs debug information to console:

```
SimpleAsyncLogger started for service: my_app
Sending HTTP POST to: https://<your_endpoint>:443/json/v2?service=my_app
Authorization: [PROTECTED]
Body: [{"message":"User logged in","level":"info","user_id":123}]
Successfully sent 1 log entries
```

This output includes:
- Service startup confirmation
- HTTP request details (URL and protected auth)
- JSON payload being sent
- Success/failure status

## Notes

- Failed logs are logged to console for debugging
- Always call `shutdown()` during app termination
- Uses daemon threads that won't prevent JVM shutdown
- Minimal footprint - single file, one dependency

## Example Project Structure

```
src/
├── main/
│   └── java/
│       ├── SimpleAsyncLogger.java
│       └── Example.java
└── test/
    └── java/
        └── SimpleAsyncLoggerTest.java
```

## Building and Running

```bash
# Compile with Maven
mvn clean compile

# Run with Maven
mvn exec:java

# Or compile manually
javac -cp jackson-databind-2.15.2.jar *.java

# Run manually
java -cp .:jackson-databind-2.15.2.jar SimpleAsyncLogger
```