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

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import java.net.InetSocketAddress;

public class GameClientActivity extends GameActivity
{
    private static final String LOG_TAG = GameClientActivity.class.getSimpleName();

    private GameClientView m_view;
    private boolean m_pause;

    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        Log.d(LOG_TAG, "onCreate");

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        final String deviceId = (String) getIntent().getSerializableExtra(MainActivity.EXTRA_DEVICE_ID);
        final String playerName = (String) getIntent().getSerializableExtra(MainActivity.EXTRA_PLAYER_NAME);
        final InetSocketAddress serverAddr = (InetSocketAddress) getIntent().getSerializableExtra(MainActivity.EXTRA_SERVER_ADDRESS);
        final String serverDeviceId = (String) getIntent().getSerializableExtra(MainActivity.EXTRA_SERVER_DEVICE_ID);
        final String serverPlayerName = (String) getIntent().getSerializableExtra(MainActivity.EXTRA_SERVER_PLAYER_NAME);

        m_view = new GameClientView(this, deviceId, playerName, serverAddr, serverDeviceId, serverPlayerName);
        setContentView(m_view);
    }

    public void onResume()
    {
        super.onResume();
        Log.d( LOG_TAG, "onResume" );
    }

    public void onBackPressed()
    {
        Log.d( LOG_TAG, "onBackPressed" );
        if (!m_pause)
        {
            /* Activity will not be started any more */
            m_pause = true;
            final Intent result = m_view.onPauseEx();
            if (result != null)
                setResult( 0, result );
        }
        super.onBackPressed();
    }

    public void onPause()
    {
        Log.d( LOG_TAG, "onPause" );

        if (!m_pause)
        {
            /* Activity will not be started any more */
            m_pause = true;
            final Intent result = m_view.onPauseEx();
            if (result != null)
                setResult( 0, result );
        }

        super.onPause();
    }

    public void onDestroy()
    {
        super.onDestroy();
        Log.d(LOG_TAG, "onDestroy");
    }
}
