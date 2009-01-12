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
package com.sun.max.vm.compiler.target;

import com.sun.max.asm.*;
import com.sun.max.program.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.eir.*;
import com.sun.max.vm.compiler.ir.*;

public abstract class TargetGenerator extends IrGenerator<DynamicCompilerScheme, TargetMethod> {

    private final InstructionSet _instructionSet;

    public InstructionSet instructionSet() {
        return _instructionSet;
    }

    public TargetGenerator(DynamicCompilerScheme dynamicCompilerScheme, InstructionSet instructionSet) {
        super(dynamicCompilerScheme, instructionSet.name());
        _instructionSet = instructionSet;
    }

    /**
     * ATTENTION: override this method in the template-generating optimizing compiler.
     *
     * @return whether any of the given method's parameters would be passed on the stack
     */
    public boolean hasStackParameters(ClassMethodActor classMethodActor) {
        return true; // err on the "safe side", assume some parameters don't make it into a register
    }

    public TargetMethod makeIrMethod(EirMethod eirMethod) {
        ProgramError.unexpected();
        return null;
    }
}
