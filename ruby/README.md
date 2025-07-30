# Last9 Async Logger for Ruby

Asynchronous, thread-safe Ruby library for shipping logs to Last9 with batching, retry logic, and non-blocking operations.

## Features

- Asynchronous processing with batching
- Automatic retry with exponential backoff
- Thread-safe operations
- Flexible input (strings, hashes, objects)
- Debug support and production ready

## Installation

Add to your Gemfile:
```ruby
gem 'concurrent-ruby'
```

Include `logs.rb` in your project.

## Quick Start

```ruby
require_relative 'logs'

logger = Last9AsyncLogger.new(
  username: 'your_username',
  password: 'your_password',
  host: 'your_endpoint',
  service_name: 'my_app'
)

logger.log('Simple message')
logger.log({ level: 'info', message: 'Structured log', user_id: 123 })
logger.shutdown
```

## Configuration

**Required:** `username`, `password`, `host`, `service_name`

| Parameter | Default | Description |
|-----------|---------|-------------|
| `batch_size` | 100 | Logs per batch |
| `flush_interval` | 5.0 | Max seconds before flush |
| `max_retries` | 3 | Retry attempts |
| `timeout` | 10 | HTTP timeout |
| `additional_params` | {} | Extra query params |
| `debug` | false | Enable debug logging |

## Usage

```ruby
# Basic logging
logger.log('String message')
logger.log({ level: 'error', message: 'Error occurred' })
logger.log('Event', { user_id: 123, action: 'login' })

# Batch logging
logger.log_batch([
  { level: 'info', message: 'Task started' },
  { level: 'info', message: 'Task completed' }
])

# Force flush and shutdown
logger.flush!
logger.shutdown(30)
```

## Verification

Visit [Logs Explorer](https://app.last9.io/logs) to see the logs for `<service_name>`.

## API

- `log(data, attributes = {})` - Queue log entry
- `log_batch(entries)` - Log multiple entries
- `flush!` - Force immediate flush
- `shutdown(timeout = 30)` - Graceful shutdown

## Notes

- Failed logs are dropped after max retries
- Always call `shutdown()` during app termination
- Monitor queue size in high-volume applications 