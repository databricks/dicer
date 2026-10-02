package com.databricks.dicer.client

import com.databricks.dicer.client.testing.{SliceLookupHarness}

/**
 * Tests for the Scala implementation of the slice lookup driver where the Slicelet is not
 * watching from the Data Plane.
 *
 * @note There is no concept of the 'Data Plane' in the open-source version of Dicer.
 */
class ScalaSliceLookupSuite extends ScalaSliceLookupSuiteBase(watchFromDataPlane = false) {

}
