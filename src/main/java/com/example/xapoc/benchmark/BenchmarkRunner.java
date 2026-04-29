package com.example.xapoc.benchmark;

import com.example.xapoc.producer.EventProducerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Drives the XA transaction producer under sustained load and records performance metrics.
 *
 * Each call to produceEvent() is individually timed. After the loop, percentile values
 * are extracted from the Micrometer Timer's HDR histogram and written to benchmark-result.json.
 */
@Service
public class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

    private final EventProducerService producerService;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    @Value("${xa-poc.benchmark.event-count:100}")
    private int eventCount;

    public BenchmarkRunner(EventProducerService producerService,
                           MeterRegistry meterRegistry,
                           ObjectMapper objectMapper) {
        this.producerService = producerService;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    public BenchmarkResult run() {
        String runId = UUID.randomUUID().toString();
        log.info("Starting benchmark: runId={} eventCount={}", runId, eventCount);

        Timer timer = Timer.builder("xa.transaction.duration")
                .description("XA commit latency end-to-end")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(meterRegistry);

        long startMs = System.currentTimeMillis();

        for (int i = 0; i < eventCount; i++) {
            String payload = "benchmark-" + i + "-" + runId;
            timer.record(() -> producerService.produceEvent(payload));
        }

        long durationMs = System.currentTimeMillis() - startMs;
        double throughputPerSec = eventCount / (durationMs / 1000.0);

        HistogramSnapshot snapshot = timer.takeSnapshot();
        ValueAtPercentile[] percentiles = snapshot.percentileValues();

        double p50 = percentiles.length > 0 ? percentiles[0].value(TimeUnit.MILLISECONDS) : 0;
        double p95 = percentiles.length > 1 ? percentiles[1].value(TimeUnit.MILLISECONDS) : 0;
        double p99 = percentiles.length > 2 ? percentiles[2].value(TimeUnit.MILLISECONDS) : 0;

        BenchmarkResult result = new BenchmarkResult(
                runId, eventCount, durationMs, throughputPerSec, p50, p95, p99);

        log.info("Benchmark complete: {} events in {}ms | throughput={} msg/s | p50={}ms p95={}ms p99={}ms",
                eventCount, durationMs,
                String.format("%.1f", throughputPerSec),
                String.format("%.2f", p50),
                String.format("%.2f", p95),
                String.format("%.2f", p99));

        writeToFile(result);
        return result;
    }

    private void writeToFile(BenchmarkResult result) {
        try {
            File output = new File("benchmark-result.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output, result);
            log.info("Benchmark results written to {}", output.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write benchmark-result.json", e);
        }
    }
}
