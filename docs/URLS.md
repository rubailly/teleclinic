# URLs and ports (local demo)

| What | URL | Notes |
| --- | --- | --- |
| Public edge (HAProxy → APISIX → core) | http://localhost:9080 | Sign in at `/login`; `/api/me` shows the session identity |
| Release running behind the edge | http://localhost:9080/api/public/version | `{"version":"v1.4","color":"blue"}` |
| IremboAccount stand-in (Keycloak) | http://localhost:9180 | Realm `irembo-mock`; admin console at `/admin` |
| HAProxy stats | http://localhost:9404/stats | Blue/green pool weights and health |

Internal only (not published on the host): HAProxy `core` pool on `:9100`, HAProxy runtime API on `:9999`, core on `:8080`.

Ports 80, 8080, 5432 and 6379 are often taken on developer machines, so the demo stays in the 9xxx range.
Credentials are demo-only and live in `.env.example`.
