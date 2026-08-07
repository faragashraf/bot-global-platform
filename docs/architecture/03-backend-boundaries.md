# Backend Boundaries

Architecture style: Modular Monolith.

Rules:
1. Build by capability.
2. No generic mega-service.
3. API is the composition root, not the business layer.
4. Modules do not reference each other directly.
5. SharedKernel stays minimal.
6. Contracts contain integration contracts only.
7. SignalR belongs to Realtime.
8. Notification persistence and targeting belong to Notifications.
9. SQL Server persistence will be introduced behind module boundaries.
