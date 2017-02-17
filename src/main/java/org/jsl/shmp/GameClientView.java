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
import java.util.HashSet;
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

    public static class Ball
    {
        private static final int MODEL = 0;
        private static final int MODEL_INVERTED = 16;
        private static final int SHADOW = 32;

        private final ModelBall m_model;
        private final float [] m_matrix;
        private boolean m_visible;
        private float m_x;
        private float m_y;
        private float m_z;

        private static void orientMatrixTo(float [] m, int offset, float [] to, int toOffset, float [] tmp, int tmpOffset)
        {
            Vector.set(tmp, tmpOffset, /*x*/0f, /*y*/0f, /*z*/1f);
            Vector.crossProduct(tmp, tmpOffset+4, tmp, tmpOffset, to, toOffset);
            final double angle = (Math.asin(Vector.length(tmp, tmpOffset+4) / Vector.length(to, toOffset)) * 180d / Math.PI);
            Matrix.setRotateM(m, offset, (float)angle, tmp[tmpOffset+4], tmp[tmpOffset+4+1], tmp[tmpOffset+4+2]);
        }

        public Ball(Context context, int color) throws IOException
        {
            m_model = new ModelBall(context, color);
            m_matrix = new float[16*3];
            m_visible = false;
        }

        public void updateMatrix(float x, float y, float z, float radius, Vector eyePosition, Vector light, float [] tmp)
        {
            m_visible = true;
            m_x = x;
            m_y = y;
            m_z = z;

            updateMatrix(radius, eyePosition, tmp);

            // shadow model matrix = [translate matrix] x [orient matrix] x [scale matrix]
            Vector.set(tmp, 16, light.getX()-m_x, light.getY()-m_y, light.getZ()-m_z);
            orientMatrixTo(tmp, 0, tmp, 16, tmp, 20);
            Matrix.setIdentityM(tmp, 16);
            Matrix.scaleM(tmp, 16, radius, radius, radius);
            Matrix.multiplyMM(tmp, 32, tmp, 0, tmp, 16);
            Matrix.setIdentityM(tmp, 0);
            Matrix.translateM(tmp, 0, m_x, m_y, m_z);
            Matrix.multiplyMM(m_matrix, SHADOW, tmp, 0, tmp, 32);
        }

        public void updateMatrix(float radius, Vector eyePosition, float [] tmp)
        {
            // model matrix = [translate matrix] x [orient matrix] x [scale matrix]
            Vector.set(tmp, 16, eyePosition.getX()-m_x, eyePosition.getY()-m_y, eyePosition.getZ()-m_z);
            orientMatrixTo(tmp, 0, tmp, 16, tmp, 19);
            Matrix.setIdentityM(tmp, 16);
            Matrix.scaleM(tmp, 16, radius, radius, radius);
            Matrix.multiplyMM(tmp, 32, tmp, 0, tmp, 16);
            Matrix.setIdentityM(tmp, 0);
            Matrix.translateM(tmp, 0, m_x, m_y, m_z);
            Matrix.multiplyMM(m_matrix, MODEL, tmp, 0, tmp, 32);
            Matrix.invertM(m_matrix, MODEL_INVERTED, m_matrix, MODEL);
        }

        public void draw(float [] vpMatrix, Vector light, float [] tmp)
        {
            if (m_visible)
            {
                Matrix.multiplyMM(tmp, 0, vpMatrix, 0, m_matrix, MODEL);
                Matrix.multiplyMV(tmp, 16, m_matrix, MODEL_INVERTED, light.v, light.offs);
                tmp[16+3] = light.v[light.offs+4];
                tmp[16+4] = light.v[light.offs+5];
                tmp[16+5] = light.v[light.offs+6];
                m_model.draw(tmp, 0, tmp, 16);
            }
        }

        public void drawShadow(float [] vpMatrix, int vpMatrixOffset, float [] tmp)
        {
            if (m_visible)
            {
                Matrix.multiplyMM(tmp, 0, vpMatrix, vpMatrixOffset, m_matrix, SHADOW);
                m_model.drawShadow(tmp, 0);
            }
        }

        public void setVisible( boolean visible )
        {
            m_visible = visible;
        }
    }

    public static class Cup
    {
        private final ModelCup m_model;
        private final float [] m_matrix;
        private boolean m_visible;
        private float m_x;
        private float m_y;
        private float m_z;
        private int   m_lastFrameId;
        private float m_lastFrameX;
        private float m_lastFrameY;

        public Cup(ModelCup model)
        {
            m_model = model;
            m_matrix = new float[16*2];
            m_visible = false;
            m_lastFrameId = -1;
        }

        public boolean updateMatrix(float x, float y, float z, float radius, int frameId, float [] tmp)
        {
            Matrix.setIdentityM(tmp, 0);
            Matrix.translateM(tmp, 0, x, y, z);
            Matrix.setIdentityM(tmp, 16);
            Matrix.scaleM(tmp, 16, radius, radius, radius);
            Matrix.multiplyMM(m_matrix, 0, tmp, 0, tmp, 16);
            Matrix.invertM(m_matrix, 16, m_matrix, 0);
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

        public void draw(float [] vpMatrix, Vector eyePosition, Vector light, ShadowObject shadowObject, float [] tmp)
        {
            if (m_visible)
            {
                if (shadowObject == null)
                {
                    Matrix.multiplyMM(tmp, 0, vpMatrix, 0, m_matrix, 0);
                    Matrix.multiplyMV(tmp, 16, m_matrix, 16, eyePosition.v, eyePosition.offs);
                    Matrix.multiplyMV(tmp, 20, m_matrix, 16, light.v, light.offs);
                    tmp[20+3] = light.get(4);
                    tmp[20+4] = light.get(5);
                    tmp[20+5] = light.get(6);
                    m_model.draw(tmp, 0, tmp, 16, tmp, 20, null, 0, 0);
                }
                else
                {
                    final float[] shadowMatrix = shadowObject.matrix;
                    final int shadowTextureId = shadowObject.textureId;
                    Matrix.multiplyMM(tmp, 0, vpMatrix, 0, m_matrix, 0);
                    Matrix.multiplyMV(tmp, 16, m_matrix, 16, eyePosition.v, eyePosition.offs);
                    Matrix.multiplyMV(tmp, 20, m_matrix, 16, light.v, light.offs);
                    Matrix.multiplyMM(tmp, 32, shadowMatrix, 16, m_matrix, 0);
                    tmp[20+3] = light.get(4);
                    tmp[20+4] = light.get(5);
                    tmp[20+5] = light.get(6);
                    m_model.draw(tmp, 0, tmp, 16, tmp, 20, tmp, 32, shadowTextureId);
                }
            }
        }

        public void drawShadow(float [] vpMatrix, int vpMatrixOffset, float [] tmp)
        {
            if (m_visible)
            {
                Matrix.multiplyMM(tmp, 0, vpMatrix, vpMatrixOffset, m_matrix, 0);
                m_model.drawShadow(tmp, 0);
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

    private void updateTableViewMatrixRT(float angleX, float angleZ)
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
        Matrix.setRotateM(m_tableMatrix, 16, angleX, 1f, 0f, 0f);
        Matrix.setRotateM(m_tableMatrix, 32, angleZ, 0f, 0f, 1f);
        Matrix.multiplyMM(m_tableMatrix, 0, m_tableMatrix, 32, m_tableMatrix, 16);
        m_eyePosition.set(m_viewDistance * m_tableMatrix[8], m_viewDistance * m_tableMatrix[9], m_viewDistance * m_tableMatrix[10]);
        final float upX = m_tableMatrix[4];
        final float upY = m_tableMatrix[5];
        final float upZ = m_tableMatrix[6];

        Matrix.frustumM(m_tableMatrix, 16,
                /*left*/    -viewWidth/2,
                /*right*/    viewWidth/2,
                /*bottom*/ -viewHeight/2,
                /*top*/     viewHeight/2,
                /*near*/    viewWidth*3-50,
                /*far*/     viewWidth*4+50);

        Matrix.setLookAtM(m_tableMatrix, 32,
              /*eye X*/    m_eyePosition.getX(),
              /*eye Y*/    m_eyePosition.getY(),
              /*eye Z*/    m_eyePosition.getZ(),
              /*center X*/ 0.0f,
              /*center Y*/ 0.0f,
              /*center Z*/ 0.0f,
              /*up X*/     upX,
              /*up Y*/     upY,
              /*up Z*/     upZ);
        Matrix.multiplyMM(m_tableMatrix, 0, m_tableMatrix, 16, m_tableMatrix, 32);

        m_ball.updateMatrix(m_ballRadius, m_eyePosition, m_tmpMatrix);
    }

    private void onTouchEventRT(float touchX, float touchY, int frameId)
    {
        /* executed on render thread */
        int touchCapIdx = -1;
        float distance = 0;

        for (int idx = 0; idx < m_cup.length; idx++)
        {
            final Cup cup = m_cup[idx];
            if (cup.isVisible())
            {
                final float x = Vector.getMVX(m_tableMatrix, 0, cup.getX(), cup.getY(), cup.getZ(), 1f);
                final float y = Vector.getMVY(m_tableMatrix, 0, cup.getX(), cup.getY(), cup.getZ(), 1f);
                final float w = Vector.getMVW(m_tableMatrix, 0, 1f, 1f, 1f, 1f);
                final float screenX = ((x/w + 1f) / 2f) * getWidth();
                final float screenY = (getHeight() - ((y/w + 1f) / 2f) * getHeight());

                final float dx = (screenX - touchX);
                final float dy = (screenY - touchY);
                if (Math.sqrt( dx * dx + dy * dy ) <= m_ballRadius)
                {
                    final float d = m_viewDistance - (m_eyePosition.getX()*cup.getX() + m_eyePosition.getY()*cup.getY() + m_eyePosition.getZ()*cup.getZ()) / m_viewDistance;
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

            Cup cup = m_cup[touchCapIdx];
            cup.updateMatrix( cup.getX(), cup.getY(), m_ballRadius*4, m_ballRadius, frameId, m_tmpMatrix );

            boolean found;

            if (touchCapIdx == m_capWithBall)
            {
                setBottomLineText( R.string.you_win, WIN_TEXT_COLOR, GAMBLE_TIMER_FONT_SIZE );
                found = true;
            }
            else
            {
                setBottomLineText( R.string.you_lose, LOSE_TEXT_COLOR, GAMBLE_TIMER_FONT_SIZE );

                cup = m_cup[m_capWithBall];
                cup.updateMatrix( cup.getX(), cup.getY(), m_ballRadius*4, m_ballRadius, frameId, m_tmpMatrix );
                found = false;
            }

            m_ball.updateMatrix(cup.getX(), cup.getY(), m_ballRadius, m_ballRadius, m_eyePosition, m_light, m_tmpMatrix);

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
    private final Vector m_eyePosition;
    private float m_scale;
    private float m_tableWidth;
    private float m_tableHeight;
    private Vector m_light;
    private ShadowObject m_shadowObject;
    private Table m_table;
    private float [] m_tableMatrix;
    private Ball m_ball;
    private float m_ballRadius;
    private Cup[] m_cup;

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
        m_eyePosition = new Vector();
        m_timerManager = new TimerManager();
        m_state = STATE_WATCH;
    }

    public void onSurfaceCreated(GL10 gl, EGLConfig config)
    {
        super.onSurfaceCreated(gl, config);
    }

    public void onSurfaceChanged(GL10 gl, int width, int height)
    {
        /* Looks like a better place to start all than surfaceCreated() */
        super.onSurfaceChanged(gl, width, height);

        final float [] light =
        {
            -width/4, // x
            -width/4, // y
             width*2, // z
                  0f, // w
                  1f, // r
                  1f, // g
                  1f  // b
        };

        m_light = new Vector(light, 0);
        m_shadowObject = ShadowObject.create(width, m_light, m_tmpMatrix);

        try
        {
            final Collider collider = startCollider();
            final Connector connector = new GameConnector(
                    m_serverAddr,
                    getPingConfig(),
                    getDesiredTableHeight(width, height),
                    getDeviceId(),
                    getPlayerName() );
            collider.addConnector(connector);
        }
        catch (final IOException ex)
        {
            Log.e(LOG_TAG, ex.toString(), ex);
        }
    }

    public void onDrawFrame(float [] vpMatrix, Canvas3D canvas3D)
    {
        super.onDrawFrame(vpMatrix, canvas3D);

        if (m_table != null)
        {
            final float [] tmp = m_tmpMatrix;

            if (m_shadowObject != null)
            {
                final int frameBufferId = m_shadowObject.frameBufferId;
                final int mapSize = m_shadowObject.mapSize;
                final int viewWidth = getWidth();
                final int viewHeight = getHeight();

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBufferId);
                GLES20.glViewport(0, 0, mapSize, mapSize);

                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT|GLES20.GL_COLOR_BUFFER_BIT);

                m_ball.drawShadow(m_shadowObject.matrix, 0, tmp);
                for (Cup cup : m_cup)
                    cup.drawShadow(m_shadowObject.matrix, 0, tmp);

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glViewport(0, 0, viewWidth, viewHeight);
            }

            m_table.draw(m_tableMatrix, m_eyePosition, m_light, m_shadowObject, tmp, 0);
            m_ball.draw(m_tableMatrix, m_light, tmp);
            for (Cup cup : m_cup)
                cup.draw(m_tableMatrix, m_eyePosition, m_light, m_shadowObject, tmp);
        }

        if (m_bottomLineString != null)
        {
            canvas3D.drawText(
                    vpMatrix,
                    m_bottomLineString,
                    m_bottomLineX,
                    m_bottomLineY,
                    1f,
                    m_bottomLineStringFontSize,
                    m_bottomLineStringColor,
                    Paint.Align.CENTER );
        }
    }

    public void onConnected(GameClientSession session, short virtualTableHeight, short virtualBallRadius)
    {
        final float viewWidth = getWidth();
        final float viewHeight = getHeight();

        m_session = session;
        m_scale = (viewWidth / VIRTUAL_TABLE_WIDTH);
        m_tableWidth = viewWidth;
        m_tableHeight = (virtualTableHeight * m_scale);
        m_ballRadius = (virtualBallRadius * m_scale);
        final Bitmap statusLine = createStatusLine( -1, m_serverPlayerName );

        Log.d( LOG_TAG, "onConnected: tableSize=(" + m_tableWidth + ", " + m_tableHeight + ")" );

        m_viewDistance = (viewWidth * 3.5f);

        final float bottomHeight2 = (getBottomReservedHeight() / 2f);
        m_bottomLineX = (viewWidth / 2f);
        m_bottomLineY = (viewHeight/2f - viewWidth/2f - bottomHeight2);
        if (m_bottomLineY < bottomHeight2)
            m_bottomLineY = bottomHeight2;

        m_angleX = (float) (Math.PI / 4f);
        m_angleZ = 0f;

        final float angleXd = (float) (m_angleX * 180f / Math.PI);
        final float angleZd = (float) (m_angleZ * 180f / Math.PI);
        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                final Context context = getContext();
                try
                {
                    HashSet<String> macro;
                    if (m_shadowObject == null)
                        macro = null;
                    else
                    {
                        macro = new HashSet<String>();
                        macro.add("RENDER_SHADOWS");
                    }

                    m_renderThreadId = Thread.currentThread().getId();
                    m_table = new Table(context, macro);
                    m_ball = new Ball(context, BALL_COLOR);

                    final ModelCup modelCup = new ModelCup(context, CAP_STRIPES, macro);
                    m_cup = new Cup[3];
                    for (int idx = 0; idx< m_cup.length; idx++)
                        m_cup[idx] = new Cup(modelCup);
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

    public void dragBallCT(float virtualX, float virtualY, final float rm)
    {
        /* ball position is relative to the center of the table,
         * but second player look at the table from opposite side,
         * so we should invert position.
         */
        final float x = -(virtualX * m_scale);
        final float y = -(virtualY * m_scale);
        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                m_ball.updateMatrix(x, y, m_ballRadius*rm, m_ballRadius, m_eyePosition, m_light, m_tmpMatrix);
                return false;
            }
        } );
    }

    public void putBallCT(float virtualX, float virtualY)
    {
        dragBallCT(virtualX, virtualY, 1f);
        m_activity.playSound_BallPut();
    }

    public void removeBallCT()
    {
        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                m_ball.setVisible(false);
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
                return m_cup[cid].updateMatrix( x, y, z, m_ballRadius, frameId, m_tmpMatrix );
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
                m_cup[fid].setVisible( false );
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
