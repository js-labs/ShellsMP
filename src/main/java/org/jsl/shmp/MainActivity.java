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
import android.app.AlertDialog;
import android.content.*;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.*;
import android.widget.*;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class MainActivity extends Activity
{
    private static final String LOG_TAG = MainActivity.class.getSimpleName();

    private static final int REQUEST_CODE_GAME = 1;

    private static final String NSD_SERVICE_TYPE = "_shells_mp._tcp.";
    private static final String NSD_SERVICE_NAME_SEPARATOR = ":";

    public static final String EXTRA_DEVICE_ID = "device-id";
    public static final String EXTRA_SERVER_DEVICE_ID = "server-device-id";
    public static final String EXTRA_SERVER_PLAYER_NAME = "server-player-name";
    public static final String EXTRA_GAMBLE_TIME = "gamble-time";
    public static final String EXTRA_CAPS = "caps";
    public static final String EXTRA_PLAYER_NAME = "player-name";
    public static final String EXTRA_SERVER_ADDRESS = "game-server-address";
    public static final String EXTRA_TITLE_ID = "title-id";
    public static final String EXTRA_MESSAGE_ID = "message-id";
    public static final String EXTRA_WIN = "win";

    private static final int STATE_START_DISCOVERY = 1;
    private static final int STATE_DISCOVERY = 2;
    private static final int STATE_STOP_DISCOVERY_START_GAME = 3;
    private static final int STATE_STOP_DISCOVERY_RESOLVE_GAME = 4;
    private static final int STATE_RESOLVE_GAME = 5;

    /* Shared preferences keys */
    private static final String SPK_SCORE_PREFIX = "score-";

    private String m_deviceID;
    private String m_playerName;

    private NsdManager m_nsdManager;
    private ListViewAdapter m_discoveredGames;
    private Button m_buttonStartGame;

    /* Will not be modified */
    private NsdManager.DiscoveryListener m_discoveryListener;
    private NsdManager.ResolveListener m_resolveListener;

    private ReentrantLock m_lock;
    private Condition m_cond;
    private boolean m_stop;
    private int m_state;
    private GameInfo m_gameInfo;

    private static class GameInfo
    {
        final NsdServiceInfo serviceInfo;
        final String deviceId;
        final String playerName;
        final String score;

        public GameInfo(NsdServiceInfo serviceInfo, String deviceId, String playerName, String score)
        {
            this.serviceInfo = serviceInfo;
            this.deviceId = deviceId;
            this.playerName = playerName;
            this.score = score;
        }
    }

    private static class ListViewAdapter extends ArrayAdapter<GameInfo>
    {
        private final LayoutInflater m_inflater;
        private final TreeMap<String, GameInfo> m_gameInfoByServiceName;
        private GameInfo [] m_items;

        private static class ViewInfo
        {
            public final TextView playerName;
            public final TextView score;

            public ViewInfo(TextView playerName, TextView score)
            {
                this.playerName = playerName;
                this.score = score;
            }
        }

        private void updateItems()
        {
            m_items = new GameInfo[m_gameInfoByServiceName.size()];
            final Iterator<TreeMap.Entry<String, GameInfo>> it = m_gameInfoByServiceName.entrySet().iterator();
            for (int idx=0; it.hasNext(); idx++)
            {
                final Map.Entry<String, GameInfo> entry = it.next();
                m_items[idx] = entry.getValue();
            }
            notifyDataSetChanged();
        }

        public ListViewAdapter( Context context )
        {
            super( context, android.R.layout.simple_list_item_1 );
            m_inflater = (LayoutInflater) context.getSystemService( Context.LAYOUT_INFLATER_SERVICE );
            m_gameInfoByServiceName = new TreeMap<String, GameInfo>();
        }

        public void add( GameInfo gameInfo )
        {
            final String serviceName = gameInfo.serviceInfo.getServiceName();
            if (m_gameInfoByServiceName.get(serviceName) == null)
            {
                m_gameInfoByServiceName.put(serviceName, gameInfo);
                updateItems();
            }
            else
                Log.d( LOG_TAG, "Internal error: [" + serviceName + "] already registered." );
        }

        public void remove( NsdServiceInfo serviceInfo )
        {
            final String serviceName = serviceInfo.getServiceName();
            if (m_gameInfoByServiceName.remove(serviceName) != null)
                updateItems();
        }

        public void clear()
        {
            m_gameInfoByServiceName.clear();
            updateItems();
        }

        public int getCount()
        {
            return ((m_items == null) ? 0 : m_items.length);
        }

        public GameInfo getItem( int position )
        {
            return m_items[position];
        }

        public long getItemId( int position )
        {
            return position;
        }

        public View getView( int position, View convertView, ViewGroup parent )
        {
            View view;
            ViewInfo viewInfo;

            if (convertView == null)
            {
                view = m_inflater.inflate(R.layout.list_view_item_games, parent, false);
                final TextView playerName = (TextView) view.findViewById(R.id.textViewPlayerName);
                final TextView score = (TextView) view.findViewById(R.id.textViewScore);
                viewInfo = new ViewInfo(playerName, score);
                view.setTag(viewInfo);
            }
            else
            {
                view = convertView;
                viewInfo = (ViewInfo) view.getTag();
            }

            viewInfo.playerName.setText(m_items[position].playerName);
            viewInfo.score.setText(m_items[position].score);

            return view;
        }
    }

    private class DiscoveryListener implements NsdManager.DiscoveryListener
    {
        public void onStartDiscoveryFailed( String serviceType, int errorCode )
        {
            Log.d( LOG_TAG, "onStartDiscoveryFailed" );
            m_lock.lock();
            try
            {
                if (BuildConfig.DEBUG && (m_discoveryListener != this))
                    throw new AssertionError();

                m_state = 0;

                if (m_stop)
                    m_cond.signal();
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onStopDiscoveryFailed( String serviceType, int errorCode )
        {
            Log.d( LOG_TAG, "onStopDiscoveryFailed" );
            m_lock.lock();
            try
            {
                if (BuildConfig.DEBUG && (m_discoveryListener != this))
                    throw new AssertionError();

                m_state = 0;
                m_cond.signal();
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onDiscoveryStarted( String serviceType )
        {
            Log.d( LOG_TAG, "onDiscoveryStarted" );
            m_lock.lock();
            try
            {
                if (BuildConfig.DEBUG && (m_discoveryListener != this))
                    throw new AssertionError();

                if (m_stop)
                    m_nsdManager.stopServiceDiscovery( this );
                else if (m_state == STATE_START_DISCOVERY)
                    m_state = STATE_DISCOVERY;
                else if (BuildConfig.DEBUG)
                    throw new AssertionError();
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onDiscoveryStopped( String serviceType )
        {
            Log.d( LOG_TAG, "onDiscoveryStopped" );
            m_lock.lock();
            try
            {
                if (m_stop)
                {
                    m_state = 0;
                    m_cond.signal();
                }
                else if (m_state == STATE_STOP_DISCOVERY_START_GAME)
                {
                    m_state = 0;
                    runOnUiThread( new Runnable() {
                        public void run() {
                            startGame();
                        }
                    } );
                }
                else if (m_state == STATE_STOP_DISCOVERY_RESOLVE_GAME)
                {
                    m_state = STATE_RESOLVE_GAME;
                    m_nsdManager.resolveService( m_gameInfo.serviceInfo, m_resolveListener );
                }
                else if (BuildConfig.DEBUG)
                {
                    /* Not expected */
                    throw new AssertionError();
                }
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onServiceFound( NsdServiceInfo nsdServiceInfo )
        {
            Log.d(LOG_TAG, "onServiceFound: " + nsdServiceInfo.toString());
            final String [] ss = nsdServiceInfo.getServiceName().split( NSD_SERVICE_NAME_SEPARATOR );
            if ((ss.length >= 2) && (ss[1].length() > 0))
            {
                if (ss[0].compareTo(m_deviceID) == 0)
                {
                    /* Sometimes discovery can still see the registered game just finished */
                    Log.d( LOG_TAG, "Skip service with the same device ID: " + nsdServiceInfo );
                }
                else
                {
                    final SharedPreferences sharedPreferences = getPreferences(MODE_PRIVATE);
                    final String deviceId = ss[0];
                    final String key = SPK_SCORE_PREFIX + deviceId;
                    final String score = sharedPreferences.getString(key, "0-0");
                    final String playerName = new String(Base64.decode(ss[1], 0));
                    final GameInfo gameInfo = new GameInfo(nsdServiceInfo, deviceId, playerName, score);
                    runOnUiThread( new Runnable() {
                        public void run() {
                            m_discoveredGames.add( gameInfo );
                        }
                    } );
                }
            }
            else
                Log.w( LOG_TAG, "Invalid service found: " + nsdServiceInfo );
        }

        public void onServiceLost( final NsdServiceInfo nsdServiceInfo )
        {
            Log.d( LOG_TAG, "onServiceLost: " + nsdServiceInfo.toString() );
            final String [] ss = nsdServiceInfo.getServiceName().split( NSD_SERVICE_NAME_SEPARATOR );
            if ((ss.length >= 2) && (ss[1].length() > 0))
            {
                if (ss[0].compareTo(m_deviceID) != 0)
                {
                    runOnUiThread( new Runnable() {
                        public void run() {
                            m_discoveredGames.remove( nsdServiceInfo );
                        }
                    } );
                }
            }
        }
    }

    private class ResolveListener implements NsdManager.ResolveListener
    {
        public void onResolveFailed( NsdServiceInfo serviceInfo, int errorCode )
        {
            Log.d( LOG_TAG, "onResolveFailed: " + errorCode + ": " + serviceInfo );
            m_lock.lock();
            try
            {
                if (m_stop)
                {
                    m_state = 0;
                    m_cond.signal();
                }
                else if (m_state == STATE_RESOLVE_GAME)
                {
                    m_state = STATE_START_DISCOVERY;
                    m_nsdManager.discoverServices( NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, m_discoveryListener );
                }
                else if (BuildConfig.DEBUG)
                    throw new AssertionError();
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onServiceResolved( NsdServiceInfo serviceInfo )
        {
            Log.d( LOG_TAG, "onServiceResolved: " + serviceInfo );
            m_lock.lock();
            try
            {
                if (m_stop)
                {
                    m_state = 0;
                    m_cond.signal();
                }
                else if (m_state == STATE_RESOLVE_GAME)
                {
                    final InetSocketAddress serverAddr = new InetSocketAddress( serviceInfo.getHost(), serviceInfo.getPort() );
                    final String serverDeviceId = m_gameInfo.deviceId;
                    final String serverPlayerName = m_gameInfo.playerName;
                    m_gameInfo = null;
                    m_state = 0;

                    runOnUiThread( new Runnable() {
                        public void run() {
                            connectGame(serverAddr, serverDeviceId, serverPlayerName);
                        }
                    } );
                }
                else if (BuildConfig.DEBUG)
                    throw new AssertionError();
            }
            finally
            {
                m_lock.unlock();
            }
        }
    }

    private class SettingsDialogClickListener implements DialogInterface.OnClickListener
    {
        private final EditText m_editTextPlayerName;

        public SettingsDialogClickListener( EditText editTextPlayerName )
        {
            m_editTextPlayerName = editTextPlayerName;
        }

        public void onClick( DialogInterface dialog, int which )
        {
            final String playerName = m_editTextPlayerName.getText().toString();
            if (playerName.compareTo(m_playerName) != 0)
            {
                m_playerName = playerName;
                final String title = getString(R.string.app_name) + " : " + m_playerName;
                setTitle( title );

                final SharedPreferences sharedPreferences = getPreferences( Context.MODE_PRIVATE );
                final SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString( Prefs.PLAYER_NAME, m_playerName );
                editor.apply();
            }
        }
    }

    private String getDeviceID()
    {
        long deviceID = 0;
        final String str = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (str != null)
        {
            try
            {
                final BigInteger bi = new BigInteger( str, 16 );
                deviceID = bi.longValue();
            }
            catch (final NumberFormatException ex)
            {
                /* Nothing critical */
            }
        }

        if (deviceID == 0)
        {
            /* Let's use random number */
            deviceID = new Random().nextLong();
        }

        final byte [] bb = new byte[Long.SIZE / Byte.SIZE];
        for (int idx=(bb.length - 1); idx>=0; idx--)
        {
            bb[idx] = (byte) (deviceID & 0xFF);
            deviceID >>= Byte.SIZE;
        }

        return Base64.encodeToString(bb, (Base64.NO_PADDING | Base64.NO_WRAP));
    }

    private void startGame()
    {
        final Intent intent = new Intent( MainActivity.this, GameServerActivity.class );
        intent.putExtra(EXTRA_DEVICE_ID, m_deviceID);
        intent.putExtra(EXTRA_PLAYER_NAME, m_playerName);
        intent.putExtra(EXTRA_GAMBLE_TIME, 20);
        intent.putExtra(EXTRA_CAPS, 3);
        startActivityForResult(intent, REQUEST_CODE_GAME);
    }

    private void connectGame(InetSocketAddress serverAddr, String serverDeviceId, String serverPlayerName)
    {
        final Intent intent = new Intent( MainActivity.this, GameClientActivity.class );
        intent.putExtra(EXTRA_DEVICE_ID, m_deviceID);
        intent.putExtra(EXTRA_PLAYER_NAME, m_playerName);
        intent.putExtra(EXTRA_SERVER_ADDRESS, serverAddr);
        intent.putExtra(EXTRA_SERVER_DEVICE_ID, serverDeviceId);
        intent.putExtra(EXTRA_SERVER_PLAYER_NAME, serverPlayerName);
        startActivityForResult(intent, REQUEST_CODE_GAME);
    }

    public void onCreate( Bundle savedInstanceState )
    {
        super.onCreate( savedInstanceState );
        Log.d( LOG_TAG, "onCreate" );

        /*********************************************************************/

        m_deviceID = getDeviceID();

        m_nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        if (m_nsdManager == null)
        {
            final AlertDialog.Builder builder = new AlertDialog.Builder( this );
            builder.setTitle( getString(R.string.system_error) );
            builder.setMessage( getString(R.string.nsd_not_found) );
            builder.setPositiveButton( getString(R.string.close), null );
            final AlertDialog alertDialog = builder.create();
            alertDialog.show();
            finish();
            return;
        }

        final SharedPreferences prefs = getSharedPreferences( MainActivity.class.getSimpleName(), Context.MODE_PRIVATE );
        m_playerName = prefs.getString( Prefs.PLAYER_NAME, "" );
        if (m_playerName.isEmpty())
            m_playerName = Build.MODEL;

        m_discoveredGames = new ListViewAdapter( this );
        m_discoveryListener = new DiscoveryListener();
        m_resolveListener = new ResolveListener();
        m_lock = new ReentrantLock();
        m_cond = m_lock.newCondition();

        getWindow().addFlags( WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON );
        setContentView( R.layout.main );

        final String title = getString(R.string.app_name) + " : " + m_playerName;
        setTitle( title );

        final ListView listView = (ListView) findViewById( R.id.listViewGames );
        listView.setAdapter( m_discoveredGames );
        listView.setLongClickable( true );
        listView.setOnItemLongClickListener( new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick( AdapterView<?> parent, View view, int position, long id )
            {
                m_lock.lock();
                try
                {
                    if (BuildConfig.DEBUG && (m_state != STATE_START_DISCOVERY) && (m_state != STATE_DISCOVERY))
                        throw new AssertionError();

                    m_state = STATE_STOP_DISCOVERY_RESOLVE_GAME;
                    m_gameInfo = m_discoveredGames.getItem( position );

                    m_nsdManager.stopServiceDiscovery( m_discoveryListener );
                }
                finally
                {
                    m_lock.unlock();
                }
                return false;
            }
        } );

        m_buttonStartGame = (Button) findViewById( R.id.buttonStartGame );
    }

    public void onDestroy()
    {
        Log.d( LOG_TAG, "onDestroy" );
        super.onDestroy();
    }

    public void onResume()
    {
        super.onResume();
        Log.d( LOG_TAG, "onResume" );

        if (BuildConfig.DEBUG && (m_state != 0))
            throw new AssertionError();

        m_buttonStartGame.setEnabled( true );

        m_stop = false;
        m_state = STATE_START_DISCOVERY;
        m_nsdManager.discoverServices( NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, m_discoveryListener );

        Log.d( LOG_TAG, "onResume: done" );
    }

    public void onPause()
    {
        super.onPause();
        Log.d( LOG_TAG, "onPause" );

        m_lock.lock();
        try
        {
            if (BuildConfig.DEBUG && m_stop)
                throw new AssertionError();

            m_stop = true;
            switch (m_state)
            {
                case STATE_START_DISCOVERY:
                    while (m_state != 0)
                        m_cond.await();
                break;

                case STATE_DISCOVERY:
                    m_nsdManager.stopServiceDiscovery( m_discoveryListener );
                    while (m_state != 0)
                        m_cond.await();
                break;

                case STATE_STOP_DISCOVERY_START_GAME:
                case STATE_STOP_DISCOVERY_RESOLVE_GAME:
                case STATE_RESOLVE_GAME:
                    while (m_state != 0)
                        m_cond.await();
                break;

                case 0:
                    /* Do nothing */
                break;

                default:
                    if (BuildConfig.DEBUG)
                        throw new AssertionError();
            }
        }
        catch (final InterruptedException ex)
        {
            Log.e( LOG_TAG, ex.toString() );
        }
        finally
        {
            m_lock.unlock();
        }

        m_discoveredGames.clear();
        Log.d( LOG_TAG, "onPause: done" );
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (requestCode == REQUEST_CODE_GAME)
        {
            if (data != null)
            {
                final int titleId = data.getIntExtra(EXTRA_TITLE_ID, -1);
                final int messageId = data.getIntExtra(EXTRA_MESSAGE_ID, -1);
                final String deviceId = data.getStringExtra(EXTRA_DEVICE_ID);
                final boolean win = data.getBooleanExtra(EXTRA_WIN, false);

                Log.d(LOG_TAG, "onActivityResult: REQUEST_CODE_GAME: deviceId=[" + deviceId + "] win=" + win);

                if ((titleId > 0) && (messageId > 0))
                {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle(getString(titleId));
                    builder.setMessage(getString(messageId));
                    builder.setPositiveButton(getString(R.string.close), null);
                    final AlertDialog alertDialog = builder.create();
                    alertDialog.show();
                }

                if ((deviceId != null) && !deviceId.isEmpty())
                {
                    final SharedPreferences sharedPreferences = getPreferences(MODE_PRIVATE);
                    final String key = SPK_SCORE_PREFIX + deviceId;
                    final String score = sharedPreferences.getString(key, "0-0");
                    final String [] ss = score.split("-");
                    int score0;
                    int score1;
                    if (ss.length == 2)
                    {
                        score0 = Integer.valueOf(ss[0]);
                        score1 = Integer.valueOf(ss[1]);
                    }
                    else
                    {
                        score0 = 0;
                        score1 = 0;
                    }
                    if (win)
                        score0++;
                    else
                        score1++;
                    final SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putString(key, Integer.toString(score0) + '-' + Integer.toString(score1));
                    editor.apply();
                }
            }
        }
        else
            Log.e(LOG_TAG, "Internal error");
    }

    public void onStartGame( View view )
    {
        Log.d( LOG_TAG, "onStartGame" );

        if (BuildConfig.DEBUG && (view != m_buttonStartGame))
            throw new AssertionError();
        view.setEnabled( false );

        /* 1) stop discovery
         * 2) start game activity
         */
        m_lock.lock();
        try
        {
            if ((m_state == STATE_START_DISCOVERY) || (m_state == STATE_DISCOVERY))
            {
                m_state = STATE_STOP_DISCOVERY_START_GAME;
                m_nsdManager.stopServiceDiscovery( m_discoveryListener );
            }
            else if (BuildConfig.DEBUG)
                throw new AssertionError();
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public static NsdServiceInfo createServiceInfo( String deviceID, String playerName, int portNumber )
    {
        final NsdServiceInfo serviceInfo = new NsdServiceInfo();
        final int flags = (Base64.NO_PADDING | Base64.NO_WRAP);
        final String serviceName =
                deviceID + NSD_SERVICE_NAME_SEPARATOR +
                Base64.encodeToString(playerName.getBytes(), flags) + NSD_SERVICE_NAME_SEPARATOR;
        serviceInfo.setServiceType( NSD_SERVICE_TYPE );
        serviceInfo.setServiceName( serviceName );
        serviceInfo.setPort( portNumber );
        return serviceInfo;
    }

    public boolean onCreateOptionsMenu( Menu menu )
    {
        getMenuInflater().inflate( R.menu.menu, menu );
        return true;
    }

    public boolean onOptionsItemSelected( MenuItem item )
    {
        final LayoutInflater layoutInflater = LayoutInflater.from( this );
        final AlertDialog.Builder dialogBuilder = new AlertDialog.Builder( this );
        switch (item.getItemId())
        {
            case R.id.actionSettings:
            {
                final View dialogView = layoutInflater.inflate(R.layout.dialog_settings, null);
                final EditText editTextPlayerName = (EditText) dialogView.findViewById( R.id.editTextPlayerName );
                editTextPlayerName.setText( m_playerName );
                dialogBuilder.setTitle( R.string.settings );
                dialogBuilder.setView( dialogView );
                dialogBuilder.setCancelable( true );
                dialogBuilder.setPositiveButton( getString(R.string.set), new SettingsDialogClickListener(editTextPlayerName) );
                dialogBuilder.setNegativeButton( getString(R.string.cancel), null );
                final AlertDialog dialog = dialogBuilder.create();
                dialog.show();
            }
            break;

            case R.id.actionAbout:
            {
                /*
                final View dialogView = layoutInflater.inflate( R.layout.dialog_about, null );
                final TextView textView = (TextView) dialogView.findViewById( R.id.textView );
                textView.setMovementMethod( LinkMovementMethod.getInstance() /new ScrollingMovementMethod()/ );
                dialogBuilder.setTitle( R.string.about );
                dialogBuilder.setView( dialogView );
                dialogBuilder.setPositiveButton( getString(R.string.close), null );
                final AlertDialog dialog = dialogBuilder.create();
                dialog.show();
                */
            }
            break;
        }
        return super.onOptionsItemSelected( item );
    }
}
