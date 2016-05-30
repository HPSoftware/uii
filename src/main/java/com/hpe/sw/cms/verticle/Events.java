/**
 * (c)Copyright[2016]Hewlett Packard Enterprise Development LP
 * Licensed under the Apache License,Version2.0(the"License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,software
 * distributed under the License is distributed on an"AS IS"BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
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

