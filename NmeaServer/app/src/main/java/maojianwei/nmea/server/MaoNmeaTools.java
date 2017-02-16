package maojianwei.nmea.server;

import android.location.GpsSatellite;
import android.location.Location;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Created by mao on 2017/2/16.
 */

public class MaoNmeaTools {

    public static final String PERMISSION_ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";
    public static final String ACTIVITY_LOCATION_SOURCE_SETTINGS = "android.settings.LOCATION_SOURCE_SETTINGS";

    public static final String DoubleBlankspace = "  ";
    public static final String SingleEnter = "\n";
    public static final String DoubleEnter = "\n\n";

    public static final String EnableGpsDevice = "enableGpsDevice: ";
    public static final String MaoNmeaSendMessage_Close = "MaoNmeaSendMessage close: ";
    public static final String MaoNmeaSendMessage_write_flush = "MaoNmeaSendMessage write/flush: ";
    public static final String GenerateNmeaWithBeidou = "generateNmeaWithBeidou: ";
    public static final String DestroyNmeaServer = "destroyNmeaServer: ";
    public static final String CreateNmeaServer = "createNmeaServer: ";
    public static final String CheckGPSLocationProviderEnable = "checkGPSLocationProviderEnable: ";
    public static final String CheckEnableGpsDevice = "checkEnableGpsDevice: ";
    public static final String No_Permission_ACCESS_FINE_LOCATION = "not have permission ACCESS_FINE_LOCATION !!!";
    public static final String Need_Permission_ACCESS_FINE_LOCATION = "We need to access GPS information :)";
    public static final String Restart_Permission_ACCESS_FINE_LOCATION = "Please restart our App :)";
    public static final String CopyToClipboard = "Copy Mao NMEA messages to clipboard";
    public static final String ClipboardLabel = "Mao_NMEA_Server";

    public static int calSatelliteUseInFix(List<GpsSatellite> satellites) {
        int count = 0;
        for (GpsSatellite s : satellites) {
            if ((s.getPrn() > 0) && s.usedInFix())
                count++;
        }
        return count;
    }

    public static String convertNmeaGGA(Location paramLocation, int paramInt) {
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

    public static String convertNmeaGLL(Location paramLocation) {
        StringBuilder localStringBuilder = new StringBuilder();
        localStringBuilder.append("$GPGLL,");
        localStringBuilder.append(FormatLatLon("%02d%08.5f,%c,", 'S', 'N', paramLocation.getLatitude()));
        localStringBuilder.append(FormatLatLon("%03d%08.5f,%c,", 'W', 'E', paramLocation.getLongitude()));
        localStringBuilder.append(FormatUTCDate("HHmmss.SSS", new Date(paramLocation.getTime())));
        localStringBuilder.append(",A,A");
        localStringBuilder.append(calcNmeaCrc(localStringBuilder));
        return localStringBuilder.toString();
    }

    public static String convertNmeaGSA(List<GpsSatellite> satellites) {
        StringBuilder localStringBuilder = new StringBuilder();
        localStringBuilder.append("$GPGSA,,,");

        int satelliteChannelCount = 12;
        for (GpsSatellite s : satellites) {
            if ((s.getPrn() > 0) && s.usedInFix()) {
                localStringBuilder.append(String.format(Locale.getDefault(), "%02d,", s.getPrn()));
                satelliteChannelCount--;
            }
            if (satelliteChannelCount == 0) {
                break;
            }
        }
        while (satelliteChannelCount-- > 0) {
            localStringBuilder.append(",");
        }

        localStringBuilder.append(",,,4");
        localStringBuilder.append(calcNmeaCrc(localStringBuilder));
        return localStringBuilder.toString();
    }

    public static String[] convertNmeaGSV(List<GpsSatellite> satellites) {
        StringBuilder localStringBuilder = new StringBuilder();

        // all satellite in ephemeris, include InUse, Seen and Not seen.
        // but the size will change dynamically.
        int seenCount = satellites.size();

        if (seenCount <= 0) {
            localStringBuilder.append("$GPGSV,1,1,00");
            localStringBuilder.append(calcNmeaCrc(localStringBuilder));
            return new String[]{localStringBuilder.toString()};
        }

        int msgRows = (seenCount + 3) / 4;
        String[] arrayOfString = new String[msgRows];

        //int prn, elevation, azimuth, snr;
        for (int i = 0; i < msgRows; i++) {
            localStringBuilder.setLength(0);
            localStringBuilder.append(String.format(Locale.getDefault(), "$GPGSV,%d,%d,%02d", msgRows, i + 1, seenCount));

            for (int j = 0; j < 4; j++) {
                if (i * 4 + j >= satellites.size())
                    break;

                GpsSatellite g = satellites.get(i * 4 + j);
                //prn = g.getPrn();
                //elevation = (int)g.getElevation();
                //azimuth = (int)g.getAzimuth();
                //snr = (int)g.getSnr();

                if ((int) g.getSnr() == 0) {
                    localStringBuilder.append(String.format(Locale.getDefault(), ",%02d,%02d,%03d,",
                            g.getPrn(), (int) g.getElevation(), (int) g.getAzimuth()));
                } else {
                    localStringBuilder.append(String.format(Locale.getDefault(), ",%02d,%02d,%03d,%02d",
                            g.getPrn(), (int) g.getElevation(), (int) g.getAzimuth(), (int) g.getSnr()));
                }
            }

            localStringBuilder.append(calcNmeaCrc(localStringBuilder));
            arrayOfString[i] = localStringBuilder.toString();
        }

        return arrayOfString;
    }

    public static String convertNmeaRMC(Location paramLocation) {
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

    public static String convertNmeaVTG(Location paramLocation) {
        StringBuilder localStringBuilder = new StringBuilder();
        localStringBuilder.append("$GPVTG,");
        if (paramLocation.hasBearing()) {
            localStringBuilder.append(String.format(Locale.getDefault(), "%1.0f", paramLocation.getBearing()));
        }
        localStringBuilder.append(",T,,M,");
        if (paramLocation.hasSpeed()) {
            localStringBuilder.append(String.format(Locale.getDefault(), "%5.3f,N,%5.3f,K,",
                    paramLocation.getSpeed() / 0.5395720670123687D, 3.6D * paramLocation.getSpeed()));
        } else {
            localStringBuilder.append(",,,,");
        }
        localStringBuilder.append(calcNmeaCrc(localStringBuilder));
        return localStringBuilder.toString();
    }

    private static String FormatUTCDate(String paramString, Date paramDate) {
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

    private static CharSequence calcNmeaCrc(CharSequence paramCharSequence) {
        int i = 0;
        for (int k = 1; k < paramCharSequence.length(); k++) {
            i = (byte) (paramCharSequence.charAt(k) ^ i);
        }
        //return String.format("*%02X\r\n", (byte) i);
        return String.format("*%02X\r", (byte) i);
    }
}
