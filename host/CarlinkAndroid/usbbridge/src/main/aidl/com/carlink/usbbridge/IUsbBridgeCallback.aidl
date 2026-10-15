package com.carlink.usbbridge;

interface IUsbBridgeCallback {
    void onState(int state, String detail);
    void onInboundData(in byte[] data);
}
