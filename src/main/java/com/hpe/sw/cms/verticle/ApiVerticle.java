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
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Set;
import java.util.zip.GZIPInputStream;
/**
 * API Verticle exposes a number of Restful endpoint
 *
 * @author keke
 * @author dmb
 */
public class ApiVerticle extends AbstractVerticle {
    private static final Logger LOG = LoggerFactory.getLogger(ApiVerticle.class);

    @Override
    public void start() throws Exception {
        super.start();
        HttpServer server = vertx.createHttpServer();
        Router mainRouter = Router.router(vertx);
        Router restApi = Router.router(vertx);
        mainRouter.mountSubRouter("/api", restApi);
        setUpApiRouter(restApi);
        server.requestHandler(mainRouter::accept).listen(8080);
    }


    private void setUpApiRouter(Router restApi) {
        restApi.route().handler(BodyHandler.create());
        //end point which receives Docker Registry events
        restApi.post("/registry/event").consumes("application/vnd.docker.distribution.events.v1+json").handler(registryEventHandler());
        //end point which provides image list ; images?timestamp=timestamp , url to get images after timestamp ;images?id=imageid , url to get images with param imageid.
        restApi.get("/images").produces("application/json").handler(imagesHandler());
        //images/scan?host=${host}&name=${name}&tag=${tag} for downloading scan file, /images/enrich?host=${host}&name=${name}&tag=${tag} for downloading enrich file.
        restApi.get("/images/:id/:fileCategory").handler(downloadHandler());
        restApi.get("/images/:fileCategory").handler(downloadHandler());
        //_scanfile?host=${host}&name=${name}&tag=${tag}&imageId={imageId} for successful scanning, _scanfile?host=${host}&name=${name}&tag=${tag}&imageId=0&errorMsg=${MESSAGE} for failed scanning.
        restApi.post("/_scanfile").handler(uploadHandler());
    }

    /**
     * Handler to receive POST /_scanfile.
     *
     * @return
     */
    private Handler<RoutingContext> uploadHandler() {
        return routingContext -> {
            int resposeCode = 201;
            String resposeMsg = "OK";
            String host = routingContext.request().getParam(Image.HOST);
            String name = routingContext.request().getParam(Image.NAME);
            String tag = routingContext.request().getParam(Image.TAG);
            String imageid = routingContext.request().getParam(Image.IMAGE_ID);
            String errorMsg = routingContext.request().getParam("errorMsg");
            JsonObject upFile = new JsonObject();
            upFile.put(Image.HOST, host);
            upFile.put(Image.NAME, name);
            upFile.put(Image.TAG, tag);
            if (errorMsg != null && "0".equals(imageid)) {
                upFile.put(Image.IS_SCANNED_FAILED, true);
                LOG.error("scanfile uploaded failed " + Image.getImageKey(upFile) + " with error message " + errorMsg);
            } else {
                Set<FileUpload> upFiles = routingContext.fileUploads();
                FileUpload upF = upFiles.iterator().next();
                ByteArrayOutputStream out = null;
                BufferedInputStream in = null;
                upFile.put(Image.IMAGE_ID, imageid);
                upFile.put(Image.IS_SCANNED_FAILED, false);
                try {
                    File file = new File(upF.uploadedFileName());
                    in = new BufferedInputStream(new FileInputStream(file));
                    out = new ByteArrayOutputStream();
                    IOUtils.copy(in, out);
                    upFile.put(Image.SCANNED_FILE, out.toByteArray());
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    try {
                        in.close();
                        out.close();
                    } catch (IOException e) {
                        upFile.put(Image.IS_SCANNED_FAILED, true);
                        resposeCode = 500;
                        resposeMsg = "Error happens when in reading file";
                        LOG.error(resposeMsg, e);
                    }
                }
                LOG.debug("scanfile uploaded " + Image.getImageKey(upFile));
            }
            routingContext.response().setStatusCode(resposeCode).end(resposeMsg);
            getVertx().eventBus().publish(Events.SCANFILE_UPLOADED.name(), upFile);
        };
    }

    @Override
    public void stop() throws Exception {
        super.stop();
    }

    /**
     * Handler to handle GET /images/:id request. Response is the image scan files (with/without enrich).
     *
     * @return
     */
    private Handler<RoutingContext> downloadHandler() {
        return routingContext -> {
            String category = routingContext.request().getParam("fileCategory");
            JsonObject params = new JsonObject();
            if(routingContext.request().getParam("id")!=null){
                params.put(Image.IMAGE_ID, routingContext.request().getParam("id"));
            }else {
                params.put(Image.HOST, routingContext.request().getParam(Image.HOST));
                params.put(Image.NAME, routingContext.request().getParam(Image.NAME));
                params.put(Image.TAG, routingContext.request().getParam(Image.TAG));
            }
            vertx.eventBus().send(Events.DOWNLOAD_FILE.name(), params, event -> {
                if (event.succeeded() && event.result() != null) {
                    Message msg = event.result();
                    JsonObject file = (JsonObject) msg.body();
                    routingContext.response().setChunked(true);
                    if (file == null) {
                        routingContext.response().setStatusCode(404).end("There is no image  found.");
                        return;
                    }
                    if ("enrich".equals(category) && file.getBinary(Image.ENRICHED_FILE) != null) {
                        String fileName = file.getString(Image.HOST) + "___" + file.getString(Image.NAME) + "___" + file.getString(Image.IMAGE_ID) + ".xsf";
                        routingContext.response().putHeader("Content-Disposition", "attachment; filename = " + fileName);
                        Buffer buffer = Buffer.buffer(file.getBinary(Image.ENRICHED_FILE));
                        routingContext.response().end(buffer);
                    } else if ("enrich_xml".equals(category) && file.getBinary(Image.SCANNED_FILE) != null) {
                        routingContext.response().end(decompressGzip(file.getBinary(Image.ENRICHED_FILE)));
                    } else if ("scan".equals(category) && file.getBinary(Image.SCANNED_FILE) != null) {
                        String fileName = file.getString(Image.HOST) + "___" + file.getString(Image.NAME) + "___" + file.getString(Image.IMAGE_ID) + ".xsf";
                        routingContext.response().putHeader("Content-Disposition", "attachment; filename = " + fileName);
                        Buffer buffer = Buffer.buffer(file.getBinary(Image.SCANNED_FILE));
                        routingContext.response().end(buffer);
                    } else if ("scan_xml".equals(category) && file.getBinary(Image.SCANNED_FILE) != null) {
                        routingContext.response().end(decompressGzip(file.getBinary(Image.SCANNED_FILE)));
                    }
                } else if (event.result() == null) {
                    routingContext.response().setStatusCode(404).end("There is no image  found.");
                } else {
                    routingContext.response().setStatusCode(500).end("Server has error.");
                }
            });
        };
    }

    /**
     * decompress gzip to string
     * @param gzip byte[]
     * @return
     */
    private String decompressGzip(byte[] gzip) {
        GZIPInputStream gZIPInputStream = null;
        InputStreamReader inputStreamReader = null;
        BufferedReader bf = null;
        try {
            gZIPInputStream = new GZIPInputStream(new ByteArrayInputStream(gzip));
            inputStreamReader = new InputStreamReader(gZIPInputStream);
            bf = new BufferedReader(inputStreamReader);
            StringBuffer outStr = new StringBuffer();
            String line;
            while ((line = bf.readLine()) != null) {
                outStr.append(line);
            }
            return outStr.toString();
        } catch (Exception e) {
            return "failed in decompressGzip with error:" + e.getMessage();
        } finally {
            try {
                bf.close();
                inputStreamReader.close();
                gZIPInputStream.close();
            } catch (Throwable t) {
                return "failed in decompressGzip with error:" + t.getMessage();
            }
        }
    }
    /**
     * Handler to handle GET /images request. The response is a list of images
     *
     * @return
     */
    private Handler<RoutingContext> imagesHandler() {
        return routingContext -> {
            String timestamp = routingContext.request().getParam("timestamp");
            String id = routingContext.request().getParam("id");
            String include = routingContext.request().getParam("include");
            JsonObject param = new JsonObject();
            if (timestamp != null) {
                param.put("timestamp", timestamp);
            }
            if (id != null) {
                param.put("imageid", id);
            }
            if (include != null) {
                param.put("include", include);
            }
            getVertx().eventBus().send(Events.GET_IMAGES.name(), param, event -> {
                if (event.succeeded() && event.result() != null) {
                    Message msg = event.result();
                    JsonArray updates = (JsonArray) msg.body();
                    HttpServerResponse response = routingContext.response();
                    if(updates.size()==0){
                        response.end("There is no image found.");
                    }else{
                        response.end(updates.toString());
                    }
                } else if (event.result() == null) {
                    routingContext.response().setStatusCode(404).end("There is no image found.");
                } else {
                    routingContext.response().setStatusCode(500).end("Server has error.");
                }
            });
        };
    }

    /**
     * Handler to handle Docker Registry event
     *
     * @return handler context
     */
    private Handler<RoutingContext> registryEventHandler() {
        return routingContext -> {
            JsonObject body = routingContext.getBodyAsJson();
            LOG.info("Docker Registry events received from {}", routingContext.request().getParam("registry"));
            JsonArray events = body.getJsonArray("events");
            JsonArray updated = new JsonArray();
            JsonArray deleted = new JsonArray();
            events.forEach(e -> {
                JsonObject event = (JsonObject) e;
                if (event.getString("action").equals("push")) {
                    JsonObject obj = createObj(event);
                    if (obj != null) {
                        updated.add(obj);
                    }
                } else if (event.getString("action").equals("delete")) {
                    JsonObject obj = createObj(event);
                    if (obj != null) {
                        deleted.add(obj);
                    }
                }
            });
            if (updated.size() != 0)
                vertx.eventBus().publish(Events.EVENT_IMAGES_UPDATED.name(), updated);
            if (deleted.size() != 0) //TODO
                vertx.eventBus().publish(Events.EVENT_IMAGES_DELETED.name(), deleted);
            //Always return 200 OK.
            routingContext.response().setStatusCode(200).end("OK");

        };
    }

    private JsonObject createObj(JsonObject event) {
        try {
            JsonObject image = new JsonObject();
            String timestamp = event.getString("timestamp");
            if (timestamp != null && timestamp.length() > 19) {
                timestamp = timestamp.substring(0, 10) + " " + timestamp.substring(11, 19);
                image.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(timestamp).getTime());
            }
            String url = event.getJsonObject("target").getString("url");
            image.put("name", event.getJsonObject("target").getString("repository"));
            image.put("host", event.getJsonObject("request").getString("host"));
            image.put(Image.EVENT_URL, url);
            return image;
        } catch (Exception e) {
            LOG.error("Error in reading event to object ",e);
            return null;
        }
    }
}
