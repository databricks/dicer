package com.databricks.caching.util

import java.io.File

import com.databricks.caching.util.ConfigTestUtil.ConfigWriter
import com.databricks.testing.DatabricksTest

class ConfigTestUtilSuite extends DatabricksTest {

  /** Returns the contents of the file at `relativePath` under `directory`. */
  private def readFile(directory: File, relativePath: String): String = {
    os.read(os.Path(directory) / os.SubPath(relativePath))
  }

  test("`ConfigWriter` starts empty and reads back the contents written to each directory") {
    // Test plan: Verify that a new writer owns two distinct empty directories, that writing a
    // primary config leaves the advanced directory empty, and that the same filename written to
    // both directories reads back its own contents.
    val writer = new ConfigWriter

    assert(writer.getConfigDirectory != writer.getAdvancedConfigDirectory)
    assert(os.list(os.Path(writer.getConfigDirectory)).isEmpty)
    assert(os.list(os.Path(writer.getAdvancedConfigDirectory)).isEmpty)

    writer.writeConfig("target.textproto", "target")
    writer.writeConfig("other.textproto", "other")

    assert(os.list(os.Path(writer.getAdvancedConfigDirectory)).isEmpty)

    writer.writeAdvancedConfig("target.textproto", "advanced")

    assertResult("target")(readFile(writer.getConfigDirectory, "target.textproto"))
    assertResult("other")(readFile(writer.getConfigDirectory, "other.textproto"))
    assertResult("advanced")(readFile(writer.getAdvancedConfigDirectory, "target.textproto"))
  }

  test("A nested filename creates the missing parent directories") {
    // Test plan: Write configs several path segments deep into directory trees that do not exist
    // yet, in both the primary and the advanced directory.
    val writer = new ConfigWriter

    writer.writeConfig("a/b/nested.textproto", "nested")
    writer.writeConfig("a/shallow.textproto", "shallow")
    writer.writeAdvancedConfig("a/b/nested.textproto", "advanced nested")

    assertResult("nested")(readFile(writer.getConfigDirectory, "a/b/nested.textproto"))
    assertResult("shallow")(readFile(writer.getConfigDirectory, "a/shallow.textproto"))
    assertResult("advanced nested")(
      readFile(writer.getAdvancedConfigDirectory, "a/b/nested.textproto")
    )
  }

  test("Separate `ConfigWriter` instances do not share directories") {
    // Test plan: Have two writers write the same filename with different contents, and verify that
    // neither observes the other's file.
    val first = new ConfigWriter
    val second = new ConfigWriter

    first.writeConfig("target.textproto", "first")
    second.writeConfig("target.textproto", "second")
    first.writeAdvancedConfig("advanced.textproto", "first advanced")

    assert(first.getConfigDirectory != second.getConfigDirectory)
    assertResult("first")(readFile(first.getConfigDirectory, "target.textproto"))
    assertResult("second")(readFile(second.getConfigDirectory, "target.textproto"))
    assert(!new File(second.getAdvancedConfigDirectory, "advanced.textproto").exists())
  }
}
