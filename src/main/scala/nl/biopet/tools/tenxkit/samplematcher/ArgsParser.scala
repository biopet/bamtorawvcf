/*
 * Copyright (c) 2018 Biopet
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package nl.biopet.tools.tenxkit.samplematcher

import java.io.File

import nl.biopet.utils.tool.{AbstractOptParser, ToolCommand}

class ArgsParser(toolCommand: ToolCommand[Args])
    extends AbstractOptParser[Args](toolCommand) {
  opt[File]('i', "inputFile")
    .required()
    .action((x, c) => c.copy(inputFile = x))
    .text("Input bam file")
  opt[File]('o', "outputDir")
    .required()
    .action((x, c) => c.copy(outputDir = x))
    .text("Output directory")
  opt[File]('R', "reference")
    .required()
    .action((x, c) => c.copy(reference = x))
    .text("Reference fasta file")
  opt[File]("correctCells")
    .required()
    .action((x, c) => c.copy(correctCellsFile = x))
    .text("List of correct cells")
  opt[Int]('c', "expectedSamples")
    .required()
    .action((x, c) => c.copy(expectedSamples = x))
    .text("Number of expected samples")
  opt[String]("sparkMaster")
    .required()
    .action((x, c) => c.copy(sparkMaster = x))
    .text("Spark master")
}