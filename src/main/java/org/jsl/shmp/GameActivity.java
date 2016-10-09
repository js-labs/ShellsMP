/*
 * Copyright (C) 2015 Sergey Zubarev, info@js-labs.org
 *
 * This file is a part of ShellsMP application.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jsl.shmp;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ConfigurationInfo;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

public class GameActivity extends Activity
{
    private static final String LOG_TAG = GameActivity.class.getSimpleName();

    private static final float TICK_VOLUME = 0.4f;

    private SoundPool m_soundPool;
    private int m_soundBallSet;
    private int m_soundCapSet;
    private int m_soundTick;

    protected void playSound_BallPut()
    {
        m_soundPool.play( m_soundBallSet, 1f, 1f, /*priority*/1, /*loop*/0, /*rate*/1f );
    }

    protected void playSound_CapPut()
    {
        m_soundPool.play( m_soundCapSet, 1f, 1f, /*priority*/1, /*loop*/0, /*rate*/1f );
    }

    protected void playSound_Tick()
    {
        m_soundPool.play( m_soundTick, TICK_VOLUME, TICK_VOLUME, /*priority*/1, /*loop*/0, /*rate*/1f );
    }

    public void onCreate( Bundle savedInstanceState )
    {
        Log.d( LOG_TAG, "onCreate" );
        super.onCreate( savedInstanceState );

        getWindow().addFlags( WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON );
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED );

        final ActivityManager activityManager = (ActivityManager) getSystemService( Context.ACTIVITY_SERVICE);
        final ConfigurationInfo configurationInfo = activityManager.getDeviceConfigurationInfo();
        //final boolean supportsEs2 = configurationInfo.reqGlEsVersion >= 0x20000;
        Log.d( LOG_TAG, "Supported OpenGL API: " + configurationInfo.reqGlEsVersion );

        m_soundPool = new SoundPool( 3, AudioManager.STREAM_MUSIC, 0 );
        m_soundBallSet = m_soundPool.load( this, R.raw.ball_set, 1 );
        m_soundCapSet = m_soundPool.load( this, R.raw.cap_set, 1 );
        m_soundTick = m_soundPool.load( this, R.raw.tick, 1 );
    }

    public void onDestroy()
    {
        Log.d( LOG_TAG, "onDestroy" );
        m_soundPool.release();
        super.onDestroy();
    }

    public void showMessageAndFinish( int titleStringID, int messageStringID, final String deviceId )
    {
        final AlertDialog.Builder builder = new AlertDialog.Builder( this );
        builder.setTitle( getString(titleStringID) );
        builder.setMessage( getString(messageStringID) );
        builder.setPositiveButton( getString(R.string.close), null );
        runOnUiThread( new Runnable() {
            public void run() {
                final AlertDialog alertDialog = builder.create();
                alertDialog.setOnDismissListener( new DialogInterface.OnDismissListener() {
                    public void onDismiss( DialogInterface dialog ) {
                        final Intent result = new Intent();
                        result.putExtra(MainActivity.EXTRA_DEVICE_ID, deviceId);
                        result.putExtra(MainActivity.EXTRA_WIN, true);
                        GameActivity.this.setResult(0, result);
                        GameActivity.this.finish();
                    }
                } );
                alertDialog.show();
            }
        } );
    }

    public void showMessage( int titleStringID, int messageStringID )
    {
        final AlertDialog.Builder builder = new AlertDialog.Builder( this );
        builder.setTitle( getString(titleStringID) );
        builder.setMessage( getString(messageStringID) );
        builder.setPositiveButton( getString(R.string.close), null );

        if (Looper.getMainLooper().getThread() == Thread.currentThread())
        {
            final AlertDialog alertDialog = builder.create();
            alertDialog.show();
        }
        else
        {
            runOnUiThread( new Runnable() {
                public void run()
                {
                    final AlertDialog alertDialog = builder.create();
                    alertDialog.show();
                }
            } );
        }
    }
}
