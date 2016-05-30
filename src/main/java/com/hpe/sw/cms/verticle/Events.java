package com.hpe.sw.cms.verticle;

/**
 * Created by keke on 3/29/16.
 */
public enum Events {
  //Docker Registry Event
  /**
   * Docker Registry events received
   */
  REGISTRY_EVENT_RECEIVED,
  /**
   * event for fetch updated Image  from DB.
   */
  IMAGES_UPDATED,
  /**
   * Event of push Image
   */
  EVENT_IMAGES_UPDATED,
  /**
   * Event of delete Image
   */
  EVENT_IMAGES_DELETED,
  /**
   * scan file uploaded
   */
  SCANFILE_UPLOADED,
  IMAGE_TO_ENRICH,
  ENRICHFILE_UPLOADED,
  /**
   * in WatcherVeticle, whatever image from event or registry all mark it as new image.
   */
  NEW_IMAGE,

  /**
   * download scanfile or enrichfile.
   */
  DOWNLOAD_FILE,
  /**
   * API ,get all images with json format.
   * NOTE: follow the naming convention GET_IMAGES
   */
  GET_IMAGES;

}

