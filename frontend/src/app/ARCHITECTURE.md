# Frontend Architecture

## core
Singleton platform capabilities only:
- auth
- configuration
- HTTP/interceptors
- i18n + RTL/LTR
- layout shell
- theme (light/dark/system)
- realtime

Do not place feature-specific UI here.

## shared
Reusable, presentation-oriented building blocks:
- `shared/ui`: Bot Global UI kit built on PrimeNG
- `shared/models`: cross-feature contracts only
- `shared/utils`: pure reusable helpers

Pages must not duplicate PrimeNG styling, validation presentation,
theme logic, RTL logic, loading/empty states, or dialog conventions.

## features
Business/product capabilities. Each feature owns its routes,
facades/state, models and page composition.

Initial capabilities:
- home
- catalog
- portfolio
- account
- admin
- design-system
