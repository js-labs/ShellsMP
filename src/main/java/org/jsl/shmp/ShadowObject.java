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

import android.opengl.GLES20;
import android.opengl.Matrix;

class ShadowObject
{
    public static final float [] MATRIX_BIAS =
    {
        0.5f, 0.0f, 0.0f, 0.0f, // 0.5  0.0  0.0  0.5
        0.0f, 0.5f, 0.0f, 0.0f, // 0.0  0.5  0.0  0.5
        0.0f, 0.0f, 0.5f, 0.0f, // 0.0  0.0  0.5  0.5
        0.5f, 0.5f, 0.5f, 1.0f  // 0.0  0.0  0.0  1.0
    };

    public final int frameBufferId;
    public final int textureId;
    public final int mapSize;
    public final float [] matrix;

    private ShadowObject(int frameBufferId, int textureId, int mapSize, float [] matrix)
    {
        this.frameBufferId = frameBufferId;
        this.textureId = textureId;
        this.mapSize = mapSize;
        this.matrix = matrix;
    }

    public static ShadowObject create(int shadowMapSize, Vector light, float [] tmp)
    {
        final String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        if (extensions.contains("OES_depth_texture"))
        {
            final int[] ids = new int[1];

            GLES20.glGenTextures(1, ids, 0);
            final int textureId = ids[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);

            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_DEPTH_COMPONENT,
                    shadowMapSize, // width
                    shadowMapSize, // height
                    0, GLES20.GL_DEPTH_COMPONENT, GLES20.GL_UNSIGNED_INT, null);

            GLES20.glGenFramebuffers(1, ids, 0);
            final int frameBufferId = ids[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBufferId);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_TEXTURE_2D, textureId, 0);

            final int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            if (status == GLES20.GL_FRAMEBUFFER_COMPLETE)
            {
                Vector.set(tmp, 0, 0f, 0f, 1f, 0f);
                Vector.crossProduct(tmp, 4, light.v, 0, tmp, 0);
                Vector.crossProduct(tmp, 0, tmp, 4, light.v, 0);
                Vector.normalize(tmp, 0);

                final float ll = Vector.length(light.v, 0);

                Matrix.frustumM(tmp, 4,
                        -shadowMapSize / 2,      // left
                         shadowMapSize / 2,      // right
                        -shadowMapSize / 2,      // bottom
                         shadowMapSize / 2,      // top
                        ll - shadowMapSize / 2,  // near
                        ll + shadowMapSize / 2); // far

                Matrix.setLookAtM(tmp, 20,
                        light.getX(), // light X
                        light.getY(), // light Y
                        light.getZ(), // light Z
                        0.0f,         // center X
                        0.0f,         // center Y
                        0.0f,         // center Z
                        tmp[0],       // up X
                        tmp[1],       // up Y
                        tmp[2]);      // up Z

                final float [] matrix = new float[16 * 2];
                Matrix.multiplyMM(matrix, 0, tmp, 4, tmp, 20);
                Matrix.multiplyMM(matrix, 16, MATRIX_BIAS, 0, matrix, 0);

                return new ShadowObject(frameBufferId, textureId, shadowMapSize, matrix);
            }
        }
        return null;
    }
}
