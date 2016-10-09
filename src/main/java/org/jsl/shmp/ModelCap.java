/*
 * Copyright (C) 2016 Sergey Zubarev, info@js-labs.org
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
import android.graphics.Color;
import android.opengl.GLES20;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class ModelCap implements Model
{
    private final int m_programId;
    private final int m_matrixLocation;
    private final int m_lightPositionLocation;
    private final int m_colorLocation;
    private final int m_positionLocation;
    private final int m_normalLocation;
    private final FloatBuffer m_vertexData;
    private final int m_stripes;
    private final int m_bottomOffs;

    public ModelCap( Context context, int stripes ) throws IOException
    {
        m_programId = Canvas3D.createProgram( context, R.raw.cap_vs, R.raw.cap_fs );

        m_matrixLocation = GLES20.glGetUniformLocation( m_programId, "u_m4Matrix" );
        if (m_matrixLocation < 0)
            throw new IOException();

        m_lightPositionLocation = GLES20.glGetUniformLocation( m_programId, "u_v3LightPosition" );
        if (m_lightPositionLocation < 0)
            throw new IOException();

        m_colorLocation = GLES20.glGetUniformLocation( m_programId, "u_v4Color" );
        if (m_colorLocation < 0)
            throw new IOException();

        m_positionLocation = GLES20.glGetAttribLocation( m_programId, "a_v4Position" );
        if (m_positionLocation < 0)
            throw new IOException();

        m_normalLocation = GLES20.glGetAttribLocation( m_programId, "a_v3Normal" );
        if (m_normalLocation < 0)
            throw new IOException();

        m_stripes = stripes;
        m_bottomOffs = ((stripes + 1) * 2 * 6);
        final int bufferCapacity = (Float.SIZE / Byte.SIZE) * (m_bottomOffs + /*bottom*/(stripes+2)*6);
        final ByteBuffer byteBuffer = ByteBuffer.allocateDirect( bufferCapacity );
        byteBuffer.order( ByteOrder.nativeOrder() );
        m_vertexData = byteBuffer.asFloatBuffer();

        final float neckRadius = 1f;
        final float [] tmp = new float[6];
        final float height =  (neckRadius / 6f * 10f);
        final float bottomRadius = (neckRadius / 6f * 4.5f);

        final float pitch = (float) (Math.PI * 2f / stripes);
        final float pitchCos = (float) Math.cos( pitch );
        final float pitchSin = (float) Math.sin( pitch );

        final float [] bottom = new float[3];
        final float [] neck = new float[3];
        final float [] n1 = new float[3];
        final float [] n2 = new float[3];
        final float [] bottomNormal = { 0f, 0f, 1f }; /* bottom normal */

        bottom[0] = bottomRadius;
        bottom[1] = 0f;
        bottom[2] = height;

        neck[0] = (float) (neckRadius * Math.cos(pitch / 2f));
        neck[1] = (float) (neckRadius * Math.sin(pitch / 2f));
        neck[2] = 0f;

        /*  0 p1 -  3 p1n
         *  6 p2 -  9 p2n
         * 12 p3 - 15 p3n
         */
        m_vertexData.position(0);
        m_vertexData.put( bottom );

        m_vertexData.position(6);
        m_vertexData.put( neck );

        m_vertexData.position( m_bottomOffs );
        m_vertexData.put( /*x*/0f );
        m_vertexData.put( /*y*/0f );
        m_vertexData.put( /*z*/height );
        m_vertexData.put( bottomNormal );
        m_vertexData.put( bottom );
        m_vertexData.put( bottomNormal );

        Vector.rotateAroundZ( bottom, 0, pitchCos, pitchSin );
        Vector.rotateAroundZ( neck, 0, pitchCos, pitchSin );

        m_vertexData.position(12);
        m_vertexData.put( bottom );

        m_vertexData.position(18);
        m_vertexData.put( neck );

        m_vertexData.position( m_bottomOffs + 6*2 );
        m_vertexData.put( bottom );
        m_vertexData.put( bottomNormal );
        int bottomPos = m_vertexData.position();

        Vector.rotateAroundZ( bottom, 0, pitchCos, pitchSin );
        Vector.rotateAroundZ( neck, 0, pitchCos, pitchSin );

        /* Lighting requires face normal,
         * we could calculate it once for one face and then rotate.
         */
        tmp[0] = m_vertexData.get(0) - m_vertexData.get(6);
        tmp[1] = m_vertexData.get(1) - m_vertexData.get(7);
        tmp[2] = m_vertexData.get(2) - m_vertexData.get(8);

        tmp[3] = m_vertexData.get(12) - m_vertexData.get(6);
        tmp[4] = m_vertexData.get(13) - m_vertexData.get(7);
        tmp[5] = m_vertexData.get(14) - m_vertexData.get(8);

        Vector.crossProduct( n1, 0, tmp, 3, tmp, 0 );
        Vector.normalize( n1, 0 );

        tmp[0] = m_vertexData.get(18) - m_vertexData.get(12);
        tmp[1] = m_vertexData.get(19) - m_vertexData.get(13);
        tmp[2] = m_vertexData.get(20) - m_vertexData.get(14);

        tmp[3] = m_vertexData.get(6) - m_vertexData.get(12);
        tmp[4] = m_vertexData.get(7) - m_vertexData.get(13);
        tmp[5] = m_vertexData.get(8) - m_vertexData.get(14);

        Vector.crossProduct( n2, 0, tmp, 3, tmp, 0 );
        Vector.normalize( n2, 0 );

        m_vertexData.position(3);
        m_vertexData.put( n1 );
        m_vertexData.position(9);
        m_vertexData.put( n2 );

        Vector.rotateAroundZ( n1, 0, pitchCos, pitchSin );
        Vector.rotateAroundZ( n2, 0, pitchCos, pitchSin );

        m_vertexData.position(15);
        m_vertexData.put( n1 );
        m_vertexData.position(21);
        m_vertexData.put( n2 );

        Vector.rotateAroundZ( n1, 0, pitchCos, pitchSin );
        Vector.rotateAroundZ( n2, 0, pitchCos, pitchSin );

        for (int idx=stripes-1; idx>0; idx--)
        {
            m_vertexData.put( bottom );
            m_vertexData.put( n1 );
            m_vertexData.put( neck );
            m_vertexData.put( n2 );

            final int pos = m_vertexData.position();
            m_vertexData.position( bottomPos );
            m_vertexData.put( bottom );
            m_vertexData.put( bottomNormal );
            bottomPos = m_vertexData.position();
            m_vertexData.position( pos );

            Vector.rotateAroundZ( bottom, 0, pitchCos, pitchSin );
            Vector.rotateAroundZ( n1, 0, pitchCos, pitchSin );
            Vector.rotateAroundZ( neck, 0, pitchCos, pitchSin );
            Vector.rotateAroundZ( n2, 0, pitchCos, pitchSin );
        }
    }

    public void draw( float [] mvpMatrix, int mvpMatrixOffset, float [] lightPosition, int lightPositionOffset )
    {
        final int color = Color.BLUE;

        GLES20.glUseProgram( m_programId );

        GLES20.glUniformMatrix4fv( m_matrixLocation, 1, false, mvpMatrix, mvpMatrixOffset );
        GLES20.glUniform3fv( m_lightPositionLocation, 1, lightPosition, lightPositionOffset );

        final float red = ((float) Color.red(color)) / 255f;
        final float green = ((float)Color.green(color)) / 255f;
        final float blue = ((float)Color.blue(color)) / 255f;
        GLES20.glUniform4f( m_colorLocation, red, green, blue, 1.0f );

        final int STRIDE = (3 + 3) * (Float.SIZE / Byte.SIZE);

        /* Draw side */

        m_vertexData.position(0);
        GLES20.glVertexAttribPointer( m_positionLocation, 3, GLES20.GL_FLOAT, false, STRIDE, m_vertexData );
        GLES20.glEnableVertexAttribArray( m_positionLocation );

        m_vertexData.position(3);
        GLES20.glVertexAttribPointer( m_normalLocation, 3, GLES20.GL_FLOAT, false, STRIDE, m_vertexData );
        GLES20.glEnableVertexAttribArray( m_normalLocation );

        GLES20.glDrawArrays( GLES20.GL_TRIANGLE_STRIP, 0, m_stripes*2+2 );

        /* Draw bottom */

        m_vertexData.position( m_bottomOffs );
        GLES20.glVertexAttribPointer( m_positionLocation, 3, GLES20.GL_FLOAT, false, STRIDE, m_vertexData );
        GLES20.glEnableVertexAttribArray( m_positionLocation );

        m_vertexData.position( m_bottomOffs + 3 );
        GLES20.glVertexAttribPointer( m_normalLocation, 3, GLES20.GL_FLOAT, false, STRIDE, m_vertexData );
        GLES20.glEnableVertexAttribArray( m_normalLocation );

        GLES20.glDrawArrays( GLES20.GL_TRIANGLE_FAN, 0, m_stripes+2 );

        GLES20.glUseProgram( 0 );
    }
}
