package com.example.xapoc.benchmark;

import com.example.xapoc.AbstractIntegrationTest;
import com.example.xapoc.consumer.EventConsumerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3 — Performance Baseline: Throughput and Latency Under Load.
 *
 * Runs the benchmark against Testcontainers infrastructure and validates that
 * benchmark-result.json is produced with all required fields.
 *
 * Note: Constitution performance targets (>=500 msg/s, <=200ms p95) are validated
 * against real Docker infrastructure, not Testcontainers, which has higher overhead.
 * This test validates the benchmark mechanism and output structure only.
 */
@ActiveProfiles("benchmark")
class PerformanceBenchmarkIT extends AbstractIntegrationTest {

    @Autowired
    private BenchmarkRunner benchmarkRunner;

    @Autowired
    private EventConsumerService eventConsumerService;

    @BeforeEach
    void setUp() {
        eventConsumerService.clearMessages();
        new File("benchmark-result.json").delete();
    }

    @Test
    void benchmarkProducesCompleteResultFile() throws Exception {
        BenchmarkResult result = benchmarkRunner.run();

        System.out.printf(
                "%nBenchmark results:%n" +
                "  Events:     %d%n" +
                "  Duration:   %dms%n" +
                "  Throughput: %.1f msg/s%n" +
                "  p50:        %.2fms%n" +
                "  p95:        %.2fms%n" +
                "  p99:        %.2fms%n",
                result.eventCount(), result.durationMs(), result.throughputPerSec(),
                result.p50Ms(), result.p95Ms(), result.p99Ms());

        // Structural validation — mechanism works
        File resultFile = new File("benchmark-result.json");
        assertThat(resultFile).exists();
        assertThat(result.runId()).isNotBlank();
        assertThat(result.eventCount()).isEqualTo(100); // test profile event-count
        assertThat(result.durationMs()).isPositive();
        assertThat(result.throughputPerSec()).isPositive();
        assertThat(result.p50Ms()).isPositive();
        assertThat(result.p95Ms()).isPositive();
        assertThat(result.p99Ms()).isPositive();

        // Ordering invariant: p50 <= p95 <= p99
        assertThat(result.p50Ms()).isLessThanOrEqualTo(result.p95Ms());
        assertThat(result.p95Ms()).isLessThanOrEqualTo(result.p99Ms());
    }
}
