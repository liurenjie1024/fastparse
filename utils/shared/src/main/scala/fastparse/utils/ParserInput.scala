package fastparse.utils

import fastparse.utils.Utils.IsReachable

import scala.reflect.ClassTag

/**
  * ParserInput class represents data that is needed to parse.
  *
  * It can be regular `IndexedSeq` that behaves as simple array or
  * `Iterator` of `IndexedSeq` batches which is optimized by `dropBuffer` method.
  */
abstract class ParserInput[Elem, Repr] extends IsReachable[Elem] {
  def apply(index: Int): Elem

  /**
    * Special method for `Iterator` mode. It drops the prefix of the internal buffer
    * so that all the data '''strictly before''' becomes unavailable and `index` is the first valid element to access.
    */
  def dropBuffer(index: Int): Unit

  /**
    * @return Slice of internal data.
    *         For `IndexedSeq` mode it works as regular slice, if `until` overshoots the end of input,
    *         it just ignores it and behaves like `until` equals to the length of input.
    *         Same for `Iterator` mode, but it requests batches while the index of last retrieved element is less than `until`
    *         and if `until` is farther away than any element, it ignores this too.
    */
  def slice(from: Int, until: Int): Repr

  def length: Int
  def innerLength: Int

  /**
    * Shows if we can access to the element at given `index`.
    */
  def isReachable(index: Int): Boolean

  val formatter: ReprOps[Elem, Repr]

  def checkTraceable(): Unit
}

case class IndexedParserInput[Elem, Repr](data: Repr)
                                             (implicit val formatter: ReprOps[Elem, Repr])
    extends ParserInput[Elem, Repr] {
  override def apply(index: Int) = formatter.apply0(data, index)

  /**
    * As for `IndexedSeq` mode `dropBuffer` does nothing.
    */
  override def dropBuffer(index: Int) = {}

  override def slice(from: Int, until: Int) = formatter.slice0(data, from, until)

  /**
    * @return Length of internal immutable data. It works equally as `innerLength`.
    */
  override def length: Int = formatter.length0(data)
  /**
    * @return Length of internal immutable data. It works equally as `length`.
    */
  override def innerLength: Int = length

  /**
    * Simple condition of `index` < `length`
    */
  override def isReachable(index: Int): Boolean = index < length

  def checkTraceable() = ()
}

/**
  * Contains buffer - queue of elements that extends from given iterator when particular elements are requested;
  * and shrinks by calling `dropBuffer` method.
  *
  * Generally, at any specific time this buffer contains "suffix" of given iterator,
  * e.g. some piece of data from past calls of `next`, which extends by requesting new batches from iterator.
  * Therefore we can denote the same notation of indices as for regular `Array` or more abstract `IndexedSeq`.
  * The only difference is when index doesn't fit into the bounds of current buffer
  * either the new batches are requested to extend the buffer, either it's inaccessible at all,
  * so calling of `dropBuffer` should guarantee that there won't be any attempts to access to the elements in dropped part of input.
  */
case class IteratorParserInput[Elem, Repr](data: Iterator[Repr])
                                          (implicit val formatter: ReprOps[Elem, Repr],
                                           converter: ReprOps[Elem, Repr],
                                           ct: ClassTag[Elem])
    extends ParserInput[Elem, Repr] {
  private val buffer: UberBuffer[Elem] = new UberBuffer[Elem](16)
  private var firstIdx: Int = 0 // index in the data corresponding to the 0th element in the buffer

  private def requestUntil(until: Int): Boolean = {
    while (this.length <= until && data.hasNext) {
      val chunk = data.next()
      buffer.write(converter.toArray(chunk))
    }
    this.length > until
  }

  /**
    * Requests batches until given `index` and in case of success returns needed element.
    */
  override def apply(index: Int): Elem = {
    requestUntil(index)
    buffer(index - firstIdx)
  }

  /**
    * Drops all elements before index not inclusive.
    */
  override def dropBuffer(index: Int): Unit = {
    if (index > firstIdx) {
      buffer.drop(index - firstIdx)
      firstIdx = index
    }
  }

  /**
    * Also requests batches to the `until`.
    * If the current buffer is too small to provide some part of data after `from` bound,
    * lower bound of slice is cut to the minimum of `from` and first index of accessible element in the buffer.
    */
  override def slice(from: Int, until: Int): Repr = {
    requestUntil(until - 1)
    val lo = math.max(from, firstIdx)
    converter.fromArray(buffer.slice(lo - firstIdx, until - firstIdx))
  }

  /**
    * @return Index of the last element which buffer contains.
    */
  override def length: Int = firstIdx + buffer.length

  /**
    * @return Length of the internal buffer.
    */
  override def innerLength: Int = buffer.length

  /**
    * Requests batches until index of last retrieved element is less than `index` and,
    * when it's possible, returns true, otherwise false.
    */
  override def isReachable(index: Int): Boolean = {
    index < length || requestUntil(index)
  }

  def checkTraceable() = throw new RuntimeException(
    "Cannot perform `.traced` on an `IteratorParserInput`, as it needs to parse " +
    "the input a second time to collect traces, which is impossible after an " +
    "`IteratorParserInput` is used once and the underlying Iterator exhausted."
  )
}

