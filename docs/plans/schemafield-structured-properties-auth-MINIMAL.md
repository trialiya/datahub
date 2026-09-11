# schemaField через OpenAPI: минимальный вариант (без `ASPECT_PRIVILEGES`)

Упрощение плана из `schemafield-structured-properties-auth-DETAILED.md`: не вводим таблицу аспект → привилегии.
Единственное изменение — **schemaField авторизуется как его родительский датасет**. Какие привилегии при этом
требуются, решает существующая entity-level логика (`CREATE_ENTITY ∨ EDIT_ENTITY` / `EDIT_ENTITY` / `DELETE_ENTITY`)
уже применительно к датасету.

## Идея

Сегодня для `urn:li:schemaField:(P, path)` строится `EntitySpec("schemaField", "urn:li:schemaField:(P,path)")`,
и политика на датасет `P` его не матчит. Заменяем на `EntitySpec(P.getEntityType(), P.toString())` — и всё
остальное (PolicyEngine, политики, UI) работает без изменений.

Эффект для пользователя: политика **Edit All (`EDIT_ENTITY`) на датасет** даёт право писать любые аспекты
всех его колонок через OpenAPI/Rest.li. Fine-grained привилегии (`EDIT_ENTITY_PROPERTIES`, `EDIT_DATASET_COL_*`)
через OpenAPI по-прежнему ничего не дают — так же, как и для самого датасета сегодня. Это осознанное ограничение
варианта.

## Изменения

### 1. `AuthUtil` — одна функция и её применение

`metadata-auth/auth-api/src/main/java/com/datahub/authorization/AuthUtil.java`

```java
/** Authorization target for a URN. schemaField is a sub-resource of its parent dataset. */
@Nonnull
static EntitySpec toEntitySpec(@Nonnull Urn urn) {
  if (Constants.SCHEMA_FIELD_ENTITY_NAME.equals(urn.getEntityType())
      && urn.getEntityKey().size() == 2) {
    try {
      Urn parent = Urn.createFromString(urn.getEntityKey().get(0));
      return new EntitySpec(parent.getEntityType(), parent.toString());
    } catch (URISyntaxException e) {
      // fall through: malformed key, authorize as-is
    }
  }
  return new EntitySpec(urn.getEntityType(), urn.toString());
}
```

Родитель берётся из ключа URN (`SchemaFieldUtils` в `metadata-utils` уже так делает: `getEntityKey().get(0)`),
без запросов к БД.

Заменить все `new EntitySpec(urn.getEntityType(), urn.toString())` на `toEntitySpec(urn)`:

| Строка | Метод |
|--------|-------|
| :189, :197, :205 | `isAPIAuthorized(... changeTypePairs)` — batch по `ChangeType` |
| :261 | `isAPIAuthorizedUrns` (не-ENTITY ApiGroup) |
| :275 | `isAPIAuthorizedEntityUrns` |
| :427 | `isAPIAuthorizedAspect` (restricted-аспекты) |
| :683 | `isAuthorizedUrns` |

`isAPIAuthorizedEntityUrns` группирует по `EntitySpec::getType` и через `lookupEntityAPIPrivilege(type, op)`
берёт привилегии для типа — после remap это будет тип **родителя** (`dataset`), что и нужно: для schemaField в
`API_ENTITY_PRIVILEGE_MAP` записи нет, для dataset — есть.

### 2. `GenericEntitiesController.createEntity` (:670)

Тип-only проверка `isAPIAuthorizedEntityType(opContext, CREATE, "schemaField")` строит `EntitySpec("schemaField", "")`
и remap не проходит. Для `entityName == schemaField` заменить на per-item
`isAPIAuthorizedEntityUrns(opContext, CREATE, urnsOfBatch)` — каждая колонка проверится по своему родителю.
Остальные точки контроллера (`:707`, `:779`, `:864`, `:557-586`) уже идут через `isAPIAuthorizedEntityUrns*` и
чинятся правкой 1.

### 3. Rest.li

`AspectResource`, `EntityResource` (`metadata-service/restli-servlet-impl`) вызывают те же `AuthUtil.isAPIAuthorized*`
методы — правка 1 покрывает их автоматически. Проверить, нет ли там своего `new EntitySpec(...)`:
`grep -rn "new EntitySpec(" metadata-service/restli-servlet-impl`.

### 4. GraphQL (опционально, но логично)

`AuthorizationUtils.canEditProperties(targetUrn, ctx)` (:154) — единственная GraphQL-проверка, которая авторизует
schemaField по его собственному URN. Применить тот же remap:

```java
EntitySpec target = AuthUtil.toEntitySpec(targetUrn);
return isAuthorized(context, target.getType(), target.getEntity(), orPrivilegeGroups);
```

Тогда `upsertStructuredProperties` на колонку заработает от политики «Edit Dataset Properties» на датасет —
как и остальные колоночные мутации (`LabelUtils`, `DescriptionUtils`), которые уже проверяют родителя.

### 5. Тесты

- `AuthUtilTest`: `toEntitySpec` для dataset / schemaField / schemaField с битым ключом; `isAPIAuthorizedEntityUrns`
  с политикой на `urn:li:dataset:(…)` и целью `urn:li:schemaField:(urn:li:dataset:(…),col)` → true; политика на
  другой датасет → false.
- `GenericEntitiesControllerTest`: `POST /entity/schemaField` с `EDIT_ENTITY` на родителя → 200/201;
  без → 403.
- Существующие `PolicyEngineTest`, `DefaultEntitySpecResolverTest` — без изменений (не трогаем).

## Что покрывает и что нет

| Сценарий | Результат |
|----------|-----------|
| `EDIT_ENTITY` на датасет → любой аспект любой колонки через OpenAPI/Rest.li | ✅ |
| `EDIT_ENTITY` на датасет → чтение колонки (`VIEW_ENTITY_PAGE`/`GET_ENTITY` от датасета) | ✅ (READ тоже идёт через remap) |
| Owner-/domain-политика на датасет → колонки | ✅ (`OwnerFieldResolverProvider` теперь читает `ownership` датасета) |
| `EDIT_ENTITY_PROPERTIES` на датасет → `structuredProperties` колонки через **GraphQL** | ✅ при правке 4 |
| `EDIT_ENTITY_PROPERTIES` на датасет → `structuredProperties` колонки через **OpenAPI** | ❌ — OpenAPI не знает fine-grained привилегий вообще (и для датасета тоже). Нужен `ASPECT_PRIVILEGES` из детального плана |
| `EDIT_DATASET_COL_TAGS` на датасет → `globalTags` колонки через OpenAPI | ❌ — то же |
| Политика непосредственно на `urn:li:schemaField:(…)` (созданная через API) | ❌ — перестаёт работать: ресурс теперь всегда датасет. Таких политик в UI создать нельзя, риск сломать существующие мал, но нужно предупредить в release notes |

Последняя строка — единственное **сужение** прав. Всё остальное — расширение: то, что было 403, становится 200.

## Порядок и коммиты

1. `fix(auth): authorize schemaField as a sub-resource of its parent dataset` — правки 1, 2, 3, тесты.
2. `fix(graphql): canEditProperties on schemaField checks the parent dataset` — правка 4.
3. (независимо) `fix(auth): port getFilter URN criterion fix from upstream` — как в детальном плане § 4.3.

Дальнейший путь: если позже понадобятся fine-grained привилегии через OpenAPI — добавляется `ASPECT_PRIVILEGES`
поверх (детальный план § 4.1); `toEntitySpec` остаётся как есть.

## Конфликты при обновлении на будущие версии DataHub

- **`AuthUtil`** — upstream master (837b2ac0) эту часть не менял, `new EntitySpec(urn.getEntityType(), urn.toString())`
  на тех же местах. Конфликтов при rebase не ожидается; если появятся новые точки построения `EntitySpec`
  (в master их больше — `EntityAuthorizationUtils` в `metadata-io`), их нужно будет перевести на `toEntitySpec`.
  Это главный риск варианта: remap легко **потерять** в новой точке входа, и колонки тихо вернутся к старому
  поведению. Смягчение: тест на `GenericEntitiesController` уровня HTTP (403 → 200), который упадёт при регрессии.
- **`GenericEntitiesController.createEntity`** — в master переписан под `EntityAuthorizationUtils.isAPIAuthorizedIngest`
  (per-item статусы, existence-aware CREATE/UPDATE). Наша правка 2 при апгрейде выбрасывается, remap должен
  оказаться внутри `isAPIAuthorizedIngest` → `AuthUtil.isAPIAuthorizedUrns` (см. предыдущий пункт).
- **`subResources` / `privilegeConstraints`** в `PolicyEngine` master — концептуально тот же sub-resource, но
  на уровне движка. Если upstream распространит его на колонки, `toEntitySpec` станет дублирующим слоем;
  снимается одной правкой.
- **`canEditProperties`** — в master идентичен нашему; если upstream починит сам, конфликт в одной функции.
- **`PolicyEngine`, `DefaultEntitySpecResolver`, провайдеры, `PoliciesConfig`, UI** — не трогаем, конфликтов нет.
  В сравнении с детальным планом отпадает и конфликт в `PoliciesConfig`.

| Компонент | Риск конфликта | Тяжесть |
|-----------|----------------|---------|
| `AuthUtil.toEntitySpec` + вызовы | низкий | низкая; главный риск — потерять remap в новых точках входа upstream |
| `GenericEntitiesController.createEntity` | высокий | средняя — правка одноразовая, при апгрейде переносится в `isAPIAuthorizedIngest` |
| GraphQL `canEditProperties` | низкий | низкая |
| Всё остальное | нет | — |
