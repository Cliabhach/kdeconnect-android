package org.kde.kdeconnect.Backends.BluetoothBackend;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import org.kde.kdeconnect.NetworkPackage;

/**
* Runnable that tries to connect to a given BluetoothDevice and send it an identity NetworkPackage.
*/
class SendingRunnable implements Runnable {
	private static final Object lock = new Object();

	private BluetoothLinkProvider bluetoothLinkProvider;
	private final BluetoothDevice btDev;
	private final Context context;
	private int tries;

	public SendingRunnable(BluetoothLinkProvider bluetoothLinkProvider, BluetoothDevice device, Context context) {
		this.bluetoothLinkProvider = bluetoothLinkProvider;
		btDev = device;
		this.context = context;
	}

	@Override
	public void run() {
		synchronized (lock) {
			tries = 0;
			BluetoothSocket bluetoothSocket = obtainBluetoothSocket();

			if (bluetoothSocket == null || tries > 2) return;

			Log.w("BLP: ", "Connection established to device MAC=" + btDev.getAddress());

			try {
				BluetoothLink bl = new BluetoothLink(bluetoothLinkProvider, bluetoothLinkProvider.getBtAdapter(), btDev, bluetoothSocket);
				bl.sendPackage(NetworkPackage.createIdentityPackage(context));
			} catch (Exception e) {
				Log.e("BLP: ", "Bonding to " + btDev.getAddress() + " failed.", e);
			}
		}
	}

	private BluetoothSocket obtainBluetoothSocket() {
		BluetoothSocket bluetoothSocket = null;
		boolean success = false;
		while (!success) {
			try {

				if (btDev.getBondState() != BluetoothDevice.BOND_BONDED)
					break;
				bluetoothSocket = btDev.createRfcommSocketToServiceRecord(BluetoothLink.APP_UUID);
				tries++;
				Log.w("BLP: ", "Bonding to device MAC=" + btDev.getAddress());
				bluetoothSocket.connect();
				Log.w("BLP: ", "Bond succeeded");
				success = true;
			} catch (Exception e) {
				Log.w("BLP: ", "Bonding failed");
				if (tries > 2) {
					Log.e("BLP: ", "Giving up on device MAC=" + btDev.getAddress());
					break;
				}
			}
		}
		return bluetoothSocket;
	}
}
