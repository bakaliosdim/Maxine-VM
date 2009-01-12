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

package com.sun.max.asm.gen.risc.arm;

import com.sun.max.asm.*;
import com.sun.max.asm.arm.complete.*;
import com.sun.max.asm.dis.*;
import com.sun.max.asm.dis.arm.*;
import com.sun.max.asm.gen.risc.*;
import com.sun.max.collect.*;


/**
 * The program entry point for the ARM assembler generator.
 *
 * @author Sumeet Panchal
 */

public final class ARMAssemblerGenerator extends RiscAssemblerGenerator<ARMTemplate> {

    private ARMAssemblerGenerator() {
        super(ARMAssembly.ASSEMBLY);
    }

    @Override
    protected String getJavadocManualReference(ARMTemplate template) {
        final String section = template.instructionDescription().architectureManualSection();
        /*if (section.indexOf("[Book ") == -1) {
            section += " [Book 1]";
        }*/
        return "\"ARM Architecture Reference Manual, Second Edition - Section " + section + "\"";
    }

    public static void main(String[] programArguments) {
        final ARMAssemblerGenerator generator = new ARMAssemblerGenerator();
        generator._options.parseArguments(programArguments);
        generator.generate();
    }

    @Override
    protected DisassembledInstruction generateExampleInstruction(ARMTemplate template, IndexedSequence<Argument> arguments) throws AssemblyException {
        final ARMAssembler assembler = new ARMAssembler(0);
        assembly().assemble(assembler, template, arguments);
        final byte[] bytes = assembler.toByteArray();
        return new ARMDisassembledInstruction(new ARMDisassembler(0, null), 0, bytes, template, arguments);
    }

}
