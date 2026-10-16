# TeleClinic reference platform

A runnable reference implementation of the TeleClinic Technical Design Document (Irembo, Software Architect, Digital Health).
It exists to show the design working, including when parts of it fail. **Synthetic data only.**

## Start

```bash
make up-lite     # build and start; waits until the edge serves the core
make doctor      # check every health endpoint
make smoke       # end-to-end login as patient1 through the edge
make reset       # back to a known state (about 30 s)
make test        # core unit and module-boundary tests (Java 21 in a container)
```

Requirements: Docker with Compose v2, `make`, `curl`. Nothing else needs to be installed on the host.

## What is real and what is a stand-in

| Component | Status |
| --- | --- |
| TeleClinic core (Spring Boot 3, Java 21, Spring Modulith) | Real |
| HAProxy edge with blue/green pool and runtime API | Real |
| Apache APISIX API gateway (correlation id, rate limits, configuration as code) | Real |
| **Keycloak** | **Labelled stand-in for IremboAccount** |

Later build steps add the remaining components; see `docs/build-plan.md` for the order and `docs/DEMO_STORY.md` for what each one must show.

## Built so far (step 1)

- **Backend-for-frontend login.** The core is a confidential OIDC client of IremboAccount (stand-in). Authorization code with PKCE (S256), state and nonce. The id token's issuer is checked against the public issuer while token and key calls use the private network. The browser only ever receives an HttpOnly session cookie; `make smoke` checks that no token reaches the client.
- **Request path.** Browser → HAProxy `:9080` → APISIX → HAProxy `core` pool (blue weight 100, green weight 0) → core. The core's actuator is not reachable from the edge.
- **Correlation id.** Created at the gateway, or kept if the caller supplies one (the demo uses the `demo-` prefix), echoed on every response and written on every log line.
- **Module boundaries.** Nine domain modules are declared; `make test` fails the build if one module reaches into another's internals.

## Demo users (synthetic)

`patient1` (Uwase), `patient2` (Eric), `clinician1` (Dr Mukamana), `clerk1` (Habimana), `admin1`. One demo password for all of them, set in `.env.example`.

## Ports

See `docs/URLS.md`.

## Measured (step 1, lite stack so far)

| Container | Memory in use | Limit |
| --- | --- | --- |
| core-blue | 156 MiB | 512 MiB |
| keycloak | 526 MiB | 768 MiB |
| apisix | 155 MiB | 256 MiB |
| haproxy | 12 MiB | 128 MiB |

`make reset` to a working edge: 30 s on a 12-core, 16 GB laptop.
