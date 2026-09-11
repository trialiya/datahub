# schemaField + structuredProperties через OpenAPI: детальный разбор и план

Ветка: `v1` (HEAD b7eea9f, база upstream cbe0334, март 2025).
Сравнение с upstream: `datahub-project/datahub@master` 837b2ac0 (11.09.2026).

---

## 1. Симптом

1. Создана политика: тип ресурса `dataset`, ресурс `urn:li:dataset:(…)`, привилегия
   **Edit Dataset Properties** (`EDIT_ENTITY_PROPERTIES`).
2. Пользователь пишет аспект `structuredProperties` на колонку через OpenAPI:
   `POST /openapi/v3/entity/schemaField` или `POST /openapi/v3/entity/schemaField/{urn}/structuredProperties`.
3. Ответ 403: «не имеет привилегий для редактирования StructuredProperties для schemaField».

Через GraphQL (`upsertStructuredProperties`) — тоже 403, но по другой причине (см. § 2.2).

---

## 2. Разбор по слоям

### 2.1. OpenAPI не знает fine-grained привилегий

Путь запроса:

```
GenericEntitiesController.createEntity   (:670)
GenericEntitiesController.createAspect   (:779)
GenericEntitiesController.patchAspect    (:864)
GenericEntitiesController.deleteAspect   (:707)
      │
      ▼
AuthUtil.isAPIAuthorizedEntityUrnsWithAspect(session, op, urn, aspectName)   (:441)
      │
      ├─ isRestrictedAspect(entityType, aspectName)  → true только для dataset/upstreamLineage (fork)
      │       └─ isAPIAuthorizedAspect(...)          → RESTRICTED_ASPECT_PRIVILEGES
      │
      └─ иначе: isAPIAuthorizedEntityUrns(session, op, [urn])   ← имя аспекта ОТБРОШЕНО
                └─ lookupEntityAPIPrivilege(entityType, op)    (:806)
                      ├─ API_ENTITY_PRIVILEGE_MAP.get(entityType)  → для schemaField нет
                      └─ API_PRIVILEGE_MAP.get(ENTITY)             (:869)
                              CREATE → CREATE_ENTITY ∨ EDIT_ENTITY
                              UPDATE → EDIT_ENTITY
                              DELETE → DELETE_ENTITY
```

`EDIT_ENTITY_PROPERTIES` в `API_PRIVILEGE_MAP` и `API_ENTITY_PRIVILEGE_MAP` **не встречается**. Единственный
потребитель — `datahub-graphql-core/.../AuthorizationUtils.canEditProperties` (:154). Следствие: даже на самом
датасете (не колонке) «Edit Dataset Properties» через OpenAPI не даёт ничего. Требуется `EDIT_ENTITY`.

### 2.2. schemaField — отдельный ресурс для PolicyEngine

`DataHubAuthorizer.authorize()` → `DefaultEntitySpecResolver.resolve(EntitySpec("schemaField", "urn:li:schemaField:(…)"))`
→ `PolicyEngine.isResourceMatch` (:184) → `getFilter` (:208) → `checkFilter` (:232):

```java
return filter.getCriteria().stream().allMatch(criterion -> checkCriterion(criterion, resource));
```

UI политик всегда пишет **оба** критерия (`datahub-web-react/src/app/permissions/policy/policyUtils.ts:103-108`):

```ts
if (resourceFilter.type)      criteria.push(createCriterion('TYPE', [createCriterionValue(resourceFilter.type)]));
if (resourceFilter.resources) criteria.push(createCriterion('URN',  resourceFilter.resources.map(createCriterionValue)));
```

Значения полей ресурса берутся из провайдеров (`DefaultEntitySpecResolver`):

| Провайдер | Поле | Что возвращает для schemaField |
|-----------|------|--------------------------------|
| `EntityTypeFieldResolverProvider` | TYPE | `{"schemaField"}` — не равно `dataset` |
| `EntityUrnFieldResolverProvider` | URN | `{"urn:li:schemaField:(…)"}` — не равно `urn:li:dataset:(…)` |
| `OwnerFieldResolverProvider` | OWNER | `ownership` самой колонки — обычно пусто |
| `DomainFieldResolverProvider` | DOMAIN | `domains` самой колонки — пусто |
| `TagFieldResolverProvider` | TAG | `globalTags` колонки (может быть, но редко) |

Ни один провайдер не выводит родительский датасет из URN. Политика на датасет не матчится по обоим критериям.
Owner-based и domain-based политики — тоже мимо.

`PolicyEngine.java` (577 строк) прочитан целиком: schemaField-специфичной логики нет ни в `isResourceMatch`,
ни в `checkCriterion` (:236), ни в `checkCondition` (:252, только `EQUALS` / `STARTS_WITH`).

### 2.3. schemaField нельзя выбрать в UI

`PoliciesConfig.ENTITY_RESOURCE_PRIVILEGES` (:822) — 20 записей, `schemaField` нет. Дропдаун «Resource Type»
строится из этого списка. Политику на schemaField можно создать только через GraphQL/OpenAPI, вручную задав
`resources.filter.criteria`.

### 2.4. Итог

| Слой | Ломает GraphQL? | Ломает OpenAPI? |
|------|-----------------|-----------------|
| 2.1 нет aspect→privilege | нет | **да** |
| 2.2 нет наследования scope | **да** | **да** |
| 2.3 нет в UI | косвенно | косвенно |

Правки 2.1 и 2.2 ортогональны; без любой из них OpenAPI-сценарий остаётся 403.

---

## 3. Сравнение с upstream master (837b2ac0)

### 3.1. Что изменилось в `PolicyEngine.java`

| Изменение | Касается schemaField? |
|-----------|-----------------------|
| `GroupService` в конструкторе; `SessionActorIdentity` вместо разбора membership-аспектов | нет |
| `evaluatePolicy(..., List<ResolvedEntitySpec> subResources)`; `isSubResourceAllowed` по `DataHubResourceFilter.privilegeConstraints` | нет — используется только для тегов (`AuthUtil.tagSubResourceSpecs`) |
| Порядок: actor-предикаты → resource → ownership последним | нет |
| `PolicyEvaluationContext` public, кэш `resourceOwnersByUrn`, `directRoles`, `opContext` | нет |
| `PolicyGrantedPrivileges` с `reasonOfDeny`; `PolicyEvaluationResult` с `policyName` | нет |
| `checkCondition(List<String>)` + `NOT_EQUALS` | нет напрямую; см. § 5.3 |
| `getFilter`: URN-критерий добавляется при `hasResources() && !isAllResources()` (раньше ещё требовалось `hasType()`) | нет, но это баг форка — см. § 4.4 |

### 3.2. Что НЕ изменилось

- `grep -rn "schemaField\|SCHEMA_FIELD"` по `metadata-auth/`, `metadata-service/auth-impl/`,
  `metadata-utils/.../authorization/` в master → **0 совпадений**.
- `EntityUrnFieldResolverProvider.java` — `diff` с нашим пустой.
- `EDIT_ENTITY_PROPERTIES_PRIVILEGE` в master по-прежнему только в `PoliciesConfig` и graphql `AuthorizationUtils` (:186).
- `ENTITY_RESOURCE_PRIVILEGES` (:1013) вырос до 28 записей (DATA_PROCESS_INSTANCE, DOCUMENT, INGESTION_SOURCE,
  APPLICATION, DATAHUB_VIEW, ML_*) — `schemaField` нет.
- OpenAPI-write переехал в `metadata-io/.../EntityAuthorizationUtils.isAPIAuthorizedIngest / isAPIAuthorizedBatchItems`:
  решение принимается по парам `(ChangeType, Urn)` → `AuthUtil.isAPIAuthorizedUrns`. **Имя аспекта не участвует.**

### 3.3. Новое в upstream, важное для выбора подхода

- `EntityFieldType` получил `CONTAINER` и `GLOSSARY`.
- Добавлены `ContainerFieldResolverProvider` (читает аспект `container` через `SystemEntityClient`, затем
  `BoundHierarchyAccess.expandAncestors`) и `GlossaryFieldResolverProvider`.
- `DefaultEntitySpecResolver` реализует `ContextualEntitySpecResolver`, принимает `GroupService`, добавил
  `resolve(entitySpec, opContext)`, импорт провайдеров — wildcard.

Прецедент upstream для «наследования от родителя» — **новый EntityFieldType + новый провайдер**, а не
расширение семантики `URN`/`TYPE`.

---

## 4. План реализации

### 4.1. Шаг 1 — `structuredProperties` в `RESTRICTED_ASPECT_PRIVILEGES`

Файл: `metadata-utils/.../PoliciesConfig.java` (~:1161).

```java
// dataset
STRUCTURED_PROPERTIES_ASPECT_NAME → {
    READ:   API_PRIVILEGE_MAP ENTITY READ  (не ограничивать чтение),
    CREATE: Disjunctive(EDIT_ENTITY_PROPERTIES ∨ EDIT_ENTITY),
    UPDATE: Disjunctive(EDIT_ENTITY_PROPERTIES ∨ EDIT_ENTITY),
    DELETE: Disjunctive(EDIT_ENTITY_PROPERTIES ∨ DELETE_ENTITY ∨ EDIT_ENTITY)
}
// schemaField — то же самое
```

Нюансы:
- `RESTRICTED_ASPECT_PRIVILEGES` **переопределяет**, а не AND-ит entity-привилегии → `EDIT_ENTITY` нужно явно
  включить в дизъюнкцию, иначе «Edit All» перестанет давать право на structuredProperties.
- `READ` для structuredProperties ограничивать не нужно, но `filterAuthorizedAspects`/`isProjectionDenied`
  (fork, коммит #8) отфильтруют аспект на multi-aspect чтении, если READ будет задан. Задать READ = обычный
  `VIEW_ENTITY_PAGE`-эквивалент из `API_PRIVILEGE_MAP`, чтобы не изменить поведение чтения.
- `createEntity` (:670): item с restricted-аспектом проверяется per-aspect, остальные — через
  `isAPIAuthorizedEntityType(CREATE, entityName)`. Для батча `schemaField` с одним аспектом
  `structuredProperties` `hasUnrestrictedAspects == false` → entity-level проверка не сработает. Это ожидаемо.
- Тесты: `AuthUtilTest` — случаи `(dataset|schemaField, structuredProperties, CREATE|UPDATE|DELETE)` ×
  `{EDIT_ENTITY_PROPERTIES, EDIT_ENTITY, ничего}`; `GenericEntitiesControllerTest` — 403/200.

Результат: «Edit Dataset Properties» на **датасет** начинает работать через OpenAPI для самого датасета.
Для колонок — ещё нет (§ 2.2).

### 4.2. Шаг 2 — наследование scope родителя для schemaField (за флагом)

Флаг: `authorization.schemaField.inheritParentScope` (`application.yaml`, env
`AUTHORIZATION_SCHEMA_FIELD_INHERIT_PARENT_SCOPE`, default `false`). Пробрасывается в
`DefaultEntitySpecResolver` через `AuthorizerConfiguration`/`DataHubAuthorizerFactory`.

**Почему нельзя «просто добавить провайдер»:**

```java
// DefaultEntitySpecResolver.getFieldResolvers
return _entityFieldResolverProviders.stream()
    .flatMap(r -> r.getFieldTypes().stream().map(ft -> Pair.of(ft, r)))
    .collect(Collectors.toMap(Pair::getKey, p -> p.getValue().getFieldResolver(opContext, entitySpec)));
```

`Collectors.toMap` без merge-функции → второй провайдер, объявляющий `URN` или `TYPE`, роняет GMS на старте
`IllegalStateException: Duplicate key`. Единственные варианты: (а) править существующие провайдеры in-place,
(б) поменять `toMap` на merge и делать union множеств. Вариант (б) чище и ближе к «добавить провайдер», но
это правка в `DefaultEntitySpecResolver`, который в master сильно переписан (§ 5.1).

Изменения:

| Файл | Изменение |
|------|-----------|
| `SchemaFieldUrnUtils` (новый, `metadata-utils`) | `Optional<Urn> parentUrn(Urn schemaFieldUrn)` — парсинг `urn:li:schemaField:(<parent>,<path>)` через `SchemaFieldUrn.createFromUrn(...).getParentEntity()`. Без I/O. |
| `EntityUrnFieldResolverProvider` | если `entitySpec.getType() == "schemaField"` и флаг → `{selfUrn, parentUrn}` |
| `EntityTypeFieldResolverProvider` | аналогично → `{"schemaField", parentUrn.getEntityType()}` |
| `OwnerFieldResolverProvider`, `DomainFieldResolverProvider` | если аспект у колонки пуст и флаг → запросить у родителя (тут уже I/O: `entityClient.getV2(parentUrn)`) |
| `DefaultEntitySpecResolver` | принять флаг, передать в провайдеры |
| `application.yaml`, `AuthorizerConfiguration` | флаг |
| `PolicyEngineTest`, `DefaultEntitySpecResolverTest`, `*FieldResolverProviderTest` | тесты с флагом on/off |

Обоснование расширения `TYPE`: без него (`allMatch` + UI всегда пишет `TYPE`) расширение `URN` бесполезно.

Побочный эффект (почему флаг): политика «все датасеты → X» при включённом флаге начинает покрывать все
колонки для **всех** привилегий, включая `DELETE_ENTITY`. `STARTS_WITH`-политики на датасет тоже
расширяются. Для большинства инсталляций это желаемое поведение (колонка — часть датасета), но включать
по умолчанию нельзя.

### 4.3. Шаг 3 — `SCHEMA_FIELD_PRIVILEGES` в `ENTITY_RESOURCE_PRIVILEGES`

```java
public static final ResourcePrivileges SCHEMA_FIELD_PRIVILEGES =
    ResourcePrivileges.of("schemaField", "Schema Fields", "Schema fields (columns) of datasets",
        ImmutableList.of(VIEW_ENTITY_PAGE_PRIVILEGE, EDIT_ENTITY_TAGS_PRIVILEGE, EDIT_ENTITY_GLOSSARY_TERMS_PRIVILEGE,
                         EDIT_ENTITY_PROPERTIES_PRIVILEGE, EDIT_ENTITY_DOCS_PRIVILEGE, EDIT_ENTITY_PRIVILEGE));
```

UI подхватит автоматически (список приходит из `listPolicies`/`PolicyBuilder`). Проверить
`datahub-web-react/.../policy/policyUtils.ts` — нет ли захардкоженного списка иконок/лейблов по типу.

### 4.4. Шаг 4 — портировать фикс `PolicyEngine.getFilter` из master

Наш `getFilter` добавляет URN-критерий только при `hasType() && hasResources() && !isAllResources()`.
Legacy-политика с `resources`, но без `type`, теряет ограничение по URN и превращается в «все ресурсы».
В master условие `hasResources() && !isAllResources()`. Отдельный коммит, отдельный тест в `PolicyEngineTest`.

### 4.5. Порядок и коммиты

1. `fix(auth): honor EDIT_ENTITY_PROPERTIES for structuredProperties writes via OpenAPI` — § 4.1
2. `fix(auth): port getFilter URN criterion fix from upstream` — § 4.4
3. `feat(auth): schemaField inherits parent dataset scope in policy matching (flagged)` — § 4.2
4. `feat(auth): expose schemaField as policy resource type` — § 4.3

---

## 5. Конфликты при обновлении на будущие версии DataHub

### 5.1. `DefaultEntitySpecResolver` — конфликт гарантирован

В master: другой конструктор (`+ GroupService`), интерфейс `ContextualEntitySpecResolver`, вторая перегрузка
`resolve(entitySpec, opContext)`, wildcard-импорт, и **два новых провайдера на тех же строках** списка
(`ContainerFieldResolverProvider`, `GlossaryFieldResolverProvider`). Любая наша правка этого файла (флаг,
merge в `toMap`) — текстовый конфликт при каждом rebase. Разрешение простое (union списков), но ручное.

Смягчение: держать нашу логику в провайдерах, а в `DefaultEntitySpecResolver` — минимум (одна строка
конструктора с флагом).

### 5.2. Расширение `URN`/`TYPE` vs прецедент upstream

Upstream добавляет **новые** поля (`CONTAINER`, `GLOSSARY`) и не трогает семантику `URN`/`TYPE`. Если
когда-нибудь upstream сделает наследование для schemaField, он почти наверняка введёт что-то вроде
`EntityFieldType.PARENT` / `DATASET` с отдельным провайдером и отдельным критерием в UI. Тогда:

- наши расширенные `URN`/`TYPE` будут матчить политики, которые upstream-логика матчить не должна
  (двойное покрытие, но не отказ в доступе);
- при появлении upstream-константы `EntityFieldType` рядом с нашей — конфликт в enum и в `EntitySpec.pdl`
  (если добавляли туда).

Альтернатива, ближе к upstream: вместо расширения `URN`/`TYPE` ввести `EntityFieldType.PARENT_URN` /
`PARENT_TYPE` и провайдер `SchemaFieldParentFieldResolverProvider`. Минус: UI не пишет такие критерии,
политику придётся создавать через API (пока не доработан UI) — исходную задачу «политика на датасет
покрывает колонки» это не решает без правки `policyUtils.ts`. Поэтому выбран вариант расширения `URN`/`TYPE`
за флагом; при переходе на upstream-решение флаг выключается, код удаляется.

### 5.3. `NOT_EQUALS` (уже в master)

`checkCondition` в master принимает `List<String>` значений ресурса. При `NOT_EQUALS` семантика —
`noneMatch`. С расширенным `TYPE = {"schemaField", "dataset"}` критерий `TYPE NOT_EQUALS dataset` для
колонки перестанет срабатывать (раньше — срабатывал). Это ожидаемо и логично при включённом флаге, но
нужно зафиксировать в тесте при портировании `NOT_EQUALS`.

### 5.4. `RESTRICTED_ASPECT_PRIVILEGES` и новый OpenAPI-путь в master

Механизм fork-only. В master `GenericEntitiesController` делегирует в
`EntityAuthorizationUtils.isAPIAuthorizedIngest(opContext, batch)` и `isAPIAuthorizedBatchItems(...)`,
которые возвращают per-item результат (HTTP-статус на item), а не bool. Наш хук с `unauthorizedAspects`
в `createEntity` (:670) при апгрейде придётся переписать под `isAPIAuthorizedIngest` — точка входа
одна, но сигнатура и модель ошибок другие. Сама таблица `RESTRICTED_ASPECT_PRIVILEGES` и
`AuthUtil.isAPIAuthorizedAspect` переживут rebase (upstream эти файлы в этой части не менял), конфликт
будет только в контроллере.

Также upstream ввёл existence-aware привилегии (`CREATE` vs `UPDATE` определяется по факту существования
entity, а не по `ChangeType`) — `toAspectApiOperation(changeType)` у нас нужно будет заменить на их логику.

### 5.5. `PolicyEngine`

Планом **не трогается** (кроме § 4.4, который повторяет master и при rebase схлопнется без конфликта).
Это осознанно: сигнатуры `evaluatePolicy`/`isPolicyApplicable`/`getGrantedPrivileges` в master изменены,
возвращаемые типы другие (`PolicyGrantedPrivileges`) — любая fork-правка внутри `PolicyEngine`
конфликтовала бы тяжело.

### 5.6. `ENTITY_RESOURCE_PRIVILEGES`

Список в master +8 записей. Добавление `SCHEMA_FIELD_PRIVILEGES` — конфликт в одной строке `ImmutableList.of(...)`,
разрешается тривиально.

### 5.7. Сводка рисков

| Компонент | Риск конфликта | Тяжесть разрешения |
|-----------|----------------|--------------------|
| `DefaultEntitySpecResolver` | высокий | низкая (union) |
| `Entity{Urn,Type}FieldResolverProvider` | низкий (upstream не менял) | — |
| `Owner/DomainFieldResolverProvider` | средний (upstream добавил кэш owners в `PolicyEvaluationContext`) | средняя |
| `EntityFieldType` | низкий, если не добавлять констант | — |
| `PoliciesConfig` | средний (большой файл, много добавлений в master) | низкая |
| `GenericEntitiesController` | высокий | **высокая** — переписать под `EntityAuthorizationUtils` |
| `PolicyEngine` | нет (не трогаем) | — |
| `application.yaml` | низкий | низкая |
