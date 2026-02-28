package com.vnc.model;

public record LockStatusMessage(String type, boolean locked, boolean you) {

    public static LockStatusMessage of(boolean locked, boolean isController) {
        return new LockStatusMessage("lockStatus", locked, isController);
    }
}
