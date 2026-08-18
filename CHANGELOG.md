# Changelog

## 1.0.0 — Initial public release

TierLookup 1.0.0 is the first release prepared for public distribution.

Highlights:

- Client-side Fabric support for Minecraft 1.21.11.
- Legacy and Custom TAB modes with tier sorting.
- Interactive frozen TAB and player quick actions.
- RAM-first player lookup with Internet and Offline data modes.
- SQLite-backed local tier-list mirror and safe partial-sync handling.
- Dynamic hover tables with a RAM-only render path.
- Search, favorites, player comparison, and local player notes.
- Exact LT/MT/HT tier semantics and source-explicit Retired handling.
- Built-in `/tl selftest` diagnostics.
- No runtime Mixin dependency.

### Source cleanup
- Split unrelated state fields that were declared on the same line in the client, overlay and profile service.
- Removed a few comments that only repeated the surrounding identifier and shortened internal notes where the reason mattered more than a formal description.
- Kept regression notes and compatibility comments that document real Minecraft/Fabric/provider edge cases.
- No feature, storage schema, network contract or user-facing behavior was intentionally changed by this cleanup.
