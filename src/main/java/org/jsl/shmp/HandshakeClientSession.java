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
import org.jsl.collider.RetainableByteBuffer;
import org.jsl.collider.Session;
import org.jsl.collider.StreamDefragger;
import org.jsl.collider.TimerQueue;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

public class HandshakeClientSession implements Session.Listener
{
    private static final String LOG_TAG = HandshakeClientSession.class.getSimpleName();
    private static final String LOG_PROTOCOL = "Protocol";

    private final GameClientView m_view;
    private final Session m_session;
    private final PingConfig m_pingConfig;
    private final StreamDefragger m_streamDefragger;
    private TimerHandler m_timerHandler;

    private class TimerHandler implements TimerQueue.Task
    {
        public long run()
        {
            Log.i( LOG_TAG, m_session.getRemoteAddress() + ": session timeout, close connection." );
            m_session.closeConnection();
            return 0; /*once*/
        }
    }

    public HandshakeClientSession(
            GameClientView view,
            Session session,
            PingConfig pingConfig,
            short desiredTableHeight,
            String deviceId,
            String playerName )
    {
        m_view = view;
        m_session = session;
        m_pingConfig = pingConfig;
        m_streamDefragger = GameSession.createStreamDefragger();

        final long pingTimeout = pingConfig.timeout;
        if (pingTimeout > 0)
        {
            final TimeUnit timeUnit = pingConfig.timeUnit;
            final TimerQueue timerQueue = pingConfig.timerQueue;
            m_timerHandler = new TimerHandler();
            timerQueue.schedule( m_timerHandler, pingTimeout, timeUnit );
        }

        final ByteBuffer handshakeRequest = Protocol.HandshakeRequest.create(Protocol.VERSION, desiredTableHeight, deviceId, playerName);
        session.sendData( handshakeRequest );
    }

    public void onDataReceived( RetainableByteBuffer data )
    {
        RetainableByteBuffer msg = m_streamDefragger.getNext( data );
        if (msg == null)
        {
            /* HandshakeReply is fragmented, strange but can happen */
            Log.i( LOG_TAG, m_session.getRemoteAddress() + ": fragmented HandshakeReply." );
        }
        else if (msg == StreamDefragger.INVALID_HEADER)
        {
            Log.i( LOG_TAG, m_session.getRemoteAddress() +
                    ": invalid message received, close connection." );
            m_session.closeConnection();
        }
        else
        {
            if (m_timerHandler != null)
            {
                boolean interrupted = false;
                try
                {
                    final TimerQueue timerQueue = m_pingConfig.timerQueue;
                    if (timerQueue.cancel(m_timerHandler) != 0)
                    {
                        /* timer fired, session is being closed,
                         * onConnectionClosed() will be called soon, do nothing here.
                         */
                        return;
                    }
                }
                catch (final InterruptedException ex)
                {
                    interrupted = true;
                }
                finally
                {
                    if (interrupted)
                        Thread.currentThread().interrupt();
                }
            }

            final short messageId = Protocol.Message.getMessageId( msg );
            if (messageId == Protocol.HandshakeReplyOk.ID)
            {
                if (Log.isLoggable(LOG_PROTOCOL, Log.VERBOSE))
                {
                    final StringBuilder sb = new StringBuilder();
                    Protocol.HandshakeReplyOk.print(sb, msg);
                    Log.v(LOG_PROTOCOL, sb.toString());
                }

                final short virtualTableHeight = Protocol.HandshakeReplyOk.getTableHeight( msg );
                final short virtualBallRadius = Protocol.HandshakeReplyOk.getBallRadius( msg );
                Log.i( LOG_TAG, m_session.getRemoteAddress() + ": handshake reply ok" );

                final GameClientSession gameClientSession = new GameClientSession(
                        m_session,
                        m_streamDefragger, m_pingConfig,
                        m_view );

                m_session.replaceListener( gameClientSession );
                m_view.onConnected( gameClientSession, virtualTableHeight, virtualBallRadius );
            }
            else if (messageId == Protocol.HandshakeReplyFail.ID)
            {
                if (Log.isLoggable(LOG_PROTOCOL, Log.VERBOSE))
                {
                    final StringBuilder sb = new StringBuilder();
                    Protocol.HandshakeReplyFail.print(sb, msg);
                    Log.v(LOG_PROTOCOL, sb.toString());
                }
            }
            else
            {
                Log.i( LOG_TAG, m_session.getRemoteAddress() +
                        ": unexpected message " + messageId + " received, closing connection." );
                m_session.closeConnection();
            }
        }
    }

    public void onConnectionClosed()
    {
        Log.d( LOG_TAG, m_session.getRemoteAddress() + ": connection closed" );

        boolean interrupted = false;
        if (m_timerHandler != null)
        {
            try
            {
                final TimerQueue timerQueue = m_pingConfig.timerQueue;
                timerQueue.cancel( m_timerHandler );
            }
            catch (final InterruptedException ex)
            {
                Log.w( LOG_TAG, ex.toString() );
                interrupted = true;
            }
        }

        m_streamDefragger.close();
        m_view.onServerDisconnected();

        if (interrupted)
            Thread.currentThread().interrupt();
    }
}
