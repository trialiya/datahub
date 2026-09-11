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
| 2 | `schemaField` — самостоятельный ресурс в PolicyEngine. Политика на датасет (`TYPE=dataset`, `URN=urn:li:dataset:…`) не матчится на ресурс `schemaField`: ни один field-resolver не выводит родительский датасет из URN колонки. Owner-политики тоже не работают — `ownership` у колонок пустой. | `DefaultEntitySpecResolver`, `EntityUrnFieldResolverProvider`, `EntityTypeFieldResolverProvider`, `PolicyEngine.checkFilter` |
| 3 | `schemaField` отсутствует в `ENTITY_RESOURCE_PRIVILEGES` → в UI политик его нельзя выбрать как тип ресурса. | `PoliciesConfig.ENTITY_RESOURCE_PRIVILEGES` |

Причины 1 и 2 ортогональны: чинить нужно **обе**, иначе исходный сценарий всё равно даёт 403.

## Обходной путь (без кода)

Создать через API METADATA-политику с фильтром
`URN STARTS_WITH "urn:li:schemaField:(urn:li:dataset:(urn:li:dataPlatform:<plat>,<name>,<env>)"`
и привилегией `EDIT_ENTITY` (Edit All). Покроет все колонки одного датасета. Через UI не создать (п. 3).

## План работ (в порядке выполнения)

1. **Аспект-привилегии для OpenAPI** — добавить в наш `RESTRICTED_ASPECT_PRIVILEGES`:
   `dataset → structuredProperties`, `schemaField → structuredProperties` →
   `{CREATE, UPDATE: EDIT_ENTITY_PROPERTIES ∨ EDIT_ENTITY; DELETE: EDIT_ENTITY_PROPERTIES ∨ DELETE_ENTITY}`.
   Локально, риск для чужих политик нулевой. Тесты на `AuthUtilTest`.
2. **Наследование scope родителя для schemaField** (за флагом `authorization.schemaField.inheritParentScope`, default `false`):
   - `EntityUrnFieldResolverProvider`: для `schemaField` возвращать `{selfUrn, parentDatasetUrn}`;
   - `EntityTypeFieldResolverProvider`: `{"schemaField", "dataset"}`;
   - `OwnerFieldResolverProvider`/`DomainFieldResolverProvider`: при пустом аспекте у колонки — брать у родителя.
   Родитель парсится из URN, I/O нет. Нельзя «добавить» новый провайдер для `URN`/`TYPE` — `Collectors.toMap` в
   `DefaultEntitySpecResolver.getFieldResolvers` упадёт на дубликате ключа; только правка существующих.
3. **`SCHEMA_FIELD_PRIVILEGES` в `ENTITY_RESOURCE_PRIVILEGES`** — чтобы UI позволял целить schemaField напрямую.
4. **Портировать из master фикс `PolicyEngine.getFilter`** — сейчас legacy-политика с `resources`, но без `type`,
   теряет URN-критерий и действует на всё. Отдельный коммит.

## Риски при обновлении на новые версии DataHub

- `DefaultEntitySpecResolver` и список провайдеров в master уже переписаны (новый конструктор с `GroupService`,
  `ContextualEntitySpecResolver`, `ContainerFieldResolverProvider`/`GlossaryFieldResolverProvider` на тех же строках) —
  текстовые конфликты гарантированы.
- Upstream расширяет матчинг через **новые** `EntityFieldType` (CONTAINER, GLOSSARY), а не через расширение `URN`/`TYPE`.
  Наш подход семантически расходится; при появлении `NOT_EQUALS` (уже есть в master) расширенный `TYPE` меняет смысл
  условия `TYPE NOT_EQUALS dataset`.
- `RESTRICTED_ASPECT_PRIVILEGES` — fork-only механизм; в master авторизация OpenAPI переехала в
  `EntityAuthorizationUtils.isAPIAuthorizedIngest/BatchItems`, наш хук в `GenericEntitiesController` придётся переносить.

Подробности — в `schemafield-structured-properties-auth-DETAILED.md`.
