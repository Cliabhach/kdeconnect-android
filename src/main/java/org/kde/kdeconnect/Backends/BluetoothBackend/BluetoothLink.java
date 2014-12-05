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
import java.lang.reflect.Method;
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
	/**
	 * Represents the built-in method to create an RFComm socket with a given channel
	 */
	private static Method createRfcommSocket;

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
		synchronized (APP_UUID) {
			try {

				BluetoothSocket candidateLocalSocket = btSocket;
				boolean success = (btSocket != null && isConnectedICS(btSocket));
				int tries = 1;
				int nextChannel = 0;
				while (!success) {
					try {
						if (tries < 3)
							candidateLocalSocket = otherDevice.createRfcommSocketToServiceRecord(APP_UUID);
						else {
							nextChannel = (int) (Math.random() * 28) + 2; // Smallest channel we can use is 2 - trying 0 or 1 will always fail.
							if (createRfcommSocket == null)
								createRfcommSocket = BluetoothDevice.class.getDeclaredMethod("createRfcommSocket", Integer.TYPE);
							candidateLocalSocket = (BluetoothSocket) createRfcommSocket.invoke(otherDevice, nextChannel);
						}
						candidateLocalSocket.connect();
						success = true;
						Log.i("BluetoothLink", "Success connecting to " + otherDevice + " on try " + tries + " over channel " + nextChannel);
					} catch (Exception e) {
						Log.e("BluetoothLink", "Exception opening serversocket to " + otherDevice + " on try " + tries + " over channel " + nextChannel + "; " + e.getMessage());
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
		}
	}

	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
	private boolean isConnectedICS(BluetoothSocket socket) {
		return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) && socket.isConnected();
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
			InputStream inSocket = null;

				// Turn off discovery for better performance.
				btAdapter.cancelDiscovery();

				/*if (!isConnectedICS(localSocket)) {
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
				String temp = null;

				if (payloadStream != null) {

					Log.e("BluetoothLink", "Beginning to send payload");
					while ((bytesRead = payloadStream.read(buffer)) != -1) {
						Log.i("BL: ok",""+bytesRead);
						outSocket.write(buffer, 0, bytesRead);
					}
					Log.e("BluetoothLink", "Finished sending payload");

					inSocket = localSocket.getInputStream();

					while ((bytesRead = inSocket.read(buffer)) != -1) {
						Log.i("BL: ok", "" + bytesRead);
						temp = new String(buffer, 0, bytesRead);
					}
				} else {
					temp = "received";
				}

				// This confirms that the payload went through all right.
				if (temp != null && temp.equals("received")) {

					// Time to send the actual package. Use UTF-8 for encoding to bytes.
					InputStream serializedStream = new ByteArrayInputStream(serialized.getBytes("UTF-8"));
					Log.e("BluetoothLink","Beginning to send package");
					while ((bytesRead = serializedStream.read(buffer)) != -1) {
						Log.i("BL: ok",""+bytesRead);
						outSocket.write(buffer, 0, bytesRead);
						outSocket.flush();
					}
					Log.e("BluetoothLink","Finished sending package");

				} else {
					Log.e("BluetoothLink", "Payload not received at the other end.");
				}

			} catch(Exception e) {
				Log.e("BluetoothLink: PAPRunnable", "Exception with payload upload socket", e);
			} finally {
				if (outSocket != null) {
					try { outSocket.close(); } catch(Exception e) { }
				}
				if (inSocket != null) {
					try { inSocket.close(); } catch(Exception e) { }
				}
			}
		}
	}

	public String getBluetoothAddress() {
		return otherDevice.getAddress();
	}
}
