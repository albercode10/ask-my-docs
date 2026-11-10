# Architecture decision records

An ADR records one decision that is costly to reverse or shapes later work: a datastore, a framework or library, a data model, a protocol, a cross-cutting pattern. Local design choices don't need one; they go in the ticket's plan in [`docs/plans/`](../plans).

## How to add one

1. Copy [`0000-template.md`](0000-template.md) to `NNNN-kebab-case-title.md`, using the next free 4-digit number. Numbers are never reused.
2. Fill in Context, Options considered, Decision and Consequences. Keep it to about a page, and link Linear issues for background instead of copying them.
3. Add a row to the index below.
4. Commit it in the same PR as the change it decides, or on its own if nothing changes in code yet.

**Status** is one of `Proposed`, `Accepted`, `Deprecated` or `Superseded by NNNN`. An accepted ADR isn't rewritten when the decision changes: write a new ADR and mark the old one `Superseded by NNNN`. Fixing typos or links is fine.

[`scripts/test-docs.sh`](../../scripts/test-docs.sh) checks the numbering, titles, statuses, sections and this index in CI.

## Index

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-spring-ai-and-ollama-as-llm-stack.md) | Spring AI with a local Ollama model (`qwen3:8b`) as the LLM stack | Accepted |
| [0002](0002-in-memory-vector-store-then-pgvector.md) | Vector store: in-memory `SimpleVectorStore` first, PostgreSQL + pgvector later | Accepted |
| [0003](0003-linear-for-tracking-repo-and-linear-docs-for-documentation.md) | Linear for tracking; repo docs and Linear Docs for documentation | Accepted |
| [0004](0004-grafana-lgtm-for-observability.md) | Grafana LGTM stack for observability | Proposed |
