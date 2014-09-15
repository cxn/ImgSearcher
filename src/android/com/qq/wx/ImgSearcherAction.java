package com.qq.wx;

import android.os.Handler;
import android.os.Message;
import android.util.Log;
import com.megster.cordova.BluetoothSerialService;
import com.qq.wx.img.imgsearcher.ImgListener;
import com.qq.wx.img.imgsearcher.ImgResult;
import com.qq.wx.img.imgsearcher.ImgSearcher;
import com.qq.wx.img.imgsearcher.ImgSearcherState;
import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Set;

// kludgy imports to support 2.9 and 3.0 due to package changes
// import org.apache.cordova.CordovaArgs;
// import org.apache.cordova.CordovaPlugin;
// import org.apache.cordova.CallbackContext;
// import org.apache.cordova.PluginResult;
// import org.apache.cordova.LOG;

/**
 * PhoneGap Plugin for Serial Communication over Bluetooth
 */
public class ImgSearcherAction extends CordovaPlugin implements ImgListener  {

    // actions
    private static final String INIT = "init";
    private static final String START = "start";
    private static final String CANCEL = "cancel";
    private static final String DESTROY = "destroy";

    /**
     * 官网申请的KEY值
     * The APPID from official website
     */
    private static final String screKey = "wxb65fb184ad802b3a";

    int mInitSucc = 0;

    private final int IMG = 13;
    private int mType = IMG;

    // callbacks
    private CallbackContext connectCallback;
    private CallbackContext dataAvailableCallback;
    private CallbackContext discoveryCallback;
    private CallbackContext pairingCallback;

    // Debugging
    private static final String TAG = "BluetoothSerial";
    private static final boolean D = true;

    // Message types sent from the BluetoothSerialService Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;
    public static final int MESSAGE_DISCOVERY_STARTED = 6;
    public static final int MESSAGE_DISCOVERY_FINISHED = 7;
    public static final int MESSAGE_DEVICE_FOUND = 8;
    public static final int MESSAGE_DEVICE_BONDED = 9;

    // Key names received from the BluetoothChatService Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";
    public static final String DATA_DEVICE_NAME = "name";
    public static final String DATA_DEVICE_ADDRESS = "address";

    public static int ERR_UNKNOWN = 404;

    private boolean _wasDiscoveryCanceled;

    StringBuffer buffer = new StringBuffer();
    private String delimiter;

    @Override
    public boolean execute(String action, CordovaArgs args, CallbackContext callbackContext) throws JSONException {

        LOG.d(TAG, "action = " + action);

        boolean validAction = true;

        if (action.equals(INIT)) {

            preInitImg(callbackContext);

        } else if (action.equals(START)) {
            startImgSearching(args, callbackContext);

        } else if (action.equals(CANCEL)) {

            // see Android docs about Insecure RFCOMM http://goo.gl/1mFjZY
            boolean secure = false;
            callbackContext.success();

        } else if (action.equals(DESTROY)) {

            connectCallback = null;
            callbackContext.success();

        }else {

            validAction = false;

        }

        return validAction;
    }


    private void preInitImg(CallbackContext callbackContext) throws JSONException {
        JSONArray deviceList = new JSONArray();
        ImgSearcher.shareInstance().setListener(this);
        mInitSucc = ImgSearcher.shareInstance().init(this.cordova.getActivity(), screKey);
        if (mInitSucc != 0) {
            callbackContext.success(mInitSucc);
        }else{
            callbackContext.error(mInitSucc);
        }
    }

    private void startImgSearching(CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        byte[] img = args.getArrayBuffer(0);
        if (mInitSucc != 0) {
            mInitSucc = ImgSearcher.shareInstance().init(this.cordova.getActivity(), screKey);
        }
        if (mInitSucc != 0) {
            callbackContext.error(mInitSucc);
        }

        int ret = ImgSearcher.shareInstance().start(img, mType);
        if (0 == ret) {
            callbackContext.success();
        }else {
            callbackContext.error(-1);
        }

    }

    // The Handler that gets information back from the BluetoothSerialService
    // Original code used handler for the because it was talking to the UI.
    // Consider replacing with normal callbacks
    private final Handler mHandler = new Handler() {

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_READ:
                    buffer.append((String) msg.obj);

                    if (dataAvailableCallback != null) {
                        sendDataToSubscriber();
                    }
                    break;
                case MESSAGE_STATE_CHANGE:

                    if (D) Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
                    switch (msg.arg1) {
                        case BluetoothSerialService.STATE_CONNECTED:
                            Log.i(TAG, "BluetoothSerialService.STATE_CONNECTED");
                            notifyConnectionSuccess();
                            break;
                        case BluetoothSerialService.STATE_CONNECTING:
                            Log.i(TAG, "BluetoothSerialService.STATE_CONNECTING");
                            break;
                        case BluetoothSerialService.STATE_LISTEN:
                            Log.i(TAG, "BluetoothSerialService.STATE_LISTEN");
                            break;
                        case BluetoothSerialService.STATE_NONE:
                            Log.i(TAG, "BluetoothSerialService.STATE_NONE");
                            break;
                    }
                    break;
                case MESSAGE_WRITE:
                    //  byte[] writeBuf = (byte[]) msg.obj;
                    //  String writeMessage = new String(writeBuf);
                    //  Log.i(TAG, "Wrote: " + writeMessage);
                    break;
                case MESSAGE_DEVICE_NAME:
                    Log.i(TAG, msg.getData().getString(DEVICE_NAME));
                    break;
                case MESSAGE_TOAST:
                    String message = msg.getData().getString(TOAST);
                    notifyConnectionLost(message);
                    break;
                case MESSAGE_DISCOVERY_STARTED:
                    _wasDiscoveryCanceled = false;
                    break;
                case MESSAGE_DISCOVERY_FINISHED:
                    if (!_wasDiscoveryCanceled) {
                        if (discoveryCallback != null) {
                            PluginResult result = new PluginResult(PluginResult.Status.OK, false);
                            discoveryCallback.sendPluginResult(result);
                            discoveryCallback = null;
                        }
                    }
                    break;
                case MESSAGE_DEVICE_FOUND:
                    try {
                        String name = msg.getData().getString(DATA_DEVICE_NAME);
                        String address = msg.getData().getString(DATA_DEVICE_ADDRESS);

                        JSONObject device = new JSONObject();
                        device.put("name", name);
                        device.put("address", address);

                        // Send one device at a time, keeping callback to be used again
                        if (discoveryCallback != null) {
                            PluginResult result = new PluginResult(PluginResult.Status.OK, device);
                            result.setKeepCallback(true);
                            discoveryCallback.sendPluginResult(result);
                        } else {
                            Log.e(TAG, "CallbackContext for discovery doesn't exist.");
                        }
                    } catch (JSONException e) {
                        if (discoveryCallback != null) {
                            ImgSearcherAction.this.error(discoveryCallback,
                                    e.getMessage(),
                                    ERR_UNKNOWN
                            );
                            discoveryCallback = null;
                        }
                    }

                    break;

                case MESSAGE_DEVICE_BONDED:

                    try {
                        String name = msg.getData().getString(DATA_DEVICE_NAME);
                        String address = msg.getData().getString(DATA_DEVICE_ADDRESS);

                        JSONObject bondedDevice = new JSONObject();
                        bondedDevice.put("name", name);
                        bondedDevice.put("address", address);

                        if (pairingCallback != null) {
                            pairingCallback.success(bondedDevice);
                            pairingCallback = null;
                        } else {
                            Log.e(TAG, "CallbackContext for pairing doesn't exist.");
                        }
                    } catch (Exception e) {
                        if (pairingCallback != null) {
                            ImgSearcherAction.this.error(pairingCallback,
                                    e.getMessage(), 500
                            );
                            pairingCallback = null;
                        }
                    }

                    break;

            }
        }
    };

    private void notifyConnectionLost(String error) {
        if (connectCallback != null) {
            connectCallback.error(error);
            connectCallback = null;
        }
        if (dataAvailableCallback != null) {
            dataAvailableCallback.error(error);
        }
    }

    private void notifyConnectionSuccess() {
        if (connectCallback != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK);
            result.setKeepCallback(true);
            connectCallback.sendPluginResult(result);
        }
    }

    private void sendDataToSubscriber() {
        String data = readUntil(delimiter);
        if (data != null && data.length() > 0) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, data);
            result.setKeepCallback(true);
            dataAvailableCallback.sendPluginResult(result);

            sendDataToSubscriber();
        }
    }

    private int available() {
        return buffer.length();
    }

    private String read() {
        int length = buffer.length();
        String data = buffer.substring(0, length);
        buffer.delete(0, length);
        return data;
    }

    private String readUntil(String c) {
        String data = "";
        int index = buffer.indexOf(c, 0);
        if (index > -1) {
            data = buffer.substring(0, index + c.length());
            buffer.delete(0, index + c.length());
        }
        return data.toString();
    }

    private void error(CallbackContext ctx, String msg, int code) {
        try {
            JSONObject result = new JSONObject();
            result.put("message", msg);
            result.put("code", code);

            ctx.error(result);
        } catch (Exception e) {
            Log.e(TAG, "Error with... error raising, " + e.getMessage());
        }
    }

    @Override
    public void onGetResult(ImgResult result) {

    }

    @Override
    public void onGetError(int i) {

    }

    @Override
    public void onGetState(ImgSearcherState imgSearcherState) {

    }
}
