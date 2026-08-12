package com.datahub.authorization.fieldresolverprovider;

import static com.linkedin.metadata.search.utils.QueryUtils.EMPTY_FILTER;

import com.datahub.authorization.EntityFieldType;
import com.datahub.authorization.EntitySpec;
import com.datahub.authorization.FieldResolver;
import com.linkedin.common.Owner;
import com.linkedin.common.Ownership;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.entity.EntityResponse;
import com.linkedin.entity.EnvelopedAspect;
import com.linkedin.entity.client.SystemEntityClient;
import com.linkedin.metadata.Constants;
import com.linkedin.metadata.aspect.GraphRetriever;
import com.linkedin.metadata.aspect.models.graph.RelatedEntities;
import com.linkedin.metadata.aspect.models.graph.RelatedEntitiesScrollResult;
import com.linkedin.metadata.query.filter.RelationshipDirection;
import com.linkedin.metadata.search.utils.QueryUtils;
import io.datahubproject.metadata.context.OperationContext;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the owners of the data product(s) that contain the entity as an asset.
 *
 * <p>The reverse graph lookup and the ownership fetch are performed together here rather than
 * exposing the data product urns as a field of their own: a caller that only got the urns would
 * have to resolve each data product as a separate entity spec, defeating the per-field memoization
 * on {@link FieldResolver} and issuing one ownership request per product. Doing both steps in one
 * resolver keeps the whole chain behind a single cached future and lets the ownership aspects be
 * fetched in one batch.
 */
@Slf4j
@RequiredArgsConstructor
public class DataProductOwnerFieldResolverProvider implements EntityFieldResolverProvider {

  private static final String DATA_PRODUCT_CONTAINS_RELATIONSHIP = "DataProductContains";

  /**
   * An entity belongs to a handful of data products at most, so a single page all but always
   * suffices; the scroll loop below is retained for correctness on pathological data.
   */
  private static final int RELATIONSHIP_SCROLL_COUNT = 100;

  private final SystemEntityClient _entityClient;

  @Override
  public List<EntityFieldType> getFieldTypes() {
    return Collections.singletonList(EntityFieldType.DATA_PRODUCT_OWNER);
  }

  @Override
  public FieldResolver getFieldResolver(
      @Nonnull OperationContext opContext, EntitySpec entitySpec) {
    return FieldResolver.getResolverFromFunction(
        entitySpec, spec -> getDataProductOwners(opContext, spec));
  }

  private FieldResolver.FieldValue getDataProductOwners(
      @Nonnull OperationContext opContext, EntitySpec entitySpec) {
    try {
      if (entitySpec.getEntity().isEmpty()) {
        return FieldResolver.emptyFieldValue();
      }
      final Urn entityUrn = UrnUtils.getUrn(entitySpec.getEntity());

      final Set<Urn> dataProductUrns = fetchContainingDataProducts(opContext, entityUrn);
      if (dataProductUrns.isEmpty()) {
        return FieldResolver.emptyFieldValue();
      }

      final Set<Owner> owners = fetchOwners(opContext, dataProductUrns);
      if (owners.isEmpty()) {
        return FieldResolver.emptyFieldValue();
      }

      return FieldResolver.FieldValue.builder()
          .values(
              owners.stream()
                  .map(owner -> owner.getOwner().toString())
                  .collect(Collectors.toUnmodifiableSet()))
          .typedValues(owners)
          .build();
    } catch (Exception e) {
      log.error("Error while retrieving data product owners for entitySpec {}", entitySpec, e);
      return FieldResolver.emptyFieldValue();
    }
  }

  /** Walks {@code DataProductContains} incoming to the entity: source is the data product. */
  @Nonnull
  private Set<Urn> fetchContainingDataProducts(
      @Nonnull OperationContext opContext, @Nonnull Urn entityUrn) {
    final GraphRetriever graphRetriever = opContext.getRetrieverContext().getGraphRetriever();
    final Set<Urn> dataProductUrns = new HashSet<>();

    String scrollId = null;
    do {
      final RelatedEntitiesScrollResult scroll =
          graphRetriever.scrollRelatedEntities(
              Collections.singleton(Constants.DATA_PRODUCT_ENTITY_NAME),
              EMPTY_FILTER,
              null,
              QueryUtils.newFilter("urn", entityUrn.toString()),
              Collections.singleton(DATA_PRODUCT_CONTAINS_RELATIONSHIP),
              QueryUtils.newRelationshipFilter(EMPTY_FILTER, RelationshipDirection.INCOMING),
              Collections.emptyList(),
              scrollId,
              RELATIONSHIP_SCROLL_COUNT,
              null,
              null);

      for (RelatedEntities related : scroll.getEntities()) {
        final Urn sourceUrn = UrnUtils.getUrn(related.getSourceUrn());
        // Guard against a malformed edge whose source is not actually a data product; granting on
        // one would let an unrelated entity's owners inherit the privilege.
        if (!Constants.DATA_PRODUCT_ENTITY_NAME.equals(sourceUrn.getEntityType())) {
          log.warn(
              "Skipping non-data-product source urn {} on {} relationship",
              sourceUrn,
              DATA_PRODUCT_CONTAINS_RELATIONSHIP);
          continue;
        }
        dataProductUrns.add(sourceUrn);
      }

      scrollId = scroll.getEntities().isEmpty() ? null : scroll.getScrollId();
    } while (scrollId != null);

    return dataProductUrns;
  }

  @Nonnull
  private Set<Owner> fetchOwners(
      @Nonnull OperationContext opContext, @Nonnull Set<Urn> dataProductUrns) throws Exception {
    final Map<Urn, EntityResponse> responses =
        _entityClient.batchGetV2(
            opContext,
            Constants.DATA_PRODUCT_ENTITY_NAME,
            dataProductUrns,
            Collections.singleton(Constants.OWNERSHIP_ASPECT_NAME),
            false);

    final Set<Owner> owners = new HashSet<>();
    for (EntityResponse response : responses.values()) {
      if (response == null || !response.getAspects().containsKey(Constants.OWNERSHIP_ASPECT_NAME)) {
        continue;
      }
      final EnvelopedAspect ownershipAspect =
          response.getAspects().get(Constants.OWNERSHIP_ASPECT_NAME);
      owners.addAll(new Ownership(ownershipAspect.getValue().data()).getOwners());
    }
    return owners;
  }
}
