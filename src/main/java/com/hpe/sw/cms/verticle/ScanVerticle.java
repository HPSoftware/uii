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
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * @author keke
 */
public class ScanVerticle extends AbstractVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(ScanVerticle.class);
    private static final long INTERVAL = 20000L;
    private HttpClient httpClient;

    @Override
    public void start() throws Exception {
        super.start();
        httpClient = vertx.createHttpClient();
        final String dricHost = config().getString("dric.host");
        final String swarmProtocol = config().getString("swarm.protocol");
        final String swarmHost = config().getString("swarm.host");
        final String swarmImage = config().getString("swarm.scan.image");
        final int scanMax = config().getInteger("scanner.max");


        getVertx().setPeriodic(config().getLong("scan.interval", INTERVAL), h -> {
            httpClient.getAbs(swarmProtocol + swarmHost + "/containers/json", new Handler<HttpClientResponse>() {
                @Override
                public void handle(HttpClientResponse httpClientResponse) {
                    httpClientResponse.bodyHandler(new Handler<Buffer>() {
                        @Override
                        public void handle(Buffer res) {
                            JsonArray containers = res.toJsonArray();
                            int currentRunning = 0;
                            for (Object obj : containers) {
                                JsonObject container = (JsonObject) obj;
                                if (swarmImage.equals(container.getString("Image"))) {
                                    currentRunning++;
                                }
                            }
                            int fetchSize = scanMax - currentRunning;
                            if (fetchSize > 0) {
                                getVertx().eventBus().send(Events.IMAGES_UPDATED.name(), fetchSize, event -> {
                                    Message msg = event.result();
                                    if (msg != null) {
                                        JsonArray updates = (JsonArray) msg.body();
                                        for (Object obj : updates) {
                                            try {
                                                JsonObject image = (JsonObject) obj;
                                                processImage(dricHost, image.getString("host"), image.getString("name"), image.getString("tag"));
                                            } catch (Exception e) {
                                                LOG.error("image sent to Scan error", e);
                                            }
                                        }
                                    }
                                });
                            }

                        }
                    });
                }
            }).end();
        });


        //delete exited containers of scan.
        getVertx().setPeriodic(config().getLong("scan.interval", INTERVAL), h -> {
            Set containerIds = getVertx().sharedData().getLocalMap("scanContainerIds").keySet();
            containerIds.forEach(containerId -> {
                httpClient.getAbs(swarmProtocol + swarmHost + "/containers/" + containerId + "/json", new Handler<HttpClientResponse>() {
                    @Override
                    public void handle(HttpClientResponse httpClientResponse) {
                        httpClientResponse.bodyHandler(new Handler<Buffer>() {
                            @Override
                            public void handle(Buffer event) {
                                if ("exited".equals(event.toJsonObject().getJsonObject("State").getString("Status"))) {
                                    String containerId = event.toJsonObject().getString("Id");
                                    httpClient.deleteAbs("http://" + swarmHost + "/containers/" + containerId, new Handler<HttpClientResponse>() {
                                        @Override
                                        public void handle(HttpClientResponse event) {
                                            LOG.info("delete container with response code :" + event.statusCode());
                                            getVertx().sharedData().getLocalMap("scanContainerIds").remove(containerId);
                                        }
                                    }).end();
                                }
                            }
                        });
                    }
                }).end();
            });
        });

    }

    private void processImage(String dricHost, String reigstryHost, String image, String tag) throws Exception {
        final String swarmProtocol = config().getString("swarm.protocol");
        final String swarmHost = config().getString("swarm.host");

        JsonObject hostConfig = new JsonObject();
        hostConfig.put("Privileged", true);

        JsonObject restartPolicy = new JsonObject();
        restartPolicy.put("Name", "no");

        JsonObject createContinaerJson = new JsonObject();
        createContinaerJson.put("AttachStdin", true);
        createContinaerJson.put("AttachStdout", true);
        createContinaerJson.put("AttachStderr", true);
        createContinaerJson.put("Tty", true);
        createContinaerJson.put("OpenStdin", true);
        createContinaerJson.put("StdinOnce", true);
        JsonArray cmds = new JsonArray();
        cmds.add("-l=file").add(dricHost).add(reigstryHost).add(image).add(tag);
        createContinaerJson.put("Cmd", cmds);
        createContinaerJson.put("Image", config().getString("swarm.scan.image"));
        createContinaerJson.put("StopSignal", "SIGTERM");
        createContinaerJson.put("", true);

        hostConfig.put("RestartPolicy", restartPolicy);
        createContinaerJson.put("HostConfig", hostConfig);

        httpClient.postAbs(swarmProtocol + swarmHost + "/containers/create", new Handler<HttpClientResponse>() {
            @Override
            public void handle(HttpClientResponse event) {
                event.bodyHandler(new Handler<Buffer>() {
                    @Override
                    public void handle(Buffer buffer) {
                        JsonObject retVal = buffer.toJsonObject();
                        String containerId = retVal.getString("Id");
                        getVertx().sharedData().getLocalMap("scanContainerIds").put(containerId, containerId);
                        httpClient.postAbs(swarmProtocol + swarmHost + "/containers/" + containerId + "/start", new Handler<HttpClientResponse>() {
                            @Override
                            public void handle(HttpClientResponse event) {
                                LOG.info("start container with response code :" + event.statusCode());
                            }
                        }).end();
                    }
                });
            }
        }).putHeader("content-type", "application/json").putHeader("content-length", String.valueOf(createContinaerJson.toString().length())).write(createContinaerJson.toString()).end();
    }

    @Override
    public void stop() throws Exception {
        super.stop();
    }
}
