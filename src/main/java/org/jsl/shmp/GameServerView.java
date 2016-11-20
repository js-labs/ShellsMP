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

import android.content.Context;
import android.content.Intent;
import android.graphics.RectF;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.opengl.Matrix;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import android.view.MotionEvent;
import org.jsl.collider.*;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.util.HashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.io.IOException;

public class GameServerView extends GameView
{
    private static final String LOG_TAG = GameServerView.class.getSimpleName();

    private static final int STATE_WAIT_CLIENT = 0;
    private static final int STATE_BALL_SET  = 1;
    private static final int STATE_BALL_DRAG = 2;
    private static final int STATE_CAP_SET   = 3;
    private static final int STATE_CAP_DRAG  = 4;
    private static final int STATE_GAMBLE    = 5;
    private static final int STATE_GAMBLE_TIMER_TOUCH = 6;
    private static final int STATE_WAIT_REPLY = 7;
    private static final int STATE_FINISHED  = 8;

    private static final int CAP_TOUCH = 1;
    private static final int CAP_DRAG  = 2;

    private static class SceneObject
    {
        /* Screen coordinates */
        private float m_x;
        private float m_y;

        public boolean contains( float x, float y, float radius )
        {
            final float dx = (x - m_x);
            final float dy = (y - m_y);
            return (Math.sqrt(dx*dx + dy*dy) < radius);
        }

        public float getX()
        {
            return m_x;
        }

        public float getY()
        {
            return m_y;
        }

        public void moveTo( float x, float y )
        {
            m_x = x;
            m_y = y;
        }

        public float moveByX( float dx )
        {
            m_x += dx;
            return m_x;
        }

        public float moveByY( float dy )
        {
            m_y += dy;
            return m_y;
        }
    }

    private static class Ball extends SceneObject
    {
        private final ModelBall m_model;
        private final float [] m_matrix;
        private boolean m_visible;
        private float m_x;
        private float m_y;

        public Ball( ModelBall model )
        {
            m_model = model;
            m_matrix = new float[16];
            Matrix.setIdentityM( m_matrix, 0 );
            m_visible = false;
        }

        public void updateMatrix( float x, float y, float radius, float [] tmp )
        {
            Matrix.setIdentityM( tmp, 0 );
            Matrix.translateM( tmp, 0, x, y, 0 );
            Matrix.setIdentityM( tmp, 16 );
            Matrix.scaleM( tmp, 16, radius, radius, radius );
            Matrix.multiplyMM( m_matrix, 0, tmp, 0, tmp, 16 );
            m_visible = true;
            m_x = x;
            m_y = y;
        }

        public void draw( float [] vpMatrix, float [] light, float [] tmp )
        {
            if (m_visible)
            {
                Matrix.multiplyMM( tmp, 0, vpMatrix, 0, m_matrix, 0 );

                /* tmp[16-19] : light direction
                 * ball shader draws ball at (0,0,0) and assuming the eye position is on the Z axis,
                 * also shader expects the direction to the light, but not it's position.
                 */
                tmp[16] = (light[0] - m_x);
                tmp[17] = (light[1] - m_y);
                tmp[18] = light[2];

                m_model.draw( tmp, 0, tmp, 16, light, 3 );
            }
        }

        public void setVisible( boolean visible )
        {
            m_visible = visible;
        }

        public boolean isVisible()
        {
            return m_visible;
        }
    }

    private static class Cap extends SceneObject
    {
        private final int m_id;
        private final Model m_model;
        private final float [] m_matrix;
        private boolean m_draw;

        private float m_eventX;
        private float m_eventY;
        private int m_state;

        public Cap( int id, Model model )
        {
            m_id = id;
            m_model = model;
            m_matrix = new float[16];
            Matrix.setIdentityM( m_matrix, 0 );
            m_draw = false;
        }

        public int getID()
        {
            return m_id;
        }

        public void updateMatrix( float x, float y, float radius, float [] tmp )
        {
            Matrix.setIdentityM( tmp, 0 );
            Matrix.translateM( tmp, 0, x, y, 0 );
            Matrix.setIdentityM( tmp, 16 );
            Matrix.scaleM( tmp, 16, radius, radius, radius );
            Matrix.multiplyMM( m_matrix, 0, tmp, 0, tmp, 16 );
            m_draw = true;
        }

        public void draw( float [] vpMatrix, float [] lightPosition, float [] tmp )
        {
            if (m_draw)
            {
                Matrix.multiplyMM( tmp, 0, vpMatrix, 0, m_matrix, 0 );
                m_model.draw( tmp, 0, lightPosition, 0 );
            }
        }

        void setEventPosition( float x, float y )
        {
            m_eventX = x;
            m_eventY = y;
        }

        float getEventX()
        {
            return m_eventX;
        }

        float getEventY()
        {
            return m_eventY;
        }

        void setState( int state )
        {
            m_state = state;
        }

        int getState()
        {
            return m_state;
        }
    }

    private class GambleTimerImpl extends GambleTimer
    {
        protected void onStop()
        {
            if (m_timerManager.resetTimer(this))
            {
                executeOnRenderThread( new RenderThreadRunnable() {
                    public boolean runOnRenderThread(int frameId)
                    {
                        /* Change status line,
                         * then change state then send message to the client,
                         * to be sure m_state will be changed before we will receive reply.
                         */
                        setBottomLineText( R.string.waiting, GAMBLE_TIMER_COLOR, 0.5f );

                        post( new Runnable() {
                            public void run() {
                                m_state = STATE_WAIT_REPLY;
                                final RetainableByteBuffer msg = Protocol.Guess.create( m_byteBufferPool, (short) m_capWithBall );
                                m_session.sendMessage( msg );
                                msg.release();
                            }
                        } );

                        return false;
                    }
                } );
            }
        }

        protected void onUpdate( final long value, final float fontSize )
        {
            executeOnRenderThread( new RenderThreadRunnable() {
                public boolean runOnRenderThread(int frameId) {
                    setBottomLineText( Long.toString(value), GAMBLE_TIMER_COLOR, fontSize );
                    return false;
                }
            } );
        }

        public GambleTimerImpl( int timeout )
        {
            super( m_activity, timeout );
        }
    }

    private void setBottomLineText( int resId, int color, float fontSize )
    {
        setBottomLineText( getContext().getString(resId), color, fontSize );
    }

    private void setBottomLineText( String str, int color, float fontSize )
    {
        m_bottomLineText = str;
        m_bottomLineTextFontSize = (getBottomReservedHeight() * fontSize);
        m_bottomLineTextColor = color;
    }

    private float getVirtualX( float x )
    {
        return (x - m_tableRect.left - m_tableRect.width()/2) * m_scale;
    }

    private float getVirtualY( float y )
    {
        return (m_tableRect.height()/2 - (y - m_tableRect.top)) * m_scale;
    }

    private final GameServerActivity m_activity;
    private final NsdManager m_nsdManager;
    private final short m_gameTime;
    private final Cap [] m_cap;
    private final String m_strPort;
    private final int m_ballRadius;
    private final TimerManager m_timerManager;

    private final float [] m_tmpMatrix;
    private final HashMap<Integer, Cap> m_capByPointer;

    private float [] m_light;
    private Table m_table;
    private RectF m_tableRect;
    private RectF m_tableRectEx;
    private Ball m_ball;
    private int m_capIdx;
    private int m_capWithBall;

    private int m_state;
    private float m_eventX;
    private float m_eventY;

    private float m_bottomLineX;
    private float m_bottomLineY;
    private String m_bottomLineText;
    private float m_bottomLineTextFontSize;
    private int m_bottomLineTextColor;

    private RetainableByteBufferPool m_byteBufferPool;

    private final ReentrantLock m_lock;
    private final Condition m_cond;
    private RegistrationListener m_registrationListener;
    private boolean m_registrationListenerStop;
    private boolean m_pause;
    private GameServerSession m_session;

    private float m_scale;

    private String m_clientDeviceId;
    private String m_clientPlayerName;
    private boolean m_clientDisconnected;
    private int m_win;

    private class GameAcceptor extends Acceptor
    {
        private final short m_desiredTableHeight;
        private final short m_ballRadius;

        public GameAcceptor( short desiredTableHeight, short ballRadius )
        {
            m_desiredTableHeight = desiredTableHeight;
            m_ballRadius = ballRadius;
        }

        public void onAcceptorStarted( Collider collider, int localPortNumber )
        {
            Log.d( LOG_TAG, "Acceptor started, port=" + localPortNumber );
            m_lock.lock();
            try
            {
                final String deviceId = getDeviceId();
                final String playerName = getPlayerName();
                final NsdServiceInfo serviceInfo = MainActivity.createServiceInfo(deviceId, playerName, localPortNumber);
                m_registrationListener = new RegistrationListener(localPortNumber);
                m_registrationListenerStop = false;
                m_nsdManager.registerService( serviceInfo, NsdManager.PROTOCOL_DNS_SD, m_registrationListener );
                final Bitmap statusLine = createStatusLine( localPortNumber, Color.WHITE );
                executeOnRenderThread( new RenderThreadRunnable() {
                    public boolean runOnRenderThread(int frameId) {
                        setStatusLine( statusLine );
                        return false;
                    }
                } );
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public Session.Listener createSessionListener( Session session )
        {
            boolean interrupted = false;
            try
            {
                session.getCollider().removeAcceptor( this );
            }
            catch (final InterruptedException ex)
            {
                interrupted = true;
                Log.e( LOG_TAG, ex.toString() );
            }
            finally
            {
                if (interrupted)
                    Thread.currentThread().interrupt();
            }

            m_lock.lock();
            try
            {
                m_nsdManager.unregisterService( m_registrationListener );
                m_registrationListenerStop = true;
            }
            finally
            {
                m_lock.unlock();
            }

            /* Session will register in the activity after handshake */
            return new HandshakeServerSession(
                    GameServerView.this,
                    session,
                    getPingConfig(),
                    m_desiredTableHeight,
                    m_ballRadius,
                    (short)m_cap.length );
        }
    }

    private class RegistrationListener implements NsdManager.RegistrationListener
    {
        /* Port number in NsdServiceInfo in onServiceRegistered() is 0,
         * so we have to keep a port number to show it later.
          */
        private final int m_portNumber;

        public RegistrationListener( int portNumber )
        {
            m_portNumber = portNumber;
        }

        public void onRegistrationFailed( NsdServiceInfo serviceInfo, int errorCode )
        {
            Log.d( LOG_TAG, "onRegistrationFailed: " + errorCode );
            m_lock.lock();
            try
            {
                m_registrationListener = null;
            }
            finally
            {
                m_lock.unlock();
            }
            m_activity.onGameRegistrationFailed();
        }

        public void onUnregistrationFailed( NsdServiceInfo serviceInfo, int errorCode )
        {
            Log.d( LOG_TAG, "onUnregistrationFailed: " + errorCode );
            if (BuildConfig.DEBUG)
                throw new AssertionError();
            /* Nothing critical probably... */
        }

        public void onServiceRegistered( NsdServiceInfo serviceInfo )
        {
            m_lock.lock();
            try
            {
                if (BuildConfig.DEBUG && (m_registrationListener != this))
                    throw new AssertionError();

                final String serviceName = serviceInfo.getServiceName();
                Log.d( LOG_TAG, "onServiceRegistered: " + serviceName );

                final Bitmap statusLine = createStatusLine( m_portNumber, Color.GREEN );
                executeOnRenderThread( new RenderThreadRunnable() {
                    public boolean runOnRenderThread(int frameId) {
                        setStatusLine( statusLine );
                        return false;
                    }
                } );
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onServiceUnregistered( NsdServiceInfo serviceInfo )
        {
            Log.d( LOG_TAG, "onServiceUnregistered: " + serviceInfo.getServiceName() );
            m_lock.lock();
            try
            {
                m_registrationListener = null;
                m_cond.signal();
            }
            finally
            {
                m_lock.unlock();
            }
        }
    }

    protected Bitmap createStatusLine()
    {
        /* Initial status line */
        final int width = getWidth();
        final Bitmap bitmap = Bitmap.createBitmap( width, getTopReservedHeight(), Bitmap.Config.RGB_565 );
        final Canvas canvas = new Canvas( bitmap );
        final Paint paint = getPaint();
        final float textY = -paint.ascent();

        paint.setColor( Color.WHITE );
        paint.setTextAlign( Paint.Align.LEFT );
        canvas.drawText( m_strPort, 0, textY, paint );

        return bitmap;
    }

    private Bitmap createStatusLine( int portNumber, int color )
    {
        final int width = getWidth();
        final Bitmap bitmap = Bitmap.createBitmap( width, getTopReservedHeight(), Bitmap.Config.RGB_565 );
        final Canvas canvas = new Canvas( bitmap );
        final Paint paint = getPaint();
        final float textY = -paint.ascent();

        paint.setColor( color );
        final String str = m_strPort + Integer.toString(portNumber);
        paint.setTextAlign( Paint.Align.LEFT );
        canvas.drawText( str, 0, textY, paint );

        return bitmap;
    }

    public GameServerView( GameServerActivity activity, String deviceId, String playerName, short gameTime, short caps, NsdManager nsdManager )
    {
        super( activity, deviceId, playerName );

        m_activity = activity;
        m_nsdManager = nsdManager;
        m_gameTime = gameTime;
        m_cap = new Cap[caps];
        m_strPort = getResources().getString( R.string.port );
        m_ballRadius = (getBottomReservedHeight() / 3);
        m_timerManager = new TimerManager();

        m_tmpMatrix = new float[64];
        m_capByPointer = new HashMap<Integer, Cap>();

        m_byteBufferPool = new RetainableByteBufferPool( 1024, true, Protocol.BYTE_ORDER );

        m_lock = new ReentrantLock();
        m_cond = m_lock.newCondition();
        m_win = -1;
    }

    public void onSurfaceCreated( GL10 gl, EGLConfig config )
    {
        super.onSurfaceCreated( gl, config );
    }

    public void onSurfaceChanged( GL10 gl, int width, int height )
    {
        /* Looks like a better place to start all than surfaceCreated() */
        super.onSurfaceChanged( gl, width, height );

        m_scale = (VIRTUAL_TABLE_WIDTH / width);
        m_state = STATE_WAIT_CLIENT;

        try
        {
            /* [0-2] : position,
             * [3-5] : color
             */
            m_light = new float[6];
            m_light[0] = -(width / 4f);      /*x*/
            m_light[1] = (height / 4f * 3f); /*y*/
            m_light[2] = 200f;               /*z*/
            m_light[3] = 1.0f; /*r*/
            m_light[4] = 1.0f; /*g*/
            m_light[5] = 1.0f; /*b*/

            final Context context = getContext();
            m_table = new Table( context );

            final ModelBall modelBall = new ModelBall( context, BALL_COLOR );
            m_ball = new Ball( modelBall );

            final ModelCap modelCap = new ModelCap( context, CAP_STRIPES );
            for (int idx=0; idx<m_cap.length; idx++)
                m_cap[idx] = new Cap( idx, modelCap );

            final Collider collider = startCollider();
            final GameAcceptor acceptor = new GameAcceptor(
                    getDesiredTableHeight(width, height),
                    (short) (m_ballRadius * VIRTUAL_TABLE_WIDTH / width) );
            collider.addAcceptor( acceptor );

            /* Bottom line will be in the middle of the screen,
             * position will be adjusted after we will find the table height.
             */
            m_bottomLineX = (width / 2f);
            m_bottomLineY = (height / 2f);

            m_bottomLineText = getContext().getString( R.string.waiting_second_player );
            m_bottomLineTextColor = Color.GREEN;
            m_bottomLineTextFontSize = (getBottomReservedHeight() * 0.3f);
        }
        catch (final IOException ex)
        {
            Log.e( LOG_TAG, ex.toString() );
        }
    }

    public void onDrawFrame( float [] vpMatrix, Canvas3D canvas3D )
    {
        super.onDrawFrame( vpMatrix, canvas3D );

        if (m_tableRect != null)
        {
            final float viewHeight = getHeight();
            final float tableWidth = m_tableRect.width();
            final float tableHeight = m_tableRect.height();
            Matrix.translateM(
                    m_tmpMatrix, 16,
                    Canvas3D.s_identityMatrix, 0,
                    tableWidth/2, viewHeight - getTopReservedHeight() - 1 - tableHeight/2, 0f );
            Matrix.multiplyMM( m_tmpMatrix, 0, vpMatrix, 0, m_tmpMatrix, 16 );
            m_table.draw( m_tmpMatrix );

            m_ball.draw( vpMatrix, m_light, m_tmpMatrix );

            for (Cap cap : m_cap)
                cap.draw( vpMatrix, m_light, m_tmpMatrix );
        }

        if (m_bottomLineText != null)
        {
            canvas3D.drawText(
                    vpMatrix,
                    m_bottomLineText,
                    /*x*/ m_bottomLineX,
                    /*y*/ (getHeight() - m_bottomLineY),
                    /*z*/ 1f,
                    m_bottomLineTextFontSize,
                    m_bottomLineTextColor,
                    Paint.Align.CENTER );
        }

        /*
        canvas3D.drawText( vpMatrix, "12wait",
                getWidth()/2, getHeight()/2, 1f, 70, Color.GREEN, Paint.Align.CENTER );

        canvas3D.drawText( vpMatrix, "12wait",
                getWidth()/2, getHeight()/2-100, 1f, 50, Color.GREEN, Paint.Align.CENTER );
        */

        /*
        Matrix.setIdentityM( m_tmpMatrix, 16 );
        //Matrix.scaleM( m_tmpMatrix, 16, 2f, 2f, 2f );
        //Matrix.setIdentityM( m_tmpMatrix, 32 );
        //Matrix.rotateM( m_tmpMatrix, 32, 45, 1, 0, 0 );
        //Matrix.multiplyMM( m_tmpMatrix, 48, m_tmpMatrix, 16, m_tmpMatrix, 32 );
        Matrix.setIdentityM( m_tmpMatrix, 32 );
        Matrix.translateM( m_tmpMatrix, 32, getWidth()/2, getHeight()/2, 0f );
        //Matrix.multiplyMM( m_tmpMatrix, 48, m_tmpMatrix, 32, m_tmpMatrix, 16 );
        Matrix.multiplyMM( m_tmpMatrix, 0, vpMatrix, 0, m_tmpMatrix, 32 );
        m_cap.draw( m_tmpMatrix );
        */
    }

    public void onClientConnected( GameServerSession session, short virtualTableHeight, String clientDeviceId, String clientPlayerName )
    {
        m_session = session;
        m_clientDeviceId = clientDeviceId;
        m_clientPlayerName = clientPlayerName;

        final int topReservedHeight = getTopReservedHeight();
        final int viewWidth = getWidth();
        final int tableWidth = viewWidth;
        final int tableHeight = (int) (viewWidth * virtualTableHeight / VIRTUAL_TABLE_WIDTH);

        /* Android view coordinates */
        m_tableRect = new RectF(
                /*left  */ 0,
                /*top   */ topReservedHeight + 1,
                /*right */ tableWidth - 1,
                /*bottom*/ topReservedHeight + 1 + tableHeight );

        m_tableRectEx = new RectF(
                /*left  */ (m_tableRect.left + m_ballRadius),
                /*top   */ (m_tableRect.top + m_ballRadius),
                /*right */ (m_tableRect.right - m_ballRadius),
                /*bottom*/ (m_tableRect.bottom - m_ballRadius) );

        m_table.setSize( tableWidth, tableHeight );

        m_bottomLineText = null;
        m_bottomLineY = (topReservedHeight + tableHeight + getBottomReservedHeight() / 2);

        m_state = STATE_BALL_SET;
        
        final Bitmap statusLine = createStatusLine( /*ping*/-1, clientPlayerName );

        final float ballX = m_bottomLineX;
        final float ballY = m_bottomLineY;
        m_ball.moveTo( ballX, ballY );

        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                setStatusLine( statusLine );
                m_ball.updateMatrix( ballX, (getHeight() - ballY), m_ballRadius, m_tmpMatrix );
                return false;
            }
        } );
    }

    public void onClientDisconnected()
    {
        /* m_state will be changed to STATE_FINISHED in the collider thread,
         * so update will be serial with the onClientDisconnected() call.
         */
        if (!m_pause && (m_state != STATE_FINISHED))
        {
            m_clientDisconnected = true;
            m_activity.showMessageAndFinish(
                    R.string.info, R.string.other_player_quit_before_game_end, m_clientDeviceId);
        }
    }

    public void setPing( int ping )
    {
        final Bitmap statusLine = createStatusLine( ping, m_clientPlayerName );
        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                setStatusLine( statusLine );
                return false;
            }
        } );
    }

    public void showGuessReplyCT( boolean found )
    {
        m_state = STATE_FINISHED;
        m_win = (found ? 1 : 0);
        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                if (m_win > 0)
                    setBottomLineText( R.string.you_win, WIN_TEXT_COLOR, GAMBLE_TIMER_FONT_SIZE );
                else
                    setBottomLineText( R.string.you_lose, LOSE_TEXT_COLOR, GAMBLE_TIMER_FONT_SIZE );
                return false;
            }
        } );
    }

    public boolean onTouchEvent( MotionEvent event )
    {
        final int action = event.getActionMasked();
        //final long tsc = System.nanoTime();

        if (action == MotionEvent.ACTION_DOWN)
        {
            if (m_state == STATE_BALL_SET)
            {
                final float eventX = event.getX();
                final float eventY = event.getY();
                if (m_ball.contains(eventX, eventY, m_ballRadius))
                {
                    m_eventX = eventX;
                    m_eventY = eventY;
                    m_state = STATE_BALL_DRAG;
                    Log.d( LOG_TAG, "STATE_BALL_SET -> STATE_BALL_DRAG" );
                }
            }
            else if (m_state == STATE_CAP_SET)
            {
                final float x = event.getX();
                final float y = event.getY();
                if (m_cap[m_capIdx].contains(x, y, m_ballRadius))
                {
                    m_eventX = x;
                    m_eventY = y;
                    m_state = STATE_CAP_DRAG;
                    Log.d( LOG_TAG, "STATE_CAP_SET -> STATE_CAP_DRAG" );
                }
            }
            else if (m_state == STATE_GAMBLE)
            {
                if (!m_capByPointer.isEmpty())
                    throw new AssertionError();

                final int pointerIndex = event.getActionIndex();
                final int pointerId = event.getPointerId( pointerIndex );
                final float eventX = event.getX( pointerIndex );
                final float eventY = event.getY( pointerIndex );
                int idx = 0;
                for (; idx<m_cap.length; idx++)
                {
                    final Cap cap = m_cap[idx];
                    if (cap.contains(eventX, eventY, m_ballRadius))
                    {
                        cap.setEventPosition( eventX, eventY );
                        cap.setState( CAP_TOUCH );
                        m_capByPointer.put( pointerId, cap );
                        break;
                    }
                }

                if (idx < m_cap.length)
                {
                    Log.d( LOG_TAG, "ACTION_DOWN: pointerIndex=" + pointerIndex +
                            " pointerId=" + pointerId + ", capIdx=" + idx );
                }
                else if ((Math.abs(eventX - m_bottomLineX) < getBottomReservedHeight()/2f) &&
                         (Math.abs(eventY - m_bottomLineY) < getBottomReservedHeight()/2f))
                {
                    m_state = STATE_GAMBLE_TIMER_TOUCH;
                    Log.d( LOG_TAG, "ACTION_DOWN: STATE_GAMBLE -> STATE_GAMBLE_TIMER_TOUCH" );
                }
                else
                {
                    Log.d( LOG_TAG, "ACTION_DOWN: pointerIndex=" + pointerIndex +
                            " pointerId=" + pointerId + ", no cap" );
                }
            }
        }
        else if (action == MotionEvent.ACTION_POINTER_DOWN)
        {
            if (m_state == STATE_GAMBLE)
            {
                final int pointerIndex = event.getActionIndex();
                final int pointerId = event.getPointerId( pointerIndex );
                Log.d( LOG_TAG, "STATE_GAMBLE: POINTER_DOWN: pointerIndex=" + pointerIndex + " pointerId=" + pointerId );
                final float x = event.getX( pointerIndex );
                final float y = event.getY( pointerIndex );
                for (Cap cap : m_cap)
                {
                    if (cap.contains(x, y, m_ballRadius))
                    {
                        cap.setEventPosition( x, y );
                        cap.setState( CAP_TOUCH );
                        m_capByPointer.put( pointerId, cap );
                        break;
                    }
                }
            }
        }
        else if (action == MotionEvent.ACTION_POINTER_UP)
        {
            if (m_state == STATE_GAMBLE)
            {
                final int pointerIndex = event.getActionIndex();
                final int pointerId = event.getPointerId( pointerIndex );
                Log.d( LOG_TAG, "STATE_GAMBLE: POINTER_UP: pointerIndex=" + pointerIndex + " pointerId=" + pointerId );
                m_capByPointer.remove( pointerId );
            }
        }
        else if (action == MotionEvent.ACTION_MOVE)
        {
            if (m_state == STATE_BALL_DRAG)
            {
                final float x = event.getX();
                final float y = event.getY();
                final float dx = (x - m_eventX);
                final float dy = (y - m_eventY);
                final float bx = m_ball.moveByX( dx );
                final float by = m_ball.moveByY( dy );

                executeOnRenderThread( new RenderThreadRunnable() {
                    public boolean runOnRenderThread(int frameId) {
                        m_ball.updateMatrix( bx, (getHeight() - by), m_ballRadius, m_tmpMatrix );
                        return false;
                    }
                } );

                final RetainableByteBuffer msg = Protocol.DragBall.create( m_byteBufferPool, getVirtualX(bx), getVirtualY(by) );
                m_session.sendMessage( msg );
                msg.release();

                m_eventX = x;
                m_eventY = y;
            }
            else if (m_state == STATE_CAP_DRAG)
            {
                final int capIdx = m_capIdx;
                final float x = event.getX();
                final float y = event.getY();
                final float dx = (x - m_eventX);
                final float dy = (y - m_eventY);
                final float cx = m_cap[capIdx].moveByX( dx );
                final float cy = m_cap[capIdx].moveByY( dy );

                executeOnRenderThread( new RenderThreadRunnable() {
                    public boolean runOnRenderThread(int frameId) {
                        m_cap[capIdx].updateMatrix( cx, (getHeight() - cy), m_ballRadius, m_tmpMatrix );
                        return false;
                    }
                } );

                final float vcx = getVirtualX( cx );
                final float vcy = getVirtualY( cy );
                final float vcz = (m_ballRadius * 2f * m_scale);
                final RetainableByteBuffer msg = Protocol.DragCap.create( m_byteBufferPool, (short)capIdx, vcx, vcy, vcz );
                m_session.sendMessage( msg );
                msg.release();

                m_eventX = x;
                m_eventY = y;
            }
            else if (m_state == STATE_GAMBLE)
            {
                final int pointerCount = event.getPointerCount();
                for (int pointerIndex=0; pointerIndex<pointerCount; pointerIndex++)
                {
                    final int pointerId = event.getPointerId( pointerIndex );
                    final Cap cap = m_capByPointer.get( pointerId );
                    if (cap != null)
                    {
                        /* Properly detect cap collision would be quite difficult now,
                         * let's just stop drugging if cap meet another or leave the table.
                         */
                        final float x = event.getX( pointerIndex );
                        final float y = event.getY( pointerIndex );
                        float dx = (x - cap.getEventX());
                        float dy = (y - cap.getEventY());

                        if (cap.getState() == CAP_TOUCH)
                        {
                            final int touchSlop = getTouchSlop();
                            if (Math.sqrt(dx*dx + dy*dy) < touchSlop)
                            {
                                /* Let's wait more significant movement */
                                break;
                            }
                            cap.setState( CAP_DRAG );
                        }

                        float cx = cap.moveByX( dx );
                        float cy = cap.moveByY( dy );

                        if ((cx >= m_tableRectEx.left) &&
                            (cx <= m_tableRectEx.right) &&
                            (cy >= m_tableRectEx.top) &&
                            (cy <= m_tableRectEx.bottom))
                        {
                            final float minDistance = (m_ballRadius * 2f);
                            int idx = 0;
                            for (; idx<m_cap.length; idx++)
                            {
                                if (m_cap[idx] != cap)
                                {
                                    if (m_cap[idx].contains(cx, cy, minDistance))
                                        break;
                                }
                            }

                            if (idx == m_cap.length)
                            {
                                final float fcx = cx;
                                final float fcy = cy;
                                executeOnRenderThread( new RenderThreadRunnable() {
                                    public boolean runOnRenderThread(int frameId) {
                                        cap.updateMatrix( fcx, (getHeight() - fcy), m_ballRadius, m_tmpMatrix );
                                        return false;
                                    }
                                } );

                                final float vcx = (cap.getX() - m_tableRect.left - m_tableRect.width()/2) * m_scale;
                                final float vcy = (m_tableRect.height()/2 - (cap.getY() - m_tableRect.top)) * m_scale;
                                final RetainableByteBuffer msg = Protocol.DragCap.create( m_byteBufferPool, (short)cap.getID(), vcx, vcy, 0 );
                                m_session.sendMessage( msg );
                                msg.release();

                                cap.setEventPosition( x, y );
                            }
                            else
                            {
                                /* Collision with another cap, caps now can significantly intersect,
                                 * have to put them properly.
                                 */
                                dx = (cx - m_cap[idx].getX());
                                dy = (cy - m_cap[idx].getY());
                                final float dist = (float) Math.sqrt( dx*dx + dy*dy );
                                final float fcx = m_cap[idx].getX() + minDistance*dx/dist;
                                final float fcy = m_cap[idx].getY() + minDistance*dy/dist;
                                executeOnRenderThread( new RenderThreadRunnable() {
                                    public boolean runOnRenderThread(int frameId) {
                                        cap.updateMatrix( fcx, (getHeight() - fcy), m_ballRadius, m_tmpMatrix );
                                        return false;
                                    }
                                } );
                                cap.moveTo( fcx, fcy );
                                m_capByPointer.remove( pointerId );
                            }
                        }
                        else
                        {
                            /* Out of the table */
                            if (cx < m_tableRectEx.left)
                                cx = m_tableRectEx.left;
                            else if (cx > m_tableRectEx.right)
                                cx = m_tableRectEx.right;

                            if (cy < m_tableRectEx.top)
                                cy = m_tableRectEx.top;
                            else if (cy > m_tableRectEx.bottom)
                                cy = m_tableRectEx.bottom;

                            cap.moveTo( cx, cy );

                            final float fcx = cx;
                            final float fcy = cy;
                            executeOnRenderThread( new RenderThreadRunnable() {
                                public boolean runOnRenderThread(int frameId) {
                                    cap.updateMatrix( fcx, (getHeight() - fcy), m_ballRadius, m_tmpMatrix );
                                    return false;
                                }
                            } );

                            m_capByPointer.remove( pointerId );
                        }
                    }
                    /* else pointer missed the cap when was down */
                }
            }
        }
        else if ((action == MotionEvent.ACTION_UP) ||
                 (action == MotionEvent.ACTION_CANCEL))
        {
            if (m_state == STATE_BALL_DRAG)
            {
                final float x = event.getX();
                final float y = event.getY();
                final float dx = (x - m_eventX);
                final float dy = (y - m_eventY);
                final float bx = m_ball.moveByX( dx );
                final float by = m_ball.moveByY( dy );

                if ((bx >= m_tableRectEx.left) && (bx <= m_tableRectEx.right) &&
                    (by >= m_tableRectEx.top) && (by <= m_tableRectEx.bottom))
                {
                    /* Ball in the valid range, proceed with caps. */
                    final int capIdx = 0;
                    m_capIdx = capIdx;

                    executeOnRenderThread( new RenderThreadRunnable() {
                        public boolean runOnRenderThread(int frameId) {
                            m_ball.updateMatrix( bx, (getHeight() - by), m_ballRadius, m_tmpMatrix );
                            m_cap[capIdx].updateMatrix( m_bottomLineX, (getHeight() - m_bottomLineY), m_ballRadius, m_tmpMatrix );
                            return false;
                        }
                    } );

                    final RetainableByteBuffer msg = Protocol.PutBall.create( m_byteBufferPool, getVirtualX(bx), getVirtualY(by) );
                    m_session.sendMessage( msg );
                    msg.release();

                    m_cap[capIdx].moveTo( m_bottomLineX, m_bottomLineY );
                    m_state = STATE_CAP_SET;
                    m_activity.playSound_BallPut();
                }
                else
                {
                    executeOnRenderThread( new RenderThreadRunnable() {
                        public boolean runOnRenderThread(int frameId) {
                            m_ball.updateMatrix( m_bottomLineX, (getHeight() - m_bottomLineY), m_ballRadius, m_tmpMatrix );
                            return false;
                        }
                    } );

                    final RetainableByteBuffer msg = Protocol.RemoveBall.create( m_byteBufferPool );
                    m_session.sendMessage( msg );
                    msg.release();

                    m_ball.moveTo( m_bottomLineX, m_bottomLineY );
                    m_state = STATE_BALL_SET;
                }
            }
            else if (m_state == STATE_CAP_DRAG)
            {
                final int capIdx = m_capIdx;
                final float cx = m_cap[capIdx].getX();
                final float cy = m_cap[capIdx].getY();

                if ((cx >= m_tableRectEx.left) && (cx <= m_tableRectEx.right) &&
                    (cy >= m_tableRectEx.top) && (cy <= m_tableRectEx.bottom))
                {
                    if (m_ball.isVisible() && m_ball.contains(cx, cy, m_ballRadius))
                    {
                        /* Cap is almost over the ball, let' put it exactly on the ball. */
                        final float bx = m_ball.getX();
                        final float by = m_ball.getY();
                        m_ball.setVisible( false );
                        m_capWithBall = m_capIdx;

                        short gambleTime;
                        final int capIdxx = ++m_capIdx;
                        if (capIdxx == m_cap.length)
                        {
                            /* Last cap */
                            executeOnRenderThread( new RenderThreadRunnable() {
                                public boolean runOnRenderThread(int frameId) {
                                    m_cap[capIdx].updateMatrix( bx, (getHeight() - by), m_ballRadius, m_tmpMatrix );
                                    return false;
                                }
                            } );

                            m_cap[capIdx].moveTo( bx, by );
                            m_state = STATE_GAMBLE;

                            final GambleTimer gambleTimer = new GambleTimerImpl(m_gameTime);
                            m_timerManager.scheduleTimer( getTimerQueue(), gambleTimer );

                            gambleTime = m_gameTime;
                        }
                        else
                        {
                            /* Set next cap */
                            executeOnRenderThread( new RenderThreadRunnable() {
                                public boolean runOnRenderThread(int frameId) {
                                    final int height = getHeight();
                                    m_cap[capIdx].updateMatrix( bx, (height - by), m_ballRadius, m_tmpMatrix );
                                    m_cap[capIdxx].updateMatrix( m_bottomLineX, (height-m_bottomLineY), m_ballRadius, m_tmpMatrix );
                                    return false;
                                }
                            } );
                            m_cap[capIdx].moveTo( bx, by );
                            m_cap[capIdxx].moveTo( m_bottomLineX, m_bottomLineY );
                            m_state = STATE_CAP_SET;
                            gambleTime = 0;
                        }

                        RetainableByteBuffer msg = Protocol.RemoveBall.create( m_byteBufferPool );
                        m_session.sendMessage( msg );
                        msg.release();

                        final float vcx = (bx - m_tableRect.left - m_tableRect.width()/2) * m_scale;
                        final float vcy = (m_tableRect.height()/2 - (by - m_tableRect.top)) * m_scale;
                        msg = Protocol.PutCap.create( m_byteBufferPool, (short)capIdx, vcx, vcy, /*gambleTime*/ gambleTime );
                        m_session.sendMessage( msg );
                        msg.release();

                        m_activity.playSound_CapPut();
                    }
                    else if (m_ball.isVisible() && m_ball.contains(cx, cy, m_ballRadius*2))
                    {
                        /* Intersect with ball,
                         * but does not cover even half of the ball,
                         * let's move cap to the start point.
                         */
                        executeOnRenderThread( new RenderThreadRunnable() {
                            public boolean runOnRenderThread(int frameId) {
                                m_cap[capIdx].updateMatrix( m_bottomLineX, (getHeight() - m_bottomLineY), m_ballRadius, m_tmpMatrix );
                                return false;
                            }
                        } );

                        final RetainableByteBuffer msg = Protocol.RemoveCap.create( m_byteBufferPool, (short)capIdx );
                        m_session.sendMessage( msg );
                        msg.release();

                        m_cap[capIdx].moveTo( m_bottomLineX, m_bottomLineY );
                        m_state = STATE_CAP_SET;
                    }
                    else
                    {
                        /* Check that new cap does not intersect with all previously set */
                        int idx = 0;
                        for (; idx<m_capIdx; idx++)
                        {
                            if (m_cap[idx].contains(cx, cy, m_ballRadius*2))
                                break;
                        }

                        if (idx == m_capIdx)
                        {
                            final int capIdxx = ++m_capIdx;
                            if (capIdxx == m_cap.length)
                            {
                                if (m_ball.isVisible())
                                {
                                    /* Last cap should cover ball if it is still visible */
                                    executeOnRenderThread( new RenderThreadRunnable() {
                                        public boolean runOnRenderThread(int frameId) {
                                            final int height = getHeight();
                                            m_cap[capIdx].updateMatrix( m_bottomLineX, (height-m_bottomLineY), m_ballRadius, m_tmpMatrix );
                                            return false;
                                        }
                                    } );

                                    final RetainableByteBuffer msg = Protocol.RemoveCap.create( m_byteBufferPool, (short)capIdx );
                                    m_session.sendMessage( msg );
                                    msg.release();

                                    m_capIdx = capIdx;
                                    m_cap[capIdx].moveTo( m_bottomLineX, m_bottomLineY );
                                    m_state = STATE_CAP_SET;
                                }
                                else
                                {
                                    final Cap cap = m_cap[capIdx];
                                    final float vcx = getVirtualX( cap.getX() );
                                    final float vcy = getVirtualY( cap.getY() );
                                    final RetainableByteBuffer msg = Protocol.PutCap.create(
                                            m_byteBufferPool, (short)capIdx, vcx, vcy, m_gameTime);

                                    m_session.sendMessage( msg );
                                    msg.release();

                                    m_state = STATE_GAMBLE;
                                    m_activity.playSound_CapPut();

                                    final GambleTimer gambleTimer = new GambleTimerImpl(m_gameTime);
                                    m_timerManager.scheduleTimer( getTimerQueue(), gambleTimer );
                                }
                            }
                            else
                            {
                                executeOnRenderThread( new RenderThreadRunnable() {
                                    public boolean runOnRenderThread(int frameId) {
                                        final int height = getHeight();
                                        m_cap[capIdxx].updateMatrix( m_bottomLineX, (height-m_bottomLineY), m_ballRadius, m_tmpMatrix );
                                        return false;
                                    }
                                } );

                                final Cap cap = m_cap[capIdx];
                                final float vcx = (cap.getX() - m_tableRect.left - m_tableRect.width()/2) * m_scale;
                                final float vcy = (m_tableRect.height()/2 - (cap.getY() - m_tableRect.top)) * m_scale;
                                final RetainableByteBuffer msg = Protocol.PutCap.create( m_byteBufferPool, (short)capIdx, vcx, vcy, /*gambleTime*/(short)0 );
                                m_session.sendMessage( msg );
                                msg.release();

                                m_cap[capIdxx].moveTo( m_bottomLineX, m_bottomLineY );
                                m_state = STATE_CAP_SET;
                                m_activity.playSound_CapPut();
                            }
                        }
                        else
                        {
                            /* Cap intersects with a cap set before, remove it. */
                            executeOnRenderThread( new RenderThreadRunnable() {
                                public boolean runOnRenderThread(int frameId) {
                                    m_cap[capIdx].updateMatrix( m_bottomLineX, (getHeight() - m_bottomLineY), m_ballRadius, m_tmpMatrix );
                                    return false;
                                }
                            } );

                            final RetainableByteBuffer msg = Protocol.RemoveCap.create( m_byteBufferPool, (short)capIdx );
                            m_session.sendMessage( msg );
                            msg.release();

                            m_cap[capIdx].moveTo( m_bottomLineX, m_bottomLineY );
                            m_state = STATE_CAP_SET;
                        }
                    }
                }
                else
                {
                    /* Cap is not in the valid range */
                    executeOnRenderThread( new RenderThreadRunnable() {
                        public boolean runOnRenderThread(int frameId) {
                            m_cap[capIdx].updateMatrix( m_bottomLineX, (getHeight() - m_bottomLineY), m_ballRadius, m_tmpMatrix );
                            return false;
                        }
                    } );

                    final RetainableByteBuffer msg = Protocol.RemoveCap.create( m_byteBufferPool, (short)capIdx );
                    m_session.sendMessage( msg );
                    msg.release();

                    m_cap[capIdx].moveTo( m_bottomLineX, m_bottomLineY );
                    m_state = STATE_CAP_SET;
                }
            }
            else if (m_state == STATE_GAMBLE)
            {
                final int pointerIndex = event.getActionIndex();
                final int pointerId = event.getPointerId( pointerIndex );
                Log.d( LOG_TAG, "STATE_GAMBLE: ACTION_UP/ACTION_CANCEL: pointerIndex=" + pointerIndex + " pointerId=" + pointerId );
                m_capByPointer.remove( pointerId );
            }
            else if (m_state == STATE_GAMBLE_TIMER_TOUCH)
            {
                if ((Math.abs(event.getX() - m_bottomLineX) < getBottomReservedHeight()/2) &&
                    (Math.abs(event.getY() - m_bottomLineY) < getBottomReservedHeight()/2))
                {
                    try
                    {
                        if (m_timerManager.cancelTimer(getTimerQueue()))
                        {
                            m_state = STATE_WAIT_REPLY;
                            executeOnRenderThread( new RenderThreadRunnable() {
                                public boolean runOnRenderThread(int frameId) {
                                    setBottomLineText( R.string.waiting, GAMBLE_TIMER_COLOR, 0.5f );
                                    return false;
                                }
                            } );

                            final RetainableByteBuffer msg = Protocol.Guess.create( m_byteBufferPool, (short) m_capWithBall );
                            m_session.sendMessage( msg );
                            msg.release();
                        }
                    }
                    catch (final InterruptedException ex)
                    {
                        Log.w( LOG_TAG, "Exception:", ex );
                    }
                }
                else
                    m_state = STATE_GAMBLE;
            }
        }
        return true;
    }

    public Intent onPauseEx()
    {
        if (BuildConfig.DEBUG && m_pause)
            throw new AssertionError();

        m_pause = true;
        boolean interrupted = false;

        try
        {
            m_timerManager.cancelTimer( getTimerQueue() );
        }
        catch (final InterruptedException ex)
        {
            Log.w( LOG_TAG, "Exception:", ex );
            interrupted = true;
        }

        try
        {
            stopCollider();
        }
        catch (final InterruptedException ex)
        {
            Log.w( LOG_TAG, ex.toString() );
            interrupted = true;
        }

        /* Game acceptor is not working now, no new session can appear.
         * The only thing remaining is a service registration.
         */

        m_lock.lock();
        try
        {
            if (m_registrationListener != null)
            {
                if (!m_registrationListenerStop)
                {
                    m_registrationListenerStop = true;
                    m_nsdManager.unregisterService( m_registrationListener );
                }

                do
                {
                    m_cond.await();
                }
                while (m_registrationListener != null);
            }
        }
        catch (final InterruptedException ex)
        {
            Log.w( LOG_TAG, ex.toString() );
            interrupted = true;
        }
        finally
        {
            m_lock.unlock();
        }

        if (interrupted)
            Thread.currentThread().interrupt();

        super.onPause();

        if (m_state == STATE_WAIT_CLIENT)
            return null;
        else if (m_clientDisconnected)
            return null; /* Activity result already set */
        else if (m_state == STATE_FINISHED)
        {
            final Intent result = new Intent();
            result.putExtra(MainActivity.EXTRA_DEVICE_ID, m_clientDeviceId);
            result.putExtra(MainActivity.EXTRA_WIN, (m_win > 0));
            return result;
        }
        else
        {
            final Intent result = new Intent();
            result.putExtra(MainActivity.EXTRA_TITLE_ID, R.string.info);
            result.putExtra(MainActivity.EXTRA_MESSAGE_ID, R.string.quit_game_before_end);
            result.putExtra(MainActivity.EXTRA_DEVICE_ID, m_clientDeviceId);
            result.putExtra(MainActivity.EXTRA_WIN, false);
            return result;
        }
    }
}
