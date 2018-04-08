package nl.biopet.tools.tenxkit

import nl.biopet.utils.Histogram

class DistanceHistogram extends Histogram[Double] {
  def binned: DistanceHistogram = {
    val histogram = new DistanceHistogram
    counts.foreach(x =>
      histogram.addMulti(DistanceHistogram.getBin(x._1), x._2))
    histogram
  }
}

object DistanceHistogram {
  def getBin(value: Double): Double = {
    (value * 10).round.toDouble / 10
  }
}
