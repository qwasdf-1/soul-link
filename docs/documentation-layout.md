# Documentation layout

| Content | Location |
|---|---|
| Product scope and source-of-truth map | `project-reference.md` |
| Present behavior | `current-state.md`, `gameplay.md`, `configuration.md` |
| Runtime ownership and durable choices | `architecture.md`, `architecture-decisions.md` |
| Reset, persistence, performance, compatibility | `reference.md` |
| Product requests and open design questions | `roadmap.md` |
| Release workflow | `publishing.md` |
| Current operator-reported defects | Local `issue-ledger.md` |
| Agent instructions | Root `AGENTS.md` (`CLAUDE.md` is a compatibility symlink) |
| Local active work and backlog index | `open-work.md`, `TODOS.md`, `work-register.md` |

Keep implementation statements in present tense and tied to code, tests, or
release configuration. Keep requested behavior in the roadmap until it is
implemented. Do not maintain a second active issue list in another document.
