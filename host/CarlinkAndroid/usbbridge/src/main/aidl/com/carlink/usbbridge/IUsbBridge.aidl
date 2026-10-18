package com.carlink.usbbridge;

import com.carlink.usbbridge.IUsbBridgeCallback;

interface IUsbBridge {
    String[] listAdapters();
    boolean hasPermission(String deviceName);
    // Find an OCBM device, wait briefly for enumeration/GM's silent grant, and open it.
    // Returns the opened deviceName, or an empty string if no usable device appeared.
    String openFirstAdapter();
    boolean open(String deviceName);
    void startReading();
    void close();
    void registerCallback(IUsbBridgeCallback callback);
    void unregisterCallback(IUsbBridgeCallback callback);
    // Empty string means accepted; otherwise returns a diagnostic suitable for the app's log.
    String sendChunk(long transferId, int chunkIndex, int chunkCount, int totalLength, in byte[] data);
}
