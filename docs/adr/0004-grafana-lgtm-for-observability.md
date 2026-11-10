# 0004. Grafana LGTM stack for observability

Date: 2026-11-10
Status: Proposed

## Context

A RAG app is hard to debug from logs alone: a slow or wrong answer can come from embedding, retrieval, the prompt or the model. Spring Boot and Spring AI emit Micrometer observations (HTTP requests, `ChatClient` calls, token usage, vector store queries) that can be exported over OpenTelemetry (OTLP). The project runs locally, so the backend for that telemetry should be one container in Docker Compose.

No ticket implements this yet: the observability issues (ALB-18 to ALB-22) are canceled for now. This records the intended choice so later work starts from it.

## Options considered

- **Grafana LGTM** (`grafana/otel-lgtm` image): one container with an OpenTelemetry collector, Loki (logs), Grafana, Tempo (traces) and Prometheus/Mimir (metrics). Standard OTLP in, no vendor lock-in; a Grafana MCP server can query it.
- **Prometheus + Jaeger + a log tool, separately**: same capabilities, three or more services to wire together.
- **An LLM-specific tool** (e.g. Langfuse): strong prompt and trace views, but doesn't cover HTTP, JVM or infrastructure metrics. Can be added on top later.
- **A hosted APM** (Datadog, Grafana Cloud): no local setup, but costs money and sends data off the machine.

## Decision

When observability is scheduled, run `grafana/otel-lgtm` in the existing Docker Compose file and export traces, metrics and logs from the backend over OTLP, including Spring AI observations.

## Consequences

- One more container in Compose, and OTLP settings in the backend.
- Traces show each step of a RAG request (retrieval, prompt, model call) with timings and token counts.
- Prompt contents in traces must be opt-in: they can contain document text.
- Revisit this ADR when the observability work is planned: accept it, or supersede it.
