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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;
import android.view.MotionEvent;
import org.jsl.collider.*;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public class GameClientView extends GameView
{
    private static String LOG_TAG = GameClientView.class.getSimpleName();

    private static final AtomicIntegerFieldUpdater<GameClientView>
            s_stateUpdater = AtomicIntegerFieldUpdater.newUpdater( GameClientView.class, "m_state" );

    private static final float ANGLE_ADJUST = 4.0f;
    private static final float MAX_FRAME_MOVE = 20.0f;

    private static final float ANGLE_X_MIN = (float) (Math.PI / 2f * 0.1f);
    private static final float ANGLE_X_MAX = (float) (Math.PI / 2f * 0.9f);
    private static final float ANGLE_Z_MIN = (float) (-Math.PI / 2f / 2f);
    private static final float ANGLE_Z_MAX = (float) (Math.PI / 2f / 2f);

    private static final int STATE_WATCH    = 0;
    private static final int STATE_GUESS    = 1;
    private static final int STATE_CHECK    = 2;
    private static final int STATE_FINISHED = 3;

    private static final int TOUCH_STATE_TOUCH = 1;
    private static final int TOUCH_STATE_DRAG = 2;

    private class GameConnector extends Connector
    {
        private final PingConfig m_pingConfig;
        private final short m_desiredTableHeight;
        private final String m_deviceId;
        private final String m_playerName;

        public GameConnector(InetSocketAddress addr, PingConfig pingConfig, short desiredTableHeight, String deviceId, String playerName)
        {
            super( addr );
            m_pingConfig = pingConfig;
            m_desiredTableHeight = desiredTableHeight;
            m_deviceId = deviceId;
            m_playerName = playerName;
        }

        public Session.Listener createSessionListener( Session session )
        {
            Log.d( LOG_TAG, session.getRemoteAddress().toString() + ": connected" );
            return new HandshakeClientSession(
                    GameClientView.this,
                    session,
                    m_pingConfig,
                    m_desiredTableHeight,
                    m_deviceId,
                    m_playerName );
        }

        public void onException( IOException ex )
        {
            m_state = STATE_FINISHED; // avoid score discount
            Log.i(LOG_TAG, getAddr().toString(), ex);
            m_activity.showMessageAndFinish(R.string.error, R.string.cant_connect_to_server, m_serverDeviceId);
        }
    }

    private static class Ball
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
            m_visible = false;
        }

        public void updateMatrix( float x, float y, float radius, float [] eyePosition, float [] tmp )
        {
            m_visible = true;
            m_x = x;
            m_y = y;
            updateMatrix( radius, eyePosition, tmp );
        }

        public void updateMatrix( float radius, float [] eyePosition, float [] tmp )
        {
            Vector.set( tmp, 0, /*x*/0f, /*y*/0f, /*z*/1f );
            Vector.crossProduct( tmp, 48, tmp, 0, eyePosition, 0 );
            final float angle = (float) Math.asin( Vector.length(tmp, 48) / Vector.length(eyePosition, 0) );

            Matrix.setIdentityM( tmp, 0 );
            Matrix.translateM( tmp, 0, m_x, m_y, radius );
            Matrix.setIdentityM( tmp, 16 );
            Matrix.scaleM( tmp, 16, radius, radius, radius );
            Matrix.multiplyMM( tmp, 32, tmp, 0, tmp, 16 );
            Matrix.setRotateM( tmp, 0, (float) (angle*180f/Math.PI), tmp[48], tmp[49], tmp[50] );
            Matrix.multiplyMM( m_matrix, 0, tmp, 32, tmp, 0 );
        }

        public void draw( float [] vpMatrix, float [] light, float [] tmp )
        {
            if (m_visible)
            {
                Matrix.multiplyMM( tmp, 0, vpMatrix, 0, m_matrix, 0 );

                /* tmp[16-19] : light direction */
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
    }

    private static class Cap
    {
        private final Model m_model;
        private final float [] m_matrix;
        private boolean m_visible;
        private float m_x;
        private float m_y;
        private float m_z;
        private int   m_lastFrameId;
        private float m_lastFrameX;
        private float m_lastFrameY;

        public Cap( Model model )
        {
            m_model = model;
            m_matrix = new float[16];
            m_visible = false;
            m_lastFrameId = -1;
        }

        public boolean updateMatrix( float x, float y, float z, float radius, int frameId, float [] tmp )
        {
            Matrix.setIdentityM( tmp, 0 );
            Matrix.translateM( tmp, 0, x, y, z );
            Matrix.setIdentityM( tmp, 16 );
            Matrix.scaleM( tmp, 16, radius, radius, radius );
            Matrix.multiplyMM( m_matrix, 0, tmp, 0, tmp, 16 );
            m_visible = true;
            m_x = x;
            m_y = y;
            m_z = z;

            if (m_lastFrameId == frameId)
            {
                if ((Math.abs(m_lastFrameX-m_x) >= MAX_FRAME_MOVE) ||
                    (Math.abs(m_lastFrameY-m_y) >= MAX_FRAME_MOVE))
                {
                    /* Render frame */
                    return true;
                }
            }
            else
            {
                m_lastFrameId = frameId;
                m_lastFrameX = m_x;
                m_lastFrameY = m_y;
            }
            return false;
        }

        public void draw( float [] vpMatrix, float [] lightPosition, float [] tmp )
        {
            if (m_visible)
            {
                Matrix.multiplyMM( tmp, 0, vpMatrix, 0, m_matrix, 0 );

                tmp[16] = (lightPosition[0] - m_x);
                tmp[17] = (lightPosition[1] - m_y);
                tmp[18] = lightPosition[2];

                m_model.draw( tmp, 0, tmp, 16 );
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

        public float getX() { return m_x; }
        public float getY() { return m_y; }
        public float getZ() { return m_z; }
    }

    private void setBottomLineText( int resId, int color, float fontSize )
    {
        setBottomLineText( getContext().getString(resId), color, fontSize );
    }

    private void setBottomLineText( String str, int color, float fontSize )
    {
        m_bottomLineString = str;
        m_bottomLineStringFontSize = (getBottomReservedHeight() * fontSize);
        m_bottomLineStringColor = color;
    }

    private void updateTableViewMatrixRT( float angleX, float angleZ )
    {
        if (BuildConfig.DEBUG && (m_renderThreadId != Thread.currentThread().getId()))
            throw new AssertionError();

        final float viewWidth = getWidth();
        final float viewHeight = getHeight();

        /* 0 4  8 12
         * 1 5  9 13
         * 2 6 10 14
         * 3 7 11 15
         */
        Matrix.setRotateM( m_tableMatrix, 16, angleX, 1f, 0f, 0f );
        Matrix.setRotateM( m_tableMatrix, 32, angleZ, 0f, 0f, 1f );
        Matrix.multiplyMM( m_tableMatrix, 0, m_tableMatrix, 32, m_tableMatrix, 16 );
        m_eyePosition[0] = m_viewDistance * m_tableMatrix[8];
        m_eyePosition[1] = m_viewDistance * m_tableMatrix[9];
        m_eyePosition[2] = m_viewDistance * m_tableMatrix[10];
        final float upX = m_tableMatrix[4];
        final float upY = m_tableMatrix[5];
        final float upZ = m_tableMatrix[6];

        Matrix.frustumM( m_tableMatrix, 16,
                /*left*/    -viewWidth/2,
                /*right*/    viewWidth/2,
                /*bottom*/ -viewHeight/2,
                /*top*/     viewHeight/2,
                /*near*/    viewWidth*3-50,
                /*far*/     viewWidth*4+50 );

        Matrix.setLookAtM( m_tableMatrix, 32,
              /*eye X*/    m_eyePosition[0],
              /*eye Y*/    m_eyePosition[1],
              /*eye Z*/    m_eyePosition[2],
              /*center X*/ 0.0f,
              /*center Y*/ 0.0f,
              /*center Z*/ 0.0f,
              /*up X*/     upX,
              /*up Y*/     upY,
              /*up Z*/     upZ );
        Matrix.multiplyMM( m_tableMatrix, 0, m_tableMatrix, 16, m_tableMatrix, 32 );

        m_ball.updateMatrix( m_ballRadius, m_eyePosition, m_tmpMatrix );
    }

    private void onTouchEventRT( float touchX, float touchY, int frameId )
    {
        /* executed on render thread */
        int touchCapIdx = -1;
        float distance = 0;

        for (int idx = 0; idx < m_cap.length; idx++)
        {
            final Cap cap = m_cap[idx];
            if (cap.isVisible())
            {
                final float x = cap.getX()*m_tableMatrix[0] + cap.getY()*m_tableMatrix[4] + cap.getZ()*m_tableMatrix[8] + m_tableMatrix[12];
                final float y = cap.getX()*m_tableMatrix[1] + cap.getY()*m_tableMatrix[5] + cap.getZ()*m_tableMatrix[9] + m_tableMatrix[13];
                final float w = m_tableMatrix[3] + m_tableMatrix[7] + m_tableMatrix[11] + m_tableMatrix[15];
                final float screenX = ((x/w + 1f) / 2f) * getWidth();
                final float screenY = (getHeight() - ((y/w + 1f) / 2f) * getHeight());

                final float dx = (screenX - touchX);
                final float dy = (screenY - touchY);
                if (Math.sqrt( dx * dx + dy * dy ) <= m_ballRadius)
                {
                    final float d = m_viewDistance - (m_eyePosition[0]*cap.getX() + m_eyePosition[1]*cap.getY() + m_eyePosition[2]*cap.getZ()) / m_viewDistance;
                    if ((touchCapIdx < 0) || (d < distance))
                    {
                        touchCapIdx = idx;
                        distance = d;
                    }
                }
            }
        }

        if (touchCapIdx >= 0)
        {
            s_stateUpdater.set( this, STATE_FINISHED );

            Cap cap = m_cap[touchCapIdx];
            cap.updateMatrix( cap.getX(), cap.getY(), m_ballRadius*4, m_ballRadius, frameId, m_tmpMatrix );

            boolean found;

            if (touchCapIdx == m_capWithBall)
            {
                setBottomLineText( R.string.you_win, WIN_TEXT_COLOR, GAMBLE_TIMER_FONT_SIZE );
                found = true;
            }
            else
            {
                setBottomLineText( R.string.you_lose, LOSE_TEXT_COLOR, GAMBLE_TIMER_FONT_SIZE );

                cap = m_cap[m_capWithBall];
                cap.updateMatrix( cap.getX(), cap.getY(), m_ballRadius*4, m_ballRadius, frameId, m_tmpMatrix );
                found = false;
            }

            m_ball.updateMatrix( cap.getX(), cap.getY(), m_ballRadius, m_eyePosition, m_tmpMatrix );

            final ByteBuffer msg = Protocol.GuessReply.create( found );
            m_session.sendMessage( msg );
        }
        else
        {
            /* No cap touched, let's wait new touch */
            for (;;)
            {
                final int state = s_stateUpdater.get( this );
                if (state == STATE_CHECK)
                {
                    if (s_stateUpdater.compareAndSet(this, state, STATE_GUESS))
                        break;
                }
                else
                    break;
            }
        }
    }

    private class GambleTimerImpl extends GambleTimer
    {
        protected void onStop()
        {
            m_timerManager.resetTimer( this );
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

    private final GameClientActivity m_activity;
    private final InetSocketAddress m_serverAddr;
    private final String m_serverDeviceId;
    private final String m_serverPlayerName;
    private final float [] m_tmpMatrix;
    private GameClientSession m_session;
    private boolean m_pause;
    private long m_renderThreadId;
    private float m_viewDistance;
    private final float [] m_eyePosition;
    private float m_scale;
    private float m_tableWidth;
    private float m_tableHeight;
    private Table m_table;
    private float [] m_tableMatrix;
    private Ball m_ball;
    private float m_ballRadius;
    private float [] m_light;
    private Cap [] m_cap;

    private final TimerManager m_timerManager;

    private volatile int m_state;
    private int m_capWithBall;

    private float m_bottomLineX;
    private float m_bottomLineY;
    private String m_bottomLineString;
    private int m_bottomLineStringColor;
    private float m_bottomLineStringFontSize;

    private int m_touchState;
    private float m_eventX;
    private float m_eventY;
    private float m_angleX;
    private float m_angleZ;

    public GameClientView(
            GameClientActivity activity,
            String deviceId,
            String playerName,
            InetSocketAddress serverAddr,
            String serverDeviceId,
            String serverPlayerName)
    {
        super(activity, deviceId, playerName);
        m_activity = activity;
        m_serverAddr = serverAddr;
        m_serverDeviceId = serverDeviceId;
        m_serverPlayerName = serverPlayerName;
        m_tmpMatrix = new float[16*5];
        m_eyePosition = new float[3];
        m_timerManager = new TimerManager();
        m_state = STATE_WATCH;
    }

    public void onSurfaceCreated( GL10 gl, EGLConfig config )
    {
        super.onSurfaceCreated( gl, config );
        GLES20.glEnable( GLES20.GL_DEPTH_TEST );
    }

    public void onSurfaceChanged( GL10 gl, int width, int height )
    {
        /* Looks like a better place to start all than surfaceCreated() */
        super.onSurfaceChanged( gl, width, height );

        try
        {
            final Collider collider = startCollider();
            final Connector connector = new GameConnector(
                    m_serverAddr,
                    getPingConfig(),
                    getDesiredTableHeight(width, height),
                    getDeviceId(),
                    getPlayerName() );
            collider.addConnector( connector );
        }
        catch (final IOException ex)
        {
            Log.e( LOG_TAG, ex.toString() );
        }
    }

    public void onDrawFrame( float [] vpMatrix, Canvas3D canvas3D )
    {
        super.onDrawFrame( vpMatrix, canvas3D );

        if (m_table != null)
        {
            m_table.draw( m_tableMatrix, m_light, m_eyePosition );
            m_ball.draw( m_tableMatrix, m_light, m_tmpMatrix );
            for (Cap cap : m_cap)
                cap.draw( m_tableMatrix, m_light, m_tmpMatrix );
        }

        if (m_bottomLineString != null)
        {
            canvas3D.drawText(
                    vpMatrix,
                    m_bottomLineString,
                    /*x*/ m_bottomLineX,
                    /*y*/ m_bottomLineY,
                    /*z*/ 1f,
                    m_bottomLineStringFontSize,
                    m_bottomLineStringColor,
                    Paint.Align.CENTER );
        }
    }

    public void onConnected( GameClientSession session, short virtualTableHeight, short virtualBallRadius )
    {
        final float viewWidth = getWidth();
        final float viewHeight = getHeight();

        m_session = session;
        m_scale = (viewWidth / VIRTUAL_TABLE_WIDTH);
        m_tableWidth = viewWidth;
        m_tableHeight = (virtualTableHeight * m_scale);
        m_ballRadius = (virtualBallRadius * m_scale);
        m_light = new float[6];
        final Bitmap statusLine = createStatusLine( -1, m_serverPlayerName );

        Log.d( LOG_TAG, "onConnected: tableSize=(" + m_tableWidth + ", " + m_tableHeight + ")" );

        m_viewDistance = (viewWidth * 3.5f);

        final float bottomHeight2 = (getBottomReservedHeight() / 2f);
        m_bottomLineX = (viewWidth / 2f);
        m_bottomLineY = (viewHeight/2f - viewWidth/2f - bottomHeight2);
        if (m_bottomLineY < bottomHeight2)
            m_bottomLineY = bottomHeight2;

        m_light[0] = (-viewWidth/4);     /*x*/
        m_light[1] = (-viewWidth/4);     /*y*/
        m_light[2] = (viewWidth / 2.0f); /*z*/
        m_light[3] = 1.0f; /*r*/
        m_light[4] = 1.0f; /*g*/
        m_light[5] = 1.0f; /*b*/

        m_angleX = (float) (Math.PI / 4f);
        m_angleZ = 0f;

        final float angleXd = (float) (m_angleX * 180f / Math.PI);
        final float angleZd = (float) (m_angleZ * 180f / Math.PI);
        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                final Context context = getContext();
                try
                {
                    m_renderThreadId = Thread.currentThread().getId();
                    m_table = new Table( context );

                    final ModelBall modelBall = new ModelBall( context, BALL_COLOR );
                    m_ball = new Ball( modelBall );

                    final ModelCap modelCap = new ModelCap( context, CAP_STRIPES );
                    m_cap = new Cap[3];
                    for (int idx=0; idx<m_cap.length; idx++)
                        m_cap[idx] = new Cap( modelCap );
                    m_capWithBall = -1;
                }
                catch (final IOException ex)
                {
                    Log.d( LOG_TAG, ex.toString() );
                }
                m_table.setSize( m_tableWidth, m_tableHeight );
                m_tableMatrix = new float[48];
                updateTableViewMatrixRT( angleXd, angleZd );
                setStatusLine( statusLine );
                return false;
            }
        } );
    }

    public void onServerDisconnected()
    {
        if (!m_pause)
        {
            /* Server disconnected, client won. */
            for (;;)
            {
                final int state = s_stateUpdater.get( this );
                if (state == STATE_FINISHED)
                    break;
                if (s_stateUpdater.compareAndSet(this, state, STATE_FINISHED))
                {
                    m_activity.showMessage( R.string.info, R.string.other_player_quit_before_game_end );
                    break;
                }
            }
        }
    }

    protected Bitmap createStatusLine()
    {
        /* Status line before connect */
        final int width = getWidth();
        final Bitmap bitmap = Bitmap.createBitmap( width, getTopReservedHeight(), Bitmap.Config.RGB_565 );
        final Canvas canvas = new Canvas( bitmap );
        final Paint paint = getPaint();
        final float textY = -paint.ascent();

        paint.setColor( Color.WHITE );
        paint.setTextAlign( Paint.Align.CENTER );
        canvas.drawText( m_serverAddr.toString(), 0, textY, paint );

        return bitmap;
    }

    private void updateAngles( float dx, float dy )
    {
        float angleX = (float) -(Math.asin(dy / m_viewDistance) * ANGLE_ADJUST);
        float angleZ = (float) -(Math.asin(dx / m_viewDistance) * ANGLE_ADJUST);

        angleX = m_angleX + angleX;
        if (angleX > ANGLE_X_MAX)
            angleX = ANGLE_X_MAX;
        else if (angleX < ANGLE_X_MIN)
            angleX = ANGLE_X_MIN;

        angleZ = m_angleZ + angleZ;
        if (angleZ > ANGLE_Z_MAX)
            angleZ = ANGLE_Z_MAX;
        else if (angleZ < ANGLE_Z_MIN)
            angleZ = ANGLE_Z_MIN;

        if ((angleX != m_angleX) || (angleZ != m_angleZ))
        {
            final float angleXd = (float) (angleX / Math.PI * 180f);
            final float angleZd = (float) (angleZ / Math.PI * 180f);
            executeOnRenderThread( new RenderThreadRunnable() {
                public boolean runOnRenderThread(int frameId) {
                    updateTableViewMatrixRT( angleXd, angleZd );
                    return false;
                }
            } );
            m_angleX = angleX;
            m_angleZ = angleZ;
            m_touchState = TOUCH_STATE_DRAG;
        }
    }

    public boolean onTouchEvent( MotionEvent event )
    {
        if (event.getAction() == MotionEvent.ACTION_DOWN)
        {
            final float x = event.getX();
            final float y = event.getY();

            for (;;)
            {
                final int state = s_stateUpdater.get( this );
                if (state == STATE_GUESS)
                {
                    if (s_stateUpdater.compareAndSet(this, state, STATE_CHECK))
                    {
                        executeOnRenderThread( new RenderThreadRunnable() {
                            public boolean runOnRenderThread(int frameId) {
                                onTouchEventRT( x, y, frameId );
                                return false;
                            }
                        } );
                        break;
                    }
                }
                else
                    break;
            }

            if (m_touchState == 0)
            {
                m_touchState = TOUCH_STATE_TOUCH;
                m_eventX = event.getX();
                m_eventY = event.getY();
            }
        }
        else if (event.getAction() == MotionEvent.ACTION_MOVE)
        {
            if (m_touchState == TOUCH_STATE_TOUCH)
            {
                final float x = event.getX();
                final float y = event.getY();
                final float dx = (x - m_eventX);
                final float dy = (y - m_eventY);
                final int touchSlop = getTouchSlop();
                if (Math.sqrt(dx*dx + dy*dy) >= touchSlop)
                {
                    updateAngles( dx, dy );
                    m_eventX = x;
                    m_eventY = y;
                }
            }
            else if (m_touchState == TOUCH_STATE_DRAG)
            {
                final float x = event.getX();
                final float y = event.getY();
                final float dx = (x - m_eventX);
                final float dy = (y - m_eventY);
                updateAngles( dx, dy );
                m_eventX = x;
                m_eventY = y;
            }
        }
        else if ((event.getAction() == MotionEvent.ACTION_UP) ||
                 (event.getAction() == MotionEvent.ACTION_CANCEL))
        {
            m_touchState = 0;
        }
        return true;
    }

    public void setPing( int ping )
    {
        final Bitmap statusLine = createStatusLine( ping, m_serverPlayerName );
        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                setStatusLine( statusLine );
                return false;
            }
        } );
    }

    public void dragBallCT( float virtualX, float virtualY )
    {
        /* ball position is relative to the center of the table,
         * but second player look at the table from opposite side,
         * so we should invert position.
         */
        final float x = -(virtualX * m_scale);
        final float y = -(virtualY * m_scale);
        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                m_ball.updateMatrix( x, y, m_ballRadius, m_eyePosition, m_tmpMatrix );
                return false;
            }
        } );
    }

    public void putBallCT( float virtualX, float virtualY )
    {
        dragBallCT( virtualX, virtualY );
        m_activity.playSound_BallPut();
    }

    public void removeBallCT()
    {
        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                m_ball.setVisible( false );
                return false;
            }
        } );
    }

    public void setCapPositionCT( int id, float virtualX, float virtualY, float virtualZ )
    {
        /* cap position is relative to the center of the table,
         * side should be changed as well as for ball.
         */
        final float x = -(virtualX * m_scale);
        final float y = -(virtualY * m_scale);
        final float z = (virtualZ * m_scale);
        final int cid = id;
        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                return m_cap[cid].updateMatrix( x, y, z, m_ballRadius, frameId, m_tmpMatrix );
            }
        } );
    }

    public void putCapCT( int id, float virtualX, float virtualY, int gambleTime )
    {
        setCapPositionCT( id, virtualX, virtualY, 0f );
        m_activity.playSound_CapPut();

        if (gambleTime > 0)
            m_timerManager.scheduleTimer( getTimerQueue(), new GambleTimerImpl(gambleTime) );
    }

    public void removeCapCT( int id )
    {
        final int fid = id;
        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                m_cap[fid].setVisible( false );
                return false;
            }
        } );
    }

    public void guessCT( final int capWithBall )
    {
        Log.d( LOG_TAG, "guess: capWithBall=" + capWithBall );

        boolean interrupted = false;
        try
        {
            m_timerManager.cancelTimer( getTimerQueue() );
        }
        catch (final InterruptedException ex)
        {
            Log.w( LOG_TAG, "Exception", ex );
            interrupted = true;
        }

        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                for (;;)
                {
                    final int state = s_stateUpdater.get( GameClientView.this );
                    if (state == STATE_WATCH)
                    {
                        if (s_stateUpdater.compareAndSet(GameClientView.this, state, STATE_GUESS))
                        {
                            m_capWithBall = capWithBall;
                            setBottomLineText( R.string.guess, Color.LTGRAY, 0.5f );
                            break;
                        }
                    }
                    else
                    {
                        if (BuildConfig.DEBUG)
                            throw new AssertionError();
                        break;
                    }
                }
                return false;
            }
        } );

        if (interrupted)
            Thread.currentThread().interrupt();
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
            Log.w( LOG_TAG, "Exception:", ex );
            interrupted = true;
        }

        super.onPause();

        if (interrupted)
            Thread.currentThread().interrupt();

        if (m_state == STATE_FINISHED)
            return null;

        final Intent result = new Intent();
        result.putExtra( MainActivity.EXTRA_TITLE_ID, R.string.info );
        result.putExtra( MainActivity.EXTRA_MESSAGE_ID, R.string.quit_game_before_end );
        result.putExtra( MainActivity.EXTRA_DEVICE_ID, m_serverDeviceId );

        return result;
    }
}
