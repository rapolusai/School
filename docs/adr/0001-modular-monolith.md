# 1. One API, split into modules

Status: accepted (Phase 0)

## Context
The product covers many areas (admissions, fees, exams, transport, …) and starts with a small team.
Separate microservices would multiply deployments, networking and data consistency work before the
product has users.

## Decision
A single Spring Boot API (Java 21) organised as Spring Modulith modules, one package per area:
`shared`, `platform`, `identity`, `audit`, `onboarding`, and later `students`, `fees`, and so on.
`ModularityTests` fails the build when modules form a cycle or reach into each other's internals.
The web app is a separate Next.js project that talks to the API only through `/api`.

## Consequences
- One deployable, one database, simple local setup (`docker compose up`).
- A module that later needs to scale on its own can be split out along an existing boundary.
