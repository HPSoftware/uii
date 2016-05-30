package com.hpe.sw.cms.store;

import io.vertx.core.json.JsonObject;

/**
 * Created by dingmeng on 4/23/2016.
 */
public class Image {
    public static String NAME = "name";
    public static String IMAGE_ID = "imageid";
    public static String TAG = "tag";
    public static String HOST = "host";
    public static String IS_SCANNED = "isScanned";
    public static String IS_SCANNED_FAILED = "isScanFailed";
    public static String IS_ENRICHED = "isEnriched";
    public static String TIMESTAMP = "timestamp";
    public static String ENRICHED_FILE = "enrichedFile";
    public static String SCANNED_FILE = "scannedFile";
    public static String SIGN = "sign";
    public static String EVENT_URL = "eventUrl";
    public static String getImageKey(JsonObject image) {
        return image.getString(HOST) + "/" + image.getString(NAME) + ":" + image.getString(TAG);
    }




    public static JsonObject cloneImage(JsonObject sourceImage) {
        JsonObject targetImage = new JsonObject();
        targetImage.put("host", (sourceImage).getString("host"));
        targetImage.put("name", (sourceImage).getString("name"));
        targetImage.put("tag", (sourceImage).getString("tag"));
        targetImage.put("imageid", (sourceImage).getString("imageid"));
        targetImage.put("timestamp", (sourceImage).getLong("timestamp"));
        targetImage.put("isEnriched", (sourceImage).getBoolean("isEnriched"));
        targetImage.put("isScanned", (sourceImage).getBoolean("isScanned"));
        targetImage.put(Image.IS_SCANNED_FAILED, (sourceImage).getBoolean(Image.IS_SCANNED_FAILED));
        return targetImage;
    }
}
