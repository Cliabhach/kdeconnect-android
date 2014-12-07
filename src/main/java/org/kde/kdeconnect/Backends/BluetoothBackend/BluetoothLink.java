package org.kde.kdeconnect.Backends.BluetoothBackend;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import android.util.Log;

import org.kde.kdeconnect.Backends.BaseLink;
import org.kde.kdeconnect.Backends.BaseLinkProvider;
import org.kde.kdeconnect.NetworkPackage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.PublicKey;
import java.util.UUID;

public class BluetoothLink extends BaseLink {

	static final UUID APP_UUID = UUID.fromString("69fda304-da91-477b-b988-28d49ca99cf5"); //FIXME: use a constant, recognizable UUID.
	private final BluetoothDevice otherDevice;
	private BluetoothSocket btSocket;
	/**
	 * Our adapter for using the Bluetooth capabilities of this device.
	 * Use it to create and end connections.
	 */
	protected BluetoothAdapter btAdapter;

	protected BluetoothLink(String deviceId, BaseLinkProvider linkProvider, BluetoothAdapter btAdapter, BluetoothDevice otherDevice) {
		super(deviceId, linkProvider);
		this.otherDevice = otherDevice;
		this.btAdapter = btAdapter;
		Log.w("BluetoothLink", "UUID = " + APP_UUID);
	}

	public BluetoothLink(BluetoothLinkProvider bluetoothLinkProvider, BluetoothAdapter btAdapter, BluetoothDevice bluetoothDevice, BluetoothSocket bluetoothSocket) {
		super(bluetoothDevice.getAddress(), bluetoothLinkProvider);
		otherDevice = bluetoothDevice;
		this.btAdapter = btAdapter;
		btSocket = bluetoothSocket;
	}

	boolean releaseResources() {
		return true;
	}

	@Override
	public boolean sendPackage(NetworkPackage np) {
		try {
            Thread thread;
            if (np.hasPayload()) {
                thread = sendPayloadAndPackage(np.getPayload(), np);
                if (thread == null) return false;
            } else {
				thread = sendPackageOnly(np);
			}

            if (thread != null) {
                thread.join(); //Wait for thread to finish
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("BluetoothLink", "sendPackage exception");
            return false;
        }

	}

	private Thread sendPayloadAndPackage(final InputStream payloadStream, NetworkPackage np) {
		//synchronized (APP_UUID) {
			try {

				BluetoothSocket candidateLocalSocket = btSocket;
				boolean success = (btSocket != null && checkConnectedICS(btSocket));
				int tries = 1;
				while (!success) {
					try {
						candidateLocalSocket = otherDevice.createRfcommSocketToServiceRecord(APP_UUID);
						candidateLocalSocket.connect();
						success = true;
						Log.i("BluetoothLink", "Success connecting to " + otherDevice + " on try " + tries);
					} catch (Exception e) {
						Log.e("BluetoothLink", "Exception opening serversocket to " + otherDevice + " on try " + tries + "; " + e.getMessage());
						tries++;
						// Allow for 6 tries - channels go 1 to 30
						if (tries > 6) {
							Log.e("BluetoothLink", "Giving up on this connection");
							return null;
						}
					}
				}

				final BluetoothSocket localSocket = candidateLocalSocket;
				final String serialized = np.serialize();
				Log.i("BL: ", "About to start thread for sending NetworkPackage to " + otherDevice.getAddress());
				Thread thread = new Thread(new PayloadAndPackageRunnable(localSocket, payloadStream, serialized));
				thread.start();

				return thread;

			} catch (Exception e) {

				e.printStackTrace();
				Log.e("BluetoothLink", "Exception with payload upload socket");

				return null;
			}
		//}
	}

	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
	private boolean checkConnectedICS(BluetoothSocket socket) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
			if (socket.isConnected()) {
				return true;
			}
			try {
				socket.connect();
			} catch (IOException e) {
				return false;
			}
		}
		return true;
	}

	private Thread sendPackageOnly(NetworkPackage np) {
		return sendPayloadAndPackage(null, np);
	}

	@Override
	public boolean sendPackageEncrypted(NetworkPackage np, PublicKey key) {
		try {
			Thread thread;
            if (np.hasPayload()) {
                thread = sendPayloadAndPackage(np.getPayload(), np.encrypt(key));
                if (thread == null) return false;
            } else {

				np = np.encrypt(key);

				thread = sendPackageOnly(np);
			}

			if (thread != null) {
				thread.join(); //Wait for thread to finish
			}
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			Log.e("BluetoothLink", "sendPackageEncrypted exception");
			return false;
		}
	}

	public void handleIncomingPackage(NetworkPackage np) {

		if (np.getType().equals(NetworkPackage.PACKAGE_TYPE_ENCRYPTED)) {

			try {
				np = np.decrypt(privateKey);
			} catch(Exception e) {
				e.printStackTrace();
				Log.e("onPackageReceived","Exception reading the key needed to decrypt the package");
			}

		}

		if (np.hasPayloadTransferInfo()) {

			try {
				BluetoothSocket socket = otherDevice.createRfcommSocketToServiceRecord(APP_UUID);
				socket.connect();
				np.setPayload(socket.getInputStream(), np.getPayloadSize());
			} catch (Exception e) {
				e.printStackTrace();
				Log.e("BluetoothLink", "Exception connecting to payload remote socket");
			}

		}

		packageReceived(np);
	}

	private class PayloadAndPackageRunnable implements Runnable {
		private final BluetoothSocket localSocket;
		private final InputStream payloadStream;
		private final String serialized;

		public PayloadAndPackageRunnable(BluetoothSocket localSocket, InputStream payloadStream, String serialized) {
			this.localSocket = localSocket;
			this.payloadStream = payloadStream;
			this.serialized = serialized;
		}

		@Override
		public void run() {
			//TODO: Timeout when waiting for a connection and close the socket
			OutputStream outSocket = null;

			// Turn off discovery for better performance.
				btAdapter.cancelDiscovery();

				/*if (!checkConnectedICS(localSocket)) {
					Log.i("BluetoothLink: PAPRunnable", "Trying to connect to " + getBluetoothAddress());

					try {
						localSocket.connect();
					} catch (IOException ioe) {
						Log.w("BluetoothLink: PAPRunnable", "Initial connection failed.");
						return;
					}
				}*/

				Log.i("BluetoothLink: PAPRunnable", "Connection succeeded to " + getBluetoothAddress());

			try {
				outSocket = localSocket.getOutputStream();

				byte[] buffer = new byte[4096];
				int bytesRead;

				if (payloadStream != null) {

					Log.e("BluetoothLink", "Beginning to send payload");
					while ((bytesRead = payloadStream.read(buffer)) != -1) {
						Log.i("BL: ok",""+bytesRead);
						outSocket.write(buffer, 0, bytesRead);
					}
					Log.e("BluetoothLink", "Finished sending payload");
					outSocket.write("Bluetooth Nonce".getBytes("UTF-8"));
					Log.e("BluetoothLink", "Finished sending nonce");
				}

				// Time to send the actual package. Use UTF-8 for encoding to bytes.
				InputStream serializedStream = new ByteArrayInputStream(serialized.getBytes("UTF-8"));
				Log.e("BluetoothLink","Beginning to send package");
				while ((bytesRead = serializedStream.read(buffer)) != -1) {
					Log.i("BL: ok",""+bytesRead);
					outSocket.write(buffer, 0, bytesRead);
					outSocket.flush();
				}
				Log.e("BluetoothLink","Finished sending package");

			} catch(Exception e) {
				Log.e("BluetoothLink: PAPRunnable", "Exception with payload upload socket", e);
			} finally {
				if (outSocket != null) {
					try { outSocket.close(); } catch(Exception e) { }
				}
			}
		}
	}

	public String getBluetoothAddress() {
		return otherDevice.getAddress();
	}
}
