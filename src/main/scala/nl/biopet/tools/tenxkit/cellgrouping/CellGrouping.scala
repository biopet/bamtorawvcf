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

package nl.biopet.tools.tenxkit.cellgrouping

import java.io.{File, PrintWriter}

import nl.biopet.tools.tenxkit
import nl.biopet.tools.tenxkit.variantcalls
import nl.biopet.tools.tenxkit.variantcalls.{CellVariantcaller, VariantCall}
import nl.biopet.utils.ngs.{bam, fasta, vcf}
import nl.biopet.utils.ngs.intervals.BedRecordList
import nl.biopet.utils.tool.{AbstractOptParser, ToolCommand}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import org.apache.spark.sql.functions.broadcast

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global

object CellGrouping extends ToolCommand[Args] {
  def argsParser: AbstractOptParser[Args] = new ArgsParser(this)
  def emptyArgs: Args = Args()

  def main(args: Array[String]): Unit = {
    val cmdArgs = cmdArrayToArgs(args)
    logger.info("Start")

    val sparkConf: SparkConf =
      new SparkConf(true).setMaster(cmdArgs.sparkMaster)
    implicit val sparkSession: SparkSession =
      SparkSession.builder().config(sparkConf).getOrCreate()
    import sparkSession.implicits._
    implicit val sc: SparkContext = sparkSession.sparkContext
    logger.info(
      s"Context is up, see ${sparkSession.sparkContext.uiWebUrl.getOrElse("")}")

    val correctCells = tenxkit.parseCorrectCells(cmdArgs.correctCells)
    val correctCellsMap = tenxkit.correctCellsMap(correctCells)

    val futures: ListBuffer[Future[Any]] = ListBuffer()

    val variants: RDD[VariantCall] = {
      val v = cmdArgs.inputFile.getName match {
        case name if name.endsWith(".bam") =>
          val result = readBamFile(cmdArgs, correctCells, correctCellsMap)
          futures += result.totalFuture
          Await.result(result.filteredVariants, Duration.Inf)
        case name if name.endsWith(".vcf") || name.endsWith(".vcf.gz") =>
          readVcfFile(cmdArgs, correctCellsMap)
        case _ =>
          throw new IllegalArgumentException(
            "Input file must be a bam or vcf file")
      }
      v.filter(_.totalAltRatio >= cmdArgs.minTotalAltRatio)
        .repartition(v.partitions.length)
        .cache()
    }

    val combinations = variants
      .flatMap { variant =>
        val samples = variant.samples
          .filter(_._2.map(_.total).sum > cmdArgs.minAlleleCoverage)
          .keys
        for (s1 <- samples; s2 <- samples if s2 > s1) yield {
          SampleCombinationKey(s1, s2) -> SampleCombinationValue()
        }
      }
      .groupByKey()

    val counts = combinations.map(x => x._1 -> x._2.size)
    val f = counts
      .groupBy(_._1.sample1)
      .map {
        case (s1, list) =>
          val map = list.groupBy(_._1.sample2).map(x => x._1 -> x._2.head._2)
          s1 -> (for (s2 <- correctCells.value.indices.toArray) yield {
            map.get(s2)
          })
      }
      .repartition(1)
    f.foreachPartition { it =>
      val map = it.toMap
      val writer =
        new PrintWriter(new File(cmdArgs.outputDir, "count.positions.csv"))
      writer.println(correctCells.value.mkString("Sample\t", "\t", ""))
      for (s1 <- correctCells.value.indices) {
        writer.print(s"${correctCells.value(s1)}\t")
        writer.println(
          correctCells.value.indices
            .map(s2 => map.get(s1).flatMap(_.lift(s2)))
            .map(x => x.flatten.getOrElse("."))
            .mkString("\t"))
      }
      writer.close()
    }

    def sufixColumns(df: DataFrame, sufix: String): DataFrame = {
      df.columns.foldLeft(df)((a, b) => a.withColumnRenamed(b, b + sufix))
    }

    //TODO: Grouping

    Await.result(Future.sequence(futures), Duration.Inf)

    logger.info("Done")
  }

  def readVcfFile(cmdArgs: Args, sampleMap: Broadcast[Map[String, Int]])(
      implicit sc: SparkContext): RDD[VariantCall] = {
    val dict = sc.broadcast(fasta.getCachedDict(cmdArgs.reference))
    val regions =
      BedRecordList.fromReference(cmdArgs.reference).scatter(500000)
    sc.parallelize(regions, regions.size).mapPartitions { it =>
      it.flatMap { list =>
        vcf
          .loadRegions(cmdArgs.inputFile, list.iterator)
          .map(VariantCall.fromVariantContext(_, dict.value, sampleMap.value))
      }
    }
  }

  def readBamFile(cmdArgs: Args,
                  correctCells: Broadcast[Array[String]],
                  correctCellsMap: Broadcast[Map[String, Int]])(
      implicit sc: SparkContext): CellVariantcaller.Result = {
    logger.info(s"Starting variant calling on '${cmdArgs.inputFile}'")
    logger.info(
      s"Using default parameters, to set different cutoffs please use the CellVariantcaller module")
    val dict = sc.broadcast(bam.getDictFromBam(cmdArgs.inputFile))
    val partitions = {
      val x = (cmdArgs.inputFile.length() / 10000000).toInt
      if (x > 0) x else 1
    }
    val cutoffs = sc.broadcast(variantcalls.Cutoffs())
    val result = CellVariantcaller.totalRun(
      cmdArgs.inputFile,
      cmdArgs.outputDir,
      cmdArgs.reference,
      dict,
      partitions,
      cmdArgs.intervals,
      cmdArgs.sampleTag,
      cmdArgs.umiTag,
      correctCells,
      correctCellsMap,
      cutoffs,
      variantcalls.Args().seqError
    )

    result
  }

  def descriptionText: String =
    """
      |
    """.stripMargin

  def manualText: String =
    """
      |
    """.stripMargin

  def exampleText: String =
    """
      |
    """.stripMargin

}
