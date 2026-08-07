# Design System Foundation Contract

## Theme
Supported preferences:
- light
- dark
- system

`system` follows the operating-system preference and reacts to runtime changes.

PrimeNG and Bot Global custom surfaces use the same root dark selector:
`.bgp-dark-mode`.

## Language and direction
Supported languages:
- English (`en`, LTR)
- Arabic (`ar`, RTL)

Direction is controlled only by the centralized `LanguageService`.
Features must not set `dir` locally.

## PrimeNG
PrimeNG is the low-level component foundation.
`BotGlobalPreset` owns global semantic tokens.

Reusable Bot Global wrappers own repeated interaction/shape contracts.
Pages must not create their own PrimeNG visual language.

## Initial reusable UI
- `bgp-button`
- `bgp-input`
- `bgp-select`
- `bgp-card`
- `bgp-badge`

## Design System Lab
`/design-system` is the visual verification surface for shared UI.
