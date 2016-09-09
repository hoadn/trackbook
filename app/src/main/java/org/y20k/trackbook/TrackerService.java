/**
 * TrackerService.java
 * Implements the app's movement tracker service
 * The TrackerService creates a Track object and displays a notification
 *
 * This file is part of
 * TRACKBOOK - Movement Recorder for Android
 *
 * Copyright (c) 2016 - Y20K.org
 * Licensed under the MIT-License
 * http://opensource.org/licenses/MIT
 *
 * Trackbook uses osmdroid - OpenStreetMap-Tools for Android
 * https://github.com/osmdroid/osmdroid
 */

package org.y20k.trackbook;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;

import org.y20k.trackbook.core.Track;
import org.y20k.trackbook.core.WayPoint;
import org.y20k.trackbook.helpers.LocationHelper;
import org.y20k.trackbook.helpers.LogHelper;
import org.y20k.trackbook.helpers.NotificationHelper;
import org.y20k.trackbook.helpers.TrackbookKeys;

import java.util.List;


/**
 * TrackerService class
 */
public class TrackerService extends Service implements TrackbookKeys {

    /* Define log tag */
    private static final String LOG_TAG = TrackerService.class.getSimpleName();


    /* Main class variables */
    private Track mTrack;
    private CountDownTimer mTimer;
    private LocationManager mLocationManager;
    private LocationListener mGPSListener = null;
    private LocationListener mNetworkListener = null;
    private Location mCurrentBestLocation;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // acquire reference to Location Manager
        mLocationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        // checking for empty intent
        if (intent == null) {
            LogHelper.v(LOG_TAG, "Null-Intent received. Stopping self.");
            stopSelf();
        }

        // ACTION START
        else if (intent.getAction().equals(ACTION_START)) {
            LogHelper.v(LOG_TAG, "Service received command: START");

            // create a new track
            mTrack = new Track();

            // get last location
            if (intent.hasExtra(EXTRA_LAST_LOCATION)) {
                mCurrentBestLocation = intent.getParcelableExtra(EXTRA_LAST_LOCATION);
            }
            //  get last location - fallback
            if (mCurrentBestLocation == null) {
                mCurrentBestLocation = LocationHelper.determineLastKnownLocation(mLocationManager);
            }

            // add last location as waypoint to track
            addWayPointToTrack();

            // set timer to retrieve new locations and to prevent endless tracking
            mTimer = new CountDownTimer(EIGHT_HOURS_IN_MILLISECONDS, FIFTEEN_SECONDS_IN_MILLISECONDS) {
                @Override
                public void onTick(long millisUntilFinished) {
                    // update track duration
                    mTrack.setTrackDuration(EIGHT_HOURS_IN_MILLISECONDS - millisUntilFinished);
                    // try to add WayPoint to Track
                    addWayPointToTrack();
                    // update notification
                    NotificationHelper.update(mTrack, true);
                }

                @Override
                public void onFinish() {
                    // remove listeners
                    stopFindingLocation();
                }
            };
            mTimer.start();

            // create gps and network location listeners
            startFindingLocation();
        }

        // ACTION STOP
        else if (intent.getAction().equals(ACTION_STOP)) {
            LogHelper.v(LOG_TAG, "Service received command: STOP");

            // stop timer
            mTimer.cancel();

            // remove listeners
            stopFindingLocation();
        }

        // START_STICKY is used for services that are explicitly started and stopped as needed
        return START_STICKY;
    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    @Override
    public void onDestroy() {
        LogHelper.v(LOG_TAG, "onDestroy called.");

        // remove listeners
        stopFindingLocation();

        // cancel notification
        stopForeground(true);

        super.onDestroy();
    }


    /* Adds a new WayPoint to current track */
    private void addWayPointToTrack() {

        // create new WayPoint
        WayPoint newWayPoint = null;

        // get number of previously tracked WayPoints
        int trackSize = mTrack.getWayPoints().size();

        if (trackSize == 0) {
            // add first location to track
            newWayPoint = mTrack.addWayPoint(mCurrentBestLocation);
        } else {
            // get last waypoint and compare it to current location
            Location lastWayPoint = mTrack.getWayPointLocation(trackSize - 1);
            if (LocationHelper.isNewWayPoint(lastWayPoint, mCurrentBestLocation)) {
                // if new, add current best location to track
                newWayPoint = mTrack.addWayPoint(mCurrentBestLocation);
            }

        }

        // send local broadcast if new WayPoint added
        if (newWayPoint != null) {
            Intent i = new Intent();
            i.setAction(ACTION_TRACK_UPDATED);
            i.putExtra(EXTRA_TRACK, mTrack);
            i.putExtra(EXTRA_LAST_LOCATION, mCurrentBestLocation);
            LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(i);
        }

    }


    /* Creates a location listener */
    private LocationListener createLocationListener() {
        return new LocationListener() {
            public void onLocationChanged(Location location) {
                // check if the new location is better
                if (LocationHelper.isBetterLocation(location, mCurrentBestLocation)) {
                    // save location
                    mCurrentBestLocation = location;
                }
            }

            public void onStatusChanged(String provider, int status, Bundle extras) {
                // TODO do something
            }

            public void onProviderEnabled(String provider) {
                LogHelper.v(LOG_TAG, "Location provider enabled: " +  provider);
            }

            public void onProviderDisabled(String provider) {
                LogHelper.v(LOG_TAG, "Location provider disabled: " +  provider);
            }
        };
    }


    /* Creates gps and network location listeners */
    private void startFindingLocation() {
        // put up notification
        NotificationHelper.show(this,mTrack);

        // register location listeners and request updates
        List locationProviders = mLocationManager.getProviders(true);
        if (locationProviders.contains(LocationManager.GPS_PROVIDER)) {
            mGPSListener = createLocationListener();
        } else if (locationProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            mNetworkListener = createLocationListener();
        }
        LocationHelper.registerLocationListeners(mLocationManager, mGPSListener, mNetworkListener);
    }


    /* Removes gps and network location listeners */
    private void stopFindingLocation() {
        // remove listeners
        LocationHelper.removeLocationListeners(mLocationManager, mGPSListener, mNetworkListener);

        // notify MainActivityFragment
        Intent i = new Intent();
        i.setAction(ACTION_TRACKING_STOPPED);
        i.putExtra(EXTRA_TRACK, mTrack);
        i.putExtra(EXTRA_LAST_LOCATION, mCurrentBestLocation);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(i);

//        // cancel notification
//        NotificationHelper.stop();
        // change notification
        NotificationHelper.update(mTrack, false);
    }


}
