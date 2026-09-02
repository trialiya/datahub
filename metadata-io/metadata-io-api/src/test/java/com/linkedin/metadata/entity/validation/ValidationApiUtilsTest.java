package com.linkedin.metadata.entity.validation;

import static org.testng.Assert.*;

import com.linkedin.data.ByteString;
import com.linkedin.data.DataList;
import com.linkedin.data.DataMap;
import com.linkedin.dataset.UpstreamLineage;
import com.linkedin.metadata.utils.GenericRecordUtils;
import com.linkedin.schema.SchemaMetadata;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;

public class ValidationApiUtilsTest {

  @Test
  public void testNormalizedEqual_IgnoresIntegerLongMismatchForAuditStampTimeDefaults() {
    SchemaMetadata oldAspect = createSchemaMetadataWithAuditStampDefaults();
    SchemaMetadata newAspect = createSchemaMetadataWithAuditStampDefaults();

    ((DataMap) oldAspect.data().get("created")).put("time", Integer.valueOf(0));
    ((DataMap) oldAspect.data().get("lastModified")).put("time", Integer.valueOf(0));
    newAspect.getCreated().setTime(0L);
    newAspect.getLastModified().setTime(0L);

    assertTrue(ValidationApiUtils.normalizedEqual(oldAspect, newAspect));
  }

  @Test
  public void testNormalizedEqual_IgnoresIntegerLongMismatchForNestedUpstreamAuditStampDefaults() {
    UpstreamLineage oldAspect = createUpstreamLineageWithAuditStampDefaults();
    UpstreamLineage newAspect = createUpstreamLineageWithAuditStampDefaults();

    setUpstreamAuditStampTimes(oldAspect.data(), Integer.valueOf(0));
    setUpstreamAuditStampTimes(newAspect.data(), Long.valueOf(0));

    assertTrue(ValidationApiUtils.normalizedEqual(oldAspect, newAspect));
  }

  @Test
  public void testNormalizedEqual_IgnoresIntegerLongMismatchForSchemaMetadataVersion() {
    SchemaMetadata oldAspect = createSchemaMetadataWithAuditStampDefaults();
    SchemaMetadata newAspect = createSchemaMetadataWithAuditStampDefaults();

    oldAspect.data().put("version", Integer.valueOf(0));
    newAspect.data().put("version", Long.valueOf(0));

    assertTrue(ValidationApiUtils.normalizedEqual(oldAspect, newAspect));
  }

  private static SchemaMetadata createSchemaMetadataWithAuditStampDefaults() {
    String rawSchemaMetadataString =
        "{\"platformSchema\":{\"com.linkedin.schema.KafkaSchema\":{\"documentSchemaType\":\"AVRO\",\"documentSchema\":\"{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"SampleHiveSchema\\\",\\\"namespace\\\":\\\"com.linkedin.dataset\\\",\\\"doc\\\":\\\"Sample Hive dataset\\\",\\\"fields\\\":[{\\\"name\\\":\\\"field_foo\\\",\\\"type\\\":[\\\"string\\\"]}]\"}},\"created\":{\"actor\":\"urn:li:corpuser:jdoe\",\"time\":0},\"lastModified\":{\"actor\":\"urn:li:corpuser:jdoe\",\"time\":0},\"fields\":[{\"nullable\":false,\"fieldPath\":\"user_id\",\"description\":\"Id of the user created\",\"isPartOfKey\":false,\"type\":{\"type\":{\"com.linkedin.schema.BooleanType\":{}}},\"recursive\":false,\"nativeDataType\":\"varchar(100)\"}],\"schemaName\":\"SampleHiveSchema\",\"version\":0,\"hash\":\"\",\"platform\":\"urn:li:dataPlatform:hive\"}";
    return GenericRecordUtils.deserializeAspect(
        ByteString.copyString(rawSchemaMetadataString, StandardCharsets.UTF_8),
        "application/json",
        SchemaMetadata.class);
  }

  private static UpstreamLineage createUpstreamLineageWithAuditStampDefaults() {
    String rawUpstreamLineageString =
        "{\"upstreams\":[{\"type\":\"TRANSFORMED\",\"auditStamp\":{\"actor\":\"urn:li:corpuser:unknown\",\"time\":0},\"dataset\":\"urn:li:dataset:(urn:li:dataPlatform:test,example.upstream_one,PROD)\"},{\"type\":\"TRANSFORMED\",\"auditStamp\":{\"actor\":\"urn:li:corpuser:unknown\",\"time\":0},\"dataset\":\"urn:li:dataset:(urn:li:dataPlatform:test,example.upstream_two,PROD)\"}]}";
    return GenericRecordUtils.deserializeAspect(
        ByteString.copyString(rawUpstreamLineageString, StandardCharsets.UTF_8),
        "application/json",
        UpstreamLineage.class);
  }

  private static void setUpstreamAuditStampTimes(DataMap aspectData, Number timeValue) {
    DataList upstreams = (DataList) aspectData.get("upstreams");
    for (Object upstream : upstreams) {
      DataMap upstreamMap = (DataMap) upstream;
      DataMap auditStamp = (DataMap) upstreamMap.get("auditStamp");
      auditStamp.put("time", timeValue);
    }
  }
}
