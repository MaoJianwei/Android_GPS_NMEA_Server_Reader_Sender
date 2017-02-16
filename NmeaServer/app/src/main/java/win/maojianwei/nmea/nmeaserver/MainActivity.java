package win.maojianwei.nmea.nmeaserver;

import android.annotation.TargetApi;
import android.app.Activity;
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
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

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

import static android.location.GpsStatus.GPS_EVENT_SATELLITE_STATUS;
import static win.maojianwei.nmea.nmeaserver.MaoNmeaTools.calSatelliteUseInFix;
import static win.maojianwei.nmea.nmeaserver.MaoNmeaTools.convertNmeaGGA;
import static win.maojianwei.nmea.nmeaserver.MaoNmeaTools.convertNmeaGLL;
import static win.maojianwei.nmea.nmeaserver.MaoNmeaTools.convertNmeaGSA;
import static win.maojianwei.nmea.nmeaserver.MaoNmeaTools.convertNmeaGSV;
import static win.maojianwei.nmea.nmeaserver.MaoNmeaTools.convertNmeaRMC;
import static win.maojianwei.nmea.nmeaserver.MaoNmeaTools.convertNmeaVTG;

public class MainActivity extends Activity {

    private static final String PERMISSION_ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";
    private static final int GPS_PORT = 8888;

    private LocationManager mLocationManager;
    private MaoGpsListener maoGpsListener;

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

        this.logView = ((TextView) findViewById(R.id.logText));
        this.logView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logView.setText(R.string.Mao_NMEA_TitleView);
            }
        });
        this.nmeaView = ((TextView) findViewById(R.id.nmeaText));
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
                    programLog("not have permission ACCESS_FINE_LOCATION !!!");
                }
            }
        }
    }

    protected void checkEnableGpsDevice() {
        if (this.mLocationManager != null) {
            if (!checkGPSLocationProviderEnable()) {
                try {
                    startActivity(new Intent("android.settings.LOCATION_SOURCE_SETTINGS"));
                } catch (Exception localException) {
                    programLog("checkEnableGpsDevice, exception:" + localException.getMessage());
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
                programLog("checkGPSLocationProviderEnable, exception:" + paramString.getMessage());
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
            programLog("createNmeaServer: " + ioe.getMessage());
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
            programLog("destroyNmeaServer1: " + ioe.getMessage());
        }

        for (Socket c : clients) {
            try {
                c.close();
            } catch (IOException ioe) {
                programLog("destroyNmeaServer2: " + ioe.getMessage());
            }
        }
        clients.clear();

        threadPool.shutdownNow();
        try {
            threadPool.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            programLog("destroyNmeaServer: " + ie.getMessage());
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
        logQueue.add(Integer.toString(++logCount) + "  " + paramCharSequence + "\n");

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

        if (nmeaLogQueue.size() >= 10)
            nmeaLogQueue.remove(0);
        nmeaLogQueue.add(Integer.toString(++nmeaCount) + "  " + nmea + "\n\n");

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
            programLog("generateNmeaWithBeidou: not have permission ACCESS_FINE_LOCATION !!!");
            return;
        }


        Iterator gi = this.mLocationManager.getGpsStatus(null).getSatellites().iterator();
        if (gi == null) {
            programLog("generateNmeaWithBeidou: GPS device is not ready!");
            return;
        }
        List<GpsSatellite> satellites = new ArrayList<>();
        while (gi.hasNext())
            satellites.add((GpsSatellite) gi.next());

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
                    programLog("MaoNmeaSendMessage run: " + ioe.getMessage());
                    clients.remove(c);

                    try {
                        c.close();
                    } catch (IOException e) {
                        programLog("MaoNmeaSendMessage try: " + e.getMessage());
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
                    startActivity(new Intent("android.settings.LOCATION_SOURCE_SETTINGS"));
                } catch (Exception localException) {
                    programLog("checkEnableGpsDevice, exception:" + localException.getMessage());
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
