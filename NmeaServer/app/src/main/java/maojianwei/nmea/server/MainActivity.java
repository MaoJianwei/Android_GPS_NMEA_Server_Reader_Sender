package maojianwei.nmea.server;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import win.maojianwei.nmea.nmeaserver.R;

import static android.location.GpsStatus.GPS_EVENT_SATELLITE_STATUS;
import static maojianwei.nmea.server.MaoNmeaTools.ACTIVITY_LOCATION_SOURCE_SETTINGS;
import static maojianwei.nmea.server.MaoNmeaTools.CheckEnableGpsDevice;
import static maojianwei.nmea.server.MaoNmeaTools.CheckGPSLocationProviderEnable;
import static maojianwei.nmea.server.MaoNmeaTools.ClipboardLabel;
import static maojianwei.nmea.server.MaoNmeaTools.CopyToClipboard;
import static maojianwei.nmea.server.MaoNmeaTools.CreateNmeaServer;
import static maojianwei.nmea.server.MaoNmeaTools.DestroyNmeaServer;
import static maojianwei.nmea.server.MaoNmeaTools.DoubleBlankspace;
import static maojianwei.nmea.server.MaoNmeaTools.DoubleEnter;
import static maojianwei.nmea.server.MaoNmeaTools.EnableGpsDevice;
import static maojianwei.nmea.server.MaoNmeaTools.GenerateNmeaWithBeidou;
import static maojianwei.nmea.server.MaoNmeaTools.MaoNmeaSendMessage_Close;
import static maojianwei.nmea.server.MaoNmeaTools.MaoNmeaSendMessage_write_flush;
import static maojianwei.nmea.server.MaoNmeaTools.No_Permission_ACCESS_FINE_LOCATION;
import static maojianwei.nmea.server.MaoNmeaTools.PERMISSION_ACCESS_FINE_LOCATION;
import static maojianwei.nmea.server.MaoNmeaTools.SingleEnter;
import static maojianwei.nmea.server.MaoNmeaTools.calSatelliteUseInFix;
import static maojianwei.nmea.server.MaoNmeaTools.convertNmeaGGA;
import static maojianwei.nmea.server.MaoNmeaTools.convertNmeaGLL;
import static maojianwei.nmea.server.MaoNmeaTools.convertNmeaGSA;
import static maojianwei.nmea.server.MaoNmeaTools.convertNmeaGSV;
import static maojianwei.nmea.server.MaoNmeaTools.convertNmeaRMC;
import static maojianwei.nmea.server.MaoNmeaTools.convertNmeaVTG;

public class MainActivity extends Activity {

    private static final int GPS_PORT = 8888;

    private LocationManager mLocationManager;
    private MaoGpsListener maoGpsListener;

    private Context activityContext;

    private boolean needOutput;
    private Switch outputSwitch;
    private boolean useApiConvert;
    private Switch originSwitch;
    private TextView logView;
    private TextView nmeaView;

    private List<Socket> clients;
    private ServerSocket server;
    private ExecutorService threadPool;
    private boolean exitServer;

    private int logCount = 0;
    private List<String> logQueue = new ArrayList<>();
    private int nmeaCount = 0;
    private List<String> nmeaLogQueue = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.activityContext = this;

        this.logView = ((TextView) findViewById(R.id.logText));
        this.logView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                logView.setText(R.string.Mao_NMEA_TitleView);
            }
        });
        this.nmeaView = ((TextView) findViewById(R.id.nmeaText));
        this.nmeaView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboardManager = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                clipboardManager.setPrimaryClip(ClipData.newPlainText(ClipboardLabel, nmeaView.getText()));
                Toast.makeText(activityContext, CopyToClipboard, Toast.LENGTH_SHORT).show();
            }
        });
        this.outputSwitch = ((Switch) findViewById(R.id.OutputSwitch));
        this.outputSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                needOutput = isChecked;
            }
        });
        this.originSwitch = ((Switch) findViewById(R.id.OriginSwitch));
        this.originSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                useApiConvert = isChecked;
            }
        });

        this.needOutput = true;
        this.useApiConvert = true;

        onCreateLocation();
        checkEnableGpsDevice();
        createNmeaServer();
    }

    protected void onCreateLocation() {
        if (this.mLocationManager == null) {
            this.mLocationManager = ((LocationManager) getSystemService(Context.LOCATION_SERVICE));
            if (this.mLocationManager != null) {

                if (PackageManager.PERMISSION_GRANTED == checkCallingOrSelfPermission(PERMISSION_ACCESS_FINE_LOCATION)) {
                    this.maoGpsListener = new MaoGpsListener();
                    this.mLocationManager.addGpsStatusListener(this.maoGpsListener);
                    this.mLocationManager.addNmeaListener(this.maoGpsListener);
                } else {
                    programLog(No_Permission_ACCESS_FINE_LOCATION);
                }
            }
        }
    }

    protected void checkEnableGpsDevice() {
        if (this.mLocationManager != null) {
            if (!checkGPSLocationProviderEnable()) {
                try {
                    startActivity(new Intent(ACTIVITY_LOCATION_SOURCE_SETTINGS));
                } catch (Exception localException) {
                    programLog(CheckEnableGpsDevice + localException.getMessage());
                }
            } else {
                requestGpsMessage();
            }
        }
    }

    boolean checkGPSLocationProviderEnable() {
        if (this.mLocationManager != null) {
            try {
                return this.mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            } catch (Exception paramString) {
                programLog(CheckGPSLocationProviderEnable + paramString.getMessage());
            }
        }
        return false;
    }

    @TargetApi(9)
    protected void requestGpsMessage() {
        if ((this.mLocationManager != null) && (Build.VERSION.SDK_INT >= 9) &&
                (PackageManager.PERMISSION_GRANTED == checkCallingOrSelfPermission(PERMISSION_ACCESS_FINE_LOCATION))) {
            this.mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, (float) 0.1, this.maoGpsListener);
        }
    }

    private void createNmeaServer() {
        exitServer = false;
        clients = new ArrayList<>();
        threadPool = Executors.newFixedThreadPool(2);

        try {
            server = new ServerSocket(GPS_PORT);
            threadPool.submit(new MaoNmeaServer());
        } catch (IOException ioe) {
            programLog(CreateNmeaServer + ioe.getMessage());
        }
    }


    @Override
    protected void onDestroy() {
        onDestroyLocation();

        destroyNmeaServer();

        super.onDestroy();
    }

    protected void onDestroyLocation() {
        if (this.mLocationManager != null) {
            if (PackageManager.PERMISSION_GRANTED == checkCallingOrSelfPermission(PERMISSION_ACCESS_FINE_LOCATION)) {
                this.mLocationManager.removeUpdates(this.maoGpsListener);
            }

            this.mLocationManager.removeGpsStatusListener(this.maoGpsListener);
            this.mLocationManager.removeNmeaListener(this.maoGpsListener);
            this.mLocationManager = null;
            this.maoGpsListener = null;
        }
    }

    private void destroyNmeaServer() {
        exitServer = true;

        try {
            server.close();
        } catch (IOException ioe) {
            programLog(DestroyNmeaServer + ioe.getMessage());
        }

        for (Socket c : clients) {
            try {
                c.close();
            } catch (IOException ioe) {
                programLog(DestroyNmeaServer + ioe.getMessage());
            }
        }
        clients.clear();

        threadPool.shutdownNow();
        try {
            threadPool.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            programLog(DestroyNmeaServer + ie.getMessage());
        }

        server = null;
        threadPool = null;
        clients = null;
    }


    public void programLog(CharSequence paramCharSequence) {
        if (!needOutput)
            return;

        if (logQueue.size() >= 2)
            logQueue.remove(0);
        logQueue.add(Integer.toString(++logCount) + DoubleBlankspace + paramCharSequence + SingleEnter);

        StringBuilder temp = new StringBuilder();
        for (String msg : logQueue) {
            temp.append(msg);
        }

        if (this.logView != null) {
            this.logView.setText(temp.toString());
        }
    }

    public void nmeaLog(String nmea) {
        if (!needOutput)
            return;

        if (nmeaLogQueue.size() >= 15)
            nmeaLogQueue.remove(0);
        nmeaLogQueue.add(Integer.toString(++nmeaCount) + DoubleBlankspace + nmea + DoubleEnter);

        StringBuilder temp = new StringBuilder();
        for (String msg : nmeaLogQueue) {
            temp.append(msg);
        }

        if (this.nmeaView != null) {
            this.nmeaView.setText(temp.toString());
        }
    }


    private void generateNmeaWithBeidou() {

        if (PackageManager.PERMISSION_GRANTED != checkCallingOrSelfPermission(PERMISSION_ACCESS_FINE_LOCATION)) {
            programLog(GenerateNmeaWithBeidou + No_Permission_ACCESS_FINE_LOCATION);
            return;
        }


        Iterator satelliteIterator = this.mLocationManager.getGpsStatus(null).getSatellites().iterator();
        List<GpsSatellite> satellites = new ArrayList<>();
        while (satelliteIterator.hasNext())
            satellites.add((GpsSatellite) satelliteIterator.next());

        String temp;
        temp = convertNmeaGSA(satellites);
        commitNmeaMessage(temp);

        String[] tempList = convertNmeaGSV(satellites);
        for (String gsv : tempList)
            commitNmeaMessage(gsv);


        Location location = this.mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (location != null) {
            temp = convertNmeaGGA(location, calSatelliteUseInFix(satellites));
            commitNmeaMessage(temp);
            temp = convertNmeaGLL(location);
            commitNmeaMessage(temp);
            temp = convertNmeaRMC(location);
            commitNmeaMessage(temp);
            temp = convertNmeaVTG(location);
            commitNmeaMessage(temp);
        }
    }

    private void commitNmeaMessage(String nmea) {
        nmeaLog(nmea);
        threadPool.submit(new MaoNmeaSendMessage(nmea));
    }


    private class MaoNmeaServer implements Runnable {
        public void run() {
            while (!exitServer) {
                try {
                    Socket client = server.accept();
                    clients.add(client);
                } catch (IOException ioe) {
                    programLog(ioe.getMessage());
                    break;
                }
            }
        }
    }

    private class MaoNmeaSendMessage implements Runnable {
        String nmeaMessage;

        MaoNmeaSendMessage(String nmeaMessage) {
            this.nmeaMessage = nmeaMessage;
        }

        public void run() {
            OutputStreamWriter temp;

            for (Socket c : clients) {
                if (exitServer)
                    break;

                try {
                    temp = new OutputStreamWriter(c.getOutputStream());
                    temp.write(nmeaMessage, 0, nmeaMessage.length());
                    temp.write(0x0A);
                    temp.flush();
                } catch (IOException ioe) {
                    programLog(MaoNmeaSendMessage_write_flush + ioe.getMessage());
                    clients.remove(c);

                    try {
                        c.close();
                    } catch (IOException e) {
                        programLog(MaoNmeaSendMessage_Close + e.getMessage());
                    }
                }
            }
        }
    }

    private class MaoGpsListener implements GpsStatus.Listener, GpsStatus.NmeaListener, LocationListener {

        //GpsStatus.NmeaListener
        @Override
        public void onNmeaReceived(long timestamp, String nmea) {
            if(!useApiConvert) {
                //--- Separate two data source, API Convert and Silicon Original, to avoid NMEA message conflict ---
                commitNmeaMessage(nmea);
            }
        }

        //GpsStatus.Listener
        public void onGpsStatusChanged(int event) {
            if(useApiConvert) {
                if (event == GPS_EVENT_SATELLITE_STATUS) {
                    //--- Separate two data source, API Convert and Silicon Original, to avoid NMEA message conflict ---
                    generateNmeaWithBeidou();
                }
            }
        }

        // LocationListener
        @Override
        public void onProviderDisabled(String provider) {
            if (provider.equals(LocationManager.GPS_PROVIDER)) {
                try {
                    startActivity(new Intent(ACTIVITY_LOCATION_SOURCE_SETTINGS));
                } catch (Exception localException) {
                    programLog(EnableGpsDevice + localException.getMessage());
                }
            }
        }

        @Override
        public void onLocationChanged(Location location) {
            if(useApiConvert) {
                //--- Separate two data source, API Convert and Silicon Original, to avoid NMEA message conflict ---
                generateNmeaWithBeidou();
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {

        }

        @Override
        public void onProviderEnabled(String provider) {

        }
    }
}
