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
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static android.location.GpsStatus.GPS_EVENT_SATELLITE_STATUS;

public class MainActivity extends Activity {

    private static final String PERMISSION_ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";
    private static final int GPS_PORT = 8888;

    private LocationManager mLocationManager;
    private MaoGpsListener maoGpsListener;

    private boolean needOutput;
    private Switch outputSwitch;
    private TextView logView; // mLogView
    private TextView nmeaView; // mLogView

    private List<Socket> clients;
    private ServerSocket server;
    private ExecutorService threadPool;
    private boolean exitServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.outputSwitch = ((Switch) findViewById(R.id.OutputSwitch));
        this.logView = ((TextView) findViewById(R.id.logText));
        this.nmeaView = ((TextView) findViewById(R.id.nmeaText));
        this.outputSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                needOutput = isChecked;
            }
        });
        this.needOutput = true;


        //String nmea = "$GPGGA,063006,1959.784173,N,11019.531628,E,2,05,1.6,13.0,M,-9.0,M,,";//*6F
        //CharSequence crc = calcNmeaCrc(nmea);

        //String time =FormatUTCDate("HHmmss.SSS", new Date());

//        String m1 = FormatLatLon("%02d%08.5f,%c,", 'S', 'N', 19.996536);
//        String m2 = FormatLatLon("%02d%08.5f,%c,", 'S', 'N', -19.996536);
//        String m3 = FormatLatLon("%02d%08.5f,%c,", 'S', 'N', 0);
//        String m4 = FormatLatLon("%03d%08.5f,%c,", 'W', 'E', 110.325431);
//        String m5 = FormatLatLon("%03d%08.5f,%c,", 'W', 'E', -110.325431);
//        String m6 = FormatLatLon("%03d%08.5f,%c,", 'W', 'E', 0);


        onCreateLocation();
        checkLocationProviders();
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

    protected void checkLocationProviders() {
        if (this.mLocationManager != null) {
            if (!checkGPSLocationProviderEnable()) {
                try {
                    startActivity(new Intent("android.settings.LOCATION_SOURCE_SETTINGS"));
                } catch (Exception localException) {
                    programLog("checkLocationProviders, exception:" + localException.getMessage());
                }
            } else {
                onRequestPosition();
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
    protected void onRequestPosition() {
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
        onPauseLocation();
        if (this.mLocationManager != null) {
            this.mLocationManager.removeGpsStatusListener(this.maoGpsListener);
            this.mLocationManager.removeNmeaListener(this.maoGpsListener);
            this.mLocationManager = null;
            this.maoGpsListener = null;
        }
    }

    protected void onPauseLocation() {
        if (this.mLocationManager != null) {
            if (PackageManager.PERMISSION_GRANTED == checkCallingOrSelfPermission(PERMISSION_ACCESS_FINE_LOCATION)) {
                this.mLocationManager.removeUpdates(this.maoGpsListener);
                if (this.logView != null) {
                    this.logView.setText("");
                }
            }
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


    private int logCount = 0;
    private List<String> logQueue = new ArrayList<>();

    public void programLog(CharSequence paramCharSequence) {
        if (!needOutput)
            return;

        if (logQueue.size() >= 3)
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

    private int nmeaCount = 0;
    private List<String> nmeaLogQueue = new ArrayList<>();

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

    private void generateNmeaWithBeidou() {

        if (PackageManager.PERMISSION_GRANTED != checkCallingOrSelfPermission(PERMISSION_ACCESS_FINE_LOCATION)) {
            programLog("generateNmeaWithBeidou: not have permission ACCESS_FINE_LOCATION !!!");
            return;
        }


        Iterator gi = this.mLocationManager.getGpsStatus(null).getSatellites().iterator();
        if(gi == null) {
            programLog("generateNmeaWithBeidou: GPS device is not ready!");
            return;
        }
        List<GpsSatellite> satellites = new ArrayList<>();
        while(gi.hasNext())
            satellites.add((GpsSatellite) gi.next());

        String temp;
        temp = convertNmeaGSA(satellites);
        commitNmeaMessage(temp);

        String [] tempList = convertNmeaGSV(satellites);
        for(String gsv : tempList)
            commitNmeaMessage(gsv);


        Location location = this.mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if(location != null) {
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
    private int calSatelliteUseInFix(List<GpsSatellite> satellites){
        int count = 0;
        for(GpsSatellite s : satellites) {
            if ((s.getPrn() > 0) && s.usedInFix())
                count++;
        }
        return count;
    }

    private String convertNmeaGGA(Location paramLocation, int paramInt) {
        StringBuilder localStringBuilder = new StringBuilder();
        localStringBuilder.append("$GPGGA,");
        localStringBuilder.append(FormatUTCDate("HHmmss.SSS", new Date(paramLocation.getTime())));
        localStringBuilder.append(",");
        localStringBuilder.append(FormatLatLon("%02d%08.5f,%c,", 'S', 'N', paramLocation.getLatitude()));
        localStringBuilder.append(FormatLatLon("%03d%08.5f,%c,", 'W', 'E', paramLocation.getLongitude()));

        localStringBuilder.append(String.format(Locale.getDefault(), "1,%02d,,", paramInt));
        if (paramLocation.hasAltitude()) {
            localStringBuilder.append(String.format(Locale.getDefault(), "%.1f", paramLocation.getAltitude()));
        }
        localStringBuilder.append(",M,,M,,");
        localStringBuilder.append(calcNmeaCrc(localStringBuilder));
        return localStringBuilder.toString();
    }


    private String convertNmeaGLL(Location paramLocation) {
        StringBuilder localStringBuilder = new StringBuilder();
        localStringBuilder.append("$GPGLL,");
        localStringBuilder.append(FormatLatLon("%02d%08.5f,%c,", 'S', 'N', paramLocation.getLatitude()));
        localStringBuilder.append(FormatLatLon("%03d%08.5f,%c,", 'W', 'E', paramLocation.getLongitude()));
        localStringBuilder.append(FormatUTCDate("HHmmss.SSS", new Date(paramLocation.getTime())));
        localStringBuilder.append(",A,A");
        localStringBuilder.append(calcNmeaCrc(localStringBuilder));
        return localStringBuilder.toString();
    }

    private String convertNmeaGSA(List<GpsSatellite> satellites)
    {
        StringBuilder localStringBuilder = new StringBuilder();
        localStringBuilder.append("$GPGSA,,,");

        int satelliteChannelCount = 12;
        for(GpsSatellite s : satellites){
            if ((s.getPrn() > 0) && s.usedInFix()) {
                localStringBuilder.append(String.format(Locale.getDefault(), "%02d,", s.getPrn()));
                satelliteChannelCount--;
            }
        }
        while(satelliteChannelCount-- > 0){
            localStringBuilder.append(",");
        }

        localStringBuilder.append(",,,");
        localStringBuilder.append(calcNmeaCrc(localStringBuilder));
        return localStringBuilder.toString();
    }

    private String[] convertNmeaGSV(List<GpsSatellite> satellites)//, int paramInt)
    {
        StringBuilder localStringBuilder = new StringBuilder();

        // is satellite in seen ?
        int seenCount = satellites.size();

        if(seenCount<=0){
            localStringBuilder.append("$GPGSV,1,1,00");
            localStringBuilder.append(calcNmeaCrc(localStringBuilder));
            return new String[] { localStringBuilder.toString() };
        }

        int msgRows = (seenCount + 3) / 4;
        String[] arrayOfString = new String[msgRows];

        //int prn, elevation, azimuth, snr;
        for(int i = 0; i < msgRows; i++){
            localStringBuilder.setLength(0);
            localStringBuilder.append(String.format(Locale.getDefault(), "$GPGSV,%d,%d,%02d", msgRows, i + 1, seenCount));

            for(int j = 0; j < 4; j++){
                if(i * 4 + j >= satellites.size())
                    break;

                GpsSatellite g = satellites.get(i * 4 + j);
                //prn = g.getPrn();
                //elevation = (int)g.getElevation();
                //azimuth = (int)g.getAzimuth();
                //snr = (int)g.getSnr();

                if((int)g.getSnr() == 0){
                    localStringBuilder.append(String.format(Locale.getDefault(), ",%02d,%02d,%03d,",
                            g.getPrn(), (int)g.getElevation(), (int)g.getAzimuth()));
                }
                else {
                    localStringBuilder.append(String.format(Locale.getDefault(), ",%02d,%02d,%03d,%02d",
                            g.getPrn(), (int) g.getElevation(), (int) g.getAzimuth(), (int) g.getSnr()));
                }
            }

            localStringBuilder.append(calcNmeaCrc(localStringBuilder));
            arrayOfString[i] = localStringBuilder.toString();
        }

        return arrayOfString;
    }

    private String convertNmeaRMC(Location paramLocation)
    {
        StringBuilder localStringBuilder = new StringBuilder();
        localStringBuilder.append("$GPRMC,");
        localStringBuilder.append(FormatUTCDate("HHmmss.SSS", new Date(paramLocation.getTime())));
        localStringBuilder.append(",A,");
        localStringBuilder.append(FormatLatLon("%02d%08.5f,%c,", 'S', 'N', paramLocation.getLatitude()));
        localStringBuilder.append(FormatLatLon("%03d%08.5f,%c,", 'W', 'E', paramLocation.getLongitude()));
        if (paramLocation.hasSpeed()) {
            localStringBuilder.append(String.format(Locale.getDefault(), "%5.3f", paramLocation.getSpeed() / 0.5395720670123687D));
        }
        localStringBuilder.append(",");
        if (paramLocation.hasBearing()) {
            localStringBuilder.append(String.format(Locale.getDefault(), "%1.0f", (double) paramLocation.getBearing()));
        }
        localStringBuilder.append(",");
        localStringBuilder.append(FormatUTCDate("ddMMyy", new Date(paramLocation.getTime())));
        localStringBuilder.append(",,,A");
        localStringBuilder.append(calcNmeaCrc(localStringBuilder));
        return localStringBuilder.toString();
    }

    private String convertNmeaVTG(Location paramLocation)
    {
        StringBuilder localStringBuilder = new StringBuilder();
        localStringBuilder.append("$GPVTG,");
        if (paramLocation.hasBearing()) {
            localStringBuilder.append(String.format(Locale.getDefault(), "%1.0f", paramLocation.getBearing()));
        }
        localStringBuilder.append(",T,,M,");
        if (paramLocation.hasSpeed())
        {
            localStringBuilder.append(String.format(Locale.getDefault(), "%5.3f,N,%5.3f,K,",
                    paramLocation.getSpeed() / 0.5395720670123687D, 3.6D * paramLocation.getSpeed()));
        }else{
            localStringBuilder.append(",,,,");
        }
        localStringBuilder.append(calcNmeaCrc(localStringBuilder));
        return localStringBuilder.toString();
    }

    private String FormatUTCDate(String paramString, Date paramDate) {
        SimpleDateFormat dateString = new SimpleDateFormat(paramString, Locale.getDefault());
        dateString.setTimeZone(TimeZone.getTimeZone("UTC"));
        return dateString.format(paramDate);
    }

    private static String FormatLatLon(String paramString, char paramChar1, char paramChar2, double paramDouble) {
        double d1, d2;
        int i;
        if (paramDouble < 0.0D) {
            d1 = -paramDouble;
            i = (int) d1;
            d2 = i;
            paramChar2 = paramChar1;
        } else {
            d1 = paramDouble;
            i = (int) d1;
            d2 = i;
        }
        return String.format(paramString, i, 60.0D * (d1 - d2), paramChar2);
    }

    private CharSequence calcNmeaCrc(CharSequence paramCharSequence) {
        int i = 0;
        for (int k = 1; k < paramCharSequence.length(); k++) {
            i = (byte) (paramCharSequence.charAt(k) ^ i);
        }
        //return String.format("*%02X\r\n", (byte) i);
        return String.format("*%02X\r", (byte) i);
    }


    private void commitNmeaMessage(String nmea){
        nmeaLog(nmea);
        threadPool.submit(new MaoNmeaSendMessage(nmea));
    }

    private class MaoGpsListener implements GpsStatus.Listener, GpsStatus.NmeaListener, LocationListener {

        //GpsStatus.NmeaListener
        @Override
        public void onNmeaReceived(long timestamp, String nmea) {
            //--- Comment this, for convert NMEA debug ---
            commitNmeaMessage(nmea);
        }

        //GpsStatus.Listener
        public void onGpsStatusChanged(int event) {
            if (event == GPS_EVENT_SATELLITE_STATUS) {
                generateNmeaWithBeidou();
            }
        }

        // LocationListener
        @Override
        public void onProviderDisabled(String provider) {
            if (provider.equals(LocationManager.GPS_PROVIDER)) {
                try {
                    startActivity(new Intent("android.settings.LOCATION_SOURCE_SETTINGS"));
                } catch (Exception localException) {
                    programLog("checkLocationProviders, exception:" + localException.getMessage());
                }
            }
        }

        @Override
        public void onLocationChanged(Location location) {
            generateNmeaWithBeidou();
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {

        }

        @Override
        public void onProviderEnabled(String provider) {

        }
    }
}
