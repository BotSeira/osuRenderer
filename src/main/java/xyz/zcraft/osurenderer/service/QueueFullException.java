package xyz.zcraft.osurenderer.service;

public class QueueFullException extends RuntimeException {
    public QueueFullException() {
        super("Render queue is full");
    }
}
