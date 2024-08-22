package dev.convex.android

import kotlin.Exception

/**
 * An exception that may be thrown or included in a [Result] when an unexpected error occurs calling
 * a Convex backend function.
 */
class ServerError(message: String, cause: Exception? = null) : Exception(message, cause) {
    companion object {
        fun from(exception: ClientException.ServerException): ServerError =
            ServerError(exception.msg, exception)
    }
}

/**
 * An exception that may be thrown or included in a [Result] when a `ConvexError` is thrown in a
 * backend function.
 *
 * A [ConvexError] represents an application specific error and the [data] field may carry JSON
 * encoded application specific payload. In simple cases it is a JSON encoded [String], but it can
 * be any sort of JSON object you want to include to carry information about the error.
 *
 * See the
 * [published documentation](https://docs.convex.dev/functions/error-handling/application-errors)
 * for more information.
 */
class ConvexError(message: String, val data: String, cause: Exception? = null) :
    Exception(message, cause) {
    companion object {
        fun from(exception: ClientException.ConvexException): ConvexError =
            ConvexError(exception.message, exception.data, exception)
    }
}

/**
 * An exception thrown when an internal error occurs in the mobile Convex client code.
 */
class InternalError(message: String, cause: Exception? = null) : Exception(message, cause) {
    companion object {
        fun from(exception: ClientException.InternalException): InternalError =
            InternalError(exception.msg, exception)
    }
}

fun ClientException.toError() = when (this) {
    is ClientException.ConvexException -> ConvexError.from(this)
    is ClientException.InternalException -> InternalError.from(this)
    is ClientException.ServerException -> ServerError.from(this)
}