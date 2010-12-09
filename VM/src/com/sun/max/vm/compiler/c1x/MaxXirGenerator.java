/*
 * Copyright (c) 2009 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.vm.compiler.c1x;

import static com.sun.max.platform.Platform.*;
import static com.sun.max.vm.VMConfiguration.*;
import static com.sun.max.vm.layout.Layout.*;
import static java.lang.reflect.Modifier.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

import com.sun.c1x.*;
import com.sun.c1x.target.amd64.*;
import com.sun.cri.ci.CiAddress.Scale;
import com.sun.cri.ci.*;
import com.sun.cri.ri.*;
import com.sun.cri.ri.RiType.Representation;
import com.sun.cri.xir.*;
import com.sun.cri.xir.CiXirAssembler.XirLabel;
import com.sun.cri.xir.CiXirAssembler.XirOperand;
import com.sun.cri.xir.CiXirAssembler.XirParameter;
import com.sun.max.*;
import com.sun.max.annotate.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.classfile.constant.UnresolvedType.ByAccessingClass;
import com.sun.max.vm.classfile.constant.UnresolvedType.InPool;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.snippet.*;
import com.sun.max.vm.compiler.snippet.ResolutionSnippet.ResolveArrayClass;
import com.sun.max.vm.compiler.snippet.ResolutionSnippet.ResolveClass;
import com.sun.max.vm.compiler.snippet.ResolutionSnippet.ResolveClassForNew;
import com.sun.max.vm.compiler.snippet.ResolutionSnippet.ResolveInstanceFieldForReading;
import com.sun.max.vm.compiler.snippet.ResolutionSnippet.ResolveInstanceFieldForWriting;
import com.sun.max.vm.compiler.snippet.ResolutionSnippet.ResolveInterfaceMethod;
import com.sun.max.vm.compiler.snippet.ResolutionSnippet.ResolveStaticFieldForReading;
import com.sun.max.vm.compiler.snippet.ResolutionSnippet.ResolveStaticFieldForWriting;
import com.sun.max.vm.compiler.snippet.ResolutionSnippet.ResolveStaticMethod;
import com.sun.max.vm.compiler.snippet.ResolutionSnippet.ResolveVirtualMethod;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.layout.*;
import com.sun.max.vm.object.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.thread.*;
import com.sun.max.vm.type.*;

/**
 * This class implements the VM interface for generating XIR snippets that express
 * the low-level implementation of each bytecode for C1X compilation.
 *
 * @author Ben L. Titzer
 * @author Thomas Wuerthinger
 */
public class MaxXirGenerator implements RiXirGenerator {

    private static final int SMALL_MULTIANEWARRAY_RANK = 2;

    // (tw) TODO: Up this to 255 / make a loop in the template
    private static final int MAX_MULTIANEWARRAY_RANK = 6;

    static class XirPair {
        final XirTemplate resolved;
        final XirTemplate unresolved;

        XirPair(XirTemplate resolved, XirTemplate unresolved) {
            this.resolved = resolved;
            this.unresolved = unresolved;
        }
    }

    static class NewInstanceTemplates extends XirPair {
        final XirTemplate resolvedHybrid;

        public NewInstanceTemplates(XirTemplate resolved, XirTemplate resolvedHybrid, XirTemplate unresolved) {
            super(resolved, unresolved);
            this.resolvedHybrid = resolvedHybrid;
        }
    }

    static class InvokeSpecialTemplates extends XirPair {
        final XirTemplate resolvedNullCheckEliminated;

        public InvokeSpecialTemplates(XirTemplate resolved, XirTemplate unresolved, XirTemplate resolvedNullCheckEliminated) {
            super(resolved, unresolved);
            this.resolvedNullCheckEliminated = resolvedNullCheckEliminated;
        }
    }

    private final HashMap<String, XirTemplate> runtimeCallStubs = new HashMap<String, XirTemplate>();
    private final HashMap<String, RiMethod> runtimeMethods = new HashMap<String, RiMethod>();

    private XirTemplate epilogueTemplate;
    private XirPair[] putFieldTemplates;
    private XirPair[] getFieldTemplates;
    private XirPair[] putStaticFieldTemplates;
    private XirPair[] getStaticFieldTemplates;

    private XirPair invokeVirtualTemplates;
    private XirPair invokeInterfaceTemplates;
    private InvokeSpecialTemplates invokeSpecialTemplates;
    private XirPair invokeStaticTemplates;
    private XirPair[] newArrayTemplates;
    private XirTemplate[] arrayLoadTemplates;
    private XirTemplate[] arrayStoreTemplates;

    private DynamicHub[] arrayHubs;

    private XirPair[] multiNewArrayTemplate;

    private XirTemplate safepointTemplate;
    private XirTemplate arraylengthTemplate;
    private XirTemplate monitorEnterTemplate;
    private XirTemplate monitorExitTemplate;
    private XirTemplate[] resolveClassTemplates;
    private NewInstanceTemplates newInstanceTemplate;
    private XirPair checkcastForLeafTemplate;
    private XirPair checkcastForClassTemplate;
    private XirPair checkcastForInterfaceTemplate;
    private XirPair instanceofForLeafTemplate;
    private XirPair instanceofForClassTemplate;
    private XirPair instanceofForInterfaceTemplate;

    private XirTemplate exceptionObjectTemplate;

    private List<XirTemplate> stubs = new ArrayList<XirTemplate>();

    @FOLD
    int hubOffset() {
        return generalLayout().getOffsetFromOrigin(Layout.HeaderField.HUB).toInt();
    }

    @FOLD
    int offsetOfFirstArrayElement() {
        return byteArrayLayout().getElementOffsetFromOrigin(0).toInt();
    }

    @FOLD
    int offsetOfMTableStartIndex() {
        return FieldActor.findInstance(Hub.class, "mTableStartIndex").offset();
    }

    @FOLD
    int offsetOfMTableLength() {
        return FieldActor.findInstance(Hub.class, "mTableLength").offset();
    }

    public MaxXirGenerator() {
    }

    private CiXirAssembler asm;

    @Override
    public List<XirTemplate> buildTemplates(CiXirAssembler asm) {

        CiKind[] kinds = CiKind.values();
        this.asm = asm;

        epilogueTemplate = buildEpilogue();

        putFieldTemplates = new XirPair[kinds.length];
        getFieldTemplates = new XirPair[kinds.length];
        putStaticFieldTemplates = new XirPair[kinds.length];
        getStaticFieldTemplates = new XirPair[kinds.length];

        newArrayTemplates = new XirPair[kinds.length];
        arrayLoadTemplates = new XirTemplate[kinds.length];
        arrayStoreTemplates = new XirTemplate[kinds.length];

        arrayHubs = new DynamicHub[kinds.length];

        arrayHubs[CiKind.Boolean.ordinal()] = ClassRegistry.BOOLEAN_ARRAY.dynamicHub();
        arrayHubs[CiKind.Byte.ordinal()] = ClassRegistry.BYTE_ARRAY.dynamicHub();
        arrayHubs[CiKind.Short.ordinal()] = ClassRegistry.SHORT_ARRAY.dynamicHub();
        arrayHubs[CiKind.Char.ordinal()] = ClassRegistry.CHAR_ARRAY.dynamicHub();
        arrayHubs[CiKind.Int.ordinal()] = ClassRegistry.INT_ARRAY.dynamicHub();
        arrayHubs[CiKind.Float.ordinal()] = ClassRegistry.FLOAT_ARRAY.dynamicHub();
        arrayHubs[CiKind.Double.ordinal()] = ClassRegistry.DOUBLE_ARRAY.dynamicHub();
        arrayHubs[CiKind.Long.ordinal()] = ClassRegistry.LONG_ARRAY.dynamicHub();
        arrayHubs[CiKind.Object.ordinal()] = ClassActor.fromJava(Object[].class).dynamicHub();
        arrayHubs[CiKind.Word.ordinal()] = ClassActor.fromJava(WordArray.class).dynamicHub();

        for (CiKind kind : kinds) {
            int index = kind.ordinal();
            if (kind == CiKind.Illegal || kind == CiKind.Jsr) {
                continue;
            }
            if (kind != CiKind.Void) {
                putFieldTemplates[index] = buildPutFieldTemplate(kind, kind == CiKind.Object, false);
                getFieldTemplates[index] = buildGetFieldTemplate(kind, false);
                putStaticFieldTemplates[index] = buildPutFieldTemplate(kind, kind == CiKind.Object, true);
                getStaticFieldTemplates[index] = buildGetFieldTemplate(kind, true);
                arrayLoadTemplates[index] = buildArrayLoad(kind, asm, true);
                arrayStoreTemplates[index] = buildArrayStore(kind, asm, true, kind == CiKind.Object, kind == CiKind.Object);
                newArrayTemplates[index] = buildNewArray(kind);
            }
        }

        invokeVirtualTemplates = buildInvokeVirtual();
        invokeInterfaceTemplates = buildInvokeInterface();
        invokeSpecialTemplates = buildInvokeSpecial();
        invokeStaticTemplates = buildInvokeStatic();

        multiNewArrayTemplate = new XirPair[MAX_MULTIANEWARRAY_RANK + 1];

        for (int i = 1; i < MAX_MULTIANEWARRAY_RANK + 1; i++) {
            multiNewArrayTemplate[i] = buildNewMultiArray(i);
        }

        resolveClassTemplates = new XirTemplate[Representation.values().length];
        for (Representation representation : Representation.values()) {
            resolveClassTemplates[representation.ordinal()] = buildResolveClass(representation);
        }

        safepointTemplate = buildSafepoint();
        arraylengthTemplate = buildArrayLength();
        monitorEnterTemplate = buildMonitorEnter();
        monitorExitTemplate = buildMonitorExit();

        newInstanceTemplate = buildNewInstance();

        checkcastForLeafTemplate = buildCheckcastForLeaf(false);
        checkcastForClassTemplate = buildCheckcastForInterface(false); // XXX: more efficient template for class checks
        checkcastForInterfaceTemplate = buildCheckcastForInterface(false);

        instanceofForLeafTemplate = buildInstanceofForLeaf(false);
        instanceofForClassTemplate = buildInstanceofForInterface(false); // XXX: more efficient template for class checks
        instanceofForInterfaceTemplate = buildInstanceofForInterface(false);

        exceptionObjectTemplate = buildExceptionObject();

        return stubs;
    }


    @Override
    public XirSnippet genPrologue(XirSite site, RiMethod method) {
        assert method.isResolved() : "Cannot generate prologue for unresolved method: " + method;
        ClassMethodActor callee = (ClassMethodActor) method;

        // Cannot share 'asm' across concurrent compilations.
        CiXirAssembler asm = this.asm.copy();
        asm.restart(CiKind.Void);

        AdapterGenerator generator = AdapterGenerator.forCallee(callee, CallEntryPoint.OPTIMIZED_ENTRY_POINT);
        if (generator != null) {
            ByteArrayOutputStream os = new ByteArrayOutputStream(8);
            generator.adapt(callee, os);
            asm.rawBytes(os.toByteArray());
        }

        asm.pushFrame();

        if (!callee.isVmEntryPoint()) {
            asm.stackOverflowCheck();
        }
        return new XirSnippet(finishTemplate(asm, "prologue"));
    }

    @HOSTED_ONLY
    private XirTemplate buildEpilogue() {
        asm.restart(CiKind.Void);
        asm.popFrame();
        // TODO safepoint check
        return asm.finishTemplate("epilogue");
    }

    @Override
    public XirSnippet genEpilogue(XirSite site, RiMethod method) {
        return new XirSnippet(epilogueTemplate);
    }

    @Override
    public XirSnippet genSafepoint(XirSite site) {
        return new XirSnippet(safepointTemplate);
    }

    @Override
    public XirSnippet genResolveClass(XirSite site, RiType type, Representation representation) {
        return new XirSnippet(resolveClassTemplates[representation.ordinal()], guardFor(type, ResolveClass.SNIPPET));
    }

    @Override
    public XirSnippet genInvokeInterface(XirSite site, XirArgument receiver, RiMethod method) {
        XirPair pair = invokeInterfaceTemplates;
        if (method.isResolved()) {
            InterfaceMethodActor methodActor = (InterfaceMethodActor) method;
            XirArgument interfaceID = XirArgument.forInt(methodActor.holder().id);
            XirArgument methodIndex = XirArgument.forInt(methodActor.iIndexInInterface());
            return new XirSnippet(pair.resolved, receiver, interfaceID, methodIndex);
        }
        XirArgument guard = XirArgument.forObject(guardFor(method, ResolveInterfaceMethod.SNIPPET));
        return new XirSnippet(pair.unresolved, receiver, guard);
    }

    @Override
    public XirSnippet genInvokeVirtual(XirSite site, XirArgument receiver, RiMethod method) {
        XirPair pair = invokeVirtualTemplates;
        if (method.isResolved()) {
            VirtualMethodActor methodActor = (VirtualMethodActor) method;
            XirArgument vtableOffset = XirArgument.forInt(methodActor.vTableIndex() * Word.size() + offsetOfFirstArrayElement());
            return new XirSnippet(pair.resolved, receiver, vtableOffset);
        }
        XirArgument guard = XirArgument.forObject(guardFor(method, ResolveVirtualMethod.SNIPPET));
        return new XirSnippet(pair.unresolved, receiver, guard);
    }

    @Override
    public XirSnippet genInvokeSpecial(XirSite site, XirArgument receiver, RiMethod method) {
        if (method.isResolved()) {
            if (site.requiresNullCheck()) {
                return new XirSnippet(invokeSpecialTemplates.resolved, XirArgument.forWord(0), receiver);
            }
            return new XirSnippet(invokeSpecialTemplates.resolvedNullCheckEliminated, XirArgument.forWord(0));
        }

        XirArgument guard = XirArgument.forObject(guardFor(method, ResolveVirtualMethod.SNIPPET));
        return new XirSnippet(invokeSpecialTemplates.unresolved, guard);
    }

    @Override
    public XirSnippet genInvokeStatic(XirSite site, RiMethod method) {
        if (method.isResolved()) {
            return new XirSnippet(invokeStaticTemplates.resolved, XirArgument.forWord(0));
        }

        XirArgument guard = XirArgument.forObject(guardFor(method, ResolveStaticMethod.SNIPPET));
        return new XirSnippet(invokeStaticTemplates.unresolved, guard);
    }

    @Override
    public XirSnippet genMonitorEnter(XirSite site, XirArgument receiver, XirArgument lockAddress) {
        return new XirSnippet(monitorEnterTemplate, receiver);
    }

    @Override
    public XirSnippet genMonitorExit(XirSite site, XirArgument receiver, XirArgument lockAddress) {
        return new XirSnippet(monitorExitTemplate, receiver);
    }

    @Override
    public XirSnippet genGetField(XirSite site, XirArgument receiver, RiField field) {
        XirPair pair = getFieldTemplates[field.kind().ordinal()];
        if (field.isResolved()) {
            FieldActor fieldActor = (FieldActor) field;
            XirArgument offset = XirArgument.forInt(fieldActor.offset());
            return new XirSnippet(pair.resolved, receiver, offset);
        }

        XirArgument guard = XirArgument.forObject(guardFor(field, ResolveInstanceFieldForReading.SNIPPET));
        return new XirSnippet(pair.unresolved, receiver, guard);
    }

    @Override
    public XirSnippet genPutField(XirSite site, XirArgument receiver, RiField field, XirArgument value) {
        XirPair pair = putFieldTemplates[field.kind().ordinal()];
        if (field.isResolved()) {
            FieldActor fieldActor = (FieldActor) field;
            XirArgument offset = XirArgument.forInt(fieldActor.offset());
            return new XirSnippet(pair.resolved, receiver, value, offset);
        }
        XirArgument guard = XirArgument.forObject(guardFor(field, ResolveInstanceFieldForWriting.SNIPPET));
        return new XirSnippet(pair.unresolved, receiver, value, guard);
    }

    @Override
    public XirSnippet genGetStatic(XirSite site, XirArgument staticTuple, RiField field) {
        XirPair pair = getStaticFieldTemplates[field.kind().ordinal()];
        if (field.isResolved()) {
            FieldActor fieldActor = (FieldActor) field;
            XirArgument offset = XirArgument.forInt(fieldActor.offset());
            return new XirSnippet(pair.resolved, staticTuple, offset);
        }
        XirArgument guard = XirArgument.forObject(guardFor(field, ResolveStaticFieldForReading.SNIPPET));
        return new XirSnippet(pair.unresolved, staticTuple, guard);
    }

    @Override
    public XirSnippet genPutStatic(XirSite site, XirArgument staticTuple, RiField field, XirArgument value) {
        XirPair pair = putStaticFieldTemplates[field.kind().ordinal()];
        if (field.isResolved()) {
            FieldActor fieldActor = (FieldActor) field;
            XirArgument offset = XirArgument.forInt(fieldActor.offset());
            return new XirSnippet(pair.resolved, staticTuple, value, offset);
        }
        XirArgument guard = XirArgument.forObject(guardFor(field, ResolveStaticFieldForWriting.SNIPPET));
        return new XirSnippet(pair.unresolved, staticTuple, value, guard);
    }

    @Override
    public XirSnippet genNewInstance(XirSite site, RiType type) {
        if (type.isResolved() && type.isInitialized()) {
            DynamicHub hub = hubFor(type);
            if (hub.classActor.isHybridClass()) {
                return new XirSnippet(newInstanceTemplate.resolvedHybrid, XirArgument.forObject(hub));
            }
            return new XirSnippet(newInstanceTemplate.resolved, XirArgument.forObject(hub));
        }
        XirArgument guard = guardFor(type, ResolveClassForNew.SNIPPET);
        return new XirSnippet(newInstanceTemplate.unresolved, guard);
    }

    private DynamicHub hubFor(RiType type) {
        if (type instanceof ClassActor) {
            return ((ClassActor) type).dynamicHub();
        }
        throw ((UnresolvedType) type).unresolved("new instance");
    }

    @Override
    public XirSnippet genNewArray(XirSite site, XirArgument length, CiKind elementKind, RiType componentType, RiType arrayType) {
        XirPair pair = newArrayTemplates[elementKind.ordinal()];
        Object hub = arrayHubs[elementKind.ordinal()];
        if (elementKind == CiKind.Object && arrayType.isResolved()) {
            hub = hubFor(arrayType);
        } else if (elementKind == CiKind.Object) {
            hub = null;
        }

        if (hub != null) {
            return new XirSnippet(pair.resolved, XirArgument.forObject(hub), length);
        }
        XirArgument guard = guardForComponentType(componentType);
        return new XirSnippet(pair.unresolved, guard, length);
    }

    @Override
    public XirSnippet genNewMultiArray(XirSite site, XirArgument[] lengths, RiType type) {
        int rank = lengths.length;
        if (!type.isResolved() || rank >= SMALL_MULTIANEWARRAY_RANK) {
            XirArgument guard = guardFor(type, ResolveClass.SNIPPET);
            return new XirSnippet(multiNewArrayTemplate[rank].unresolved, Utils.concat(lengths, guard));
        }
        if (rank >= multiNewArrayTemplate.length) {
            FatalError.unimplemented();
        }
        XirArgument hub = XirArgument.forObject(hubFor(type));
        return new XirSnippet(multiNewArrayTemplate[rank].resolved, Utils.concat(lengths, hub));
    }

    @Override
    public XirSnippet genCheckCast(XirSite site, XirArgument object, XirArgument hub, RiType type) {
        if (type.isResolved()) {
            XirTemplate template;
            if (type.isInterface()) {
                // have to use the interface template
                template = checkcastForInterfaceTemplate.resolved;
                ClassActor classActor = (ClassActor) type;
                int interfaceID = classActor.id;
                return new XirSnippet(template, object, XirArgument.forInt(interfaceID), hub);
            } else if (isFinal(type.accessFlags()) && !type.isArrayClass()) {
                // can use the leaf class test
                template = checkcastForLeafTemplate.resolved;
            } else {
                // can use the class test
                template = checkcastForClassTemplate.resolved;
                ClassActor classActor = (ClassActor) type;
                int interfaceID = classActor.id;
                return new XirSnippet(template, object, XirArgument.forInt(interfaceID), hub);
            }
            return new XirSnippet(template, object, hub);
        }
        XirArgument guard = guardFor(type, ResolveClass.SNIPPET);
        return new XirSnippet(checkcastForInterfaceTemplate.unresolved, object, guard);
    }

    @Override
    public XirSnippet genInstanceOf(XirSite site, XirArgument object, XirArgument hub, RiType type) {
        if (type.isResolved()) {
            XirTemplate template;
            if (type.isInterface()) {
                template = instanceofForInterfaceTemplate.resolved;
                ClassActor classActor = (ClassActor) type;
                int interfaceID = classActor.id;
                return new XirSnippet(template, object, XirArgument.forInt(interfaceID), hub);
            }

            if (isFinal(type.accessFlags()) && !type.isArrayClass()) {
                template = instanceofForLeafTemplate.resolved;
            } else {
                template = instanceofForClassTemplate.resolved;
                ClassActor classActor = (ClassActor) type;
                int interfaceID = classActor.id;
                return new XirSnippet(template, object, XirArgument.forInt(interfaceID), hub);
            }
            return new XirSnippet(template, object, hub);
        }
        XirArgument guard = guardFor(type, ResolveClass.SNIPPET);
        return new XirSnippet(instanceofForInterfaceTemplate.unresolved, object, guard);
    }

    @Override
    public XirSnippet genArrayLoad(XirSite site, XirArgument array, XirArgument index, XirArgument length, CiKind elementKind, RiType elementType) {
        XirTemplate template = arrayLoadTemplates[elementKind.ordinal()];
        return new XirSnippet(template, array, index);
    }

    @Override
    public XirSnippet genArrayStore(XirSite site, XirArgument array, XirArgument index, XirArgument length, XirArgument value, CiKind elementKind, RiType elementType) {
        XirTemplate template = arrayStoreTemplates[elementKind.ordinal()];
        return new XirSnippet(template, array, index, value);
    }

    @Override
    public XirSnippet genArrayLength(XirSite site, XirArgument array) {
        return new XirSnippet(arraylengthTemplate, array);
    }

    @Override
    public XirSnippet genExceptionObject(XirSite site) {
        return new XirSnippet(exceptionObjectTemplate);
    }

    private ResolutionGuard guardFor(RiField unresolvedField, ResolutionSnippet snippet) {
        UnresolvedField f = (UnresolvedField) unresolvedField;
        return makeResolutionGuard(f.constantPool, f.cpi, snippet);
    }

    private ResolutionGuard guardFor(RiMethod unresolvedMethod, ResolutionSnippet snippet) {
        UnresolvedMethod m = (UnresolvedMethod) unresolvedMethod;
        return makeResolutionGuard(m.constantPool, m.cpi, snippet);
    }

    private XirArgument guardForComponentType(RiType type) {
        if (type instanceof InPool) {
            InPool unresolvedType = (InPool) type;
            return XirArgument.forObject(makeResolutionGuard(unresolvedType.pool, unresolvedType.cpi, ResolveArrayClass.SNIPPET));
        }
        return XirArgument.forObject(new ResolutionGuard.InAccessingClass((ByAccessingClass) type));
    }

    private XirArgument guardFor(RiType type, ResolutionSnippet snippet) {
        ResolutionGuard guard;
        if (!type.isResolved()) {
            if (type instanceof InPool) {
                InPool unresolvedType = (InPool) type;
                guard = makeResolutionGuard(unresolvedType.pool, unresolvedType.cpi, snippet);
            } else {
                guard = new ResolutionGuard.InAccessingClass((ByAccessingClass) type);
            }
        } else {
            guard = new ResolutionGuard.InPool(null, Integer.MAX_VALUE);
            guard.value = (ClassActor) type;
        }
        return XirArgument.forObject(guard);
    }

    private ResolutionGuard makeResolutionGuard(ConstantPool pool, int cpi, ResolutionSnippet snippet) {
        assert cpi > 0;
        return pool.makeResolutionGuard(cpi, snippet);
    }

    @HOSTED_ONLY
    private XirTemplate buildResolveClass(Representation representation) {
        XirOperand result = asm.restart(CiKind.Object);
        XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
        String resolver = null;
        switch (representation) {
            // Checkstyle: stop
            case JavaClass    : resolver = "resolveClassObject"; break;
            case ObjectHub    : resolver = "resolveHub"; break;
            case StaticFields : resolver = "resolveStaticTuple"; break;
            case TypeInfo     : resolver = "resolveClassActor"; break;
            // Checkstyle: resume
        }
        callRuntimeThroughStub(asm, resolver, result, guard);
        return finishTemplate(asm, resolver);
    }

    @HOSTED_ONLY
    private XirTemplate buildSafepoint() {
        asm.restart(CiKind.Void);
        XirOperand latch = asm.createRegisterTemp("latch", CiKind.Word, AMD64.r14);
        asm.safepoint();
        asm.pload(CiKind.Word, latch, latch, false);
        return finishTemplate(asm, "safepoint");
    }

    @HOSTED_ONLY
    private XirTemplate buildArrayLength() {
        XirOperand result = asm.restart(CiKind.Int);
        XirOperand param = asm.createInputParameter("param", CiKind.Object);
        asm.pload(CiKind.Word, result, param, asm.i(arrayLayout().arrayLengthOffset()), true);
        return finishTemplate(asm, "arraylength");
    }

    @HOSTED_ONLY
    private XirTemplate buildArrayStore(CiKind kind, CiXirAssembler asm, boolean genBoundsCheck, boolean genStoreCheck, boolean genWriteBarrier) {
        asm.restart(CiKind.Void);
        XirParameter array = asm.createInputParameter("array", CiKind.Object);
        XirParameter index = asm.createInputParameter("index", CiKind.Int);
        XirParameter value = asm.createInputParameter("value", kind);
        XirOperand length = asm.createTemp("length", CiKind.Int);
        XirOperand valueHub = null;
        XirOperand compHub = null;
        XirLabel store = asm.createInlineLabel("store");
        XirLabel failBoundsCheck = null;
        XirLabel slowStoreCheck = null;
        if (genBoundsCheck) {
            // load the array length and check the index
            failBoundsCheck = asm.createOutOfLineLabel("failBoundsCheck");
            asm.pload(CiKind.Int, length, array, asm.i(arrayLayout().arrayLengthOffset()), true);
            asm.jugteq(failBoundsCheck, index, length);
        }
        if (genStoreCheck) {
            slowStoreCheck = asm.createOutOfLineLabel("slowStoreCheck");
            asm.jeq(store, value, asm.o(null)); // first check if value is null
            valueHub = asm.createTemp("valueHub", CiKind.Object);
            compHub = asm.createTemp("compHub", CiKind.Object);
            int compHubOffset = FieldActor.findInstance(Hub.class, "componentHub").offset();
            asm.pload(CiKind.Object, compHub, array, asm.i(hubOffset()), !genBoundsCheck);
            asm.pload(CiKind.Object, compHub, compHub, asm.i(compHubOffset), false);
            asm.pload(CiKind.Object, valueHub, value, asm.i(hubOffset()), false);
            asm.jneq(slowStoreCheck, compHub, valueHub); // then check component hub matches value hub
        }
        asm.bindInline(store);
        int elemSize = target().sizeInBytes(kind);
        asm.pstore(kind, array, index, value, offsetOfFirstArrayElement(), Scale.fromInt(elemSize), !genBoundsCheck && !genStoreCheck);
        if (genWriteBarrier) {
            addWriteBarrier(asm, array, value);
        }
        if (genBoundsCheck) {
            asm.bindOutOfLine(failBoundsCheck);
            callRuntimeThroughStub(asm, "throwArrayIndexOutOfBoundsException", null, array, index);
        }
        if (genStoreCheck) {
            asm.bindOutOfLine(slowStoreCheck);
            callRuntimeThroughStub(asm, "arrayHubStoreCheck", null, compHub, valueHub);
            asm.jmp(store);
        }
        return finishTemplate(asm, "arraystore<" + kind + ">");
    }

    @HOSTED_ONLY
    private XirTemplate buildArrayLoad(CiKind kind, CiXirAssembler asm, boolean genBoundsCheck) {
        XirOperand result = asm.restart(kind);
        XirParameter array = asm.createInputParameter("array", CiKind.Object);
        XirParameter index = asm.createInputParameter("index", CiKind.Int);
        XirOperand length = asm.createTemp("length", CiKind.Int);
        XirLabel fail = null;
        if (genBoundsCheck) {
            // load the array length and check the index
            fail = asm.createOutOfLineLabel("fail");
            asm.pload(CiKind.Int, length, array, asm.i(arrayLayout().arrayLengthOffset()), true);
            asm.jugteq(fail, index, length);
        }
        int elemSize = target().sizeInBytes(kind);
        asm.pload(kind, result, array, index, offsetOfFirstArrayElement(), Scale.fromInt(elemSize), !genBoundsCheck);
        if (genBoundsCheck) {
            asm.bindOutOfLine(fail);
            callRuntimeThroughStub(asm, "throwArrayIndexOutOfBoundsException", null, array, index);
        }
        return finishTemplate(asm, "arrayload<" + kind + ">");
    }

    @HOSTED_ONLY
    private XirPair buildInvokeStatic() {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            // resolved invokestatic template
            asm.restart();
            XirParameter addr = asm.createConstantInputParameter("addr", CiKind.Word);
            resolved = finishTemplate(asm, addr, "invokestatic");
        }
        {
            // unresolved invokestatic template
            asm.restart();
            XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
            XirOperand addr = asm.createTemp("addr", CiKind.Word);
            callRuntimeThroughStub(asm, "resolveStaticMethod", addr, guard);
            unresolved = finishTemplate(asm, addr, "invokestatic-unresolved");
        }
        return new XirPair(resolved, unresolved);
    }

    private InvokeSpecialTemplates buildInvokeSpecial() {
        XirTemplate resolved;
        XirTemplate resolvedNullCheckEliminated;
        XirTemplate unresolved;
        {
            // resolved case
            asm.restart();
            XirParameter addr = asm.createConstantInputParameter("addr", CiKind.Word); // address to call
            XirParameter receiver = asm.createInputParameter("receiver", CiKind.Object); // receiver object
            asm.nullCheck(receiver);
            resolved = finishTemplate(asm, addr, "invokespecial");
        }
        {
            // resolved case, null pointer check eliminated
            asm.restart();
            XirParameter addr = asm.createConstantInputParameter("addr", CiKind.Word); // address to call
            resolvedNullCheckEliminated = finishTemplate(asm, addr, "invokespecial-nce");
        }
        {
            // unresolved invokespecial template
            asm.restart();
            XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
            XirOperand addr = asm.createTemp("addr", CiKind.Word);
            callRuntimeThroughStub(asm, "resolveSpecialMethod", addr, guard);
            unresolved = finishTemplate(asm, addr, "invokespecial-unresolved");
        }
        return new InvokeSpecialTemplates(resolved, unresolved, resolvedNullCheckEliminated);
    }

    @HOSTED_ONLY
    private XirPair buildInvokeInterface() {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            // resolved invokeinterface
            asm.restart();
            XirParameter receiver = asm.createInputParameter("receiver", CiKind.Object); // receiver object
            XirParameter interfaceID = asm.createConstantInputParameter("interfaceID", CiKind.Int);
            XirParameter methodIndex = asm.createConstantInputParameter("methodIndex", CiKind.Int);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            XirOperand mtableLengthOrStartIndex = asm.createTemp("mtableLength/StartIndex", CiKind.Int);
            XirOperand a = asm.createTemp("a", CiKind.Int);
            asm.pload(CiKind.Object, hub, receiver, asm.i(hubOffset()), true);
            asm.pload(CiKind.Int, mtableLengthOrStartIndex, hub, asm.i(offsetOfMTableLength()), false);
            asm.mod(a, interfaceID, mtableLengthOrStartIndex);
            asm.pload(CiKind.Int, mtableLengthOrStartIndex, hub, asm.i(offsetOfMTableStartIndex()), false);
            asm.add(a, a, mtableLengthOrStartIndex);
            asm.pload(CiKind.Int, a, hub, a, offsetOfFirstArrayElement(), Scale.Times4, false);
            asm.add(a, a, methodIndex);
            XirOperand result = asm.createTemp("result", CiKind.Word);
            asm.pload(CiKind.Word, result, hub, a, offsetOfFirstArrayElement(), Scale.fromInt(Word.size()), false);
            resolved = finishTemplate(asm, result, "invokeinterface");
        }
        {
            // unresolved invokeinterface
            // TODO This uses seven registers, combined with lots of parameters this can lead to heavy spilling.
            // Some of the temps could be reused if there was a way to use them as another CiKind.
            asm.restart();
            XirParameter receiver = asm.createInputParameter("receiver", CiKind.Object); // receiver object
            XirParameter guard = asm.createInputParameter("guard", CiKind.Object); // guard
            XirOperand interfaceID = asm.createTemp("interfaceID", CiKind.Int);
            XirOperand methodIndex = asm.createTemp("methodIndex", CiKind.Int);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);

            callRuntimeThroughStub(asm, "resolveInterfaceMethod", methodIndex, guard);
            callRuntimeThroughStub(asm, "resolveInterfaceID", interfaceID, guard);
            XirOperand mtableLengthOrStartIndex = asm.createTemp("mtableLength/StartIndex", CiKind.Int);
            XirOperand a = asm.createTemp("a", CiKind.Int);
            asm.pload(CiKind.Object, hub, receiver, asm.i(hubOffset()), true);
            asm.pload(CiKind.Int, mtableLengthOrStartIndex, hub, asm.i(offsetOfMTableLength()), false);
            asm.mod(a, interfaceID, mtableLengthOrStartIndex);
            asm.pload(CiKind.Int, mtableLengthOrStartIndex, hub, asm.i(offsetOfMTableStartIndex()), false);
            asm.add(a, a, mtableLengthOrStartIndex);
            asm.pload(CiKind.Int, a, hub, a, offsetOfFirstArrayElement(), Scale.Times4, false);
            asm.add(a, a, methodIndex);
            XirOperand result = asm.createTemp("result", CiKind.Word);
            asm.pload(CiKind.Word, result, hub, a, offsetOfFirstArrayElement(), Scale.fromInt(Word.size()), false);
            unresolved = finishTemplate(asm, result, "invokeinterface");
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirPair buildInvokeVirtual() {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            // resolved invokevirtual
            asm.restart();
            XirParameter receiver = asm.createInputParameter("receiver", CiKind.Object);
            XirParameter vtableOffset = asm.createConstantInputParameter("vtableOffset", CiKind.Int);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            XirOperand addr = asm.createTemp("addr", CiKind.Word);
            asm.pload(CiKind.Object, hub, receiver, asm.i(hubOffset()), true);
            asm.pload(CiKind.Word, addr, hub, vtableOffset, false);
            resolved = finishTemplate(asm, addr, "invokevirtual");
        }
        {
            // unresolved invokevirtual template
            asm.restart();
            XirParameter receiver = asm.createInputParameter("receiver", CiKind.Object); // receiver object
            XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
            XirOperand vtableOffset = asm.createTemp("vtableOffset", CiKind.Int);
            callRuntimeThroughStub(asm, "resolveVirtualMethod", vtableOffset, guard);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            XirOperand addr = asm.createTemp("addr", CiKind.Word);
            asm.pload(CiKind.Object, hub, receiver, asm.i(hubOffset()), true);
            asm.pload(CiKind.Word, addr, hub, vtableOffset, false);
            unresolved = finishTemplate(asm, addr, "invokevirtual-unresolved");
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirPair buildJniCall() {
        XirTemplate linked;
        XirTemplate unlinked;
        {
            // resolved invokevirtual
            asm.restart();
            XirParameter receiver = asm.createInputParameter("receiver", CiKind.Object);
            XirParameter vtableOffset = asm.createConstantInputParameter("vtableOffset", CiKind.Int);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            XirOperand addr = asm.createTemp("addr", CiKind.Word);
            asm.pload(CiKind.Object, hub, receiver, asm.i(hubOffset()), true);
            asm.pload(CiKind.Word, addr, hub, vtableOffset, false);
            linked = finishTemplate(asm, addr, "invokevirtual");
        }
        {
            // unresolved invokevirtual template
            asm.restart();
            XirParameter receiver = asm.createInputParameter("receiver", CiKind.Object); // receiver object
            XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
            XirOperand vtableOffset = asm.createTemp("vtableOffset", CiKind.Int);
            callRuntimeThroughStub(asm, "resolveVirtualMethod", vtableOffset, guard);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            XirOperand addr = asm.createTemp("addr", CiKind.Word);
            asm.pload(CiKind.Object, hub, receiver, asm.i(hubOffset()), true);
            asm.pload(CiKind.Word, addr, hub, vtableOffset, false);
            unlinked = finishTemplate(asm, addr, "invokevirtual-unresolved");
        }
        return new XirPair(linked, unlinked);
    }

    @HOSTED_ONLY
    private XirPair buildNewArray(CiKind kind) {
        XirTemplate resolved;
        XirTemplate unresolved;
        if (kind == CiKind.Object) {
            {
                // resolved new object array
                XirOperand result = asm.restart(CiKind.Object);
                XirParameter hub = asm.createConstantInputParameter("hub", CiKind.Object);
                XirParameter length = asm.createInputParameter("length", CiKind.Int);
                callRuntimeThroughStub(asm, "allocateObjectArray", result, hub, length);
                resolved = finishTemplate(asm, "anewarray<" + kind + ">");
            }
            {
                // unresolved new object array
                XirOperand result = asm.restart(CiKind.Object);
                XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
                XirParameter length = asm.createInputParameter("length", CiKind.Int);
                XirOperand hub = asm.createTemp("hub", CiKind.Object);
                callRuntimeThroughStub(asm, "resolveNewArray", hub, guard);
                callRuntimeThroughStub(asm, "allocateObjectArray", result, hub, length);
                unresolved = finishTemplate(asm, "anewarray<" + kind + ">-unresolved");
            }

        } else {
            // XXX: specialized, inline templates for each kind
            XirOperand result = asm.restart(CiKind.Object);
            XirParameter hub = asm.createConstantInputParameter("hub", CiKind.Object);
            XirParameter length = asm.createInputParameter("length", CiKind.Int);
            callRuntimeThroughStub(asm, "allocatePrimitiveArray", result, hub, length);
            resolved = finishTemplate(asm, "newarray<" + kind + ">");
            unresolved = resolved;
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirPair buildNewMultiArray(int rank) {
        XirTemplate resolved = null;
        XirTemplate unresolved;
        if (rank < SMALL_MULTIANEWARRAY_RANK) {
            // "small" resolved multianewarray (rank 3 or less)
            XirOperand result = asm.restart(CiKind.Object);
            XirParameter[] lengths = new XirParameter[rank];
            for (int i = 0; i < rank; i++) {
                lengths[i] = asm.createInputParameter("lengths[" + i + "]", CiKind.Int);
            }
            XirParameter hub = asm.createConstantInputParameter("hub", CiKind.Object);
            callRuntimeThroughStub(asm, "allocateMultiArray" + rank, result, Utils.prepend(lengths, hub));
            resolved = finishTemplate(asm, "multianewarray<" + rank + ">");
        }

        // unresolved or large multianewarray
        XirOperand result = asm.restart(CiKind.Object);
        XirParameter[] lengths = new XirParameter[rank];
        for (int i = 0; i < rank; i++) {
            lengths[i] = asm.createInputParameter("lengths[" + i + "]", CiKind.Int);
        }
        XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
        XirOperand lengthArray = asm.createTemp("lengthArray", CiKind.Object);
        callRuntimeThroughStub(asm, "allocateIntArray", lengthArray, asm.i(rank));
        for (int i = 0; i < rank; i++) {
            asm.pstore(CiKind.Int, lengthArray, asm.i(offsetOfFirstArrayElement() + (i * 4)), lengths[i], false);
        }
        callRuntimeThroughStub(asm, "allocateMultiArrayN", result, guard, lengthArray);
        unresolved = finishTemplate(asm, "multianewarray-complex<" + rank + ">");

        return new XirPair(resolved == null ? unresolved : resolved, unresolved);
    }

    private NewInstanceTemplates buildNewInstance() {
        XirTemplate resolved;
        XirTemplate resolvedHybrid;
        XirTemplate unresolved;
        {
            // resolved new instance
            XirOperand result = asm.restart(CiKind.Object);
            XirParameter hub = asm.createConstantInputParameter("hub", CiKind.Object);
            callRuntimeThroughStub(asm, "allocateObject", result, hub);
            resolved = finishTemplate(asm, "new");
        }
        {
            // resolved new hybrid
            XirOperand result = asm.restart(CiKind.Object);
            XirParameter hub = asm.createConstantInputParameter("hub", CiKind.Object);
            callRuntimeThroughStub(asm, "allocateHybrid", result, hub);
            resolvedHybrid = finishTemplate(asm, "newHybrid");
        }
        {
            // unresolved new instance
            XirOperand result = asm.restart(CiKind.Object);
            XirParameter guard = asm.createConstantInputParameter("guard", CiKind.Object);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            callRuntimeThroughStub(asm, "resolveNew", hub, guard);
            callRuntimeThroughStub(asm, "allocateObject", result, hub);
            unresolved = finishTemplate(asm, "new-unresolved");
        }

        return new NewInstanceTemplates(resolved, resolvedHybrid, unresolved);
    }

    @HOSTED_ONLY
    private XirPair buildPutFieldTemplate(CiKind kind, boolean genWriteBarrier, boolean isStatic) {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            // resolved case
            asm.restart(CiKind.Void);
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter value = asm.createInputParameter("value", kind);
            XirParameter fieldOffset = asm.createConstantInputParameter("fieldOffset", CiKind.Int);
            asm.pstore(kind, object, fieldOffset, value, true);
            if (genWriteBarrier) {
                addWriteBarrier(asm, object, value);
            }
            resolved = finishTemplate(asm, "putfield<" + kind + ", " + genWriteBarrier + ">");
        } {
            // unresolved case
            asm.restart(CiKind.Void);
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter value = asm.createInputParameter("value", kind);
            XirParameter guard = asm.createInputParameter("guard", CiKind.Object);
            XirOperand fieldOffset = asm.createTemp("fieldOffset", CiKind.Int);
            if (isStatic) {
                callRuntimeThroughStub(asm, "resolvePutStatic", fieldOffset, guard);
            } else {
                callRuntimeThroughStub(asm, "resolvePutField", fieldOffset, guard);
            }
            asm.pstore(kind, object, fieldOffset, value, true);
            if (genWriteBarrier) {
                addWriteBarrier(asm, object, value);
            }
            unresolved = finishTemplate(asm, "putfield<" + kind + ", " + genWriteBarrier + ">-unresolved");
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirPair buildGetFieldTemplate(CiKind kind, boolean isStatic) {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            // resolved case
            XirOperand result = asm.restart(kind);
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter fieldOffset = asm.createConstantInputParameter("fieldOffset", CiKind.Int);
            asm.pload(kind, result, object, fieldOffset, true);
            resolved = finishTemplate(asm, "getfield<" + kind + ">");
        }
        {
            // unresolved case
            XirOperand result = asm.restart(kind);
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter guard = asm.createInputParameter("guard", CiKind.Object);
            XirOperand fieldOffset = asm.createTemp("fieldOffset", CiKind.Int);
            if (isStatic) {
                callRuntimeThroughStub(asm, "resolveGetStatic", fieldOffset, guard);
            } else {
                callRuntimeThroughStub(asm, "resolveGetField", fieldOffset, guard);
            }
            asm.pload(kind, result, object, fieldOffset, true);
            unresolved = finishTemplate(asm, "getfield<" + kind + ">-unresolved");
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirTemplate buildMonitorExit() {
        asm.restart(CiKind.Void);
        XirParameter object = asm.createInputParameter("object", CiKind.Object);
        callRuntimeThroughStub(asm, "monitorExit", null, object);
        return finishTemplate(asm, "monitorexit");
    }

    @HOSTED_ONLY
    private XirTemplate buildMonitorEnter() {
        asm.restart(CiKind.Void);
        XirParameter object = asm.createInputParameter("object", CiKind.Object);
        callRuntimeThroughStub(asm, "monitorEnter", null, object);
        return finishTemplate(asm, "monitorenter");
    }

    @HOSTED_ONLY
    private XirPair buildCheckcastForLeaf(boolean nonnull) {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            // resolved checkcast for a leaf class
            asm.restart();
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter checkedHub = asm.createConstantInputParameter("checkedHub", CiKind.Object);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            XirLabel pass = asm.createInlineLabel("pass");
            XirLabel fail = asm.createOutOfLineLabel("fail");
            if (!nonnull) {
                // first check against null
                asm.jeq(pass, object, asm.o(null));
            }
            asm.pload(CiKind.Object, hub, object, asm.i(hubOffset()), !nonnull);
            asm.jneq(fail, hub, checkedHub);
            asm.bindInline(pass);
            asm.bindOutOfLine(fail);
            callRuntimeThroughStub(asm, "throwClassCastException", null, checkedHub, object);
            resolved = finishTemplate(asm, object, "checkcast-leaf<" + nonnull + ">");
        }
        {
            // unresolved checkcast
            unresolved = buildUnresolvedCheckcast(nonnull);
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirPair buildCheckcastForInterface(boolean nonnull) {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            // resolved checkcast against an interface class
            asm.restart();
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter interfaceID = asm.createConstantInputParameter("interfaceID", CiKind.Int);
            XirParameter checkedHub = asm.createConstantInputParameter("checkedHub", CiKind.Object);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            XirOperand mtableTemp = asm.createTemp("mtableTemp", CiKind.Int);
            XirOperand a = asm.createTemp("a", CiKind.Int);
            XirLabel pass = asm.createInlineLabel("pass");
            XirLabel fail = asm.createOutOfLineLabel("fail");
            // XXX: use a cache to check the last successful receiver type
            if (!nonnull) {
                // first check for null
                asm.jeq(pass, object, asm.o(null));
            }
            asm.pload(CiKind.Object, hub, object, asm.i(hubOffset()), !nonnull);
            asm.jeq(pass, hub, checkedHub);
            asm.pload(CiKind.Int, mtableTemp, hub, asm.i(offsetOfMTableLength()), false);
            asm.mod(a, interfaceID, mtableTemp);
            asm.pload(CiKind.Int, mtableTemp, hub, asm.i(offsetOfMTableStartIndex()), false);
            asm.add(a, a, mtableTemp);
            asm.pload(CiKind.Int, a, hub, a, offsetOfFirstArrayElement(), Scale.Times4, false);
            asm.pload(CiKind.Int, a, hub, a, offsetOfFirstArrayElement(), Scale.fromInt(Word.size()), false);
            asm.jneq(fail, a, interfaceID);
            asm.bindInline(pass);
            asm.bindOutOfLine(fail);
            callRuntimeThroughStub(asm, "throwClassCastException", null, checkedHub, object);
            resolved = finishTemplate(asm, object, "checkcast-interface<" + nonnull + ">");
        }
        {
            unresolved = buildUnresolvedCheckcast(nonnull);
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirTemplate buildUnresolvedCheckcast(boolean nonnull) {
        asm.restart();
        XirParameter object = asm.createInputParameter("object", CiKind.Object);
        XirParameter guard = asm.createInputParameter("guard", CiKind.Object);
        XirLabel pass = asm.createInlineLabel("pass");
        if (!nonnull) {
            // XXX: build a version that does not include a null check
            asm.jeq(pass, object, asm.o(null));
        }
        callRuntimeThroughStub(asm, "unresolvedCheckcast", null, object, guard);
        asm.bindInline(pass);
        return finishTemplate(asm, object, "checkcast-unresolved<" + nonnull + ">");
    }

    @HOSTED_ONLY
    private XirPair buildInstanceofForLeaf(boolean nonnull) {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            XirOperand result = asm.restart(CiKind.Boolean);
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter hub = asm.createConstantInputParameter("hub", CiKind.Object);
            XirOperand temp = asm.createTemp("temp", CiKind.Object);
            XirLabel pass = asm.createInlineLabel("pass");
            XirLabel fail = asm.createInlineLabel("fail");
            asm.mov(result, asm.b(false));
            if (!nonnull) {
                // first check for null
                asm.jeq(fail, object, asm.o(null));
            }
            asm.pload(CiKind.Object, temp, object, asm.i(hubOffset()), !nonnull);
            asm.jneq(fail, temp, hub);
            asm.bindInline(pass);
            asm.mov(result, asm.b(true));
            asm.bindInline(fail);
            resolved = finishTemplate(asm, "instanceof-leaf<" + nonnull + ">");
        }
        {
            // unresolved instanceof
            unresolved = buildUnresolvedInstanceOf(nonnull);
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirPair buildInstanceofForInterface(boolean nonnull) {
        XirTemplate resolved;
        XirTemplate unresolved;
        {
            // resolved instanceof for interface
            XirOperand result = asm.restart(CiKind.Boolean);
            XirParameter object = asm.createInputParameter("object", CiKind.Object);
            XirParameter interfaceID = asm.createConstantInputParameter("interfaceID", CiKind.Int);
            XirParameter checkedHub = asm.createConstantInputParameter("checkedHub", CiKind.Object);
            XirOperand hub = asm.createTemp("hub", CiKind.Object);
            XirOperand mtableLength = asm.createTemp("mtableLength", CiKind.Int);
            XirOperand mtableStartIndex = asm.createTemp("mtableStartIndex", CiKind.Int);
            XirOperand a = asm.createTemp("a", CiKind.Int);
            XirLabel pass = asm.createInlineLabel("pass");
            XirLabel fail = asm.createInlineLabel("fail");
            asm.mov(result, asm.b(false));
            // XXX: use a cache to check the last successful receiver type
            if (!nonnull) {
                // first check for null
                asm.jeq(fail, object, asm.o(null));
            }
            asm.pload(CiKind.Object, hub, object, asm.i(hubOffset()), !nonnull);
            asm.jeq(pass, hub, checkedHub);
            asm.pload(CiKind.Int, mtableLength, hub, asm.i(offsetOfMTableLength()), false);
            asm.pload(CiKind.Int, mtableStartIndex, hub, asm.i(offsetOfMTableStartIndex()), false);
            asm.mod(a, interfaceID, mtableLength);
            asm.add(a, a, mtableStartIndex);
            asm.pload(CiKind.Int, a, hub, a, offsetOfFirstArrayElement(), Scale.Times4, false);
            asm.pload(CiKind.Int, a, hub, a, offsetOfFirstArrayElement(), Scale.fromInt(Word.size()), false);
            asm.jneq(fail, a, interfaceID);
            asm.bindInline(pass);
            asm.mov(result, asm.b(true));
            asm.bindInline(fail);
            resolved = finishTemplate(asm, "instanceof-interface<" + nonnull + ">");
        }
        {
            // unresolved instanceof
            unresolved = buildUnresolvedInstanceOf(nonnull);
        }
        return new XirPair(resolved, unresolved);
    }

    @HOSTED_ONLY
    private XirTemplate buildUnresolvedInstanceOf(boolean nonnull) {
        XirTemplate unresolved;
        XirOperand result = asm.restart(CiKind.Boolean);
        XirParameter object = asm.createInputParameter("object", CiKind.Object);
        XirParameter guard = asm.createInputParameter("guard", CiKind.Object);
        XirLabel fail = null;
        if (!nonnull) {
            // first check failed
            fail = asm.createInlineLabel("fail");
            asm.jeq(fail, object, asm.o(null));
        }
        callRuntimeThroughStub(asm, "unresolvedInstanceOf", result, object, guard);
        if (!nonnull) {
            // null check failed
            XirLabel pass = asm.createInlineLabel("pass");
            asm.jmp(pass);
            asm.bindInline(fail);
            asm.mov(result, asm.b(false));
            asm.bindInline(pass);
        }
        unresolved = finishTemplate(asm, "instanceof-unresolved<" + nonnull + ">");
        return unresolved;
    }

    @HOSTED_ONLY
    private XirTemplate buildExceptionObject() {
        XirOperand result = asm.restart(CiKind.Object);
        XirOperand latch = asm.createRegisterTemp("latch", CiKind.Word, AMD64.r14);
        XirOperand temp = asm.createTemp("temp", CiKind.Word);
        // Emit a safepoint
        asm.safepoint();
        asm.pload(CiKind.Word, latch, latch, true);
        // Load safepoints-enabled thread locals pointer
        asm.pload(CiKind.Word, temp, latch, asm.i(VmThreadLocal.ETLA.offset), false);
        // Load exception object from thread locals
        asm.pload(CiKind.Object, result, temp, asm.i(VmThreadLocal.EXCEPTION_OBJECT.offset), false);
        // Clear the exception object out of thread locals
        asm.pstore(CiKind.Word, temp, asm.i(VmThreadLocal.EXCEPTION_OBJECT.offset), asm.createConstant(CiConstant.ZERO), false);
        return finishTemplate(asm, "load-exception");
    }

    @HOSTED_ONLY
    private XirTemplate finishTemplate(CiXirAssembler asm, XirOperand result, String name) {
        final XirTemplate template = asm.finishTemplate(result, name);
        if (C1XOptions.PrintXirTemplates) {
            template.print(Log.out);
        }
        return template;
    }

    private XirTemplate finishTemplate(CiXirAssembler asm, String name) {
        final XirTemplate template = asm.finishTemplate(name);
        if (C1XOptions.PrintXirTemplates) {
            template.print(Log.out);
        }
        return template;
    }

    private void addWriteBarrier(CiXirAssembler asm, XirOperand object, XirOperand value) {
        // XXX: add write barrier mechanism
    }

    @HOSTED_ONLY
    private void callRuntimeThroughStub(CiXirAssembler asm, String method, XirOperand result, XirOperand... args) {
        XirTemplate stub = runtimeCallStubs.get(method);
        if (stub == null) {
            // search for the runtime call and create the stub
            for (Method m : RuntimeCalls.class.getDeclaredMethods()) {
                int flags = m.getModifiers();
                if (Modifier.isStatic(flags) && Modifier.isPublic(flags) && m.getName().equals(method)) {
                    // runtime call found. create a global stub that calls the runtime method
                    MethodActor methodActor = MethodActor.fromJava(m);
                    SignatureDescriptor signature = methodActor.descriptor();
                    if (result == null) {
                        assert signature.resultKind() == Kind.VOID;
                    } else {
                        CiKind ciKind = signature.resultKind().ciKind;
                        assert ciKind == result.kind : "return type mismatch in call to " + method;
                    }

                    assert signature.numberOfParameters() == args.length : "parameter mismatch in call to " + method;
                    CiXirAssembler stubAsm = asm.copy();
                    XirOperand resultVariable = stubAsm.restart(signature.resultKind().ciKind);

                    XirParameter[] rtArgs = new XirParameter[signature.numberOfParameters()];
                    for (int i = 0; i < signature.numberOfParameters(); i++) {
                        // create a parameter for each parameter to the runtime call
                        CiKind ciKind = signature.parameterDescriptorAt(i).toKind().ciKind;
                        assert ciKind == args[i].kind : "type mismatch in call to " + method;
                        rtArgs[i] = stubAsm.createInputParameter("rtArgs[" + i + "]", ciKind);
                    }
                    stubAsm.callRuntime(methodActor, resultVariable, rtArgs);
                    stub = stubAsm.finishStub("stub-" + method);

                    if (C1XOptions.PrintXirTemplates) {
                        stub.print(Log.out);
                    }
                    runtimeCallStubs.put(method, stub);
                }
            }

            stubs.add(stub);
        }
        if (stub == null) {
            throw ProgramError.unexpected("could not find runtime call: " + method);
        }
        asm.callStub(stub, result, args);
    }

    private void callRuntime(CiXirAssembler asm, String method, XirOperand result, XirOperand... args) {
        // TODO: make direct runtime calls work in XIR!
        RiMethod rtMethod = runtimeMethods.get(method);
        if (rtMethod == null) {
            // search for the runtime call and create the stub
            for (Method m : RuntimeCalls.class.getDeclaredMethods()) {
                int flags = m.getModifiers();
                if (Modifier.isStatic(flags) && Modifier.isPublic(flags) && m.getName().equals(method)) {
                    // runtime call found. create a global stub that calls the runtime method
                    MethodActor methodActor = MethodActor.fromJava(m);
                    rtMethod = methodActor;
                    runtimeMethods.put(method, rtMethod);
                }
            }
        }
        if (rtMethod == null) {
            throw ProgramError.unexpected("could not find runtime call: " + method);
        }
        asm.callRuntime(rtMethod, result, args);
    }

    public static class RuntimeCalls {
        public static ClassActor resolveClassActor(ResolutionGuard guard) {
            return ResolutionSnippet.ResolveClass.resolveClass(guard);
        }

        public static Class resolveClassObject(ResolutionGuard guard) {
            return ResolutionSnippet.ResolveClass.resolveClass(guard).javaClass();
        }

        public static Object resolveHub(ResolutionGuard guard) {
            return ResolutionSnippet.ResolveClass.resolveClass(guard).dynamicHub();
        }

        public static Object resolveNew(ResolutionGuard guard) {
            ClassActor classActor = ResolutionSnippet.ResolveClassForNew.resolveClassForNew(guard);
            classActor.makeInitialized();
            return classActor.dynamicHub();
        }

        public static Object resolveNewArray(ResolutionGuard guard) {
            return ResolutionSnippet.ResolveArrayClass.resolveArrayClass(guard).dynamicHub();
        }

        public static int resolveGetField(ResolutionGuard.InPool guard) {
            return ResolutionSnippet.ResolveInstanceFieldForReading.resolveInstanceFieldForReading(guard).offset();
        }

        public static int resolvePutField(ResolutionGuard.InPool guard) {
            return ResolutionSnippet.ResolveInstanceFieldForWriting.resolveInstanceFieldForWriting(guard).offset();
        }

        public static int resolveGetStatic(ResolutionGuard.InPool guard) {
            FieldActor fieldActor = ResolutionSnippet.ResolveStaticFieldForReading.resolveStaticFieldForReading(guard);
            fieldActor.holder().makeInitialized();
            return fieldActor.offset();
        }

        public static int resolvePutStatic(ResolutionGuard.InPool guard) {
            FieldActor fieldActor = ResolutionSnippet.ResolveStaticFieldForWriting.resolveStaticFieldForWriting(guard);
            fieldActor.holder().makeInitialized();
            return fieldActor.offset();
        }

        public static Object resolveStaticTuple(ResolutionGuard guard) {
            ClassActor classActor = ResolveClass.resolveClass(guard);
            classActor.makeInitialized();
            return classActor.staticTuple();
        }

        public static Word resolveStaticMethod(ResolutionGuard.InPool guard) {
            return CompilationScheme.Static.compile(ResolutionSnippet.ResolveStaticMethod.resolveStaticMethod(guard), CallEntryPoint.OPTIMIZED_ENTRY_POINT);
        }

        public static int resolveVirtualMethod(ResolutionGuard.InPool guard) {
            return ResolutionSnippet.ResolveVirtualMethod.resolveVirtualMethod(guard).vTableIndex() * Word.size() + vmConfig().layoutScheme().hybridLayout.headerSize();
        }

        public static Word resolveSpecialMethod(ResolutionGuard.InPool guard) {
            return CompilationScheme.Static.compile(ResolutionSnippet.ResolveSpecialMethod.resolveSpecialMethod(guard), CallEntryPoint.OPTIMIZED_ENTRY_POINT);
        }

        public static int resolveInterfaceMethod(ResolutionGuard.InPool guard) {
            return ResolutionSnippet.ResolveInterfaceMethod.resolveInterfaceMethod(guard).iIndexInInterface();
        }

        public static int resolveInterfaceID(ResolutionGuard.InPool guard) {
            return ResolutionSnippet.ResolveInterfaceMethod.resolveInterfaceMethod(guard).holder().id;
        }

        public static Object allocatePrimitiveArray(DynamicHub hub, int length) {
            if (length < 0) {
                throw new NegativeArraySizeException();
            }
            return Heap.createArray(hub, length);
        }

        public static Object allocateObjectArray(DynamicHub hub, int length) {
            if (length < 0) {
                throw new NegativeArraySizeException();
            }
            return Heap.createArray(hub, length);
        }

        public static Object allocateObject(DynamicHub hub) {
            return Heap.createTuple(hub);
        }

        public static Object allocateHybrid(DynamicHub hub) {
            return Heap.createHybrid(hub);
        }

        public static int[] allocateIntArray(int length) {
            return new int[length];
        }

        public static Object allocateMultiArray1(DynamicHub hub, int l1) {
            if (l1 < 0) {
                throw new NegativeArraySizeException();
            }
            return createArray(hub, l1);
        }

        public static Object allocateMultiArray2(DynamicHub hub1, int l1, int l2) {
            if (l1 < 0 | l2 < 0) {
                throw new NegativeArraySizeException();
            }
            Object[] result = UnsafeCast.asObjectArray(createObjectArray(hub1, l1));
            DynamicHub hub2 = UnsafeCast.asDynamicHub(hub1.componentHub);
            for (int i1 = 0; i1 < l1; i1++) {
                ArrayAccess.setObject(result, i1, createArray(hub2, l2));
            }
            return result;
        }

        public static Object allocateMultiArray3(DynamicHub hub1, int l1, int l2, int l3) {
            if (l1 < 0 | l2 < 0 | l3 < 0) {
                throw new NegativeArraySizeException();
            }
            Object[] result = UnsafeCast.asObjectArray(createObjectArray(hub1, l1));
            DynamicHub hub2 = UnsafeCast.asDynamicHub(hub1.componentHub);
            DynamicHub hub3 = UnsafeCast.asDynamicHub(hub2.componentHub);
            for (int i1 = 0; i1 < l1; i1++) {
                Object[] result2 = createObjectArray(hub2, l2);
                safeArrayStore(result, i1, result2);
                for (int i2 = 0; i2 < l2; i2++) {
                    safeArrayStore(result2, i2, createArray(hub3, l3));
                }
            }
            return result;
        }

        public static Object allocateMultiArrayN(ResolutionGuard guard, int[] lengths) {
            for (int length : lengths) {
                if (length < 0) {
                    Throw.negativeArraySizeException(length);
                }
            }
            ClassActor actor = ResolutionSnippet.ResolveClass.resolveClass(guard);
            return recursiveNewMultiArray(0, actor, lengths);
        }

        private static Object recursiveNewMultiArray(int index, ClassActor arrayClassActor, int[] lengths) {
            final int length = lengths[index];
            final Object result = createArray(arrayClassActor.dynamicHub(), length);
            if (length > 0) {
                final int nextIndex = index + 1;
                if (nextIndex < lengths.length) {
                    Object[] array = (Object[]) result;
                    final ClassActor subArrayClassActor = arrayClassActor.componentClassActor();
                    for (int i = 0; i < length; i++) {
                        safeArrayStore(array, i, recursiveNewMultiArray(nextIndex, subArrayClassActor, lengths));
                    }
                }
            }
            return result;
        }

        @INLINE
        private static Object[] createObjectArray(DynamicHub hub, int length) {
            if (MaxineVM.isHosted()) {
                return (Object[]) Array.newInstance(hub.classActor.componentClassActor().toJava(), length);
            }
            return UnsafeCast.asObjectArray(Heap.createArray(hub, length));
        }

        @INLINE
        private static Object createArray(DynamicHub hub, int length) {
            if (MaxineVM.isHosted()) {
                return Array.newInstance(hub.classActor.componentClassActor().toJava(), length);
            }
            return Heap.createArray(hub, length);
        }

        @INLINE
        private static void safeArrayStore(Object[] array, int index, Object val) {
            if (MaxineVM.isHosted()) {
                array[index] = val;
            } else {
                ArrayAccess.setObject(array, index, val);
            }
        }

        public static void unresolvedCheckcast(Object object, ResolutionGuard guard) {
            final ClassActor classActor = ResolutionSnippet.ResolveClass.resolveClass(guard);
            if (!ObjectAccess.readHub(object).isSubClassHub(classActor)) {
                Throw.classCastException(classActor, object);
            }
        }

        public static boolean unresolvedInstanceOf(Object object, ResolutionGuard guard) {
            final ClassActor classActor = ResolutionSnippet.ResolveClass.resolveClass(guard);
            return ObjectAccess.readHub(object).isSubClassHub(classActor);
        }

        public static void arrayHubStoreCheck(DynamicHub componentHub, DynamicHub valueHub) {
            if (!valueHub.isSubClassHub(componentHub.classActor)) {
                throw new ArrayStoreException(componentHub.classActor + " is not assignable to " + componentHub.classActor);
            }
        }

        public static void throwClassCastException(DynamicHub hub, Object object) {
            Throw.classCastException(hub.classActor, object);
        }

        public static void throwNullPointerException() {
            throw new NullPointerException();
        }

        public static void throwArrayIndexOutOfBoundsException(Object array, int index) {
            Throw.arrayIndexOutOfBoundsException(array, index);
        }

        public static void monitorEnter(Object o) {
            vmConfig().monitorScheme().monitorEnter(o);
        }

        public static void monitorExit(Object o) {
            vmConfig().monitorScheme().monitorExit(o);
        }
    }

    @Override
    public XirSnippet genIntrinsic(XirSite site, XirArgument[] arguments, RiMethod method) {
        return null;
    }
}