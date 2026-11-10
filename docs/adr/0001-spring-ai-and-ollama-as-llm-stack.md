# 0001. Spring AI with a local Ollama model (`qwen3:8b`) as the LLM stack

Date: 2026-11-10
Status: Accepted

## Context

ask-my-docs answers questions about documents with an LLM. The backend is Spring Boot (Java 21). The project is built and run on a single Apple Silicon Mac, it should cost nothing to run, and the documents shouldn't leave the machine. The stack has to cover chat and embeddings, and make it easy to swap the model or the provider later.

## Options considered

- **Spring AI + Ollama on the host**: Spring's own abstraction (`ChatClient`, `ChatModel`, `EmbeddingModel`, vector stores, advisors), auto-configured by a Boot starter. Ollama serves open models locally, free, on the Apple GPU. Slower and less capable than hosted frontier models.
- **Spring AI + a hosted API** (Anthropic, OpenAI): better answers, no local hardware limits, but costs money per request and sends documents to a third party.
- **LangChain4j**: a capable Java LLM library, but less integrated with Spring Boot's auto-configuration, properties and observability than Spring AI.
- **Ollama in Docker**: same models, but Docker on macOS can't use the Apple GPU, so inference would run on the CPU.

## Decision

Use Spring AI 2.0 (`spring-ai-starter-model-ollama`) with Ollama running natively on the host (`http://localhost:11434`) and `qwen3:8b` as the chat model. The model and URL live in `backend/src/main/resources/application.properties`.

## Consequences

- No API keys or per-request cost; documents stay local.
- Developers must install Ollama and `ollama pull` the models; the backend starts without Ollama, but can't answer until it's up.
- An 8B model is slower and weaker than hosted models; answer quality has to be measured (RAG evaluation) rather than assumed.
- Code depends on Spring AI's interfaces, so moving to a hosted provider is mostly a starter and properties change.
- Automated tests never call Ollama: they stub `ChatModel` / `EmbeddingModel` with `@MockitoBean`. Real model behavior is checked by hand through `backend/http/`.
- The embedding model and vector store are decided in [0002](0002-in-memory-vector-store-then-pgvector.md).
