/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lemariva.androidthings.ble.sensortag;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.preference.PreferenceManager;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.lemariva.androidthings.ble.common.HCIDefines;
import com.lemariva.androidthings.ble.common.BleDeviceInfo;
import com.lemariva.androidthings.ble.common.BluetoothLeService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class MainActivity extends ViewPagerActivity {
	private static final String TAG = MainActivity.class.getSimpleName();

	/* Local UI */
	private TextView mLocalTimeView;
	/* Bluetooth API */
	private BluetoothManager mBluetoothManager;
	private BluetoothGattServer mBluetoothGattServer;
	private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
	/* Collection of notification subscribers */
	private Set<BluetoothDevice> mRegisteredDevices = new HashSet<>();

	// URLs
	private static final Uri URL_FORUM = Uri
			.parse("http://e2e.ti.com/support/low_power_rf/default.aspx?DCMP=hpa_hpa_community&HQS=NotApplicable+OT+lprf-forum");
	private static final Uri URL_STHOME = Uri
			.parse("http://www.ti.com/ww/en/wireless_connectivity/sensortag/index.shtml?INTC=SensorTagGatt&HQS=sensortag");

	// Requests to other activities
	private static final int REQ_ENABLE_BT = 0;
	private static final int REQ_DEVICE_ACT = 1;

	// GUI
	private static MainActivity mThis = null;
	private ScanView mScanView;
	private Intent mDeviceIntent;
	private static final int STATUS_DURATION = 5;

	// BLE management
	private boolean mBtAdapterEnabled = false;
	private boolean mBleSupported = true;
	private boolean mScanning = false;
	private int mNumDevs = 0;
	private int mConnIndex = NO_DEVICE;
	private List<BleDeviceInfo> mDeviceInfoList;

	private BluetoothAdapter mBtAdapter = null;
	private BluetoothDevice mBluetoothDevice = null;
	private BluetoothLeService mBluetoothLeService = null;

	private IntentFilter mFilter;
	private String[] mDeviceFilter = null;

	// Housekeeping
	private static final int NO_DEVICE = -1;
	private boolean mInitialised = false;
	SharedPreferences prefs = null;

	public MainActivity() {
		mThis = this;
		mResourceFragmentPager = R.layout.fragment_pager;
		mResourceIdPager = R.id.pager;
	}


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		prefs = PreferenceManager.getDefaultSharedPreferences(this);


		// Initialize device list container and device filter
		mDeviceInfoList = new ArrayList<BleDeviceInfo>();
		Resources res = getResources();
		mDeviceFilter = res.getStringArray(R.array.device_filter);

		// Create the fragments and add them to the view pager and tabs
		mScanView = new ScanView();
		mSectionsPagerAdapter.addSection(mScanView, "BLE Device List");

		// Register the BroadcastReceiver
		mFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
		mFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
		mFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);


		//startScan();

	}

	/*
	@Override
	protected void onStart() {
		super.onStart();
		// Register for system clock events
		IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.ACTION_TIME_TICK);
		filter.addAction(Intent.ACTION_TIME_CHANGED);
		filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
		registerReceiver(mTimeReceiver, filter);
	}

	@Override
	protected void onStop() {
		super.onStop();
		unregisterReceiver(mTimeReceiver);
	}

	*/

	@Override
	public void onDestroy() {
		// Log.e(TAG,"onDestroy");
		super.onDestroy();

		BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
		if (bluetoothAdapter.isEnabled()) {

		}

		unregisterReceiver(mBluetoothReceiver);

		mBtAdapter = null;

		// Clear cache
		File cache = getCacheDir();
		String path = cache.getPath();
		try {
			Runtime.getRuntime().exec(String.format("rm -rf %s", path));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * Verify the level of Bluetooth support provided by the hardware.
	 * @param bluetoothAdapter System {@link BluetoothAdapter}.
	 * @return true if Bluetooth is properly supported, false otherwise.
	 */
	private boolean checkBluetoothSupport(BluetoothAdapter bluetoothAdapter) {

		if (bluetoothAdapter == null) {
			Log.w(TAG, "Bluetooth is not supported");
			return false;
		}

		if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
			Log.w(TAG, "Bluetooth LE is not supported");
			return false;
		}

		return true;
	}

	/**
	 * Listens for system time changes and triggers a notification to
	 * Bluetooth subscribers.
	 */
	private BroadcastReceiver mTimeReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			byte adjustReason;
			switch (intent.getAction()) {
				case Intent.ACTION_TIME_CHANGED:
					adjustReason = TimeProfile.ADJUST_MANUAL;
					break;
				case Intent.ACTION_TIMEZONE_CHANGED:
					adjustReason = TimeProfile.ADJUST_TIMEZONE;
					break;
				default:
				case Intent.ACTION_TIME_TICK:
					adjustReason = TimeProfile.ADJUST_NONE;
					break;
			}
			long now = System.currentTimeMillis();
			notifyRegisteredDevices(now, adjustReason);
			//updateLocalUi(now);
		}
	};

/*
	private BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);

			switch (state) {
				case BluetoothAdapter.STATE_ON:
					startAdvertising();
					startServer();
					break;
				case BluetoothAdapter.STATE_OFF:
					stopServer();
					stopAdvertising();
					break;
				default:
					// Do nothing
			}
		}
	};
*/


	/**
	 * Begin advertising over Bluetooth that this device is connectable
	 * and supports the Current Time Service.
	 */
	private void startAdvertising() {
		BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
		mBluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
		if (mBluetoothLeAdvertiser == null) {
			Log.w(TAG, "Failed to create advertiser");
			return;
		}

		AdvertiseSettings settings = new AdvertiseSettings.Builder()
				.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
				.setConnectable(true)
				.setTimeout(0)
				.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
				.build();

		AdvertiseData data = new AdvertiseData.Builder()
				.setIncludeDeviceName(true)
				.setIncludeTxPowerLevel(false)
				.addServiceUuid(new ParcelUuid(TimeProfile.TIME_SERVICE))
				.build();

		mBluetoothLeAdvertiser
				.startAdvertising(settings, data, mAdvertiseCallback);
	}

	/**
	 * Stop Bluetooth advertisements.
	 */
	private void stopAdvertising() {
		if (mBluetoothLeAdvertiser == null) return;

		mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
	}

	/**
	 * Initialize the GATT server instance with the services/characteristics
	 * from the Time Profile.
	 */
	private void startServer() {
		mBluetoothGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
		if (mBluetoothGattServer == null) {
			Log.w(TAG, "Unable to create GATT server");
			return;
		}

		mBluetoothGattServer.addService(TimeProfile.createTimeService());

		// Initialize the local UI
		updateLocalUi(System.currentTimeMillis());
	}

	/**
	 * Shut down the GATT server.
	 */
	private void stopServer() {
		if (mBluetoothGattServer == null) return;

		mBluetoothGattServer.close();
	}

	/**
	 * Callback to receive information about the advertisement process.
	 */
	private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
		@Override
		public void onStartSuccess(AdvertiseSettings settingsInEffect) {
			Log.i(TAG, "LE Advertise Started.");
		}

		@Override
		public void onStartFailure(int errorCode) {
			Log.w(TAG, "LE Advertise Failed: "+errorCode);
		}
	};

	/**
	 * Send a time service notification to any devices that are subscribed
	 * to the characteristic.
	 */
	private void notifyRegisteredDevices(long timestamp, byte adjustReason) {
		if (mRegisteredDevices.isEmpty()) {
			Log.i(TAG, "No subscribers registered");
			return;
		}
		byte[] exactTime = TimeProfile.getExactTime(timestamp, adjustReason);

		Log.i(TAG, "Sending update to " + mRegisteredDevices.size() + " subscribers");
		for (BluetoothDevice device : mRegisteredDevices) {
			BluetoothGattCharacteristic timeCharacteristic = mBluetoothGattServer
					.getService(TimeProfile.TIME_SERVICE)
					.getCharacteristic(TimeProfile.CURRENT_TIME);
			timeCharacteristic.setValue(exactTime);
			mBluetoothGattServer.notifyCharacteristicChanged(device, timeCharacteristic, false);
		}
	}

	/**
	 * Update graphical UI on devices that support it with the current time.
	 */
	private void updateLocalUi(long timestamp) {
		Date date = new Date(timestamp);
		String displayDate = DateFormat.getMediumDateFormat(this).format(date)
				+ "\n"
				+ DateFormat.getTimeFormat(this).format(date);
		mLocalTimeView.setText(displayDate);
	}

	/**
	 * Callback to handle incoming requests to the GATT server.
	 * All read/write requests for characteristics and descriptors are handled here.
	 */
	private BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {

		@Override
		public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
			if (newState == BluetoothProfile.STATE_CONNECTED) {
				Log.i(TAG, "BluetoothDevice CONNECTED: " + device);
			} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
				Log.i(TAG, "BluetoothDevice DISCONNECTED: " + device);
				//Remove device from any active subscriptions
				mRegisteredDevices.remove(device);
			}
		}

		@Override
		public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
												BluetoothGattCharacteristic characteristic) {
			long now = System.currentTimeMillis();
			if (TimeProfile.CURRENT_TIME.equals(characteristic.getUuid())) {
				Log.i(TAG, "Read CurrentTime");
				mBluetoothGattServer.sendResponse(device,
						requestId,
						BluetoothGatt.GATT_SUCCESS,
						0,
						TimeProfile.getExactTime(now, TimeProfile.ADJUST_NONE));
			} else if (TimeProfile.LOCAL_TIME_INFO.equals(characteristic.getUuid())) {
				Log.i(TAG, "Read LocalTimeInfo");
				mBluetoothGattServer.sendResponse(device,
						requestId,
						BluetoothGatt.GATT_SUCCESS,
						0,
						TimeProfile.getLocalTimeInfo(now));
			} else {
				// Invalid characteristic
				Log.w(TAG, "Invalid Characteristic Read: " + characteristic.getUuid());
				mBluetoothGattServer.sendResponse(device,
						requestId,
						BluetoothGatt.GATT_FAILURE,
						0,
						null);
			}
		}

		@Override
		public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
											BluetoothGattDescriptor descriptor) {
			if (TimeProfile.CLIENT_CONFIG.equals(descriptor.getUuid())) {
				Log.d(TAG, "Config descriptor read");
				byte[] returnValue;
				if (mRegisteredDevices.contains(device)) {
					returnValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
				} else {
					returnValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
				}
				mBluetoothGattServer.sendResponse(device,
						requestId,
						BluetoothGatt.GATT_FAILURE,
						0,
						returnValue);
			} else {
				Log.w(TAG, "Unknown descriptor read request");
				mBluetoothGattServer.sendResponse(device,
						requestId,
						BluetoothGatt.GATT_FAILURE,
						0,
						null);
			}
		}

		@Override
		public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
											 BluetoothGattDescriptor descriptor,
											 boolean preparedWrite, boolean responseNeeded,
											 int offset, byte[] value) {
			if (TimeProfile.CLIENT_CONFIG.equals(descriptor.getUuid())) {
				if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
					Log.d(TAG, "Subscribe device to notifications: " + device);
					mRegisteredDevices.add(device);
				} else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
					Log.d(TAG, "Unsubscribe device from notifications: " + device);
					mRegisteredDevices.remove(device);
				}

				if (responseNeeded) {
					mBluetoothGattServer.sendResponse(device,
							requestId,
							BluetoothGatt.GATT_SUCCESS,
							0,
							null);
				}
			} else {
				Log.w(TAG, "Unknown descriptor write request");
				if (responseNeeded) {
					mBluetoothGattServer.sendResponse(device,
							requestId,
							BluetoothGatt.GATT_FAILURE,
							0,
							null);
				}
			}
		}
	};

	void onScanViewReady(View view) {


		// License popup on first run
		if (prefs.getBoolean("firstrun", true)) {
			//onLicense();
			prefs.edit().putBoolean("firstrun", false).commit();
		}

		if (!mInitialised) {
			// Broadcast receiver
            mBluetoothLeService = BluetoothLeService.getInstance();
			mBluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
			mBtAdapter = mBluetoothManager.getAdapter();
			// We can't continue without proper Bluetooth support
			if (!checkBluetoothSupport(mBtAdapter)) {
				finish();
			}

			// Register for system Bluetooth events
			registerReceiver(mBluetoothReceiver, mFilter);

			mBtAdapterEnabled = mBtAdapter.isEnabled();

			if (mBtAdapterEnabled) {

				Log.d(TAG, "Bluetooth enabled...starting services");
			} else {
				// Request BT adapter to be turned on
				Log.d(TAG, "Bluetooth is currently disabled...enabling");
				mBtAdapter.enable();
				Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
				startActivityForResult(enableIntent, REQ_ENABLE_BT);
			}

			mInitialised = true;
		} else {
			mScanView.notifyDataSetChanged();
		}
		// Initial state of widgets
		updateGuiState();
	};


	// ////////////////////////////////////////////////////////////////////////////////////////////////
	/**
	 * Broadcasted actions from Bluetooth adapter and BluetoothLeService
	 * Listens for Bluetooth adapter events
	 */
	private BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();
			if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
				// Bluetooth adapter state change
				switch (mBtAdapter.getState()) {
					case BluetoothAdapter.STATE_ON:
						mConnIndex = NO_DEVICE;
						//startBluetoothLeService();
						break;
					case BluetoothAdapter.STATE_OFF:
						Toast.makeText(context, R.string.app_closing, Toast.LENGTH_LONG)
								.show();
						finish();
						break;
					default:
						// Log.w(TAG, "Action STATE CHANGED not processed ");
						break;
				}

				updateGuiState();
			} else if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
				// GATT connect
				int status = intent.getIntExtra(BluetoothLeService.EXTRA_STATUS,
						BluetoothGatt.GATT_FAILURE);
				if (status == BluetoothGatt.GATT_SUCCESS) {
					setBusy(false);
					startDeviceActivity();
				} else
					setError("Connect failed. Status: " + status);
			} else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
				// GATT disconnect
				int status = intent.getIntExtra(BluetoothLeService.EXTRA_STATUS,
						BluetoothGatt.GATT_FAILURE);
                stopDeviceActivity();
				if (status == BluetoothGatt.GATT_SUCCESS) {
					setBusy(false);
					mScanView.setStatus(mBluetoothDevice.getName() + " disconnected",
							STATUS_DURATION);
				} else {
					setError("Disconnect Status: " + HCIDefines.hciErrorCodeStrings.get(status));
				}
				mConnIndex = NO_DEVICE;
				mBluetoothLeService.close();
			} else {
				// Log.w(TAG,"Unknown action: " + action);
			}

		}
	};



    private void startDeviceActivity() {
        mDeviceIntent = new Intent(this, DeviceActivity.class);
        mDeviceIntent.putExtra(DeviceActivity.EXTRA_DEVICE, mBluetoothDevice);
        startActivityForResult(mDeviceIntent, REQ_DEVICE_ACT);
    }

    private void stopDeviceActivity() {
        finishActivity(REQ_DEVICE_ACT);
    }



	public void onScanTimeout() {
		runOnUiThread(new Runnable() {
			public void run() {
				stopServer();
			}
		});
	}

	public void onConnectTimeout() {
		runOnUiThread(new Runnable() {
			public void run() {
				setError("Connection timed out");
			}
		});
		if (mConnIndex != NO_DEVICE) {
			mBluetoothLeService.disconnect(mBluetoothDevice.getAddress());
			mConnIndex = NO_DEVICE;
		}
	}

	// ////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// GUI methods
	//
	public void updateGuiState() {
		boolean mBtEnabled = mBtAdapter.isEnabled();

		if (mBtEnabled) {
			if (mScanning) {
				// BLE Host connected
				if (mConnIndex != NO_DEVICE) {
					String txt = mBluetoothDevice.getName() + " connected";
					mScanView.setStatus(txt);
				} else {
					mScanView.setStatus(mNumDevs + " devices");
				}
			}
		} else {
			mDeviceInfoList.clear();
			mScanView.notifyDataSetChanged();
		}
	}

	private void setBusy(boolean f) {
		mScanView.setBusy(f);
	}

	void setError(String txt) {
		mScanView.setError(txt);
		//CustomToast.middleBottom(this, "Turning BT adapter off and on again may fix Android BLE stack problems");
	}

	List<BleDeviceInfo> getDeviceInfoList() {
		return mDeviceInfoList;
	}


	public void onDeviceClick(final int pos) {

		if (mScanning)
			stopServer();

		setBusy(true);
		mBluetoothDevice = mDeviceInfoList.get(pos).getBluetoothDevice();
		if (mConnIndex == NO_DEVICE) {
			mScanView.setStatus("Connecting");
			mConnIndex = pos;
			onConnect();
		} else {
			mScanView.setStatus("Disconnecting");
			if (mConnIndex != NO_DEVICE) {
				mBluetoothLeService.disconnect(mBluetoothDevice.getAddress());
			}
		}
	};

	void onConnect() {
		if (mNumDevs > 0) {

			int connState = mBluetoothManager.getConnectionState(mBluetoothDevice,
					BluetoothGatt.GATT);

			switch (connState) {
				case BluetoothGatt.STATE_CONNECTED:
					mBluetoothLeService.disconnect(null);
					break;
				case BluetoothGatt.STATE_DISCONNECTED:
					boolean ok = mBluetoothLeService.connect(mBluetoothDevice.getAddress());
					if (!ok) {
						setError("Connect failed");
					}
					break;
				default:
					setError("Device busy (connecting/disconnecting)");
					break;
			}
		}
	};

	public void onBtnScan(View view) {
		if (mScanning) {
			stopScan();
		} else {
			startScan();
		}
	};

	private void startScan() {
		// Start device discovery
		if (mBleSupported) {
			mNumDevs = 0;
			mDeviceInfoList.clear();
			mScanView.notifyDataSetChanged();
			scanLeDevice(true);
			mScanView.updateGui(mScanning);
			if (!mScanning) {
				setError("Device discovery start failed");
				setBusy(false);
			}
		} else {
			setError("BLE not supported on this device");
		}

	};

	private void stopScan() {
		mScanning = false;
		mScanView.updateGui(false);
		scanLeDevice(false);
	};


	private boolean scanLeDevice(boolean enable) {
        if (enable) {
            mBtAdapter.getBluetoothLeScanner().startScan(mLeScanCallback);
            mScanning = true;
        } else {
            mScanning = false;
            mBtAdapter.getBluetoothLeScanner().stopScan(mLeScanCallback);
        }
        return mScanning;
    }

	private ScanCallback mLeScanCallback = new ScanCallback() {
		@Override
		public void onScanResult(int callbackType, ScanResult result) {
			final BluetoothDevice device = result.getDevice();
			final int rssi = result.getRssi();
			runOnUiThread(new Runnable() {
				public void run() {
					// Filter devices
					if (checkDeviceFilter(device.getAddress())) {
						if (!deviceInfoExists(device.getAddress())) {
							// New device
							BleDeviceInfo deviceInfo = createDeviceInfo(device, rssi);
							addDevice(deviceInfo);
						} else {
							// Already in list, update RSSI info
							BleDeviceInfo deviceInfo = findDeviceInfo(device);
							deviceInfo.updateRssi(rssi);
							mScanView.notifyDataSetChanged();
						}
					}
				}

			});
		}
	};

	boolean checkDeviceFilter(String deviceAddr) {
		if (deviceAddr == null)
			return false;

		int n = mDeviceFilter.length;
		if (n > 0) {
			boolean found = false;
			for (int i = 0; i < n && !found; i++) {
				found = deviceAddr.contains(mDeviceFilter[i]);
			}
			return found;
		} else
			// Allow all devices if the device filter is empty
			return true;
	};

	private void addDevice(BleDeviceInfo device) {
		mNumDevs++;
		mDeviceInfoList.add(device);
		mScanView.notifyDataSetChanged();
		if (mNumDevs > 1)
			mScanView.setStatus(mNumDevs + " devices");
		else
			mScanView.setStatus("1 device");
	}

	private boolean deviceInfoExists(String address) {
		for (int i = 0; i < mDeviceInfoList.size(); i++) {
			if (mDeviceInfoList.get(i).getBluetoothDevice().getAddress()
					.equals(address)) {
				return true;
			}
		}
		return false;
	}

	private BleDeviceInfo findDeviceInfo(BluetoothDevice device) {
		for (int i = 0; i < mDeviceInfoList.size(); i++) {
			if (mDeviceInfoList.get(i).getBluetoothDevice().getAddress()
					.equals(device.getAddress())) {
				return mDeviceInfoList.get(i);
			}
		}
		return null;
	}

	private BleDeviceInfo createDeviceInfo(BluetoothDevice device, int rssi) {
		BleDeviceInfo deviceInfo = new BleDeviceInfo(device, rssi);

		return deviceInfo;
	}

}
