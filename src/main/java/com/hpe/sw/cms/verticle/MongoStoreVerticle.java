package com.hpe.sw.cms.verticle;

import com.hpe.sw.cms.store.Image;
import com.hpe.sw.cms.store.Store;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.FindOptions;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.mongo.UpdateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.rmi.runtime.Log;

import java.io.*;
import java.util.*;


/**
 * Created by keke on 3/28/16.
 */
public class MongoStoreVerticle extends AbstractVerticle implements Store {
    private static final Logger LOG = LoggerFactory.getLogger(MongoStoreVerticle.class);
    private MongoClient client;


    @Override
    public void start() throws Exception {
        super.start();
        client = MongoClient.createShared(vertx, config().getJsonObject("mongo"));
        vertx.eventBus().consumer(Events.GET_IMAGES.name(), msg -> {
            JsonObject param = (JsonObject) msg.body();
            JsonObject query = new JsonObject();
            if (param != null && param.getString("timestamp") != null) {
                Long timestamp = Long.parseLong(param.getString("timestamp"));
                query.put(Image.TIMESTAMP, new JsonObject().put("$gte", timestamp));
            } else if (param != null && param.getString("imageid") != null) {
                query.put(Image.IMAGE_ID, param.getString(Image.IMAGE_ID));
            }

            if (!query.containsKey(Image.IMAGE_ID)&&(param==null||param.getString("include") == null || !"all".equals(param.getString("include")))) {
                query.put(Image.IMAGE_ID, new JsonObject().put("$exists", true));
            }
            JsonArray images = new JsonArray();
            client.find("images", query, res -> {
                if (res.succeeded()) {
                    List<JsonObject> result = res.result();
                    for (JsonObject dbImage : result) {
                        images.add(Image.cloneImage(dbImage));
                    }
                    msg.reply(images);
                }
            });
        });


        vertx.eventBus().consumer(Events.DOWNLOAD_FILE.name(), msg -> {
            JsonObject query = (JsonObject) msg.body();
            LOG.debug("DOWNLOAD_FILE query is " + query);
            client.find("images", query, res -> {
                if (res.succeeded()) {
                    List<JsonObject> result = res.result();
                    LOG.debug("DOWNLOAD_FILE result is " + result.size());
                    if (result.size() > 0) {
                        msg.reply(result.get(0));
                    }else{
                        msg.reply(null);
                    }
                }
            });
        });


        vertx.eventBus().consumer(Events.IMAGES_UPDATED.name(), msg -> {
            JsonArray updates = new JsonArray();
            JsonObject query = new JsonObject();
            query.put(Image.IS_SCANNED, false);
            int fetchSize = Integer.valueOf(String.valueOf(msg.body()));
            FindOptions options =new FindOptions();
            JsonObject sort=new JsonObject();
            sort.put(Image.TIMESTAMP, -1);
            options.setLimit(fetchSize).setSort(sort);
            client.findWithOptions("images", query,options, res -> {
                if (res.succeeded()) {
                    List<JsonObject> result = res.result();
                    for (JsonObject update : result) {
                        updates.add(update);
                        LOG.debug("get image from DB :" + Image.getImageKey(update));
                    }
                    LOG.debug("IMAGES_UPDATED reply updates size " + updates.size());
                    msg.reply(updates);
                }
            });
        });

        vertx.eventBus().consumer(Events.SCANFILE_UPLOADED.name(), msg -> {
            JsonObject upFile = (JsonObject) msg.body();
            JsonObject query = new JsonObject();
            query.put(Image.HOST, upFile.getString(Image.HOST)).put(Image.NAME, upFile.getString(Image.NAME)).put(Image.TAG, upFile.getString(Image.TAG));
            client.find("images", query, res -> {
                if (res.succeeded()) {
                    List<JsonObject> result = res.result();
                    if (result.size() == 0) {
                        LOG.error("no mapped image in DB for " + Image.getImageKey(upFile));
                        return;
                    }
                    for (JsonObject dbImage : result) {
                        if (upFile.getBoolean("isScanFailed")) {
                            //Failed in scanning.
                            LOG.info("store failed scan to DB " + Image.getImageKey(upFile));
                            dbImage.put(Image.IS_SCANNED, true);
                            dbImage.put(Image.IS_SCANNED_FAILED, true);
                        } else {
                            //successfully in scanning.
                            LOG.info("store scanfile to DB " + Image.getImageKey(upFile));
                            dbImage.put(Image.IS_SCANNED, true);
                            dbImage.put(Image.IS_SCANNED_FAILED, false);
                            dbImage.put(Image.IMAGE_ID, upFile.getString(Image.IMAGE_ID));
                            dbImage.put(Image.SCANNED_FILE, upFile.getBinary(Image.SCANNED_FILE));
                        }
                        client.save("images", dbImage, h -> {
                            if (h.succeeded()) {
                                LOG.info("SCANFILE_UPLOADED:Image " + Image.getImageKey(dbImage) + " updated !");
                            } else {
                                h.cause().printStackTrace();
                            }
                        });
                    }
                }
            });


        });

        vertx.eventBus().consumer(Events.ENRICHFILE_UPLOADED.name(), msg -> {
            JsonArray upFiles = (JsonArray) msg.body();
            for (Object upFileObj : upFiles) {
                JsonObject upFile = (JsonObject) upFileObj;
                if (upFile.getBinary("enrichedFile") == null) {
                    LOG.info("enrichedFile is emptry for " + upFile.getString("imageid"));
                    continue;
                }
                LOG.info("store enrichfile to DB " + upFile.getString("imageid"));
                JsonObject query = new JsonObject();
                query.put(Image.IMAGE_ID, upFile.getString(Image.IMAGE_ID));
                client.find("images", query, res -> {
                    if (res.succeeded()) {
                        List<JsonObject> result = res.result();
                        for (JsonObject dbImage : result) {
                            dbImage.put(Image.IS_ENRICHED, true);
                            dbImage.put(Image.ENRICHED_FILE, upFile.getBinary(Image.ENRICHED_FILE));
                            client.save("images", dbImage, h -> {
                                if (h.succeeded()) {
                                    LOG.info("ENRICHFILE_UPLOADED:Image " + Image.getImageKey(dbImage) + " updated !");
                                } else {
                                    h.cause().printStackTrace();
                                }
                            });
                        }
                    }
                });
            }

        });

        vertx.eventBus().consumer(Events.IMAGE_TO_ENRICH.name(), msg -> {
            JsonObject query = new JsonObject();
            query.put(Image.IS_SCANNED, true).put(Image.IS_SCANNED_FAILED, false).put(Image.IS_ENRICHED, false);
            client.find("images", query, res -> {
                if (res.succeeded()) {
                    List<JsonObject> result = res.result();
                    msg.reply(new JsonArray(result));
                }
            });
        });

        vertx.eventBus().consumer(Events.NEW_IMAGE.name(), msg -> {
            //to store events in
            JsonObject obj = (JsonObject) msg.body();
            JsonObject query = new JsonObject();
            query.put(Image.HOST, obj.getString(Image.HOST)).put(Image.NAME, obj.getString(Image.NAME)).put(Image.TAG, obj.getString(Image.TAG));
            client.find("images", query, res -> {
                if (res.succeeded()) {
                    List<JsonObject> result = res.result();
                    if (result.isEmpty()) {
                        //inserted
                        client.insert("images", obj, h -> {
                            if (h.succeeded()) {
                                LOG.info("IMAGES_COMMING :Image " + Image.getImageKey(obj) + " inserted !");
                            } else {
                                h.cause().printStackTrace();
                            }
                        });
                    } else if (result.size() == 1) {
                        JsonObject toUpdate = result.get(0);
                        if (!obj.getString(Image.SIGN).equals(toUpdate.getString(Image.SIGN))) {
                            toUpdate.put(Image.TIMESTAMP, obj.getLong(Image.TIMESTAMP)).put(Image.SIGN, obj.getString(Image.SIGN)).put(Image.IS_SCANNED, obj.getBoolean(Image.IS_SCANNED)).put(Image.IS_ENRICHED, obj.getBoolean(Image.IS_ENRICHED));
                            //saved
                            client.save("images", toUpdate, h -> {
                                if (h.succeeded()) {
                                    LOG.info("IMAGES_COMMING :Image " + Image.getImageKey(obj) + " updated !");
                                } else {
                                    h.cause().printStackTrace();
                                }
                            });
                        } else {
                            LOG.info("IMAGES_COMMING :Image " + Image.getImageKey(obj) + " has the same sign with the coming image, so will not update to DB !");
                        }
                    } else {
                        throw new RuntimeException("IMAGES_COMMING :Found " + result.size() + " image for " + Image.getImageKey(obj));
                    }
                }
            });
        });
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        client.close();
    }
}
