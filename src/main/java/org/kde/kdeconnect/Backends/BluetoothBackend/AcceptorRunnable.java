package org.kde.kdeconnect.Backends.BluetoothBackend;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import org.kde.kdeconnect.BackgroundService;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.NetworkPackage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Runnable for receiving data from other BluetoothDevices.
 * @see org.kde.kdeconnect.Backends.BluetoothBackend.SendingRunnable
 *
 */
class AcceptorRunnable implements Runnable {
	private final BluetoothLinkProvider blp;
	private BluetoothSocket socket;
	private Context context;

	public AcceptorRunnable(BluetoothLinkProvider blp, Context context) {
		this.blp = blp;
		this.context = context;
	}

	public void runAndReset(BluetoothSocket socket) {
		synchronized (this) {
			this.socket = socket;
			run();
			this.socket = null;
		}
	}

	@Override
	public void run() {

		if (socket == null) return;

		final BluetoothDevice remoteDevice = socket.getRemoteDevice();

		Log.i("BluetoothLinkProvider", "Device found us: " + remoteDevice.toString());

		// Avoid performance issues - don't discover while transmitting or receiving data
		blp.getBtAdapter().cancelDiscovery();

		// Assume we're connected and get a NetworkPackage.

		try {
			// First, pull in the stream.
			InputStream stream = socket.getInputStream();

			// Payload comes first...
			StringBuilder received = receiveFromStream(stream);
			String serialized = received.toString();
			String payload = null;
			int splitPoint = received.indexOf("Bluetooth Nonce");
			if (splitPoint > 0) {
				// There are both a payload and a package here
				payload = received.substring(0, splitPoint);
				serialized = received.substring(splitPoint + "Bluetooth Nonce".length());
			}

			// We now have a StringBuilder with a serialized NetworkPackage - time to unserialize it.
			final NetworkPackage np = NetworkPackage.unserialize(serialized);
			if (payload != null)
				np.setPayload(payload.getBytes());

			if (np.getType().equals(NetworkPackage.PACKAGE_TYPE_IDENTITY)) {
				Log.w("BluetoothLinkProvider", "New Identity package");
				String myId = NetworkPackage.createIdentityPackage(context).getString("deviceId");
				if (np.getString("deviceId").equals(myId)) {
					// It has our ID! We can't do anything with it.
					return;
				}

				// Let's pass this new package on to be handled properly.
				BackgroundService.RunCommand(context, new BackgroundService.InstanceCallback() {
					@Override
					public void onServiceStart(BackgroundService service) {
						Device device = service.getDevice(np.getString("deviceId"));
						if (device == null) {
							device = service.getDevice(remoteDevice.getAddress());
							if (device == null)
								Log.i("BluetoothLinkProvider", "Device has only been connected over bluetooth");
						}
						blp.onNewDeviceAvailable(np, remoteDevice, device);
					}
				});
			} else {
				Log.w("BluetoothLinkProvider", "New non-identity package");
				// This belongs to an existing link.
				// First figure out which:
				BluetoothLink desiredLink = blp.getKnownDevice(remoteDevice.getAddress());
				if (desiredLink == null) {
					BackgroundService.RunCommand(context, new BackgroundService.InstanceCallback() {
						@Override
						public void onServiceStart(BackgroundService service) {
							Device device = service.getDevice(np.getString("deviceId"));
							if (device == null) {
								device = service.getDevice(remoteDevice.getAddress());
								if (device == null)
									Log.w("BluetoothLinkProvider", "Device thinks we've connected before, but there's no record of that.");
								return;
							}
							BluetoothLink chosenLink = (BluetoothLink) device.getLink();
							chosenLink.handleIncomingPackage(np);
						}
					});
					return;
				}
				desiredLink.handleIncomingPackage(np);
			}
		} catch (Exception e) {
			Log.d("AcceptorTask", "Problem with acceptor: " + e.getMessage(), e);
		}
	}

	private StringBuilder receiveFromStream(InputStream stream) throws Exception {
		StringBuilder received = new StringBuilder();
		byte[] buffer = new byte[4096];
		int bytesRead;
		Log.e("BluetoothLinkProvider", "Beginning to receive package and payload");
		while ((bytesRead = readFromStream(stream, buffer)) != -1) {
			Log.i("ok",""+bytesRead);
			String temp = new String(buffer, 0, bytesRead);
			received.append(temp);
		}
		Log.e("BluetoothLinkProvider", "Finished receiving package and payload");
		if (received.length() == 0) {
			throw new Exception("0 bytes of data received");
		} else {
			Log.v("BluetoothLinkProvider", String.valueOf(received));
		}
		return received;
	}

	/**
	 * Convenience method to swallow IOExceptions raised by {@link android.bluetooth.BluetoothSocket#read(byte[], int, int)}
	 * (Javadoc won't link directly to the method because it is package-private.)
	 * @param stream Stream to read from
	 * @param buffer buffer to use while reading
	 * @return number of bytes read, or -1 on failure
	 */
	@SuppressWarnings("JavadocReference")
	private int readFromStream(InputStream stream, byte[] buffer) {
		int bytesRead;
		try {
			bytesRead = stream.read(buffer);
		} catch (IOException ioe) {
			bytesRead = -1;
		}
		return bytesRead;
	}
}
