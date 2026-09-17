# DataHub CLI (Incubating)

A small, standalone Java CLI for developers and DevOps to inspect a running DataHub instance.
It talks to GMS over GraphQL and needs nothing but a URL and a token.

## Build and run

The distributable artifact is a single portable jar — copy it anywhere and run it with `java -jar`,
no `lib/` directory needed:

```bash
./gradlew :metadata-integration:java:datahub-cli:shadowJar
java -jar metadata-integration/java/datahub-cli/build/libs/datahub-cli.jar policies
```

A start script with the dependencies on the classpath is also available:

```bash
./gradlew :metadata-integration:java:datahub-cli:installDist
./metadata-integration/java/datahub-cli/build/install/datahub-cli/bin/datahub-cli policies
```

During development you can run it straight from Gradle, but note that Gradle captures stdin, so the
interactive shell needs one of the two forms above:

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

Every command works both as a one-shot invocation and inside the interactive shell.

### `shell`

Starts an interactive session with line editing, history (`~/.datahub/datahub-cli-history`) and TAB
completion of commands, options, option values, and entity and aspect names. `Ctrl+C` abandons the current line, `Ctrl+D` or
`exit` leaves the shell. Connection settings given to `shell` apply to every command run inside it.

```
$ java -jar datahub-cli.jar --url https://datahub.example.com shell
DataHub CLI · connected to https://datahub.example.com
Type 'help' for commands, TAB to complete, Ctrl+D to exit.

datahub> pol<TAB>
policies

datahub> policies --format <TAB>
TABLE  CSV  JSON

datahub> aspect <TAB>
urn:li:dataset:   urn:li:dataHubPolicy:   urn:li:chart:   ...      (71 entity types)

datahub> aspect urn:li:dataHubPolicy:0 <TAB>
dataHubPolicyInfo  dataHubPolicyKey                                (only that entity's aspects)

datahub> policies --state ACTIVE
...

datahub> exit
```

### `search`

Searches entities through the GraphQL `scrollAcrossEntities` query, with an optional `--where`
filter expression.

```bash
datahub-cli search '*' --where 'entity_type = dataset AND platform = hive'
datahub-cli search 'events' --where 'platform IN (hive, snowflake) AND env = PROD' -n 50
datahub-cli search '*' --where 'owner IS NOT NULL AND NOT tag = urn:li:tag:Deprecated'
datahub-cli search --where '...' --print-filters     # show the compiled filters, run nothing
```

By default the first `--limit` hits are returned (20). `--all` follows the scroll cursor until every
match is collected, 100 per request.

`--aspect` fetches aspects for the hits and prints one JSON object per line, ready for `jq`. The
aspects are read through OpenAPI v3 `batchGet`, grouped by entity type and chunked 100 at a time, so
a 250-hit search costs 3 search requests and 3 aspect requests rather than 250:

```bash
datahub-cli search '*' --where 'platform = hive' --all --aspect ownership --aspect status
```

```
{"urn":"urn:li:dataset:(urn:li:dataPlatform:hive,my_db.events,PROD)","type":"DATASET","aspects":{"ownership":{"owners":[...]}}}
```

Aspects that an entity does not have are simply absent from its `aspects` object.

The filter vocabulary mirrors the Python SDK's, so the same field names work in both CLIs:
`entity_type` (or `type`), `entity_subtype`, `platform`, `env`, `domain`, `container`, `tag`,
`glossary_term`, `owner`. Any other name is passed through as a raw Elasticsearch field.

Values are coerced the way the Python DSL coerces them — `platform = snowflake` is sent as
`platform.keyword = urn:li:dataPlatform:snowflake` — and `domain`, `container`, `tag`,
`glossary_term` and `owner` require full URNs.

**Supported subset**: conditions joined by `AND`, plus `NOT`, `!=`, `IN (a, b)` and
`IS [NOT] NULL`. Top-level `OR` and parentheses are rejected with an explanatory error rather than
parsed loosely, because supporting them means normalising the expression to disjunctive normal
form. `IN` still gives OR over one field's values, which covers most queries.

`--print-filters` shows what an expression compiles to, which is the quickest way to check a
mapping without running anything:

```
$ datahub-cli search --where 'platform = hive AND env = PROD' --print-filters
orFilters:
  - and: [{field=platform.keyword, condition=EQUAL, values=[urn:li:dataPlatform:hive]}, {field=origin, condition=EQUAL, values=[PROD]}]
  - and: [{field=platform.keyword, condition=EQUAL, values=[urn:li:dataPlatform:hive]}, {field=env, condition=EQUAL, values=[PROD]}]
```

Two clauses, because containers keep the environment in `env` while everything else keeps it in
`origin`. GMS takes `orFilters` as an OR of ANDs, so the platform condition is repeated into both.

### `aspect`

Fetches one aspect of one entity through the OpenAPI v3 endpoint
`GET /openapi/v3/entity/{entityName}/{urn}/{aspectName}`. The entity type is derived from the URN.

```bash
datahub-cli aspect 'urn:li:dataset:(urn:li:dataPlatform:hive,my_db.my_schema.events,PROD)' ownership
datahub-cli aspect <urn> ownership --version 2
datahub-cli aspect <urn> ownership --system-metadata
datahub-cli aspect <urn> ownership --list        # aspect names for the URN's entity type
```

Before fetching, the aspect name is validated against the server's own entity registry
(`GET /openapi/v1/registry/models/entity/specifications/{entityName}/aspects`), so a typo is
reported with the list of valid names instead of a bare 404:

```
$ datahub-cli aspect <urn> ownershipp
Unknown aspect 'ownershipp' for entity type 'dataset'. Known aspects: datasetKey,
datasetProperties, globalTags, ownership, schemaMetadata
```

That registry endpoint requires `MANAGE_SYSTEM_OPERATIONS_PRIVILEGE`. When the token does not have
it, validation is skipped silently and the aspect is fetched anyway — reading an aspect needs no
such privilege. `--list` says so explicitly rather than printing nothing.

Exit codes: `0` on success, `2` for an unknown aspect name, `1` when the entity has no such aspect.

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

## Where the model names come from

Entity and aspect names offered by TAB completion come from the `entity-registry.yml` compiled into
this CLI, read offline so a keystroke never waits on the network. It is the model of the build the
CLI was compiled from, which is good enough to type against but is not authoritative: a server may
run a different version or carry custom models. Correctness checks therefore still go to the server —
`aspect` validates the aspect name against `/openapi/v1/registry/models/...`, not against this copy.

Loading the registry parses every aspect's schema and takes about two seconds, so it is deferred:
one-shot commands never load it (`--version` still runs in ~0.25s), and `shell` starts loading it in
the background at startup so the first TAB press does not wait.

This is what makes the shaded jar ~47 MB rather than ~5 MB: the registry brings metadata-models and
the pegasus runtime, of which icu4j alone is 13 MB.

## Troubleshooting

Set `DATAHUB_CLI_DEBUG=1` to print stack traces on failure.
