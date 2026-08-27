package com.databricks.logging.activity

import com.databricks.context.Ctx

/**
 * Minimal ActivityContextFactory that simply executes the thunk, without any activity context
 * tracking. All parameters exist for API compatibility but are ignored.
 */
object ActivityContextFactory {
  def withBackgroundActivity[S](
      onlyWarnOnAttrTagViolation: Boolean = false,
      addUserContextTags: Boolean = true)(thunk: Ctx => S): S = {
    // Simply execute the thunk without any background activity tracking.
    thunk(Ctx())
  }
}
