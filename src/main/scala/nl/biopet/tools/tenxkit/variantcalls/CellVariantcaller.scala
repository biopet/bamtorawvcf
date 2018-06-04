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

package nl.biopet.tools.tenxkit.variantcalls

import java.io.File

import htsjdk.samtools._
import htsjdk.samtools.reference.IndexedFastaSequenceFile
import nl.biopet.tools.tenxkit
import nl.biopet.tools.tenxkit.{TenxKit, VariantCall}
import nl.biopet.utils.ngs.bam
import nl.biopet.utils.ngs.intervals.BedRecord
import nl.biopet.utils.tool.{AbstractOptParser, ToolCommand}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.{SparkConf, SparkContext}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.collection.JavaConversions._

object CellVariantcaller extends ToolCommand[Args] {
  def argsParser: AbstractOptParser[Args] = new ArgsParser(this)
  def emptyArgs: Args = Args()

  def main(args: Array[String]): Unit = {
    val cmdArgs = cmdArrayToArgs(args)

    logger.info("Start")

    val sparkConf: SparkConf =
      new SparkConf(true).setMaster(cmdArgs.sparkMaster)
    implicit val sparkSession: SparkSession =
      SparkSession.builder().config(sparkConf).getOrCreate()
    implicit val sc: SparkContext = sparkSession.sparkContext
    logger.info(
      s"Context is up, see ${sparkSession.sparkContext.uiWebUrl.getOrElse("")}")

    val dict = sc.broadcast(bam.getDictFromBam(cmdArgs.inputFile))

    val correctCells = tenxkit.parseCorrectCells(cmdArgs.correctCells)
    val correctCellsMap = tenxkit.correctCellsMap(correctCells)
    val cutoffs = sc.broadcast(cmdArgs.cutoffs)

    val result = totalRun(
      cmdArgs.inputFile,
      cmdArgs.outputDir,
      cmdArgs.reference,
      dict,
      getPartitions(cmdArgs.inputFile, cmdArgs.partitions),
      cmdArgs.intervals,
      cmdArgs.sampleTag,
      cmdArgs.umiTag,
      correctCells,
      correctCellsMap,
      cutoffs,
      cmdArgs.seqError,
      writeRawVcf = true
    )

    Await.result(result.totalFuture, Duration.Inf)

    sparkSession.stop()
    logger.info("Done")
  }

  def getPartitions(inputFile: File,
                    partitions: Option[Int] = None,
                    fileSizePerPartition: Int = 10000000): Int = {
    val x =
      partitions.getOrElse((inputFile.length() / fileSizePerPartition).toInt)
    if (x > 0) x else 1
  }

  case class Result(contigs: Map[String, ContigResult],
                    writeFilteredFuture: Option[Future[Unit]],
                    writeRawFuture: Option[Future[Unit]]) {
    def totalFuture: Future[List[Unit]] =
      Future.sequence(writeFilteredFuture.toList ::: writeRawFuture.toList)

    def allVariants()(implicit sc: SparkContext): RDD[VariantCall] =
      sc.union(contigs.map(_._2.allVariants).toSeq)
    def filteredVariants()(implicit sc: SparkContext): RDD[VariantCall] =
      sc.union(contigs.map(_._2.filteredVariants).toSeq)

  }

  case class ContigResult(filteredVariants: RDD[VariantCall],
                          allVariants: RDD[VariantCall])

  def totalRun(
      inputFile: File,
      outputDir: File,
      reference: File,
      dict: Broadcast[SAMSequenceDictionary],
      partitions: Int,
      intervals: Option[File],
      sampleTag: String,
      umiTag: Option[String],
      correctCells: Broadcast[IndexedSeq[String]],
      correctCellsMap: Broadcast[Map[String, Int]],
      cutoffs: Broadcast[Cutoffs],
      seqError: Float,
      writeRawVcf: Boolean = false,
      writeFilteredVcf: Boolean = true)(implicit sc: SparkContext): Result = {
    val regions =
      tenxkit.createRegions(inputFile, reference, partitions, intervals)

    val contigs = regions.groupBy(_.map(_.chr).distinct).map {
      case (contig, r) =>
        val all = createAllVariants(inputFile,
                                    reference,
                                    r,
                                    correctCellsMap,
                                    cutoffs,
                                    sampleTag,
                                    umiTag)
        val filter = filterVariants(all, seqError, cutoffs).cache()
        contig.headOption
          .map(_ -> ContigResult(all, filter))
          .getOrElse(throw new IllegalStateException("No contig found"))
    }

    val vcfHeader = sc.broadcast(tenxkit.vcfHeader(correctCells.value))

    //sc.setLocalProperty("spark.scheduler.pool", "high-prio")

    val writeFilterVcfFuture =
      if (writeFilteredVcf) {
          Thread.sleep(10000)
          sc.setLocalProperty("spark.scheduler.pool", "low-prio")
          val sorted = dict.value.getSequences
              .sortBy(_.getSequenceIndex)
              .flatMap(c =>
                contigs
                  .get(c.getSequenceName)
                  .map(x => c.getSequenceName -> Future(x.filteredVariants.sortBy(_.pos))))
          Some(Future.sequence(sorted.map(_._2)).map { r =>
            VariantCall.writeToPartitionedVcf(sc.union(r),
              new File(outputDir, "filter-vcf"),
              correctCells,
              dict,
              vcfHeader,
              seqError)
          }
        )
      } else None

    val writeAllVcfFuture = {
      if (writeRawVcf) {
        Thread.sleep(10000)
        sc.setLocalProperty("spark.scheduler.pool", "low-prio")
        val sorted = dict.value.getSequences
          .sortBy(_.getSequenceIndex)
          .flatMap(c =>
            contigs
              .get(c.getSequenceName)
              .map(x => c.getSequenceName -> Future(x.allVariants.sortBy(_.pos))))
        Some(Future.sequence(sorted.map(_._2)).map { r =>
          VariantCall.writeToPartitionedVcf(sc.union(r),
            new File(outputDir, "raw-vcf"),
            correctCells,
            dict,
            vcfHeader,
            seqError)
        }
        )
      }
      else None
    }

    Result(contigs, writeFilterVcfFuture, writeAllVcfFuture)
  }

  def filterVariants(variants: RDD[VariantCall],
                     seqError: Float = Args().seqError,
                     cutoffs: Broadcast[Cutoffs]): RDD[VariantCall] = {
    variants
      .flatMap(
        _.setAllelesToZeroPvalue(seqError, cutoffs.value.maxPvalue)
          .setAllelesToZeroDepth(cutoffs.value.minCellAlternativeDepth)
          .cleanupAlleles()
      )
      .filter(
        x =>
          x.hasNonReference &&
            x.altDepth >= cutoffs.value.minAlternativeDepth &&
            x.totalDepth >= cutoffs.value.minTotalDepth &&
            x.minSampleAltDepth(cutoffs.value.minCellAlternativeDepth))
  }

  def createAllVariants(inputFile: File,
                        reference: File,
                        regions: List[List[BedRecord]],
                        correctCellsMap: Broadcast[Map[String, Int]],
                        cutoffs: Broadcast[Cutoffs],
                        sampleTag: String = Args().sampleTag,
                        umiTag: Option[String] = Args().umiTag)(
      implicit sc: SparkContext): RDD[VariantCall] = {
    val dict = sc.broadcast(bam.getDictFromBam(inputFile))

    sc.parallelize(regions, regions.size)
      .mapPartitions { it =>
        it.flatMap { x =>
          x.flatMap { region =>
            val samReader =
              SamReaderFactory.makeDefault().open(inputFile)
            val fastaReader = new IndexedFastaSequenceFile(reference)

            new ReadBam(
              samReader,
              sampleTag,
              umiTag,
              region,
              dict.value,
              fastaReader,
              correctCellsMap.value,
              cutoffs.value.minBaseQual,
              cutoffs.value.minCellAlternativeDepth
            ).filter(
              x =>
                x.hasNonReference &&
                  x.altDepth >= cutoffs.value.minAlternativeDepth &&
                  x.totalDepth >= cutoffs.value.minTotalDepth &&
                  x.minSampleAltDepth(cutoffs.value.minCellAlternativeDepth))
          }
        }
      }
  }

  case class Key(sample: Int, allele: String, delBases: Int, umi: Option[Int])

  def descriptionText: String =
    """
      |This tool will call variants based on 10x data. Usually the output of cellranger is used.
      |Each cell will be treated a separated sample.
    """.stripMargin

  def manualText: String =
    """
      |The input data should have a tag to identify the sample. In cellranger output this is the 'CB' tag.
      |The variantcalling can take umi information into account. For this the option '--umiTag' must be given. When given the duplicates are still used.
      |If not given the reads marked as duplicate are ignored.
      |
      |The tool require a list of correct cells to use in the variantcalling. This file is a text file where each line is 1 sample/cell ID.
    """.stripMargin

  def exampleText: String =
    s"""
      |Default run without umi:
      |${TenxKit.sparkExample(
         "CellVariantcaller",
         "-i",
         "<input bam file>",
         "-o",
         "<output_dir>",
         "-R",
         "<reference fasta>",
         "--correctCells",
         "<txt file>",
         "--sparkMaster",
         "<spark master>"
       )}
      |
      |Run with umi aware:
      |${TenxKit.sparkExample(
         "CellVariantcaller",
         "-i",
         "<input bam file>",
         "-o",
         "<output_dir>",
         "-R",
         "<reference fasta>",
         "--correctCells",
         "<txt file>",
         "--sparkMaster",
         "<spark master>",
         "-u",
         "UB"
       )}
      |
    """.stripMargin
}
