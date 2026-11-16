package com.databricks.conf

import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.GuardedBy

import com.databricks.common.util.Lock.withLock
import com.databricks.testing.TestEnvironment.IS_TEST

/**
 * Creates a flag configured via the static service configuration (a.k.a. DB_CONF) that doesn't
 * change its value for the lifetime of the application.
 */
final class StaticFlag[T: Manifest] private (flagName: String, defaultValue: T) {

  /** The configured value, read from DB_CONF on first use. */
  private lazy val value: T = new StaticFlag.Conf(flagName, defaultValue).value

  /** Lock protecting [[testOnlyOverrideOpt]]. */
  private val lock: ReentrantLock = new ReentrantLock()

  /** The value set by [[withTestOnlyOverride]], if any. */
  @GuardedBy("lock")
  private var testOnlyOverrideOpt: Option[T] = None

  /** Returns the name of this flag. */
  def getFlagName: String = flagName

  /**
   * Returns the value from DB_CONF, or the default if it is not set. In tests, returns the
   * test-only override instead if one is set.
   */
  def staticValue: T = {
    if (IS_TEST) {
      withLock(lock) { testOnlyOverrideOpt.getOrElse(value) }
    } else {
      value
    }
  }

  /** Sets the value of the flag for the duration of the thunk. Has no effect outside of tests. */
  def withTestOnlyOverride[S](value: T)(thunk: => S): S = {
    if (IS_TEST) {
      withLock(lock) { testOnlyOverrideOpt = Some(value) }
      try {
        thunk
      } finally {
        withLock(lock) { testOnlyOverrideOpt = None }
      }
    } else {
      thunk
    }
  }
}

object StaticFlag {

  /**
   * Creates a flag configured via the static service configuration (a.k.a. DB_CONF) that doesn't
   * change its value for the lifetime of the application.
   *
   * @param flagName The name of the flag.
   * @param defaultValue The default value of the flag.
   *
   * {{{
   *  // MyClass.scala
   *  package com.databricks.mypackage
   *
   *  object MyClass {
   *    private[mypackage] val myFlag = StaticFlag[String]("mypackage.myFlag", "default-value")
   *  }
   *
   *  class MyClass {
   *    def myMethod(): String = MyClass.myFlag.staticValue
   *  }
   *
   *  // MyClassSuite.scala
   *  class MyClassSuite extends DatabricksTest {
   *    test("returns the default value") {
   *      val instance = new MyClass()
   *      assert(instance.myMethod() == "default-value")
   *      MyClass.myFlag.withTestOnlyOverride("test-only-override") {
   *        assert(instance.myMethod() == "test-only-override")
   *      }
   *    }
   *  }
   * }}}
   */
  def apply[T: Manifest](flagName: String, defaultValue: T): StaticFlag[T] = {
    new StaticFlag(flagName, defaultValue)
  }

  /** Reads `flagName` from DB_CONF, or returns `defaultValue` if it is not set. */
  private class Conf[T: Manifest](flagName: String, defaultValue: T) extends DbConfSingletonImpl {
    val value: T = configure(flagName, defaultValue)
  }
}
