/*
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.pointsto;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.java.MethodCallTargetNode;
import org.graalvm.compiler.options.OptionValues;

import com.oracle.graal.pointsto.api.PointstoOptions;
import com.oracle.graal.pointsto.flow.AbstractVirtualInvokeTypeFlow;
import com.oracle.graal.pointsto.flow.ActualReturnTypeFlow;
import com.oracle.graal.pointsto.flow.MethodFlowsGraph;
import com.oracle.graal.pointsto.flow.MethodTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.flow.context.AnalysisContext;
import com.oracle.graal.pointsto.flow.context.BytecodeLocation;
import com.oracle.graal.pointsto.flow.context.bytecode.BytecodeAnalysisContextPolicy;
import com.oracle.graal.pointsto.flow.context.object.AllocationContextSensitiveObject;
import com.oracle.graal.pointsto.flow.context.object.AnalysisObject;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.graal.pointsto.typestate.TypeState;
import com.oracle.graal.pointsto.typestate.TypeState.TypesObjectsIterator;
import com.oracle.graal.pointsto.typestore.ArrayElementsTypeStore;
import com.oracle.graal.pointsto.typestore.FieldTypeStore;
import com.oracle.graal.pointsto.typestore.SplitArrayElementsTypeStore;
import com.oracle.graal.pointsto.typestore.SplitFieldTypeStore;
import com.oracle.graal.pointsto.typestore.UnifiedArrayElementsTypeStore;
import com.oracle.graal.pointsto.typestore.UnifiedFieldTypeStore;

import jdk.vm.ci.meta.JavaConstant;

public class BytecodeSensitiveAnalysisPolicy extends AnalysisPolicy {

    private BytecodeAnalysisContextPolicy contextPolicy;

    public BytecodeSensitiveAnalysisPolicy(OptionValues options) {
        super(options);
        this.contextPolicy = new BytecodeAnalysisContextPolicy();
    }

    @Override
    public BytecodeAnalysisContextPolicy contextPolicy() {
        return contextPolicy;
    }

    @Override
    public boolean needsConstantCache() {
        return true;
    }

    @Override
    public boolean isSummaryObject(AnalysisObject object) {
        /* Context insensitive objects summarize context sensitive objects of the same type. */
        return object.isContextInsensitiveObject();
    }

    @Override
    public boolean isMergingEnabled() {
        // the context sensitive analysis relies on proper signal of merging
        return true;
    }

    @Override
    public void noteMerge(BigBang bb, TypeState t) {
        t.noteMerge(bb);
    }

    @Override
    public void noteMerge(BigBang bb, AnalysisObject... a) {
        for (AnalysisObject o : a) {
            o.noteMerge(bb);
        }
    }

    @Override
    public boolean isContextSensitiveAllocation(BigBang bb, AnalysisType type, AnalysisContext allocationContext) {
        return bb.trackConcreteAnalysisObjects(type);
    }

    @Override
    public AnalysisObject createHeapObject(BigBang bb, AnalysisType type, BytecodeLocation allocationSite, AnalysisContext allocationContext) {
        assert PointstoOptions.AllocationSiteSensitiveHeap.getValue(options);
        if (isContextSensitiveAllocation(bb, type, allocationContext)) {
            return new AllocationContextSensitiveObject(bb, type, allocationSite, allocationContext);
        } else {
            return type.getContextInsensitiveAnalysisObject();
        }
    }

    @Override
    public AnalysisObject createConstantObject(BigBang bb, JavaConstant constant, AnalysisType exactType) {
        /* Get the analysis object wrapping the JavaConstant. */
        if (bb.trackConcreteAnalysisObjects(exactType)) {
            return exactType.getCachedConstantObject(bb, constant);
        } else {
            return exactType.getContextInsensitiveAnalysisObject();
        }
    }

    @Override
    public BytecodeLocation createAllocationSite(BigBang bb, int bci, AnalysisMethod method) {
        return BytecodeLocation.create(bci, method);
    }

    @Override
    public FieldTypeStore createFieldTypeStore(AnalysisObject object, AnalysisField field, AnalysisUniverse universe) {
        assert PointstoOptions.AllocationSiteSensitiveHeap.getValue(options);
        if (object.isContextInsensitiveObject()) {
            return new SplitFieldTypeStore(field, object);
        } else {
            return new UnifiedFieldTypeStore(field, object);
        }
    }

    @Override
    public ArrayElementsTypeStore createArrayElementsTypeStore(AnalysisObject object, AnalysisUniverse universe) {
        assert PointstoOptions.AllocationSiteSensitiveHeap.getValue(options);
        if (object.type().isArray()) {
            if (object.isContextInsensitiveObject()) {
                return new SplitArrayElementsTypeStore(object);
            } else {
                return new UnifiedArrayElementsTypeStore(object);
            }
        } else {
            return null;
        }
    }

    @Override
    public AbstractVirtualInvokeTypeFlow createVirtualInvokeTypeFlow(Invoke invoke, MethodCallTargetNode target,
                    TypeFlow<?>[] actualParameters, ActualReturnTypeFlow actualReturn, BytecodeLocation location) {
        return new BytecodeSensitiveVirtualInvokeTypeFlow(invoke, target, actualParameters, actualReturn, location);
    }

    /**
     * Bytecode context sensitive implementation of the invoke virtual type flow update.
     *
     * TODO Can we merge the slow path (i.e., this class) and fast path (i.e., the default, context
     * insensitive virtual invoke implementation) to be able to fall back to fast path when context
     * sensitivity is disabled or reaches budget threshold?
     */
    private static class BytecodeSensitiveVirtualInvokeTypeFlow extends AbstractVirtualInvokeTypeFlow {

        /*
         * Remember all the callee clones that were already linked in each context at this
         * invocation site to avoid redundant relinking. MethodFlows is unique for each method type
         * flow and context combination.
         */
        private final ConcurrentMap<MethodFlowsGraph, Object> calleesFlows;
        private final AnalysisContext callerContext;

        protected BytecodeSensitiveVirtualInvokeTypeFlow(Invoke invoke, MethodCallTargetNode target,
                        TypeFlow<?>[] actualParameters, ActualReturnTypeFlow actualReturn, BytecodeLocation location) {
            super(invoke, target, actualParameters, actualReturn, location);
            calleesFlows = null;
            callerContext = null;
        }

        protected BytecodeSensitiveVirtualInvokeTypeFlow(BigBang bb, MethodFlowsGraph methodFlows, BytecodeSensitiveVirtualInvokeTypeFlow original) {
            super(bb, methodFlows, original);
            calleesFlows = new ConcurrentHashMap<>(4, 0.75f, 1);
            callerContext = methodFlows.context();
        }

        @Override
        public TypeFlow<MethodCallTargetNode> copy(BigBang bb, MethodFlowsGraph methodFlows) {
            return new BytecodeSensitiveVirtualInvokeTypeFlow(bb, methodFlows, this);
        }

        @Override
        public void onObservedUpdate(BigBang bb) {
            assert this.isClone();

            /*
             * Capture the current receiver state before the update. The type state objects are
             * immutable and a later call to getState() can yield a different value.
             */
            TypeState receiverState = getReceiver().getState();
            if (receiverState.isUnknown()) {
                bb.reportIllegalUnknownUse(graphRef.getMethod(), source, "Illegal: Invoke on UnknownTypeState objects. Invoke: " + this);
                return;
            }

            /* Use the tandem types - objects iterator. */
            TypesObjectsIterator toi = receiverState.getTypesObjectsIterator();
            while (toi.hasNextType()) {
                AnalysisType type = toi.nextType();

                AnalysisMethod method = type.resolveConcreteMethod(getTargetMethod(), getSource().invoke().getContextType());
                if (method == null || Modifier.isAbstract(method.getModifiers())) {
                    /*
                     * Type states can be conservative, i.e., we can have receiver types that do not
                     * implement the method. Just ignore such types.
                     */
                    continue;
                }

                assert !Modifier.isAbstract(method.getModifiers());

                MethodTypeFlow callee = method.getTypeFlow();

                while (toi.hasNextObject(type)) {
                    AnalysisObject actualReceiverObject = toi.nextObject(type);

                    // get the context based on the actualReceiverObject
                    AnalysisContext calleeContext = bb.contextPolicy().calleeContext(bb, actualReceiverObject, callerContext, callee);

                    MethodFlowsGraph calleeFlows = callee.addContext(bb, calleeContext, this);

                    if (calleesFlows.put(calleeFlows, Boolean.TRUE) == null) {
                        /* register the analysis method as a callee for this invoke */
                        addCallee(calleeFlows.getMethod());
                        /* linkCallee() does not link the receiver object. */
                        linkCallee(bb, false, calleeFlows);
                    }

                    updateReceiver(bb, calleeFlows, actualReceiverObject);
                }

            }
        }

        @Override
        public Collection<MethodFlowsGraph> getCalleesFlows(BigBang bb) {
            return new ArrayList<>(calleesFlows.keySet());
        }
    }

}
