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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
	private VoidFutureTask listener;

	private static boolean flag = true;

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
			return;
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
		String deviceId = identityPackage.getString("deviceId");
		Log.i("BluetoothLinkProvider", "addLink to " + deviceId);
		BluetoothLink oldLink = knownComputers.get(deviceId);
		if (oldLink == link) {
			Log.e("KDEConnect", "BluetoothLinkProvider: oldLink == link. This should not happen!");
			return;
		}
		knownComputers.put(deviceId, link);
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

		flag = true;
		if (listener == null) {
			listener = new VoidFutureTask(this);
		}

		Thread listenerBackgroundThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (flag) {
					try {
						Thread.sleep(20000);
					} catch (InterruptedException e) {}
					Log.i("BluetoothLinkProvider: listener: ", "About to start listening for connections.");
					listener.runAndReset();
				}
			}
		});
		listenerBackgroundThread.start();
	}

	private void startSendingIdentityPackagesToBondedDevices() {
		for (BluetoothDevice bd : bondedDevices) {
			final BluetoothDevice btDev = bd;
			Thread sendingThread = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						if (btDev.getBondState() != BluetoothDevice.BOND_BONDED)
							return;
						BluetoothSocket bluetoothSocket = btDev.createRfcommSocketToServiceRecord(BluetoothLink.APP_UUID);
						Log.w("BLP: ", "Bonding to device MAC=" + btDev.getAddress());
						bluetoothSocket.connect();
						Log.w("BLP: ", "Connection established to device MAC=" + btDev.getAddress());

						BluetoothLink bl = new BluetoothLink(BluetoothLinkProvider.this, btAdapter, btDev, bluetoothSocket);
						bl.sendPackage(NetworkPackage.createIdentityPackage(context));
						bluetoothSocket.close();
					} catch (Exception e) {
						Log.e("BLP: ", "Bonding to " + btDev.getAddress() + " failed.", e);
					}
				}
			});
			sendingThread.start();
		}
	}

	private boolean shouldScanForMoreDevices() {
		return true;
	}

	public void onNewDeviceAvailable(NetworkPackage np, BluetoothDevice bd, Device device) {
		device.setBluetoothAddress(bd.getAddress());
		onNewDeviceAvailable(np, bd);
	}

	private void onNewDeviceAvailable(NetworkPackage np, BluetoothDevice bd) {
		if (np == null)
			np = NetworkPackage.createIdentityPackage(context);
		addLink(np, new BluetoothLink(np.getString("deviceId"), this, getBtAdapter(), bd));
	}

	@Override
	public void onStop() {
		flag = false;
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

	private class VoidFutureTask extends FutureTask<Void> {
		public VoidFutureTask(final BluetoothLinkProvider blp) {
			super(new Runnable() {
				@Override
				public void run() {
					try {
						if (!blp.btAdapter.isEnabled()) {
							flag = false;
							return;
						}
						Log.d("BLP: listenerBackgroundThread: ", "Bluetooth is on, ready to listen.");
						BluetoothServerSocket serverSocket = blp.btAdapter.listenUsingRfcommWithServiceRecord("KDE Connect", BluetoothLink.APP_UUID);
						BluetoothSocket socket = serverSocket.accept();

						final BluetoothDevice remoteDevice = socket.getRemoteDevice();
						serverSocket.close();

						Log.i("BluetoothLinkProvider", "Device found: " + remoteDevice.toString());

						// Avoid performance issues - don't discover while transmitting or receiving data
						BluetoothLinkProvider.this.btAdapter.cancelDiscovery();
						socket.connect();

						// We're connected! Let's get a NetworkPackage.

						// First, pull in the stream.
						InputStream stream = socket.getInputStream();

						// Payload comes first...
						StringBuilder payload = new StringBuilder();
						byte[] buffer = new byte[4096];
						int bytesRead;
						Log.e("BluetoothLinkProvider", "Beginning to receive payload");
						while ((bytesRead = stream.read(buffer)) != -1) {
							Log.i("ok",""+bytesRead);
							String temp = new String(buffer, 0, bytesRead);
							payload.append(temp);
						}
						Log.e("BluetoothLinkProvider", "Finished receiving payload");
						final String thePayload = payload.toString();

						/*// Clear up resources
						socket.close();
						stream.close();

						// Start a new connection! This might be to a different device (I think)!
						socket = serverSocket.accept();

						btAdapter.cancelDiscovery();

						if (socket.getRemoteDevice() != remoteDevice)
							Log.w("BluetoothLinkProvider", "Different device connected now - be warned.");*/

						OutputStream confirmation = socket.getOutputStream();
						InputStream confirmationMessage = new ByteArrayInputStream("received".getBytes("UTF-8"));

						Log.i("BluetoothLink", "Sending confirmation of receipt");
						while ((bytesRead = confirmationMessage.read(buffer)) != -1) {
							confirmation.write(buffer, 0, bytesRead);
						}

						// Time to get the serialized package now.
						StringBuilder serialized = new StringBuilder();
						buffer = new byte[4096];
						Log.e("BluetoothLink", "Beginning to receive package");
						while ((bytesRead = stream.read(buffer)) != -1) {
							//Log.i("ok",""+bytesRead);
							String temp = new String(buffer, 0, bytesRead);
							serialized.append(temp);
						}
						Log.e("BluetoothLink", "Finished receiving package");

						// We now have a StringBuilder with a serialized NetworkPackage - time to unserialize it.
						final NetworkPackage np = NetworkPackage.unserialize(serialized.toString());
						np.setPayload(thePayload.getBytes());

						if (np.getType().equals(NetworkPackage.PACKAGE_TYPE_IDENTITY)) {
							String myId = NetworkPackage.createIdentityPackage(BluetoothLinkProvider.this.context).getString("deviceId");
							if (np.getString("deviceId").equals(myId)) {
								return;
							}

							// Let's pass this new package on to be handled properly.
							BackgroundService.RunCommand(blp.context, new BackgroundService.InstanceCallback() {
								@Override
								public void onServiceStart(BackgroundService service) {
									Device device = service.getDevice(np.getString("deviceId"));
									if (device == null) return;
									blp.onNewDeviceAvailable(np, remoteDevice, device);
								}
							});
						} else {
							// This belongs to an existing link.
							// First figure out which:
							BluetoothLink desiredLink = BluetoothLinkProvider.this.knownComputers.get(np.getString("deviceId"));
							desiredLink.handleIncomingPackage(np);
						}
					} catch (Exception e) {
						Log.d("VoidFuture: ", "Problem with listener: " + e.getMessage());
					}
				}
			}, null);
		}

		@Override
		public boolean runAndReset() {
			return super.runAndReset();
		}
	}
}
