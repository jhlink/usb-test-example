package com.hoho.android.usbserial.examples;

import android.hardware.usb.UsbDevice;

public interface UsbListener {
    void insertUsb(UsbDevice device);
}
