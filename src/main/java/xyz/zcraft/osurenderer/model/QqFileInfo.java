package xyz.zcraft.osurenderer.model;

import com.google.gson.annotations.SerializedName;

public record QqFileInfo(
        @SerializedName("file_uuid") String fileUuid,
        @SerializedName("file_info") String fileInfo,
        Integer ttl
) {
    public QqFileInfo {
        if (fileInfo == null || fileInfo.isBlank()) {
            throw new IllegalArgumentException("QQ upload response is missing file_info");
        }
    }
}
