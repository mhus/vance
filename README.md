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

Vance is licensed under the [Vance Non-Commercial, Non-Production Copyleft License v1.0](LICENSE.txt).

Use, copying, and modification are permitted **only** for non-commercial, non-production purposes (testing, evaluation, research, experimentation). Any production, operational, or commercial use is prohibited. Modifications redistributed to third parties must be published in source form under the same license. See [LICENSE.txt](LICENSE.txt) for the full terms and [CLA.md](CLA.md) for the contributor agreement.

Enterprise features (SSO, Audit, Team Management) are available separately under a commercial license — see [vance-ee](https://github.com/mhus/vance-ee).

## Status

🚧 Early development — architecture and core concepts are being defined.
