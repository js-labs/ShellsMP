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

import org.jsl.collider.RetainableByteBuffer;
import org.jsl.collider.Session;
import org.jsl.collider.StreamDefragger;

public class GameServerSession extends GameSession
{
    private final GameServerView m_view;

    public GameServerSession(
            Session session,
            StreamDefragger streamDefragger,
            PingConfig pingConfig,
            GameServerView view )
    {
        super( session, streamDefragger, pingConfig, view );
        m_view = view;
    }

    public int onMessageReceived( int messageID, RetainableByteBuffer msg )
    {
        switch (messageID)
        {
            case Protocol.GuessReply.ID:
                m_view.showGuessReplyCT( Protocol.GuessReply.getFound(msg) );
            break;
        }
        return 0;
    }

    public void onConnectionClosed()
    {
        m_view.onClientDisconnected();
        super.onConnectionClosed();
    }
}
