# Frontend Boundaries

The frontend is composed from reusable platform UI, not page-specific CSS.

Rules:
1. PrimeNG is the component foundation.
2. Bot Global semantic tokens own the visual identity.
3. Theme modes: light, dark, system.
4. Languages: Arabic and English from day one.
5. Direction: RTL/LTR is centralized.
6. Repeated behavior gets a shared `bgp-*` component.
7. Pages compose shared components; they do not restyle PrimeNG locally.
8. Feature business state remains inside its feature.
