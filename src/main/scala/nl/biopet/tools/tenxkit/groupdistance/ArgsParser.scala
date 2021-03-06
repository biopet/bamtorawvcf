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

package nl.biopet.tools.tenxkit.groupdistance

import java.io.File

import nl.biopet.utils.tool.{AbstractOptParser, ToolCommand}

class ArgsParser(toolCommand: ToolCommand[Args])
    extends AbstractOptParser[Args](toolCommand) {
  opt[File]('d', "distanceMatrix")
    .required()
    .action((x, c) => c.copy(distanceMatrix = x))
    .text("Distance matrix")
  opt[File]("countMatrix")
    .action((x, c) => c.copy(countMatrix = Some(x)))
    .text("Position count matrix")
  opt[File]('o', "outputDir")
    .required()
    .action((x, c) => c.copy(outputDir = x))
    .text("Output directory")
  opt[File]("correctCells")
    .required()
    .action((x, c) => c.copy(correctCells = x))
    .text("List of correct cells")
  opt[Int]("numClusters")
    .required()
    .action((x, c) => c.copy(numClusters = x))
    .text("Number of expected clusters")
  opt[Int]("numIterations")
    .action((x, c) => c.copy(numIterations = x))
    .text(s"Number of maximum iterations, default ${Args().numIterations}")
  opt[Unit]("skipKmeans")
    .action((_, c) => c.copy(skipKmeans = true))
    .text(s"Skip kmeans machine learning step")
  opt[Long]("seed")
    .action((x, c) => c.copy(seed = x))
    .text(s"Random seed, default ${Args().seed}")
  opt[String]("sparkMaster")
    .required()
    .action((x, c) => c.copy(sparkMaster = x))
    .text("Spark master")
}
