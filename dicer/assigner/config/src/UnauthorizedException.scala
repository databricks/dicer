package com.databricks.dicer.assigner.config

/** Thrown when a request is not authorized to register as a Dicer target. */
final class UnauthorizedException(message: String) extends Exception(message)
