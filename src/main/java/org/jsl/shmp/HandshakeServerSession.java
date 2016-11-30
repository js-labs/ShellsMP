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

public class HandshakeServerSession implements Session.Listener
{
    private static final String LOG_TAG = HandshakeServerSession.class.getSimpleName();
    private static final String LOG_PROTOCOL = "Protocol";

    private final GameServerView m_view;
    private final Session m_session;
    private final PingConfig m_pingConfig;
    private final short m_desiredTableHeight;
    private final short m_ballRadius;
    private final short m_caps;
    private final StreamDefragger m_streamDefragger;
    private final TimerHandler m_timerHandler;

    private class TimerHandler implements TimerQueue.Task
    {
        public long run()
        {
            Log.i( LOG_TAG, m_session.getRemoteAddress() + ": session timeout, close connection." );
            m_session.closeConnection();
            return 0; /*once*/
        }
    }

    public HandshakeServerSession(
            GameServerView view,
            Session session,
            PingConfig pingConfig,
            short desiredTableHeight,
            short ballRadius,
            short caps )
    {
        m_view = view;
        m_session = session;
        m_pingConfig = pingConfig;
        m_desiredTableHeight = desiredTableHeight;
        m_ballRadius = ballRadius;
        m_caps = caps;
        m_streamDefragger = GameSession.createStreamDefragger();

        final long pingTimeout = pingConfig.timeout;
        if (pingTimeout > 0)
        {
            final TimeUnit timeUnit = pingConfig.timeUnit;
            final TimerQueue timerQueue = pingConfig.timerQueue;
            m_timerHandler = new TimerHandler();
            timerQueue.schedule( m_timerHandler, pingTimeout, timeUnit );
        }
        else
            m_timerHandler = null;
    }

    public void onDataReceived( RetainableByteBuffer data )
    {
        final RetainableByteBuffer msg = m_streamDefragger.getNext( data );
        if (msg == null)
        {
            /* HandshakeRequest is fragmented, strange but can happen */
            Log.i( LOG_TAG, m_session.getRemoteAddress() + ": fragmented HandshakeRequest." );
        }
        else if (msg == StreamDefragger.INVALID_HEADER)
        {
            Log.i( LOG_TAG, m_session.getRemoteAddress() +
                    ": invalid <HandshakeRequest> received, close connection." );
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
            if (messageId == Protocol.HandshakeRequest.ID)
            {
                if (Log.isLoggable(LOG_PROTOCOL, Log.VERBOSE))
                {
                    final StringBuilder sb = new StringBuilder();
                    Protocol.HandshakeRequest.print(sb, msg);
                    Log.v(LOG_PROTOCOL, sb.toString());
                }

                final short protocolVersion = Protocol.HandshakeRequest.getProtocolVersion(msg);
                if (protocolVersion == Protocol.VERSION)
                {
                    short tableHeight = Protocol.HandshakeRequest.getDesiredTableHeight( msg );
                    final String clientDeviceId = Protocol.HandshakeRequest.getDeviceId( msg );
                    final String clientPlayerName = Protocol.HandshakeRequest.getPlayerName( msg );
                    Log.i( LOG_TAG, m_session.getRemoteAddress() +
                            ": handshake ok: playerName=[" + clientPlayerName + "]" );

                    if (m_desiredTableHeight < tableHeight)
                        tableHeight = m_desiredTableHeight;

                    /* Send reply first to be sure other side will receive
                     * HandshakeReplyOk before anything else.
                     */
                    final ByteBuffer handshakeReply = Protocol.HandshakeReplyOk.create(tableHeight, m_ballRadius, m_caps);
                    m_session.sendData( handshakeReply );

                    final GameServerSession gameServerSession = new GameServerSession(
                            m_session,
                            m_streamDefragger,
                            m_pingConfig,
                            m_view );

                    m_session.replaceListener( gameServerSession );
                    m_view.onClientConnected( gameServerSession, tableHeight, clientDeviceId, clientPlayerName );
                }
                else
                {
                    /* Protocol version is different, can not continue. */
                    Log.i( LOG_TAG, m_session.getRemoteAddress() + ": protocol version mismatch: " +
                            Protocol.VERSION + "-" + protocolVersion + ", close connection." );

                    final String statusText = "Protocol version mismatch: " + Protocol.VERSION + "-" + protocolVersion;
                    final ByteBuffer handshakeReply = Protocol.HandshakeReplyFail.create( statusText );
                    m_session.sendData( handshakeReply );
                    m_session.closeConnection();
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
        Log.i( LOG_TAG, m_session.getRemoteAddress() + ": connection closed" );

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
                Log.i( LOG_TAG, ex.toString() );
                interrupted = true;
            }
        }

        m_streamDefragger.close();
        m_view.onClientDisconnected();

        if (interrupted)
            Thread.currentThread().interrupt();
    }
}
