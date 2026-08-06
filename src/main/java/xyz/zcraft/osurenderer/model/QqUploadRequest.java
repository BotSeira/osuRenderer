package xyz.zcraft.osurenderer.model;

public record QqUploadRequest(String accessToken, String targetType, String targetId) {
    public QqUploadRequest {
        accessToken = requireValue(accessToken, "accessToken", 4096);
        targetType = requireValue(targetType, "targetType", 16);
        targetId = requireValue(targetId, "targetId", 256);
        if (!targetType.equals("groups") && !targetType.equals("users")) {
            throw new IllegalArgumentException("qqUpload.targetType must be groups or users");
        }
    }

    private static String requireValue(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("qqUpload." + name + " is invalid");
        }
        return value;
    }
}
