/*
 * Copyright (c) 2017, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
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
 */

package com.sun.max.asm.dis.arm;

import java.util.*;

import com.sun.max.asm.*;
import com.sun.max.asm.arm.complete.*;
import com.sun.max.asm.dis.*;
import com.sun.max.asm.dis.risc.*;
import com.sun.max.asm.gen.*;
import com.sun.max.asm.gen.risc.*;
import com.sun.max.asm.gen.risc.arm.*;
import com.sun.max.lang.*;

/**
 */
public class ARMDisassembler extends RiscDisassembler{

    public ARMDisassembler(int startAddress, InlineDataDecoder inlineDataDecoder) {
        super(new Immediate32Argument(startAddress), ARMAssembly.ASSEMBLY, Endianness.LITTLE, inlineDataDecoder);
    }

    @Override
    protected Assembler createAssembler(int position) {
        return new ARMAssembler((int) startAddress().asLong() + position);
    }

    @Override
    protected DisassembledInstruction createDisassembledInstruction(int position, byte[] bytes, RiscTemplate template, List<Argument> arguments) {
        return new DisassembledInstruction(this, position, bytes, template, arguments);
    }
}
