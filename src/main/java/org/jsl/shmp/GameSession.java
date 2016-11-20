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

import android.util.Log;
import org.jsl.collider.*;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.ReentrantLock;

abstract class GameSession implements Session.Listener
{
    private static final String LOG_TAG = GameSession.class.getSimpleName();

    private static final AtomicIntegerFieldUpdater<GameSession> s_bytesReceivedUpdater =
            AtomicIntegerFieldUpdater.newUpdater( GameSession.class, "m_bytesReceived" );

    public static StreamDefragger createStreamDefragger()
    {
        return new StreamDefragger( Protocol.Message.HEADER_SIZE )
        {
            protected int validateHeader( ByteBuffer header )
            {
                if (BuildConfig.DEBUG && (header.remaining() < Protocol.Message.HEADER_SIZE))
                    throw new AssertionError();
                final int messageLength = Protocol.Message.getMessageSize( header );
                if (messageLength <= 0)
                    return -1; /* StreamDefragger.getNext() will return StreamDefragger.INVALID_HEADER */
                return messageLength;
            }
        };
    }

    private class PingTimer implements TimerQueue.Task
    {
        private final long m_interval;
        private int m_bytesReceived;
        private int m_timeouts;

        public PingTimer( long interval, TimeUnit timeUnit )
        {
            m_interval = timeUnit.toMillis( interval );
        }

        public long run()
        {
            final PingConfig pingConfig = m_pingConfig;
            if (pingConfig.timeout > 0)
            {
                final int bytesReceived = s_bytesReceivedUpdater.get( GameSession.this );
                if (bytesReceived == m_bytesReceived)
                {
                    final int timeouts = ++m_timeouts;
                    final long timeout = (timeouts * pingConfig.interval);
                    if (timeout > pingConfig.timeout)
                    {
                        Log.d( LOG_TAG, m_session.getRemoteAddress() +
                                ": session timeout (" + timeout + " sec), close connection." );
                        m_session.closeConnection();
                    }
                }
                else
                {
                    m_bytesReceived = bytesReceived;
                    m_timeouts = 0;
                }
            }
            sendPing();
            return m_interval;
        }
    }

    private void sendPing()
    {
        final int pingID = ++m_pingID;
        final RetainableByteBuffer ping = Protocol.Ping.create( m_byteBufferPool, pingID );
        final long currentTime = System.currentTimeMillis();

        m_lock.lock();
        try
        {
            m_pingTime.put( pingID, currentTime );
        }
        finally
        {
            m_lock.unlock();
        }

        m_session.sendData( ping );
    }

    protected final Session m_session;
    protected final StreamDefragger m_streamDefragger;
    private final PingConfig m_pingConfig;
    private final PingTimer m_pingTimer;
    private final GameView m_view;
    private RetainableByteBufferPool m_byteBufferPool;

    private final ReentrantLock m_lock;
    private final HashMap<Integer, Long> m_pingTime;
    private int m_pingID;

    private volatile int m_bytesReceived;

    public GameSession( Session session, StreamDefragger streamDefragger, PingConfig pingConfig, GameView view )
    {
        m_session = session;
        m_streamDefragger = streamDefragger;
        m_pingConfig = pingConfig;
        m_view = view;
        m_byteBufferPool = new RetainableByteBufferPool( 1024, true, Protocol.BYTE_ORDER );

        final long pingInterval = pingConfig.interval;
        if (pingInterval > 0)
        {
            final TimeUnit timeUnit = pingConfig.timeUnit;
            final TimerQueue timerQueue = pingConfig.timerQueue;
            m_pingTimer = new PingTimer( pingInterval, timeUnit );
            timerQueue.schedule( m_pingTimer, pingInterval, timeUnit );
            m_lock = new ReentrantLock();
            m_pingTime = new HashMap<Integer, Long>();
        }
        else
        {
            m_pingTimer = null;
            m_lock = null;
            m_pingTime = null;
        }
    }

    public void onDataReceived( RetainableByteBuffer data )
    {
        final int bytesReceived = data.remaining();
        if (BuildConfig.DEBUG && (bytesReceived == 0))
            throw new AssertionError();

        s_bytesReceivedUpdater.addAndGet( this, bytesReceived );

        RetainableByteBuffer msg = m_streamDefragger.getNext( data );
        while (msg != null)
        {
            if (msg == StreamDefragger.INVALID_HEADER)
            {
                Log.w( LOG_TAG, m_session.getRemoteAddress() +
                        ": invalid message header received, close connection." );
                m_session.closeConnection();
                break;
            }
            else
            {
                final int messageID = Protocol.Message.getMessageId( msg );
                if (messageID == Protocol.Ping.ID)
                {
                    final int sequenceNumber = Protocol.Ping.getSequenceNumber( msg );
                    final RetainableByteBuffer pong = Protocol.Pong.create( m_byteBufferPool, sequenceNumber );
                    m_session.sendData( pong );
                    pong.release();
                }
                else if (messageID == Protocol.Pong.ID)
                {
                    long ping = -1;
                    final int sequenceNumber = Protocol.Pong.getSequenceNumber( msg );
                    m_lock.lock();
                    try
                    {
                        final Long pingTime = m_pingTime.remove( sequenceNumber );
                        if (pingTime == null)
                        {
                            Log.e( LOG_TAG, m_session.getRemoteAddress() +
                                    ": internal error: ping " + sequenceNumber + " not found." );
                        }
                        else
                        {
                            ping = System.currentTimeMillis();
                            ping -= pingTime;
                        }
                    }
                    finally
                    {
                        m_lock.unlock();
                    }

                    if (ping >= 0)
                        m_view.setPing( (int) ping );
                }
                else
                {
                    int rc = onMessageReceived( messageID, msg );
                    if (rc != 0)
                        break;
                }
            }
            msg = m_streamDefragger.getNext();
        }
    }

    abstract int onMessageReceived( int messageID, RetainableByteBuffer msg );

    public int sendMessage( RetainableByteBuffer msg )
    {
        return m_session.sendData( msg );
    }

    public int sendMessage( ByteBuffer msg )
    {
        return m_session.sendData( msg );
    }

    public void onConnectionClosed()
    {
        boolean interrupted = false;
        if (m_pingTimer != null)
        {
            try
            {
                final TimerQueue timerQueue = m_pingConfig.timerQueue;
                timerQueue.cancel( m_pingTimer );
            }
            catch (final InterruptedException ex)
            {
                Log.w( LOG_TAG, ex.toString() );
                interrupted = true;
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }
}
