package com.hpe.sw.cms.verticle;

import io.vertx.core.*;
import io.vertx.core.json.JsonObject;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 */
public class Boot extends AbstractVerticle {
  private static final Logger LOG = LoggerFactory.getLogger(Boot.class);

  private static final List<String> VERTICLES = Collections.unmodifiableList(
      Arrays.asList(
          "com.hpe.sw.cms.verticle.ScanVerticle",
          "com.hpe.sw.cms.verticle.ApiVerticle",
          "com.hpe.sw.cms.verticle.RecognizerVerticle",
          "com.hpe.sw.cms.verticle.WatcherVerticle",
          "com.hpe.sw.cms.verticle.MongoStoreVerticle"
      ));


  public static void main(String... args) throws IOException {
    String file=args[0];
//    String file="dockerWeb/startup.json";
    String confStr = FileUtils.readFileToString(new File(file));
    JsonObject jsonConf = new JsonObject(confStr);
    DeploymentOptions deploymentOptions = new DeploymentOptions(jsonConf);
    VertxOptions options = new VertxOptions();
    options.setMaxEventLoopExecuteTime(Long.MAX_VALUE);
    Vertx vertx = Vertx.vertx(options);
    vertx.deployVerticle(new Boot(), deploymentOptions, r -> {
      if (r.succeeded()) {
        LOG.info("Successfully deployed");
      } else {
        throw new RuntimeException(r.cause());
      }
    });
  }

  private Handler<AsyncResult<String>> handler(String name) {
    return event -> {
      if (event.succeeded()) {
        LOG.info("Successfully deploy {}", name);
      } else {
        LOG.error("Unable to deploy {}", name, event.cause());
        throw new RuntimeException(event.cause());
      }
    };
  }

  @Override
  public void start(Future<Void> startFuture) throws Exception {
    DeploymentOptions deploymentOptions = new DeploymentOptions().setConfig(config());
    VERTICLES.forEach(v -> {
      getVertx().deployVerticle(v, deploymentOptions, handler(v));
    });
    startFuture.complete();
  }

  @Override
  public void stop() throws Exception {
    super.stop();
  }
}
