package com.databricks.caching.util

import scala.collection.mutable

import com.databricks.caching.util.AssertMacros.iassert

/**
 * Base class for elements stored in [[IntrusiveMinHeap]]. Includes state determining the ordering
 * of elements (by priority then push order), as well as the location of the element in the heap
 * to support efficient setPriority and remove implementations.
 *
 * When created, an element is "detached". It can be pushed onto a heap (at most one at a time), at
 * which point it is considered attached. Pushing an element that is already attached is not
 * permitted.
 *
 * Not thread-safe. All interactions with the heap, either direct or via the element class, must be
 * synchronized.
 */
trait IntrusiveMinHeapElement[Priority] {

  /** The heap to which this element is attached, or null if the element is currently detached. */
  private[util] final var heap: IntrusiveMinHeap[_, _] = _

  /** The index of this element in the heap array. Undefined when [[heap]] is null. */
  private[util] final var index: Int = _

  /** The priority of this element in the heap. */
  private[util] final var priority: Priority = _

  /** The order in which this element was pushed onto the heap. Undefined when [[heap]] is null. */
  private[util] final var pushOrder: Long = _

  /**
   * By default, returns a representation of the heap element for debugging. Callers may override to
   * their own representation.
   */
  override def toString: String = debugString

  /** Returns whether this element is attached to a heap. */
  protected final def attached: Boolean = heap != null

  /** Gets the priority of this element in the heap. */
  protected final def getPriority: Priority = priority

  /**
   * Sets the priority of this element. If this element is currently attached to a heap, its
   * position in the heap is updated as needed. Callers must synchronize all interactions with the
   * heap.
   *
   * O(logn) operation.
   */
  protected final def setPriority(priority: Priority): Unit = {
    this.priority = priority
    if (heap != null) {
      heap.updatePriority(index)
    }
  }

  /**
   * REQUIRES: attached
   *
   * Removes this element from the heap. Callers must synchronize all interactions
   * with the heap.
   *
   * O(logn) operation.
   */
  protected final def remove(): Unit = {
    require(attached, "Can only remove attached element")
    heap.remove(index)
  }

  /** The debug string representation of the heap element. */
  // Consider making this protected so that callers can still include this information when
  // defining their own debug strings.
  private final def debugString: String =
    if (heap == null) s"detached,pri=$priority"
    else s"pri=$priority,pushOrder=$pushOrder,index=$index"

  private[util] object forTest {

    /** Checks data structure invariants for the heap in which this element is contained. */
    def checkInvariants(): Unit = {
      iassert(heap != null == attached)

      if (heap != null) {
        heap.forTest.checkInvariants()
      }
    }
  }
}

/**
 * A binary heap data structure whose elements track their location in the heap to support efficient
 * removal and priority updates.
 *
 * The element with the lowest priority value is at the top of the heap (hence "min-heap"). If
 * multiple elements in the heap have the same priority, the element that was first pushed takes
 * precedence.
 *
 * Because of the need to maintain locations, this abstraction is "intrusive", i.e., it requires
 * elements to carry some additional state around with them by extending the
 * [[IntrusiveMinHeapElement]] base class. Additional complications relative to a basic heap:
 *
 *  - If an element is contained in a heap, it cannot be pushed again (either onto the same heap
 *    or a different heap) unless it is first removed or popped, since only a single location is
 *    maintained for each element.
 *  - Elements must be refs, not values, since the implementation relies on reference stability.
 *
 * Not thread-safe. External synchronization is required when modifying the heap, either directly
 * via [[push]] and [[pop]] or indirectly via [[IntrusiveMinHeapElement.setPriority]] and
 * [[IntrusiveMinHeapElement.remove]].
 */
class IntrusiveMinHeap[Priority, Element <: IntrusiveMinHeapElement[Priority]](
    implicit ordering: Ordering[Priority]) {
  import IntrusiveMinHeap.{getLeftChildIndex, getParentIndex}

  /**
   * Contains all heap elements. Based on the index of an element in the heap, you can determine
   * the indices of its parent (see [[getParentIndex]]) and children (see [[getLeftChildIndex]]).
   * The critical invariant maintained in the heap is that an element always has priority and
   * insertion order such that it is ordered before its children (in this min-heap, elements are
   * ordered by increasing priority and increasing insertion order). A valid heap is illustrated
   * below:
   *
   * <pre>
   *                            heap(0) pri=1
   *                          /               \
   *           heap(1) pri=10                   heap(2) pri=2
   *         /                \               /               \
   *   heap(3) pri=12    heap(4) pri=13     heap(5) pri=4     heap(6) pri=3
   * </pre>
   */
  private val heap = new mutable.ArrayBuffer[Element]

  /**
   * To support insertion order stability, each element is assigned a push order when it is attached
   * to the heap. Whenever an element is attached, its push order is taken from this field, which
   * is then incremented to ensure that every element has a unique, increasing push order.
   */
  private var pushOrderSequencer: Long = 0

  /** The number of elements in this heap. */
  def size: Int = heap.size

  /** Whether the heap is empty (contains no elements). */
  def isEmpty: Boolean = heap.isEmpty

  /** Whether the heap is non-empty (contains at least one element). */
  def nonEmpty: Boolean = heap.nonEmpty

  /**
   * Returns the element with the lowest priority in the heap. In case of ties, the element that was
   * first pushed onto the heap is removed. Returns None if the heap is empty.
   */
  def peek: Option[Element] = if (heap.isEmpty) None else Some(heap(0))

  /**
   * REQUIRES: `nonEmpty`
   *
   * Removes and returns the element with the lowest priority in the heap. In case of ties, the
   * element that was first pushed onto the heap is removed.
   */
  def pop(): Element = {
    require(heap.nonEmpty, "Can only pop an element from a non-empty heap")
    val top: Element = heap(0)
    remove(0)
    top
  }

  /**
   * REQUIRES: `elem` must not already be attached to this or some other heap.
   *
   * Pushes the given element onto this heap.
   */
  def push(elem: Element): Unit = {
    require(elem.heap == null, "Can only push detached element")
    heap.append(elem)

    // Tell the element where it is located.
    elem.heap = this
    elem.index = heap.size - 1

    // Assign push order to the element to break ties if it has the same priority as some other
    // element in this heap.
    elem.pushOrder = pushOrderSequencer
    pushOrderSequencer += 1

    // Restore the heap property, which may be violated for the element we just appended.
    siftUp(elem)
  }

  /**
   * Returns an iterator which traverses the elements in the heap in an arbitrary order.
   *
   * Modifications to the heap invalidate outstanding iterators, which should not be used
   * afterwards.
   */
  def unorderedIterator(): Iterator[Element] = {
    heap.iterator
  }

  /**
   * REQUIRES: index is in [0, size).
   *
   * Removes the element at index from this heap. Returns false if the element is not in the heap.
   */
  private[util] def remove(index: Int): Unit = {
    val elem: Element = heap(index)

    // Swap `elem` with the element at the end of the heap.
    val replacement: Element = heap.last
    swap(elem, replacement) // no-op if `elem` is `replacement`

    // Detach `elem` so that it no longer believes itself to be in this heap.
    elem.heap = null

    // Truncate to exclude `elem` (which was just swapped to the end of the heap).
    heap.remove(heap.size - 1)
    if (elem eq replacement) {
      // Normally, the replacement element needs to be fixed up after being swapped with the removed
      // element. Skip this step if the replacement element is also the removed element (not
      // possible to "sift" an element that is not attached).
      return
    }
    // Restore heap property for the moved element, which may have lower priority than its parent
    // (requiring siftUp) or higher priority than one its children (requiring siftDown). We could
    // call siftUp and siftDown in either order.
    siftUp(replacement)
    siftDown(replacement)
  }

  /**
   * REQUIRES: index is in [0, size)
   *
   * REQUIRES: heap property holds for all elements exception possibly the element at `index`.
   *
   * Restores heap property for the element at index after its priority has been updated.
   */
  private[util] def updatePriority(index: Int): Unit = {
    val elem: Element = heap(index)

    // Restore heap property for the updated element, which may now have lower priority than its
    // parent (requiring siftUp) or higher priority than one its children (requiring siftDown). We
    // could call siftUp and siftDown in either order.
    siftUp(elem)
    siftDown(elem)
  }

  /**
   * REQUIRES: `elem` is attach to this heap.
   *
   * REQUIRES: heap property possibly does not hold locally for `elem` but holds for the rest of the
   * heap.
   *
   * While `elem` is ordered after one of its children -- violating the heap property -- swap it
   * with its minimum child.
   *
   * NOTE: Cormen-Leiserson-Rivest-Stein refers to this function as HEAPIFY.
   */
  private def siftDown(elem: Element): Unit = {
    iassert(elem.heap eq this, "elem must be attached to this heap")
    while (true) {
      val leftIndex: Int = getLeftChildIndex(elem.index)
      if (leftIndex >= heap.size) {
        return // no children, can't sift down any further
      }

      // Find the minimum child.
      val leftChild: Element = heap(leftIndex)
      val rightIndex: Int = leftIndex + 1
      var minChild: Element = leftChild
      if (rightIndex < heap.size) {
        val rightChild: Element = heap(rightIndex)
        if (lessThan(rightChild, leftChild)) {
          minChild = rightChild
        }
      }
      // If `elem` is ordered after its minimum child, swap to restore the heap property locally and
      // continue sifting down. Otherwise, we're done.
      if (!lessThan(minChild, elem)) { // minChild >= elem
        return
      }
      swap(minChild, elem)
    }
  }

  /**
   * REQUIRES: `elem` is in this heap.
   *
   * REQUIRES: heap property possibly does not hold locally for `elem` but holds for the rest of the
   * heap.
   *
   * While `elem` is not at the top of the heap and is ordered before its parent -- violating the
   * heap property -- swap it with its parent.
   *
   * NOTE: Cormen-Leiserson-Rivest-Stein refers to this function as HEAP-INCREASE-KEY (in the
   * context of max-heap).
   */
  private def siftUp(elem: Element): Unit = {
    iassert(elem.heap eq this, "elem must be attached to this heap")
    while (elem.index > 0) {
      val parentIndex: Int = getParentIndex(elem.index)
      val parent: Element = heap(parentIndex)

      // If `elem` is ordered before its parent, swap to restore the heap property locally and
      // continue sifting up. Otherwise, we're done.
      if (!lessThan(elem, parent)) { // elem >= parent
        return
      }
      swap(elem, parent)
    }
  }

  /**
   * Swaps the given elements in the heap and fixes up the index back-pointers maintained within
   * the elements.
   */
  private def swap(x: Element, y: Element): Unit = {
    val xIndex: Int = x.index
    val yIndex: Int = y.index
    heap(xIndex) = y
    heap(yIndex) = x
    x.index = yIndex
    y.index = xIndex
  }

  /** Determines whether `x` is ordered before `y`, by priority and then push order. */
  private def lessThan(
      x: IntrusiveMinHeapElement[Priority],
      y: IntrusiveMinHeapElement[Priority]): Boolean = {
    val priorityOrdering = ordering.compare(x.priority, y.priority)
    (priorityOrdering < 0) || (priorityOrdering == 0 && x.pushOrder < y.pushOrder)
  }

  object forTest {

    /** Asserts data structure invariants (heap property and element location consistency). */
    def checkInvariants(): Unit = {
      for (i <- heap.indices) {
        val elem = heap(i)
        iassert(elem.index == i, "wrong index location")
        iassert(elem.heap eq IntrusiveMinHeap.this, "wrong heap location")
        if (i > 0) {
          val parent = heap(getParentIndex(i))
          iassert(
            lessThan(parent, elem),
            "heap property violated (total ordering expected because of push order)"
          )
        }
      }
    }

    /** Returns copy of the raw heap contents. */
    def copyContents(): List[Element] = heap.toList
  }
}

/** Companion object containing helpers for the heap. */
private object IntrusiveMinHeap {

  /** Returns the index of the parent of the element at the given index. */
  def getParentIndex(index: Int): Int = (index - 1) / 2

  /** Returns the index of the left child of the element at the given index. */
  def getLeftChildIndex(index: Int): Int = index * 2 + 1
}
