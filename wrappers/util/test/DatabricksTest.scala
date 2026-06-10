package com.databricks.testing

import org.scalatest._
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterAll
import com.typesafe.scalalogging.Logger

/**
 * Open source version of DatabricksTest that provides the same interface as the internal version.
 * This extends AnyFunSuite and includes before and after all hooks.
 */
abstract class DatabricksTest extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  protected val logger: Logger = Logger(getClass.getName)

  /**
   * Runs a test for each parameter in the collection, appending the parameter value to the test
   * name.
   */
  protected def gridTest[A](testNamePrefix: String, testTags: Tag*)(params: Traversable[A])(
      testFun: A => Unit
  ): Unit = {
    for (param <- params) {
      test(testNamePrefix + s" ($param)", testTags: _*)(testFun(param))
    }
  }

  /**
   * Runs a test for each named parameter in the collection, appending the parameter name to the
   * test name.
   */
  protected def namedGridTest[A](testNamePrefix: String, testTags: Tag*)(
      params: Traversable[(String, A)]
  )(testFun: A => Unit): Unit = {
    for ((paramName, param) <- params) {
      test(testNamePrefix + s" ($paramName)", testTags: _*)(testFun(param))
    }
  }
}
