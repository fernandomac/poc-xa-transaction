package com.example.xapoc.loadtest;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.util.UUID;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class XaLoadSimulation extends Simulation {

    private static final String BASE_URL =
            System.getProperty("gatling.baseUrl", "http://localhost:8080");
    private static final double PEAK_RPS =
            Double.parseDouble(System.getProperty("gatling.peakRps", "5000"));
    private static final int RAMP_SECONDS =
            Integer.parseInt(System.getProperty("gatling.rampSeconds", "60"));
    private static final int SUSTAIN_SECONDS =
            Integer.parseInt(System.getProperty("gatling.sustainSeconds", "300"));

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private final ScenarioBuilder scn = scenario("XA Load")
            .exec(
                    http("POST /api/events")
                            .post("/api/events")
                            .body(StringBody(__ -> "{\"payload\": \"" + UUID.randomUUID() + "\"}"))
                            .check(status().is(201))
            );

    {
        setUp(
                scn.injectOpen(
                        rampUsersPerSec(0).to(PEAK_RPS).during(RAMP_SECONDS),
                        constantUsersPerSec(PEAK_RPS).during(SUSTAIN_SECONDS)
                )
        ).protocols(httpProtocol);
    }
}
