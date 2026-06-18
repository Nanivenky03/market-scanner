package com.trading.scanner.service.provider.angelone;

import com.trading.scanner.config.TimeProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WebSocketFrameCaptureService {

    private static final int MAX_FRAMES = 200;

    private final TimeProvider timeProvider;
    private final Deque<FrameRecord> frames = new ArrayDeque<>();

    public synchronized void captureText(String payload) {
        add(new FrameRecord(
                "TEXT",
                payload != null ? payload.length() : 0,
                abbreviate(payload, 4000),
                timeProvider.nowDateTime()));
    }

    public synchronized void captureBinary(byte[] bytes) {
        add(new FrameRecord(
                "BINARY",
                bytes != null ? bytes.length : 0,
                toHexPreview(bytes, 256),
                timeProvider.nowDateTime()));
    }

    public synchronized List<FrameRecord> recent(int limit) {
        List<FrameRecord> copy = new ArrayList<>(frames);
        int from = Math.max(0, copy.size() - limit);
        return copy.subList(from, copy.size());
    }

    public synchronized ClearResult clear() {
        int removed = frames.size();
        frames.clear();
        return new ClearResult(removed, "Cleared captured websocket frames");
    }

    private void add(FrameRecord record) {
        if (frames.size() >= MAX_FRAMES) {
            frames.removeFirst();
        }
        frames.addLast(record);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String toHexPreview(byte[] bytes, int maxBytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        int limit = Math.min(bytes.length, maxBytes);
        StringBuilder sb = new StringBuilder(limit * 3);

        for (int i = 0; i < limit; i++) {
            sb.append(String.format("%02X", bytes[i]));
            if (i < limit - 1) {
                sb.append(' ');
            }
        }

        if (bytes.length > maxBytes) {
            sb.append(" ...");
        }

        return sb.toString();
    }

    public record FrameRecord(
            String frameType,
            int size,
            String preview,
            LocalDateTime receivedAt) {
    }

    public record ClearResult(
            int removed,
            String message) {
    }
}