package com.linkedin.metadata.aspect.patch.template;

import static com.linkedin.metadata.aspect.patch.template.TemplateUtil.OBJECT_MAPPER;
import static com.linkedin.metadata.aspect.patch.template.TemplateUtil.populateTopLevelKeys;

import com.datahub.util.RecordUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.linkedin.data.template.RecordTemplate;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonPatch;
import jakarta.json.JsonValue;
import java.io.StringReader;

public abstract class CompoundKeyTemplate<T extends RecordTemplate>
    implements ArrayMergingTemplate<T> {

  @Override
  public T applyPatch(RecordTemplate recordTemplate, JsonPatch jsonPatch)
      throws JsonProcessingException {
    validateMoveOperations(jsonPatch);
    JsonNode transformed = populateTopLevelKeys(preprocessTemplate(recordTemplate), jsonPatch);
    JsonObject patched =
        jsonPatch.apply(
            Json.createReader(new StringReader(OBJECT_MAPPER.writeValueAsString(transformed)))
                .readObject());
    JsonNode postProcessed = rebaseFields(OBJECT_MAPPER.readTree(patched.toString()));
    return RecordUtils.toRecordTemplate(getTemplateType(), postProcessed.toString());
  }

  /**
   * The underlying JSON Patch engine (org.eclipse.parsson) rejects a 'move' operation whenever
   * 'path' literally starts with the 'from' string, even when the two refer to unrelated sibling
   * keys (e.g. moving "/a/foo" to "/a/foo2") rather than an actual parent/child relationship. It
   * compares raw strings instead of JSON Pointer path segments. Reject this case up front with a
   * clear message instead of letting the confusing raw exception surface.
   */
  private void validateMoveOperations(JsonPatch jsonPatch) {
    for (JsonValue opValue : jsonPatch.toJsonArray()) {
      JsonObject op = opValue.asJsonObject();
      if (!"move".equals(op.getString("op", null))) {
        continue;
      }
      String from = op.getString("from", null);
      String path = op.getString("path", null);
      if (from != null && path != null && path.startsWith(from) && from.length() < path.length()) {
        throw new IllegalArgumentException(
            String.format(
                "Unsupported 'move' patch operation: 'path' (%s) begins with the same characters"
                    + " as 'from' (%s). Choose a target key name that does not start with the"
                    + " source key name, or use 'copy' followed by 'remove' instead.",
                path, from));
      }
    }
  }
}
