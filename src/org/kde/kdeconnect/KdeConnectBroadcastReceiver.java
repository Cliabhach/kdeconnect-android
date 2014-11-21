/*
 * Copyright 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License or (at your option) version 3 or any later version
 * accepted by the membership of KDE e.V. (or its successor approved
 * by the membership of KDE e.V.), which shall act as a proxy
 * defined in Section 14 of version 3 of the license.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
*/

package org.kde.kdeconnect;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.kde.kdeconnect.Backends.BluetoothBackend.BluetoothLinkProvider;

public class KdeConnectBroadcastReceiver extends BroadcastReceiver
{


    public void onReceive(Context context, Intent intent) {

        //Log.e("KdeConnect", "Broadcast event: "+intent.getAction());

        String action = intent.getAction();

        switch(action) {
            case Intent.ACTION_PACKAGE_REPLACED:
                Log.i("KdeConnect", "UpdateReceiver");
                if (!intent.getData().getSchemeSpecificPart().equals(context.getPackageName())) {
                    Log.i("KdeConnect", "Ignoring, it's not me!");
                    return;
                }
                BackgroundService.RunCommand(context, new BackgroundService.InstanceCallback() {
                    @Override
                    public void onServiceStart(BackgroundService service) {

                    }
                });
                break;
            case Intent.ACTION_BOOT_COMPLETED:
                Log.i("KdeConnect", "KdeConnectBroadcastReceiver");
                BackgroundService.RunCommand(context, new BackgroundService.InstanceCallback() {
                    @Override
                    public void onServiceStart(BackgroundService service) {

                    }
                });
                break;
            case WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION:
            case WifiManager.WIFI_STATE_CHANGED_ACTION:
            case ConnectivityManager.CONNECTIVITY_ACTION:
                Log.i("KdeConnect", "Connection state changed, trying to connect");
                BackgroundService.RunCommand(context, new BackgroundService.InstanceCallback() {
                    @Override
                    public void onServiceStart(BackgroundService service) {
                        service.onNetworkChange();
                    }
                });
                break;
            case Intent.ACTION_SCREEN_ON:
                BackgroundService.RunCommand(context, new BackgroundService.InstanceCallback() {
                    @Override
                    public void onServiceStart(BackgroundService service) {
                        service.onNetworkChange();
                    }
                });
                break;
            case BluetoothAdapter.ACTION_STATE_CHANGED:
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0) != BluetoothAdapter.STATE_ON)
			return;
		Log.i("KdeConnect", "Bluetooth is now available.");
		break;
            case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                final BluetoothDevice bd = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
		final int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, 0);

		if (bondState != BluetoothDevice.BOND_BONDED) {
			String details = bondState == BluetoothDevice.BOND_BONDING ? " yet" : " at all.";
			Log.d("KdeConnect", "Bluetooth device not ready" + details);
			BackgroundService.RunCommand(context, new BackgroundService.InstanceCallback() {
				@Override
				public void onServiceStart(BackgroundService service) {
					BluetoothLinkProvider.removeBondedDevice(bd);
				}
			});
		} else {
			Log.i("KdeConnect", "New Bluetooth device found.");
			BackgroundService.RunCommand(context, new BackgroundService.InstanceCallback() {
				@Override
				public void onServiceStart(BackgroundService service) {
					BluetoothLinkProvider.addBondedDevice(bd);
					final String targetAddress = bd.getAddress();
					Device targetDevice = null;
					for (Device device : service.getDevices().values())
						if (targetAddress.equals(device.getBluetoothAddress())) {
							// We've identified this device before.
							targetDevice = device;
							break;
						}
					// TODO: What do we do with targetDevice? Send it an identity package?
					if (targetDevice == null) {
						// This is a new device.
						return;
					}
				}
			});
		}
		break;
            default:
                Log.i("BroadcastReceiver", "Ignoring broadcast event: "+intent.getAction());
                break;
    }



}
