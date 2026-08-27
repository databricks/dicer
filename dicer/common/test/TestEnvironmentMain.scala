package com.databricks.dicer.common

import java.nio.file.{Files, Path, Paths, StandardOpenOption}

import scala.collection.mutable

import mainargs.{ParserForMethods, arg, main}

import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.common.web.InfoService
import com.databricks.conf.Constants.INFO_SERVICE_PORT
import com.databricks.dicer.client.TestClientUtils
import com.databricks.dicer.external.{ClerkConf, DicerTestEnvironment, Slicelet, Target}

/**
 * An object that runs an Assigner and some number of Clerks/Slicelets on the local machine. One can
 * look at the command-line arguments using --help. Here are some sample commands (you can also
 * simply run the binary from bazel-bin):
 *
 * Command to run with 3 slicelets and 2 Clerks and default target name.
 * bazel run dicer/common:dicer_env -- --slicelets 3 --clerks 2
 *
 * Command to run with 3 slicelets, 2 Clerks with a target name "NewTarget".
 * bazel run dicer/common:dicer_env -- --slicelets 3 --clerks 2 --target "NewTarget"
 *
 * You can check on the zPage http://localhost:7777/admin/debug about the state of Assigner, Clerks
 * and Slicelets.
 */
object TestEnvironmentMain {
  // Default Target name and number of Clerks/Slicelets
  val DEFAULT_TARGET: String = "my-target"
  val DEFAULT_CLERKS: Int = 1
  val DEFAULT_SLICELETS: Int = 2
  val DEFAULT_ASSIGNER_PORT_FILE: String = "/tmp/dicer_assigner_port.txt"

  private var internalTestEnv: InternalDicerTestEnvironment = _

  /**
   * Write the Assigner port to a file so that other binaries that want it can use it.
   *
   * @param assignerPortFile Path of the file in which the port is written
   * @param assignerPort The port of the Assigner
   */
  def writeAssignerPort(assignerPortFile: String, assignerPort: Int): Unit = {
    val assignerPortFilePath: Path = Paths.get(assignerPortFile)
    Files.write(assignerPortFilePath, assignerPort.toString.getBytes(), StandardOpenOption.CREATE)
  }

  /** Reads the Assigner port that was written by [[writeAssignerPort]]. */
  def readAssignerPort(assignerPortFile: String): Int = {
    val assignerPortFilePath: Path = Paths.get(assignerPortFile)
    val contents: java.util.List[String] = Files.readAllLines(assignerPortFilePath)
    iassert(contents.size() == 1)
    contents.get(0).toInt
  }

  /** Returns the Assigner's port as chosen by `dicerEnv`. */
  def getAssignerPort(dicerEnv: DicerTestEnvironment): Int = dicerEnv.getAssignerPort

  /**
   * The command that is run in main when the binary starts up. The command-line args are parsed
   * using the `mainargs` library, which is documented at https://github.com/com-lihaoyi/mainargs.
   */
  @main
  def run(
      @arg(doc = "Target description (default: my-target)")
      target: String = DEFAULT_TARGET,
      @arg(doc = "No. of Clerks to run (default: 1)")
      clerks: Int = DEFAULT_CLERKS,
      @arg(doc = "No. of Slicelets to run (default: 2)")
      slicelets: Int = DEFAULT_SLICELETS,
      @arg(doc = "InfoService port (default: 7777)")
      infoServicePort: Int = INFO_SERVICE_PORT,
      @arg(doc = "Whether to run the Assigner (default: true)")
      runAssigner: Boolean = true,
      @arg(doc = "File where the Assigner port is written (default: /tmp/dicer_assigner_port.txt)")
      assignerPortFile: String = DEFAULT_ASSIGNER_PORT_FILE): Unit = {
    require(slicelets > 0, "must be configured to run at least one Slicelet")
    val parsedTarget: Target = TargetHelper.parse(target)

    val (assignerPort, infoPort): (Int, Int) =
      if (runAssigner) {
        // Create a test environment. The default test environment configuration quickly produces an
        // initial assignment.
        this.internalTestEnv = InternalDicerTestEnvironment.create(
          dPageNamespaceOpt = Some("dicer")
        )
        val assignerPort: Int = this.internalTestEnv.getAssignerPort
        writeAssignerPort(assignerPortFile, assignerPort)
        (assignerPort, infoServicePort)
      } else {
        // Read the assigner port from local file.
        val assignerPort: Int = readAssignerPort(assignerPortFile)

        // If running without assigner, try to use a different port rather than forcing the user
        // to explicitly specifying the port (presumably, the environment is using 7777).
        val infoPort: Int = infoServicePort + 1
        (assignerPort, infoPort)
      }
    // Initialize the InfoService so that we have zPages and then setup the Assigner's Slicez.
    InfoService.start(infoPort)

    // Create the requested number of Slicelets.
    val startedSlicelets = new mutable.ArrayBuffer[Slicelet]
    for (i <- 0 until slicelets) {
      // selfPort is arbitrary as we're not running an actual RPC service.
      val selfPort: Int = 8080 + i
      val slicelet: Slicelet =
        TestClientUtils
          .createSlicelet(
            assignerPort,
            parsedTarget,
            "localhost",
            clientTlsFilePathsOpt = None,
            serverTlsFilePathsOpt = None,
            watchFromDataPlane = false
          )
          .start(selfPort, listenerOpt = None)
      startedSlicelets.append(slicelet)
    }
    // Create the requested number of Clerks. Clerks connect with arbitrary Slicelets.
    for (i <- 0 until clerks) {
      val slicelet: Slicelet = startedSlicelets(i % startedSlicelets.size)
      val clerkConf: ClerkConf =
        TestClientUtils.createTestClerkConf(
          slicelet.impl.forTest.sliceletPort,
          clientTlsFilePathsOpt = None
        )
      TestClientUtils.createClerk(parsedTarget, clerkConf)
    }
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)

  object forTest {
    def internalTestEnv: InternalDicerTestEnvironment = TestEnvironmentMain.internalTestEnv
  }
}
