package com.vnc.model;

import java.util.List;

public record FrameMessage(String type, boolean full, List<TileData> tiles) {

    public FrameMessage(boolean full, List<TileData> tiles) {
        this("frame", full, tiles);
    }
}
