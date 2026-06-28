# Simple DataHub quickstart (pin a version and run)

A self-contained Docker Compose stack for running DataHub locally. The only
thing you set is the **image version/tag**; everything else has sane defaults.

## Usage

1. Set the version you want in [`.env`](./.env):

   ```env
   DATAHUB_VERSION=v1.5.0.6
   ```

2. Start it:

   ```bash
   cd docker/quickstart-simple
   docker compose up -d
   ```

3. Open the UI at <http://localhost:9002> and log in with `datahub` / `datahub`.

4. Stop it (data is preserved in named volumes):

   ```bash
   docker compose down
   ```

   To also wipe stored metadata: `docker compose down -v`.

## How it differs from `docker/profiles/`

`docker/profiles/docker-compose.yml` is the full matrix (MySQL/Postgres/Cassandra,
Neo4j, consumers, CDC, debug builds) and requires selecting a Compose **profile**.

This file is a single fixed topology — **MySQL + OpenSearch + Kafka** with GMS,
Frontend, and Actions — and **no profiles**, so a plain `docker compose up`
starts the whole thing. It reuses the canonical per-service env files under
`../profiles/<service>/env/docker.env`, overriding only what this topology needs.

## What runs

| Service                 | Purpose                                        | Port |
| ----------------------- | ---------------------------------------------- | ---- |
| `mysql`                 | Primary metadata store                         | 3306 |
| `opensearch`            | Search + graph index                           | 9200 |
| `kafka-broker`          | Event stream (KRaft, no ZooKeeper)             | 9092 |
| `datahub-system-update` | One-shot SQL setup + index build, then exits   | —    |
| `datahub-gms`           | Metadata service (REST/GraphQL)                | 8080 |
| `datahub-frontend-react`| Web UI                                         | 9002 |
| `datahub-actions`       | Actions/ingestion executor                     | —    |

## Common overrides

All settable in `.env`:

| Variable                        | Default                       | Meaning                          |
| ------------------------------- | ----------------------------- | -------------------------------- |
| `DATAHUB_VERSION`               | `v1.5.0.6`                    | Image tag for all DataHub images |
| `DATAHUB_MAPPED_FRONTEND_PORT`  | `9002`                        | Host port for the UI             |
| `DATAHUB_MAPPED_GMS_PORT`       | `8080`                        | Host port for GMS                |
| `DATAHUB_SEARCH_TAG`            | `2.19.3`                      | OpenSearch image tag             |
| `DATAHUB_MYSQL_VERSION`         | `8.2`                         | MySQL image tag                  |
| `METADATA_SERVICE_AUTH_ENABLED` | `true`                        | Require auth tokens on GMS       |
