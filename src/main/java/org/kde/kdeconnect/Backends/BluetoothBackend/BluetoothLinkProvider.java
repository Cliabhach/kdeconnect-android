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
import java.util.concurrent.FutureTask;

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
	private AcceptorTask acceptor;

	private static boolean shouldAccept = true;
	private final Object lock = new Object();

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
			Log.e("KDEConnect", "BluetoothLinkProvider: oldLink == link. This should not happen!");
			return;
		}
		knownComputers.put(bluetoothId, link);
		connectionAccepted(identityPackage, link);
		if (oldLink != null) {
			Log.i("BluetoothLinkProvider","Removing old connection to same device");
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
			acceptor = new AcceptorTask(new AcceptorRunnable(null, this));
		}

		Thread acceptorThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (shouldAccept) {
					Log.i("BluetoothLinkProvider: acceptor: ", "About to start listening for connections.");
					try {
						if (!btAdapter.isEnabled()) {
							shouldAccept = false;
							break;
						}
						synchronized (BluetoothLink.APP_UUID) { // TODO: Solve the resource starvation problem.
							Log.d("BLP: listenerBackgroundThread", "Bluetooth is on, ready to listen.");
							BluetoothServerSocket serverSocket = btAdapter.listenUsingRfcommWithServiceRecord("KDE Connect", BluetoothLink.APP_UUID);
							BluetoothSocket socket = serverSocket.accept();
							Log.d("BLP: listenerBackgroundThread", "Stopped listening - we've got a connection.");
							serverSocket.close();
							acceptor.runAndReset(socket);
						}
					} catch (Exception e) {
						Log.i("BLP: listenerBackgroundThread", "Problem: ", e);
						break;
					}
				}
			}
		});
		acceptorThread.start();
	}

	private void startSendingIdentityPackagesToBondedDevices() {
		for (BluetoothDevice bd : bondedDevices) {
			final BluetoothDevice btDev = bd;
			Thread sendingThread = new Thread(new Runnable() {

				private int tries;

				@Override
				public void run() {
					synchronized (lock) {
						tries = 0;
						BluetoothSocket bluetoothSocket = obtainBluetoothSocket();

						if (bluetoothSocket == null || tries > 2) return;

						Log.w("BLP: ", "Connection established to device MAC=" + btDev.getAddress());

						try {
							BluetoothLink bl = new BluetoothLink(BluetoothLinkProvider.this, btAdapter, btDev, bluetoothSocket);
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
							Thread.sleep(500);
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
			});
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

	private class AcceptorTask extends FutureTask<Void> {
		BluetoothSocket socket;
		AcceptorRunnable runnable;

		public AcceptorTask(AcceptorRunnable runnable) {
			super(runnable, null);
			this.runnable = runnable;
			runnable.acceptorTask = this;
		}

		public boolean runAndReset(BluetoothSocket socket) {
			this.socket = socket;
			return super.runAndReset();
		}

	}

	private class AcceptorRunnable implements Runnable {
		private AcceptorTask acceptorTask;
		private final BluetoothLinkProvider blp;

		public AcceptorRunnable(AcceptorTask acceptorTask, BluetoothLinkProvider blp) {
			this.acceptorTask = acceptorTask;
			this.blp = blp;
		}

		@Override
		public void run() {

			if (acceptorTask == null || acceptorTask.socket == null) return;

			final BluetoothDevice remoteDevice = acceptorTask.socket.getRemoteDevice();

			Log.i("BluetoothLinkProvider", "Device found us: " + remoteDevice.toString());

			// Avoid performance issues - don't discover while transmitting or receiving data
			blp.btAdapter.cancelDiscovery();

			// Assume we're connected and get a NetworkPackage.
			// TODO: Add isConnected() check or similar here or in AcceptorTask#runAndReset()

			try {
				// First, pull in the stream.
				InputStream stream = acceptorTask.socket.getInputStream();

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
					String myId = NetworkPackage.createIdentityPackage(BluetoothLinkProvider.this.context).getString("deviceId");
					if (np.getString("deviceId").equals(myId)) {
						return;
					}

					// Let's pass this new package on to be handled properly.
					BackgroundService.RunCommand(blp.context, new BackgroundService.InstanceCallback() {
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
						BackgroundService.RunCommand(blp.context, new BackgroundService.InstanceCallback() {
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
