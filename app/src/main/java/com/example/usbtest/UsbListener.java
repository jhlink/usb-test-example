package com.example.usbtest;

import android.hardware.usb.UsbDevice;

public interface UsbListener {
    void insertUsb(UsbDevice device);
}
