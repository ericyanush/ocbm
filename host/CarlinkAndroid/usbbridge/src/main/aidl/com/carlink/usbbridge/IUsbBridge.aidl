package com.carlink.usbbridge;

import com.carlink.usbbridge.IUsbBridgeCallback;

interface IUsbBridge {
    String[] listAdapters();
    boolean hasPermission(String deviceName);
    boolean open(String deviceName);
    void startReading();
    void close();
    void registerCallback(IUsbBridgeCallback callback);
    void unregisterCallback(IUsbBridgeCallback callback);
    boolean sendChunk(long transferId, int chunkIndex, int chunkCount, int totalLength, in byte[] data);
}
