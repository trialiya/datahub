package com.linkedin.datahub.graphql.resolvers.post;

import com.datahub.authentication.Authentication;
import com.datahub.authentication.post.PostService;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.authorization.AuthorizationUtils;
import com.linkedin.datahub.graphql.concurrency.GraphQLConcurrencyUtils;
import com.linkedin.datahub.graphql.exception.AuthorizationException;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class DeletePostResolver implements DataFetcher<CompletableFuture<Boolean>> {
  private final PostService _postService;

  @Override
  public CompletableFuture<Boolean> get(final DataFetchingEnvironment environment)
      throws Exception {
    final QueryContext context = environment.getContext();

    final Urn postUrn = UrnUtils.getUrn(environment.getArgument("urn"));
    final Authentication authentication = context.getAuthentication();

    // Notes attached to a specific entity are gated by the entity-scoped EDIT_ENTITY_NOTES
    // privilege, while home page announcements continue to require the global announcement
    // privileges.
    final Urn targetUrn = _postService.getPostTarget(context.getOperationContext(), postUrn);
    if (targetUrn != null) {
      if (!AuthorizationUtils.canEditEntityNotes(targetUrn, context)) {
        throw new AuthorizationException(
            "Unauthorized to delete notes for this entity. Please contact your DataHub administrator if this needs corrective action.");
      }
    } else if (!AuthorizationUtils.canManageGlobalAnnouncements(context)) {
      throw new AuthorizationException(
          "Unauthorized to delete posts. Please contact your DataHub administrator if this needs corrective action.");
    }

    return GraphQLConcurrencyUtils.supplyAsync(
        () -> {
          try {
            return _postService.deletePost(context.getOperationContext(), postUrn);
          } catch (Exception e) {
            throw new RuntimeException("Failed to create a new post", e);
          }
        },
        this.getClass().getSimpleName(),
        "get");
  }
}
