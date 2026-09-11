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

### 2.2. Проблема 2.1 общая для всех аспектов и сущностей

`EDIT_ENTITY_PROPERTIES` — не исключение. Ни одна fine-grained привилегия не участвует в OpenAPI-решении:

| Аспект | GraphQL проверяет | OpenAPI требует |
|--------|-------------------|-----------------|
| `globalTags` | `EDIT_ENTITY_TAGS` | `EDIT_ENTITY` |
| `glossaryTerms` | `EDIT_ENTITY_GLOSSARY_TERMS` | `EDIT_ENTITY` |
| `ownership` | `EDIT_ENTITY_OWNERS` | `EDIT_ENTITY` |
| `domains` | `EDIT_ENTITY_DOMAINS` | `EDIT_ENTITY` |
| `editableDatasetProperties` и др. `editable*` | `EDIT_ENTITY_DOCS` | `EDIT_ENTITY` |
| `institutionalMemory` | `EDIT_ENTITY_DOC_LINKS` | `EDIT_ENTITY` |
| `deprecation` | `EDIT_ENTITY_DEPRECATION` | `EDIT_ENTITY` |
| `status` | `EDIT_ENTITY_STATUS` | `EDIT_ENTITY` |
| `dataProducts` | `EDIT_ENTITY_DATA_PRODUCTS` | `EDIT_ENTITY` |
| `structuredProperties` | `EDIT_ENTITY_PROPERTIES` | `EDIT_ENTITY` |
| `embed` | `EDIT_ENTITY_EMBED` | `EDIT_ENTITY` |
| `upstreamLineage` (dataset) | `EDIT_LINEAGE` | `EDIT_LINEAGE ∨ EDIT_ENTITY` — **только это** закрыто нашим `RESTRICTED_ASPECT_PRIVILEGES` |

Точечная запись `structuredProperties` в `RESTRICTED_ASPECT_PRIVILEGES` закрыла бы одну строку из двенадцати.

### 2.3. schemaField — sub-resource датасета, а не ресурс политики

В модели авторизации DataHub колонка **не является самостоятельным ресурсом**. GraphQL авторизует колоночные правки
на URN **родительского датасета** с отдельными колоночными привилегиями:

| Операция | Где | Ресурс проверки | Привилегия |
|----------|-----|-----------------|------------|
| теги на колонку | `LabelUtils.isAuthorizedToUpdateTags:236` | `targetUrn` = датасет, `subResource` = fieldPath | `EDIT_DATASET_COL_TAGS` |
| термины на колонку | `LabelUtils.isAuthorizedToUpdateTerms:257` | датасет | `EDIT_DATASET_COL_GLOSSARY_TERMS` |
| описание колонки | `DescriptionUtils.isAuthorizedToUpdateFieldDescription:328` | датасет | `EDIT_DATASET_COL_DESCRIPTION` |
| business attribute | `BusinessAttributeAuthorizationUtils:51` | датасет | `EDIT_DATASET_COL_BUSINESS_ATTRIBUTE` |
| **structuredProperties на колонку** | `UpsertStructuredPropertiesResolver:69` → `canEditProperties(assetUrn)` | **`urn:li:schemaField:(…)`** | `EDIT_ENTITY_PROPERTIES` |

Последняя строка — единственное место, где GraphQL авторизует колонку по её собственному URN. Это
непоследовательность upstream (резолвер писался для «любого asset URN» и про схему не думал), и именно она даёт
403 в GraphQL. Именно поэтому `schemaField` намеренно отсутствует в `ENTITY_RESOURCE_PRIVILEGES`: ему не положено
быть целью политики, политика вешается на датасет.

OpenAPI делает то же, что `UpsertStructuredPropertiesResolver`, только для **всех** аспектов schemaField:
`EntitySpec("schemaField", "urn:li:schemaField:(…)")` → `PolicyEngine.checkFilter` (`allMatch` по `TYPE`, `URN`) →
политика на датасет не совпадает ни по типу, ни по URN.

`PolicyEngine.java` (577 строк) прочитан целиком: schemaField-специфичной логики нет ни в `isResourceMatch` (:184),
ни в `checkCriterion` (:236), ни в `checkCondition` (:252). Это ожидаемо: при sub-resource-модели она там и не нужна —
remap на родителя должен происходить **до** PolicyEngine, в вызывающем коде.

### 2.4. Итог

| Слой | Ломает GraphQL? | Ломает OpenAPI? | Область |
|------|-----------------|-----------------|---------|
| 2.1/2.2 нет aspect→privilege | нет | **да** | все сущности, все аспекты |
| 2.3 schemaField авторизуется по своему URN | только structuredProperties | **да, все аспекты** | schemaField |

Правки ортогональны; без любой из них OpenAPI-сценарий остаётся 403.

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
расширение семантики `URN`/`TYPE`. Для колонок же upstream использует другую модель — sub-resource
(`subResources` + `privilegeConstraints` в `PolicyEngine`, в GraphQL — `EDIT_DATASET_COL_*` на родителе), что и
определяет выбор в § 4.2.

---

## 4. План реализации

### 4.1. Шаг 1 — общая таблица аспект → привилегии (`ASPECT_PRIVILEGES`)

Файл: `metadata-utils/.../PoliciesConfig.java`, рядом с `RESTRICTED_ASPECT_PRIVILEGES`.

```java
/** Entity-agnostic aspect-level privileges. Entity-specific overrides live in RESTRICTED_ASPECT_PRIVILEGES. */
public static final Map<String, Map<ApiOperation, Disjunctive<Conjunctive<Privilege>>>> ASPECT_PRIVILEGES =
    ImmutableMap.<String, ...>builder()
        .put(GLOBAL_TAGS_ASPECT_NAME,              edit(EDIT_ENTITY_TAGS_PRIVILEGE))
        .put(GLOSSARY_TERMS_ASPECT_NAME,           edit(EDIT_ENTITY_GLOSSARY_TERMS_PRIVILEGE))
        .put(OWNERSHIP_ASPECT_NAME,                edit(EDIT_ENTITY_OWNERS_PRIVILEGE))
        .put(DOMAINS_ASPECT_NAME,                  edit(EDIT_ENTITY_DOMAINS_PRIVILEGE))
        .put(STRUCTURED_PROPERTIES_ASPECT_NAME,    edit(EDIT_ENTITY_PROPERTIES_PRIVILEGE))
        .put(INSTITUTIONAL_MEMORY_ASPECT_NAME,     edit(EDIT_ENTITY_DOC_LINKS_PRIVILEGE))
        .put(DEPRECATION_ASPECT_NAME,              edit(EDIT_ENTITY_DEPRECATION_PRIVILEGE))
        .put(STATUS_ASPECT_NAME,                   edit(EDIT_ENTITY_STATUS_PRIVILEGE))
        .put(DATA_PRODUCTS_ASPECT_NAME,            edit(EDIT_ENTITY_DATA_PRODUCTS_PRIVILEGE))
        .put(EMBED_ASPECT_NAME,                    edit(EDIT_ENTITY_EMBED_PRIVILEGE))
        .put(EDITABLE_DATASET_PROPERTIES_ASPECT_NAME, edit(EDIT_ENTITY_DOCS_PRIVILEGE))
        // + остальные editable*Properties (chart, dashboard, dataFlow, dataJob, container, mlModel…)
        .build();

// edit(p): READ → как ENTITY READ; CREATE/UPDATE → p ∨ EDIT_ENTITY; DELETE → p ∨ EDIT_ENTITY ∨ DELETE_ENTITY
```

Порядок поиска в `AuthUtil.isAPIAuthorizedEntityUrnsWithAspect`:
1. `RESTRICTED_ASPECT_PRIVILEGES[entityType][aspect]` — per-entity override (как сейчас);
2. `ASPECT_PRIVILEGES[aspect]` — новый default;
3. иначе — entity-level `lookupEntityAPIPrivilege` (как сейчас).

Нюансы:
- `EDIT_ENTITY` обязательно в дизъюнкции — таблица **переопределяет**, а не AND-ит entity-привилегии.
- `READ` не сужать: `filterAuthorizedAspects` / `isProjectionDenied` (коммит #8) фильтруют аспекты на multi-aspect
  чтении по этой же таблице. Для `ASPECT_PRIVILEGES` READ = обычный ENTITY READ, и/или `filterAuthorizedAspects`
  смотрит только в `RESTRICTED_ASPECT_PRIVILEGES`. Второе проще и безопаснее.
- `isRestrictedAspect(entityType, aspect)` в `GenericEntitiesController.createEntity` (:670) сейчас управляет
  split-ом батча. Переименовать в `hasAspectPrivileges` и учитывать обе таблицы, иначе item с `globalTags`
  по-прежнему пойдёт через entity-level `isAPIAuthorizedEntityType(CREATE)`.
- Rest.li (`AspectResource`, `EntityResource`) идёт через те же `AuthUtil` методы — проверить, что таблица
  применяется и там, чтобы OpenAPI и Rest.li не разошлись.
- Тесты: `AuthUtilTest` — матрица `(аспект) × (fine-grained, EDIT_ENTITY, ничего) × (CREATE, UPDATE, DELETE)`;
  `GenericEntitiesControllerTest`; `AspectResourceTest`.

### 4.2. Шаг 2 — schemaField как sub-resource в `AuthUtil`

Ровно та модель, что в GraphQL: цель `urn:li:schemaField:(P, path)` → авторизовать `P` с колоночной привилегией.

```java
// AuthUtil
static EntitySpec authorizationTarget(Urn urn) {
  if (SCHEMA_FIELD_ENTITY_NAME.equals(urn.getEntityType())) {
    Urn parent = SchemaFieldUrn.createFromUrn(urn).getParentEntity();   // из URN, без I/O
    return new EntitySpec(parent.getEntityType(), parent.toString());
  }
  return new EntitySpec(urn.getEntityType(), urn.toString());
}
```

Таблица `SCHEMA_FIELD_ASPECT_PRIVILEGES` (применяется вместо `ASPECT_PRIVILEGES`, когда цель — schemaField):

| Аспект schemaField | CREATE/UPDATE | Аналог в GraphQL |
|--------------------|---------------|------------------|
| `globalTags` | `EDIT_DATASET_COL_TAGS ∨ EDIT_ENTITY` | `LabelUtils.isAuthorizedToUpdateTags` |
| `glossaryTerms` | `EDIT_DATASET_COL_GLOSSARY_TERMS ∨ EDIT_ENTITY` | `LabelUtils.isAuthorizedToUpdateTerms` |
| `documentation` | `EDIT_DATASET_COL_DESCRIPTION ∨ EDIT_ENTITY` | `DescriptionUtils.isAuthorizedToUpdateFieldDescription` |
| `businessAttributes` | `EDIT_DATASET_COL_BUSINESS_ATTRIBUTE ∨ EDIT_ENTITY` | `BusinessAttributeAuthorizationUtils` |
| `structuredProperties` | `EDIT_ENTITY_PROPERTIES ∨ EDIT_ENTITY` (нет `COL_*`-привилегии; вводить новую — только если реально нужна раздельная выдача) | — |
| `status`, `deprecation` | `EDIT_ENTITY_STATUS/DEPRECATION ∨ EDIT_ENTITY` | — |
| прочее (`schemaFieldKey`, `schemaFieldAliases`…) | `EDIT_ENTITY` родителя | — |

Где применяется:
- `AuthUtil.isAPIAuthorizedEntityUrnsWithAspect`, `isAPIAuthorizedEntityUrns`, `isAPIAuthorizedUrns` — все места,
  где из `Urn` строится `EntitySpec`. Централизовать в `authorizationTarget(urn)` / `buildEntitySpec(urn)`.
- `GenericEntitiesController.createEntity` (:670): `isAPIAuthorizedEntityType(CREATE, "schemaField")` →
  для schemaField проверять родителя per-item (тип-only проверка для schemaField не имеет смысла).
- **GraphQL**: `UpsertStructuredPropertiesResolver:69` → `canEditProperties(parentOf(assetUrn))`; `canEditProperties`
  сам может делать remap, тогда и `RemoveStructuredPropertiesResolver` починится автоматически.
- READ на schemaField: сейчас `VIEW_ENTITY_PAGE` на самой колонке — тоже мимо политик на датасет. Remap чинит и это.

Не трогаем: `PolicyEngine`, `DefaultEntitySpecResolver`, `*FieldResolverProvider`, `EntityFieldType`, UI политик.

Отвергнутый вариант — наследование scope в `EntityUrn/TypeFieldResolverProvider` (возвращать `{self, parent}` для
URN и `{"schemaField","dataset"}` для TYPE): технически возможно (родитель парсится из URN; «добавить» провайдер
нельзя из-за `Collectors.toMap` в `DefaultEntitySpecResolver.getFieldResolvers`, только править существующие), но
(а) расширяет **все** существующие политики на все привилегии, (б) требует флага, (в) противоречит sub-resource-модели
upstream, (г) ломается на `NOT_EQUALS` из master. Sub-resource remap в `AuthUtil` даёт тот же эффект без этих проблем.

### 4.3. Шаг 3 — портировать фикс `PolicyEngine.getFilter` из master

Наш `getFilter` добавляет URN-критерий только при `hasType() && hasResources() && !isAllResources()`.
Legacy-политика с `resources`, но без `type`, теряет ограничение по URN и превращается в «все ресурсы».
В master условие `hasResources() && !isAllResources()`. Отдельный коммит, отдельный тест в `PolicyEngineTest`.

### 4.4. Порядок и коммиты

1. `fix(auth): port getFilter URN criterion fix from upstream` — § 4.3 (независим, маленький)
2. `feat(auth): aspect-level privileges for OpenAPI/Rest.li writes` — § 4.1
3. `fix(auth): authorize schemaField writes against parent dataset with column privileges` — § 4.2
4. `fix(graphql): structured properties on schemaField authorize against parent dataset` — § 4.2, GraphQL-часть

---

## 5. Конфликты при обновлении на будущие версии DataHub

### 5.1. `AuthUtil` / `PoliciesConfig` — основная площадка изменений

Upstream не менял `isAPIAuthorizedEntityUrnsWithAspect` и соседние методы (наш `RESTRICTED_ASPECT_PRIVILEGES` —
fork-only, upstream аналога так и не завёл). `PoliciesConfig` в master вырос (новые сущности, привилегии,
`ENTITY_RESOURCE_PRIVILEGES` +8), но это добавления в других местах файла — конфликты вероятны, разрешение
тривиальное. Держать наши таблицы в отдельном блоке в конце файла с явным комментарием `// fork-only`.

### 5.2. `GenericEntitiesController` — единственный тяжёлый конфликт

В master контроллер делегирует в `metadata-io/.../EntityAuthorizationUtils.isAPIAuthorizedIngest(opContext, batch)`
и `isAPIAuthorizedBatchItems(...)`: результат per-item (HTTP-статус на элемент, а не общий 403), CREATE/UPDATE
определяется по факту существования entity (existence-aware), а не по `ChangeType`. Наш split батча по
`isRestrictedAspect` / `unauthorizedAspects` (:670) при апгрейде переписывается под эту точку входа. Сама логика
(таблицы + remap в `AuthUtil`) переезжает без изменений — `isAPIAuthorizedIngest` в конце тоже зовёт `AuthUtil`.

`toAspectApiOperation(changeType)` заменяется на existence-aware логику upstream.

### 5.3. `subResources` / `privilegeConstraints` в `PolicyEngine` master

Upstream ввёл в `DataHubResourceFilter.privilegeConstraints` и `evaluatePolicy(..., subResources)` — сейчас
используется только для ограничения «какие теги можно вешать» (`AuthUtil.tagSubResourceSpecs`). Это тот же
концепт «sub-resource», что и наш remap, но на уровне PolicyEngine. Если upstream расширит его на колонки
(schemaField как subResource датасета с privilegeConstraints), наш remap в `AuthUtil` станет дублирующим слоем:
снять его и перейти на upstream-механизм. До тех пор — никакого пересечения: мы в `AuthUtil`, они в `PolicyEngine`.

### 5.4. `UpsertStructuredPropertiesResolver` / `canEditProperties`

В master код идентичен нашему (`AuthorizationUtils:186`). Если upstream починит это сам, будет конфликт в одной
функции — взять upstream-версию.

### 5.5. Что НЕ конфликтует (потому что не трогаем)

`PolicyEngine` (кроме § 4.3, который совпадает с master и при rebase схлопнется), `DefaultEntitySpecResolver`
(в master сильно переписан: `GroupService`, `ContextualEntitySpecResolver`, два новых провайдера),
`*FieldResolverProvider`, `EntityFieldType` (в master +CONTAINER, +GLOSSARY), UI политик.

### 5.6. Сводка рисков

| Компонент | Риск конфликта | Тяжесть разрешения |
|-----------|----------------|--------------------|
| `AuthUtil` | низкий (upstream не менял эту часть) | низкая |
| `PoliciesConfig` | средний (большой файл, много добавлений в master) | низкая |
| `GenericEntitiesController` | высокий | **высокая** — переписать под `EntityAuthorizationUtils` |
| Rest.li `AspectResource`/`EntityResource` | средний | средняя |
| GraphQL `AuthorizationUtils.canEditProperties` | низкий | низкая |
| `PolicyEngine`, `DefaultEntitySpecResolver`, провайдеры | нет (не трогаем) | — |
