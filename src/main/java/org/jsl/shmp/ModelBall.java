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

public class ModelBall
{
    private final int m_color;
    private final int m_programId;
    private final int m_matrixLocation;
    private final int m_positionLocation;
    private final int m_colorLocation;
    private final int m_lightDirectionLocation;
    private final int m_lightColorLocation;
    private final FloatBuffer m_vertexData;

    public ModelBall( Context context, int color ) throws IOException
    {
        m_color = color;
        m_programId = Canvas3D.createProgram( context, R.raw.ball_vs, R.raw.ball_fs );

        m_matrixLocation = GLES20.glGetUniformLocation( m_programId, "u_m4Matrix" );
        if (m_matrixLocation < 0)
            throw new IOException();

        m_positionLocation = GLES20.glGetAttribLocation( m_programId, "a_v4Position" );
        if (m_positionLocation < 0)
            throw new IOException();

        m_colorLocation = GLES20.glGetUniformLocation( m_programId, "u_v3Color" );
        if (m_colorLocation < 0)
            throw new IOException();

        m_lightDirectionLocation = GLES20.glGetUniformLocation( m_programId, "u_v3LightDirection" );
        if (m_lightDirectionLocation < 0)
            throw new IOException();

        m_lightColorLocation = GLES20.glGetUniformLocation( m_programId, "u_v3LightColor" );
        if (m_lightColorLocation < 0)
            throw new IOException();

        final int bufferCapacity = (Float.SIZE / Byte.SIZE) * 12;
        final ByteBuffer byteBuffer = ByteBuffer.allocateDirect( bufferCapacity );
        byteBuffer.order( ByteOrder.nativeOrder() );
        m_vertexData = byteBuffer.asFloatBuffer();

        final float [] vertices =
        {
            -1f,  1f,
            -1f, -1f,
            1f,  1f,
            1f, -1f
        };
        m_vertexData.put( vertices );
    }

    public void draw(
            float [] mvpMatrix, int mvpMatrixOffset,
            float [] lightDirection, int lightDirectionOffset,
            float [] lightColor, int lightColorOffset )
    {
        GLES20.glUseProgram( m_programId );

        GLES20.glUniformMatrix4fv( m_matrixLocation, 1, false, mvpMatrix, mvpMatrixOffset );

        m_vertexData.position(0);
        GLES20.glVertexAttribPointer( m_positionLocation, 2, GLES20.GL_FLOAT, false, 0, m_vertexData );
        GLES20.glEnableVertexAttribArray( m_positionLocation );

        final float red = ((float) Color.red(m_color)) / 255f;
        final float green = ((float)Color.green(m_color)) / 255f;
        final float blue = ((float)Color.blue(m_color)) / 255f;
        GLES20.glUniform3f( m_colorLocation, red, green, blue );

        GLES20.glUniform3fv( m_lightDirectionLocation, 1, lightDirection, lightDirectionOffset );
        GLES20.glUniform3fv( m_lightColorLocation, 1, lightColor, lightColorOffset );

        GLES20.glDrawArrays( GLES20.GL_TRIANGLE_STRIP, 0, 4 );
        GLES20.glUseProgram( 0 );
    }
}
