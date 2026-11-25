# Entia API reference snapshot

Source: [Entia's API documentation](https://api.entia.si/doc/). The user supplied a saved Swagger UI page and its companion files on 2026-11-16. The live page could not be fetched from the agent environment; this review uses the supplied local snapshot.

Source priority: the user confirmed that the integration is proven to work and instructed that its code/documentation take precedence over Swagger, which may be outdated. These notes record differences; they do not turn Swagger-only claims into prerequisites for the application.

- [index.html](index.html) is the saved `Swagger UI.html`; its local asset references now point to `assets/`.
- [assets/](assets/) contains the original companion files, including `swagger-ui-init.js` with the embedded specification. These files are unchanged.
- [openapi.json](openapi.json) is a JSON extraction of the initializer's `swaggerDoc` object. It is semantically identical to that object, with no endpoint/schema corrections applied.

The snapshot describes OpenAPI 3.0.0, title `Entia API V1`, API version `1.0.0`, 24 path entries, and 51 component schemas. Production server: `https://api.entia.si`; its additional localhost server is for development and is not an app default.

## Differences that affect the Android design

| Topic | Saved specification | Home Assistant integration / planned treatment |
| --- | --- | --- |
| Authentication | Global bearer OR `X-API-KEY`; `/login` overrides this with API-key security | Use the proven integration's keyless login by default. Do not require a key or block implementation based only on Swagger; investigate only if live behavior warrants it |
| Flat identity | `Flat.flat.id` and `FlatState.id` are documented | Integration examples omit IDs; retain them when present without requiring them |
| Hierarchy | Floor label, room IDs/types/labels/scenes, device IDs/types/subtypes/labels/`isFlatDevice` | Preserve navigation metadata; floor IDs are not in the schema |
| Realtime PUT | `/rt` is experimental; prose says status only and new state via WebSocket | Keep the proven `/rt` write and full-refresh/event confirmation; tolerate response variations. The conflicting response schema does not override observed behavior |
| Attribute access | GET and PUT exist without `/rt`; settings have distinct GET/PUT endpoints | Extra reads/writes are Swagger-only candidates until verified. Initial confirmation uses full refresh. Never silently repeat an uncertain command through another endpoint |
| Connection updates | `/flat/device` documents the `flat_connection` WebSocket event | Optional after observation/verification; the initial client works without it and keeps socket connectivity separate |
| Device metadata | `/flat/config` and its schemas describe bounds/access flags | The endpoint is marked NOT IMPLEMENTED. Initial controls must work without it |
| Extended features | Device extended data, account management, notifications, password changes, history export, scene/schedule schemas | These are future scope; several other endpoints explicitly say NOT IMPLEMENTED |

The snapshot also contains generator caveats: query strings appear inside path keys, schemas often omit `type`/`required`, and some response definitions are empty. Preserve this snapshot as evidence. Use explicit Kotlin DTOs and correctly encoded query parameters; if code generation is introduced, create a separately documented normalized input rather than modifying the original extraction.

The specification does not fully describe the WebSocket protocol or the meaning of the integration's numeric device attributes. Use the [reference integration](../../../reference/home-assistant-entia/), its accompanying notes, and tests as the primary contract. Full implementation decisions and validation gates are in the [Android plan](../../ANDROID_APP_PLAN.md).
