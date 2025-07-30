require 'net/http'
require 'uri'
require 'json'
require 'time'
require 'concurrent-ruby'
require 'logger'
require 'base64'

class Last9AsyncLogger
  attr_reader :service_name, :batch_size, :flush_interval, :max_retries

  def initialize(options = {})
    @username = options[:username] || raise(ArgumentError, "username is required")
    @password = options[:password] || raise(ArgumentError, "password is required") 
    @host = options[:host] || raise(ArgumentError, "host is required")
    @service_name = options[:service_name] || raise(ArgumentError, "service_name is required")
    
    # Configuration
    @batch_size = options[:batch_size] || 100
    @flush_interval = options[:flush_interval] || 5.0 # seconds
    @max_retries = options[:max_retries] || 3
    @timeout = options[:timeout] || 10
    
    # Additional query parameters
    @additional_params = options[:additional_params] || {}
    
    # Internal state
    @log_queue = Concurrent::Array.new
    @running = Concurrent::AtomicBoolean.new(false)
    @last_flush = Concurrent::AtomicReference.new(Time.now)
    
    # Logger for internal errors
    @internal_logger = Logger.new(STDOUT)
    @internal_logger.level = options[:debug] ? Logger::DEBUG : Logger::WARN
    
    # Build the endpoint URL and auth header
    @endpoint_url = build_endpoint_url
    @auth_header = build_auth_header
    
    start_worker
  end

  # Main logging method - non-blocking
  def log(data, additional_attributes = {})
    unless @running.value
      @internal_logger.warn("Logger is not running, ignoring log entry")
      return false
    end

    log_entry = prepare_log_entry(data, additional_attributes)
    @log_queue << log_entry
    
    # Check if we should flush immediately
    if should_flush?
      trigger_flush
    end
    
    true
  rescue => e
    @internal_logger.error("Failed to queue log entry: #{e.message}")
    false
  end

  # Log multiple entries at once
  def log_batch(entries)
    entries.each { |entry| log(entry) }
  end

  # Force flush all pending logs
  def flush!
    trigger_flush
  end

  # Graceful shutdown - wait for pending logs to be sent
  def shutdown(timeout = 30)
    @internal_logger.info("Shutting down Last9AsyncLogger...")
    
    @running.make_false
    
    # Flush any remaining logs
    flush_logs if @log_queue.any?
    
    # Stop the worker task properly
    if @worker_task
      @worker_task.shutdown
      # Give it a moment to stop gracefully
      sleep(0.5)
    end
    
    @internal_logger.info("Last9AsyncLogger shutdown complete")
  end

  private

  def build_endpoint_url
    base_url = "https://#{@host}:443/json/v2"
    
    params = { service: @service_name }.merge(@additional_params)
    query_string = URI.encode_www_form(params)
    
    "#{base_url}?#{query_string}"
  end

  def build_auth_header
    credentials = Base64.strict_encode64("#{@username}:#{@password}")
    "Basic #{credentials}"
  end

  def prepare_log_entry(data, additional_attributes)
    entry = case data
            when Hash
              data.dup
            when String
              { message: data }
            else
              { data: data.to_s }
            end
    
    # Add timestamp if not present
    entry[:timestamp] ||= Time.now.utc.iso8601
    
    # Merge additional attributes
    entry.merge!(additional_attributes) if additional_attributes.any?
    
    entry
  end

  def start_worker
    @running.make_true
    
    @worker_task = Concurrent::TimerTask.new(execution_interval: 1.0) do
      begin
        if should_flush?
          flush_logs
        end
      rescue => e
        @internal_logger.error("Worker error: #{e.message}")
      end
    end
    
    @worker_task.execute
    @internal_logger.info("Last9AsyncLogger worker started")
  end

  def should_flush?
    return false if @log_queue.empty?
    
    @log_queue.size >= @batch_size || 
    (Time.now - @last_flush.value) >= @flush_interval
  end

  def trigger_flush
    # Use a separate thread to avoid blocking the caller
    Concurrent::Future.execute do
      flush_logs
    end
  end

  def flush_logs
    return if @log_queue.empty?
    
    # Atomic operation to get current logs and clear queue
    logs_to_send = []
    while @log_queue.any? && logs_to_send.size < @batch_size
      logs_to_send << @log_queue.pop
    end
    
    return if logs_to_send.empty?
    
    @internal_logger.debug("Flushing #{logs_to_send.size} log entries")
    
    success = send_logs_with_retry(logs_to_send)
    
    unless success
      @internal_logger.error("Failed to send #{logs_to_send.size} log entries after retries")
      # Optionally, you could implement a dead letter queue here
    end
    
    @last_flush.set(Time.now)
  end

  def send_logs_with_retry(logs)
    attempt = 1
    
    begin
      send_logs(logs)
      @internal_logger.debug("Successfully sent #{logs.size} log entries")
      true
    rescue => e
      @internal_logger.warn("Attempt #{attempt} failed: #{e.message}")
      
      if attempt < @max_retries
        attempt += 1
        sleep(2 ** (attempt - 1)) # Exponential backoff
        retry
      else
        @internal_logger.error("Failed to send logs after #{@max_retries} attempts: #{e.message}")
        false
      end
    end
  end

  def send_logs(logs)
    uri = URI(@endpoint_url)
    
    http = Net::HTTP.new(uri.host, uri.port)
    http.use_ssl = true
    http.read_timeout = @timeout
    http.open_timeout = @timeout
    
    request = Net::HTTP::Post.new(uri)
    request['Content-Type'] = 'application/json'
    request['Authorization'] = @auth_header
    request.body = logs.to_json
    
    # Print HTTP request details for debugging (mask auth header for security)
    @internal_logger.info("=== HTTP Request to Last9 ===")
    @internal_logger.info("URL: #{uri}")
    @internal_logger.info("Method: POST")
    @internal_logger.info("Headers:")
    request.each_header do |key, value|
      # Mask the authorization header for security
      displayed_value = key.downcase == 'authorization' ? '[REDACTED]' : value
      @internal_logger.info("  #{key}: #{displayed_value}")
    end
    @internal_logger.info("Body (#{logs.size} log entries):")
    @internal_logger.info(JSON.pretty_generate(logs))
    @internal_logger.info("================================")
    
    response = http.request(request)
    
    unless response.is_a?(Net::HTTPSuccess)
      raise "HTTP Error: #{response.code} #{response.message}"
    end
    
    response
  end
end

# Example usage and testing
if __FILE__ == $0
  # Configure your Last9 credentials
  logger = Last9AsyncLogger.new(
    username: 'LAST9_USERNAME',
    password: 'LAST9_PASSWORD', 
    host: 'LAST9_ENDPOINT',
    service_name: 'ruby_sample_app',
    batch_size: 50,
    flush_interval: 3.0,
    additional_params: { source: 'ruby_app', environment: 'production' },
    debug: true
  )

  puts "Starting async logging demo..."

  # Single log entry
  logger.log({ level: 'info', message: 'Application started', module: 'main' })

  # Log with additional attributes
  logger.log('User login attempt', { user_id: 123, ip: '192.168.1.1', success: true })

  # Batch logging
  events = [
    { level: 'error', message: 'Database connection failed', component: 'db' },
    { level: 'info', message: 'Retrying connection', component: 'db' },
    { level: 'info', message: 'Connection restored', component: 'db' }
  ]
  logger.log_batch(events)

  # Simulate application work
  puts "Simulating application work..."
  10.times do |i|
    logger.log({ 
      level: 'debug', 
      message: "Processing item #{i}", 
      item_id: i,
      processing_time: rand(100..500)
    })
    sleep(0.1) # Simulate work
  end

  puts "Forcing flush of remaining logs..."
  logger.flush!

  sleep(2) # Give time for async operations

  puts "Shutting down logger..."
  logger.shutdown

  puts "Demo complete!"
end