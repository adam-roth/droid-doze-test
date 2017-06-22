package au.com.suncoastpc.testapp;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.Date;

/**
 * Demonstrates that holding a wake and wifi lock while being on the battery optimization whitelist is
 * not sufficient to suppress Doze mode from occurring on Android 7.1.2 w/ the June 5, 2017 security patches
 * applied.
 *
 * Tested and verified on a Google Pixel.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TEST_FILE_DOWNLOAD = "http://aroth.no-ip.org/10MB.jpg";
    private static final int TEST_FILE_SIZE = 1024 * 1024 * 10;
    private static final int TARGET_BYTES_PER_SECOND = TEST_FILE_SIZE / (60 * 10);  //target completion of the download in ~10 minutes
    private static final int CONNECTION_TIMEOUT = 10000;

    protected TextView statusView;
    protected TextView timerView;

    private Context context;
    private PowerManager.WakeLock wakeLock = null;
    private WifiManager.WifiLock wifiLock = null;

    private long startTime = System.currentTimeMillis();
    private long stopTime = startTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        context = this.getApplicationContext();
        statusView = (TextView)findViewById(R.id.status);
        timerView = (TextView)findViewById(R.id.timer);

        Button acquireButton = (Button)findViewById(R.id.acquire_button);
        Button releaseButton = (Button)findViewById(R.id.release_button);

        acquireButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String packageName = context.getPackageName();
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (! pm.isIgnoringBatteryOptimizations(packageName)) {
                    //reguest that Doze mode be disabled
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + packageName));

                    context.startActivity(intent);

                    statusView.setText("Please add this app to the battery optimization whitelist, then press 'Acquire' again.");
                    statusView.setTextColor(Color.RED);

                    return;
                }

                if (wakeLock == null) {
                    PowerManager powerManager = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "myapp-cpu");
                    wakeLock.acquire();

                    WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "myapp-net");
                    wifiLock.acquire();

                    //start a slow file download in the background; if the download fails it's an indication that Doze has killed our wifi/network
                    new Thread() {
                        @Override
                        public void run() {
                            int bytesTransferred = 0;
                            byte[] buffer = new byte[TARGET_BYTES_PER_SECOND];
                            try {
                                int numRead = 0;

                                URLConnection urlConn = new URL(TEST_FILE_DOWNLOAD).openConnection();
                                urlConn.setConnectTimeout(CONNECTION_TIMEOUT);
                                urlConn.setReadTimeout(CONNECTION_TIMEOUT);
                                urlConn.connect();

                                InputStream download = urlConn.getInputStream();

                                while ((numRead = download.read(buffer)) != -1) {
                                    bytesTransferred += numRead;
                                    Thread.sleep(1000);     //XXX:  this won't be exact, but it's okay because it can only be slower than our target read rate, which is good enough

                                    ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                                    NetworkInfo wifiInfo = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                                    boolean haveWifi = wifiInfo.isConnected() && wifiInfo.isAvailable();
                                    if (! haveWifi) {
                                        throw new IOException("Wifi connection went away...");
                                    }

                                    Log.d("test.net", "Downloading file; bytesTransferred=" + bytesTransferred);
                                }
                            }
                            catch (IOException transferFailed) {
                                Log.e("test.net", "File download failed; time=" + System.currentTimeMillis(), transferFailed);

                                final String reportLine = "File download failed at " + new Date() + "; exception=" + transferFailed.getMessage() + ", bytesTransferred=" + bytesTransferred;
                                MainActivity.this.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        statusView.setText(statusView.getText() + "\n\n!!! Doze mode detected:  " + reportLine);
                                        statusView.setTextColor(Color.RED);
                                    }
                                });

                                stopTime = System.currentTimeMillis();
                            }
                            catch (Exception otherError) {
                                Log.w("test.net", "Unexpected exception:  " + otherError.getMessage(), otherError);
                            }
                        }
                    }.start();
                }

                //06-23 00:23:56.894 1052-1097/? I/DreamController: Starting dream: name=ComponentInfo{com.android.systemui/com.android.systemui.doze.DozeService}, isTest=false, canDoze=true, userId=0
                //06-23 00:24:02.794 1052-1102/? I/DreamManagerService: Gently waking up from dream.
                statusView.setText("Wake and wifi locks have been acquired; please monitor the logs for Doze mode.  These messages will look like:\n\n" +
                        "I/DreamController: Starting dream: name=ComponentInfo{com.android.systemui/com.android.systemui.doze.DozeService}, isTest=false, canDoze=true, userId=0");
                statusView.setTextColor(Color.GREEN);

                if (stopTime != -1) {
                    startTime = System.currentTimeMillis();
                    stopTime = -1;
                }
            }
        });

        releaseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (wakeLock == null) {
                    return;
                }

                wakeLock.release();
                wifiLock.release();

                wakeLock = null;
                wifiLock = null;

                statusView.setText("No locks held; please press 'Acquire' button");
                statusView.setTextColor(Color.BLACK);

                stopTime = System.currentTimeMillis();
            }
        });

        //timer (doze usually kicks in around the 2:00 mark)
        new Thread() {
            private String formatSeconds(int numSeconds) {
                int displayedMinutes = numSeconds / 60;
                int displayedSeconds = numSeconds % 60;

                return displayedMinutes + ":" + (displayedSeconds < 10 ? "0" + displayedSeconds : displayedSeconds);
            }

            @Override
            public void run() {
                while (true) {
                    long endTime = stopTime == -1 ? System.currentTimeMillis() : stopTime;
                    long elapsedMillis = endTime - startTime;
                    final String displayText = formatSeconds((int)(elapsedMillis / 1000));

                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            timerView.setText(displayText);
                        }
                    });

                    try {
                        Thread.sleep(100);
                    }
                    catch (Exception e) {
                        break;
                    }
                }
            }
        }.start();
    }
}
