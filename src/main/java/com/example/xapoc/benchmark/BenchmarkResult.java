package com.example.xapoc.benchmark;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Structured output from a benchmark run.
 * Serialized to benchmark-result.json by BenchmarkRunner.
 * All latency values are in milliseconds; throughput is messages per second.
 */
public record BenchmarkResult(
        @JsonProperty("runId")          String runId,
        @JsonProperty("eventCount")     int eventCount,
        @JsonProperty("durationMs")     long durationMs,
        @JsonProperty("throughputPerSec") double throughputPerSec,
        @JsonProperty("p50Ms")          double p50Ms,
        @JsonProperty("p95Ms")          double p95Ms,
        @JsonProperty("p99Ms")          double p99Ms
) {}
