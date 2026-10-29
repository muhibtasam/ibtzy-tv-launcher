package io.ibtzy.tvlauncher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;

import java.util.List;

/**
 * The real state of Wi-Fi and Bluetooth, for the Wi-Fi and Bluetooth top buttons. Read only: since Android 10 an app
 * can't switch Wi-Fi on or off (and Bluetooth needs a system permission), so the buttons open the system settings.
 * When the TV doesn't tell us a state (no adapter, a permission refused), the button shows its plain name and icon:
 * never a made-up "Connected".
 */
final class Radios {
    private Radios() {}

    static final int UNKNOWN = 0, OFF = 1, ON = 2, CONNECTED = 3;

    /** OFF, ON (Wi-Fi on but no Wi-Fi network up: Ethernet or nothing), CONNECTED, or UNKNOWN. */
    static int wifi(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return UNKNOWN;
            int s = wm.getWifiState();
            if (s == WifiManager.WIFI_STATE_DISABLED || s == WifiManager.WIFI_STATE_DISABLING) return OFF;
            if (s != WifiManager.WIFI_STATE_ENABLED) return UNKNOWN;   // enabling, or the TV doesn't know
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return ON;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return CONNECTED;
            }
            return ON;
        } catch (RuntimeException e) {   // SecurityException on a TV that refuses ACCESS_WIFI_STATE
            AppLog.w("Wi-Fi state unavailable: " + e);
            return UNKNOWN;
        }
    }

    /** OFF, ON, or UNKNOWN (no Bluetooth on this TV, or the state is refused). */
    static int bluetooth(Context c) {
        try {
            BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
            if (a == null) return UNKNOWN;
            int s = a.getState();
            if (s == BluetoothAdapter.STATE_ON) return ON;
            if (s == BluetoothAdapter.STATE_OFF || s == BluetoothAdapter.STATE_TURNING_OFF) return OFF;
            return UNKNOWN;
        } catch (RuntimeException e) {
            AppLog.w("Bluetooth state unavailable: " + e);
            return UNKNOWN;
        }
    }

    /** Puts the current state into the Wi-Fi and Bluetooth buttons of `items` (their icon). */
    static void apply(Context c, List<HomeApps.Item> items) {
        for (HomeApps.Item it : items) {
            if (HomeApps.TOP_WIFI.equals(it.key)) show(it, wifi(c));
            else if (HomeApps.TOP_BLUETOOTH.equals(it.key)) show(it, bluetooth(c));
        }
    }

    /** Icon for a state (separate from apply() so it can be tested without a TV). */
    static void show(HomeApps.Item it, int state) {
        boolean wifi = HomeApps.TOP_WIFI.equals(it.key);
        String name = wifi ? Ui.tr("Wi-Fi") : Ui.tr("Bluetooth");
        // The state is the icon only (crossed out when off): no status text on the home screen.
        it.symbol = state == OFF ? (wifi ? "wifi_off" : "bluetooth_disabled") : (wifi ? "wifi" : "bluetooth");
        it.label = name;
    }
}
