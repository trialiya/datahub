# DataHub CLI (Incubating)

A small, standalone Java CLI for developers and DevOps to inspect a running DataHub instance.
It talks to GMS over GraphQL and needs nothing but a URL and a token.

## Build and run

```bash
./gradlew :metadata-integration:java:datahub-cli:installDist
./metadata-integration/java/datahub-cli/build/install/datahub-cli/bin/datahub-cli policies
```

During development you can also run it straight from Gradle:

```bash
./gradlew :metadata-integration:java:datahub-cli:run --args="policies --state ACTIVE"
```

## Configuration

Connection settings are resolved in this order, highest precedence first:

1. `--url` / `--token` options
2. `DATAHUB_GMS_URL` / `DATAHUB_GMS_TOKEN` environment variables
3. a YAML config file — `./datahub-cli.yaml`, then `~/.datahub/datahub-cli.yaml`, or `--config <path>`
4. the default URL `http://localhost:8080` (no token)

See [datahub-cli.example.yaml](datahub-cli.example.yaml).

## Commands

### `policies`

Lists policies and the privileges they grant.

```bash
datahub-cli policies
datahub-cli policies --state ACTIVE --type PLATFORM
datahub-cli policies --privilege MANAGE_POLICIES
datahub-cli policies --format csv > prod-policies.csv
datahub-cli policies --format json | jq '.[] | select(.state == "ACTIVE")'
```

Example output:

```
NAME                       TYPE      STATE     PRIVILEGES                                  ACTORS
All Users - View Entities  METADATA  ACTIVE    VIEW_ENTITY_PAGE, SEARCH_PRIVILEGE          <allUsers>
Editors - Tags & Terms     METADATA  ACTIVE    EDIT_ENTITY_TAGS, EDIT_ENTITY_OWNERS        groups: 2
Platform Admins            PLATFORM  ACTIVE    MANAGE_POLICIES, MANAGE_DOMAINS             roles: 1
Legacy Import Job          PLATFORM  INACTIVE  MANAGE_ACCESS_TOKENS                        users: 1

4 of 4 policies · http://localhost:8080
```

`<allUsers>`, `<allGroups>` and `<resourceOwners>` are dynamic actor rules — they are shown as-is
rather than expanded into principal lists, because expanding them requires evaluating group
membership and ownership per resource.

Options:

| Option         | Description                                   |
| -------------- | --------------------------------------------- |
| `-f, --format` | `TABLE` (default), `CSV` or `JSON`            |
| `--state`      | Filter by state, e.g. `ACTIVE`                |
| `--type`       | Filter by type, e.g. `METADATA` or `PLATFORM` |
| `--privilege`  | Only policies granting this privilege         |
| `--urn`        | Show the policy URN instead of its name       |

## Troubleshooting

Set `DATAHUB_CLI_DEBUG=1` to print stack traces on failure.
