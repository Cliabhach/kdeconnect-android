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
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.NetworkPackage;

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
	 * @see AcceptorRunnable#run()
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

	/**
	 * Call this method upon receiving an identity package.
	 * @see org.kde.kdeconnect.NetworkPackage#createIdentityPackage(android.content.Context)
	 * @see #onNewDeviceAvailable(org.kde.kdeconnect.NetworkPackage, android.bluetooth.BluetoothDevice)
	 * @param np the received package
	 * @param bd the BluetoothDevice it came from
	 * @param device a Device object corresponding to bd. Will be null the first time we receive
	 *               a NetworkPackage from that BluetoothDevice.
	 */
	public void onNewDeviceAvailable(NetworkPackage np, BluetoothDevice bd, Device device) {
		if (np == null)
			return;
		if (device != null)
			device.setBluetoothAddress(bd.getAddress());
		onNewDeviceAvailable(np, bd);
	}

	/**
	 * Shim for call to {@link #addLink}. For use only by
	 * {@link #onNewDeviceAvailable(org.kde.kdeconnect.NetworkPackage,
	 * android.bluetooth.BluetoothDevice, org.kde.kdeconnect.Device)}.
	 * @param np a newly-received identity package
	 * @param bd the BluetoothDevice it came from
	 */
	private void onNewDeviceAvailable(NetworkPackage np, BluetoothDevice bd) {
		addLink(np, new BluetoothLink(np.getString("deviceId"), this, getBtAdapter(), bd));
	}

	/*package*/ BluetoothLink getKnownDevice(String remoteDeviceAddress) {
		return knownComputers.get(remoteDeviceAddress);
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

}
