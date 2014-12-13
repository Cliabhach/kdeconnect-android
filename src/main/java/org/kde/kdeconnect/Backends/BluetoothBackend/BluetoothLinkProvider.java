package org.kde.kdeconnect.Backends.BluetoothBackend;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.kde.kdeconnect.Backends.BaseLinkProvider;
import org.kde.kdeconnect.BackgroundService;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.NetworkPackage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class BluetoothLinkProvider extends BaseLinkProvider {
	private final Context context;
	private final BluetoothAdapter btAdapter;

	/**
	 * A Set of all the devices the system considers paired (by the bluetooth protocol).
	 * <br/>
	 * <br/>
	 * Updated as devices are bonded or unbound by {@link org.kde.kdeconnect.KdeConnectBroadcastReceiver}.
	 * <br/>
	 * <br/>
	 * Performs an analogous role to {@link org.kde.kdeconnect.Device#links} (which only
	 * maintains Kde Connect links, while this is more like a set of all <em>potential</em> links).
	 * <br/>
	 * @see #addBondedDevice(android.bluetooth.BluetoothDevice)
	 * @see #removeBondedDevice(android.bluetooth.BluetoothDevice)
	 */
	private static Set<BluetoothDevice> bondedDevices = Collections.synchronizedSet(new HashSet<BluetoothDevice>());
	private final HashMap<String, BluetoothLink> knownComputers = new HashMap<String, BluetoothLink>();
	/**
	 * A Runnable specially for receiving incoming connections.
	 * @see org.kde.kdeconnect.Backends.BluetoothBackend.BluetoothLinkProvider.AcceptorRunnable#run()
	 */
	private AcceptorRunnable acceptor;

	private static boolean shouldAccept = true;

	public BluetoothLinkProvider(Context context) {
		this.context = context;

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2)
			btAdapter = BluetoothAdapter.getDefaultAdapter();
		else {
			BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
			if (manager != null)
				btAdapter = manager.getAdapter();
			else
				btAdapter = null;
		}

		if (btAdapter == null) {
			Log.i("BluetoothLinkProvider", "No bluetooth device available! Bluetooth connections will not work.");
		}
	}

	public BluetoothAdapter getBtAdapter() {
		return btAdapter;
	}

	/**
	 * Add a new link to our list of devices.
	 * @param identityPackage a package with identity details from the other device
	 * @param link the BluetoothLink object representing (the outgoing component of)
	 *                the link between these devices.
	 */
	private void addLink(NetworkPackage identityPackage, BluetoothLink link) {
		String bluetoothId = link.getBluetoothAddress();
		Log.i("BluetoothLinkProvider", "addLink to " + bluetoothId);
		BluetoothLink oldLink = knownComputers.get(bluetoothId);
		if (oldLink == link) {
			Log.e("BluetoothLinkProvider", "oldLink == link. This should not happen!");
			return;
		}
		knownComputers.put(bluetoothId, link);
		connectionAccepted(identityPackage, link);
		if (oldLink != null) {
			Log.i("BluetoothLinkProvider", "Removing old connection to same device");
			oldLink.releaseResources();
			connectionLost(oldLink);
		}
	}

	@Override
	public void onStart() {
		// Get a set of bluetooth-paired devices. They might not be connected through KDE Connect
		bondedDevices.addAll(btAdapter.getBondedDevices());

		/*if (shouldScanForMoreDevices()) {
			// Start discovery of more devices - requires BLUETOOTH_ADMIN permission.
			boolean success = btAdapter.startDiscovery();

			if (!success) {
				Log.e("BluetoothLinkProvider", "Couldn't start discovery scan.");
			}
		}*/

		// figure out which bonded devices are known (?)
		if (bondedDevices != null) {
			Log.i("BluetoothLinkProvider", "Bonded devices found.");
			startSendingIdentityPackagesToBondedDevices();
		}

		shouldAccept = true;
		if (acceptor == null) {
			acceptor = new AcceptorRunnable(this, context);
		}

		Thread acceptorThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (shouldAccept) {
					Log.i("BluetoothLinkProvider: acceptor", "About to start listening for connections.");
					try {
						if (!btAdapter.isEnabled()) {
							shouldAccept = false;
							break;
						}
						synchronized (BluetoothLink.APP_UUID) { // TODO: Solve the resource starvation problem.
							Log.d("BluetoothLinkProvider: acceptorThread", "Bluetooth is on, ready to listen.");
							BluetoothServerSocket serverSocket = btAdapter.listenUsingRfcommWithServiceRecord("KDE Connect", BluetoothLink.APP_UUID);
							BluetoothSocket socket = serverSocket.accept();
							Log.d("BluetoothLinkProvider: acceptorThread", "Stopped listening - we've got a connection.");
							serverSocket.close();
							acceptor.runAndReset(socket);
						}
					} catch (Exception e) {
						Log.i("BluetoothLinkProvider: acceptorThread", "Problem: ", e);
						break;
					}
				}
			}
		});
		acceptorThread.start();
	}

	/**
	 * Using the current list of bonded devices (from {@link android.bluetooth.BluetoothAdapter#getBondedDevices()}),
	 * this method creates and starts a series of Threads. Each thread tries to connect to a given
	 * BluetoothDevice and send an {@linkplain org.kde.kdeconnect.NetworkPackage#createIdentityPackage(android.content.Context)
	 * Identity Package} using a new {@link org.kde.kdeconnect.Backends.BluetoothBackend.BluetoothLink}.
	 * <br/>
	 * <br/>
	 * The Thread will try 3 times to connect. If that fails, then it gives up and spits an error
	 * into the log.
	 * @see org.kde.kdeconnect.Backends.BluetoothBackend.SendingRunnable
	 */
	private void startSendingIdentityPackagesToBondedDevices() {
		for (BluetoothDevice bd : bondedDevices) {
			Thread sendingThread = new Thread(new SendingRunnable(this, bd, context));
			sendingThread.start();
		}
	}

	private boolean shouldScanForMoreDevices() {
		return true;
	}

	public void onNewDeviceAvailable(NetworkPackage np, BluetoothDevice bd, Device device) {
		if (np == null)
			return;
		if (device != null)
			device.setBluetoothAddress(bd.getAddress());
		onNewDeviceAvailable(np, bd);
	}

	private void onNewDeviceAvailable(NetworkPackage np, BluetoothDevice bd) {
		addLink(np, new BluetoothLink(np.getString("deviceId"), this, getBtAdapter(), bd));
	}

	@Override
	public void onStop() {
		shouldAccept = false;
	}

	@Override
	public void onNetworkChange() {
		onStop();
		onStart();
	}

	@Override
	public String getName() {
		return "BluetoothLinkProvider";
	}

	/** For use by {@link org.kde.kdeconnect.KdeConnectBroadcastReceiver} */
	public static void addBondedDevice(BluetoothDevice bd) {
		bondedDevices.add(bd);
	}


	public static void removeBondedDevice(BluetoothDevice bd) {
		bondedDevices.remove(bd);
	}

	private class AcceptorRunnable implements Runnable {
		private final BluetoothLinkProvider blp;
		private BluetoothSocket socket;
		private Context context;

		public AcceptorRunnable(BluetoothLinkProvider blp, Context context) {
			this.blp = blp;
			this.context = context;
		}

		public void runAndReset(BluetoothSocket socket) {
			this.socket = socket;
			synchronized (this) {
				run();
			}
			this.socket = null;
		}

		@Override
		public void run() {

			if (socket == null) return;

			final BluetoothDevice remoteDevice = socket.getRemoteDevice();

			Log.i("BluetoothLinkProvider", "Device found us: " + remoteDevice.toString());

			// Avoid performance issues - don't discover while transmitting or receiving data
			blp.getBtAdapter().cancelDiscovery();

			// Assume we're connected and get a NetworkPackage.
			// TODO: Add isConnected() check or similar here or in AcceptorTask#runAndReset()

			try {
				// First, pull in the stream.
				InputStream stream = socket.getInputStream();

				// Payload comes first...
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
				String serialized = received.toString();
				String payload = null;
				int splitPoint = received.indexOf("Bluetooth Nonce");
				if (splitPoint > 0) {
					// There are both a payload and a package here
					payload = received.substring(0, splitPoint);
					serialized = received.substring(splitPoint + "Bluetooth Nonce".length());
				}
				final String thePayload = payload;

				// We now have a StringBuilder with a serialized NetworkPackage - time to unserialize it.
				final NetworkPackage np = NetworkPackage.unserialize(serialized);
				if (thePayload != null)
					np.setPayload(thePayload.getBytes());

				if (np.getType().equals(NetworkPackage.PACKAGE_TYPE_IDENTITY)) {
					Log.w("BluetoothLinkProvider", "New Identity package");
					String myId = NetworkPackage.createIdentityPackage(context).getString("deviceId");
					if (np.getString("deviceId").equals(myId)) {
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
					BluetoothLink desiredLink = blp.knownComputers.get(remoteDevice.getAddress());
					if (desiredLink == null) {
						Log.w("AcceptorTask", "Link couldn't be found. Known links are " + blp.knownComputers.keySet().toString() + " while this device is " + remoteDevice.getAddress());
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
}
