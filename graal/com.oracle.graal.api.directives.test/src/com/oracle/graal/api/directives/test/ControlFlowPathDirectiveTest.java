/*
 * Copyright (c) 2016, 2016, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.api.directives.test;

import java.io.IOException;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.graal.api.directives.GraalDirectives;
import com.oracle.graal.compiler.common.GraalOptions;
import com.oracle.graal.compiler.test.GraalCompilerTest;
import com.oracle.graal.options.OptionValue;
import com.oracle.graal.options.OptionValue.OverrideScope;

import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public class ControlFlowPathDirectiveTest extends GraalCompilerTest {

    private TinyInstrumentor instrumentor;

    public ControlFlowPathDirectiveTest() {
        HotSpotResolvedJavaMethod method = (HotSpotResolvedJavaMethod) getResolvedJavaMethod(ClassA.class, "notInlinedMethod");
        method.setNotInlineable();

        try {
            instrumentor = new TinyInstrumentor(ControlFlowPathDirectiveTest.class, "instrumentation");
        } catch (IOException e) {
            Assert.fail("unable to initialize the instrumentor: " + e);
        }
    }

    public static class ClassA {
        // This method should be marked as not inlineable
        public void notInlinedMethod() {
        }
    }

    public static int path;

    public void resetPath() {
        path = -1;
    }

    static void instrumentation() {
        GraalDirectives.instrumentationBeginForPredecessor();
        path = GraalDirectives.controlFlowPath();
        GraalDirectives.instrumentationEnd();
    }

    public static void allocationSnippet() {
        ClassA a = new ClassA();

        a.notInlinedMethod();
    }

    @Test
    public void testAllocationControlFlow() {
        try (OverrideScope s = OptionValue.override(GraalOptions.UseGraalInstrumentation, true)) {
            Class<?> clazz = instrumentor.instrument(ControlFlowPathDirectiveTest.class, "allocationSnippet", Opcodes.NEW);
            ResolvedJavaMethod method = getResolvedJavaMethod(clazz, "allocationSnippet");
            executeExpected(method, null); // ensure the method is fully resolved
            resetPath();
            // The instrumentation targets the allocation and GraalDirectives.controlFlowPath() will
            // indicate whether the allocation occurs in TLAB (0) or in the common heap space (1).
            InstalledCode code = getCode(method);
            code.executeVarargs();
            Assert.assertTrue("control flow path for allocation should be assigned", path >= 0);
        } catch (Throwable e) {
            Assert.fail("Unexpected exception: " + e);
        }
    }

    public static final Object lock = new Object();

    public static void lockSnippet() {
        synchronized (lock) {
            ClassA a = new ClassA();
            a.notInlinedMethod();
        }
    }

    @Test
    public void testLockControlFlow() {
        try (OverrideScope s = OptionValue.override(GraalOptions.UseGraalInstrumentation, true)) {
            Class<?> clazz = instrumentor.instrument(ControlFlowPathDirectiveTest.class, "lockSnippet", Opcodes.MONITORENTER);
            ResolvedJavaMethod method = getResolvedJavaMethod(clazz, "lockSnippet");
            executeExpected(method, null); // ensure the method is fully resolved
            resetPath();
            // The instrumentation targets the monitorenter and GraalDirectives.controlFlowPath()
            // will indicate the lock type [0..5].
            InstalledCode code = getCode(method);
            code.executeVarargs();
            Assert.assertTrue("control flow path for monitorenter should be assigned", path >= 0);
        } catch (Throwable e) {
            Assert.fail("Unexpected exception: " + e);
        }
    }

}
