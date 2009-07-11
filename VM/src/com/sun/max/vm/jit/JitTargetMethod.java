/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
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
package com.sun.max.vm.jit;

import java.util.*;

import com.sun.max.annotate.*;
import com.sun.max.atomic.*;
import com.sun.max.collect.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.bytecode.*;
import com.sun.max.vm.bytecode.refmaps.*;
import com.sun.max.vm.collect.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.builtin.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.stack.*;

/**
 * A target method generated by the JIT.
 *
 * @author Laurent Daynes
 * @author Bernd Mathiske
 * @author Doug Simon
 */
public abstract class JitTargetMethod extends TargetMethod {

    private int adapterReturnPosition;
    private int optimizedCallerAdapterFrameCodeSize;
    @INSPECTED
    private BytecodeInfo[] bytecodeInfos;
    private int frameReferenceMapOffset;
    private final AtomicReference referenceMapEditor = new AtomicReference();

    /**
     * The preserves the stack frame layout object from {@link #referenceMapEditor} when the latter is cleared in {@link #finalizeReferenceMaps()}.
     * The stack frame layout object is required by {@link StackReferenceMapPreparer#prepareTrampolineFrameForJITCaller}.
     */
    private JitStackFrameLayout stackFrameLayout;

    /**
     * A bit map denoting which {@linkplain #directCallees() direct calls} in this target method correspond to calls
     * into the runtime derived from the constituent templates. These calls are
     * {@linkplain #linkDirectCalls(DynamicCompilerScheme) linked} using the entry point associated with the compiler
     * used to compile the runtime (i.e the opto compiler). All other direct calls are linked using the call entry point
     * associated with the JIT compiler.
     */
    private byte[] isDirectCallToRuntime;

    /**
     * An {@code int} array that encodes a mapping from bytecode positions to target code positions. A non-zero value
     * {@code val} at index {@code i} in the array encodes that there is a bytecode instruction whose opcode is at index
     * {@code i} in the bytecode array and whose target code position is {@code val}. Unless {@code i} is equal to the
     * length of the bytecode array in which case {@code val} denotes the target code position one byte past the
     * last target code byte emitted for the last bytecode instruction.
     */
    @INSPECTED
    private int[] bytecodeToTargetCodePositionMap;


    protected JitTargetMethod(ClassMethodActor classMethodActor) {
        super(classMethodActor);
    }

    @Override
    public DynamicCompilerScheme compilerScheme() {
        return MaxineVM.hostOrTarget().configuration().jitScheme();
    }

    /**
     * The size of the adapter frame code found at the {@linkplain CallEntryPoint#OPTIMIZED_ENTRY_POINT entry point} for
     * a call from a method compiled with the optimizing compiler.
     */
    public int optimizedCallerAdapterFrameCodeSize() {
        return optimizedCallerAdapterFrameCodeSize;
    }

    /**
     * @return the code position to which the JIT method returns in its optimized-to-JIT adapter code or -1 if there is no adapter.
     */
    public int adapterReturnPosition() {
        return adapterReturnPosition;
    }

    public boolean isDirectCallToRuntime(int stopIndex) {
        return isDirectCallToRuntime != null && (stopIndex < numberOfDirectCalls()) && ByteArrayBitMap.isSet(isDirectCallToRuntime, 0, isDirectCallToRuntime.length, stopIndex);
    }

    @Override
    protected CallEntryPoint callEntryPointForDirectCall(int directCallIndex) {
        if (isDirectCallToRuntime != null && ByteArrayBitMap.isSet(isDirectCallToRuntime, 0, isDirectCallToRuntime.length, directCallIndex)) {
            return CallEntryPoint.OPTIMIZED_ENTRY_POINT;
        }
        return super.callEntryPointForDirectCall(directCallIndex);
    }

    @PROTOTYPE_ONLY
    @Override
    protected boolean isDirectCalleeInPrologue(int directCalleeIndex) {
        return stopPosition(directCalleeIndex) < targetCodePositionFor(0);
    }

    public int targetCodePositionFor(int bytecodePosition) {
        return bytecodeToTargetCodePositionMap[bytecodePosition];
    }

    @Override
    public Iterator<? extends BytecodeLocation> getBytecodeLocationsFor(Pointer instructionPointer) {
        final BytecodeLocation bytecodeLocation = new BytecodeLocation(classMethodActor(), bytecodePositionFor(instructionPointer.asPointer()));
        return Iterators.iterator(new BytecodeLocation[] {bytecodeLocation});
    }

    @Override
    public BytecodeLocation getBytecodeLocationFor(Pointer instructionPointer) {
        return new BytecodeLocation(classMethodActor(), bytecodePositionFor(instructionPointer.asPointer()));
    }

    /**
     * Gets the bytecode position for a machine code instruction address.
     *
     * @param instructionPointer
     *                an instruction pointer that may denote an instruction in this target method
     * @return the start position of the bytecode instruction that is implemented at the instruction pointer or -1 if
     *         {@code instructionPointer} denotes an instruction that does not correlate to any bytecode. This will be
     *         the case when {@code instructionPointer} is not in this target method or is in the adapter frame stub
     *         code, prologue or epilogue.
     */
    public int bytecodePositionFor(Pointer instructionPointer) {
        assert bytecodeToTargetCodePositionMap != null;
        assert bytecodeToTargetCodePositionMap.length > 0;
        final int targetCodePosition = targetCodePositionFor(instructionPointer);
        return bytecodePositionFor(targetCodePosition);
    }

    /**
     * This method is guaranteed not to perform allocation.
     */
    @Override
    public final JitStackFrameLayout stackFrameLayout() {
        final JitReferenceMapEditor refMapEditor = (JitReferenceMapEditor) referenceMapEditor.get();
        if (refMapEditor != null) {
            return refMapEditor.stackFrameLayout();
        }
        FatalError.check(stackFrameLayout != null, "Cannot get JIT stack frame layout for incomplete JIT method");
        return stackFrameLayout;
    }

    /**
     * Gets the bytecode position for a target code position in this JIT target method.
     *
     * @param targetCodePosition
     *                a target code position that may denote an instruction in this target method that correlates with a
     *                bytecode
     * @return the start position of the bytecode instruction that is implemented at {@code targetCodePosition} or -1 if
     *         {@code targetCodePosition} is outside the range(s) of target code positions in this target method that
     *         correlate with a bytecode.
     */
    public int bytecodePositionFor(int targetCodePosition) {
        assert bytecodeToTargetCodePositionMap != null;
        assert bytecodeToTargetCodePositionMap.length > 0;
        int bytecodePosition;
        if (targetCodePosition >= targetCodePositionFor(0)) {
            bytecodePosition = -1;
            for (int i = 0; i != bytecodeToTargetCodePositionMap.length; i++) {
                if (targetCodePositionFor(i) > targetCodePosition) {
                    // For now just ensure we are to the left from a bytecode that is too far to the right:
                    bytecodePosition = i - 1;
                    break;
                }
            }
            assert bytecodePosition >= 0;

            // We are just left of the leftmost bytecode that is too far right.
            // Find the start of the bytecode instruction we are in:
            while (bytecodePosition >= 0 && bytecodeToTargetCodePositionMap[bytecodePosition] == 0) {
                bytecodePosition--;
            }
            return bytecodePosition;
        }
        // The instruction pointer denotes a position in the adapter frame code or the prologue
        return -1;
    }

    /**
     * Correlates a bytecode range with a target code range. The target code range is typically the template code
     * produced by the JIT compiler for a single JVM instruction encoded in the bytecode range.
     *
     * @author Doug Simon
     */
    public static class CodeTranslation {

        private final int bytecodePosition;
        private final int bytecodeLength;
        private final int targetCodePosition;
        private final int targetCodeLength;

        /**
         * Creates an object that correlates a bytecode range with a target code range.
         *
         * @param bytecodePosition the first position in the bytecode range. This value is invalid if
         *            {@code bytecodeLength == 0}.
         * @param bytecodeLength the length of the bytecode range
         * @param targetCodePosition the first position in the target code range. This value is invalid if
         *            {@code targetCodeLength == 0}.
         * @param targetCodeLength the length of the target code range
         */
        public CodeTranslation(int bytecodePosition, int bytecodeLength, int targetCodePosition, int targetCodeLength) {
            this.bytecodeLength = bytecodeLength;
            this.bytecodePosition = bytecodePosition;
            this.targetCodeLength = targetCodeLength;
            this.targetCodePosition = targetCodePosition;
        }

        /**
         * Gets the first position in the bytecode range represented by this object. This value is only valid if
         * {@link #bytecodeLength()} does not return 0.
         */
        public int bytecodePosition() {
            return bytecodePosition;
        }

        /**
         * Gets the position one past the last position in the bytecode range represented by this object. This value is
         * only valid if {@link #bytecodeLength()} does not return 0.
         */
        public int bytecodeEndPosition() {
            return bytecodePosition + bytecodeLength;
        }

        /**
         * Gets the length of the bytecode range represented by this object.
         */
        public int bytecodeLength() {
            return bytecodeLength;
        }

        /**
         * Gets the first position in the target code range represented by this object. This value is only valid if
         * {@link #targetCodeLength()} does not return 0.
         */
        public int targetCodePosition() {
            return targetCodePosition;
        }

        /**
         * Gets the position one past the last position in the target code range represented by this object. This value is only valid if
         * {@link #targetCodeLength()} does not return 0.
         */
        public int targetCodeEndPosition() {
            return targetCodePosition + targetCodeLength;
        }

        /**
         * Gets the length of the target code range represented by this object.
         */
        public int targetCodeLength() {
            return targetCodeLength;
        }

        /**
         * Gets an object encapsulating the sub-range of a given bytecode array represented by this code translation.
         *
         * @param bytecode
         * @return null if {@code bytecodeLength() == 0}
         */
        public BytecodeBlock toBytecodeBlock(byte[] bytecode) {
            if (bytecodeLength() == 0) {
                return null;
            }
            return new BytecodeBlock(bytecode, bytecodePosition(), bytecodeEndPosition() - 1);
        }

        @Override
        public String toString() {
            final String bytecode = bytecodeLength == 0 ? "[]" : "[" + bytecodePosition + " - " + (bytecodeEndPosition() - 1) + "]";
            final String targetCode = targetCodeLength == 0 ? "[]" : "[" + targetCodePosition + " - " + (targetCodeEndPosition() - 1) + "]";
            return bytecode + " -> " + targetCode;
        }
    }

    /**
     * Gets a sequence of objects correlating bytecode ranges with the ranges of target code in this target method. The
     * returned sequence objects are exclusive of each other in terms of their target code ranges and they cover
     * every target code position in this target method.
     */
    public Sequence<CodeTranslation> codeTranslations() {
        final AppendableSequence<CodeTranslation> translations = new ArrayListSequence<CodeTranslation>();
        int startBytecodePosition = 0;
        int startTargetCodePosition = bytecodeToTargetCodePositionMap[0];
        assert startTargetCodePosition != 0;
        translations.append(new CodeTranslation(0, 0, 0, startTargetCodePosition));
        for (int bytecodePosition = 1; bytecodePosition != bytecodeToTargetCodePositionMap.length; ++bytecodePosition) {
            final int targetCodePosition = bytecodeToTargetCodePositionMap[bytecodePosition];
            if (targetCodePosition != 0) {
                final CodeTranslation codeTranslation = new CodeTranslation(startBytecodePosition, bytecodePosition - startBytecodePosition, startTargetCodePosition, targetCodePosition - startTargetCodePosition);
                translations.append(codeTranslation);
                startTargetCodePosition = targetCodePosition;
                startBytecodePosition = bytecodePosition;
            }
        }
        if (startTargetCodePosition < code().length) {
            translations.append(new CodeTranslation(0, 0, startTargetCodePosition, code().length - startTargetCodePosition));
        }
        return translations;
    }

    /**
     * @return references to the emitted templates or to byte codes in corresponding order to the above
     */
    public final BytecodeInfo[] bytecodeInfos() {
        return bytecodeInfos;
    }

    public final void setGenerated(TargetBundle targetBundle,
                    int[] catchRangePositions,
                    int[] catchBlockPositions,
                    int[] stopPositions,
                    BytecodeStopsIterator bytecodeStopsIterator,
                    byte[] compressedJavaFrameDescriptors,
                    ClassMethodActor[] directCallees,
                    int numberOfIndirectCalls,
                    int numberOfSafepoints,
                    byte[] referenceMaps,
                    byte[] scalarLiteralBytes,
                    Object[] referenceLiterals,
                    Object codeOrCodeBuffer,
                    int optimizedCallerAdapterFrameCodeSize,
                    int adapterReturnPosition,
                    byte[] encodedInlineDataDescriptors,
                    ByteArrayBitMap isDirectRuntimeCall,
                    int[] bytecodeToTargetCodePositionMap,
                    BytecodeInfo[] bytecodeInfos,
                    int numberOfBlocks,
                    boolean[] blockStarts,
                    JitStackFrameLayout jitStackFrameLayout, TargetABI abi) {
        setGenerated(
            targetBundle,
            catchRangePositions,
            catchBlockPositions,
            stopPositions,
            compressedJavaFrameDescriptors,
            directCallees,
            numberOfIndirectCalls,
            numberOfSafepoints,
            0,
            referenceMaps,
            scalarLiteralBytes,
            referenceLiterals,
            codeOrCodeBuffer,
            encodedInlineDataDescriptors,
            jitStackFrameLayout.frameSize(),
            jitStackFrameLayout.frameReferenceMapSize(),
            abi,
            -1);
        this.isDirectCallToRuntime = isDirectRuntimeCall == null ? null : isDirectRuntimeCall.bytes();
        this.bytecodeToTargetCodePositionMap = bytecodeToTargetCodePositionMap;
        this.bytecodeInfos = bytecodeInfos;
        this.frameReferenceMapOffset = jitStackFrameLayout.frameReferenceMapOffset();
        this.optimizedCallerAdapterFrameCodeSize = optimizedCallerAdapterFrameCodeSize;
        this.adapterReturnPosition = adapterReturnPosition;
        if (stopPositions != null) {
            final JitReferenceMapEditor referenceMapEditor = new JitReferenceMapEditor(this, numberOfBlocks, blockStarts, bytecodeStopsIterator, jitStackFrameLayout);
            this.referenceMapEditor.set(referenceMapEditor);
            final ReferenceMapInterpreter interpreter = ReferenceMapInterpreter.from(referenceMapEditor.blockFrames());
            if (interpreter.performsAllocation() || MaxineVM.isPrototyping()) {
                // if computing the reference map requires allocation or if prototyping,
                // compute the reference map now
                finalizeReferenceMaps();
            }
        }
    }

    /**
     * Ensures that the {@linkplain #referenceMaps() reference maps} for this JIT target method are finalized. Only
     * finalized reference maps are guaranteed to never change for the remaining lifetime of this target method.
     *
     * Although this method may be called multiple threads, it cannot use standard synchronization as that may block
     * one of the threads in native code on a mutex. This would incorrectly be interpreted by the GC as meaning
     * the mutator thread has blocked for GC after taking a safepoint trap. To avoid blocking in native code,
     * a spin loop is used instead.
     *
     * If this method is called while preparing the stack reference map for a thread that has taken a safepoint
     * for GC, then safepoints are currently disabled and so there is no need to use the {@link NO_SAFEPOINTS}
     * annotation on this method.
     */
    public void finalizeReferenceMaps() {
        final JitReferenceMapEditor referenceMapEditor = (JitReferenceMapEditor) this.referenceMapEditor.get();
        if (referenceMapEditor != null) {
            final Object result = this.referenceMapEditor.compareAndSwap(referenceMapEditor, JitReferenceMapEditor.SENTINEL);
            if (result == JitReferenceMapEditor.SENTINEL) {
                while (this.referenceMapEditor.get() != null) {
                    SpecialBuiltin.pause();
                }
            } else if (result != null) {
                referenceMapEditor.fillInMaps(bytecodeToTargetCodePositionMap);
                stackFrameLayout = referenceMapEditor.stackFrameLayout();
                this.referenceMapEditor.set(null);
            }
        }
    }

    @Override
    public boolean areReferenceMapsFinalized() {
        return referenceMapEditor == null;
    }

    @Override
    public boolean prepareFrameReferenceMap(StackReferenceMapPreparer stackReferenceMapPreparer, Pointer instructionPointer, Pointer stackPointer, Pointer framePointer) {
        finalizeReferenceMaps();
        return stackReferenceMapPreparer.prepareFrameReferenceMap(this, instructionPointer, framePointer.plus(frameReferenceMapOffset), stackPointer);
    }

    public Pointer getFramePointer(Pointer cpuStackPointer, Pointer cpuFramePointer, Pointer osSignalIntegerRegisters) {
        return cpuFramePointer;
    }
}
