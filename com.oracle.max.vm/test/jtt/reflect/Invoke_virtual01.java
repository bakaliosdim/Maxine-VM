/*
 * Copyright (c) 2007, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jtt.reflect;

import java.lang.reflect.*;

/*
 * @Harness: java
 * @Runs: 1 = 55
 */
public class Invoke_virtual01 {

    static final HelperTest helper = new HelperTest(55);

    public static int test(int input) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        if (input == 1) {
            final Method m =  HelperTest.class.getDeclaredMethod("getInt");
            Object o = m.invoke(helper);
            return ((Integer) o).intValue();
        }
        return 0;
    }

    public static class HelperTest {

        private int intField;

        public int getInt() {
            return intField;
        }

        public HelperTest(int i) {
            intField = i;
        }
    }

}
