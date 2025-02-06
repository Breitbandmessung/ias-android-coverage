/*
 *     Copyright (C) 2016-2025 zafaco GmbH
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License version 3
 *     as published by the Free Software Foundation.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.zafaco.moduleCoverage;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.TaskStackBuilder;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.sqlite.SQLiteException;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.text.HtmlCompat;

import com.google.android.gms.location.LocationRequest;
import com.zafaco.moduleCommon.Database;
import com.zafaco.moduleCommon.Log;
import com.zafaco.moduleCommon.Tool;
import com.zafaco.moduleCommon.interfaces.ModulesInterface;
import com.zafaco.moduleCommon.listener.ListenerGeoLocation;
import com.zafaco.moduleCommon.listener.ListenerNetwork;
import com.zafaco.moduleCommon.listener.ListenerTelephony;
import com.zafaco.moduleCommon.listener.ListenerWireless;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public class Coverage extends Service
{


    Context ctx;

    private boolean DEBUG = false;


    private Tool mTool;
    private JSONObject dataNetwork = null;
    private JSONObject dataWireless = null;

    private Database mDatabaseMeta = null;
    private Database mDatabaseCoverage = null;

    private ListenerNetwork listenerNetwork = null;
    private ListenerTelephony listenerTelephony = null;
    private ListenerWireless listenerWireless = null;
    private ListenerGeoLocation listenerGeoLocation = null;

    private Thread pThread;
    private int iThreadCounter = 1;

    private String appVersion = "";
    private int minTime = 1000;
    private int locationAgeThreshold = 1000;

    private double distanceActual = -1.0;
    private double appVelocity = -1.0;
    private double appAccuracy = -1.0;
    private double appLocationAgeMs = -1;

    private int appAccessId = -1;
    private int appVoiceId = -1;
    private int appCallState = -1;
    private String accessCategory = "-";

    private Location lastLocation = new Location("A");
    private Location newLocation = new Location("B");

    private int accuracyFilterInitial = 50;

    private int distanceFilterInitial = 1;
    private final int distanceFilterWalking = 10;
    private final int distanceFilterBiking = 25;
    private final int distanceFilterDriving = 50;

    private final int velocityFilterWalkingThreshold = 4;
    private final int velocityFilterBikingThreshold = 10;


    private String app_track_id = "";

    private int appIcon = 0;

    private String android_id = "";
    private String notificationChannelName = "";
    private String notificationChannelId = "";

    private final IBinder mBinder = new LocalBinder();
    private Handler mHandler = null;
    private Handler mHandlerInfo = null;
    private Class<?> mClass = null;

    private boolean isRunning = false;
    private boolean useWithDistanceFilter = true;

    private NotificationChannel mNotificationChannel;
    private NotificationManager mNotificationManager;
    private NotificationCompat.Builder mBuilder;
    private PendingIntent resultPendingIntent;

    private ConcurrentMap<String, AtomicLong> counter;

    private TimeZone tz;

    private static final String TAG = "Coverage";


    public class LocalBinder extends Binder
    {
        public Coverage getService()
        {

            return Coverage.this;
        }
    }

    public void setDebug()
    {
        DEBUG = true;
    }

    public void setHandler(Handler handler, Handler handlerInfo)
    {
        mHandler = handler;
        mHandlerInfo = handlerInfo;
    }

    public void setClass(Class<?> cls)
    {
        if (mClass == null)
            mClass = cls;
    }

    public void resetThreadCounter()
    {
        iThreadCounter = 0;
    }


    public boolean isRunning()
    {
        return isRunning;
    }

    public String getTrackID()
    {
        return app_track_id;
    }


    @Override
    public void onCreate()
    {
        super.onCreate();

        this.ctx = getApplicationContext();

        mTool = new Tool();
        dataNetwork = new JSONObject();

        android_id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);

        tz = TimeZone.getDefault();
    }

    @Override
    public int onStartCommand(Intent intent, final int flags, int startId)
    {
        super.onStartCommand(intent, flags, startId);

        if (intent == null)
            return Service.START_REDELIVER_INTENT;

        counter = new ConcurrentHashMap<>();
        counter.putIfAbsent("all", new AtomicLong(0));
        counter.putIfAbsent("unknown", new AtomicLong(0));
        counter.putIfAbsent("2G", new AtomicLong(0));
        counter.putIfAbsent("3G", new AtomicLong(0));
        counter.putIfAbsent("4G", new AtomicLong(0));
        counter.putIfAbsent("5G", new AtomicLong(0));


        minTime = intent.getIntExtra("min_time", 1000);
        accuracyFilterInitial = intent.getIntExtra("min_accuracy", 50);
        locationAgeThreshold = intent.getIntExtra("location_age_threshold", 1000);

        distanceFilterInitial = intent.getIntExtra("min_distance", 1);

        app_track_id = intent.getStringExtra("app_track_id");

        appVersion = intent.getStringExtra("app_version");

        appIcon = intent.getIntExtra("app_icon", 0);

        notificationChannelName = intent.getStringExtra("notification_channel_name") != null ? intent.getStringExtra("notification_channel_name") : "com.zafaco.ias";
        notificationChannelId = intent.getStringExtra("notification_channel_id") != null ? intent.getStringExtra("notification_channel_id") : "12345";


        isRunning = true;
        useWithDistanceFilter = true;


        lastLocation = new Location("A");
        newLocation = new Location("B");


        pThread = new Thread(new WorkerThread());
        pThread.start();


        mDatabaseCoverage = new Database(ctx, "measurements", "coverage");
        mDatabaseCoverage.createDB(addTableColumns());


        LinkedHashMap<String, String> dataMeta = new LinkedHashMap<>();
        dataMeta.put("ftable", "coverage");
        dataMeta.put("fkey", app_track_id);
        dataMeta.put("timestamp", app_track_id);
        dataMeta.put("sent", "false");
        dataMeta.put("deleted", "false");

        try
        {
            mDatabaseMeta = new Database(ctx, "measurements", "meta");
            mDatabaseMeta.createDB(dataMeta);
            mDatabaseMeta.insert(dataMeta);
        } catch (SQLiteException ex)
        {
            Log.warning(TAG, "onStartCommand: SQLiteException", ex);
        }


        if (mClass == null)
        {
            try
            {
                mClass = Class.forName(intent.getStringExtra("app_intent_class"));
            } catch (ClassNotFoundException | NullPointerException e)
            {
                Log.warning(TAG, "onStartCommand: intent class failure", e);
            }
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        {
            try
            {
                startForeground(Integer.parseInt(notificationChannelId), setNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } catch (ForegroundServiceStartNotAllowedException exception)
            {
                Log.warning(TAG, "Starting foreground service failed", exception);
            }
        } else
        {
            startForeground(Integer.parseInt(notificationChannelId), setNotification());
        }

        Log.debug(TAG, "Coverage Started: Min_Time: " + minTime + ", Min_Accuracy: " + accuracyFilterInitial + ", Min_Distance: " + distanceFilterInitial);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        {
            listenerTelephony = new ListenerTelephony(ctx, new ModulesInterface()
            {
                @Override
                public void receiveString(String message)
                {
                }

                @Override
                public void receiveData(JSONObject message)
                {
                    try
                    {

                        appAccessId = message.getInt("app_access_id");


                        appVoiceId = message.optInt("app_voice_id", -1);


                        message.put("app_access_category", mTool.setCategory(message.getInt("app_access_id")));

                        appCallState = message.getInt("app_call_state") == 0 ? 0 : 1;


                        message.put("app_call_state", appCallState);


                        dataNetwork = message;
                    } catch (Exception ex)
                    {
                        Log.warning(TAG, "receiveData: ListenerTelephony", message, ex);
                    }
                }
            });
            listenerTelephony.startUpdates();
            listenerTelephony.withIntervall();
        } else
        {
            listenerNetwork = new ListenerNetwork(ctx, new ModulesInterface()
            {
                @Override
                public void receiveString(String message)
                {
                }

                @Override
                public void receiveData(JSONObject message)
                {
                    try
                    {

                        appAccessId = message.getInt("app_access_id");


                        appVoiceId = message.optInt("app_voice_id", -1);


                        message.put("app_access_category", mTool.setCategory(message.getInt("app_access_id")));


                        appCallState = message.getInt("app_call_state") == 0 ? 0 : 1;


                        message.put("app_call_state", appCallState);


                        dataNetwork = message;
                    } catch (Exception ex)
                    {
                        Log.warning(TAG, "receiveData: ListenerNetwork", message, ex);
                    }
                }
            });
            listenerNetwork.startUpdates();
            listenerNetwork.withIntervall();
        }
        listenerWireless = new ListenerWireless(ctx, new ModulesInterface()
        {
            @Override
            public void receiveString(String message)
            {

            }

            @Override
            public void receiveData(JSONObject message)
            {
                try
                {


                    dataWireless = message;
                } catch (Exception ex)
                {
                    Log.warning(TAG, "receiveData: ListenerWireless", message, ex);
                }
            }
        });
        listenerWireless.getState();
        listenerWireless.withIntervall();

        listenerGeoLocation = new ListenerGeoLocation(ctx, new ModulesInterface()
        {
            @Override
            public void receiveString(String message)
            {
            }

            @Override
            public void receiveData(JSONObject message)
            {
                try
                {
                    appVelocity = message.getDouble("app_velocity");
                    appAccuracy = message.getDouble("app_accuracy");
                    appLocationAgeMs = message.getLong("app_location_age_ns") / 1e6;

                    newLocation.setLatitude(message.getDouble("app_latitude"));
                    newLocation.setLongitude(message.getDouble("app_longitude"));


                    changeLocationFilter();


                    message.put("timestamp", app_track_id);


                    message.put("client_os", "Android");
                    message.put("client_os_version", Build.VERSION.RELEASE);

                    message.put("app_manufacturer", Build.MANUFACTURER);
                    message.put("app_manufacturer_id", android_id);
                    message.put("app_manufacturer_version", Build.MODEL);

                    message.put("app_version", appVersion);
                    message.put("app_library_version", BuildConfig.VERSION_NAME);

                    message.put("app_geo_timestamp", System.currentTimeMillis());
                    message.put("app_geo_timezone", tz.getRawOffset() / 1000);


                    message.put("track_id", app_track_id);


                    message = mTool.mergeJSON(message, dataNetwork);
                    message = mTool.mergeJSON(message, dataWireless);


                    message.put("sent", false);


                    message.put("app_distance", lastLocation.getLatitude() != 0.0 ? lastLocation.distanceTo(newLocation) : 0.0);


                    if (!DEBUG)
                    {

                        if (mTool.isWifi(ctx))
                            return;


                        if (mTool.isAirplane(ctx))
                            return;


                        if (mTool.isSimReady(ctx) != 5)
                            return;


                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mTool.getActiveSimCount(ctx) != 1)
                        {
                            return;
                        }


                        if (message.getDouble("app_distance") < distanceActual && lastLocation.getLatitude() != 0.0)
                            return;


                        if (appAccuracy > accuracyFilterInitial)
                            return;


                        if (message.getInt("app_access_id") == 18)
                            return;


                        if (appLocationAgeMs > locationAgeThreshold)
                            return;


                        if (message.getString("app_mode").equals("WIFI"))
                            message.put("app_mode", "WWAN");


                        if (appCallState == 0 && appAccessId == 0 && (appVoiceId == 16 || message.has("app_operator_net_mnc")) && !message.optBoolean("app_emergency_only"))
                        {
                            message.put("app_access_id", 4);
                            message.put("app_access", "2G");
                        }


                        if (appCallState == 1 && appAccessId == 0)
                        {


                            message.put("app_access_id", 4);
                            message.put("app_access", "2G");
                        }


                        if (message.optBoolean("app_emergency_only"))
                        {
                            message.put("app_access_id", 0);
                            message.put("app_access", mTool.getNetType(0));
                        }
                    }


                    accessCategory = mTool.setCategory(message.getInt("app_access_id"));
                    message.put("app_access_category", accessCategory);


                    counter.putIfAbsent(accessCategory, new AtomicLong(0));
                    counter.get(accessCategory).incrementAndGet();

                    counter.putIfAbsent("all", new AtomicLong(0));
                    counter.get("all").incrementAndGet();

                    updateNotification(counter);


                    mDatabaseCoverage.createDB(message);
                    mDatabaseCoverage.insert(message);


                    lastLocation.set(newLocation);


                    sendMessageToHandler(mHandler, message);
                } catch (Exception ex)
                {
                    Log.warning(TAG, "receiveData: ListenerGeoLocation", message, ex);
                }
            }
        }, true);

        listenerGeoLocation.setGeoService();
        listenerGeoLocation.startUpdates(minTime, distanceFilterInitial, LocationRequest.PRIORITY_HIGH_ACCURACY);

        return Service.START_REDELIVER_INTENT;
    }

    @Override
    public IBinder onBind(Intent arg0)
    {
        return mBinder;
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
    }

    public void onStopCommand()
    {
        Log.debug(TAG, "------------------------------------------------------------------");
        Log.debug(TAG, "Coverage Stopped!");
        Log.debug(TAG, "------------------------------------------------------------------");

        isRunning = false;

        if (listenerTelephony != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listenerTelephony.stopUpdates();

        if (listenerNetwork != null)
            listenerNetwork.stopUpdates();

        if (listenerWireless != null)
            listenerWireless.stopUpdates();

        if (listenerGeoLocation != null)
            listenerGeoLocation.stopUpdates();

        if (pThread != null)
            pThread.interrupt();

        unsetNotification();

        stopForeground(true);

        onDestroy();
    }

    private void sendMessageToHandler(Handler handler, JSONObject message)
    {
        if (handler != null)
        {
            Message msg = Message.obtain(handler);
            msg.obj = message;
            handler.sendMessage(msg);
        }
    }

    private void changeLocationFilter()
    {
        if (!useWithDistanceFilter)
            return;

        if (listenerGeoLocation == null)
            return;

        if (appAccessId == -1)
            return;

        if (appVelocity == -1)
            return;

        double distance = 0;


        if (appVelocity <= velocityFilterWalkingThreshold)
        {
            distance = distanceFilterWalking;
            Log.debug(TAG, "changeLocationFilter: distanceFilterWalking - vA[" + appVelocity + "] - aA[" + appAccessId + "] - distance[" + distance + "]");
        }


        if (appVelocity > velocityFilterWalkingThreshold && appVelocity <= velocityFilterBikingThreshold)
        {
            distance = distanceFilterBiking;
            Log.debug(TAG, "changeLocationFilter: distanceFilterBiking - vA[" + appVelocity + "] - aA[" + appAccessId + "] - distance[" + distance + "]");
        }


        if (appVelocity > velocityFilterBikingThreshold)
        {
            distance = distanceFilterDriving;
            Log.debug(TAG, "changeLocationFilter: distanceFilterDriving - vA[" + appVelocity + "] - aA[" + appAccessId + "] - distance[" + distance + "]");
        }


        if (distanceActual != distance)
        {
            distanceActual = distance;
            Log.debug(TAG, "listenerGeoLocation: distance[" + distance + "]");
        }
    }


    class WorkerThread extends Thread
    {
        public void run()
        {
            while (isRunning)
            {
                iThreadCounter++;

                try
                {
                    Thread.sleep(1000);

                    JSONObject message = new JSONObject();


                    message.put("app_accuracy", appAccuracy);


                    if (iThreadCounter == 20)
                    {
                        message.put("warning", "info");
                        message.put("counter", iThreadCounter);
                        message.put("show", false);

                        sendMessageToHandler(mHandlerInfo, message);

                        continue;
                    }


                    if (mTool.isWifi(ctx))
                    {
                        message.put("warning", "wifi");
                        message.put("priority", 10);
                        message.put("description: ", "mTool.isWifi(ctx) = " + mTool.isWifi(ctx));

                        sendMessageToHandler(mHandlerInfo, message);
                    } else if (mTool.isAirplane(ctx))
                    {
                        message.put("warning", "airplane");
                        message.put("priority", 30);
                        message.put("description: ", "mTool.isAirplane(ctx) = " + mTool.isAirplane(ctx));

                        sendMessageToHandler(mHandlerInfo, message);
                    } else if (appAccuracy > accuracyFilterInitial)
                    {
                        message.put("warning", "gps");
                        message.put("priority", 1);
                        message.put("accuracyFilterInitial", accuracyFilterInitial);

                        sendMessageToHandler(mHandlerInfo, message);
                    } else if (appLocationAgeMs > locationAgeThreshold)
                    {
                        message.put("warning", "age");
                        message.put("priority", 30);
                        message.put("description", "Current GPS age is over " + locationAgeThreshold + "ms: " + appLocationAgeMs);
                        sendMessageToHandler(mHandlerInfo, message);

                    } else if (mTool.isSimReady(ctx) != 5 || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mTool.getActiveSimCount(ctx) < 1))
                    {
                        message.put("warning", "sim<1");
                        message.put("priority", 30);


                        sendMessageToHandler(mHandlerInfo, message);
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mTool.getActiveSimCount(ctx) > 1)
                    {
                        message.put("warning", "sim>1");
                        message.put("priority", 30);
                        message.put("description: ", "mTool.getActiveSimCount(ctx) = " + mTool.getActiveSimCount(ctx));

                        sendMessageToHandler(mHandlerInfo, message);
                    }


                    if (!message.has("priority"))
                    {
                        message.put("warning", "no");
                        message.put("priority", 0);

                        sendMessageToHandler(mHandlerInfo, message);
                    }


                } catch (Exception ex)
                {
                    if (!(ex instanceof InterruptedException))
                        Log.warning(TAG, "run", ex);
                }
            }
        }
    }

    private LinkedHashMap<String, String> addTableColumns()
    {
        LinkedHashMap<String, String> columns = new LinkedHashMap<>();

        columns.put("track_id", "");
        columns.put("app_geo_timestamp", "");
        columns.put("app_geo_timezone", "");

        columns.put("client_os", "");
        columns.put("client_os_version", "");
        columns.put("app_manufacturer", "");
        columns.put("app_manufacturer_id", "");
        columns.put("app_manufacturer_version", "");
        columns.put("app_operator_net", "");
        columns.put("app_operator_net_mcc", "");
        columns.put("app_operator_net_mnc", "");
        columns.put("app_operator_sim", "");
        columns.put("app_operator_sim_mcc", "");
        columns.put("app_operator_sim_mnc", "");
        columns.put("app_version", "");
        columns.put("app_library_version", "");

        columns.put("app_latitude", "");
        columns.put("app_longitude", "");
        columns.put("app_altitude", "");
        columns.put("app_accuracy", "");
        columns.put("app_velocity", "");
        columns.put("app_distance", "");
        columns.put("app_altitude_max", "");
        columns.put("app_velocity_max", "");
        columns.put("app_velocity_avg", "");

        columns.put("app_mode", "");
        columns.put("app_access", "");
        columns.put("app_access_id", "");
        columns.put("app_access_id_debug", "");
        columns.put("app_access_category", "");
        columns.put("app_call_state", "");
        columns.put("app_voice", "");
        columns.put("app_voice_id", "");
        columns.put("app_rssi", "");
        columns.put("app_arfcn", "");
        columns.put("app_cellid", "");
        columns.put("app_celllac", "");

        columns.put("sent", "");

        return columns;
    }

    private Notification setNotification()
    {
        this.ctx = getApplicationContext();

        mNotificationManager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
        {
            mNotificationChannel = new NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_NONE);

            if (mNotificationManager != null)
                mNotificationManager.createNotificationChannel(mNotificationChannel);
        }


        Intent notificationIntent = new Intent(ctx, mClass);
        notificationIntent.setAction(Intent.ACTION_MAIN);
        notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
        notificationIntent.setComponent(new ComponentName(ctx.getPackageName(), mClass.getName()));

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(ctx);
        stackBuilder.addParentStack(mClass);
        stackBuilder.addNextIntent(notificationIntent);
        resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String sMessageLine1 = "<b>Gesamt:</b> 0 / <b>Kein Netz:</b> 0";
        String sMessageLine2 = "<b>2G:</b> 0 / <b>3G:</b> 0 / <b>4G:</b> 0 / <b>5G:</b> 0";

        NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();
        inboxStyle.addLine(HtmlCompat.fromHtml(sMessageLine1, HtmlCompat.FROM_HTML_MODE_LEGACY));
        inboxStyle.addLine(HtmlCompat.fromHtml(sMessageLine2, HtmlCompat.FROM_HTML_MODE_LEGACY));


        mBuilder = new NotificationCompat.Builder(ctx, notificationChannelId)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setChannelId(notificationChannelId)
                .setSmallIcon(appIcon)
                .setContentIntent(resultPendingIntent)
                .setColor(0x3B78A4)

                .setContentTitle("Netzverfügbarkeit")
                .setStyle(inboxStyle);

        return mBuilder.build();
    }

    private void updateNotification(ConcurrentMap<String, AtomicLong> counter)
    {
        this.ctx = getApplicationContext();

        String sMessageLine1 = "<b>Gesamt:</b> " + counter.get("all") + " / <b>Kein Netz:</b> " + counter.get("unknown");
        String sMessageLine2 = "<b>2G:</b> " + counter.get("2G") + " / <b>3G:</b> " + counter.get("3G") + " / <b>4G:</b> " + counter.get("4G") + " / <b>5G:</b> " + counter.get("5G");

        NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();
        inboxStyle.addLine(HtmlCompat.fromHtml(sMessageLine1, HtmlCompat.FROM_HTML_MODE_LEGACY));
        inboxStyle.addLine(HtmlCompat.fromHtml(sMessageLine2, HtmlCompat.FROM_HTML_MODE_LEGACY));


        mBuilder = new NotificationCompat.Builder(ctx, notificationChannelId)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setSmallIcon(appIcon)
                .setContentIntent(resultPendingIntent)
                .setColor(0x3B78A4)
                .setContentTitle("Netzverfügbarkeit")
                .setStyle(inboxStyle);

        mNotificationManager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        assert mNotificationManager != null;
        mNotificationManager.notify(Integer.parseInt(notificationChannelId), mBuilder.build());
    }

    private void unsetNotification()
    {
        this.ctx = getApplicationContext();

        mNotificationManager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        if (mNotificationManager != null)
        {
            mNotificationManager.cancelAll();
        }
    }
}
