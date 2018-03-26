package nl.biopet.tools.tenxkit.variantcalls

import htsjdk.samtools.{CigarOperator, TextCigarCodec}

import scala.collection.JavaConversions._
import scala.collection.mutable

object SampleRead {
  def sampleBases(start: Long,
                  sample: Int,
                  strand: Boolean,
                  sequence: Array[Byte],
                  quality: Array[Byte],
                  cigar: String): List[(SamplePosition, SampleBase)] = {
    val seqIt = sequence.zip(quality).toList.toIterator

    val referenceBuffer = mutable.Map[Long, SampleBase]()
    var refPos = start
    for (element <- TextCigarCodec.decode(cigar)) {
      element.getOperator match {
        case CigarOperator.SOFT_CLIP =>
          seqIt.drop(element.getLength)
        case CigarOperator.MATCH_OR_MISMATCH | CigarOperator.EQ |
            CigarOperator.X =>
          seqIt.take(element.getLength).foreach {
            case (base, qual) =>
              referenceBuffer += refPos -> SampleBase(base.toChar.toString,
                                                      strand,
                                                      qual.toByte :: Nil)
              refPos += 1
          }
        case CigarOperator.INSERTION =>
          val seq = seqIt.take(element.getLength).toList
          referenceBuffer.get(refPos - 1) match {
            case Some(b) =>
              referenceBuffer += (refPos - 1) -> b.copy(
                allele = b.allele ++ seq.map(_._1.toChar),
                qual = b.qual ++ seq.map(_._2.toByte))
            case _ =>
              throw new IllegalStateException(
                "Insertion without a base found, cigar start with I (or after the S/H)")
          }
        case CigarOperator.DELETION =>
          referenceBuffer.get(refPos - 1) match {
            case Some(b) =>
              referenceBuffer += (refPos - 1) -> b.copy(
                delBases = b.delBases + element.getLength)
            case _ =>
              throw new IllegalStateException(
                "Deletion without a base found, cigar start with D (or after the S/H)")
          }
          (refPos to (element.getLength + refPos)).foreach(p =>
            referenceBuffer += p -> SampleBase("", strand, Nil))
          refPos += element.getLength
        case CigarOperator.SKIPPED_REGION                    => refPos += element.getLength
        case CigarOperator.HARD_CLIP | CigarOperator.PADDING =>
      }
    }
    require(!seqIt.hasNext, "After cigar parsing sequence is not depleted")
    referenceBuffer.toList
      .map { case (k, v) => SamplePosition(sample, k) -> v }
      .sortBy(_._1.position)
  }
}
