# schemaField + structuredProperties через OpenAPI: краткий план

## Проблема

Пользователю выдана политика «Edit Dataset Properties» (`EDIT_ENTITY_PROPERTIES`) на датасет.
Запись `structuredProperties` в колонку (`urn:li:schemaField:(<datasetUrn>,<fieldPath>)`) через
OpenAPI (`POST /openapi/v3/entity/schemaField`, `.../aspect/structuredProperties`) возвращает 403:

> не имеет привилегий для редактирования StructuredProperties для schemaField

Поведение **одинаковое** в нашей ветке `v1` (b7eea9f) и в upstream `datahub-project/datahub@master`
(837b2ac0, 11.09.2026). Upstream проблему не чинил.

## Три независимые причины

| # | Причина | Где |
|---|---------|-----|
| 1 | OpenAPI/Rest.li не знают fine-grained привилегий. Для любого аспекта (кроме нашего `dataset/upstreamLineage`) требуется `CREATE_ENTITY ∨ EDIT_ENTITY` (CREATE) или `EDIT_ENTITY` (UPDATE). `EDIT_ENTITY_PROPERTIES` читает только GraphQL (`AuthorizationUtils.canEditProperties`). | `AuthUtil.isAPIAuthorizedEntityUrnsWithAspect`, `PoliciesConfig.API_PRIVILEGE_MAP` |
| 2 | OpenAPI авторизует `schemaField` как самостоятельный ресурс (`EntitySpec("schemaField", "urn:li:schemaField:(…)")`). В модели DataHub колонка — **sub-resource датасета**: GraphQL проверяет колоночные правки на URN **родительского датасета** с привилегиями `EDIT_DATASET_COL_TAGS / COL_GLOSSARY_TERMS / COL_DESCRIPTION / COL_BUSINESS_ATTRIBUTE` (`LabelUtils:236-280`, `DescriptionUtils:328`). Поэтому `schemaField` намеренно нет в `ENTITY_RESOURCE_PRIVILEGES`, и политика на датасет к нему не применяется. | `AuthUtil`, `GenericEntitiesController` |
| 3 | `UpsertStructuredPropertiesResolver:69` — единственное место в GraphQL, которое авторизует колонку по её собственному URN (`canEditProperties(schemaFieldUrn)`), а не по родителю. Upstream-непоследовательность; именно на неё и наткнулись. | `AuthorizationUtils.canEditProperties` |

Причина 1 — общая для всех сущностей и аспектов (не только structuredProperties и не только schemaField).
Причина 2 — общая для всех аспектов schemaField. Чинить нужно **обе**, иначе исходный сценарий всё равно даёт 403.

## Обходной путь (без кода)

Создать через API METADATA-политику с фильтром
`URN STARTS_WITH "urn:li:schemaField:(urn:li:dataset:(urn:li:dataPlatform:<plat>,<name>,<env>)"`
и привилегией `EDIT_ENTITY` (Edit All). Покроет все колонки одного датасета. Через UI не создать (п. 3).

## План работ (в порядке выполнения)

1. **Общая таблица аспект → привилегии для OpenAPI** (`PoliciesConfig.ASPECT_PRIVILEGES`, entity-agnostic):
   `globalTags → EDIT_ENTITY_TAGS`, `glossaryTerms → EDIT_ENTITY_GLOSSARY_TERMS`, `ownership → EDIT_ENTITY_OWNERS`,
   `domains → EDIT_ENTITY_DOMAINS`, `structuredProperties → EDIT_ENTITY_PROPERTIES`, `editable*Properties → EDIT_ENTITY_DOCS`,
   `deprecation → EDIT_ENTITY_DEPRECATION`, `status → EDIT_ENTITY_STATUS`, `institutionalMemory → EDIT_ENTITY_DOC_LINKS`,
   `dataProducts → EDIT_ENTITY_DATA_PRODUCTS`, `embed → EDIT_ENTITY_EMBED` — каждая в дизъюнкции с `EDIT_ENTITY`.
   Существующий `RESTRICTED_ASPECT_PRIVILEGES` остаётся как per-entity override поверх неё. Аспекты вне таблицы —
   как сейчас (`EDIT_ENTITY`). Одна точка: `AuthUtil.isAPIAuthorizedEntityUrnsWithAspect`.
2. **schemaField как sub-resource** (та же модель, что в GraphQL): в `AuthUtil` для `urn:li:schemaField:(P,path)`
   авторизовать **P** с колоночной привилегией: `globalTags → EDIT_DATASET_COL_TAGS`, `glossaryTerms → EDIT_DATASET_COL_GLOSSARY_TERMS`,
   `documentation → EDIT_DATASET_COL_DESCRIPTION`, `businessAttributes → EDIT_DATASET_COL_BUSINESS_ATTRIBUTE`,
   `structuredProperties → EDIT_ENTITY_PROPERTIES` (родителя), прочее → `EDIT_ENTITY` родителя. Без флага, без правок
   `PolicyEngine`/резолверов. Заодно поправить `UpsertStructuredPropertiesResolver` в GraphQL на тот же remap.
3. **Портировать из master фикс `PolicyEngine.getFilter`** — сейчас legacy-политика с `resources`, но без `type`,
   теряет URN-критерий и действует на всё. Отдельный коммит.

Отвергнуто: наследование scope через `EntityUrn/TypeFieldResolverProvider` (расширяет все существующие политики,
требует флага, расходится с sub-resource-моделью upstream) и добавление `schemaField` в `ENTITY_RESOURCE_PRIVILEGES`
(в модели DataHub колонка не является ресурсом политики).

## Риски при обновлении на новые версии DataHub

- Вся логика живёт в `AuthUtil` + `PoliciesConfig` — файлы, которые upstream в этой части не менял; `PolicyEngine`,
  `DefaultEntitySpecResolver`, провайдеры не трогаем → конфликтов там нет.
- В master авторизация OpenAPI переехала в `EntityAuthorizationUtils.isAPIAuthorizedIngest/BatchItems` (per-item
  результат, existence-aware CREATE/UPDATE). Наш хук в `GenericEntitiesController.createEntity` придётся переписать под
  новую точку входа — это единственный тяжёлый конфликт.
- Upstream ввёл `subResources` + `privilegeConstraints` в `PolicyEngine` (пока только для тегов). Если он расширит это
  на колонки, наш remap в `AuthUtil` станет лишним слоем и его нужно будет снять.
- `PoliciesConfig` — большой файл, много добавлений в master; конфликт при вставке таблицы вероятен, разрешение тривиально.

Подробности — в `schemafield-structured-properties-auth-DETAILED.md`.
Упрощённый вариант только с шагом 2 (schemaField → родитель, без `ASPECT_PRIVILEGES`; достаточно `EDIT_ENTITY` на датасет) —
в `schemafield-structured-properties-auth-MINIMAL.md`.
