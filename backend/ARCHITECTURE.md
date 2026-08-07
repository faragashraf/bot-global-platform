# Backend Architecture

Bot Global starts as a modular monolith.

## Composition root
`BotGlobal.Api`
- HTTP pipeline
- dependency injection composition
- authentication/authorization wiring
- module endpoint registration
- health checks
- hosting

The API must not contain business rules.

## BuildingBlocks
### BotGlobal.SharedKernel
Very small stable primitives shared across modules.
Do not turn this project into a dumping ground.

### BotGlobal.Contracts
Cross-module integration contracts/events only.

## Modules
Each capability owns its code and data rules.

Initial modules:
- Identity
- Notifications
- Realtime

Every module is internally divided into:
- Domain
- Application
- Infrastructure
- Endpoints
- Contracts

Modules must not directly reference other module projects.
Cross-module collaboration happens through contracts/events.

## Realtime rule
SignalR is a delivery capability, not the notification domain itself.
Notifications persist and target recipients; Realtime delivers live events.

## Future modules
Catalog, Portfolio, Media and Administration are added only when their
delivery slice starts. We do not scaffold unused business complexity.
