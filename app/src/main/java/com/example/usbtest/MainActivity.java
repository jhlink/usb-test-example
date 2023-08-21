package com.example.usbtest;

import org.apache.commons.codec.binary.Hex;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Random;

public class MainActivity extends AppCompatActivity  implements  SerialInputOutputManager.Listener, UsbListener {


    private static final String TAG = "SERIAL";
    private static final long WRITE_INTERVAL = 1;



    private static final int WRITE_WAIT_MILLIS = 2500;
    private static final int READ_WAIT_MILLIS = 2500;

    private int  portNum, baudRate;

    private BroadcastReceiver mUsbReceiver;

    private BroadcastReceiver broadcastReceiver;
    private Handler mainLooper;
    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private boolean connected = false;

    private HandlerThread writeThread;
    private HandlerThread readThread;
    private Handler writeHandler;
    private Handler readHandler;

    private UsbManager usbManager;
    private UsbDevice curDevice;
    private boolean lastWrite;

    private void registerReceiver() {
        mUsbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null || "".equals(action)) return;
                switch (action) {
                    case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                        if (curDevice == null ) {
                            curDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                            if (curDevice != null) {
                                insertUsb(curDevice);
                                status("Device Inserted");
                            }
                        }
                        break;
                    case UsbManager.ACTION_USB_DEVICE_DETACHED:
                        if (curDevice != null) {
                            curDevice = null;
                            removeUsb(curDevice);
                            status("Device removed");
                        }
                        break;
                }
            }
        };

        IntentFilter usbDeviceStateFilter = new IntentFilter();
        usbDeviceStateFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbDeviceStateFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        getApplicationContext().registerReceiver(mUsbReceiver, usbDeviceStateFilter);
    }

    public void removeUsb(UsbDevice device) {
        disconnect();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SerialInputOutputManager.DEBUG = true;

        mainLooper = new Handler(Looper.getMainLooper());

        writeThread = new HandlerThread("SerialWriteThread");
        writeThread.start();
        writeHandler = new Handler(writeThread.getLooper());

        readThread = new HandlerThread("SerialReadThread");
        readThread.start();
        readHandler = new Handler(readThread.getLooper());
        registerReceiver();
    }

    @Override
    public void insertUsb(UsbDevice device) {
        initialize();
    }

    public void initialize() {
        // Initialize USB Manager
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Find and open the serial port
        usbSerialPort = findSerialPort();
        status("Opening");
        openSerialPort(usbSerialPort);

        if (connected) {

            // Create dedicated threads for write and read actions

            // Start writing and reading data
            startWriting();
        }
    }

    private UsbSerialPort findSerialPort() {
        UsbSerialProber usbCustomProber = CustomProber.getCustomProber();
        UsbSerialDriver driver = null;

        for (UsbDevice device : usbManager.getDeviceList().values()) {
            driver = usbCustomProber.probeDevice(device);
            if (driver != null) {
                for (int port = 0; port < driver.getPorts().size(); port++) {
                    portNum = port;
                }
                break;
            }
        }

        if(driver == null) {
            status("connection failed: no driver for device");
            return null;
        }

        if(driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return null;
        }


        return driver.getPorts().get(0);
    }

    private void openSerialPort(UsbSerialPort port) {
        // Remember to request permission if necessary

        // Open the connection and set the parameters
        UsbDeviceConnection connection = usbManager.openDevice(port.getDriver().getDevice());
        if (connection != null) {
            try {
                port.open(connection);
                port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                usbIoManager = new SerialInputOutputManager(port, this);
                usbIoManager.start();
                status("connected");
                connected = true;
            } catch (IOException e) {
                e.printStackTrace();
                status("connection failed: " + e.getMessage());
                disconnect();
            }
        } else {
            if (!usbManager.hasPermission(port.getDevice())) {
                status(" permission deneid");
            } else {
                status("open faled");
            }
        }
    }


    @Override
    public void onResume() {
        super.onResume();
        registerReceiver();
    }

    @Override
    public void onPause() {
        if(connected) {
            status("disconnected");
            disconnect();
        }
        if (mUsbReceiver != null) {
            unregisterReceiver(mUsbReceiver);
            mUsbReceiver = null;
        }
        super.onPause();
    }

    /*
     * Serial
     */
    @Override
    public void onNewData(byte[] data) {
        readHandler.post(new Runnable() {
            @Override
            public void run() {
                if (data.length > 0) {
                    byte[] hexData = new Hex().encode(data);
                    Log.d(TAG+"_RCV", hexData.toString());
                }
            }
        });
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(() -> {
            status("connection lost: " + e.getMessage());
            disconnect();
        });
    }

    private void startWriting() {
        writeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Generate random JSON data
                JSONObject jsonData = generateRandomJsonData();

                // Write the JSON data to the serial port
                try {
                    if (usbSerialPort != null) {
                        synchronized (usbSerialPort) {
                            String someString = jsonData.toString().concat("\\n");
                            usbSerialPort.write(someString.getBytes(), WRITE_WAIT_MILLIS);
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    status("noWrite");
                }

                // Schedule the next write action
                writeHandler.postDelayed(this, WRITE_INTERVAL);
            }
        }, WRITE_INTERVAL);
    }

    private JSONObject generateRandomJsonData() {
        Random random = new Random();
        JSONObject jsonData = new JSONObject();
        try {
            jsonData.put("sensor", "temperature");
            jsonData.put("value", random.nextDouble() * 100);
            jsonData.put("value1", random.nextDouble() * 100);
            jsonData.put("value2", random.nextDouble() * 100);
            jsonData.put("value3", random.nextDouble() * 100);
            jsonData.put("value4", random.nextDouble() * 100);
            jsonData.put("value5", random.nextDouble() * 100);
            jsonData.put("value6", random.nextDouble() * 100);
            jsonData.put("value7", random.nextDouble() * 100);
            jsonData.put("value8", random.nextDouble() * 100);
            jsonData.put("value9", random.nextDouble() * 100);
            jsonData.put("value10", random.nextDouble() * 100);
            jsonData.put("value11", random.nextDouble() * 100);
            jsonData.put("value12", random.nextDouble() * 100);
            jsonData.put("value13", random.nextDouble() * 100);
            jsonData.put("value14", random.nextDouble() * 100);
            jsonData.put("value15", random.nextDouble() * 100);
            jsonData.put("value16", random.nextDouble() * 100);
            jsonData.put("value17", random.nextDouble() * 100);
            jsonData.put("value18", random.nextDouble() * 100);
            jsonData.put("value19", random.nextDouble() * 100);
            jsonData.put("value20", random.nextDouble() * 100);
            jsonData.put("value21", random.nextDouble() * 100);
            jsonData.put("value22", random.nextDouble() * 100);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jsonData;
    }


    private void disconnect() {
        status("disconnecting");
        connected = false;
        if(usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {}
        usbSerialPort = null;
    }

    void status(String str) {
        Log.d(TAG+"_STATUS", str);
    }
}
