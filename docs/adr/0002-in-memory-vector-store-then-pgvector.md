# 0002. Vector store: in-memory `SimpleVectorStore` first, PostgreSQL + pgvector later

Date: 2026-11-10
Status: Accepted

## Context

Retrieval needs a vector store for the document's chunks and their embeddings. The first version answers questions about one book of about 16k words, so the store holds a few hundred chunks. Later, uploaded documents ([ALB-11](https://linear.app/alberto-caro/issue/ALB-11)) need chunks that survive restarts. Postgres 18 with pgvector already runs in Docker Compose for local development ([`compose.yaml`](../../compose.yaml)).

Embeddings come from Ollama ([0001](0001-spring-ai-and-ollama-as-llm-stack.md)) with `nomic-embed-text`, which returns 768-dimension vectors.

## Options considered

- **Spring AI `SimpleVectorStore` (in memory)**: no infrastructure, built from an `EmbeddingModel`; the book is re-embedded at every startup (seconds for one book), and everything must fit in memory.
- **PostgreSQL + pgvector** (Spring AI `PgVectorStore`): persistent, SQL alongside the vectors, one database for app data and embeddings; needs Docker running and a schema that matches the embedding dimensions.
- **A dedicated vector database** (Qdrant, Chroma, Weaviate): purpose-built, but one more service to run and learn, with no benefit at this size.

## Decision

Start with `SimpleVectorStore` ([ALB-32](https://linear.app/alberto-caro/issue/ALB-32)), then move to pgvector in the Compose Postgres ([ALB-10](https://linear.app/alberto-caro/issue/ALB-10)). Retrieval code depends on Spring AI's `VectorStore` interface, so the swap doesn't change the endpoints.

## Consequences

- The first RAG version needs no database, and its tests need no Docker.
- Until ALB-10, every startup re-embeds the book, and Ollama must be up at startup for retrieval to work.
- With pgvector, the table's vector dimension (768) is tied to the embedding model: changing the model means changing the table and re-embedding.
- ALB-10 must avoid re-embedding unchanged content on restart, and replace old chunks when the content, chunking settings or model change.
