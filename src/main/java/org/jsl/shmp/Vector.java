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

public class Vector
{
    public static void set( float [] v, int offset, float x, float y, float z )
    {
        v[offset] = x;
        v[offset+1] = y;
        v[offset+2] = z;
    }

    public static float length( float [] v, int offset )
    {
        final float x = v[offset];
        final float y = v[offset + 1];
        final float z = v[offset + 2];
        return (float) Math.sqrt(x*x + y*y + z*z);
    }

    public static void crossProduct(
            float [] result, int resultOffset, float [] lv, int lvOffset, float [] rv, int rvOffset )
    {
        /* ay*bz - az*by, az*bx - ax*bz, ax*by - ay*bx */
        result[resultOffset]   = lv[lvOffset+1] * rv[rvOffset+2] - lv[lvOffset+2] * rv[rvOffset+1];
        result[resultOffset+1] = lv[lvOffset+2] * rv[rvOffset]   - lv[lvOffset]   * rv[rvOffset+2];
        result[resultOffset+2] = lv[lvOffset]   * rv[rvOffset+1] - lv[lvOffset+1] * rv[rvOffset];
    }

    public static void normalize( float [] v, int offset )
    {
        final float length = length( v, offset );
        v[offset]   /= length;
        v[offset+1] /= length;
        v[offset+2] /= length;
    }

    public static void rotateAroundZ( float [] v, int vOffset, float cos, float sin )
    {
        final float x = v[vOffset];
        final float y = v[vOffset+1];
        v[vOffset]   = x*cos - y*sin;
        v[vOffset+1] = x*sin + y*cos;
    }
}
