/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.extended;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;

public class BoxingMethodPool {

    private final Set<RiMethod> specialMethods = new HashSet<>();
    private final RiRuntime runtime;
    private final RiResolvedMethod[] boxingMethods = new RiResolvedMethod[Kind.values().length];
    private final RiResolvedMethod[] unboxingMethods = new RiResolvedMethod[Kind.values().length];
    private final RiResolvedField[] boxFields = new RiResolvedField[Kind.values().length];

    public BoxingMethodPool(RiRuntime runtime) {
        this.runtime = runtime;
        initialize();
    }

    private void initialize() {
        try {
            initialize(Kind.Boolean, Boolean.class, "booleanValue");
            initialize(Kind.Byte, Byte.class, "byteValue");
            initialize(Kind.Char, Character.class, "charValue");
            initialize(Kind.Short, Short.class, "shortValue");
            initialize(Kind.Int, Integer.class, "intValue");
            initialize(Kind.Long, Long.class, "longValue");
            initialize(Kind.Float, Float.class, "floatValue");
            initialize(Kind.Double, Double.class, "doubleValue");
        } catch (SecurityException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private void initialize(Kind kind, Class<?> type, String unboxMethod) throws SecurityException, NoSuchMethodException {

        // Get boxing method from runtime.
        RiResolvedMethod boxingMethod = runtime.getRiMethod(type.getDeclaredMethod("valueOf", kind.toJavaClass()));
        specialMethods.add(boxingMethod);
        boxingMethods[kind.ordinal()] = boxingMethod;

        // Get unboxing method from runtime.
        RiResolvedMethod unboxingMethod = runtime.getRiMethod(type.getDeclaredMethod(unboxMethod));
        unboxingMethods[kind.ordinal()] = unboxingMethod;
        specialMethods.add(unboxingMethod);

        // Get the field that contains the boxed value.
        RiResolvedField[] fields = runtime.getType(type).declaredFields();
        RiResolvedField boxField = fields[0];
        assert fields.length == 1 && boxField.kind() == kind;
        boxFields[kind.ordinal()] = boxField;
    }

    public boolean isSpecialMethod(RiResolvedMethod method) {
        return specialMethods.contains(method);
    }

    public boolean isBoxingMethod(RiResolvedMethod method) {
        return isSpecialMethod(method) && method.signature().returnKind() == Kind.Object;
    }

    public boolean isUnboxingMethod(RiResolvedMethod method) {
        return isSpecialMethod(method) && method.signature().returnKind() != Kind.Object;
    }

    public RiResolvedMethod getBoxingMethod(Kind kind) {
        return boxingMethods[kind.ordinal()];
    }

    public RiResolvedMethod getUnboxingMethod(Kind kind) {
        return unboxingMethods[kind.ordinal()];
    }

    public RiResolvedField getBoxField(Kind kind) {
        return boxFields[kind.ordinal()];
    }
}
