# Live Status UX

The live execution card intentionally has three different responsibilities:

- Header state: a stable overall state (`Working`) only.
- Five phases: workflow position only (`Waiting`, `In progress`, `Complete`, `Failed`).
- Live activity: the single user-facing line containing the exact current operation.

The same detailed status is never rendered in all three places. Backend SSE `activity`, `node_start`, `task_start`, and `task_complete` events update the single live activity line. The phase cards only show lifecycle state.
