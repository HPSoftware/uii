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

import com.hpe.sw.cms.store.Image;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Map;

/**
 * @author keke
 */
public class WatcherVerticle extends AbstractVerticle {
    private static final Logger LOG = LoggerFactory.getLogger(WatcherVerticle.class);
    private static final long INTERVAL = 2 * 60 * 60 * 1000L;
    private HttpClient httpClient;

    @Override
    public void start() throws Exception {
        httpClient = vertx.createHttpClient();
        getVertx().setPeriodic(config().getLong("registry.interval", INTERVAL), this::handler);
        vertx.eventBus().consumer(Events.EVENT_IMAGES_UPDATED.name(), msg -> {
            JsonArray images = (JsonArray) msg.body();
            if (images.size() > 0) {
                for (Object image : images) {
                    JsonObject imageObj = (JsonObject) image;
                    try {
                        String url = imageObj.getString("eventUrl");
                        httpClient.getAbs(url, new Handler<HttpClientResponse>() {
                            @Override
                            public void handle(HttpClientResponse httpClientResponse) {
                                httpClientResponse.bodyHandler(new Handler<Buffer>() {
                                    @Override
                                    public void handle(Buffer buffer) {
                                        JsonObject maniFestLib = buffer.toJsonObject();
                                        String tag = maniFestLib.getString("tag");
                                        LOG.debug("populateTagToImage " + tag);
                                        imageObj.put(Image.TAG, tag);
                                        populateAndSendImage(imageObj);
                                    }
                                });
                            }
                        }).end();

                    } catch (Exception e) {
                        LOG.error("error in populateTagToImage", e);
                    }
                }

            }
        });
    }

    private void handler(Long h) {
        try {
            String protocol = config().getString("registry.protocol");
            String host = config().getString("registry.host");
            httpClient.getAbs(protocol + host + "/v2/_catalog", new Handler<HttpClientResponse>() {
                @Override
                public void handle(HttpClientResponse httpClientResponse) {
                    httpClientResponse.bodyHandler(new Handler<Buffer>() {
                        @Override
                        public void handle(Buffer buffer) {
                            JsonObject repositoryLib = buffer.toJsonObject();
                            JsonArray repos = repositoryLib.getJsonArray("repositories");
                            repos.forEach(repo -> {
                                if (repo != null && !((String) repo).trim().equals("")) {
                                    try {
                                        repo = ((String) repo).trim();
                                        String[] imagePart = ((String) repo).split("/");
                                        String imageName = String.join("/", imagePart);
                                        httpClient.getAbs(protocol + host + "/v2/" + imageName + "/tags/list", new Handler<HttpClientResponse>() {
                                            @Override
                                            public void handle(HttpClientResponse httpClientResponse) {
                                                httpClientResponse.bodyHandler(new Handler<Buffer>() {
                                                    @Override
                                                    public void handle(Buffer buffer) {
                                                        JsonObject tagLib = buffer.toJsonObject();
                                                        JsonArray tags = tagLib.getJsonArray("tags");
                                                        for (Object tag : tags) {
                                                            if (tag != null && !((String) tag).trim().equals("")) {
                                                                JsonObject imageObj = new JsonObject();
                                                                imageObj.put("name", imageName);
                                                                imageObj.put("tag", tag);
                                                                String dest = host;
                                                                imageObj.put("host", dest);
                                                                populateAndSendImage(imageObj);
                                                            }
                                                        }
                                                    }
                                                });
                                            }
                                        }).end();

                                    } catch (Exception e) {
                                        LOG.error("error in reading registry", e);
                                    }
                                }
                            });
                        }
                    });
                }
            }).end();
        } catch (Exception e) {
            LOG.error("error in registry handler", e);
        }
    }


    private void populateAndSendImage(JsonObject imageObj) {
        try {
            String protocol = config().getString("registry.protocol");
            String host = config().getString("registry.host");
            httpClient.getAbs(protocol + host + "/v2/" + imageObj.getString("name") + "/manifests/" + imageObj.getString("tag"), new Handler<HttpClientResponse>() {
                @Override
                public void handle(HttpClientResponse httpClientResponse) {
                    httpClientResponse.bodyHandler(new Handler<Buffer>() {
                        @Override
                        public void handle(Buffer buffer) {
                            JsonObject maniFestLib = buffer.toJsonObject();
                            JsonArray signs = maniFestLib.getJsonArray("signatures");
                            if (signs != null && signs.size() > 0) {
                                StringBuffer fullSign = new StringBuffer();
                                for (Object sign : signs.getList()) {
                                    fullSign.append(((Map) sign).get("signature")).append("|");
                                }
                                imageObj.put(Image.SIGN, fullSign);
                                imageObj.put(Image.IS_SCANNED, false);
                                imageObj.put(Image.IS_ENRICHED, false);
                                imageObj.put(Image.IS_SCANNED_FAILED, false);
                                if (imageObj.getLong(Image.TIMESTAMP) == null) {
                                    imageObj.put(Image.TIMESTAMP, new Date().getTime());
                                }
                                vertx.eventBus().publish(Events.NEW_IMAGE.name(), imageObj);
                                LOG.info("Event Image with populateSignToImage", imageObj);
                            }
                        }
                    });
                }
            }).end();
        } catch (Exception e) {
            LOG.error("error in populateSignToImage", e);
        }
    }

    @Override
    public void stop() throws Exception {
        super.stop();
    }
}
