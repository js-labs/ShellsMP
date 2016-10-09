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
import org.jsl.collider.RetainableByteBufferPool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.security.InvalidParameterException;

public class Protocol
{
    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    private static final short MSG_HANDSHAKE_REQUEST    = 0x0001;
    private static final short MSG_HANDSHAKE_REPLY_OK   = 0x0002;
    private static final short MSG_HANDSHAKE_REPLY_FAIL = 0x0003;
    private static final short MSG_PING                 = 0x0004;
    private static final short MSG_PONG                 = 0x0005;
    private static final short MSG_DRAG_BALL            = 0x0006;
    private static final short MSG_PUT_BALL             = 0x0007;
    private static final short MSG_REMOVE_BALL          = 0x0008;
    private static final short MSG_DRAG_CAP             = 0x0009;
    private static final short MSG_PUT_CAP              = 0x000A;
    private static final short MSG_REMOVE_CAP           = 0x000B;
    private static final short MSG_GUESS                = 0x000C;
    private static final short MSG_GUESS_REPLY          = 0x000D;

    public static final byte VERSION = 1;

    public static class Message
    {
        /* message size (short, 2 bytes) + message type (short, 2 bytes) */
        public static final short HEADER_SIZE = (2 + 2);

        private static ByteBuffer init( ByteBuffer byteBuffer, short size, short type )
        {
            byteBuffer.putShort( size );
            byteBuffer.putShort( type );
            return byteBuffer;
        }

        private static RetainableByteBuffer init( RetainableByteBuffer byteBuffer, short size, short type )
        {
            byteBuffer.putShort( size );
            byteBuffer.putShort( type );
            return byteBuffer;
        }

        protected static ByteBuffer create( short type, short extSize )
        {
            if (extSize > (Short.MAX_VALUE - HEADER_SIZE))
                throw new InvalidParameterException();
            final short messageSize = (short) (HEADER_SIZE + extSize);
            final ByteBuffer byteBuffer = ByteBuffer.allocateDirect( messageSize );
            return init( byteBuffer, messageSize, type );
        }

        protected static RetainableByteBuffer create( RetainableByteBufferPool byteBufferPool, short type, short extSize )
        {
            if (extSize > (Short.MAX_VALUE - HEADER_SIZE))
                throw new InvalidParameterException();
            final short messageSize = (short) (HEADER_SIZE + extSize);
            final RetainableByteBuffer byteBuffer = byteBufferPool.alloc( messageSize );
            return init( byteBuffer, messageSize, type );
        }

        public static int getLength( ByteBuffer msg )
        {
            return msg.getShort( msg.position() );
        }

        public static short getMessageID( RetainableByteBuffer msg )
        {
            return msg.getShort( msg.position() + 2 );
        }
    }

    public static class HandshakeRequest extends Message
    {
        /* short : protocol version
         * short : desired table height
         * short : device id length
         * short : player name length
         *       : device id
         *       : player name
         */
        public static final short ID = MSG_HANDSHAKE_REQUEST;

        public static ByteBuffer create(short desiredTableHeight, String deviceId, String playerName) throws CharacterCodingException
        {
            final CharsetEncoder encoder = Charset.defaultCharset().newEncoder();
            final ByteBuffer deviceIdBB = encoder.encode(CharBuffer.wrap(deviceId));
            final ByteBuffer playerNameBB = encoder.encode(CharBuffer.wrap(playerName));
            final short extSize = (short) (2 + 2 + 2 + 2 + deviceIdBB.remaining() + playerNameBB.remaining());
            final ByteBuffer msg = create(ID, extSize);
            msg.putShort(VERSION);
            msg.putShort(desiredTableHeight);
            msg.putShort((short)deviceIdBB.remaining());
            msg.putShort((short)playerNameBB.remaining());
            msg.put(deviceIdBB);
            msg.put(playerNameBB);
            msg.rewind();
            return msg;
        }

        public static short getProtocolVersion( RetainableByteBuffer msg )
        {
            return msg.getShort( msg.position() + HEADER_SIZE );
        }

        public static short getDesiredTableHeight( RetainableByteBuffer msg )
        {
            return (msg.getShort(msg.position() + Message.HEADER_SIZE + 2));
        }

        public static String getDeviceId( RetainableByteBuffer msg ) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            try
            {
                msg.position(pos + Message.HEADER_SIZE + 2 + 2);
                final short deviceIdLength = msg.getShort();
                if (deviceIdLength > 0)
                {
                    msg.getShort(); /* Skip player name length */
                    final CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
                    msg.limit(msg.position() + deviceIdLength);
                    ret = decoder.decode(msg.getNioByteBuffer()).toString();
                }
            }
            finally
            {
                msg.position( pos );
            }
            return ret;
        }

        public static String getPlayerName( RetainableByteBuffer msg ) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            try
            {
                msg.position(pos + Message.HEADER_SIZE + 2 + 2);
                final short deviceIdLength = msg.getShort();
                final short playerNameLength = msg.getShort();
                if (playerNameLength > 0)
                {
                    final CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
                    msg.position(msg.position() + deviceIdLength);
                    msg.limit(msg.position() + playerNameLength);
                    ret = decoder.decode(msg.getNioByteBuffer()).toString();
                }
            }
            finally
            {
                msg.position( pos );
            }
            return ret;
        }
    }

    public static class HandshakeReplyOk extends Message
    {
        /* short : table height
         * short : ball radius
         * short : caps
         */
        public static final short ID = MSG_HANDSHAKE_REPLY_OK;

        public static ByteBuffer create(short tableHeight, short ballRadius, short caps)
        {
            final ByteBuffer msg = create(ID, (short) (2 + 2 + 2 + 2));
            msg.putShort(tableHeight);
            msg.putShort(ballRadius);
            msg.putShort(caps);
            msg.rewind();
            return msg;
        }

        public static short getTableHeight(RetainableByteBuffer msg)
        {
            return msg.getShort(msg.position() + Message.HEADER_SIZE);
        }

        public static short getBallRadius(RetainableByteBuffer msg)
        {
            return msg.getShort(msg.position() + Message.HEADER_SIZE + 2);
        }

        public static short getCaps(RetainableByteBuffer msg)
        {
            return msg.getShort(msg.position() + Message.HEADER_SIZE + 2);
        }
    }

    public static class HandshakeReplyFail extends Message
    {
        /*
         * short : status text length
         * str   : status text
         */
        public static final short ID = MSG_HANDSHAKE_REPLY_FAIL;

        public static ByteBuffer create( String statusText ) throws CharacterCodingException
        {
            final CharsetEncoder encoder = Charset.defaultCharset().newEncoder();
            final ByteBuffer bb = encoder.encode( CharBuffer.wrap(statusText) );
            final ByteBuffer msg = create( ID, (short) (2 + 2 + bb.remaining()) );
            msg.putShort( (short) bb.remaining() );
            msg.put( bb );
            msg.rewind();
            return msg;
        }

        public static String getStatusText( RetainableByteBuffer msg ) throws CharacterCodingException
        {
            String ret = null;
            final int pos = msg.position();
            try
            {
                msg.position( pos + Message.HEADER_SIZE );
                final short length = msg.getShort();
                if (length > 0)
                {
                    final CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
                    ret = decoder.decode(msg.getNioByteBuffer()).toString();
                }
            }
            finally
            {
                msg.position( pos );
            }
            return ret;
        }
    }

    public static class Ping extends Message
    {
        /*
         * int : ping id
         */
        public static final short ID = MSG_PING;

        public static RetainableByteBuffer create( RetainableByteBufferPool byteBufferPool, int id )
        {
            final short extSize = (Integer.SIZE / Byte.SIZE);
            final RetainableByteBuffer msg = Message.create( byteBufferPool, ID, extSize );
            msg.putInt( id );
            return msg.rewind();
        }

        public static int getPingID( RetainableByteBuffer msg )
        {
            return msg.getInt( msg.position() + HEADER_SIZE );
        }
    }

    public static class Pong extends Message
    {
        /*
         * int : ping/pong id
         */
        public static final short ID = MSG_PONG;

        public static RetainableByteBuffer create( RetainableByteBufferPool byteBufferPool, int id )
        {
            final short extSize = (Integer.SIZE / Byte.SIZE);
            final RetainableByteBuffer msg = Message.create( byteBufferPool, ID, extSize );
            msg.putInt( id );
            return msg.rewind();
        }

        public static int getPingID( RetainableByteBuffer msg )
        {
            return msg.getInt( msg.position() + HEADER_SIZE );
        }
    }

    public static class DragBall extends Message
    {
        /* float : ball x
         * float : ball y
         */
        public static final short ID = MSG_DRAG_BALL;

        public static RetainableByteBuffer create( RetainableByteBufferPool byteBufferPool, float x, float y )
        {
            final short extSize = (Float.SIZE / Byte.SIZE) * 2;
            final RetainableByteBuffer msg = Message.create( byteBufferPool, ID, extSize );
            msg.putFloat( x );
            msg.putFloat( y );
            return msg.rewind();
        }

        public static float getX( RetainableByteBuffer msg )
        {
            return msg.getFloat( msg.position() + HEADER_SIZE );
        }

        public static float getY( RetainableByteBuffer msg )
        {
            return msg.getFloat( msg.position() + HEADER_SIZE + (Float.SIZE / Byte.SIZE) );
        }
    }

    public static class PutBall extends Message
    {
        /* float : ball x
         * float : ball y
         */
        public static final short ID = MSG_PUT_BALL;

        public static RetainableByteBuffer create( RetainableByteBufferPool byteBufferPool, float x, float y )
        {
            final short extSize = (Float.SIZE / Byte.SIZE) * 2;
            final RetainableByteBuffer msg = Message.create( byteBufferPool, ID, extSize );
            msg.putFloat( x );
            msg.putFloat( y );
            return msg.rewind();
        }

        public static float getX( RetainableByteBuffer msg )
        {
            return msg.getFloat( msg.position() + HEADER_SIZE );
        }

        public static float getY( RetainableByteBuffer msg )
        {
            return msg.getFloat( msg.position() + HEADER_SIZE + (Float.SIZE/Byte.SIZE) );
        }
    }

    public static class RemoveBall extends Message
    {
        public static final short ID = MSG_REMOVE_BALL;

        public static RetainableByteBuffer create( RetainableByteBufferPool byteBufferPool )
        {
            final RetainableByteBuffer msg = Message.create( byteBufferPool, ID, /*extSize*/(short)0 );
            return msg.rewind();
        }
    }

    public static class DragCap extends Message
    {
        /* short : cap id
         * float : cap x
         * float : cap y
         * float : cap z
         */
        public static final short ID = MSG_DRAG_CAP;

        public static RetainableByteBuffer create( RetainableByteBufferPool byteBufferPool, short id, float x, float y, float z )
        {
            final short extSize = (Short.SIZE / Byte.SIZE) + (Float.SIZE / Byte.SIZE) * 3;
            final RetainableByteBuffer msg = Message.create( byteBufferPool, ID, extSize );
            msg.putShort( id );
            msg.putFloat( x );
            msg.putFloat( y );
            msg.putFloat( z );
            return msg.rewind();
        }

        public static short getID( RetainableByteBuffer msg )
        {
            return msg.getShort( msg.position() + HEADER_SIZE );
        }

        public static float getX( RetainableByteBuffer msg )
        {
            return msg.getFloat( msg.position() + HEADER_SIZE + (Short.SIZE/Byte.SIZE) );
        }

        public static float getY( RetainableByteBuffer msg )
        {
            return msg.getFloat( msg.position() + HEADER_SIZE + (Short.SIZE/Byte.SIZE) + (Float.SIZE/Byte.SIZE) );
        }

        public static float getZ( RetainableByteBuffer msg )
        {
            return msg.getFloat( msg.position() + HEADER_SIZE + (Short.SIZE/Byte.SIZE) + (Float.SIZE/Byte.SIZE)*2 );
        }
    }

    public static class PutCap extends Message
    {
        /* short : cap id
         * float : cap x
         * float : cap y
         * int   : gamble time
         */
        public static final short ID = MSG_PUT_CAP;

        public static RetainableByteBuffer create( RetainableByteBufferPool byteBufferPool, short id, float x, float y, int gambleTime )
        {
            final short extSize = (Short.SIZE / Byte.SIZE) + (Float.SIZE / Byte.SIZE) * 2 + (Integer.SIZE / Byte.SIZE);
            final RetainableByteBuffer msg = Message.create( byteBufferPool, ID, extSize );
            msg.putShort( id );
            msg.putFloat( x );
            msg.putFloat( y );
            msg.putInt( gambleTime );
            return msg.rewind();
        }

        public static short getID( RetainableByteBuffer msg )
        {
            return msg.getShort( msg.position() + HEADER_SIZE );
        }

        public static float getX( RetainableByteBuffer msg )
        {
            return msg.getFloat( msg.position() + HEADER_SIZE + (Short.SIZE/Byte.SIZE) );
        }

        public static float getY( RetainableByteBuffer msg )
        {
            return msg.getFloat( msg.position() + HEADER_SIZE + (Short.SIZE/Byte.SIZE) + (Float.SIZE/Byte.SIZE) );
        }

        public static int getGambleTime( RetainableByteBuffer msg )
        {
            return msg.getInt( msg.position() + HEADER_SIZE + (Short.SIZE/Byte.SIZE) + (Float.SIZE/Byte.SIZE)*2 );
        }
    }

    public static class RemoveCap extends Message
    {
        /* short : cap id */
        public static final short ID = MSG_REMOVE_CAP;

        public static RetainableByteBuffer create( RetainableByteBufferPool byteBufferPool, short id )
        {
            final short extSize = (Short.SIZE / Byte.SIZE);
            final RetainableByteBuffer msg = Message.create( byteBufferPool, ID, extSize );
            msg.putShort( id );
            return msg.rewind();
        }

        public static short getID( RetainableByteBuffer msg )
        {
            return msg.getShort( msg.position() + HEADER_SIZE );
        }
    }

    public static class Guess extends Message
    {
        /* short : cap with ball */
        public static final short ID = MSG_GUESS;

        public static RetainableByteBuffer create( RetainableByteBufferPool byteBufferPool, short id )
        {
            final short extSize = (Short.SIZE / Byte.SIZE);
            final RetainableByteBuffer msg = Message.create( byteBufferPool, ID, extSize );
            msg.putShort( id );
            return msg.rewind();
        }

        public static short getCapWithBall( RetainableByteBuffer msg )
        {
            return msg.getShort( msg.position() + HEADER_SIZE );
        }
    }

    public static class GuessReply extends Message
    {
        /* byte : yes/no */
        public static final short ID = MSG_GUESS_REPLY;

        public static ByteBuffer create( boolean found )
        {
            final ByteBuffer msg = Message.create( ID, /*extSize*/(short)1 );
            msg.put( (byte) (found?1:0) );
            msg.rewind();
            return msg;
        }

        public static boolean getFound( RetainableByteBuffer msg )
        {
            return (msg.get(msg.position() + HEADER_SIZE) != 0);
        }
    }
}
