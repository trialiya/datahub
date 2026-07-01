package com.datahub.graphql;

import com.linkedin.datahub.graphql.concurrency.GraphQLConcurrencyUtils;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The GraphiQL interactive query UI is now a self-contained static page served by datahub-frontend at
 * {@code /graphiql} (assets are self-hosted under {@code /assets/graphiql}, so it works under a strict
 * Content-Security-Policy without relying on a third-party CDN). This controller keeps the legacy
 * {@code /api/graphiql} URL working by redirecting to the new location.
 *
 * <p>The redirect target is relative ({@code ../graphiql}) so the browser resolves it against the page
 * URL — this is correct whether DataHub is served at the root or under a base path, without GMS needing
 * to know the base path.
 */
@Controller
@ConditionalOnProperty(
    name = "graphql.graphiql.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class GraphiQLController {

  // Relative so the browser resolves it against the request URL, which is base-path correct.
  private static final URI REDIRECT_LOCATION = URI.create("../graphiql");

  @GetMapping(value = "/api/graphiql")
  CompletableFuture<ResponseEntity<Void>> graphiQL() {
    return GraphQLConcurrencyUtils.supplyAsync(
        () -> ResponseEntity.status(HttpStatus.FOUND).location(REDIRECT_LOCATION).build(),
        this.getClass().getSimpleName(),
        "graphiQL");
  }
}
