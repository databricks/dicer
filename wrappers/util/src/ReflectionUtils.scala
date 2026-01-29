package com.databricks.backend.common.reflection

/** Provides a single utility method that safely extracts the simple name of a Scala/Java class. */
object ReflectionUtils {

  /** Gets the simple name of a class. In open source we simply delegate to Class#getSimpleName. */
  def getSimpleName(cls: Class[_]): String = {
    cls.getSimpleName
  }

}
