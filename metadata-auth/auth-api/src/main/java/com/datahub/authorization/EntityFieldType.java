package com.datahub.authorization;

/** List of entity field types to fetch for a given entity */
public enum EntityFieldType {

  /**
   * Type of the entity (e.g. dataset, chart)
   *
   * @deprecated
   */
  @Deprecated
  RESOURCE_URN,
  /**
   * Urn of the entity
   *
   * @deprecated
   */
  @Deprecated
  RESOURCE_TYPE,
  /** Type of the entity (e.g. dataset, chart) */
  TYPE,
  /** Urn of the entity */
  URN,
  /** Owners of the entity */
  OWNER,
  /** Domains of the entity */
  DOMAIN,
  /** Groups of which the entity (only applies to corpUser) is a member */
  GROUP_MEMBERSHIP,
  /** Data platform instance of resource */
  DATA_PLATFORM_INSTANCE,
  /** Tags of the entity */
  TAG,
  /** Container of the entity */
  CONTAINER,
  /** Glossary terms/nodes associated with the entity */
  GLOSSARY,
  /**
   * Owners of the data product(s) that contain the entity as an asset.
   *
   * <p>Resolved by a single provider that performs the reverse graph lookup and the ownership fetch
   * together, so consumers never have to resolve the intermediate data product entities themselves.
   */
  DATA_PRODUCT_OWNER,
}
