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

import com.hpe.sw.cms.common.Constant;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

/**
 * @author keke
 */
public class RecognizerVerticle extends AbstractVerticle {
    private static final Logger LOG = LoggerFactory.getLogger(RecognizerVerticle.class);
    private static final long INTERVAL = 20000L;

    @Override
    public void start() throws Exception {
        super.start();
        getVertx().setPeriodic(config().getLong("recognizer.interval", INTERVAL), h -> {
            getVertx().eventBus().send(Events.IMAGE_TO_ENRICH.name(), null, event -> {
                Message msg = event.result();
                if (msg != null) {
                    String enrichPath = Constant.PROJECT_PATH + "xmlenricher/runtime/xmlenricher/Scans/incoming/";
                    JsonArray scanfiles = (JsonArray) msg.body();
                    for (Object obj : scanfiles) {
                        FileOutputStream fop = null;
                        try {
                            JsonObject image = (JsonObject) obj;
                            String filePath = enrichPath + image.getString("imageid") + ".xsf";
                            File file = new File(filePath);
                            if (!file.exists()) {
                                file.createNewFile();
                            }
                            fop = new FileOutputStream(file);
                            IOUtils.write(image.getBinary("scannedFile"), fop);
                            fop.flush();
                            fop.close();
                        } catch (Exception e) {
                            LOG.error("Error in writing scan file", e);
                        } finally {
                            if (fop != null) {
                                try {
                                    fop.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                }
            });
        });

        //check whether there are new enriched files
        getVertx().setPeriodic(config().getLong("recognizer.interval", INTERVAL), h -> {
            String enrichPath = Constant.PROJECT_PATH + "xmlenricher/runtime/xmlenricher/Scans/processedcore/";
            File fileDir = new File(enrichPath);
            File[] fileList = fileDir.listFiles(XSF_FILTER);
            JsonArray enrichedFiles = new JsonArray();
            for (File file : fileList) {
                if (file.isFile()) {
                    String imageid = file.getName().split("\\.")[0];
                    try {
                        JsonObject enrichedFile = new JsonObject();
                        enrichedFile.put("imageid", imageid);
                        enrichedFile.put("enrichedFile", FileUtils.readFileToByteArray(file));
                        enrichedFiles.add(enrichedFile);
                        file.delete(); //TODO: do a batch delete after all enrichedFiles are collected
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            if (enrichedFiles.size() > 0) {
                getVertx().eventBus().publish(Events.ENRICHFILE_UPLOADED.name(), enrichedFiles);
            }
        });
    }

    public static FileFilter XSF_FILTER = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            return pathname.getName().endsWith("xsf");
        }
    };

    @Override
    public void stop() throws Exception {
        super.stop();
    }
}
