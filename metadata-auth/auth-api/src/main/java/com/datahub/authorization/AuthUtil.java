package com.datahub.authorization;

import static com.linkedin.metadata.Constants.CHART_ENTITY_NAME;
import static com.linkedin.metadata.Constants.DASHBOARD_ENTITY_NAME;
import static com.linkedin.metadata.Constants.DATASET_ENTITY_NAME;
import static com.linkedin.metadata.Constants.DATA_FLOW_ENTITY_NAME;
import static com.linkedin.metadata.Constants.DATA_JOB_ENTITY_NAME;
import static com.linkedin.metadata.Constants.DATA_PRODUCT_ENTITY_NAME;
import static com.linkedin.metadata.Constants.DOMAIN_ENTITY_NAME;
import static com.linkedin.metadata.Constants.GLOSSARY_NODE_ENTITY_NAME;
import static com.linkedin.metadata.Constants.GLOSSARY_TERM_ENTITY_NAME;
import static com.linkedin.metadata.Constants.ML_FEATURE_ENTITY_NAME;
import static com.linkedin.metadata.Constants.ML_FEATURE_TABLE_ENTITY_NAME;
import static com.linkedin.metadata.Constants.ML_MODEL_ENTITY_NAME;
import static com.linkedin.metadata.Constants.ML_MODEL_GROUP_ENTITY_NAME;
import static com.linkedin.metadata.Constants.ML_PRIMARY_KEY_ENTITY_NAME;
import static com.linkedin.metadata.Constants.NOTEBOOK_ENTITY_NAME;
import static com.linkedin.metadata.authorization.ApiGroup.ENTITY;
import static com.linkedin.metadata.authorization.ApiOperation.CREATE;
import static com.linkedin.metadata.authorization.ApiOperation.DELETE;
import static com.linkedin.metadata.authorization.ApiOperation.READ;
import static com.linkedin.metadata.authorization.ApiOperation.UPDATE;
import static com.linkedin.metadata.authorization.Disjunctive.DENY_ACCESS;
import static com.linkedin.metadata.authorization.PoliciesConfig.API_ENTITY_PRIVILEGE_MAP;
import static com.linkedin.metadata.authorization.PoliciesConfig.API_PRIVILEGE_MAP;
import static com.linkedin.metadata.authorization.PoliciesConfig.MANAGE_SYSTEM_OPERATIONS_PRIVILEGE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.linkedin.common.urn.Urn;
import com.linkedin.events.metadata.ChangeType;
import com.linkedin.metadata.authorization.ApiGroup;
import com.linkedin.metadata.authorization.ApiOperation;
import com.linkedin.metadata.authorization.Conjunctive;
import com.linkedin.metadata.authorization.Disjunctive;
import com.linkedin.metadata.authorization.PoliciesConfig;
import com.linkedin.metadata.browse.BrowseResult;
import com.linkedin.metadata.browse.BrowseResultEntity;
import com.linkedin.metadata.models.AspectSpec;
import com.linkedin.metadata.models.registry.EntityRegistry;
import com.linkedin.metadata.query.AutoCompleteEntity;
import com.linkedin.metadata.query.AutoCompleteResult;
import com.linkedin.metadata.search.ScrollResult;
import com.linkedin.metadata.search.SearchEntity;
import com.linkedin.metadata.search.SearchResult;
import com.linkedin.metadata.utils.EntityKeyUtils;
import com.linkedin.mxe.MetadataChangeProposal;
import com.linkedin.util.Pair;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.PostConstruct;
import org.apache.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Notes: This class is an attempt to unify privilege checks across APIs.
 *
 * <p>Public: The intent is that the public interface uses the typical abstractions for Urns,
 * ApiOperation, ApiGroup, and entity type strings
 *
 * <p>Private functions can use the more specific Privileges, Disjunctive/Conjunctive interfaces
 * required for the policy engine and authorizer
 *
 * <p>isAPI...() functions are intended for OpenAPI and Rest.li since they are governed by an enable
 * flag. GraphQL is always enabled and should use is...() functions.
 */
@Component
public class AuthUtil {

  // Since all methods of this class are static, need to postConstruct to initialize the static var
  // from the instance var that spring can initialize
  // TODO: Some unit tests seem to rely on this being false, so setting the default to false.
  // When running as the spring boot application, the default property value is true.
  private static boolean isRestApiAuthorizationEnabled = false;

  // Eliminating the dependency on the env var REST_API_AUTHORIZATION_ENABLED and instead using the
  // application property to keep it consistent with all other usage of that property.
  @Value("${authorization.restApiAuthorization:true}")
  protected Boolean restApiAuthorizationEnabled;

  @PostConstruct
  protected void init() {
    AuthUtil.isRestApiAuthorizationEnabled = this.restApiAuthorizationEnabled;
  }

  /**
   * This should generally follow the policy creation UI with a few exceptions for users, groups,
   * containers, etc so that the platform still functions as expected.
   */
  public static final Set<String> VIEW_RESTRICTED_ENTITY_TYPES =
      ImmutableSet.of(
          DATASET_ENTITY_NAME,
          DASHBOARD_ENTITY_NAME,
          CHART_ENTITY_NAME,
          ML_MODEL_ENTITY_NAME,
          ML_FEATURE_ENTITY_NAME,
          ML_MODEL_GROUP_ENTITY_NAME,
          ML_FEATURE_TABLE_ENTITY_NAME,
          ML_PRIMARY_KEY_ENTITY_NAME,
          DATA_FLOW_ENTITY_NAME,
          DATA_JOB_ENTITY_NAME,
          GLOSSARY_TERM_ENTITY_NAME,
          GLOSSARY_NODE_ENTITY_NAME,
          DOMAIN_ENTITY_NAME,
          DATA_PRODUCT_ENTITY_NAME,
          NOTEBOOK_ENTITY_NAME);

  /** OpenAPI/Rest.li Methods */
  public static List<Pair<MetadataChangeProposal, Integer>> isAPIAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiGroup apiGroup,
      @Nonnull final EntityRegistry entityRegistry,
      @Nonnull final Collection<MetadataChangeProposal> mcps) {

    List<Pair<Pair<ChangeType, Urn>, MetadataChangeProposal>> changeUrnMCPs =
        mcps.stream()
            .map(
                mcp -> {
                  Urn urn = mcp.getEntityUrn();
                  if (urn == null) {
                    com.linkedin.metadata.models.EntitySpec entitySpec =
                        entityRegistry.getEntitySpec(mcp.getEntityType());
                    urn = EntityKeyUtils.getUrnFromProposal(mcp, entitySpec.getKeyAspectSpec());
                  }
                  return Pair.of(Pair.of(mcp.getChangeType(), urn), mcp);
                })
            .collect(Collectors.toList());

    Map<Pair<ChangeType, Urn>, Integer> authorizationResult =
        isAPIAuthorizedUrns(
            session,
            apiGroup,
            changeUrnMCPs.stream().map(Pair::getFirst).collect(Collectors.toSet()));

    return changeUrnMCPs.stream()
        .map(
            changeUrnMCP -> {
              final MetadataChangeProposal mcp = changeUrnMCP.getValue();
              final Urn mcpUrn = changeUrnMCP.getKey().getSecond();
              int status =
                  authorizationResult.getOrDefault(
                      changeUrnMCP.getKey(), HttpStatus.SC_INTERNAL_SERVER_ERROR);
              if ((status == HttpStatus.SC_OK || status == HttpStatus.SC_FORBIDDEN)
                  && mcp.getAspectName() != null
                  && isRestrictedAspect(mcpUrn.getEntityType(), mcp.getAspectName())) {
                // A restricted aspect is governed by its own privileges instead of the entity-level
                // ones, so the aspect check replaces the result above rather than narrowing it.
                status =
                    isAPIAuthorizedAspect(
                            session,
                            toAspectApiOperation(changeUrnMCP.getKey().getFirst()),
                            mcpUrn,
                            mcp.getAspectName())
                        ? HttpStatus.SC_OK
                        : HttpStatus.SC_FORBIDDEN;
              }
              return Pair.of(mcp, status);
            })
        .collect(Collectors.toList());
  }

  public static Map<Pair<ChangeType, Urn>, Integer> isAPIAuthorizedUrns(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiGroup apiGroup,
      @Nonnull final Collection<Pair<ChangeType, Urn>> changeTypeUrns) {

    return changeTypeUrns.stream()
        .distinct()
        .map(
            changeTypePair -> {
              final Urn urn = changeTypePair.getSecond();
              switch (changeTypePair.getFirst()) {
                case CREATE:
                case UPSERT:
                case UPDATE:
                case RESTATE:
                case PATCH:
                  if (!isAPIAuthorized(
                      session,
                      lookupAPIPrivilege(apiGroup, UPDATE, urn.getEntityType()),
                      new EntitySpec(urn.getEntityType(), urn.toString()))) {
                    return Pair.of(changeTypePair, HttpStatus.SC_FORBIDDEN);
                  }
                  break;
                case CREATE_ENTITY:
                  if (!isAPIAuthorized(
                      session,
                      lookupAPIPrivilege(apiGroup, CREATE, urn.getEntityType()),
                      new EntitySpec(urn.getEntityType(), urn.toString()))) {
                    return Pair.of(changeTypePair, HttpStatus.SC_FORBIDDEN);
                  }
                  break;
                case DELETE:
                  if (!isAPIAuthorized(
                      session,
                      lookupAPIPrivilege(apiGroup, DELETE, urn.getEntityType()),
                      new EntitySpec(urn.getEntityType(), urn.toString()))) {
                    return Pair.of(changeTypePair, HttpStatus.SC_FORBIDDEN);
                  }
                  break;
                default:
                  return Pair.of(changeTypePair, HttpStatus.SC_BAD_REQUEST);
              }
              return Pair.of(changeTypePair, HttpStatus.SC_OK);
            })
        .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
  }

  public static boolean isAPIAuthorizedResult(
      @Nonnull final AuthorizationSession session, @Nonnull final SearchResult result) {
    return isAPIAuthorizedEntityUrns(
        session,
        READ,
        result.getEntities().stream().map(SearchEntity::getEntity).collect(Collectors.toList()));
  }

  public static boolean isAPIAuthorizedResult(
      @Nonnull final AuthorizationSession session, @Nonnull final ScrollResult result) {
    return isAPIAuthorizedEntityUrns(
        session,
        READ,
        result.getEntities().stream().map(SearchEntity::getEntity).collect(Collectors.toList()));
  }

  public static boolean isAPIAuthorizedResult(
      @Nonnull final AuthorizationSession session, @Nonnull final AutoCompleteResult result) {
    return isAPIAuthorizedEntityUrns(
        session,
        READ,
        result.getEntities().stream().map(AutoCompleteEntity::getUrn).collect(Collectors.toList()));
  }

  public static boolean isAPIAuthorizedResult(
      @Nonnull final AuthorizationSession session, @Nonnull final BrowseResult result) {
    return isAPIAuthorizedEntityUrns(
        session,
        READ,
        result.getEntities().stream().map(BrowseResultEntity::getUrn).collect(Collectors.toList()));
  }

  public static boolean isAPIAuthorizedUrns(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiGroup apiGroup,
      @Nonnull final ApiOperation apiOperation,
      @Nonnull final Collection<Urn> urns) {

    if (ApiGroup.ENTITY.equals(apiGroup)) {
      return isAPIAuthorizedEntityUrns(session, apiOperation, urns);
    }

    List<EntitySpec> resourceSpecs =
        urns.stream()
            .map(urn -> new EntitySpec(urn.getEntityType(), urn.toString()))
            .collect(Collectors.toList());

    return isAPIAuthorized(
        session, lookupAPIPrivilege(apiGroup, apiOperation, null), resourceSpecs);
  }

  public static boolean isAPIAuthorizedEntityUrns(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiOperation apiOperation,
      @Nonnull final Collection<Urn> urns) {

    Map<String, List<EntitySpec>> resourceSpecs =
        urns.stream()
            .map(urn -> new EntitySpec(urn.getEntityType(), urn.toString()))
            .collect(Collectors.groupingBy(EntitySpec::getType));

    return resourceSpecs.entrySet().stream()
        .allMatch(
            entry ->
                isAPIAuthorized(
                    session,
                    lookupAPIPrivilege(ENTITY, apiOperation, entry.getKey()),
                    entry.getValue()));
  }

  public static boolean isAPIAuthorizedEntityType(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiOperation apiOperation,
      @Nonnull final String entityType) {
    return isAPIAuthorizedEntityType(session, ENTITY, apiOperation, List.of(entityType));
  }

  /**
   * Maps an MCP {@link ChangeType} to the {@link ApiOperation} used to look up aspect-specific
   * privilege restrictions in {@link PoliciesConfig#RESTRICTED_ASPECT_PRIVILEGES}. CREATE_ENTITY is
   * treated as CREATE, DELETE as DELETE, and every other (upsert-like) change type as UPDATE.
   */
  @Nonnull
  public static ApiOperation toAspectApiOperation(@Nonnull final ChangeType changeType) {
    switch (changeType) {
      case CREATE_ENTITY:
        return CREATE;
      case DELETE:
        return DELETE;
      default:
        return UPDATE;
    }
  }

  /**
   * Case-insensitive view of {@link PoliciesConfig#RESTRICTED_ASPECT_PRIVILEGES}, keyed by
   * lower-cased entity type and lower-cased aspect name.
   *
   * <p>The OpenAPI/Rest.li layers resolve aspect names case-insensitively (see {@code
   * RequestInputUtil#lookupAspectSpec}), so an exact-match lookup here would let a caller bypass
   * the restriction entirely simply by requesting e.g. {@code upstreamlineage} instead of {@code
   * upstreamLineage}. Normalizing both sides closes that hole regardless of whether an individual
   * call site remembered to normalize its input first.
   */
  private static final Map<
          String,
          Map<String, Map<ApiOperation, Disjunctive<Conjunctive<PoliciesConfig.Privilege>>>>>
      NORMALIZED_RESTRICTED_ASPECT_PRIVILEGES =
          PoliciesConfig.RESTRICTED_ASPECT_PRIVILEGES.entrySet().stream()
              .collect(
                  Collectors.toMap(
                      entityEntry -> entityEntry.getKey().toLowerCase(Locale.ROOT),
                      entityEntry ->
                          entityEntry.getValue().entrySet().stream()
                              .collect(
                                  Collectors.toMap(
                                      aspectEntry -> aspectEntry.getKey().toLowerCase(Locale.ROOT),
                                      Map.Entry::getValue))));

  /**
   * Looks up the aspect-specific privilege restriction (if any) configured in {@link
   * PoliciesConfig#RESTRICTED_ASPECT_PRIVILEGES} for the given entity type / aspect name / api
   * operation triple. Entity type and aspect name are matched case-insensitively.
   *
   * <p>MANAGE is derived as the conjunction of READ, UPDATE and DELETE. The entity-level maps
   * ({@link #lookupEntityAPIPrivilege}) derive it from UPDATE and DELETE alone because there an
   * edit privilege already implies read; for a restricted aspect that implication is not assumed by
   * this mechanism, so READ is conjoined explicitly -- managing an aspect one is not allowed to see
   * would otherwise be possible. Whether that changes the actual set of privileges that satisfy
   * MANAGE depends on how the aspect's READ set is configured in {@link
   * PoliciesConfig#RESTRICTED_ASPECT_PRIVILEGES}: for `dataset`'s `upstreamLineage`, READ already
   * includes the write privileges (see that map's Javadoc), so the conjunction is not currently a
   * distinguishing factor for that particular aspect.
   */
  @Nullable
  private static Disjunctive<Conjunctive<PoliciesConfig.Privilege>> lookupRestrictedAspectPrivilege(
      @Nonnull final String entityType,
      @Nonnull final String aspectName,
      @Nonnull final ApiOperation apiOperation) {
    final Map<ApiOperation, Disjunctive<Conjunctive<PoliciesConfig.Privilege>>> aspectPrivileges =
        NORMALIZED_RESTRICTED_ASPECT_PRIVILEGES
            .getOrDefault(entityType.toLowerCase(Locale.ROOT), Map.of())
            .getOrDefault(aspectName.toLowerCase(Locale.ROOT), Map.of());

    if (ApiOperation.MANAGE.equals(apiOperation)) {
      // Conjoining with DENY_ACCESS (an empty disjunctive) yields an empty disjunctive, i.e. deny,
      // so a partially configured aspect denies MANAGE rather than requiring only the configured
      // operations.
      return Disjunctive.conjoin(
          aspectPrivileges.getOrDefault(READ, DENY_ACCESS),
          Disjunctive.conjoin(
              aspectPrivileges.getOrDefault(UPDATE, DENY_ACCESS),
              aspectPrivileges.getOrDefault(DELETE, DENY_ACCESS)));
    }

    return aspectPrivileges.get(apiOperation);
  }

  /**
   * Whether the given (entityType, aspectName) pair carries additional privilege restrictions
   * beyond the standard entity-level CRUD privileges, e.g. `dataset`'s `upstreamLineage` aspect,
   * whose privileges are configured in {@link PoliciesConfig#RESTRICTED_ASPECT_PRIVILEGES} to match
   * {@link ApiGroup#LINEAGE} operation for operation (plus VIEW_LINEAGE_PRIVILEGE as the dedicated,
   * narrowest read grant). Entity type and aspect name are matched case-insensitively.
   */
  public static boolean isRestrictedAspect(
      @Nonnull final String entityType, @Nonnull final String aspectName) {
    return NORMALIZED_RESTRICTED_ASPECT_PRIVILEGES
        .getOrDefault(entityType.toLowerCase(Locale.ROOT), Map.of())
        .containsKey(aspectName.toLowerCase(Locale.ROOT));
  }

  /**
   * Whether the given entity type carries any restricted aspects at all. Matched
   * case-insensitively.
   */
  public static boolean hasRestrictedAspects(@Nonnull final String entityType) {
    return NORMALIZED_RESTRICTED_ASPECT_PRIVILEGES.containsKey(entityType.toLowerCase(Locale.ROOT));
  }

  /**
   * Checks the aspect-specific privilege restriction (if any) for a single urn + aspect name + api
   * operation. Returns true for any aspect that carries no restriction, so callers that also need
   * the generic entity-level CRUD check must combine this with {@link #isAPIAuthorizedEntityUrns}
   * (or use {@link #isAPIAuthorizedEntityUrnsWithAspect}, which picks the right check).
   *
   * <p>For a restricted aspect these privileges are the <i>complete</i> requirement -- see {@link
   * PoliciesConfig#RESTRICTED_ASPECT_PRIVILEGES}. READ is governed exclusively by the aspect's own
   * configured read privilege(s), independent of its write privilege(s) -- the mechanism does not
   * assume an EDIT_* privilege implies READ the way the usual entity-level pattern does. Whether
   * that produces a distinct outcome from write access depends entirely on what is configured for a
   * given aspect: `dataset`'s `upstreamLineage`, for instance, includes EDIT_ENTITY_PRIVILEGE and
   * EDIT_LINEAGE_PRIVILEGE in both its read and write sets (mirroring {@link ApiGroup#LINEAGE}), so
   * for that aspect holding either write privilege does also grant read.
   */
  public static boolean isAPIAuthorizedAspect(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiOperation apiOperation,
      @Nonnull final Urn urn,
      @Nonnull final String aspectName) {
    if (!isRestrictedAspect(urn.getEntityType(), aspectName)) {
      return true;
    }
    final Disjunctive<Conjunctive<PoliciesConfig.Privilege>> restrictedPrivileges =
        lookupRestrictedAspectPrivilege(urn.getEntityType(), aspectName, apiOperation);
    if (restrictedPrivileges == null) {
      // Aspect is restricted but no privileges are configured for this specific operation.
      return false;
    }
    return isAPIAuthorized(
        session, restrictedPrivileges, new EntitySpec(urn.getEntityType(), urn.toString()));
  }

  /**
   * Authorization check for a single urn and a single, explicitly named aspect. Use this for
   * OpenAPI/Rest.li endpoints that get, create, patch, or delete one named aspect (e.g. `GET
   * /openapi/v3/entity/dataset/{urn}/upstreamLineage`).
   *
   * <p>A restricted aspect is governed by its own configured privileges <i>instead of</i> the
   * entity-level ones, so that a privilege scoped to the aspect is sufficient on its own -- e.g.
   * EDIT_LINEAGE_PRIVILEGE alone can write `upstreamLineage` without EDIT_ENTITY_PRIVILEGE, and
   * VIEW_LINEAGE_PRIVILEGE alone can read it. Every other aspect falls back to the entity-level
   * check, unchanged.
   */
  public static boolean isAPIAuthorizedEntityUrnsWithAspect(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiOperation apiOperation,
      @Nonnull final Urn urn,
      @Nonnull final String aspectName) {
    if (isRestrictedAspect(urn.getEntityType(), aspectName)) {
      return isAPIAuthorizedAspect(session, apiOperation, urn, aspectName);
    }
    return isAPIAuthorizedEntityUrns(session, apiOperation, List.of(urn));
  }

  /**
   * Filters a collection of aspect names down to those the caller is authorized to access for the
   * given urn and api operation, per any configured aspect-specific privilege restrictions.
   * Intended for endpoints that project/return multiple aspects at once (e.g. "get entity" with no
   * explicit aspect list, or search/scroll responses that embed aspects) so that restricted aspects
   * are silently excluded from the response rather than failing the whole request.
   *
   * <p><b>Callers must handle an empty result explicitly.</b> Several {@code EntityService} read
   * methods interpret an empty aspect-name set as "all aspects", so passing an empty projection
   * straight through would return <i>more</i> than was asked for -- including the very aspects that
   * were just filtered out. Use {@link #isProjectionDenied} to detect that case.
   */
  public static Set<String> filterAuthorizedAspects(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiOperation apiOperation,
      @Nonnull final Urn urn,
      @Nonnull final Collection<String> aspectNames) {
    return aspectNames.stream()
        .filter(aspectName -> isAPIAuthorizedAspect(session, apiOperation, urn, aspectName))
        .collect(Collectors.toSet());
  }

  /**
   * The restricted aspects a caller <i>explicitly named</i> in a read request but is not authorized
   * to READ. Returns an empty set for a null/empty request, which conventionally means "all
   * aspects" -- a wildcard is not an explicit ask for anything in particular.
   *
   * <p>This is the counterpart to {@link #filterAuthorizedAspects}, and the two split the
   * projection problem along the only line that is safe for both sides:
   *
   * <ul>
   *   <li><b>Explicitly named</b> aspects must fail the request (403). Dropping them silently is
   *       indistinguishable, to the client, from the aspect simply not being set -- a caller that
   *       asks for `upstreamLineage` and gets a response without it would reasonably conclude the
   *       dataset has no upstream lineage.
   *   <li><b>Wildcard</b> projections drop restricted aspects silently, because failing them would
   *       make the read privilege mandatory for every plain "get entity" call, breaking readers
   *       that never wanted the restricted aspect in the first place.
   * </ul>
   *
   * <p>Returned sorted so error messages are deterministic.
   */
  public static Set<String> unauthorizedRequestedAspects(
      @Nonnull final AuthorizationSession session,
      @Nonnull final Urn urn,
      @Nullable final Collection<String> requestedAspectNames) {
    if (requestedAspectNames == null || requestedAspectNames.isEmpty()) {
      return Set.of();
    }
    return requestedAspectNames.stream()
        .filter(aspectName -> !isAPIAuthorizedAspect(session, READ, urn, aspectName))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  /**
   * Same as {@link #unauthorizedRequestedAspects(AuthorizationSession, Urn, Collection)} across
   * several urns, for batch endpoints. An aspect is reported if any one of the urns denies it.
   */
  public static Set<String> unauthorizedRequestedAspects(
      @Nonnull final AuthorizationSession session,
      @Nonnull final Collection<Urn> urns,
      @Nullable final Collection<String> requestedAspectNames) {
    return urns.stream()
        .flatMap(urn -> unauthorizedRequestedAspects(session, urn, requestedAspectNames).stream())
        .collect(Collectors.toCollection(TreeSet::new));
  }

  /**
   * Computes the effective, authorized set of aspect names to project for a given urn given a
   * requested set of aspect names (empty/null conventionally meaning "all aspects" for the entity
   * type in most entity/aspect projection APIs). Any requested aspect subject to a restriction (see
   * {@link PoliciesConfig#RESTRICTED_ASPECT_PRIVILEGES}) that the caller isn't authorized to READ
   * is excluded from the result. When the entity type carries no restricted aspects at all, the
   * requested set is returned unchanged (including empty, to preserve "all aspects" semantics
   * downstream).
   *
   * <p>As with {@link #filterAuthorizedAspects}, an empty result for a non-empty request means
   * "nothing may be returned", not "return everything" -- see {@link #isProjectionDenied}.
   */
  public static Set<String> filterAuthorizedProjectedAspects(
      @Nonnull final AuthorizationSession session,
      @Nonnull final EntityRegistry entityRegistry,
      @Nonnull final Urn urn,
      @Nullable final Collection<String> requestedAspectNames) {
    if (!hasRestrictedAspects(urn.getEntityType())) {
      return requestedAspectNames == null ? Set.of() : new HashSet<>(requestedAspectNames);
    }

    final Collection<String> effectiveRequested =
        (requestedAspectNames == null || requestedAspectNames.isEmpty())
            ? entityRegistry.getEntitySpec(urn.getEntityType()).getAspectSpecs().stream()
                .map(AspectSpec::getName)
                .collect(Collectors.toSet())
            : requestedAspectNames;
    return filterAuthorizedAspects(session, READ, urn, effectiveRequested);
  }

  /**
   * Whether an authorized aspect projection has collapsed to "nothing may be returned".
   *
   * <p>{@code EntityService} read methods treat an empty aspect-name set as "fetch every aspect",
   * so a non-empty request whose authorized projection came back empty must never be forwarded to
   * the service -- doing so would return the whole entity, restricted aspects included. Callers
   * should respond with a 403 (or an empty payload) instead.
   *
   * @param requestedAspectNames what the caller asked for (empty/null = "all aspects")
   * @param authorizedAspectNames the result of {@link #filterAuthorizedAspects} / {@link
   *     #filterAuthorizedProjectedAspects}
   */
  public static boolean isProjectionDenied(
      @Nullable final Collection<String> requestedAspectNames,
      @Nonnull final Collection<String> authorizedAspectNames) {
    return requestedAspectNames != null
        && !requestedAspectNames.isEmpty()
        && authorizedAspectNames.isEmpty();
  }

  public static boolean isAPIAuthorizedEntityType(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiGroup apiGroup,
      @Nonnull final ApiOperation apiOperation,
      @Nonnull final String entityType) {
    return isAPIAuthorizedEntityType(session, apiGroup, apiOperation, List.of(entityType));
  }

  public static boolean isAPIAuthorizedEntityType(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiOperation apiOperation,
      @Nonnull final Collection<String> entityTypes) {

    return isAPIAuthorizedEntityType(session, ENTITY, apiOperation, entityTypes);
  }

  public static boolean isAPIAuthorizedEntityType(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiGroup apiGroup,
      @Nonnull final ApiOperation apiOperation,
      @Nonnull final Collection<String> entityTypes) {

    return entityTypes.stream()
        .distinct()
        .allMatch(
            entityType ->
                isAPIAuthorized(
                    session,
                    lookupAPIPrivilege(apiGroup, apiOperation, entityType),
                    new EntitySpec(entityType, "")));
  }

  public static boolean isAPIAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiGroup apiGroup,
      @Nonnull final ApiOperation apiOperation) {
    return isAPIAuthorized(
        session, lookupAPIPrivilege(apiGroup, apiOperation, null), (EntitySpec) null);
  }

  public static boolean isAPIAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final PoliciesConfig.Privilege privilege,
      @Nullable final EntitySpec resource) {
    return isAPIAuthorized(session, Disjunctive.disjoint(privilege), resource);
  }

  public static boolean isAPIAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final PoliciesConfig.Privilege privilege) {
    return isAPIAuthorized(session, Disjunctive.disjoint(privilege), (EntitySpec) null);
  }

  /**
   * Allow specific privilege OR MANAGE_SYSTEM_OPERATIONS_PRIVILEGE
   *
   * @param session authorization session
   * @param privilege specific privilege
   * @return authorized status
   */
  public static boolean isAPIOperationsAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final PoliciesConfig.Privilege privilege) {
    return isAPIAuthorized(
        session,
        Disjunctive.disjoint(privilege, MANAGE_SYSTEM_OPERATIONS_PRIVILEGE),
        (EntitySpec) null);
  }

  public static boolean isAPIOperationsAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final PoliciesConfig.Privilege privilege,
      @Nullable final EntitySpec resource) {
    return isAPIAuthorized(
        session, Disjunctive.disjoint(privilege, MANAGE_SYSTEM_OPERATIONS_PRIVILEGE), resource);
  }

  private static boolean isAPIAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final Disjunctive<Conjunctive<PoliciesConfig.Privilege>> privileges,
      @Nullable final EntitySpec resource) {
    return isAPIAuthorized(session, privileges, resource != null ? List.of(resource) : List.of());
  }

  private static boolean isAPIAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final Disjunctive<Conjunctive<PoliciesConfig.Privilege>> privileges,
      @Nonnull final Collection<EntitySpec> resources) {
    if (AuthUtil.isRestApiAuthorizationEnabled) {
      return isAuthorized(session, buildDisjunctivePrivilegeGroup(privileges), resources);
    } else {
      return true;
    }
  }

  /** GraphQL Methods */
  public static boolean canViewEntity(
      @Nonnull final AuthorizationSession session, @Nonnull Urn urn) {
    return canViewEntity(session, List.of(urn));
  }

  public static boolean canViewEntity(
      @Nonnull final AuthorizationSession session, @Nonnull final Collection<Urn> urns) {

    return isAuthorizedEntityUrns(session, READ, urns);
  }

  public static boolean isAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiGroup apiGroup,
      @Nonnull final ApiOperation apiOperation) {
    return isAuthorized(session, lookupAPIPrivilege(apiGroup, apiOperation, null), null);
  }

  public static boolean isAuthorizedEntityType(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiOperation apiOperation,
      @Nonnull final Collection<String> entityTypes) {

    return entityTypes.stream()
        .distinct()
        .allMatch(
            entityType ->
                isAuthorized(
                    session,
                    lookupEntityAPIPrivilege(apiOperation, entityType),
                    new EntitySpec(entityType, "")));
  }

  public static boolean isAuthorizedEntityUrns(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiOperation apiOperation,
      @Nonnull final Collection<Urn> urns) {
    return isAuthorizedUrns(session, ENTITY, apiOperation, urns);
  }

  public static boolean isAuthorizedUrns(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ApiGroup apiGroup,
      @Nonnull final ApiOperation apiOperation,
      @Nonnull final Collection<Urn> urns) {

    Map<String, List<EntitySpec>> resourceSpecs =
        urns.stream()
            .map(urn -> new EntitySpec(urn.getEntityType(), urn.toString()))
            .collect(Collectors.groupingBy(EntitySpec::getType));

    return resourceSpecs.entrySet().stream()
        .allMatch(
            entry -> {
              Disjunctive<Conjunctive<PoliciesConfig.Privilege>> privileges =
                  lookupAPIPrivilege(apiGroup, apiOperation, entry.getKey());
              return entry.getValue().stream()
                  .allMatch(entitySpec -> isAuthorized(session, privileges, entitySpec));
            });
  }

  public static boolean isAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final PoliciesConfig.Privilege privilege) {
    return isAuthorized(
        session,
        buildDisjunctivePrivilegeGroup(Disjunctive.disjoint(privilege)),
        (EntitySpec) null);
  }

  public static boolean isAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final PoliciesConfig.Privilege privilege,
      @Nullable final EntitySpec entitySpec) {
    return isAuthorized(
        session, buildDisjunctivePrivilegeGroup(Disjunctive.disjoint(privilege)), entitySpec);
  }

  private static boolean isAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final Disjunctive<Conjunctive<PoliciesConfig.Privilege>> privileges,
      @Nullable EntitySpec maybeResourceSpec) {
    return isAuthorized(session, buildDisjunctivePrivilegeGroup(privileges), maybeResourceSpec);
  }

  public static boolean isAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final DisjunctivePrivilegeGroup privilegeGroup,
      @Nullable final EntitySpec resourceSpec) {

    for (ConjunctivePrivilegeGroup conjunctive : privilegeGroup.getAuthorizedPrivilegeGroups()) {
      if (isAuthorized(session, conjunctive, resourceSpec)) {
        return true;
      }
    }

    return false;
  }

  private static boolean isAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final ConjunctivePrivilegeGroup requiredPrivileges,
      @Nullable final EntitySpec resourceSpec) {

    // if no privileges are required, deny
    if (requiredPrivileges.getRequiredPrivileges().isEmpty()) {
      return false;
    }

    // Each privilege in a group _must_ all be true to permit the operation.
    for (final String privilege : requiredPrivileges.getRequiredPrivileges()) {
      // Create and evaluate an Authorization request.
      if (isDenied(session, privilege, resourceSpec)) {
        // Short circuit.
        return false;
      }
    }
    return true;
  }

  private static boolean isAuthorized(
      @Nonnull final AuthorizationSession session,
      @Nonnull final DisjunctivePrivilegeGroup privilegeGroup,
      @Nonnull final Collection<EntitySpec> resourceSpecs) {

    if (resourceSpecs.isEmpty()) {
      return isAuthorized(session, privilegeGroup, (EntitySpec) null);
    }

    return resourceSpecs.stream().allMatch(spec -> isAuthorized(session, privilegeGroup, spec));
  }

  /** Common Methods */

  /**
   * Based on an API group and operation return privileges. Broad level privileges that are not
   * specific to an Entity/Aspect.
   *
   * @param apiGroup
   * @param apiOperation
   * @return
   */
  public static Disjunctive<Conjunctive<PoliciesConfig.Privilege>> lookupAPIPrivilege(
      @Nonnull ApiGroup apiGroup, @Nonnull ApiOperation apiOperation, @Nullable String entityType) {

    if (ApiGroup.ENTITY.equals(apiGroup) && entityType != null) {
      return lookupEntityAPIPrivilege(apiOperation, Set.of(entityType)).get(entityType);
    }

    Map<ApiOperation, Disjunctive<Conjunctive<PoliciesConfig.Privilege>>> privMap =
        API_PRIVILEGE_MAP.getOrDefault(apiGroup, Map.of());

    switch (apiOperation) {
        // Manage is a conjunction of UPDATE and DELETE
      case MANAGE:
        return Disjunctive.conjoin(
            privMap.getOrDefault(ApiOperation.UPDATE, DENY_ACCESS),
            privMap.getOrDefault(ApiOperation.DELETE, DENY_ACCESS));
      default:
        return privMap.getOrDefault(apiOperation, DENY_ACCESS);
    }
  }

  /**
   * Returns map of entityType to privileges required for that entity
   *
   * @param apiOperation
   * @param entityTypes
   * @return
   */
  @VisibleForTesting
  static Map<String, Disjunctive<Conjunctive<PoliciesConfig.Privilege>>> lookupEntityAPIPrivilege(
      @Nonnull ApiOperation apiOperation, @Nonnull Collection<String> entityTypes) {

    return entityTypes.stream()
        .distinct()
        .map(
            entityType -> {

              // Check entity specific privilege map, otherwise default to generic entity
              Map<ApiOperation, Disjunctive<Conjunctive<PoliciesConfig.Privilege>>> privMap =
                  API_ENTITY_PRIVILEGE_MAP.getOrDefault(
                      entityType, API_PRIVILEGE_MAP.getOrDefault(ApiGroup.ENTITY, Map.of()));

              switch (apiOperation) {
                  // Manage is a conjunction of UPDATE and DELETE
                case MANAGE:
                  return Pair.of(
                      entityType,
                      Disjunctive.conjoin(
                          privMap.getOrDefault(ApiOperation.UPDATE, DENY_ACCESS),
                          privMap.getOrDefault(ApiOperation.DELETE, DENY_ACCESS)));
                default:
                  // otherwise default to generic entity
                  return Pair.of(entityType, privMap.getOrDefault(apiOperation, DENY_ACCESS));
              }
            })
        .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
  }

  @VisibleForTesting
  static Disjunctive<Conjunctive<PoliciesConfig.Privilege>> lookupEntityAPIPrivilege(
      @Nonnull ApiOperation apiOperation, @Nonnull String entityType) {
    return lookupEntityAPIPrivilege(apiOperation, Set.of(entityType)).get(entityType);
  }

  public static DisjunctivePrivilegeGroup buildDisjunctivePrivilegeGroup(
      @Nonnull final ApiGroup apiGroup,
      @Nonnull final ApiOperation apiOperation,
      @Nullable final String entityType) {
    return buildDisjunctivePrivilegeGroup(lookupAPIPrivilege(apiGroup, apiOperation, entityType));
  }

  public static DisjunctivePrivilegeGroup buildDisjunctivePrivilegeGroup(
      final Disjunctive<Conjunctive<PoliciesConfig.Privilege>> privileges) {
    return new DisjunctivePrivilegeGroup(
        privileges.stream()
            .map(
                priv ->
                    new ConjunctivePrivilegeGroup(
                        priv.stream()
                            .map(PoliciesConfig.Privilege::getType)
                            .collect(Collectors.toList())))
            .collect(Collectors.toList()));
  }

  private static boolean isDenied(
      @Nonnull final AuthorizationSession session,
      @Nonnull final String privilege,
      @Nullable final EntitySpec resourceSpec) {
    // Create and evaluate an Authorization request.
    final AuthorizationResult result = session.authorize(privilege, resourceSpec);
    return AuthorizationResult.Type.DENY.equals(result.getType());
  }

  protected AuthUtil() {}
}
