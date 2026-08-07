# ADR 0001 — Start as a Modular Monolith

Status: Accepted

Bot Global Platform will start as a modular monolith rather than
microservices. Capabilities are isolated in modules with explicit
boundaries, enabling later extraction where operationally justified.

Realtime is designed so it can later be hosted independently without
changing the platform's business contracts.
