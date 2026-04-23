# Vance

**AI Multi-Agent Orchestration Platform**

Vance is a multi-agent orchestration platform that enables complex, dialogic AI workflows through task trees, scope-aware memory, and human-in-the-loop steering.

## Core Concepts

- **Brain** — Central orchestration server managing engines, task trees, memory, and LLM dispatch
- **Engines** — Independent execution units, each with their own task tree and scope
- **Task Trees** — Hierarchical decomposition of goals into executable steps with dependency tracking and invalidation
- **Memory & RAG** — Scope-aware knowledge management (Node → Engine → Project → Tenant) with vector search
- **Steering** — Human-in-the-loop control: pause, reorder, split, cancel, and redirect tasks at any point
- **Lanes** — Serialized execution queues preventing race conditions across engines and projects

## Architecture

```
┌─────────────────────────────────────────────┐
│                 Client SDK                   │
│  WebSocket · Session · Local Tools           │
├─────────┬──────────────┬────────────────────┤
│ CLI     │ Desktop      │ Mobile / Web UI    │
└────┬────┴──────┬───────┴─────────┬──────────┘
     └───────────┼─────────────────┘
                 │ WebSocket
                 ▼
┌─────────────────────────────────────────────┐
│                Vance Brain                   │
│  Engine Registry · Task Trees · Memory       │
│  LLM Orchestration · Lane Serializer         │
│  Session Manager · Tool Dispatcher           │
├─────────────────────────────────────────────┤
│  MongoDB · LLM Providers · Remote Tools      │
└─────────────────────────────────────────────┘
```

## License

Vance is licensed under the [Functional Source License, Version 1.1, Apache 2.0 Future License](LICENSE.md) (FSL-1.1-Apache-2.0).

You are free to use, modify, and self-host Vance for any purpose **except** offering it as a competing commercial service. Each version automatically converts to Apache 2.0 two years after release.

Enterprise features (SSO, Audit, Team Management) are available separately under a commercial license — see [vance-ee](https://github.com/mhus/vance-ee).

## Status

🚧 Early development — architecture and core concepts are being defined.
