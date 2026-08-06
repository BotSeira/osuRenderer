package xyz.zcraft.osurenderer;

import com.google.gson.JsonObject;

public class MiscUtil {
    public static JsonObject deepMergeJson(JsonObject first, JsonObject... others) {
        JsonObject merged = first.deepCopy();
        for (JsonObject other : others) {
            for (String key : other.keySet()) {
                if (merged.has(key)) {
                    if (merged.get(key).isJsonObject() && other.get(key).isJsonObject()) {
                        merged.add(key, deepMergeJson(merged.getAsJsonObject(key), other.getAsJsonObject(key)));
                    } else {
                        merged.add(key, other.get(key));
                    }
                } else {
                    merged.add(key, other.get(key));
                }
            }
        }
        return merged;
    }
}
